#include "jni_callbacks.h"
#include "_cgo_export.h"
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
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&env, NULL) == JNI_OK) {
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

// ============================================================================
// JNI EXPORTS IMPLEMENTED IN PURE C
// ============================================================================

JNIEXPORT void JNICALL Java_com_example_twopchat_NativeBridge_nativeSetStorageDir(JNIEnv *env, jclass clazz, jstring jDir) {
    const char *dir = getJStringUTFChars(env, jDir);
    if (dir != NULL) {
        goSetStorageDir((char*)dir);
        releaseJStringUTFChars(env, jDir, dir);
    }
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeInit(JNIEnv *env, jclass clazz) {
    return goInit() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeSetNickname(JNIEnv *env, jclass clazz, jstring jNickname) {
    const char *nick = getJStringUTFChars(env, jNickname);
    if (nick == NULL) return JNI_FALSE;
    int res = goSetNickname((char*)nick);
    releaseJStringUTFChars(env, jNickname, nick);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeEcho(JNIEnv *env, jclass clazz, jstring jMsg) {
    const char *msg = getJStringUTFChars(env, jMsg);
    if (msg == NULL) return NULL;
    char *resp = goEcho((char*)msg);
    releaseJStringUTFChars(env, jMsg, msg);
    if (resp == NULL) return NULL;
    jstring jResp = (*env)->NewStringUTF(env, resp);
    free(resp);
    return jResp;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetLocalIdentityJSON(JNIEnv *env, jclass clazz) {
    char *json = goGetLocalIdentityJSON();
    if (json == NULL) return NULL;
    jstring jJson = (*env)->NewStringUTF(env, json);
    free(json);
    return jJson;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetLocalSeedMnemonic(JNIEnv *env, jclass clazz) {
    char *mnemonic = goGetLocalSeedMnemonic();
    if (mnemonic == NULL) return NULL;
    jstring jMnemonic = (*env)->NewStringUTF(env, mnemonic);
    free(mnemonic);
    return jMnemonic;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeRestoreFromMnemonic(
    JNIEnv *env, jclass clazz, jstring jNickname, jstring jMnemonic, jstring jAboutMe) {
    const char *nick = getJStringUTFChars(env, jNickname);
    const char *mnemonic = getJStringUTFChars(env, jMnemonic);
    const char *about = getJStringUTFChars(env, jAboutMe);
    int res = goRestoreFromMnemonic((char*)(nick ? nick : ""), (char*)(mnemonic ? mnemonic : ""), (char*)(about ? about : ""));
    if (nick) releaseJStringUTFChars(env, jNickname, nick);
    if (mnemonic) releaseJStringUTFChars(env, jMnemonic, mnemonic);
    if (about) releaseJStringUTFChars(env, jAboutMe, about);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetFingerprint(JNIEnv *env, jclass clazz, jbyteArray jPub) {
    if (jPub == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, jPub);
    if (len <= 0) return NULL;
    uint8_t *buf = (uint8_t*)malloc(len);
    if (!buf) return NULL;
    (*env)->GetByteArrayRegion(env, jPub, 0, len, (jbyte*)buf);
    char *fp = goGetFingerprint((uint8_t*)buf, (int)len);
    free(buf);
    if (fp == NULL) return NULL;
    jstring jFp = (*env)->NewStringUTF(env, fp);
    free(fp);
    return jFp;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetSafetyNumber(
    JNIEnv *env, jclass clazz, jbyteArray jMyPub, jbyteArray jTheirPub, jbyteArray jMyVerify, jbyteArray jTheirVerify) {
    if (jMyPub == NULL || jTheirPub == NULL) return NULL;
    jsize myPubLen = (*env)->GetArrayLength(env, jMyPub);
    jsize theirPubLen = (*env)->GetArrayLength(env, jTheirPub);
    if (myPubLen != 32 || theirPubLen != 32) return NULL;
    uint8_t myPub[32], theirPub[32];
    (*env)->GetByteArrayRegion(env, jMyPub, 0, 32, (jbyte*)myPub);
    (*env)->GetByteArrayRegion(env, jTheirPub, 0, 32, (jbyte*)theirPub);

    uint8_t myVerify[32], theirVerify[32];
    int myVerifyLen = 0, theirVerifyLen = 0;
    if (jMyVerify != NULL && (*env)->GetArrayLength(env, jMyVerify) == 32) {
        (*env)->GetByteArrayRegion(env, jMyVerify, 0, 32, (jbyte*)myVerify);
        myVerifyLen = 32;
    }
    if (jTheirVerify != NULL && (*env)->GetArrayLength(env, jTheirVerify) == 32) {
        (*env)->GetByteArrayRegion(env, jTheirVerify, 0, 32, (jbyte*)theirVerify);
        theirVerifyLen = 32;
    }

    char *sn = goGetSafetyNumber((uint8_t*)myPub, 32, (uint8_t*)theirPub, 32, (uint8_t*)(myVerifyLen ? myVerify : NULL), myVerifyLen, (uint8_t*)(theirVerifyLen ? theirVerify : NULL), theirVerifyLen);
    if (sn == NULL) return NULL;
    jstring jSn = (*env)->NewStringUTF(env, sn);
    free(sn);
    return jSn;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeStartListener(JNIEnv *env, jclass clazz, jint jPort) {
    return goStartListener((int)jPort) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeStopListener(JNIEnv *env, jclass clazz) {
    return goStopListener() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeConnectPeer(JNIEnv *env, jclass clazz, jstring jEndpoint, jstring jExpectedFP) {
    const char *endpoint = getJStringUTFChars(env, jEndpoint);
    if (endpoint == NULL) return JNI_FALSE;
    const char *expectedFP = getJStringUTFChars(env, jExpectedFP);
    int res = goConnectPeer((char*)endpoint, (char*)(expectedFP ? expectedFP : ""));
    releaseJStringUTFChars(env, jEndpoint, endpoint);
    if (expectedFP) releaseJStringUTFChars(env, jExpectedFP, expectedFP);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeUpdatePeerNameMapping(JNIEnv *env, jclass clazz, jstring jPeerFP, jstring jNickname) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    const char *nickname = getJStringUTFChars(env, jNickname);
    if (!peerFP || !nickname) {
        if (peerFP) releaseJStringUTFChars(env, jPeerFP, peerFP);
        if (nickname) releaseJStringUTFChars(env, jNickname, nickname);
        return JNI_FALSE;
    }
    int res = goUpdatePeerNameMapping((char*)peerFP, (char*)nickname);
    releaseJStringUTFChars(env, jPeerFP, peerFP);
    releaseJStringUTFChars(env, jNickname, nickname);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeSendMessage(JNIEnv *env, jclass clazz, jstring jPeerFP, jstring jText) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    const char *text = getJStringUTFChars(env, jText);
    if (!peerFP || !text) {
        if (peerFP) releaseJStringUTFChars(env, jPeerFP, peerFP);
        if (text) releaseJStringUTFChars(env, jText, text);
        return NULL;
    }
    char *msgID = goSendMessage((char*)peerFP, (char*)text);
    releaseJStringUTFChars(env, jPeerFP, peerFP);
    releaseJStringUTFChars(env, jText, text);
    if (msgID == NULL) return NULL;
    jstring jID = (*env)->NewStringUTF(env, msgID);
    free(msgID);
    return jID;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeSendMessageBinary(
    JNIEnv *env, jclass clazz, jstring jPeerFP, jobject jDirectBuffer, jint jOffset, jint jLength) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    if (!peerFP) return NULL;
    if (jDirectBuffer == NULL || jLength <= 0) {
        releaseJStringUTFChars(env, jPeerFP, peerFP);
        return NULL;
    }
    void *directAddr = (*env)->GetDirectBufferAddress(env, jDirectBuffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, jDirectBuffer);
    if (!directAddr || capacity < (jOffset + jLength)) {
        releaseJStringUTFChars(env, jPeerFP, peerFP);
        return NULL;
    }
    const uint8_t *payload = (const uint8_t*)directAddr + jOffset;
    char *msgID = goSendMessageBinary((char*)peerFP, (uint8_t*)payload, (int)jLength);
    releaseJStringUTFChars(env, jPeerFP, peerFP);
    if (msgID == NULL) return NULL;
    jstring jID = (*env)->NewStringUTF(env, msgID);
    free(msgID);
    return jID;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeSendRawBytes(
    JNIEnv *env, jclass clazz, jstring jPeerFP, jbyteArray jPayload) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    if (!peerFP) return NULL;
    if (!jPayload) {
        releaseJStringUTFChars(env, jPeerFP, peerFP);
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, jPayload);
    if (len <= 0) {
        releaseJStringUTFChars(env, jPeerFP, peerFP);
        return NULL;
    }
    uint8_t *buf = (uint8_t*)malloc(len);
    if (!buf) {
        releaseJStringUTFChars(env, jPeerFP, peerFP);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, jPayload, 0, len, (jbyte*)buf);
    char *msgID = goSendRawBytes((char*)peerFP, (uint8_t*)buf, (int)len);
    free(buf);
    releaseJStringUTFChars(env, jPeerFP, peerFP);
    if (msgID == NULL) return NULL;
    jstring jID = (*env)->NewStringUTF(env, msgID);
    free(msgID);
    return jID;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeIsPeerOnline(JNIEnv *env, jclass clazz, jstring jPeerFP) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    if (!peerFP) return JNI_FALSE;
    int res = goIsPeerOnline((char*)peerFP);
    releaseJStringUTFChars(env, jPeerFP, peerFP);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeSendFile(
    JNIEnv *env, jclass clazz, jstring jPeerFP, jstring jFilePath, jstring jMessageID,
    jstring jFileName, jstring jCaption, jstring jEmoji) {
    const char *peerFP = getJStringUTFChars(env, jPeerFP);
    const char *filePath = getJStringUTFChars(env, jFilePath);
    const char *messageID = getJStringUTFChars(env, jMessageID);
    const char *fileName = getJStringUTFChars(env, jFileName);
    const char *caption = getJStringUTFChars(env, jCaption);
    const char *emoji = getJStringUTFChars(env, jEmoji);

    if (!peerFP || !filePath) {
        if (peerFP) releaseJStringUTFChars(env, jPeerFP, peerFP);
        if (filePath) releaseJStringUTFChars(env, jFilePath, filePath);
        if (messageID) releaseJStringUTFChars(env, jMessageID, messageID);
        if (fileName) releaseJStringUTFChars(env, jFileName, fileName);
        if (caption) releaseJStringUTFChars(env, jCaption, caption);
        if (emoji) releaseJStringUTFChars(env, jEmoji, emoji);
        return NULL;
    }

    char *resID = goSendFile((char*)peerFP, (char*)filePath, (char*)(messageID ? messageID : ""),
                             (char*)(fileName ? fileName : ""), (char*)(caption ? caption : ""), (char*)(emoji ? emoji : ""));

    releaseJStringUTFChars(env, jPeerFP, peerFP);
    releaseJStringUTFChars(env, jFilePath, filePath);
    if (messageID) releaseJStringUTFChars(env, jMessageID, messageID);
    if (fileName) releaseJStringUTFChars(env, jFileName, fileName);
    if (caption) releaseJStringUTFChars(env, jCaption, caption);
    if (emoji) releaseJStringUTFChars(env, jEmoji, emoji);

    if (resID == NULL) return NULL;
    jstring jID = (*env)->NewStringUTF(env, resID);
    free(resID);
    return jID;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeCancelFile(JNIEnv *env, jclass clazz, jstring jMessageID) {
    const char *msgID = getJStringUTFChars(env, jMessageID);
    if (!msgID) return JNI_FALSE;
    int res = goCancelFile((char*)msgID);
    releaseJStringUTFChars(env, jMessageID, msgID);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_twopchat_NativeBridge_nativeSetTorProxy(JNIEnv *env, jclass clazz, jboolean jEnabled, jstring jProxyAddr) {
    const char *addr = getJStringUTFChars(env, jProxyAddr);
    goSetTorProxy(jEnabled == JNI_TRUE ? 1 : 0, (char*)(addr ? addr : ""));
    if (addr) releaseJStringUTFChars(env, jProxyAddr, addr);
}

JNIEXPORT void JNICALL Java_com_example_twopchat_NativeBridge_nativeSetOnionAddress(JNIEnv *env, jclass clazz, jstring jAddr) {
    const char *addr = getJStringUTFChars(env, jAddr);
    if (addr) {
        goSetOnionAddress((char*)addr);
        releaseJStringUTFChars(env, jAddr, addr);
    }
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetOnionAddress(JNIEnv *env, jclass clazz) {
    char *addr = goGetOnionAddress();
    if (!addr) return NULL;
    jstring jAddr = (*env)->NewStringUTF(env, addr);
    free(addr);
    return jAddr;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeStartDiscovery(
    JNIEnv *env, jclass clazz, jstring jTrackersJSON, jstring jInfoHashesJSON, jint jPort) {
    const char *trackers = getJStringUTFChars(env, jTrackersJSON);
    const char *hashes = getJStringUTFChars(env, jInfoHashesJSON);
    int res = goStartDiscovery((char*)(trackers ? trackers : ""), (char*)(hashes ? hashes : ""), (int)jPort);
    if (trackers) releaseJStringUTFChars(env, jTrackersJSON, trackers);
    if (hashes) releaseJStringUTFChars(env, jInfoHashesJSON, hashes);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeStopDiscovery(JNIEnv *env, jclass clazz) {
    return goStopDiscovery() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeUpdateTrackers(JNIEnv *env, jclass clazz, jstring jTrackersJSON) {
    const char *trackers = getJStringUTFChars(env, jTrackersJSON);
    if (!trackers) return JNI_FALSE;
    int res = goUpdateTrackers((char*)trackers);
    releaseJStringUTFChars(env, jTrackersJSON, trackers);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeReloadIdentity(JNIEnv *env, jclass clazz) {
    return goReloadIdentity() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeAnnounceSelf(JNIEnv *env, jclass clazz, jstring jInfoHashHex, jint jPort) {
    const char *hash = getJStringUTFChars(env, jInfoHashHex);
    if (!hash) return JNI_FALSE;
    int res = goAnnounceSelf((char*)hash, (int)jPort);
    releaseJStringUTFChars(env, jInfoHashHex, hash);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeProbePeer(
    JNIEnv *env, jclass clazz, jstring jEndpointsJSON, jstring jExpectedFP) {
    const char *endpoints = getJStringUTFChars(env, jEndpointsJSON);
    const char *expectedFP = getJStringUTFChars(env, jExpectedFP);
    if (!endpoints) {
        if (expectedFP) releaseJStringUTFChars(env, jExpectedFP, expectedFP);
        return JNI_FALSE;
    }
    int res = goProbePeer((char*)endpoints, (char*)(expectedFP ? expectedFP : ""));
    releaseJStringUTFChars(env, jEndpointsJSON, endpoints);
    if (expectedFP) releaseJStringUTFChars(env, jExpectedFP, expectedFP);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetLocalSigningPublicKey(JNIEnv *env, jclass clazz) {
    char *pub = goGetLocalSigningPublicKey();
    if (!pub) return NULL;
    jstring jPub = (*env)->NewStringUTF(env, pub);
    free(pub);
    return jPub;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeSignGroupPayload(JNIEnv *env, jclass clazz, jstring jCanonicalPayload) {
    const char *payload = getJStringUTFChars(env, jCanonicalPayload);
    if (!payload) return NULL;
    char *sig = goSignGroupPayload((char*)payload);
    releaseJStringUTFChars(env, jCanonicalPayload, payload);
    if (!sig) return NULL;
    jstring jSig = (*env)->NewStringUTF(env, sig);
    free(sig);
    return jSig;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeVerifyGroupPayload(
    JNIEnv *env, jclass clazz, jstring jVerificationKey, jstring jCanonicalPayload, jstring jSignature) {
    const char *key = getJStringUTFChars(env, jVerificationKey);
    const char *payload = getJStringUTFChars(env, jCanonicalPayload);
    const char *sig = getJStringUTFChars(env, jSignature);
    if (!key || !payload || !sig) {
        if (key) releaseJStringUTFChars(env, jVerificationKey, key);
        if (payload) releaseJStringUTFChars(env, jCanonicalPayload, payload);
        if (sig) releaseJStringUTFChars(env, jSignature, sig);
        return JNI_FALSE;
    }
    int res = goVerifyGroupPayload((char*)key, (char*)payload, (char*)sig);
    releaseJStringUTFChars(env, jVerificationKey, key);
    releaseJStringUTFChars(env, jCanonicalPayload, payload);
    releaseJStringUTFChars(env, jSignature, sig);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGroupEncrypt(
    JNIEnv *env, jclass clazz, jbyteArray jEpochSecret, jbyteArray jAuthenticatedData, jbyteArray jPlaintext) {
    if (!jEpochSecret || !jPlaintext) return NULL;
    jsize secLen = (*env)->GetArrayLength(env, jEpochSecret);
    jsize ptLen = (*env)->GetArrayLength(env, jPlaintext);
    jsize adLen = jAuthenticatedData ? (*env)->GetArrayLength(env, jAuthenticatedData) : 0;

    uint8_t *secBuf = (uint8_t*)malloc(secLen);
    uint8_t *ptBuf = (uint8_t*)malloc(ptLen);
    uint8_t *adBuf = adLen > 0 ? (uint8_t*)malloc(adLen) : NULL;

    (*env)->GetByteArrayRegion(env, jEpochSecret, 0, secLen, (jbyte*)secBuf);
    (*env)->GetByteArrayRegion(env, jPlaintext, 0, ptLen, (jbyte*)ptBuf);
    if (adLen > 0 && adBuf) {
        (*env)->GetByteArrayRegion(env, jAuthenticatedData, 0, adLen, (jbyte*)adBuf);
    }

    char *json = goGroupEncrypt((uint8_t*)secBuf, (int)secLen, (uint8_t*)adBuf, (int)adLen, (uint8_t*)ptBuf, (int)ptLen);

    if (secBuf) {
        memset(secBuf, 0, secLen);
        free(secBuf);
    }
    if (ptBuf) {
        memset(ptBuf, 0, ptLen);
        free(ptBuf);
    }
    if (adBuf) free(adBuf);

    if (!json) return NULL;
    jstring jRes = (*env)->NewStringUTF(env, json);
    free(json);
    return jRes;
}

JNIEXPORT jbyteArray JNICALL Java_com_example_twopchat_NativeBridge_nativeGroupDecrypt(
    JNIEnv *env, jclass clazz, jbyteArray jEpochSecret, jbyteArray jAuthenticatedData, jstring jNonceBase64, jstring jCiphertextBase64) {
    if (!jEpochSecret || !jNonceBase64 || !jCiphertextBase64) return NULL;
    jsize secLen = (*env)->GetArrayLength(env, jEpochSecret);
    jsize adLen = jAuthenticatedData ? (*env)->GetArrayLength(env, jAuthenticatedData) : 0;

    uint8_t *secBuf = (uint8_t*)malloc(secLen);
    uint8_t *adBuf = adLen > 0 ? (uint8_t*)malloc(adLen) : NULL;

    (*env)->GetByteArrayRegion(env, jEpochSecret, 0, secLen, (jbyte*)secBuf);
    if (adLen > 0 && adBuf) {
        (*env)->GetByteArrayRegion(env, jAuthenticatedData, 0, adLen, (jbyte*)adBuf);
    }

    const char *nonce = getJStringUTFChars(env, jNonceBase64);
    const char *ciphertext = getJStringUTFChars(env, jCiphertextBase64);

    int outLen = 0;
    void *pt = goGroupDecrypt((uint8_t*)secBuf, (int)secLen, (uint8_t*)adBuf, (int)adLen, (char*)(nonce ? nonce : ""), (char*)(ciphertext ? ciphertext : ""), &outLen);

    if (secBuf) {
        memset(secBuf, 0, secLen);
        free(secBuf);
    }
    if (adBuf) free(adBuf);
    if (nonce) releaseJStringUTFChars(env, jNonceBase64, nonce);
    if (ciphertext) releaseJStringUTFChars(env, jCiphertextBase64, ciphertext);

    if (!pt || outLen <= 0) {
        if (pt) {
            memset(pt, 0, outLen > 0 ? outLen : 0);
            free(pt);
        }
        return NULL;
    }

    jbyteArray jArr = (*env)->NewByteArray(env, outLen);
    if (jArr) {
        (*env)->SetByteArrayRegion(env, jArr, 0, outLen, (jbyte*)pt);
    }
    memset(pt, 0, outLen);
    free(pt);
    return jArr;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeTriggerNatTraversal(JNIEnv *env, jclass clazz) {
    return goTriggerNatTraversal() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_example_twopchat_NativeBridge_nativeGetNatDiagnosticsJSON(JNIEnv *env, jclass clazz) {
    char *diag = goGetNatDiagnosticsJSON();
    if (!diag) return NULL;
    jstring jDiag = (*env)->NewStringUTF(env, diag);
    free(diag);
    return jDiag;
}

JNIEXPORT jboolean JNICALL Java_com_example_twopchat_NativeBridge_nativeOnNetworkChanged(JNIEnv *env, jclass clazz) {
    return goOnNetworkChanged() ? JNI_TRUE : JNI_FALSE;
}

