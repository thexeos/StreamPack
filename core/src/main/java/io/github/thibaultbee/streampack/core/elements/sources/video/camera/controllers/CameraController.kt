/*
 * Copyright (C) 2025 Thibault B.
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
import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Range
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraException
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.sessioncompat.CameraCaptureSessionCompatBuilder
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.sessioncompat.ICameraCaptureSessionCompat
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSessionCallback
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraUtils
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureRequestWithTargetsBuilder
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureResultListener
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encapsulates device controller and session controller.
 */
internal class CameraController(
    private val manager: CameraManager,
    private val characteristics: CameraCharacteristics,
    dispatcherProvider: CameraDispatcherProvider,
    val cameraId: String,
    val onCaptureRequestBuilder: CaptureRequestWithTargetsBuilder.() -> Unit = {},
    private val hasCameraPermission: () -> Boolean = { true },
    private val sessionCompat: ICameraCaptureSessionCompat = CameraCaptureSessionCompatBuilder.build(
        dispatcherProvider
    ),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {

    private val defaultDispatcher = dispatcherProvider.default
    private val coroutineScope = CoroutineScope(defaultDispatcher)
    private var isActiveJob: Job? = null

    private var deviceController: CameraDeviceController? = null
    private var sessionController: CameraSessionController? = null

    private var captureRequestBuilder: CaptureRequestWithTargetsBuilder? = null
    private val sessionCallback = CameraSessionCallback(coroutineScope)

    private val controllerMutex = Mutex()

    private val outputs = mutableMapOf<String, CameraSurface>()

    private val _isActiveFlow = MutableStateFlow(false)
    val isActiveFlow = _isActiveFlow.asStateFlow()

    /**
     * Whether the camera was lost to another client or refused. While true, nothing opens the
     * camera but a reopen ([retryIfInterrupted] or a camera availability callback).
     */
    private val _isInterruptedFlow = MutableStateFlow(false)
    val isInterruptedFlow = _isInterruptedFlow.asStateFlow()

    /**
     * Names of the targets the caller asked for. Unlike the builder's, they survive a lost device.
     * To be used under [controllerMutex]
     */
    private val wanted = mutableSetOf<String>()

    @Volatile
    private var isReleased = false

    /** Non-null while a reopen runs: the only time the device may open while interrupted. */
    @Volatile
    private var reopenTrigger: ReopenTrigger? = null

    /** An availability reopen lost the camera again at once: only explicit triggers reopen it. */
    @Volatile
    private var autoSuspended = false
    private var openedAt = 0L
    private var openedByAvailability = false

    @Volatile
    private var audioRestricted = false

    private val availabilityLock = Any()
    private var isAvailabilityCallbackRegistered = false

    /** Ids some client (us included) holds, per the availability callback. Under [availabilityLock]. */
    private val unavailableIds = mutableSetOf<String>()

    /** Ids held since before our device opened: they coexist with us. Under [availabilityLock]. */
    private var baselineIds = emptySet<String>()

    /** The next "available" of our id is the echo of our own loss. Under [availabilityLock]. */
    private var awaitOwnEcho = false

    /** Our id was reported available since our last open began. Under [availabilityLock]. */
    private var ownFreedSinceOpen = false

    /** A RETRY skipped for want of outputs or targets: the next REQUEST runs ungated. */
    private var retryPending = false

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        // Any id: a conflict can cross ids (holding camera 1 is blocked by another client on 0).
        override fun onCameraAvailable(cameraId: String) {
            Logger.i(TAG, "Camera $cameraId available")
            val reopen = synchronized(availabilityLock) {
                unavailableIds -= cameraId
                baselineIds -= cameraId
                when {
                    cameraId != this@CameraController.cameraId -> true
                    awaitOwnEcho -> {
                        awaitOwnEcho = false
                        false
                    }
                    // Freed before the loss was seen, or during a reopen: markLost and the reopen's
                    // failure decide what it means.
                    !_isInterruptedFlow.value || reopenTrigger != null -> {
                        ownFreedSinceOpen = true
                        false
                    }

                    else -> true
                }
            }
            if (reopen) {
                tryReopen(ReopenTrigger.AVAILABILITY)
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            Logger.i(TAG, "Camera $cameraId unavailable")
            synchronized(availabilityLock) {
                unavailableIds += cameraId
                // Taken again before we saw it free: the echo was coalesced away.
                if (cameraId == this@CameraController.cameraId) awaitOwnEcho = false
            }
        }

        override fun onCameraAccessPrioritiesChanged() {
            Logger.i(TAG, "Camera access priorities changed")
            tryReopen(ReopenTrigger.PRIORITIES)
        }
    }

    private val fpsRange: Range<Int>
        get() = CameraUtils.getClosestFpsRange(characteristics, fps)
    private var fps: Int = 30
    private var dynamicRangeProfile: DynamicRangeProfile = DynamicRangeProfile.sdr

    init {
        // For life: the reopen gate needs the ids held at the moment of a loss.
        registerAvailabilityCallback()
    }

    /**
     * Whether the current capture session has the given output.
     */
    suspend fun hasOutput(output: CameraSurface) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs.values.contains(output) }
    }

    /**
     * Whether the current capture session has the given output.
     *
     * @param name The name of the output to check
     */
    suspend fun hasOutput(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs.keys.contains(name) }
    }

    /**
     * Gets an output from the current capture session.
     *
     * @param name The name of the output to get
     */
    suspend fun getOutput(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs[name] }
    }

    /**
     * Adds an output to the current capture session.
     *
     * If the output is not in the current capture session, the capture session is recreated with
     * the new output.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun addOutput(output: CameraSurface) {
        require(output.surface.isValid) { "Output is invalid: $output" }
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (outputs.values.contains(output)) {
                    Logger.w(TAG, "Output is already added: $output")
                    return@withContext
                }
                outputs[output.name] = output
                if (isInterruptedFlow.value) {
                    tryReopen(ReopenTrigger.REQUEST)
                } else if (isActiveFlow.value) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * Removes an output from the current capture session.
     *
     * If the output is in the current capture session, the capture session is recreated without.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun removeOutput(name: String) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                val needRestart = outputs.remove(name) != null && isActiveFlow.value
                if (outputs.isEmpty()) {
                    sessionController?.close()
                } else if (isInterruptedFlow.value) {
                    tryReopen(ReopenTrigger.REQUEST)
                } else if (needRestart) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * To be used under [controllerMutex]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getDeviceController(): CameraDeviceController {
        if (isReleased) {
            throw IllegalStateException("CameraController is already released")
        }
        deviceController?.let {
            if (!it.isClosed) {
                return it
            }
        }
        if (isInterruptedFlow.value && reopenTrigger == null) {
            throw CameraException("Camera $cameraId interrupted")
        }
        synchronized(availabilityLock) {
            // From the open's start: our id can go free before onDeviceOpened runs.
            ownFreedSinceOpen = false
        }
        val controller = try {
            CameraDeviceController.create(manager, sessionCompat, cameraId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (!hasCameraPermission()) {
                throw t
            }
            Logger.w(TAG, "Camera $cameraId refused: $t")
            markInterrupted()
            throw CameraException("Camera $cameraId interrupted: ${t.message}")
        }
        deviceController = controller
        onDeviceOpened(controller)
        return controller
    }

    /**
     * The success point of every open, whichever path opened the device.
     */
    private fun onDeviceOpened(controller: CameraDeviceController) {
        val trigger = reopenTrigger
        openedAt = elapsedRealtime()
        openedByAvailability = trigger == ReopenTrigger.AVAILABILITY
        synchronized(availabilityLock) {
            baselineIds = unavailableIds - cameraId
        }
        if (audioRestricted) {
            controller.muteVibrationAndSound()
        } else {
            controller.unmuteVibrationAndSound()
        }
        Logger.i(TAG, "Camera $cameraId device opened (reopen=${trigger?.name ?: "none"})")
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getCaptureRequestBuilder(): CaptureRequestWithTargetsBuilder {
        return captureRequestBuilder ?: newCaptureRequestBuilder().also {
            captureRequestBuilder = it
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun newCaptureRequestBuilder(): CaptureRequestWithTargetsBuilder {
        return getDeviceController().createCaptureRequestBuilder().apply {
            val minFrameDuration = 1_000_000_000 / fpsRange.upper.toLong()
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration)
            onCaptureRequestBuilder()
        }
    }

    /**
     * To be used under [controllerMutex]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getSessionController(): CameraSessionController {
        return if (sessionController == null) {
            val device = getDeviceController()
            try {
                CameraSessionController.create(
                    device,
                    sessionCallback,
                    sessionCompat,
                    outputs.values.toList(),
                    dynamicRange = dynamicRangeProfile.dynamicRange,
                    defaultDispatcher
                ).apply {
                    applySessionController(this, device)
                    Logger.d(TAG, "Session controller created")
                }
            } catch (t: Throwable) {
                if (device.isLost) {
                    markLost(device)
                }
                throw t
            }
        } else {
            if (!sessionController!!.isClosed) {
                sessionController!!
            } else {
                var device: CameraDeviceController? = null
                try {
                    device = getDeviceController()
                    sessionController!!.recreate(
                        device,
                        outputs.values.toList(),
                        dynamicRange = dynamicRangeProfile.dynamicRange
                    ).apply {
                        applySessionController(this, device)
                        Logger.d(TAG, "Session controller recreated")
                    }
                } catch (t: Throwable) {
                    if (device?.isLost == true) {
                        markLost(device)
                    }
                    _isActiveFlow.tryEmit(false)
                    throw t
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun applySessionController(
        sessionController: CameraSessionController,
        device: CameraDeviceController
    ) {
        isActiveJob?.cancel()
        this.sessionController = sessionController

        isActiveJob = coroutineScope.launch {
            combine(sessionController.isClosedFlow, device.isClosedFlow) { session, camera ->
                session || camera
            }.distinctUntilChanged().collect { isClosed ->
                val current = this@CameraController.sessionController
                if (current != null && current !== sessionController) {
                    return@collect
                }
                if (isClosed) {
                    if (device.isLost) {
                        Logger.w(TAG, "Camera $cameraId session closed: device lost")
                        // Before isActive goes false: CameraSource reads it to keep streaming.
                        onDeviceLost(device)
                    } else {
                        Logger.i(TAG, "Camera $cameraId session closed: requested")
                    }
                }
                _isActiveFlow.emit(!isClosed)
            }
        }

        // Re-add the targets the caller asked for that are in the new outputs
        captureRequestBuilder?.let {
            val targets = outputs.values.filter { output ->
                output.name in wanted
            }
            it.clearTargets()
            if (targets.isNotEmpty()) {
                it.addTargets(targets)
            }
        }

        applyRepeatingSessionUnsafe()
    }

    /**
     * Restarts the current capture session.
     *
     * The current capture session is closed and a new one is created with the same outputs.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun restartSessionUnsafe() {
        val sessionController = sessionController
        if (sessionController == null) {
            Logger.i(TAG, "SessionController is null, nothing to restart")
            return
        }

        // Not cancellable: cancelled between the old session's close and the new watcher, the
        // controller would be left watching nothing (PreviewView cancels its start on each resize).
        withContext(NonCancellable) {
            isActiveJob?.cancel()
            isActiveJob = null

            var device: CameraDeviceController? = null
            try {
                device = getDeviceController()
                sessionController.recreate(
                    device,
                    outputs.values.toList(),
                    dynamicRange = dynamicRangeProfile.dynamicRange
                ).apply {
                    applySessionController(this, device)
                    Logger.d(TAG, "Session controller restarted")
                }
            } catch (t: Throwable) {
                // The watcher was cancelled above: nothing else reports this loss.
                if (device?.isLost == true) {
                    markLost(device)
                }
                if (isInterruptedFlow.value) {
                    _isActiveFlow.tryEmit(false)
                }
                throw t
            }
        }
    }

    /**
     * Adds a target to the current capture session.
     *
     * @param name The name of target to add
     * @return true if the target has been added, false otherwise
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun addTarget(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock {
            val target = outputs[name] ?: throw IllegalArgumentException(
                "Output $name not found in outputs ${outputs.keys.joinToString(", ")}"
            )
            wanted += name
            if (isInterruptedFlow.value) {
                Logger.i(TAG, "Target $name deferred: camera $cameraId interrupted")
                tryReopen(ReopenTrigger.REQUEST)
                return@withLock
            }

            val wasAdded = try {
                getCaptureRequestBuilder().addTarget(target)
            } catch (e: CameraException) {
                if (!isInterruptedFlow.value) {
                    throw e
                }
                Logger.i(TAG, "Target $name deferred: ${e.message}")
                return@withLock
            }
            if (wasAdded) {
                try {
                    applyRepeatingSessionUnsafe()
                } catch (t: Throwable) {
                    Logger.e(TAG, "Error to add target $name", t)
                }
                Logger.d(TAG, "Target $name added")
            }
        }
    }

    /**
     * Removes a target from the current capture session.
     *
     * @param name The name of target to remove
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun removeTarget(name: String) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (isReleased) {
                    return@withContext
                }
                wanted -= name
                val target = outputs[name] ?: return@withContext
                val captureRequestBuilder = captureRequestBuilder ?: return@withContext

                val wasRemoved = captureRequestBuilder.removeTarget(target)
                if (!wasRemoved) {
                    return@withLock
                }
                if (isInterruptedFlow.value) {
                    Logger.i(TAG, "Target $name removed while camera $cameraId interrupted")
                    return@withLock
                }
                try {
                    applyRepeatingSessionUnsafe()
                } catch (t: Throwable) {
                    Logger.e(TAG, "Error to remove target $name", t)
                }
                Logger.d(TAG, "Target $name removed")
            }
        }
    }

    /**
     * Gets a setting from the current capture request.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun <T> getSetting(key: CaptureRequest.Key<T?>): T? {
        val captureRequestBuilder =
            requireNotNull(captureRequestBuilder) { "CaptureRequestBuilder is null" }
        return captureRequestBuilder.get(key)
    }

    /**
     * Resets the current capture request to its default settings.
     *
     * Don't forget to call [applyRepeatingSession] to apply the setting.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun resetSettings() {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                val currentCaptureRequestBuilder = captureRequestBuilder ?: return@withLock
                // Get targets of previous builder
                val targets = currentCaptureRequestBuilder.getTargets()
                captureRequestBuilder = newCaptureRequestBuilder().apply {
                    addTargets(targets)
                }
            }
        }
    }

    /**
     * Sets a setting to the current capture request.
     *
     * Don't forget to call [applyRepeatingSession] to apply the setting.
     *
     * @param key The setting key
     * @param value The setting value
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun <T> setSetting(key: CaptureRequest.Key<T>, value: T) {
        val captureRequestBuilder = getCaptureRequestBuilder()
        captureRequestBuilder.set(key, value)
    }

    /**
     * Sets the fps to the current capture request.
     *
     * @param fpsRange The fps range
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun setFps(fps: Int) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (this@CameraController.fps == fps) {
                    return@withContext
                }

                this@CameraController.fps = fps

                val captureRequestBuilder = getCaptureRequestBuilder()

                val range = fpsRange
                val minFrameDuration = 1_000_000_000 / range.upper.toLong()
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                captureRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration)

                if (isActiveFlow.value) {
                    applyRepeatingSessionUnsafe()
                }
            }
        }
    }

    /**
     * Sets the dynamic range profile to the current capture request.
     *
     * @param dynamicRangeProfile The dynamic range profile
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun setDynamicRangeProfile(dynamicRangeProfile: DynamicRangeProfile) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (this@CameraController.dynamicRangeProfile == dynamicRangeProfile) {
                    return@withContext
                }
                Logger.d(TAG, "Setting dynamic range profile to $dynamicRangeProfile")

                this@CameraController.dynamicRangeProfile = dynamicRangeProfile

                if (isActiveFlow.value) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * Adds a capture callback listener to the current capture session.
     *
     * The listener is removed when it returns true or [removeCaptureCallbackListener] is called.
     */
    suspend fun addCaptureCallbackListener(listener: CaptureResultListener) {
        sessionCallback.addListener(listener)
    }

    /**
     * Removes a capture callback listener from the current capture session.
     *
     * @param listener The listener to remove
     */
    suspend fun removeCaptureCallbackListener(listener: CaptureResultListener) {
        sessionCallback.removeListener(listener)
    }

    /**
     * Sets a repeating session with the current capture request.
     *
     * @param tag A tag to associate with the session.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun applyRepeatingSessionUnsafe(tag: Any? = null) {
        val sessionController = getSessionController()
        val captureRequestBuilder = getCaptureRequestBuilder()
        tag?.let {
            captureRequestBuilder.setTag(it)
        }
        sessionController.applyRepeatingSession(captureRequestBuilder)
    }


    /**
     * Sets a repeating session with the current capture request.
     *
     * @param tag A tag to associate with the session.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun applyRepeatingSession(tag: Any? = null) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                applyRepeatingSessionUnsafe(tag)
            }
        }
    }

    private suspend fun closeControllers() {
        sessionController?.close()
        sessionController = null
        deviceController?.close()
        deviceController = null
        captureRequestBuilder = null
    }

    suspend fun release() {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                isReleased = true
                unregisterAvailabilityCallback()
                closeControllers()
                outputs.clear()
            }
        }
        sessionCompat.release()
        coroutineScope.cancel()
    }

    /**
     * Reopens an interrupted camera now, also after availability reopens were suspended.
     */
    fun retryIfInterrupted() {
        tryReopen(ReopenTrigger.RETRY)
    }

    private fun onDeviceLost(device: CameraDeviceController) {
        if (openedByAvailability && device === deviceController &&
            elapsedRealtime() - openedAt < FLAP_MS
        ) {
            Logger.w(TAG, "Camera $cameraId lost again right after its reopen: availability reopens suspended")
            autoSuspended = true
        }
        markLost(device)
    }

    private fun markInterrupted() {
        synchronized(availabilityLock) {
            markInterruptedLocked()
        }
    }

    /** To be used under [availabilityLock]. False once released. */
    private fun markInterruptedLocked(): Boolean {
        if (isReleased) {
            return false
        }
        if (!_isInterruptedFlow.value) {
            Logger.w(TAG, "Camera $cameraId interrupted")
        }
        _isInterruptedFlow.value = true
        return true
    }

    private fun markLost(device: CameraDeviceController) {
        val reopen = synchronized(availabilityLock) {
            if (!markInterruptedLocked()) {
                return
            }
            if (device.isDisconnected) {
                // The holder's own status follows our echo: the gate needs both.
                awaitOwnEcho = !ownFreedSinceOpen
                false
            } else {
                // An error has no holder: our id going free is the trigger, and it may have come
                // first. Not inside a reopen, whose failure waits for the next trigger.
                ownFreedSinceOpen && reopenTrigger == null
            }
        }
        if (reopen) {
            tryReopen(ReopenTrigger.AVAILABILITY)
        }
    }

    /** Ends the interruption once a reopen has a session, unless the device is gone again. */
    private fun clearInterruption(trigger: ReopenTrigger) {
        synchronized(availabilityLock) {
            // Atomic with markInterrupted: a loss after this check is marked by the session watcher.
            if (deviceController?.isClosed != false) {
                return
            }
            _isInterruptedFlow.value = false
            awaitOwnEcho = false
        }
        Logger.i(TAG, "Camera $cameraId reopened (${trigger.name})")
    }

    private fun registerAvailabilityCallback() {
        synchronized(availabilityLock) {
            if (isAvailabilityCallbackRegistered || isReleased) {
                return
            }
            sessionCompat.registerAvailabilityCallback(manager, availabilityCallback)
            isAvailabilityCallbackRegistered = true
        }
    }

    private fun unregisterAvailabilityCallback() {
        synchronized(availabilityLock) {
            if (!isAvailabilityCallbackRegistered) {
                return
            }
            sessionCompat.unregisterAvailabilityCallback(manager, availabilityCallback)
            isAvailabilityCallbackRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryReopen(trigger: ReopenTrigger) {
        coroutineScope.launch {
            try {
                controllerMutex.withLock {
                    reopenUnsafe(trigger)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.e(TAG, "Camera $cameraId reopen (${trigger.name}) failed: $t")
                markInterrupted()
            }
        }
    }

    /**
     * To be used under [controllerMutex]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun reopenUnsafe(trigger: ReopenTrigger) {
        if (isReleased || !isInterruptedFlow.value) {
            return
        }
        if (trigger == ReopenTrigger.RETRY) {
            autoSuspended = false
        } else if (autoSuspended && trigger == ReopenTrigger.AVAILABILITY) {
            Logger.i(TAG, "Camera $cameraId reopen (${trigger.name}) skipped: suspended")
            return
        }
        if (outputs.isEmpty() || outputs.values.any { !it.surface.isValid }) {
            Logger.i(TAG, "Camera $cameraId reopen (${trigger.name}) skipped: outputs not ready")
            retryPending = retryPending || trigger == ReopenTrigger.RETRY
            return
        }
        if (outputs.keys.none { it in wanted }) {
            Logger.i(TAG, "Camera $cameraId reopen (${trigger.name}) skipped: no target wanted")
            retryPending = retryPending || trigger == ReopenTrigger.RETRY
            return
        }
        val isExplicit = trigger == ReopenTrigger.RETRY ||
                (trigger == ReopenTrigger.REQUEST && retryPending)
        if (!isExplicit) {
            // Opening now would evict the client holding them, which then takes the camera back.
            val blocking = synchronized(availabilityLock) { unavailableIds - baselineIds }
            if (blocking.isNotEmpty()) {
                Logger.i(TAG, "Camera $cameraId reopen (${trigger.name}) skipped: camera(s) $blocking in use")
                return
            }
        }
        retryPending = false

        val previous = deviceController
        reopenTrigger = trigger
        try {
            // Opens the device; the new session re-adds the wanted targets and applies the request.
            getCaptureRequestBuilder()
            getSessionController()
            clearInterruption(trigger)
        } catch (t: Throwable) {
            // A half-built session reads as alive until its device's onDisconnected arrives.
            if (t !is CancellationException) {
                if (deviceController.let { it != null && it !== previous }) {
                    // Its "available" is no trigger: the reopen would fail the same way, in a loop.
                    synchronized(availabilityLock) {
                        if (!ownFreedSinceOpen) awaitOwnEcho = true
                    }
                }
                closeDeviceUnsafe()
            }
            throw t
        } finally {
            reopenTrigger = null
        }
    }

    /**
     * Closes the session and the device, keeping the request builder (zoom, focus, fps).
     *
     * To be used under [controllerMutex]
     */
    private suspend fun closeDeviceUnsafe() {
        sessionController?.takeIf { !it.isClosed }?.close()
        sessionController = null
        deviceController?.takeIf { !it.isClosed }?.close()
        deviceController = null
    }

    fun muteVibrationAndSound() {
        audioRestricted = true
        deviceController?.muteVibrationAndSound()
    }

    fun unmuteVibrationAndSound() {
        audioRestricted = false
        deviceController?.unmuteVibrationAndSound()
    }

    private enum class ReopenTrigger {
        AVAILABILITY, PRIORITIES, RETRY,

        /** The caller asked for a target or added an output while interrupted. */
        REQUEST
    }

    companion object {
        private const val TAG = "CameraController"

        /** A device lost this soon after an availability reopen is a tug of war with its holder. */
        private const val FLAP_MS = 2_000L
    }
}