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
package android.hardware.camera2

import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.os.Handler
import android.view.Surface

/**
 * A [CameraDevice] for Robolectric tests. In this package for the package-private constructor:
 * mockk cannot stub [createCaptureRequest] under Robolectric (its return type is final).
 */
class FakeCameraDevice(
    private val deviceId: String,
    private val createRequest: () -> CaptureRequest.Builder,
    private val onClose: (FakeCameraDevice) -> Unit
) : CameraDevice() {
    private var audioRestriction = AUDIO_RESTRICTION_NONE
    private var isClosed = false

    override fun getId() = deviceId

    override fun createCaptureRequest(templateType: Int) = createRequest()

    override fun createReprocessCaptureRequest(inputResult: TotalCaptureResult) =
        throw UnsupportedOperationException()

    @Deprecated("Deprecated in Java")
    override fun createCaptureSession(
        outputs: List<Surface>, callback: CameraCaptureSession.StateCallback, handler: Handler?
    ) = throw UnsupportedOperationException()

    @Deprecated("Deprecated in Java")
    override fun createCaptureSessionByOutputConfigurations(
        outputConfigurations: List<OutputConfiguration>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) = throw UnsupportedOperationException()

    @Deprecated("Deprecated in Java")
    override fun createReprocessableCaptureSession(
        inputConfig: InputConfiguration,
        outputs: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) = throw UnsupportedOperationException()

    @Deprecated("Deprecated in Java")
    override fun createReprocessableCaptureSessionByConfigurations(
        inputConfig: InputConfiguration,
        outputs: List<OutputConfiguration>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?
    ) = throw UnsupportedOperationException()

    @Deprecated("Deprecated in Java")
    override fun createConstrainedHighSpeedCaptureSession(
        outputs: List<Surface>, callback: CameraCaptureSession.StateCallback, handler: Handler?
    ) = throw UnsupportedOperationException()

    override fun setCameraAudioRestriction(mode: Int) {
        audioRestriction = mode
    }

    override fun getCameraAudioRestriction() = audioRestriction

    override fun close() {
        if (!isClosed) {
            isClosed = true
            onClose(this)
        }
    }
}
