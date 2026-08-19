package main

/*
#include "jni_callbacks.h"
*/
import "C"
import (
	"fmt"
	"unsafe"
	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
)

func init() {
	bridge.GetManager().SetCallbacks(
		session.EventCallbacks{
			OnPeerConnected: func(peerFP, endpoint string) {
				cFP := C.CString(peerFP)
				cEndp := C.CString(endpoint)
				defer C.free(unsafe.Pointer(cFP))
				defer C.free(unsafe.Pointer(cEndp))
				C.callbackOnPeerConnected(cFP, cEndp)
			},
			OnPeerDisconnected: func(peerFP, reason string) {
				cFP := C.CString(peerFP)
				cReason := C.CString(reason)
				defer C.free(unsafe.Pointer(cFP))
				defer C.free(unsafe.Pointer(cReason))
				C.callbackOnPeerDisconnected(cFP, cReason)
			},
			OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
				cFP := C.CString(peerFP)
				cMsgID := C.CString(messageID)
				defer C.free(unsafe.Pointer(cFP))
				defer C.free(unsafe.Pointer(cMsgID))

				var pPtr *C.jbyte
				if len(payload) > 0 {
					pPtr = (*C.jbyte)(unsafe.Pointer(&payload[0]))
				}
				C.callbackOnMessageReceived(cFP, pPtr, C.jsize(len(payload)), cMsgID)
			},
			OnError: func(code int, msg string) {
				cMsg := C.CString(msg)
				defer C.free(unsafe.Pointer(cMsg))
				C.callbackOnError(C.int(code), cMsg)
			},
			OnFileProgress: func(peerFP, messageID string, transferred, total int64, speed float64) {
				cFP := C.CString(peerFP)
				cMsgID := C.CString(messageID)
				defer C.free(unsafe.Pointer(cFP))
				defer C.free(unsafe.Pointer(cMsgID))
				C.callbackOnFileProgress(cFP, cMsgID, C.jlong(transferred), C.jlong(total), C.jdouble(speed))
			},
		},
		func(infoHashHex, endpoint, source string) {
			cHash := C.CString(infoHashHex)
			cEndp := C.CString(endpoint)
			cSrc := C.CString(source)
			defer C.free(unsafe.Pointer(cHash))
			defer C.free(unsafe.Pointer(cEndp))
			defer C.free(unsafe.Pointer(cSrc))
			C.callbackOnPeerDiscovered(cHash, cEndp, cSrc)
		},
	)
}

func main() {}

//export Java_com_example_twopchat_NativeBridge_nativeSetStorageDir
func Java_com_example_twopchat_NativeBridge_nativeSetStorageDir(env *C.JNIEnv, clazz C.jclass, jDir C.jstring) {
	cStr := C.getJStringUTFChars(env, jDir)
	if cStr == nil {
		return
	}
	goStr := C.GoString(cStr)
	C.releaseJStringUTFChars(env, jDir, cStr)
	bridge.GetManager().SetStorageDir(goStr)
}

//export Java_com_example_twopchat_NativeBridge_nativeInit
func Java_com_example_twopchat_NativeBridge_nativeInit(env *C.JNIEnv, clazz C.jclass) C.jboolean {
	err := bridge.GetManager().Init()
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeEcho
func Java_com_example_twopchat_NativeBridge_nativeEcho(env *C.JNIEnv, clazz C.jclass, jMsg C.jstring) C.jstring {
	cStr := C.getJStringUTFChars(env, jMsg)
	if cStr == nil {
		return C.nullJString()
	}
	goStr := C.GoString(cStr)
	C.releaseJStringUTFChars(env, jMsg, cStr)

	resp := "Echo from Go core: " + goStr
	cResp := C.CString(resp)
	defer C.free(unsafe.Pointer(cResp))
	return C.createJString(env, cResp)
}

//export Java_com_example_twopchat_NativeBridge_nativeGetLocalIdentityJSON
func Java_com_example_twopchat_NativeBridge_nativeGetLocalIdentityJSON(env *C.JNIEnv, clazz C.jclass) C.jstring {
	jsonStr, err := bridge.GetManager().GetLocalIdentityJSON()
	if err != nil {
		return C.nullJString()
	}
	cResp := C.CString(jsonStr)
	defer C.free(unsafe.Pointer(cResp))
	return C.createJString(env, cResp)
}

//export Java_com_example_twopchat_NativeBridge_nativeGetFingerprint
func Java_com_example_twopchat_NativeBridge_nativeGetFingerprint(env *C.JNIEnv, clazz C.jclass, jPub C.jbyteArray) C.jstring {
	length := int(C.getByteArrayLength(env, jPub))
	if length != crypto.KeySize {
		return C.nullJString()
	}
	buf := make([]byte, length)
	C.getByteArrayRegion(env, jPub, 0, C.jsize(length), (*C.jbyte)(unsafe.Pointer(&buf[0])))

	fp := crypto.Fingerprint(buf)
	cFp := C.CString(fp)
	defer C.free(unsafe.Pointer(cFp))
	return C.createJString(env, cFp)
}

//export Java_com_example_twopchat_NativeBridge_nativeGetSafetyNumber
func Java_com_example_twopchat_NativeBridge_nativeGetSafetyNumber(
	env *C.JNIEnv,
	clazz C.jclass,
	jMyPub C.jbyteArray,
	jTheirPub C.jbyteArray,
	jMyVerify C.jbyteArray,
	jTheirVerify C.jbyteArray,
) C.jstring {
	myPubLen := int(C.getByteArrayLength(env, jMyPub))
	theirPubLen := int(C.getByteArrayLength(env, jTheirPub))
	if myPubLen != crypto.KeySize || theirPubLen != crypto.KeySize {
		return C.nullJString()
	}

	myPub := make([]byte, myPubLen)
	theirPub := make([]byte, theirPubLen)
	C.getByteArrayRegion(env, jMyPub, 0, C.jsize(myPubLen), (*C.jbyte)(unsafe.Pointer(&myPub[0])))
	C.getByteArrayRegion(env, jTheirPub, 0, C.jsize(theirPubLen), (*C.jbyte)(unsafe.Pointer(&theirPub[0])))

	var myVerify, theirVerify []byte
	myVerifyLen := int(C.getByteArrayLength(env, jMyVerify))
	theirVerifyLen := int(C.getByteArrayLength(env, jTheirVerify))
	if myVerifyLen == crypto.KeySize && theirVerifyLen == crypto.KeySize {
		myVerify = make([]byte, myVerifyLen)
		theirVerify = make([]byte, theirVerifyLen)
		C.getByteArrayRegion(env, jMyVerify, 0, C.jsize(myVerifyLen), (*C.jbyte)(unsafe.Pointer(&myVerify[0])))
		C.getByteArrayRegion(env, jTheirVerify, 0, C.jsize(theirVerifyLen), (*C.jbyte)(unsafe.Pointer(&theirVerify[0])))
	}

	safetyNum, err := crypto.SafetyNumber(myPub, theirPub, myVerify, theirVerify)
	if err != nil {
		return C.nullJString()
	}

	cNum := C.CString(safetyNum)
	defer C.free(unsafe.Pointer(cNum))
	return C.createJString(env, cNum)
}

//export Java_com_example_twopchat_NativeBridge_nativeStartListener
func Java_com_example_twopchat_NativeBridge_nativeStartListener(
	env *C.JNIEnv,
	clazz C.jclass,
	jPort C.jint,
) C.jboolean {
	err := bridge.GetManager().StartListener(int(jPort))
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeStopListener
func Java_com_example_twopchat_NativeBridge_nativeStopListener(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jboolean {
	err := bridge.GetManager().StopListener()
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeConnectPeer
func Java_com_example_twopchat_NativeBridge_nativeConnectPeer(
	env *C.JNIEnv,
	clazz C.jclass,
	jEndpoint C.jstring,
	jExpectedFP C.jstring,
) C.jboolean {
	cEndpoint := C.getJStringUTFChars(env, jEndpoint)
	if cEndpoint == nil {
		return C.JNI_FALSE
	}
	endpoint := C.GoString(cEndpoint)
	C.releaseJStringUTFChars(env, jEndpoint, cEndpoint)

	var expectedFP string
	cFP := C.getJStringUTFChars(env, jExpectedFP)
	if cFP != nil {
		expectedFP = C.GoString(cFP)
		C.releaseJStringUTFChars(env, jExpectedFP, cFP)
	}

	err := bridge.GetManager().ConnectPeer(endpoint, expectedFP)
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeSendMessage
func Java_com_example_twopchat_NativeBridge_nativeSendMessage(
	env *C.JNIEnv,
	clazz C.jclass,
	jPeerFP C.jstring,
	jText C.jstring,
) C.jstring {
	cFP := C.getJStringUTFChars(env, jPeerFP)
	if cFP == nil {
		return C.nullJString()
	}
	peerFP := C.GoString(cFP)
	C.releaseJStringUTFChars(env, jPeerFP, cFP)

	cText := C.getJStringUTFChars(env, jText)
	if cText == nil {
		return C.nullJString()
	}
	text := C.GoString(cText)
	C.releaseJStringUTFChars(env, jText, cText)

	msgID, err := bridge.GetManager().SendMessage(peerFP, text)
	if err != nil {
		return C.nullJString()
	}

	cID := C.CString(msgID)
	defer C.free(unsafe.Pointer(cID))
	return C.createJString(env, cID)
}

//export Java_com_example_twopchat_NativeBridge_nativeIsPeerOnline
func Java_com_example_twopchat_NativeBridge_nativeIsPeerOnline(
	env *C.JNIEnv,
	clazz C.jclass,
	jPeerFP C.jstring,
) C.jboolean {
	cFP := C.getJStringUTFChars(env, jPeerFP)
	if cFP == nil {
		return C.JNI_FALSE
	}
	peerFP := C.GoString(cFP)
	C.releaseJStringUTFChars(env, jPeerFP, cFP)

	if bridge.GetManager().IsPeerOnline(peerFP) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_twopchat_NativeBridge_nativeSendFile
func Java_com_example_twopchat_NativeBridge_nativeSendFile(
	env *C.JNIEnv,
	clazz C.jclass,
	jPeerFP C.jstring,
	jFilePath C.jstring,
	jMessageID C.jstring,
	jFileName C.jstring,
	jCaption C.jstring,
	jEmoji C.jstring,
) C.jstring {
	cFP := C.getJStringUTFChars(env, jPeerFP)
	if cFP == nil {
		return C.nullJString()
	}
	peerFP := C.GoString(cFP)
	C.releaseJStringUTFChars(env, jPeerFP, cFP)

	cPath := C.getJStringUTFChars(env, jFilePath)
	if cPath == nil {
		return C.nullJString()
	}
	filePath := C.GoString(cPath)
	C.releaseJStringUTFChars(env, jFilePath, cPath)

	var messageID string
	cMsgID := C.getJStringUTFChars(env, jMessageID)
	if cMsgID != nil {
		messageID = C.GoString(cMsgID)
		C.releaseJStringUTFChars(env, jMessageID, cMsgID)
	}

	var fileName string
	cName := C.getJStringUTFChars(env, jFileName)
	if cName != nil {
		fileName = C.GoString(cName)
		C.releaseJStringUTFChars(env, jFileName, cName)
	}

	var caption string
	cCaption := C.getJStringUTFChars(env, jCaption)
	if cCaption != nil {
		caption = C.GoString(cCaption)
		C.releaseJStringUTFChars(env, jCaption, cCaption)
	}

	var emoji string
	cEmoji := C.getJStringUTFChars(env, jEmoji)
	if cEmoji != nil {
		emoji = C.GoString(cEmoji)
		C.releaseJStringUTFChars(env, jEmoji, cEmoji)
	}

	metaID, err := bridge.GetManager().SendFile(peerFP, filePath, messageID, fileName, caption, emoji)
	if err != nil {
		return C.nullJString()
	}

	cID := C.CString(metaID)
	defer C.free(unsafe.Pointer(cID))
	return C.createJString(env, cID)
}

//export Java_com_example_twopchat_NativeBridge_nativeCancelFile
func Java_com_example_twopchat_NativeBridge_nativeCancelFile(
	env *C.JNIEnv,
	clazz C.jclass,
	jMessageID C.jstring,
) C.jboolean {
	cMsgID := C.getJStringUTFChars(env, jMessageID)
	if cMsgID == nil {
		return C.JNI_FALSE
	}
	messageID := C.GoString(cMsgID)
	C.releaseJStringUTFChars(env, jMessageID, cMsgID)

	if bridge.GetManager().CancelFile(messageID) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_twopchat_NativeBridge_nativeSetTorProxy
func Java_com_example_twopchat_NativeBridge_nativeSetTorProxy(
	env *C.JNIEnv,
	clazz C.jclass,
	jEnabled C.jboolean,
	jProxyAddr C.jstring,
) {
	enabled := jEnabled == C.JNI_TRUE
	var proxyAddr string
	cAddr := C.getJStringUTFChars(env, jProxyAddr)
	if cAddr != nil {
		proxyAddr = C.GoString(cAddr)
		C.releaseJStringUTFChars(env, jProxyAddr, cAddr)
	}
	bridge.GetManager().SetTorProxy(enabled, proxyAddr)
}

//export Java_com_example_twopchat_NativeBridge_nativeSetOnionAddress
func Java_com_example_twopchat_NativeBridge_nativeSetOnionAddress(
	env *C.JNIEnv,
	clazz C.jclass,
	jAddr C.jstring,
) {
	var onionAddr string
	cAddr := C.getJStringUTFChars(env, jAddr)
	if cAddr != nil {
		onionAddr = C.GoString(cAddr)
		C.releaseJStringUTFChars(env, jAddr, cAddr)
	}
	bridge.GetManager().SetOnionAddress(onionAddr)
}

//export Java_com_example_twopchat_NativeBridge_nativeGetOnionAddress
func Java_com_example_twopchat_NativeBridge_nativeGetOnionAddress(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	addr := bridge.GetManager().GetOnionAddress()
	if addr == "" {
		return C.nullJString()
	}
	cAddr := C.CString(addr)
	defer C.free(unsafe.Pointer(cAddr))
	return C.createJString(env, cAddr)
}

//export Java_com_example_twopchat_NativeBridge_nativeStartDiscovery
func Java_com_example_twopchat_NativeBridge_nativeStartDiscovery(
	env *C.JNIEnv,
	clazz C.jclass,
	jTrackersJSON C.jstring,
	jInfoHashesJSON C.jstring,
	jPort C.jint,
) C.jboolean {
	var trackersJSON string
	cTrackers := C.getJStringUTFChars(env, jTrackersJSON)
	if cTrackers != nil {
		trackersJSON = C.GoString(cTrackers)
		C.releaseJStringUTFChars(env, jTrackersJSON, cTrackers)
	}

	var infoHashesJSON string
	cHashes := C.getJStringUTFChars(env, jInfoHashesJSON)
	if cHashes != nil {
		infoHashesJSON = C.GoString(cHashes)
		C.releaseJStringUTFChars(env, jInfoHashesJSON, cHashes)
	}

	err := bridge.GetManager().StartDiscovery(trackersJSON, infoHashesJSON, int(jPort))
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeUpdateTrackers
func Java_com_example_twopchat_NativeBridge_nativeUpdateTrackers(
	env *C.JNIEnv,
	clazz C.jclass,
	jTrackersJSON C.jstring,
) C.jboolean {
	var trackersJSON string
	cTrackers := C.getJStringUTFChars(env, jTrackersJSON)
	if cTrackers != nil {
		trackersJSON = C.GoString(cTrackers)
		C.releaseJStringUTFChars(env, jTrackersJSON, cTrackers)
	}

	err := bridge.GetManager().UpdateTrackers(trackersJSON)
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeReloadIdentity
func Java_com_example_twopchat_NativeBridge_nativeReloadIdentity(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jboolean {
	err := bridge.GetManager().ReloadIdentity()
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeStopDiscovery
func Java_com_example_twopchat_NativeBridge_nativeStopDiscovery(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jboolean {
	err := bridge.GetManager().StopDiscovery()
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeAnnounceSelf
func Java_com_example_twopchat_NativeBridge_nativeAnnounceSelf(
	env *C.JNIEnv,
	clazz C.jclass,
	jInfoHashHex C.jstring,
	jPort C.jint,
) C.jboolean {
	cHash := C.getJStringUTFChars(env, jInfoHashHex)
	if cHash == nil {
		return C.JNI_FALSE
	}
	infoHashHex := C.GoString(cHash)
	C.releaseJStringUTFChars(env, jInfoHashHex, cHash)

	err := bridge.GetManager().AnnounceSelf(infoHashHex, int(jPort))
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

//export Java_com_example_twopchat_NativeBridge_nativeProbePeer
func Java_com_example_twopchat_NativeBridge_nativeProbePeer(
	env *C.JNIEnv,
	clazz C.jclass,
	jEndpointsJSON C.jstring,
	jExpectedFP C.jstring,
) C.jboolean {
	cEndpoints := C.getJStringUTFChars(env, jEndpointsJSON)
	if cEndpoints == nil {
		return C.JNI_FALSE
	}
	endpointsJSON := C.GoString(cEndpoints)
	C.releaseJStringUTFChars(env, jEndpointsJSON, cEndpoints)

	var expectedFP string
	cFP := C.getJStringUTFChars(env, jExpectedFP)
	if cFP != nil {
		expectedFP = C.GoString(cFP)
		C.releaseJStringUTFChars(env, jExpectedFP, cFP)
	}

	err := bridge.GetManager().ProbePeer(endpointsJSON, expectedFP)
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}

func readJByteArray(env *C.JNIEnv, arr C.jbyteArray) []byte {
	length := int(C.getByteArrayLength(env, arr))
	if length <= 0 {
		return []byte{}
	}
	buf := make([]byte, length)
	C.getByteArrayRegion(env, arr, 0, C.jsize(length), (*C.jbyte)(unsafe.Pointer(&buf[0])))
	return buf
}

func createJByteArrayFromSlice(env *C.JNIEnv, data []byte) C.jbyteArray {
	if data == nil {
		return C.nullJByteArray()
	}
	if len(data) == 0 {
		return C.createJByteArray(env, nil, 0)
	}
	return C.createJByteArray(env, (*C.jbyte)(unsafe.Pointer(&data[0])), C.jsize(len(data)))
}

//export Java_com_example_twopchat_NativeBridge_nativeGetLocalSigningPublicKey
func Java_com_example_twopchat_NativeBridge_nativeGetLocalSigningPublicKey(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jstring {
	pub, err := bridge.GetManager().GetLocalSigningPublicKey()
	if err != nil {
		return C.nullJString()
	}
	cPub := C.CString(pub)
	defer C.free(unsafe.Pointer(cPub))
	return C.createJString(env, cPub)
}

//export Java_com_example_twopchat_NativeBridge_nativeSignGroupPayload
func Java_com_example_twopchat_NativeBridge_nativeSignGroupPayload(
	env *C.JNIEnv,
	clazz C.jclass,
	jCanonicalPayload C.jstring,
) C.jstring {
	cPayload := C.getJStringUTFChars(env, jCanonicalPayload)
	if cPayload == nil {
		return C.nullJString()
	}
	payload := C.GoString(cPayload)
	C.releaseJStringUTFChars(env, jCanonicalPayload, cPayload)

	sig, err := bridge.GetManager().SignGroupPayload(payload)
	if err != nil {
		return C.nullJString()
	}

	cSig := C.CString(sig)
	defer C.free(unsafe.Pointer(cSig))
	return C.createJString(env, cSig)
}

//export Java_com_example_twopchat_NativeBridge_nativeVerifyGroupPayload
func Java_com_example_twopchat_NativeBridge_nativeVerifyGroupPayload(
	env *C.JNIEnv,
	clazz C.jclass,
	jVerificationKey C.jstring,
	jCanonicalPayload C.jstring,
	jSignature C.jstring,
) C.jboolean {
	cKey := C.getJStringUTFChars(env, jVerificationKey)
	if cKey == nil {
		return C.JNI_FALSE
	}
	key := C.GoString(cKey)
	C.releaseJStringUTFChars(env, jVerificationKey, cKey)

	cPayload := C.getJStringUTFChars(env, jCanonicalPayload)
	if cPayload == nil {
		return C.JNI_FALSE
	}
	payload := C.GoString(cPayload)
	C.releaseJStringUTFChars(env, jCanonicalPayload, cPayload)

	cSig := C.getJStringUTFChars(env, jSignature)
	if cSig == nil {
		return C.JNI_FALSE
	}
	sig := C.GoString(cSig)
	C.releaseJStringUTFChars(env, jSignature, cSig)

	if bridge.GetManager().VerifyGroupPayload(key, payload, sig) {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_twopchat_NativeBridge_nativeGroupEncrypt
func Java_com_example_twopchat_NativeBridge_nativeGroupEncrypt(
	env *C.JNIEnv,
	clazz C.jclass,
	jEpochSecret C.jbyteArray,
	jAuthenticatedData C.jbyteArray,
	jPlaintext C.jbyteArray,
) C.jstring {
	epochSecret := readJByteArray(env, jEpochSecret)
	authenticatedData := readJByteArray(env, jAuthenticatedData)
	plaintext := readJByteArray(env, jPlaintext)

	nonceB64, ciphertextB64, err := bridge.GetManager().GroupEncrypt(epochSecret, authenticatedData, plaintext)
	if err != nil {
		return C.nullJString()
	}

	res := fmt.Sprintf(`{"nonce":"%s","ciphertext":"%s"}`, nonceB64, ciphertextB64)
	cRes := C.CString(res)
	defer C.free(unsafe.Pointer(cRes))
	return C.createJString(env, cRes)
}

//export Java_com_example_twopchat_NativeBridge_nativeGroupDecrypt
func Java_com_example_twopchat_NativeBridge_nativeGroupDecrypt(
	env *C.JNIEnv,
	clazz C.jclass,
	jEpochSecret C.jbyteArray,
	jAuthenticatedData C.jbyteArray,
	jNonceBase64 C.jstring,
	jCiphertextBase64 C.jstring,
) C.jbyteArray {
	epochSecret := readJByteArray(env, jEpochSecret)
	authenticatedData := readJByteArray(env, jAuthenticatedData)

	cNonce := C.getJStringUTFChars(env, jNonceBase64)
	if cNonce == nil {
		return C.nullJByteArray()
	}
	nonceB64 := C.GoString(cNonce)
	C.releaseJStringUTFChars(env, jNonceBase64, cNonce)

	cCiphertext := C.getJStringUTFChars(env, jCiphertextBase64)
	if cCiphertext == nil {
		return C.nullJByteArray()
	}
	ciphertextB64 := C.GoString(cCiphertext)
	C.releaseJStringUTFChars(env, jCiphertextBase64, cCiphertext)

	plaintext, err := bridge.GetManager().GroupDecrypt(epochSecret, authenticatedData, nonceB64, ciphertextB64)
	if err != nil {
		return C.nullJByteArray()
	}

	return createJByteArrayFromSlice(env, plaintext)
}

//export Java_com_example_twopchat_NativeBridge_nativeTriggerNatTraversal
func Java_com_example_twopchat_NativeBridge_nativeTriggerNatTraversal(env *C.JNIEnv, clazz C.jclass) C.jboolean {
	ok := bridge.GetManager().TriggerNatTraversal()
	if ok {
		return C.JNI_TRUE
	}
	return C.JNI_FALSE
}

//export Java_com_example_twopchat_NativeBridge_nativeGetNatDiagnosticsJSON
func Java_com_example_twopchat_NativeBridge_nativeGetNatDiagnosticsJSON(env *C.JNIEnv, clazz C.jclass) C.jstring {
	jsonStr := bridge.GetManager().GetNatDiagnosticsJSON()
	cStr := C.CString(jsonStr)
	defer C.free(unsafe.Pointer(cStr))
	return C.createJString(env, cStr)
}

//export Java_com_example_twopchat_NativeBridge_nativeOnNetworkChanged
func Java_com_example_twopchat_NativeBridge_nativeOnNetworkChanged(
	env *C.JNIEnv,
	clazz C.jclass,
) C.jboolean {
	err := bridge.GetManager().OnNetworkChanged()
	if err != nil {
		return C.JNI_FALSE
	}
	return C.JNI_TRUE
}



