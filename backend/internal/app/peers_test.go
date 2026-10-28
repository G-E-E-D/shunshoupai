package app

import (
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type devicePeerHost struct {
	Store       *Store
	Owner       *grantedDevice
	Server      *Server
	HTTP        *httptest.Server
	Credentials atomic.Int64
	mu          sync.Mutex
	hold        func()
}

func newDevicePeerHost(t *testing.T) *devicePeerHost {
	t.Helper()
	s := deviceTestStore(t)
	host := &devicePeerHost{Store: s, Owner: deviceTestOwner(t, s), Server: NewServer(s, "")}
	host.HTTP = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			host.Credentials.Add(1)
		}
		if r.URL.Path == "/api/pairing/redeem" {
			recorder := httptest.NewRecorder()
			host.Server.ServeHTTP(recorder, r)
			host.mu.Lock()
			hold := host.hold
			host.mu.Unlock()
			if hold != nil {
				hold()
			}
			for k, v := range recorder.Header() {
				w.Header()[k] = v
			}
			w.WriteHeader(recorder.Code)
			_, _ = w.Write(recorder.Body.Bytes())
			return
		}
		host.Server.ServeHTTP(w, r)
	}))
	t.Cleanup(func() {
		host.HTTP.Close()
		deadline := time.Now().Add(20 * time.Second)
		for time.Now().Before(deadline) {
			host.Server.peerState.mu.Lock()
			running := host.Server.peerState.running
			host.Server.peerState.mu.Unlock()
			if !running {
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	})
	return host
}
func peerTestKey(t *testing.T) {
	t.Helper()
	t.Setenv("MODEL_SETTINGS_ENCRYPTION_KEY", base64.StdEncoding.EncodeToString(make([]byte, 32)))
}
func peerTestPair(t *testing.T, a, b *devicePeerHost) {
	t.Helper()
	qr := deviceTestIssue(t, a.Store, a.Owner.Grant)
	if e := b.Store.ConnectPeer(a.HTTP.URL+"/#pair="+qr, b.HTTP.URL, b.Owner.Grant.ID); e != nil {
		t.Fatal(e)
	}
}
func peerTestOne(t *testing.T, s *Store) PeerConnection {
	t.Helper()
	peers, e := s.allPeers()
	if e != nil || len(peers) != 1 {
		t.Fatalf("peers %v error %v", len(peers), e)
	}
	return peers[0]
}
func peerTestEvent(t *testing.T, s *Store, space, title string) Event {
	t.Helper()
	result, e := s.Commit(space, newID(), []EventDraft{deviceTestDraft(title)})
	if e != nil {
		t.Fatal(e)
	}
	return result["events"].([]Event)[0]
}
func TestPeerAddressRestrictions(t *testing.T) {
	good := []string{"http://192.168.1.5:43127", "http://10.0.0.1", "http://172.31.255.1", "https://[fd00::1]:4443", "http://127.0.0.1", "http://localhost:3000"}
	for _, u := range good {
		if _, e := peerOrigin(u); e != nil {
			t.Fatalf("%s: %v", u, e)
		}
	}
	for _, u := range []string{"https://example.com", "http://8.8.8.8", "http://169.254.169.254", "http://10.0.0.1/api", "http://user:pass@10.0.0.1", "http://10.0.0.1?token=a", "file:///C:/test", "http://[fe80::1]", "http://0.0.0.0", "http://[::]"} {
		if _, e := peerOrigin(u); e == nil {
			t.Fatalf("allowed %s", u)
		}
	}
	for _, u := range []string{"http://10.0.0.1/#pair=short", "http://10.0.0.1/#pair=" + token() + "&unexpected=1", "http://10.0.0.1/#pair=" + token() + "&pair=" + token()} {
		if _, _, e := parsePeerLink(u); e == nil {
			t.Fatalf("accepted malformed QR %s", u)
		}
	}
}
func TestPeersReciprocalHTTPMergeAndRevocation(t *testing.T) {
	peerTestKey(t)
	a, b := newDevicePeerHost(t), newDevicePeerHost(t)
	ea := peerTestEvent(t, a.Store, a.Owner.Grant.SpaceID, "电脑安排")
	eb := peerTestEvent(t, b.Store, b.Owner.Grant.SpaceID, "离线手机安排")
	peerTestPair(t, a, b)
	if e := b.Store.SyncPeer(peerTestOne(t, b.Store)); e != nil {
		t.Fatal(e)
	}
	got, e := a.Store.Event(a.Owner.Grant.SpaceID, eb.ID)
	if e != nil || got.Title != eb.Title {
		t.Fatalf("missing phone event %v", e)
	}
	got, e = b.Store.Event(b.Owner.Grant.SpaceID, ea.ID)
	if e != nil || got.Title != ea.Title {
		t.Fatalf("missing desktop event %v", e)
	}
	current, e := a.Store.Event(a.Owner.Grant.SpaceID, ea.ID)
	if e != nil {
		t.Fatal(e)
	}
	title := "电脑新修改"
	if _, e = a.Store.Mutate(a.Owner.Grant.SpaceID, ea.ID, current.Version, EventPatch{Title: &title}, false); e != nil {
		t.Fatal(e)
	}
	if e = a.Store.SyncPeer(peerTestOne(t, a.Store)); e != nil {
		t.Fatal(e)
	}
	got, e = b.Store.Event(b.Owner.Grant.SpaceID, ea.ID)
	if e != nil || got.Title != title {
		t.Fatalf("reciprocal sync %v", e)
	}
	before := got.Version
	if e = b.Store.SyncPeer(peerTestOne(t, b.Store)); e != nil {
		t.Fatal(e)
	}
	got, e = b.Store.Event(b.Owner.Grant.SpaceID, ea.ID)
	if e != nil || got.Version != before {
		t.Fatal("replayed sync changed version")
	}
	local, e := loadIdentity(b.Store.DB)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = a.Store.DB.Exec("UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer'", nowISO(), local.ID); e != nil {
		t.Fatal(e)
	}
	deviceTestError(t, b.Store.SyncPeer(peerTestOne(t, b.Store)), "PAIRING_REVOKED")
	got, e = b.Store.Event(b.Owner.Grant.SpaceID, ea.ID)
	if e != nil || got.Title != title {
		t.Fatal("unpair lost local replica")
	}
}
func TestPeerIdentitySubstitutionNeverReceivesBearer(t *testing.T) {
	peerTestKey(t)
	a, b, imposter := newDevicePeerHost(t), newDevicePeerHost(t), newDevicePeerHost(t)
	peerTestPair(t, a, b)
	old := peerTestOne(t, b.Store)
	old.Origin = imposter.HTTP.URL
	deviceTestError(t, b.Store.SyncPeer(old), "PEER_IDENTITY_CHANGED")
	if imposter.Credentials.Load() != 0 {
		t.Fatal("unverified peer received credential")
	}
	if peerTestOne(t, b.Store).Origin != a.HTTP.URL {
		t.Fatal("existing address changed")
	}
}
func TestPeerUnpairWinsDelayedPairing(t *testing.T) {
	peerTestKey(t)
	a, b := newDevicePeerHost(t), newDevicePeerHost(t)
	arrived, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	a.mu.Lock()
	a.hold = func() { close(arrived); <-release }
	a.mu.Unlock()
	qr := deviceTestIssue(t, a.Store, a.Owner.Grant)
	result := make(chan error, 1)
	go func() { result <- b.Store.ConnectPeer(a.HTTP.URL+"/#pair="+qr, b.HTTP.URL, b.Owner.Grant.ID) }()
	select {
	case <-arrived:
	case <-time.After(20 * time.Second):
		t.Fatal("pairing request never arrived")
	}
	local, e := loadIdentity(a.Store.DB)
	if e != nil {
		t.Fatal(e)
	}
	if e = b.Store.disconnectPeer(local.ID); e != nil {
		t.Fatal(e)
	}
	once.Do(func() { close(release) })
	select {
	case e := <-result:
		deviceTestError(t, e, "PAIRING_CANCELLED")
	case <-time.After(20 * time.Second):
		t.Fatal("pairing did not settle")
	}
	peers, e := b.Store.listPeers()
	if e != nil || len(peers) != 0 {
		t.Fatal("unpaired peer was reenabled")
	}
	var count int
	if e = b.Store.DB.QueryRow("SELECT count(*) FROM device_grants WHERE client_id=? AND role='peer' AND revoked_at IS NULL", local.ID).Scan(&count); e != nil || count != 0 {
		t.Fatal("callback grant survived cancellation")
	}
}
func TestPeerRedirectDoesNotForwardCredential(t *testing.T) {
	peerTestKey(t)
	received := atomic.Int64{}
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { received.Add(1) }))
	defer target.Close()
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 307) }))
	defer redirect.Close()
	var response Object
	if e := peerRequest(redirect.URL, "/api/test", "POST", Object{}, token(), &response); e == nil {
		t.Fatal("redirect accepted")
	}
	if received.Load() != 0 {
		t.Fatal("redirect received credential")
	}
}
