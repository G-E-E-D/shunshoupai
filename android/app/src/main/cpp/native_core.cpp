#include <jni.h>

#include "connection.h"

#include <new>
#include <stdexcept>
#include <string>

namespace {

std::string stringValue(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::bad_alloc();
    try {
        std::string result(chars);
        env->ReleaseStringUTFChars(value, chars);
        return result;
    } catch (...) {
        env->ReleaseStringUTFChars(value, chars);
        throw;
    }
}

void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_shunshoupai_app_NativeCore_parseConnection(JNIEnv* env, jclass, jstring value, jboolean allowPairing) {
    try {
        const auto parsed = shunshoupai::parseConnection(stringValue(env, value), allowPairing == JNI_TRUE);
        jclass stringClass = env->FindClass("java/lang/String");
        if (stringClass == nullptr) return nullptr;
        jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
        if (result == nullptr) return nullptr;
        jstring origin = env->NewStringUTF(parsed.origin.c_str());
        if (origin == nullptr) return nullptr;
        env->SetObjectArrayElement(result, 0, origin);
        env->DeleteLocalRef(origin);
        jstring token = env->NewStringUTF(parsed.pairingToken.c_str());
        if (token == nullptr) return nullptr;
        env->SetObjectArrayElement(result, 1, token);
        env->DeleteLocalRef(token);
        return result;
    } catch (const std::invalid_argument&) {
        throwJava(env, "java/lang/IllegalArgumentException", "请填写有效的 HTTP(S) 服务根地址，或重新扫描顺手排登录二维码。");
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Connection validation ran out of memory.");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Connection validation failed.");
    }
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shunshoupai_app_NativeCore_sameOrigin(JNIEnv* env, jclass, jstring service, jstring destination) {
    try {
        return shunshoupai::sameOrigin(stringValue(env, service), stringValue(env, destination)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        // A missing/invalid/unreadable origin can never authorize navigation.
        return JNI_FALSE;
    }
}
