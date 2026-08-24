#include "jni_callbacks.h"
#include <string.h>

static JavaVM *g_jvm = NULL;
static jclass g_nativeBridgeClass = NULL;
static jmethodID g_midOnPeerConnected = NULL;
static jmethodID g_midOnPeerDisconnected = NULL;
static jmethodID g_midOnMessageReceived = NULL;
static jmethodID g_midOnError = NULL;
static jmethodID g_midOnPeerDiscovered = NULL;
static jmethodID g_midOnFileProgress = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = (*env)->FindClass(env, "com/example/twopchat/NativeBridge");
    if (localClass != NULL) {
        g_nativeBridgeClass = (jclass)(*env)->NewGlobalRef(env, localClass);
        g_midOnPeerConnected = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onPeerConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnPeerDisconnected = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onPeerDisconnected", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnMessageReceived = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onMessageReceived", "(Ljava/lang/String;[BLjava/lang/String;)V");
        g_midOnError = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onError", "(ILjava/lang/String;)V");
        g_midOnPeerDiscovered = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onPeerDiscovered", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnFileProgress = (*env)->GetStaticMethodID(env, g_nativeBridgeClass, "onFileProgress", "(Ljava/lang/String;Ljava/lang/String;JJD)V");
    }
    return JNI_VERSION_1_6;
}

static JNIEnv* getJNIEnv(int *attachedOut) {
    if (attachedOut) *attachedOut = 0;
    if (g_jvm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (res == JNI_OK && env != NULL) {
        return env;
    }
    if (res == JNI_EDETACHED) {
#if defined(__ANDROID__)
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == JNI_OK) {
#else
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) == JNI_OK) {
#endif
            if (attachedOut) *attachedOut = 1;
            return env;
        }
    }
    return NULL;
}

static void releaseJNIEnv(int attached) {
    if (attached && g_jvm != NULL) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void checkAndClearException(JNIEnv *env) {
    if (env != NULL && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

void callbackOnPeerConnected(const char *peerFP, const char *endpoint) {
    if (g_nativeBridgeClass == NULL || g_midOnPeerConnected == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jFP = (*env)->NewStringUTF(env, peerFP ? peerFP : "");
        jstring jEndp = (*env)->NewStringUTF(env, endpoint ? endpoint : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnPeerConnected, jFP, jEndp);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jFP);
        (*env)->DeleteLocalRef(env, jEndp);
        releaseJNIEnv(attached);
    }
}

void callbackOnPeerDisconnected(const char *peerFP, const char *reason) {
    if (g_nativeBridgeClass == NULL || g_midOnPeerDisconnected == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jFP = (*env)->NewStringUTF(env, peerFP ? peerFP : "");
        jstring jReason = (*env)->NewStringUTF(env, reason ? reason : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnPeerDisconnected, jFP, jReason);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jFP);
        (*env)->DeleteLocalRef(env, jReason);
        releaseJNIEnv(attached);
    }
}

void callbackOnMessageReceived(const char *peerFP, const jbyte *payload, jsize len, const char *messageID) {
    if (g_nativeBridgeClass == NULL || g_midOnMessageReceived == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jFP = (*env)->NewStringUTF(env, peerFP ? peerFP : "");
        jbyteArray jArr = (*env)->NewByteArray(env, len);
        if (len > 0 && payload != NULL) {
            (*env)->SetByteArrayRegion(env, jArr, 0, len, payload);
        }
        jstring jMsgID = (*env)->NewStringUTF(env, messageID ? messageID : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnMessageReceived, jFP, jArr, jMsgID);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jFP);
        (*env)->DeleteLocalRef(env, jArr);
        (*env)->DeleteLocalRef(env, jMsgID);
        releaseJNIEnv(attached);
    }
}

void callbackOnError(int code, const char *msg) {
    if (g_nativeBridgeClass == NULL || g_midOnError == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jMsg = (*env)->NewStringUTF(env, msg ? msg : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnError, (jint)code, jMsg);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jMsg);
        releaseJNIEnv(attached);
    }
}

void callbackOnPeerDiscovered(const char *infoHashHex, const char *endpoint, const char *source) {
    if (g_nativeBridgeClass == NULL || g_midOnPeerDiscovered == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jHash = (*env)->NewStringUTF(env, infoHashHex ? infoHashHex : "");
        jstring jEndp = (*env)->NewStringUTF(env, endpoint ? endpoint : "");
        jstring jSrc = (*env)->NewStringUTF(env, source ? source : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnPeerDiscovered, jHash, jEndp, jSrc);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jHash);
        (*env)->DeleteLocalRef(env, jEndp);
        (*env)->DeleteLocalRef(env, jSrc);
        releaseJNIEnv(attached);
    }
}

void callbackOnFileProgress(const char *peerFP, const char *messageID, jlong transferred, jlong total, jdouble speedKbps) {
    if (g_nativeBridgeClass == NULL || g_midOnFileProgress == NULL) return;
    int attached = 0;
    JNIEnv *env = getJNIEnv(&attached);
    if (env != NULL) {
        jstring jFP = (*env)->NewStringUTF(env, peerFP ? peerFP : "");
        jstring jMsgID = (*env)->NewStringUTF(env, messageID ? messageID : "");
        (*env)->CallStaticVoidMethod(env, g_nativeBridgeClass, g_midOnFileProgress, jFP, jMsgID, transferred, total, speedKbps);
        checkAndClearException(env);
        (*env)->DeleteLocalRef(env, jFP);
        (*env)->DeleteLocalRef(env, jMsgID);
        releaseJNIEnv(attached);
    }
}

const char* getJStringUTFChars(JNIEnv *env, jstring str) {
    if (env == NULL || str == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, str, NULL);
}

void releaseJStringUTFChars(JNIEnv *env, jstring str, const char *chars) {
    if (env != NULL && str != NULL && chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, str, chars);
    }
}

jstring createJString(JNIEnv *env, const char *str) {
    if (env == NULL || str == NULL) return NULL;
    return (*env)->NewStringUTF(env, str);
}

jstring nullJString(void) {
    return NULL;
}

jbyteArray nullJByteArray(void) {
    return NULL;
}

jbyteArray createJByteArray(JNIEnv *env, const jbyte *bytes, jsize len) {
    if (env == NULL || len < 0) return NULL;
    jbyteArray array = (*env)->NewByteArray(env, len);
    if (array != NULL && len > 0 && bytes != NULL) {
        (*env)->SetByteArrayRegion(env, array, 0, len, bytes);
    }
    return array;
}

jsize getByteArrayLength(JNIEnv *env, jbyteArray array) {
    if (env == NULL || array == NULL) return 0;
    return (*env)->GetArrayLength(env, array);
}

void getByteArrayRegion(JNIEnv *env, jbyteArray array, jsize start, jsize len, jbyte *buf) {
    if (env != NULL && array != NULL && buf != NULL && len > 0) {
        (*env)->GetByteArrayRegion(env, array, start, len, buf);
    }
}

void* getDirectBufferAddress(JNIEnv *env, jobject buf) {
    if (env == NULL || buf == NULL) return NULL;
    return (*env)->GetDirectBufferAddress(env, buf);
}

jlong getDirectBufferCapacity(JNIEnv *env, jobject buf) {
    if (env == NULL || buf == NULL) return 0;
    return (*env)->GetDirectBufferCapacity(env, buf);
}

jobject createDirectByteBuffer(JNIEnv *env, void *address, jlong capacity) {
    if (env == NULL || address == NULL || capacity <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, address, capacity);
}
