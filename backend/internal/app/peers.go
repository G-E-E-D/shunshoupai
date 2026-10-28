package app

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type PeerConnection struct {
	ID        string  `json:"id"`
	SpaceID   string  `json:"space_id"`
	Name      string  `json:"name"`
	Origin    string  `json:"origin"`
	PublicKey string  `json:"public_key"`
	Token     string  `json:"-"`
	GrantID   *string `json:"grant_id"`
}
type returnConnection struct {
	Origin    string `json:"origin"`
	Token     string `json:"token"`
	PeerID    string `json:"peer_id"`
	SpaceID   string `json:"space_id"`
	PublicKey string `json:"public_key"`
}

func (c returnConnection) validate() error {
	if len(c.Origin) > 512 || !deviceTokenPattern.MatchString(c.Token) || !deviceUUIDPattern.MatchString(c.PeerID) || !deviceUUIDPattern.MatchString(c.SpaceID) || len(c.PublicKey) > 512 || c.PublicKey == "" {
		return deviceValidation()
	}
	_, e := peerOrigin(c.Origin)
	return e
}
func peerOrigin(value string) (string, error) {
	u, e := url.Parse(value)
	bad := func() (string, error) {
		return "", appErr("INVALID_PEER_ADDRESS", "请使用同一局域网内的设备 IP 地址和端口。", 400)
	}
	if e != nil || u.Opaque != "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.Fragment != "" || u.RawQuery != "" || u.ForceQuery || (u.Path != "" && u.Path != "/") || u.Host == "" {
		return bad()
	}
	host := strings.ToLower(u.Hostname())
	ip := net.ParseIP(host)
	if host != "localhost" && (ip == nil || (!ip.IsPrivate() && !ip.IsLoopback())) {
		return bad()
	}
	if u.Port() != "" {
		if _, e = net.LookupPort("tcp", u.Port()); e != nil {
			return bad()
		}
	}
	normalized := u.Scheme + "://" + strings.ToLower(u.Host)
	if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
		host = u.Hostname()
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
		normalized = u.Scheme + "://" + strings.ToLower(host)
	}
	return normalized, nil
}
func parsePeerLink(value string) (string, string, error) {
	u, e := url.Parse(strings.TrimSpace(value))
	if e != nil {
		return "", "", appErr("INVALID_PAIRING_LINK", "请扫描或粘贴带有一次性配对码的完整链接。", 400)
	}
	q, e := url.ParseQuery(u.Fragment)
	if e != nil || len(q) != 1 || len(q["pair"]) != 1 || !deviceTokenPattern.MatchString(q.Get("pair")) {
		return "", "", appErr("INVALID_PAIRING_LINK", "请扫描或粘贴带有一次性配对码的完整链接。", 400)
	}
	value = q.Get("pair")
	u.Fragment = ""
	u.RawFragment = ""
	origin, e := peerOrigin(u.String())
	return origin, value, e
}

var peerTransport = &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, e := net.SplitHostPort(address)
	if e != nil {
		return nil, e
	}
	if strings.EqualFold(host, "localhost") {
		address = net.JoinHostPort("127.0.0.1", port)
	}
	return (&net.Dialer{Timeout: 15 * time.Second}).DialContext(ctx, network, address)
}, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second, MaxIdleConns: 16, IdleConnTimeout: 30 * time.Second}
var peerHTTP = &http.Client{Transport: peerTransport, Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("peer redirects denied") }}

func peerRequest(origin, path, method string, body any, value string, result any) error {
	origin, e := peerOrigin(origin)
	if e != nil {
		return e
	}
	var reader io.Reader
	if body != nil {
		b, e := json.Marshal(body)
		if e != nil {
			return e
		}
		reader = bytes.NewReader(b)
	}
	req, e := http.NewRequest(method, origin+path, reader)
	if e != nil {
		return e
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if value != "" {
		req.Header.Set("Authorization", "Bearer "+value)
	}
	response, e := peerHTTP.Do(req)
	if e != nil {
		return appErr("PEER_UNAVAILABLE", "暂时连不上对方，本机日程已保存；连接恢复后自动重试。", 502)
	}
	defer response.Body.Close()
	b, e := io.ReadAll(io.LimitReader(response.Body, 16*1024*1024+1))
	if e != nil {
		return appErr("PEER_UNAVAILABLE", "对方没有返回有效内容。", 502)
	}
	if len(b) > 16*1024*1024 {
		return appErr("BODY_TOO_LARGE", "对方响应过大，已停止同步。", 413)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		if response.StatusCode == 401 {
			return appErr("PAIRING_REVOKED", "配对已失效，请重新扫码。", 401)
		}
		return appErr("PEER_SYNC_FAILED", "对方未接受同步，本机日程已保留。", response.StatusCode)
	}
	if result != nil {
		if e = json.Unmarshal(b, result); e != nil {
			return appErr("PEER_UNAVAILABLE", "对方返回了无效内容。", 502)
		}
	}
	return nil
}

type peerProof struct {
	PeerID  string `json:"peer_id"`
	SpaceID string `json:"space_id"`
	Device  struct {
		Name string `json:"name"`
	} `json:"device"`
	Identity struct {
		PublicKey string `json:"public_key"`
		Signature string `json:"signature"`
	} `json:"identity"`
	Capabilities struct {
		Web *bool `json:"web"`
	} `json:"capabilities"`
}

func verifyPeer(origin, localID string, expected *PeerConnection) (*peerProof, error) {
	challenge := token()
	var proof peerProof
	if e := peerRequest(origin, "/api/devices/identity?challenge="+challenge+"&device_id="+url.QueryEscape(localID), "GET", nil, "", &proof); e != nil {
		return nil, e
	}
	bad := func() (*peerProof, error) {
		return nil, appErr("PEER_IDENTITY_CHANGED", "设备身份与配对记录不符，未发送日程或旧凭据。请重新核对并扫码。", 409)
	}
	if !deviceUUIDPattern.MatchString(proof.PeerID) || !deviceUUIDPattern.MatchString(proof.SpaceID) || !deviceNameOK(proof.Device.Name) || len(proof.Identity.PublicKey) > 512 || len(proof.Identity.Signature) > 256 {
		return bad()
	}
	der, e := base64.StdEncoding.DecodeString(proof.Identity.PublicKey)
	if e != nil {
		return bad()
	}
	key, e := x509.ParsePKIXPublicKey(der)
	if e != nil {
		return bad()
	}
	ec, ok := key.(*ecdsa.PublicKey)
	if !ok || ec.Curve != elliptic.P256() {
		return bad()
	}
	signature, e := base64.StdEncoding.DecodeString(proof.Identity.Signature)
	if e != nil {
		return bad()
	}
	digest := sha256.Sum256([]byte(challenge + "\n" + localID + "\n" + proof.PeerID + "\n" + proof.SpaceID))
	if !ecdsa.VerifyASN1(ec, digest[:], signature) {
		return bad()
	}
	if expected != nil && (proof.PeerID != expected.ID || proof.SpaceID != expected.SpaceID || proof.Identity.PublicKey != expected.PublicKey) {
		return bad()
	}
	return &proof, nil
}
func peerGeneration(db DBTX) (int64, error) {
	var n int64
	e := db.QueryRow("SELECT generation FROM peer_control WHERE singleton=1").Scan(&n)
	return n, e
}
func peerPin(db DBTX, id string) (string, error) {
	var pin string
	e := db.QueryRow("SELECT public_key FROM peer_connections WHERE id=?", id).Scan(&pin)
	if errors.Is(e, sql.ErrNoRows) {
		return "", nil
	}
	return pin, e
}
func disablePeer(db DBTX, id string) error {
	if _, e := db.Exec("UPDATE peer_control SET generation=generation+1 WHERE singleton=1"); e != nil {
		return e
	}
	_, e := db.Exec("UPDATE peer_connections SET enabled=0,credential='',error=NULL WHERE id=?", id)
	return e
}
func (s *Store) disconnectPeer(id string) error {
	return s.Transaction(func(tx *sql.Tx) error {
		if e := disablePeer(tx, id); e != nil {
			return e
		}
		_, e := tx.Exec("UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND revoked_at IS NULL", nowISO(), id)
		return e
	})
}
func savePeer(db DBTX, peer PeerConnection, key []byte) error {
	pin, e := peerPin(db, peer.ID)
	if e != nil {
		return e
	}
	if pin != "" && pin != peer.PublicKey {
		return appErr("PEER_IDENTITY_CHANGED", "设备身份已改变，未发送或覆盖原配对凭据。请核对目标设备。", 409)
	}
	encrypted, e := encryptSecret(peer.Token, "peer:"+peer.ID+":"+peer.SpaceID, key)
	if e != nil {
		return e
	}
	_, e = db.Exec(`INSERT INTO peer_connections(id,space_id,name,origin,public_key,credential,grant_id) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET space_id=excluded.space_id,name=excluded.name,origin=excluded.origin,public_key=excluded.public_key,credential=excluded.credential,grant_id=excluded.grant_id,enabled=1,error=NULL`, peer.ID, peer.SpaceID, peer.Name, peer.Origin, peer.PublicKey, encrypted, peer.GrantID)
	return e
}
func (s *Store) listPeers() ([]Object, error) {
	rows, e := s.DB.Query("SELECT id,name,origin,last_sync_at,error FROM peer_connections WHERE enabled=1 ORDER BY name,id")
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	result := []Object{}
	for rows.Next() {
		var id, name, origin string
		var last, issue *string
		if e = rows.Scan(&id, &name, &origin, &last, &issue); e != nil {
			return nil, e
		}
		connected := false
		if last != nil && issue == nil {
			t, e := time.Parse(time.RFC3339Nano, *last)
			connected = e == nil && time.Since(t) < time.Minute
		}
		result = append(result, Object{"id": id, "name": name, "origin": origin, "last_sync_at": last, "error": issue, "connected": connected})
	}
	return result, rows.Err()
}
func (s *Store) allPeers() ([]PeerConnection, error) {
	rows, e := s.DB.Query("SELECT id,space_id,name,origin,public_key,credential,grant_id FROM peer_connections WHERE enabled=1 ORDER BY id")
	if e != nil {
		return nil, e
	}
	type saved struct {
		Peer   PeerConnection
		Cipher string
	}
	savedPeers := []saved{}
	for rows.Next() {
		v := saved{}
		if e = rows.Scan(&v.Peer.ID, &v.Peer.SpaceID, &v.Peer.Name, &v.Peer.Origin, &v.Peer.PublicKey, &v.Cipher, &v.Peer.GrantID); e != nil {
			rows.Close()
			return nil, e
		}
		savedPeers = append(savedPeers, v)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return nil, e
	}
	result := []PeerConnection{}
	if len(savedPeers) == 0 {
		return result, nil
	}
	key, keyErr := s.encryptionKey(false)
	for _, v := range savedPeers {
		value := ""
		e = keyErr
		if e == nil {
			value, e = decryptSecret(v.Cipher, "peer:"+v.Peer.ID+":"+v.Peer.SpaceID, key)
		}
		if e != nil {
			_ = s.peerFailed(v.Peer.ID, "本机配对凭据无法读取，日程仍在本机；请重新扫码配对。")
			continue
		}
		v.Peer.Token = value
		result = append(result, v.Peer)
	}
	return result, nil
}
func (s *Store) peerFailed(id, message string) error {
	_, e := s.DB.Exec("UPDATE peer_connections SET error=? WHERE id=? AND enabled=1", message, id)
	return e
}
func (s *Store) peerActive(id string) bool {
	var n int
	return s.DB.QueryRow("SELECT count(*) FROM peer_connections WHERE id=? AND enabled=1", id).Scan(&n) == nil && n == 1
}
func assertPairingCurrent(db DBTX, generation int64, grantID string) error {
	current, e := peerGeneration(db)
	if e != nil {
		return e
	}
	if current != generation || (grantID != "" && !grantIsActive(db, grantID)) {
		return appErr("PAIRING_CANCELLED", "配对已取消，未恢复连接。", 409)
	}
	return nil
}
func (s *Store) acceptReturnConnection(input returnConnection, client, name, grantID string) error {
	generation, e := peerGeneration(s.DB)
	if e != nil {
		return e
	}
	local, e := loadIdentity(s.DB)
	if e != nil {
		return e
	}
	if input.PeerID != client || client == local.ID {
		return appErr("INVALID_PEER", "回连设备编号不匹配。", 400)
	}
	origin, e := peerOrigin(input.Origin)
	if e != nil {
		return e
	}
	if _, e = verifyPeer(origin, local.ID, &PeerConnection{ID: input.PeerID, SpaceID: input.SpaceID, PublicKey: input.PublicKey}); e != nil {
		return e
	}
	key, e := s.encryptionKey(true)
	if e != nil {
		return e
	}
	return s.Transaction(func(tx *sql.Tx) error {
		if e := assertPairingCurrent(tx, generation, grantID); e != nil {
			return e
		}
		return savePeer(tx, PeerConnection{ID: client, SpaceID: input.SpaceID, Name: name, Origin: origin, PublicKey: input.PublicKey, Token: input.Token}, key)
	})
}
func (s *Store) ConnectPeer(link, localOrigin, ownerGrantID string) error {
	generation, e := peerGeneration(s.DB)
	if e != nil {
		return e
	}
	origin, ticket, e := parsePeerLink(link)
	if e != nil {
		return e
	}
	local, e := loadIdentity(s.DB)
	if e != nil {
		return e
	}
	proof, e := verifyPeer(origin, local.ID, nil)
	if e != nil {
		return e
	}
	if e = assertPairingCurrent(s.DB, generation, ownerGrantID); e != nil {
		return e
	}
	if proof.PeerID == local.ID {
		return appErr("SELF_PAIRING", "这是本机配对码，请在另一台设备扫描。", 400)
	}
	pin, e := peerPin(s.DB, proof.PeerID)
	if e != nil {
		return e
	}
	if pin != "" && pin != proof.Identity.PublicKey {
		return appErr("PEER_IDENTITY_CHANGED", "设备身份已改变，请核对目标设备。", 409)
	}
	var callback *grantedDevice
	success := false
	if localOrigin != "" {
		localOrigin, e = peerOrigin(localOrigin)
		if e != nil {
			return e
		}
		platform := "windows"
		if proof.Capabilities.Web != nil && !*proof.Capabilities.Web {
			platform = "android"
		}
		callback, e = s.callbackGrant(proof.PeerID, proof.Device.Name, platform)
		if e != nil {
			return e
		}
		defer func() {
			if !success {
				_ = s.finishCallback(callback.Grant, false)
			}
		}()
	}
	body := Object{"token": ticket, "device_id": local.ID, "device_name": local.Name, "platform": "windows"}
	if callback != nil {
		body["return_connection"] = returnConnection{Origin: localOrigin, Token: callback.Token, PeerID: local.ID, SpaceID: local.SpaceID, PublicKey: local.PublicKey}
	}
	var result struct {
		PeerID    string  `json:"peer_id"`
		SpaceID   string  `json:"space_id"`
		Token     string  `json:"device_token"`
		PairingID *string `json:"pairing_id"`
	}
	if e = peerRequest(origin, "/api/pairing/redeem", "POST", body, "", &result); e != nil {
		return e
	}
	if !deviceTokenPattern.MatchString(result.Token) || (result.PairingID != nil && !deviceUUIDPattern.MatchString(*result.PairingID)) {
		return appErr("PEER_UNAVAILABLE", "对方返回了无效内容。", 502)
	}
	if result.PeerID != proof.PeerID || result.SpaceID != proof.SpaceID {
		return appErr("PEER_IDENTITY_CHANGED", "配对过程中设备身份改变，未保存凭据。", 409)
	}
	key, e := s.encryptionKey(true)
	if e != nil {
		return e
	}
	e = s.Transaction(func(tx *sql.Tx) error {
		if e := assertPairingCurrent(tx, generation, ownerGrantID); e != nil {
			return e
		}
		if callback != nil && !grantIsActive(tx, callback.Grant.ID) {
			return appErr("PAIRING_CANCELLED", "配对已解除，未恢复连接。", 409)
		}
		if e := savePeer(tx, PeerConnection{ID: proof.PeerID, SpaceID: proof.SpaceID, Name: proof.Device.Name, Origin: origin, PublicKey: proof.Identity.PublicKey, Token: result.Token, GrantID: result.PairingID}, key); e != nil {
			return e
		}
		if callback != nil {
			_, e := tx.Exec("UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND id<>? AND revoked_at IS NULL", nowISO(), callback.Grant.ClientID, callback.Grant.ID)
			return e
		}
		return nil
	})
	success = e == nil
	return e
}
