package app

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func deviceTestStore(t *testing.T) *Store {
	t.Helper()
	s, e := OpenStore(":memory:")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func deviceTestOwner(t *testing.T, s *Store) *grantedDevice {
	t.Helper()
	g, e := s.Bootstrap("本机", "test", "", "", "")
	if e != nil {
		t.Fatal(e)
	}
	return g
}
func deviceTestError(t *testing.T, e error, code string) {
	t.Helper()
	var a *AppError
	if !errors.As(e, &a) || a.Code != code {
		t.Fatalf("expected %s, got %v", code, e)
	}
}
func deviceTestDraft(title string) EventDraft {
	return EventDraft{Title: title, StartLocal: "2032-01-01T09:00:00", EndLocal: "2032-01-01T10:00:00", Timezone: "Asia/Shanghai", TimeSource: "explicit", Assumptions: []string{}}
}
func deviceTestIssue(t *testing.T, s *Store, g *Grant) string {
	t.Helper()
	v, e := s.IssuePairing(g)
	if e != nil {
		t.Fatal(e)
	}
	return v["token"].(string)
}
func deviceTestRedeem(t *testing.T, s *Store, g *Grant, client string) *grantedDevice {
	t.Helper()
	r, e := s.RedeemPairing(deviceTestIssue(t, s, g), client, "手机", "android")
	if e != nil {
		t.Fatal(e)
	}
	return r
}

func TestDeviceBootstrapIdentityAndRotation(t *testing.T) {
	s := deviceTestStore(t)
	a := deviceTestOwner(t, s)
	context, e := s.DeviceContext()
	if e != nil {
		t.Fatal(e)
	}
	restored, e := s.Bootstrap("本机", "test", a.Token, "", "")
	if e != nil || restored.Token != a.Token || restored.Grant.ID != a.Grant.ID {
		t.Fatalf("restore %v", e)
	}
	b, e := s.Bootstrap("本机", "test", "", "", "")
	if e != nil {
		t.Fatal(e)
	}
	if b.Token == a.Token || b.Grant.SpaceID != a.Grant.SpaceID {
		t.Fatal("identity changed")
	}
	old, e := s.Authenticate(a.Token)
	if e != nil || old != nil {
		t.Fatal("old owner token still active")
	}
	next, e := s.DeviceContext()
	if e != nil || next["peer_id"] != context["peer_id"] {
		t.Fatal("device ID changed")
	}
}
func TestDevicePairingsOneUseRotationExpiryAndRevocation(t *testing.T) {
	s := deviceTestStore(t)
	owner := deviceTestOwner(t, s)
	client := newID()
	ticket := deviceTestIssue(t, s, owner.Grant)
	a, e := s.RedeemPairing(ticket, client, "A", "android")
	if e != nil {
		t.Fatal(e)
	}
	_, e = s.RedeemPairing(ticket, newID(), "B", "android")
	deviceTestError(t, e, "PAIRING_INVALID")
	_, e = s.IssuePairing(a.Grant)
	deviceTestError(t, e, "OWNER_REQUIRED")
	b := deviceTestRedeem(t, s, owner.Grant, newID())
	replacement := deviceTestRedeem(t, s, owner.Grant, client)
	if g, _ := s.Authenticate(a.Token); g != nil {
		t.Fatal("repaired token not rotated")
	}
	if g, _ := s.Authenticate(b.Token); g == nil {
		t.Fatal("unrelated device revoked")
	}
	_, e = s.RevokeDevice(replacement.Grant, b.Grant.ID)
	deviceTestError(t, e, "NOT_FOUND")
	_, e = s.RevokeDevice(owner.Grant, owner.Grant.ID)
	deviceTestError(t, e, "LOCAL_DEVICE")
	if _, e = s.RevokeDevice(owner.Grant, replacement.Grant.ID); e != nil {
		t.Fatal(e)
	}
	deviceTestError(t, s.AssertActive(replacement.Grant), "UNAUTHORIZED")
	ticket = deviceTestIssue(t, s, owner.Grant)
	if _, e = s.DB.Exec("UPDATE device_pairings SET expires_at=0 WHERE token_hash=?", hash(ticket)); e != nil {
		t.Fatal(e)
	}
	_, e = s.RedeemPairing(ticket, newID(), "过期", "android")
	deviceTestError(t, e, "PAIRING_INVALID")
}
func TestDeviceLegacyProfilesRequireChoiceAndPreserveData(t *testing.T) {
	s := deviceTestStore(t)
	a, b := newID(), newID()
	for _, id := range []string{a, b} {
		if _, e := s.DB.Exec("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)", id, id+"@example.test", "unusable", nowISO()); e != nil {
			t.Fatal(e)
		}
	}
	_, e := s.Bootstrap("本机", "test", "", "", "")
	deviceTestError(t, e, "MIGRATION_REQUIRED")
	_, e = s.Bootstrap("本机", "test", "", "", newID())
	deviceTestError(t, e, "INVALID_SPACE")
	pending := newID()
	if _, e = s.DB.Exec("INSERT INTO mutation_requests(user_id,request_id,payload_hash,result,created_at) VALUES(?,?,?,?,?)", b, pending, "preserve", "[]", nowISO()); e != nil {
		t.Fatal(e)
	}
	legacy := token()
	if _, e = s.DB.Exec("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)", hash(legacy), b, int64(9e12)); e != nil {
		t.Fatal(e)
	}
	chosen, e := s.Bootstrap("本机", "test", "", legacy, "")
	if e != nil || chosen.Grant.SpaceID != b {
		t.Fatalf("legacy selection %v", e)
	}
	var count int
	if e = s.DB.QueryRow("SELECT count(*) FROM users").Scan(&count); e != nil || count != 2 {
		t.Fatal("profiles were merged or removed")
	}
	var result string
	if e = s.DB.QueryRow("SELECT result FROM mutation_requests WHERE user_id=? AND request_id=?", b, pending).Scan(&result); e != nil || result != "[]" {
		t.Fatal("pending mutation changed")
	}
}
func TestDeviceIdentityProofAndCredentialsSurviveRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "local.sqlite")
	s, e := OpenStore(path)
	if e != nil {
		t.Fatal(e)
	}
	owner := deviceTestOwner(t, s)
	peer := deviceTestRedeem(t, s, owner.Grant, newID())
	client, challenge := newID(), token()
	proof, e := s.DeviceProof(challenge, client)
	if e != nil {
		t.Fatal(e)
	}
	identity := proof["identity"].(Object)
	pub, _ := base64.StdEncoding.DecodeString(identity["public_key"].(string))
	key, e := x509.ParsePKIXPublicKey(pub)
	if e != nil {
		t.Fatal(e)
	}
	sig, _ := base64.StdEncoding.DecodeString(identity["signature"].(string))
	message := challenge + "\n" + client + "\n" + proof["peer_id"].(string) + "\n" + proof["space_id"].(string)
	digest := sha256.Sum256([]byte(message))
	if !ecdsa.VerifyASN1(key.(*ecdsa.PublicKey), digest[:], sig) {
		t.Fatal("invalid signed identity")
	}
	wrong := sha256.Sum256([]byte(message + "tampered"))
	if ecdsa.VerifyASN1(key.(*ecdsa.PublicKey), wrong[:], sig) {
		t.Fatal("proof not bound to challenge")
	}
	var stored string
	if e = s.DB.QueryRow("SELECT token_hash FROM device_grants WHERE id=?", peer.Grant.ID).Scan(&stored); e != nil || stored == peer.Token || stored != hash(peer.Token) {
		t.Fatal("plaintext credential persisted")
	}
	s.Close()
	s, e = OpenStore(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	g, e := s.Authenticate(peer.Token)
	if e != nil || g == nil {
		t.Fatal("credential lost on restart")
	}
	next, e := s.DeviceProof(token(), client)
	if e != nil || next["identity"].(Object)["public_key"] != identity["public_key"] {
		t.Fatal("identity lost on restart")
	}
}
func TestDeviceHTTPBootstrapRequiresCapabilityAndExplicitBearerWins(t *testing.T) {
	t.Setenv("APP_DEVICE_BOOTSTRAP_KEY", strings.Repeat("s", 48))
	s := deviceTestStore(t)
	server := NewServer(s, "")
	for _, headers := range []map[string]string{{}, {"Host": "localhost:3000", "User-Agent": "ShunshoupaiDesktop", "Origin": "http://localhost:3000"}, {"X-Device-Bootstrap": strings.Repeat("x", 48)}} {
		r := httptest.NewRequest("POST", "http://localhost:3000/api/devices/bootstrap", strings.NewReader(`{"name":"电脑","platform":"test"}`))
		r.Header.Set("Content-Type", "application/json")
		for k, v := range headers {
			r.Header.Set(k, v)
		}
		w := httptest.NewRecorder()
		server.ServeHTTP(w, r)
		if w.Code != 403 || !strings.Contains(w.Body.String(), "BOOTSTRAP_DENIED") {
			t.Fatalf("owner bypass: %d %s", w.Code, w.Body.String())
		}
	}
	r := httptest.NewRequest("POST", "http://localhost:3000/api/devices/bootstrap", strings.NewReader(`{"name":"电脑","platform":"test"}`))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Device-Bootstrap", strings.Repeat("s", 48))
	w := httptest.NewRecorder()
	server.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	cookies := w.Result().Cookies()
	var ownerCookie *http.Cookie
	for _, c := range cookies {
		if c.Name == deviceCookie {
			ownerCookie = c
		}
	}
	if ownerCookie == nil || !ownerCookie.HttpOnly {
		t.Fatal("missing protected session cookie")
	}
	r = httptest.NewRequest("GET", "http://localhost/api/devices/info", nil)
	r.AddCookie(ownerCookie)
	r.Header.Set("Authorization", "Bearer invalid")
	w = httptest.NewRecorder()
	server.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("invalid bearer fell back to valid cookie")
	}
	r = httptest.NewRequest("POST", "http://localhost/api/auth/login", bytes.NewReader([]byte(`{}`)))
	w = httptest.NewRecorder()
	server.ServeHTTP(w, r)
	var body Object
	if e := json.Unmarshal(w.Body.Bytes(), &body); e != nil || w.Code != 410 {
		t.Fatal("legacy accounts returned")
	}
}
