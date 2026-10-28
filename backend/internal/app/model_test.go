package app

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func modelTestCode(t *testing.T, err error, want string) {
	t.Helper()
	var e *modelTransportError
	if !errors.As(err, &e) || e.Code != want {
		t.Fatalf("got %v, want %s", err, want)
	}
}
func TestModelEncryptionNodeCompatibilityAndLostKey(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	fixture := "v1.AwMDAwMDAwMDAwMD.lxl9dG3DkfRhhcauyqAZ2Q.Q5fbdy9aO28XLyc5h3qMaY5OhZs"
	value, e := decryptSecret(fixture, "test-space", key)
	if e != nil || value != "fixture-model-secret" {
		t.Fatal(value, e)
	}
	if _, e = decryptSecret(fixture, "wrong-space", key); e == nil {
		t.Fatal("cross calendar decrypt")
	}
	if _, e = decryptSecret(fixture, "test-space", bytes.Repeat([]byte{8}, 32)); e == nil {
		t.Fatal("wrong key accepted")
	}
	a, e := encryptSecret(value, "test-space", key)
	if e != nil {
		t.Fatal(e)
	}
	b, e := encryptSecret(value, "test-space", key)
	if e != nil || a == b {
		t.Fatal("nonce reused", e)
	}
	if got, e := decryptSecret(a, "test-space", key); e != nil || got != value {
		t.Fatal(got, e)
	}
	t.Setenv("MODEL_SETTINGS_ENCRYPTION_KEY", "")
	store, e := OpenStore(filepath.Join(t.TempDir(), "app.sqlite"))
	if e != nil {
		t.Fatal(e)
	}
	defer store.Close()
	if _, e = store.encryptionKey(false); e == nil {
		t.Fatal("created a key on read")
	}
	if _, e = store.DB.Exec("INSERT INTO peer_connections(id,space_id,name,origin,public_key,credential) VALUES('peer','space','peer','http://example.invalid','key',?)", fixture); e != nil {
		t.Fatal(e)
	}
	if _, e = store.encryptionKey(true); e == nil {
		t.Fatal("replaced lost key with encrypted records present")
	}
	if _, e = os.Stat(filepath.Join(filepath.Dir(store.Path), "model-encryption.key")); !errors.Is(e, os.ErrNotExist) {
		t.Fatal("created lost key", e)
	}
	t.Setenv("MODEL_SETTINGS_ENCRYPTION_KEY", base64.StdEncoding.EncodeToString(key))
	if got, e := store.encryptionKey(false); e != nil || !bytes.Equal(got, key) {
		t.Fatal("explicit recovery key", e)
	}
}
func TestModelAddressesAndURLValidation(t *testing.T) {
	t.Setenv("AI_ALLOWED_PRIVATE_HOSTS", "")
	for _, v := range []string{"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.20.1.1", "192.168.0.1", "169.254.169.254", "100.100.100.200", "198.18.0.1", "192.0.2.1", "224.0.0.1", "[::1]", "[fe80::1]", "[fc00::1]", "[::ffff:127.0.0.1]", "[2001:db8::1]", "[2002:7f00:1::]", "[4000::1]", "[8000::1]", "2130706433", "0x7f000001", "127.1"} {
		_, e := parseModelURL("https://" + v)
		modelTestCode(t, e, "MODEL_ADDRESS_BLOCKED")
	}
	for _, v := range []string{"file:///etc/passwd", "javascript:alert(1)", "https://key@example.com", "https://example.com?api_key=secret", "https://example.com#secret", "https://example.com/v1/chat/completions", "https://example.com\\@127.0.0.1", "https://example.com/\r\nheader", "http://1.1.1.1"} {
		if _, e := parseModelURL(v); e == nil {
			t.Fatal("accepted " + v)
		}
	}
	if !isPublicModelAddress("1.1.1.1") || !isPublicModelAddress("2606:4700:4700::1111") {
		t.Fatal("public blocked")
	}
	_, e := resolveModelEndpoint(context.Background(), "https://model.invalid", func(context.Context, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("127.0.0.1")}, nil
	})
	modelTestCode(t, e, "MODEL_ADDRESS_BLOCKED")
}
func TestModelTransportPinningAndSensitiveBoundaries(t *testing.T) {
	const secret = "fixture-secret-not-real"
	t.Setenv("AI_ALLOWED_PRIVATE_HOSTS", "127.0.0.1,model.invalid")
	var mode atomic.Int32
	var hits atomic.Int32
	var calls atomic.Int32
	finishSlow := make(chan struct{})
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path != "/v1/chat/completions" || r.Header.Get("Authorization") != "Bearer "+secret {
			t.Error("request path/auth mismatch")
		}
		switch mode.Load() {
		case 1:
			w.Header().Set("Location", "http://127.0.0.1:1/steal")
			w.WriteHeader(302)
		case 2:
			w.WriteHeader(401)
			io.WriteString(w, secret)
		case 3:
			json.NewEncoder(w).Encode(Object{"choices": []Object{{"message": Object{"content": secret}}}})
		case 4:
			json.NewEncoder(w).Encode(Object{"choices": []Object{{"message": Object{"content": strings.Repeat("字", 1000)}}}})
		case 5:
			select {
			case <-r.Context().Done():
			case <-finishSlow:
			}
		default:
			json.NewEncoder(w).Encode(Object{"choices": []Object{{"message": Object{"content": `{"ok":true}`}}}})
		}
	}))
	defer provider.Close()
	defer close(finishSlow)
	port := strings.TrimPrefix(provider.URL, "http://127.0.0.1:")
	config := ModelConfig{"http://model.invalid:" + port + "/v1", "fixture-model", secret}
	opts := modelRequestOptions{Lookup: func(ctx context.Context, host string) ([]netip.Addr, error) {
		calls.Add(1)
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	}}
	v, e := requestModel(context.Background(), config, []modelMessage{{"system", "return JSON"}}, opts)
	if e != nil || v != `{"ok":true}` || calls.Load() != 1 || hits.Load() != 1 {
		t.Fatal(v, e, calls.Load(), hits.Load())
	}
	for _, tc := range []struct {
		mode int32
		code string
	}{{1, "MODEL_REDIRECT_BLOCKED"}, {2, "MODEL_UPSTREAM_ERROR"}, {3, "MODEL_SENSITIVE_RESPONSE"}, {4, "MODEL_RESPONSE_TOO_LARGE"}, {5, "MODEL_TIMEOUT"}} {
		mode.Store(tc.mode)
		testOpts := opts
		if tc.mode == 4 {
			testOpts.MaxBytes = 200
		}
		if tc.mode == 5 {
			testOpts.Timeout = 30 * time.Millisecond
		}
		_, e = requestModel(context.Background(), config, nil, testOpts)
		modelTestCode(t, e, tc.code)
		if strings.Contains(e.Error(), secret) {
			t.Fatal("leaked secret")
		}
	}
	before := hits.Load()
	opts.Timeout = 10 * time.Millisecond
	opts.Lookup = func(ctx context.Context, host string) ([]netip.Addr, error) {
		time.Sleep(30 * time.Millisecond)
		return []netip.Addr{netip.MustParseAddr("127.0.0.1")}, nil
	}
	_, e = requestModel(context.Background(), config, nil, opts)
	modelTestCode(t, e, "MODEL_TIMEOUT")
	if hits.Load() != before {
		t.Fatal("sent request after DNS deadline")
	}
}
func TestModelParserFixedPromptAndOutputValidation(t *testing.T) {
	t.Setenv("AI_ALLOWED_PRIVATE_HOSTS", "127.0.0.1")
	now, _ := time.Parse(time.RFC3339, "2026-10-27T02:00:00Z")
	sample := ModelDraft{"面试", "2026-10-28T15:00:00", "2026-10-28T16:00:00", "Asia/Shanghai", "explicit", []string{}}
	output, _ := json.Marshal(Object{"intent": "create_events", "needs_clarification": false, "questions": []string{}, "events": []ModelDraft{sample}})
	var content atomic.Value
	content.Store(string(output))
	var hits atomic.Int32
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		var request struct {
			Messages []modelMessage `json:"messages"`
		}
		if e := json.NewDecoder(r.Body).Decode(&request); e != nil {
			t.Error(e)
		}
		if len(request.Messages) != 2 || !strings.HasPrefix(request.Messages[0].Content, "你是日程整理器") || !strings.Contains(request.Messages[1].Content, "2026-10-27T10:00:00") {
			t.Error("prompt/reference changed")
		}
		json.NewEncoder(w).Encode(Object{"choices": []Object{{"message": Object{"content": content.Load().(string)}}}})
	}))
	defer provider.Close()

	config := &ModelConfig{provider.URL, "fixture-model", "fixture-secret-not-real"}
	result, e := parseText(context.Background(), "明天下午三点到四点面试", now, nil, config)
	if e != nil || result.Provider != "model" || len(result.Events) != 1 {
		t.Fatal(result, e)
	}
	for _, bad := range []string{"not json", `{"needs_clarification":false,"questions":[],"events":[]}`, `{"needs_clarification":true,"questions":[],"events":[]}`, `{"needs_clarification":false,"questions":[],"events":[{"title":"a","start_local":"2026-02-30T15:00:00","end_local":"2026-02-30T16:00:00","timezone":"Asia/Shanghai","time_source":"explicit","assumptions":[]}]}`} {
		content.Store(bad)
		if _, e = parseText(context.Background(), "明天下午三点到四点面试", now, nil, config); e == nil {
			t.Fatal("accepted malformed model content")
		}
	}
	before := hits.Load()
	for _, v := range []string{"周五三点面试", "每周一下午三点面试"} {
		r, e := parseText(context.Background(), v, now, nil, config)
		if e != nil || !r.NeedsClarification {
			t.Fatal(r, e)
		}
	}
	if hits.Load() != before {
		t.Fatal("model bypassed semantic guard")
	}
}
