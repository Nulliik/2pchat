package bridge

import "testing"

func TestIsLocalDiscoveryEndpoint(t *testing.T) {
	localYgg := "200:76e8:9e8b:f260:25d1:7787:d756:4fb"

	for _, endpoint := range []string{
		"[200:76e8:9e8b:f260:25d1:7787:d756:4fb]:50001",
		"[::1]:50001",
		"127.0.0.1:50001",
		"localhost:50001",
	} {
		if !isLocalDiscoveryEndpoint(endpoint, localYgg) {
			t.Fatalf("local endpoint was not filtered: %q", endpoint)
		}
	}

	if isLocalDiscoveryEndpoint("[201:8fb5:198:a14c:2b2c:855:34f7:db26]:50001", localYgg) {
		t.Fatal("remote Yggdrasil endpoint was filtered")
	}
}
