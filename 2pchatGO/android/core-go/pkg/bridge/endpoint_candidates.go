package bridge

import (
	"encoding/json"
	"errors"
	"strings"
)

// This envelope is local JNI data, not a peer wire format. Legacy arrays remain
// supported. Reserve routes are attempted only after the fresh group fails.
func decodeEndpointGroups(raw string) (fresh, reserve []string, err error) {
	if len(raw) > 16384 { return nil, nil, errors.New("endpoint request too large") }
	if strings.HasPrefix(strings.TrimSpace(raw), "{") {
		var groups struct { Fresh []string `json:"fresh"`; Reserve []string `json:"reserve"` }
		err = json.Unmarshal([]byte(raw), &groups)
		fresh, reserve = groups.Fresh, groups.Reserve
	} else {
		err = json.Unmarshal([]byte(raw), &fresh)
	}
	if err != nil { return nil, nil, err }
	if len(fresh)+len(reserve) == 0 || len(fresh)+len(reserve) > 16 { return nil, nil, errors.New("invalid endpoint count") }
	for _, endpoints := range [][]string{fresh, reserve} {
		for _, ep := range endpoints {
			if ep == "" || len(ep) > 512 || strings.Contains(ep, ",") { return nil, nil, errors.New("invalid endpoint") }
		}
	}
	return fresh, reserve, nil
}

func connectEndpointGroups(fresh, reserve []string, connect func(string) error) error {
	var err error
	if len(fresh) > 0 {
		err = connect(strings.Join(fresh, ","))
		if err == nil { return nil }
	}
	if len(reserve) > 0 { return connect(strings.Join(reserve, ",")) }
	return err
}
