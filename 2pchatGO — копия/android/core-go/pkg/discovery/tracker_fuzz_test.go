package discovery

import (
	"testing"
)

func FuzzParseHTTPAnnounceResponse(f *testing.F) {
	validBencoded := []byte("d8:intervali900e5:peers6:\x7f\x00\x00\x01\x1f\x90e")
	errorBencoded := []byte("d14:failure reason13:Tracker error!e")
	f.Add(validBencoded)
	f.Add(errorBencoded)
	f.Add([]byte{})
	f.Add([]byte("invalid bencoded trash"))

	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = ParseHTTPAnnounceResponse(data)
	})
}
