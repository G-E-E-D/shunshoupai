package app

import (
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPairedDevicesCanOnlyUseCalendarCapabilities(t *testing.T) {
	t.Setenv("AI_API_KEY", "")
	store := deviceTestStore(t)
	owner := deviceTestOwner(t, store)
	peer := deviceTestRedeem(t, store, owner.Grant, newID())
	server := NewServer(store, t.TempDir())
	for _, credentials := range []string{"bearer", "cookie"} {
		for _, endpoint := range []struct{ method, path string }{
			{"GET", "/api/model-settings"}, {"PUT", "/api/model-settings"}, {"DELETE", "/api/model-settings"},
			{"POST", "/api/model-settings/test"}, {"POST", "/api/ai/parse"}, {"POST", "/api/devices/web-session"},
		} {
			r := httptest.NewRequest(endpoint.method, "http://localhost"+endpoint.path, strings.NewReader(`{}`))
			r.Header.Set("Content-Type", "application/json")
			if credentials == "bearer" {
				r.Header.Set("Authorization", "Bearer "+peer.Token)
			} else {
				r.Header.Set("Cookie", deviceCookie+"="+peer.Token)
			}
			w := httptest.NewRecorder()
			server.ServeHTTP(w, r)
			if w.Code != 403 || !strings.Contains(w.Body.String(), "SYNC_ONLY_DEVICE") {
				t.Fatalf("%s %s %s: %d %s", credentials, endpoint.method, endpoint.path, w.Code, w.Body.String())
			}
		}
	}
	// Local UI remains fully functional without granting a peer access to its model.
	for _, endpoint := range []struct{ method, path, body string }{
		{"GET", "/api/model-settings", `{}`},
		{"POST", "/api/ai/parse", `{"text":"明天上午九点到十点开会"}`},
	} {
		r := httptest.NewRequest(endpoint.method, "http://localhost"+endpoint.path, strings.NewReader(endpoint.body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+owner.Token)
		w := httptest.NewRecorder()
		server.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatalf("owner %s: %d %s", endpoint.path, w.Code, w.Body.String())
		}
	}
	// The same peer credential can still exchange schedules and cannot acquire a remote web session.
	payload, _ := json.Marshal(Object{"device_id": peer.Grant.ClientID, "records": []any{}})
	r := httptest.NewRequest("POST", "http://localhost/api/replica/exchange", strings.NewReader(string(payload)))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Authorization", "Bearer "+peer.Token)
	w := httptest.NewRecorder()
	server.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var response Object
	_ = json.Unmarshal(w.Body.Bytes(), &response)
	caps := response["capabilities"].(map[string]any)
	if caps["web"] != false || caps["ai"] != false {
		t.Fatal("remote features advertised", caps)
	}
}
