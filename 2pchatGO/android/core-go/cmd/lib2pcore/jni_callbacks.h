#ifndef JNI_CALLBACKS_H
#define JNI_CALLBACKS_H

#if defined(__has_include)
  #if __has_include(<jni.h>)
    #include <jni.h>
  #else
    #include <stdint.h>
    typedef uint8_t jboolean;
    typedef int8_t jbyte;
    typedef int32_t jint;
    typedef int64_t jlong;
    typedef float jfloat;
    typedef double jdouble;
    typedef jint jsize;
    typedef void* jobject;
    typedef jobject jclass;
    typedef jobject jstring;
    typedef jobject jbyteArray;
    typedef struct JNIEnv_ JNIEnv;
    typedef struct JavaVM_ JavaVM;
    typedef void* jmethodID;
    #define JNI_FALSE 0
    #define JNI_TRUE 1
    #define JNIEXPORT
    #define JNICALL
    #define JNI_OK 0
    #define JNI_ERR (-1)
    #define JNI_VERSION_1_6 0x00010006
  #endif
#else
  #include <jni.h>
#endif
#include <stdlib.h>

void callbackOnPeerConnected(const char *peerFP, const char *endpoint);
void callbackOnPeerDisconnected(const char *peerFP, const char *reason);
void callbackOnMessageReceived(const char *peerFP, const jbyte *payload, jsize len, const char *messageID);
void callbackOnError(int code, const char *msg);
void callbackOnPeerDiscovered(const char *infoHashHex, const char *endpoint, const char *source);
void callbackOnFileProgress(const char *peerFP, const char *messageID, jlong transferred, jlong total, jdouble speedKbps);

const char* getJStringUTFChars(JNIEnv *env, jstring str);
void releaseJStringUTFChars(JNIEnv *env, jstring str, const char *chars);
jstring createJString(JNIEnv *env, const char *str);
jstring nullJString(void);
jbyteArray nullJByteArray(void);
jbyteArray createJByteArray(JNIEnv *env, const jbyte *bytes, jsize len);
jsize getByteArrayLength(JNIEnv *env, jbyteArray array);
void getByteArrayRegion(JNIEnv *env, jbyteArray array, jsize start, jsize len, jbyte *buf);

#endif // JNI_CALLBACKS_H
