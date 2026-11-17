/*
 * Copyright (C) 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.FakeCameraDevice
import android.util.Range
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.sessioncompat.ICameraCaptureSessionCompat
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.utils.StubLogger
import io.github.thibaultbee.streampack.core.logger.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * A camera lost to another client (or refused) is an interruption: it reopens when the camera is
 * available again, and nothing else opens it in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CameraControllerInterruptionTest {
    init {
        Logger.logger = StubLogger()
    }

    /** A fake camera service behind a mocked [ICameraCaptureSessionCompat]. */
    private class FakeCamera {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager: CameraManager = context.getSystemService(CameraManager::class.java)
        val compat = mockk<ICameraCaptureSessionCompat>(relaxed = true)
        val openCallbacks = mutableListOf<CameraDevice.StateCallback>()
        val sessionCallbacks = mutableListOf<CameraCaptureSession.StateCallback>()
        val sessions = mutableListOf<CameraCaptureSession>()
        val availability = mutableListOf<CameraManager.AvailabilityCallback>()
        val devices = mutableListOf<FakeCameraDevice>()
        val requests = mutableMapOf<CameraCaptureSession, CaptureRequest>()
        var failCreateRequest: Throwable? = null
        var failCreateSession: Throwable? = null
        var failRepeating: Throwable? = null
        val repeatingCount = mutableMapOf<CameraCaptureSession, Int>()
        val closedDevices = mutableListOf<FakeCameraDevice>()

        /** Holds each device's onClosed until [finishCloses], like a HAL that takes its time. */
        var deferCloses = false

        /** Runs first in each device's close, on the closing thread. */
        var beforeClose: (() -> Unit)? = null
        private val pendingCloses = mutableListOf<() -> Unit>()

        private val sessionsOf = mutableMapOf<CameraDevice, MutableList<CameraCaptureSession>>()
        private val callbackOf = mutableMapOf<CameraDevice, CameraDevice.StateCallback>()

        val characteristics: CameraCharacteristics =
            ShadowCameraCharacteristics.newCameraCharacteristics().also {
                shadowOf(it).set(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                    arrayOf(Range(30, 30))
                )
            }

        init {
            shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
            shadowOf(manager).addCamera("0", characteristics)
            shadowOf(manager).addCamera("1", characteristics)
            every { compat.openCamera(any(), any(), any()) } answers {
                openCallbacks += thirdArg<CameraDevice.StateCallback>()
            }
            every { compat.createCaptureSessionByOutputConfiguration(any(), any(), any()) } answers {
                failCreateSession?.let { failCreateSession = null; throw it }
                sessionCallbacks += thirdArg<CameraCaptureSession.StateCallback>()
            }
            val session = slot<CameraCaptureSession>()
            val request = slot<CaptureRequest>()
            every {
                compat.setRepeatingSingleRequest(capture(session), capture(request), any())
            } answers {
                failRepeating?.let { failRepeating = null; throw it }
                requests[session.captured] = request.captured
                repeatingCount[session.captured] = (repeatingCount[session.captured] ?: 0) + 1
                0
            }
            every { compat.registerAvailabilityCallback(any(), any()) } answers {
                availability += secondArg<CameraManager.AvailabilityCallback>()
            }
            every { compat.unregisterAvailabilityCallback(any(), any()) } answers {
                availability -= secondArg<CameraManager.AvailabilityCallback>()
            }
        }

        val opens get() = openCallbacks.size

        /** Answers the last open with a new device. */
        fun open(id: String = "1"): FakeCameraDevice {
            val callback = openCallbacks.last()
            val device = FakeCameraDevice(id, {
                failCreateRequest?.let { failCreateRequest = null; throw it }
                newRequestBuilder(id)
            }) { device ->
                beforeClose?.invoke()
                closedDevices += device
                sessionsOf.remove(device)?.forEach { it.close() }
                if (deferCloses) pendingCloses += { callback.onClosed(device) } else callback.onClosed(device)
            }
            callbackOf[device] = callback
            devices += device
            callback.onOpened(device)
            return device
        }

        /** Answers the last open with an error, like a camera held by a higher-priority client. */
        fun refuse() {
            val device = FakeCameraDevice("1", { throw UnsupportedOperationException() }) {}
            openCallbacks.last().onError(device, CameraDevice.StateCallback.ERROR_CAMERA_IN_USE)
        }

        /** Answers the last session creation. */
        fun configure() {
            val callback = sessionCallbacks.last()
            val device = devices.last()
            val session = mockk<CameraCaptureSession>(relaxed = true)
            var closed = false
            every { session.close() } answers {
                if (!closed) {
                    closed = true
                    callback.onClosed(session)
                }
            }
            sessionsOf.getOrPut(device) { mutableListOf() } += session
            sessions += session
            callback.onConfigured(session)
        }

        /** Another client takes the camera. */
        fun evict(device: FakeCameraDevice = devices.last()) {
            callbackOf.getValue(device).onDisconnected(device)
        }

        /** The device fails, as a HAL or camera service error reports it. */
        fun fail(
            device: FakeCameraDevice = devices.last(),
            error: Int = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
        ) {
            callbackOf.getValue(device).onError(device, error)
        }

        fun finishCloses() {
            pendingCloses.toList().also { pendingCloses.clear() }.forEach { it() }
        }

        fun available(id: String) = availability.toList().forEach { it.onCameraAvailable(id) }

        fun unavailable(id: String) = availability.toList().forEach { it.onCameraUnavailable(id) }

        fun prioritiesChanged() =
            availability.toList().forEach { it.onCameraAccessPrioritiesChanged() }

        /** Whether the last session runs a repeating request that targets [surface]. */
        fun isStreamingTo(surface: Surface): Boolean {
            val request = sessions.lastOrNull()?.let { requests[it] } ?: return false
            // CaptureRequest.getTargets() is hidden.
            val targets = CaptureRequest::class.java.getMethod("getTargets").invoke(request)
            return (targets as Collection<*>).contains(surface)
        }

        // CaptureRequest.Builder's constructor is hidden: built like Robolectric's camera device.
        private fun newRequestBuilder(id: String): CaptureRequest.Builder {
            val metadataClass = Class.forName("android.hardware.camera2.impl.CameraMetadataNative")
            return CaptureRequest.Builder::class.java.getDeclaredConstructor(
                metadataClass,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Set::class.java
            ).apply { isAccessible = true }.newInstance(
                metadataClass.getConstructor().newInstance(), false, -1, id, emptySet<String>()
            )
        }
    }

    private val camera = FakeCamera()
    private var now = 0L
    private val surface = mockk<Surface>(relaxed = true) { every { isValid } returns true }
    private val preview = mockk<Surface>(relaxed = true) { every { isValid } returns true }

    private fun dispatcherProvider(dispatcher: CoroutineDispatcher) =
        CameraDispatcherProvider(dispatcher, { mockk(relaxed = true) }, { mockk(relaxed = true) })

    private fun TestScope.controller() = CameraController(
        camera.manager,
        camera.characteristics,
        dispatcherProvider(StandardTestDispatcher(testScheduler)),
        "1",
        sessionCompat = camera.compat,
        elapsedRealtime = { now }
    )

    /** Opens the camera and streams to [surface]. */
    private fun TestScope.stream(controller: CameraController) {
        launch {
            controller.addOutput(CameraSurface(STREAM, surface))
            controller.addTarget(STREAM)
        }
        answerReopen()
        assertTrue(controller.isActiveFlow.value)
    }

    private fun TestScope.answerReopen() {
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        camera.configure()
        advanceUntilIdle()
    }

    @Test
    fun `lost device interrupts and the source keeps streaming`() = runTest {
        val source = CameraSource(
            camera.context,
            "1",
            dispatcherProvider(StandardTestDispatcher(testScheduler)),
            camera.compat
        )
        launch {
            source.setOutput(surface)
            source.startStream()
        }
        answerReopen()
        assertTrue(source.isStreamingFlow.value)

        camera.evict()
        advanceUntilIdle()

        assertTrue(source.isInterruptedFlow.value)
        assertTrue(source.isStreamingFlow.value)
        source.release()
    }

    @Test
    fun `release and a requested close do not interrupt`() = runTest {
        val controller = controller()
        stream(controller)

        launch { controller.removeOutput(STREAM) }
        advanceUntilIdle()
        assertFalse(controller.isActiveFlow.value)
        assertFalse(controller.isInterruptedFlow.value)

        controller.release()
        advanceUntilIdle()
        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(camera.availability.isEmpty())
    }

    @Test
    fun `only a close without request is a lost device`() = runTest {
        suspend fun device() = CameraDeviceController.create(camera.manager, camera.compat, "1")

        val requested = async { device() }
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        launch { requested.await().close() }
        advanceUntilIdle()
        assertTrue(requested.await().isClosed)
        assertFalse(requested.await().isLost)

        val lost = async { device() }
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        camera.evict()
        advanceUntilIdle()
        assertTrue(lost.await().isLost)
    }

    @Test
    fun `nothing opens the camera while interrupted`() = runTest {
        val controller = controller()
        launch { controller.addOutput(CameraSurface(PREVIEW, preview)) }
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        val opens = camera.opens

        launch {
            controller.removeTarget(STREAM)
            controller.setFps(15)
            try {
                controller.resetSettings()
                fail("resetSettings must throw while interrupted")
            } catch (e: Exception) {
                assertTrue(e.message!!, e.message!!.contains("interrupted"))
            }
            controller.setSetting(CaptureRequest.CONTROL_ZOOM_RATIO, 2f)
            try {
                controller.applyRepeatingSession()
                fail("applyRepeatingSession must throw while interrupted")
            } catch (e: Exception) {
                assertTrue(e.message!!, e.message!!.contains("interrupted"))
            }
        }
        advanceUntilIdle()
        assertEquals(opens, camera.opens)

        // A target asked for is the one call that reopens, through the reopen gate.
        launch { controller.addTarget(PREVIEW) }
        advanceUntilIdle()
        assertEquals(opens + 1, camera.opens)
        camera.refuse()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `removing a target never opens the camera`() = runTest {
        val controller = controller()
        launch {
            controller.addOutput(CameraSurface(STREAM, surface))
            controller.removeTarget(STREAM)
        }
        advanceUntilIdle()

        assertEquals(0, camera.opens)
        controller.release()
    }

    @Test
    fun `availability of another id reopens the wanted targets`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()

        camera.available("0")
        answerReopen()

        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(controller.isActiveFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        controller.release()
    }

    @Test
    fun `refused open on a fresh controller keeps the target and reopens on availability`() =
        runTest {
            val controller = controller()
            val job = launch {
                controller.addOutput(CameraSurface(STREAM, surface))
                controller.addTarget(STREAM)
            }
            advanceUntilIdle()
            camera.refuse()
            advanceUntilIdle()
            assertTrue(job.isCompleted)
            assertTrue(controller.isInterruptedFlow.value)

            camera.available("1")
            answerReopen()

            assertTrue(controller.isActiveFlow.value)
            assertTrue(camera.isStreamingTo(surface))
            controller.release()
        }

    @Test
    fun `retry reopens and clears the suspension`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.available("0")
        answerReopen()
        now += 500
        camera.evict()
        advanceUntilIdle()
        val opens = camera.opens

        controller.retryIfInterrupted()
        answerReopen()
        assertEquals(opens + 1, camera.opens)
        assertTrue(controller.isActiveFlow.value)

        // The retry cleared the suspension: availability reopens again.
        now += 10_000
        camera.evict()
        advanceUntilIdle()
        camera.available("0")
        answerReopen()
        assertEquals(opens + 2, camera.opens)
        controller.release()
    }

    @Test
    fun `the reopen clears the interruption exactly once`() = runTest {
        val controller = controller()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch { controller.isInterruptedFlow.toList(states) }
        stream(controller)
        camera.evict()
        advanceUntilIdle()

        camera.prioritiesChanged()
        answerReopen()

        assertEquals(listOf(false, true, false), states)
        controller.release()
    }

    @Test
    fun `a replaced session's close is ignored`() = runTest {
        val controller = controller()
        stream(controller)
        val oldSession = camera.sessionCallbacks.last()
        // A new output restarts the session.
        launch { controller.addOutput(CameraSurface(PREVIEW, preview)) }
        advanceUntilIdle()
        camera.configure()
        advanceUntilIdle()
        assertTrue(controller.isActiveFlow.value)

        oldSession.onClosed(mockk(relaxed = true))
        advanceUntilIdle()

        assertTrue(controller.isActiveFlow.value)
        controller.release()
    }

    @Test
    fun `a loss right after an availability reopen suspends availability reopens`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.available("0")
        answerReopen()

        now += 500
        camera.evict()
        advanceUntilIdle()
        val opens = camera.opens
        camera.available("0")
        advanceUntilIdle()
        assertEquals(opens, camera.opens)

        camera.prioritiesChanged()
        answerReopen()
        assertEquals(opens + 1, camera.opens)
        controller.release()
    }

    @Test
    fun `the audio restriction is replayed on the new device`() = runTest {
        val controller = controller()
        stream(controller)
        controller.muteVibrationAndSound()
        camera.evict()
        advanceUntilIdle()

        camera.available("0")
        answerReopen()

        assertEquals(
            CameraDevice.AUDIO_RESTRICTION_VIBRATION_SOUND,
            camera.devices.last().cameraAudioRestriction
        )
        controller.release()
    }

    @Test
    fun `release unregisters the callback and a later trigger does nothing`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        val callback = camera.availability.single()

        controller.release()
        advanceUntilIdle()
        assertTrue(camera.availability.isEmpty())
        val opens = camera.opens
        callback.onCameraAvailable("1")
        controller.retryIfInterrupted()
        advanceUntilIdle()

        assertEquals(opens, camera.opens)
    }

    @Test
    fun `a failing reopen keeps the controller usable`() = runTest {
        val controller = controller()
        launch {
            controller.addOutput(CameraSurface(STREAM, surface))
            controller.addTarget(STREAM)
        }
        advanceUntilIdle()
        camera.refuse()
        advanceUntilIdle()

        camera.failCreateRequest = IllegalArgumentException("broken request")
        controller.retryIfInterrupted()
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)

        // The scope survived: a new retry runs, on a fresh device.
        assertEquals(camera.devices.last(), camera.closedDevices.last())
        controller.retryIfInterrupted()
        answerReopen()
        assertTrue(controller.isActiveFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a reopen whose session fails ends the interruption on the next one`() = runTest {
        val controller = controller()
        launch { controller.addOutput(CameraSurface(PREVIEW, preview)) }
        stream(controller)
        camera.evict()
        advanceUntilIdle()

        camera.failCreateSession = IllegalArgumentException("surface abandoned")
        camera.available("0")
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)

        // The failed reopen closed its device: the next one opens a fresh one.
        val opens = camera.opens
        controller.retryIfInterrupted()
        answerReopen()
        assertEquals(opens + 1, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)

        // Not deferred: the target reaches the live session.
        launch { controller.addTarget(PREVIEW) }
        advanceUntilIdle()
        assertTrue(camera.isStreamingTo(preview))
        controller.release()
    }

    @Test
    fun `a loss while the session restarts interrupts`() = runTest {
        val controller = controller()
        stream(controller)
        val restart = launch {
            try {
                controller.addOutput(CameraSurface(PREVIEW, preview))
            } catch (e: Exception) {
                // PreviewView reports it and carries on.
            }
        }
        advanceUntilIdle()
        camera.evict()
        camera.sessionCallbacks.last().onClosed(mockk(relaxed = true))
        advanceUntilIdle()

        assertTrue(restart.isCompleted)
        assertTrue(controller.isInterruptedFlow.value)
        assertFalse(controller.isActiveFlow.value)
        controller.release()
    }

    @Test
    fun `a restart its caller cancels still watches the new session`() = runTest {
        val controller = controller()
        stream(controller)
        // PreviewView cancels its start job on every resize, here while the new session configures.
        val restart = launch { controller.addOutput(CameraSurface(PREVIEW, preview)) }
        advanceUntilIdle()
        restart.cancel()
        advanceUntilIdle()
        camera.configure()
        advanceUntilIdle()
        assertTrue(controller.isActiveFlow.value)

        camera.evict()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a target asked for during an outage reopens the camera`() = runTest {
        val controller = controller()
        stream(controller)
        launch { controller.removeTarget(STREAM) }
        advanceUntilIdle()
        camera.evict()
        advanceUntilIdle()
        val opens = camera.opens
        camera.available("0")
        advanceUntilIdle()
        assertEquals("skipped: no target wanted", opens, camera.opens)

        launch { controller.addTarget(STREAM) }
        answerReopen()

        assertEquals(opens + 1, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        controller.release()
    }

    @Test
    fun `an output change during an outage reopens the camera`() = runTest {
        var previewValid = true
        val stale = mockk<Surface>(relaxed = true) { every { isValid } answers { previewValid } }
        val controller = controller()
        launch { controller.addOutput(CameraSurface(PREVIEW, stale)) }
        stream(controller)
        camera.evict()
        previewValid = false
        advanceUntilIdle()
        val opens = camera.opens
        camera.available("0")
        advanceUntilIdle()
        assertEquals("skipped: outputs not ready", opens, camera.opens)

        // The stale preview goes: the stream output alone can reopen.
        launch { controller.removeOutput(PREVIEW) }
        advanceUntilIdle()
        assertEquals(opens + 1, camera.opens)
        camera.refuse()
        advanceUntilIdle()

        // A fresh preview surface arrives.
        launch { controller.addOutput(CameraSurface(PREVIEW, preview)) }
        answerReopen()
        assertEquals(opens + 2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        controller.release()
    }

    @Test
    fun `a session closed while configuring does not hang`() = runTest {
        val controller = controller()
        val job = launch {
            controller.addOutput(CameraSurface(STREAM, surface))
            controller.addTarget(STREAM)
        }
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        camera.sessionCallbacks.last().onClosed(mockk(relaxed = true))
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        controller.release()
    }

    /** Streams, then loses the camera to a client on camera 0, as CameraService reports it. */
    private fun TestScope.loseToCamera0(controller: CameraController, echoFirst: Boolean = false) {
        stream(controller)
        camera.unavailable("1")
        camera.evict()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        if (echoFirst) {
            camera.available("1")
            camera.unavailable("0")
        } else {
            camera.unavailable("0")
            camera.available("1")
        }
        advanceUntilIdle()
    }

    @Test
    fun `the echo of our own loss does not evict the client that took the camera`() = runTest {
        val controller = controller()
        loseToCamera0(controller)
        assertEquals(1, camera.opens)

        camera.available("0")
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        controller.release()
    }

    @Test
    fun `the echo arriving before the holder is reported does not reopen`() = runTest {
        val controller = controller()
        loseToCamera0(controller, echoFirst = true)
        assertEquals(1, camera.opens)

        camera.available("0")
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `the echo racing the loss itself does not reopen`() = runTest {
        val controller = controller()
        stream(controller)
        camera.unavailable("1")
        // CameraService reports both before the session watcher sees the loss.
        camera.evict()
        camera.available("1")
        camera.unavailable("0")
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        assertEquals(1, camera.opens)
        controller.release()
    }

    @Test
    fun `a coalesced echo leaves the next availability of our id a reopen`() = runTest {
        val controller = controller()
        stream(controller)
        camera.unavailable("1")
        camera.evict()
        advanceUntilIdle()
        // Another client took camera 1 itself; its free status in between was never reported.
        camera.unavailable("1")
        advanceUntilIdle()
        assertEquals(1, camera.opens)

        camera.available("1")
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a request or a priority change while another camera is held does not reopen`() =
        runTest {
            val controller = controller()
            loseToCamera0(controller)

            launch {
                controller.addOutput(CameraSurface(PREVIEW, preview))
                controller.addTarget(PREVIEW)
            }
            camera.prioritiesChanged()
            advanceUntilIdle()
            assertEquals(1, camera.opens)

            controller.retryIfInterrupted()
            answerReopen()
            assertEquals(2, camera.opens)
            assertFalse(controller.isInterruptedFlow.value)
            controller.release()
        }

    @Test
    fun `a retry skipped for no target makes the next request explicit, once`() = runTest {
        val controller = controller()
        stream(controller)
        launch { controller.removeTarget(STREAM) }
        advanceUntilIdle()
        camera.evict()
        advanceUntilIdle()
        camera.unavailable("0")
        controller.retryIfInterrupted()
        advanceUntilIdle()
        assertEquals(1, camera.opens)

        // Back in the app while the other app still holds camera 0 in the background.
        launch { controller.addTarget(STREAM) }
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        // The explicit reopen evicted the other client; later it takes the camera back.
        camera.available("0")

        now += 10_000
        camera.evict()
        camera.unavailable("0")
        advanceUntilIdle()
        launch {
            controller.addOutput(CameraSurface(PREVIEW, preview))
            controller.addTarget(PREVIEW)
        }
        advanceUntilIdle()
        assertEquals(2, camera.opens)
        controller.release()
    }

    @Test
    fun `the callback is registered for the controller's life`() = runTest {
        val controller = controller()
        assertEquals(1, camera.availability.size)
        loseToCamera0(controller)
        camera.available("0")
        answerReopen()
        assertFalse(controller.isInterruptedFlow.value)
        assertEquals(1, camera.availability.size)

        controller.release()
        assertTrue(camera.availability.isEmpty())
    }

    @Test
    fun `a camera held since before the open does not block, until it is released`() = runTest {
        val controller = controller()
        camera.unavailable("2")
        stream(controller)
        camera.evict()
        advanceUntilIdle()

        camera.prioritiesChanged()
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)

        // Released and taken again after our open: now it is a holder like any other.
        camera.available("2")
        camera.unavailable("2")
        now += 10_000
        camera.evict()
        advanceUntilIdle()
        camera.prioritiesChanged()
        advanceUntilIdle()
        assertEquals(2, camera.opens)
        controller.release()
    }

    /** A reopen whose request CameraService refuses: it had disconnected us before onDisconnected. */
    private fun TestScope.failReopen(controller: CameraController, states: MutableList<Boolean>) {
        backgroundScope.launch { controller.isInterruptedFlow.toList(states) }
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.available("0")
        advanceUntilIdle()
        camera.open()
        advanceUntilIdle()
        camera.failRepeating = android.hardware.camera2.CameraAccessException(
            android.hardware.camera2.CameraAccessException.CAMERA_ERROR
        )
        camera.configure()
        advanceUntilIdle()
    }

    @Test
    fun `a failed reopen closes what it built and the next one opens a new device`() = runTest {
        val controller = controller()
        val states = mutableListOf<Boolean>()
        failReopen(controller, states)
        val halfBuilt = camera.devices.last()
        assertEquals(halfBuilt, camera.closedDevices.last())
        assertTrue(controller.isInterruptedFlow.value)
        assertEquals(2, camera.opens)

        controller.retryIfInterrupted()
        advanceUntilIdle()
        assertEquals(3, camera.opens)
        camera.refuse()
        advanceUntilIdle()
        assertEquals(listOf(false, true), states)
        controller.release()
    }

    @Test
    fun `after a failed reopen a working one ends the interruption once`() = runTest {
        val controller = controller()
        val states = mutableListOf<Boolean>()
        failReopen(controller, states)
        controller.setSetting(CaptureRequest.CONTROL_ZOOM_RATIO, 2f)

        controller.retryIfInterrupted()
        answerReopen()
        assertEquals(3, camera.opens)
        assertEquals(listOf(false, true, false), states)
        assertTrue(camera.isStreamingTo(surface))
        assertEquals(2f, controller.getSetting(CaptureRequest.CONTROL_ZOOM_RATIO))
        // A fresh session submits its request once.
        assertEquals(1, camera.repeatingCount[camera.sessions.last()])
        controller.release()
    }

    @Test
    fun `a disconnect landing after the failed reopen closed its device changes nothing`() =
        runTest {
            val controller = controller()
            val states = mutableListOf<Boolean>()
            failReopen(controller, states)

            camera.evict(camera.devices.last())
            advanceUntilIdle()
            assertEquals(listOf(false, true), states)

            // Not suspended: an availability reopen still runs.
            camera.available("0")
            answerReopen()
            assertEquals(3, camera.opens)
            assertFalse(controller.isInterruptedFlow.value)
            controller.release()
        }

    @Test
    fun `the echo and a holder on our own id, both before the loss is seen, leave its release a reopen`() =
        runTest {
            val controller = controller()
            stream(controller)
            camera.unavailable("1")
            // Another client took camera 1 itself; CameraService reports it before the watcher runs.
            camera.evict()
            camera.available("1")
            camera.unavailable("1")
            advanceUntilIdle()
            assertTrue(controller.isInterruptedFlow.value)
            assertEquals(1, camera.opens)

            camera.available("1")
            answerReopen()
            assertEquals(2, camera.opens)
            assertFalse(controller.isInterruptedFlow.value)

            // The next outage, reported in the usual order: its echo is ours again.
            camera.unavailable("1")
            now += 10_000
            camera.evict()
            advanceUntilIdle()
            camera.available("1")
            advanceUntilIdle()
            camera.unavailable("0")
            advanceUntilIdle()
            assertEquals(2, camera.opens)
            controller.release()
        }

    @Test
    fun `a refused reopen leaves the next free status of our id a trigger`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.available("1")
        camera.unavailable("1")
        controller.retryIfInterrupted()
        advanceUntilIdle()
        camera.refuse()
        advanceUntilIdle()
        assertEquals(2, camera.opens)

        camera.available("1")
        answerReopen()
        assertEquals(3, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a device error with no other client reopens on our own id going free`() = runTest {
        val controller = controller()
        stream(controller)
        camera.unavailable("1")
        camera.fail()
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        assertEquals(1, camera.opens)

        camera.available("1")
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        assertTrue(camera.isStreamingTo(surface))
        controller.release()
    }

    @Test
    fun `a device error whose free status comes before the loss is seen still reopens`() = runTest {
        val controller = controller()
        stream(controller)
        camera.unavailable("1")
        camera.fail()
        camera.available("1")
        advanceUntilIdle()
        assertEquals(2, camera.opens)

        answerReopen()
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a reopen that keeps failing waits for the next trigger, not for its own close`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.available("1")
        advanceUntilIdle()

        repeat(3) { attempt ->
            camera.prioritiesChanged()
            advanceUntilIdle()
            camera.open()
            advanceUntilIdle()
            camera.failRepeating = IllegalStateException("broken HAL")
            camera.configure()
            advanceUntilIdle()
            assertEquals(camera.devices.last(), camera.closedDevices.last())
            // The status our close of it reports.
            camera.available("1")
            advanceUntilIdle()
            assertEquals("one open per trigger", attempt + 2, camera.opens)
        }
        assertTrue(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a device error during a reopen waits for the next trigger, whichever order it is seen in`() =
        runTest {
            val controller = controller()
            stream(controller)
            camera.evict()
            advanceUntilIdle()
            camera.available("1")
            advanceUntilIdle()

            camera.prioritiesChanged()
            advanceUntilIdle()
            // Fails right after its open, reported free before the reopen sees it.
            camera.open()
            camera.fail()
            camera.available("1")
            camera.failCreateSession = IllegalStateException("CameraDevice was already closed")
            advanceUntilIdle()
            assertEquals(2, camera.opens)
            assertTrue(controller.isInterruptedFlow.value)

            camera.prioritiesChanged()
            answerReopen()
            assertEquals(3, camera.opens)
            assertFalse(controller.isInterruptedFlow.value)
            controller.release()
        }

    @Test
    fun `an echo that never came does not swallow the next outage's reopen`() = runTest {
        val controller = controller()
        stream(controller)
        camera.evict()
        advanceUntilIdle()
        camera.prioritiesChanged()
        answerReopen()
        assertFalse(controller.isInterruptedFlow.value)

        now += 10_000
        camera.fail()
        advanceUntilIdle()
        camera.available("1")
        answerReopen()
        assertEquals(3, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    @Test
    fun `a failed reopen evicted before its disconnect does not take the camera back on the echo`() =
        runTest {
            val controller = controller()
            stream(controller)
            camera.evict()
            advanceUntilIdle()
            camera.available("1")
            camera.unavailable("0")
            camera.available("0")
            advanceUntilIdle()
            camera.open()
            advanceUntilIdle()
            // Evicted again on the server; its onDisconnected has not arrived.
            camera.failRepeating = android.hardware.camera2.CameraAccessException(
                android.hardware.camera2.CameraAccessException.CAMERA_ERROR
            )
            camera.configure()
            advanceUntilIdle()
            assertEquals(2, camera.opens)
            // The echo of that second eviction, then its holder, on our own id.
            camera.available("1")
            advanceUntilIdle()
            camera.unavailable("1")
            advanceUntilIdle()
            assertEquals(2, camera.opens)

            camera.available("1")
            answerReopen()
            assertEquals(3, camera.opens)
            assertFalse(controller.isInterruptedFlow.value)
            controller.release()
        }

    @Test
    fun `a device error seen from another thread mid-callback still reads as an error`() = runTest {
        val controller = controller()
        stream(controller)
        camera.unavailable("1")

        // onError runs on the camera thread; the session watcher reads the loss meanwhile, here
        // while onError is held at its camera.close().
        val closing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        camera.beforeClose = {
            closing.countDown()
            assertTrue(resume.await(5, TimeUnit.SECONDS))
        }
        val cameraThread = thread { camera.fail() }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        advanceUntilIdle()
        assertTrue(controller.isInterruptedFlow.value)
        camera.beforeClose = null
        resume.countDown()
        cameraThread.join(5_000)
        advanceUntilIdle()

        camera.available("1")
        answerReopen()
        assertEquals(2, camera.opens)
        assertFalse(controller.isInterruptedFlow.value)
        controller.release()
    }

    companion object {
        private const val STREAM = "stream"
        private const val PREVIEW = "preview"
    }
}
