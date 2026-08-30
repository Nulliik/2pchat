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
    typedef jobject jarray;
    typedef jobject jbyteArray;
    typedef void* jmethodID;
    typedef void* jfieldID;

    #define JNI_FALSE 0
    #define JNI_TRUE 1
    #define JNIEXPORT
    #define JNICALL
    #define JNI_OK 0
    #define JNI_ERR (-1)
    #define JNI_EDETACHED (-2)
    #define JNI_VERSION_1_6 0x00010006

    struct JNINativeInterface_;
    struct JNIInvokeInterface_;
    typedef const struct JNINativeInterface_* JNIEnv;
    typedef const struct JNIInvokeInterface_* JavaVM;

    struct JNIInvokeInterface_ {
        void* reserved0;
        void* reserved1;
        void* reserved2;
        jint (*DestroyJavaVM)(JavaVM*);
        jint (*AttachCurrentThread)(JavaVM*, void**, void*);
        jint (*DetachCurrentThread)(JavaVM*);
        jint (*GetEnv)(JavaVM*, void**, jint);
        jint (*AttachCurrentThreadAsDaemon)(JavaVM*, void**, void*);
    };

    struct JNINativeInterface_ {
        void* reserved0;
        void* reserved1;
        void* reserved2;
        void* reserved3;
        jint (*GetVersion)(JNIEnv*);
        jclass (*DefineClass)(JNIEnv*, const char*, jobject, const jbyte*, jsize);
        jclass (*FindClass)(JNIEnv*, const char*);
        void* reserved4;
        void* reserved5;
        void* reserved6;
        jclass (*GetSuperclass)(JNIEnv*, jclass);
        jboolean (*IsAssignableFrom)(JNIEnv*, jclass, jclass);
        void* reserved7;
        jint (*Throw)(JNIEnv*, jobject);
        jint (*ThrowNew)(JNIEnv*, jclass, const char*);
        jobject (*ExceptionOccurred)(JNIEnv*);
        void (*ExceptionDescribe)(JNIEnv*);
        void (*ExceptionClear)(JNIEnv*);
        void (*FatalError)(JNIEnv*, const char*);
        void* reserved8;
        void* reserved9;
        jobject (*NewGlobalRef)(JNIEnv*, jobject);
        void (*DeleteGlobalRef)(JNIEnv*, jobject);
        void (*DeleteLocalRef)(JNIEnv*, jobject);
        jboolean (*IsSameObject)(JNIEnv*, jobject, jobject);
        void* reserved10;
        void* reserved11;
        jint (*EnsureLocalCapacity)(JNIEnv*, jint);
        void* reserved12;
        void* reserved13;
        void* reserved14;
        void* reserved15;
        jmethodID (*GetMethodID)(JNIEnv*, jclass, const char*, const char*);
        void* reserved16[57];
        jmethodID (*GetStaticMethodID)(JNIEnv*, jclass, const char*, const char*);
        void* reserved17[30];
        void (*CallStaticVoidMethod)(JNIEnv*, jclass, jmethodID, ...);
        void* reserved18[40];
        jstring (*NewStringUTF)(JNIEnv*, const char*);
        jsize (*GetStringUTFLength)(JNIEnv*, jstring);
        const char* (*GetStringUTFChars)(JNIEnv*, jstring, jboolean*);
        void (*ReleaseStringUTFChars)(JNIEnv*, jstring, const char*);
        jsize (*GetArrayLength)(JNIEnv*, jarray);
        void* reserved19[8];
        jbyteArray (*NewByteArray)(JNIEnv*, jsize);
        void* reserved20[16];
        void (*GetByteArrayRegion)(JNIEnv*, jbyteArray, jsize, jsize, jbyte*);
        void* reserved21[15];
        void (*SetByteArrayRegion)(JNIEnv*, jbyteArray, jsize, jsize, const jbyte*);
        void* reserved22[16];
        jboolean (*ExceptionCheck)(JNIEnv*);
        jobject (*NewDirectByteBuffer)(JNIEnv*, void*, jlong);
        void* (*GetDirectBufferAddress)(JNIEnv*, jobject);
        jlong (*GetDirectBufferCapacity)(JNIEnv*, jobject);
    };
  #endif
#else
  #include <jni.h>
#endif
#include <stdlib.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Reverse Callbacks (Go -> Java)
void callbackOnPeerConnected(const char *peerFP, const char *endpoint);
void callbackOnPeerDisconnected(const char *peerFP, const char *reason);
void callbackOnMessageReceived(const char *peerFP, const jbyte *payload, jsize len, const char *messageID);
void callbackOnError(int code, const char *msg);
void callbackOnPeerDiscovered(const char *infoHashHex, const char *endpoint, const char *source);
void callbackOnFileProgress(const char *peerFP, const char *messageID, jlong transferred, jlong total, jdouble speedKbps);
void callbackOnTrackerStatus(const char *trackerURL, jboolean success, jint peerCount, jlong elapsedMs, const char *detail);

// Helper functions for C
const char* getJStringUTFChars(JNIEnv *env, jstring str);
void releaseJStringUTFChars(JNIEnv *env, jstring str, const char *chars);
jstring createJString(JNIEnv *env, const char *str);
jstring nullJString(void);
jbyteArray nullJByteArray(void);
jbyteArray createJByteArray(JNIEnv *env, const jbyte *bytes, jsize len);
jsize getByteArrayLength(JNIEnv *env, jbyteArray array);
void getByteArrayRegion(JNIEnv *env, jbyteArray array, jsize start, jsize len, jbyte *buf);
void* getDirectBufferAddress(JNIEnv *env, jobject buf);
jlong getDirectBufferCapacity(JNIEnv *env, jobject buf);
jobject createDirectByteBuffer(JNIEnv *env, void *address, jlong capacity);

#ifdef __cplusplus
}
#endif

#endif // JNI_CALLBACKS_H
