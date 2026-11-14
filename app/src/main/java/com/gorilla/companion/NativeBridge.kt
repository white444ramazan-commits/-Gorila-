package com.gorilla.companion

/**
 * JNI is used only for a local response-curve preview.
 * It is not attached to, injected into, or called from any game process.
 */
object NativeBridge {
    var available: Boolean = false
        private set
    init {
        try {
            System.loadLibrary("gorilla_native")
            available = true
        } catch (_: UnsatisfiedLinkError) {
            available = false
        }
    }
    external fun nativeBuildInfo(): String
    external fun nativeResponsePreview(inputDelta: Float, sensitivity: Float, smoothing: Float): Float
}
