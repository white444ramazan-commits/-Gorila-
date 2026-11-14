#include <jni.h>
#include <algorithm>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_gorilla_companion_NativeBridge_nativeBuildInfo(JNIEnv* env, jobject /* thiz */) {
    const std::string info = "GORILLA native core | C++17 | local preview only";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_gorilla_companion_NativeBridge_nativeResponsePreview(
    JNIEnv* /* env */, jobject /* thiz */,
    jfloat input_delta, jfloat sensitivity, jfloat smoothing
) {
    const float input = std::clamp(static_cast<float>(input_delta), -1.0F, 1.0F);
    const float gain = std::clamp(static_cast<float>(sensitivity), 0.10F, 3.0F);
    const float smooth = std::clamp(static_cast<float>(smoothing), 0.05F, 1.0F);
    return std::clamp(input * gain * smooth, -1.0F, 1.0F);
}
