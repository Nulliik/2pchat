package discovery

import "testing"

func TestRendezvousKeyMatchesPythonVector(t *testing.T) {
	got, err := DeriveRendezvousKeyHex(" Null ", "36571c05")
	if err != nil {
		t.Fatal(err)
	}
	const want = "4725456c9bc18c138f2066366fcf09bfe6ecdc34"
	if got != want {
		t.Fatalf("DeriveRendezvousKeyHex = %s, want %s", got, want)
	}

	normalized, err := DeriveRendezvousKeyHex("  NuLL  ", "36571c05")
	if err != nil || normalized != want {
		t.Fatalf("nickname normalization drifted: got=%s err=%v", normalized, err)
	}
}
