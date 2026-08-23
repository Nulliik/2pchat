package main

/*
#include "jni_callbacks.h"
*/
import "C"
import (
	"fmt"
	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"unsafe"
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

//export goSetStorageDir
func goSetStorageDir(cDir *C.char) {
	if cDir == nil {
		return
	}
	bridge.GetManager().SetStorageDir(C.GoString(cDir))
}

//export goInit
func goInit() C.int {
	err := bridge.GetManager().Init()
	if err != nil {
		return 0
	}
	return 1
}

//export goSetNickname
func goSetNickname(cNick *C.char) C.int {
	if cNick == nil {
		return 0
	}
	bridge.GetManager().SetNickname(C.GoString(cNick))
	return 1
}

//export goEcho
func goEcho(cMsg *C.char) *C.char {
	if cMsg == nil {
		return nil
	}
	goStr := C.GoString(cMsg)
	resp := "Echo from Go core: " + goStr
	return C.CString(resp)
}

//export goGetLocalIdentityJSON
func goGetLocalIdentityJSON() *C.char {
	jsonStr, err := bridge.GetManager().GetLocalIdentityJSON()
	if err != nil {
		return nil
	}
	return C.CString(jsonStr)
}

//export goGetLocalSeedMnemonic
func goGetLocalSeedMnemonic() *C.char {
	mnemonic, err := bridge.GetManager().GetLocalSeedMnemonic()
	if err != nil {
		return nil
	}
	return C.CString(mnemonic)
}

//export goRestoreFromMnemonic
func goRestoreFromMnemonic(cNick, cMnemonic, cAbout *C.char) C.int {
	var nickname, mnemonic, about string
	if cNick != nil {
		nickname = C.GoString(cNick)
	}
	if cMnemonic != nil {
		mnemonic = C.GoString(cMnemonic)
	}
	if cAbout != nil {
		about = C.GoString(cAbout)
	}
	err := bridge.GetManager().RestoreFromMnemonic(nickname, mnemonic, about)
	if err != nil {
		return 0
	}
	return 1
}

//export goGetFingerprint
func goGetFingerprint(pubBytes *C.uint8_t, length C.int) *C.char {
	if pubBytes == nil || int(length) != crypto.KeySize {
		return nil
	}
	buf := C.GoBytes(unsafe.Pointer(pubBytes), length)
	fp := crypto.Fingerprint(buf)
	return C.CString(fp)
}

//export goGetSafetyNumber
func goGetSafetyNumber(myPub *C.uint8_t, myPubLen C.int, theirPub *C.uint8_t, theirPubLen C.int, myVerify *C.uint8_t, myVerifyLen C.int, theirVerify *C.uint8_t, theirVerifyLen C.int) *C.char {
	if myPub == nil || theirPub == nil || int(myPubLen) != crypto.KeySize || int(theirPubLen) != crypto.KeySize {
		return nil
	}
	myPubSlice := C.GoBytes(unsafe.Pointer(myPub), myPubLen)
	theirPubSlice := C.GoBytes(unsafe.Pointer(theirPub), theirPubLen)

	var myVerifySlice, theirVerifySlice []byte
	if myVerify != nil && int(myVerifyLen) == crypto.KeySize {
		myVerifySlice = C.GoBytes(unsafe.Pointer(myVerify), myVerifyLen)
	}
	if theirVerify != nil && int(theirVerifyLen) == crypto.KeySize {
		theirVerifySlice = C.GoBytes(unsafe.Pointer(theirVerify), theirVerifyLen)
	}

	safetyNum, err := crypto.SafetyNumber(myPubSlice, theirPubSlice, myVerifySlice, theirVerifySlice)
	if err != nil {
		return nil
	}
	return C.CString(safetyNum)
}

//export goStartListener
func goStartListener(port C.int) C.int {
	err := bridge.GetManager().StartListener(int(port))
	if err != nil {
		return 0
	}
	return 1
}

//export goStopListener
func goStopListener() C.int {
	err := bridge.GetManager().StopListener()
	if err != nil {
		return 0
	}
	return 1
}

//export goConnectPeer
func goConnectPeer(cEndpoint, cExpectedFP *C.char) C.int {
	if cEndpoint == nil {
		return 0
	}
	endpoint := C.GoString(cEndpoint)
	var expectedFP string
	if cExpectedFP != nil {
		expectedFP = C.GoString(cExpectedFP)
	}
	err := bridge.GetManager().ConnectPeer(endpoint, expectedFP)
	if err != nil {
		return 0
	}
	return 1
}

//export goUpdatePeerNameMapping
func goUpdatePeerNameMapping(cPeerFP, cNick *C.char) C.int {
	if cPeerFP == nil || cNick == nil {
		return 0
	}
	peerFP := C.GoString(cPeerFP)
	nick := C.GoString(cNick)
	bridge.GetManager().UpdatePeerNameMapping(peerFP, nick)
	return 1
}

//export goSendMessage
func goSendMessage(cPeerFP, cText *C.char) *C.char {
	if cPeerFP == nil || cText == nil {
		return nil
	}
	peerFP := C.GoString(cPeerFP)
	text := C.GoString(cText)
	msgID, err := bridge.GetManager().SendMessage(peerFP, text)
	if err != nil {
		return nil
	}
	return C.CString(msgID)
}

//export goSendMessageBinary
func goSendMessageBinary(cPeerFP *C.char, payload *C.uint8_t, length C.int) *C.char {
	if cPeerFP == nil || payload == nil || length <= 0 {
		return nil
	}
	peerFP := C.GoString(cPeerFP)
	payloadBytes := C.GoBytes(unsafe.Pointer(payload), length)
	msgID, err := bridge.GetManager().SendMessageBinary(peerFP, payloadBytes)
	if err != nil {
		return nil
	}
	return C.CString(msgID)
}

//export goSendRawBytes
func goSendRawBytes(cPeerFP *C.char, payload *C.uint8_t, length C.int) *C.char {
	if cPeerFP == nil || payload == nil || length <= 0 {
		return nil
	}
	peerFP := C.GoString(cPeerFP)
	payloadBytes := C.GoBytes(unsafe.Pointer(payload), length)
	msgID, err := bridge.GetManager().SendMessageBinary(peerFP, payloadBytes)
	if err != nil {
		return nil
	}
	return C.CString(msgID)
}

//export goIsPeerOnline
func goIsPeerOnline(cPeerFP *C.char) C.int {
	if cPeerFP == nil {
		return 0
	}
	peerFP := C.GoString(cPeerFP)
	if bridge.GetManager().IsPeerOnline(peerFP) {
		return 1
	}
	return 0
}

//export goSendFile
func goSendFile(cPeerFP, cFilePath, cMessageID, cFileName, cCaption, cEmoji *C.char) *C.char {
	if cPeerFP == nil || cFilePath == nil {
		return nil
	}
	peerFP := C.GoString(cPeerFP)
	filePath := C.GoString(cFilePath)
	var messageID, fileName, caption, emoji string
	if cMessageID != nil {
		messageID = C.GoString(cMessageID)
	}
	if cFileName != nil {
		fileName = C.GoString(cFileName)
	}
	if cCaption != nil {
		caption = C.GoString(cCaption)
	}
	if cEmoji != nil {
		emoji = C.GoString(cEmoji)
	}

	metaID, err := bridge.GetManager().SendFile(peerFP, filePath, messageID, fileName, caption, emoji)
	if err != nil {
		return nil
	}
	return C.CString(metaID)
}

//export goCancelFile
func goCancelFile(cMsgID *C.char) C.int {
	if cMsgID == nil {
		return 0
	}
	messageID := C.GoString(cMsgID)
	if bridge.GetManager().CancelFile(messageID) {
		return 1
	}
	return 0
}

//export goSetTorProxy
func goSetTorProxy(enabled C.int, cProxyAddr *C.char) {
	var proxyAddr string
	if cProxyAddr != nil {
		proxyAddr = C.GoString(cProxyAddr)
	}
	bridge.GetManager().SetTorProxy(enabled != 0, proxyAddr)
}

//export goSetOnionAddress
func goSetOnionAddress(cAddr *C.char) {
	if cAddr != nil {
		bridge.GetManager().SetOnionAddress(C.GoString(cAddr))
	}
}

//export goGetOnionAddress
func goGetOnionAddress() *C.char {
	addr := bridge.GetManager().GetOnionAddress()
	if addr == "" {
		return nil
	}
	return C.CString(addr)
}

//export goStartDiscovery
func goStartDiscovery(cTrackersJSON, cInfoHashesJSON *C.char, port C.int) C.int {
	var trackersJSON, infoHashesJSON string
	if cTrackersJSON != nil {
		trackersJSON = C.GoString(cTrackersJSON)
	}
	if cInfoHashesJSON != nil {
		infoHashesJSON = C.GoString(cInfoHashesJSON)
	}
	err := bridge.GetManager().StartDiscovery(trackersJSON, infoHashesJSON, int(port))
	if err != nil {
		return 0
	}
	return 1
}

//export goStopDiscovery
func goStopDiscovery() C.int {
	err := bridge.GetManager().StopDiscovery()
	if err != nil {
		return 0
	}
	return 1
}

//export goUpdateTrackers
func goUpdateTrackers(cTrackersJSON *C.char) C.int {
	if cTrackersJSON == nil {
		return 0
	}
	err := bridge.GetManager().UpdateTrackers(C.GoString(cTrackersJSON))
	if err != nil {
		return 0
	}
	return 1
}

//export goReloadIdentity
func goReloadIdentity() C.int {
	err := bridge.GetManager().ReloadIdentity()
	if err != nil {
		return 0
	}
	return 1
}

//export goAnnounceSelf
func goAnnounceSelf(cInfoHashHex *C.char, port C.int) C.int {
	if cInfoHashHex == nil {
		return 0
	}
	err := bridge.GetManager().AnnounceSelf(C.GoString(cInfoHashHex), int(port))
	if err != nil {
		return 0
	}
	return 1
}

//export goProbePeer
func goProbePeer(cEndpointsJSON, cExpectedFP *C.char) C.int {
	if cEndpointsJSON == nil {
		return 0
	}
	endpointsJSON := C.GoString(cEndpointsJSON)
	var expectedFP string
	if cExpectedFP != nil {
		expectedFP = C.GoString(cExpectedFP)
	}
	err := bridge.GetManager().ProbePeer(endpointsJSON, expectedFP)
	if err != nil {
		return 0
	}
	return 1
}

//export goGetLocalSigningPublicKey
func goGetLocalSigningPublicKey() *C.char {
	pub, err := bridge.GetManager().GetLocalSigningPublicKey()
	if err != nil {
		return nil
	}
	return C.CString(pub)
}

//export goSignGroupPayload
func goSignGroupPayload(cCanonicalPayload *C.char) *C.char {
	if cCanonicalPayload == nil {
		return nil
	}
	payload := C.GoString(cCanonicalPayload)
	sig, err := bridge.GetManager().SignGroupPayload(payload)
	if err != nil {
		return nil
	}
	return C.CString(sig)
}

//export goVerifyGroupPayload
func goVerifyGroupPayload(cVerificationKey, cCanonicalPayload, cSignature *C.char) C.int {
	if cVerificationKey == nil || cCanonicalPayload == nil || cSignature == nil {
		return 0
	}
	key := C.GoString(cVerificationKey)
	payload := C.GoString(cCanonicalPayload)
	sig := C.GoString(cSignature)
	if bridge.GetManager().VerifyGroupPayload(key, payload, sig) {
		return 1
	}
	return 0
}

//export goGroupEncrypt
func goGroupEncrypt(epochSecret *C.uint8_t, secLen C.int, authData *C.uint8_t, adLen C.int, plaintext *C.uint8_t, ptLen C.int) *C.char {
	if epochSecret == nil || plaintext == nil || secLen <= 0 || ptLen < 0 {
		return nil
	}
	secSlice := C.GoBytes(unsafe.Pointer(epochSecret), secLen)
	ptSlice := C.GoBytes(unsafe.Pointer(plaintext), ptLen)
	var adSlice []byte
	if authData != nil && adLen > 0 {
		adSlice = C.GoBytes(unsafe.Pointer(authData), adLen)
	}

	nonceB64, ciphertextB64, err := bridge.GetManager().GroupEncrypt(secSlice, adSlice, ptSlice)
	if err != nil {
		return nil
	}
	res := fmt.Sprintf(`{"nonce":"%s","ciphertext":"%s"}`, nonceB64, ciphertextB64)
	return C.CString(res)
}

//export goGroupDecrypt
func goGroupDecrypt(epochSecret *C.uint8_t, secLen C.int, authData *C.uint8_t, adLen C.int, cNonceB64, cCiphertextB64 *C.char, outLen *C.int) unsafe.Pointer {
	if epochSecret == nil || cNonceB64 == nil || cCiphertextB64 == nil || secLen <= 0 || outLen == nil {
		return nil
	}
	secSlice := C.GoBytes(unsafe.Pointer(epochSecret), secLen)
	var adSlice []byte
	if authData != nil && adLen > 0 {
		adSlice = C.GoBytes(unsafe.Pointer(authData), adLen)
	}
	nonceB64 := C.GoString(cNonceB64)
	ciphertextB64 := C.GoString(cCiphertextB64)

	plaintext, err := bridge.GetManager().GroupDecrypt(secSlice, adSlice, nonceB64, ciphertextB64)
	if err != nil || len(plaintext) == 0 {
		*outLen = 0
		return nil
	}
	*outLen = C.int(len(plaintext))
	return C.CBytes(plaintext)
}

//export goTriggerNatTraversal
func goTriggerNatTraversal() C.int {
	if bridge.GetManager().TriggerNatTraversal() {
		return 1
	}
	return 0
}

//export goGetNatDiagnosticsJSON
func goGetNatDiagnosticsJSON() *C.char {
	jsonStr := bridge.GetManager().GetNatDiagnosticsJSON()
	return C.CString(jsonStr)
}

//export goOnNetworkChanged
func goOnNetworkChanged() C.int {
	err := bridge.GetManager().OnNetworkChanged()
	if err != nil {
		return 0
	}
	return 1
}

//export goFreeMemory
func goFreeMemory(ptr unsafe.Pointer) {
	if ptr != nil {
		C.free(ptr)
	}
}
