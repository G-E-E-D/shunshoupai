package app

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestJSONBoundary(t *testing.T) {
	for _, tc := range []struct {
		name, body, origin, content string
		limit                       int64
		code                        string
	}{
		{"unknown owner", `{"owner":"forged"}`, "", "application/json", 100, "VALIDATION_ERROR"},
		{"null", "null", "", "application/json", 100, "VALIDATION_ERROR"},
		{"optional null", `{"name":null}`, "", "application/json", 100, "VALIDATION_ERROR"},
		{"invalid", "{", "", "application/json", 100, "INVALID_JSON"},
		{"oversize", `{"name":"large"}`, "", "application/json", 5, "BODY_TOO_LARGE"},
		{"content type", `{}`, "", "text/plain", 100, "INVALID_CONTENT_TYPE"},
		{"origin", `{}`, "https://attacker.test", "application/json", 100, "INVALID_ORIGIN"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "http://localhost/test", strings.NewReader(tc.body))
			r.Header.Set("Origin", tc.origin)
			r.Header.Set("Content-Type", tc.content)
			var body struct {
				Name string `json:"name"`
			}
			err := readJSON(r, &body, tc.limit)
			e, ok := err.(*AppError)
			if !ok || e.Code != tc.code {
				t.Fatalf("got %v, want %s", err, tc.code)
			}
		})
	}
}

func TestStaticFrontendCannotExposeDatabase(t *testing.T) {
	root := t.TempDir()
	web := filepath.Join(root, "web")
	if err := os.MkdirAll(filepath.Join(web, "mobile"), 0700); err != nil {
		t.Fatal(err)
	}
	for p, body := range map[string]string{filepath.Join(root, "secret.sqlite"): "PRIVATE", filepath.Join(web, "index.html"): "START", filepath.Join(web, "mobile", "index.html"): "WORKBENCH", filepath.Join(web, ".env"): "PRIVATE"} {
		if err := os.WriteFile(p, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	h := staticFiles(web)
	for _, tc := range []struct {
		path, body string
		status     int
	}{{"/", "START", 200}, {"/mobile", "WORKBENCH", 200}, {"/mobile/", "WORKBENCH", 200}, {"/.env", "", 404}, {"/../secret.sqlite", "", 404}, {"/mobile/missing", "", 404}} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", tc.path, nil))
		if w.Code != tc.status {
			t.Fatalf("%s status %d", tc.path, w.Code)
		}
		if tc.status == 200 && w.Body.String() != tc.body {
			t.Fatal(w.Body.String())
		}
		if strings.Contains(w.Body.String(), "PRIVATE") {
			t.Fatal("private file exposed")
		}
	}
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.Header.Set("User-Agent", "ShunshoupaiAndroid/2.1.0-beta.1")
	h.ServeHTTP(w, r)
	if w.Body.String() != "WORKBENCH" {
		t.Fatal("Android entry not routed")
	}
}

func TestShanghaiTimeValidation(t *testing.T) {
	for _, v := range []string{"2026-02-30T10:00", "2026-10-28T24:00", "1999-01-01T00:00", "2101-01-01T00:00", "2026-10-28T10:00Z"} {
		if _, err := parseLocal(v); err == nil {
			t.Fatal(v)
		}
	}
	stamp, err := parseLocal("2026-10-28T08:00")
	if err != nil || stamp.UTC().Format("2006-01-02T15:04:05Z") != "2026-10-28T00:00:00Z" {
		t.Fatalf("%v %v", stamp, err)
	}
}
