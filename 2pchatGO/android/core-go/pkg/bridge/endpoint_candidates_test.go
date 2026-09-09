package bridge

import (
	"errors"
	"reflect"
	"testing"
)

func TestReserveRoutesOnlyTriedAfterFreshFailure(t *testing.T) {
	fresh, reserve, err := decodeEndpointGroups(`{"fresh":["8.8.8.8:50001"],"reserve":["old.onion:50001"]}`)
	if err != nil {
		t.Fatal(err)
	}
	for _, failFresh := range []bool{false, true} {
		var calls []string
		err = connectEndpointGroups(fresh, reserve, func(ep string) error {
			calls = append(calls, ep)
			if failFresh && ep == fresh[0] {
				return errors.New("offline")
			}
			return nil
		})
		want := []string{fresh[0]}
		if failFresh {
			want = append(want, reserve[0])
		}
		if err != nil || !reflect.DeepEqual(calls, want) {
			t.Fatalf("%v: %v %v", failFresh, calls, err)
		}
	}
}

func TestEndpointGroupsLegacyAndMalformed(t *testing.T) {
	fresh, reserve, err := decodeEndpointGroups(`["8.8.8.8:50001"]`)
	if err != nil || len(fresh) != 1 || len(reserve) != 0 {
		t.Fatal("legacy array rejected")
	}
	for _, input := range []string{`[]`, `{}`, `{"fresh":["a,b"]}`, `{"reserve":[42]}`} {
		if _, _, err := decodeEndpointGroups(input); err == nil {
			t.Fatalf("accepted %s", input)
		}
	}
}
