package app

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"errors"
	"regexp"
	"time"
)

type Grant struct {
	ID         string `json:"id"`
	SpaceID    string `json:"space_id"`
	ClientID   string `json:"client_id"`
	Name       string `json:"name"`
	Platform   string `json:"platform"`
	Role       string `json:"role"`
	CreatedAt  string `json:"created_at"`
	LastSeenAt string `json:"last_seen_at"`
}
type deviceIdentity struct{ ID, SpaceID, Name, PublicKey, PrivateKey string }
type grantedDevice struct {
	Grant *Grant
	Token string
}

var deviceTokenPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
var deviceUUIDPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

const deviceCookie = "shunshoupai_device"

func initDevices(db *sql.DB) error {
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS local_device (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), id TEXT NOT NULL UNIQUE,
 space_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, public_key TEXT NOT NULL, private_key TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS device_grants (
 id TEXT PRIMARY KEY, space_id TEXT NOT NULL REFERENCES users(id), client_id TEXT NOT NULL,
 token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL, platform TEXT NOT NULL,
 role TEXT NOT NULL CHECK(role IN ('owner','peer')), created_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, revoked_at TEXT);
 CREATE TABLE IF NOT EXISTS device_pairings (
 token_hash TEXT PRIMARY KEY, owner_grant_id TEXT NOT NULL REFERENCES device_grants(id),
 space_id TEXT NOT NULL REFERENCES users(id), expires_at INTEGER NOT NULL, consumed_at INTEGER);
 CREATE TABLE IF NOT EXISTS peer_connections (
 id TEXT PRIMARY KEY, space_id TEXT NOT NULL, name TEXT NOT NULL, origin TEXT NOT NULL,
 public_key TEXT NOT NULL, credential TEXT NOT NULL, grant_id TEXT,
 enabled INTEGER NOT NULL DEFAULT 1, last_sync_at TEXT, error TEXT);
 CREATE TABLE IF NOT EXISTS peer_control(singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL);
 INSERT OR IGNORE INTO peer_control(singleton,generation) VALUES(1,0);`)
	return err
}
func loadIdentity(db DBTX) (*deviceIdentity, error) {
	i := &deviceIdentity{}
	err := db.QueryRow("SELECT id,space_id,name,public_key,private_key FROM local_device WHERE singleton=1").Scan(&i.ID, &i.SpaceID, &i.Name, &i.PublicKey, &i.PrivateKey)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, appErr("DEVICE_NOT_READY", "请先打开这台电脑上的顺手排，再生成配对码。", 503)
	}
	return i, err
}
func identityContext(i *deviceIdentity) Object {
	return Object{"capabilities": Object{"replica": 1, "web": true, "ai": true}, "peer_id": i.ID, "space_id": i.SpaceID, "device": Object{"id": i.ID, "name": i.Name}, "legacy_space_ids": []string{i.SpaceID}}
}
func (s *Store) DeviceContext() (Object, error) {
	i, e := loadIdentity(s.DB)
	if e != nil {
		return nil, e
	}
	return identityContext(i), nil
}
func (s *Store) DeviceAuthor() string {
	i, e := loadIdentity(s.DB)
	if e != nil {
		return "本机"
	}
	return i.Name
}
func lookupGrant(db DBTX, value string) (*Grant, error) {
	if !deviceTokenPattern.MatchString(value) {
		return nil, nil
	}
	g := &Grant{}
	err := db.QueryRow("SELECT id,space_id,client_id,name,platform,role,created_at,last_seen_at FROM device_grants WHERE token_hash=? AND revoked_at IS NULL", hash(value)).Scan(&g.ID, &g.SpaceID, &g.ClientID, &g.Name, &g.Platform, &g.Role, &g.CreatedAt, &g.LastSeenAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return g, err
}
func (s *Store) Authenticate(value string) (*Grant, error) {
	g, e := lookupGrant(s.DB, value)
	if e != nil || g == nil {
		return g, e
	}
	last, _ := time.Parse(time.RFC3339Nano, g.LastSeenAt)
	if time.Since(last) >= time.Minute {
		_, e = s.DB.Exec("UPDATE device_grants SET last_seen_at=? WHERE id=?", nowISO(), g.ID)
	}
	return g, e
}
func assertGrantActive(db DBTX, g *Grant) error {
	if g == nil {
		return appErr("UNAUTHORIZED", "设备授权已解除，请重新扫码配对。", 401)
	}
	var count int
	err := db.QueryRow("SELECT count(*) FROM device_grants WHERE id=? AND client_id=? AND space_id=? AND revoked_at IS NULL", g.ID, g.ClientID, g.SpaceID).Scan(&count)
	if err != nil {
		return err
	}
	if count != 1 {
		return appErr("UNAUTHORIZED", "设备授权已解除，请重新扫码配对。", 401)
	}
	return nil
}
func (s *Store) AssertActive(g *Grant) error { return assertGrantActive(s.DB, g) }
func (s *Store) assertActive(g *Grant) error { return s.AssertActive(g) }
func grantIsActive(db DBTX, id string) bool {
	var n int
	return db.QueryRow("SELECT count(*) FROM device_grants WHERE id=? AND revoked_at IS NULL", id).Scan(&n) == nil && n == 1
}
func createDeviceGrant(db DBTX, space, client, name, platform, role string, rotate bool) (*grantedDevice, error) {
	value, at := token(), nowISO()
	if rotate {
		if _, err := db.Exec("UPDATE device_grants SET revoked_at=? WHERE space_id=? AND client_id=? AND role=? AND revoked_at IS NULL", at, space, client, role); err != nil {
			return nil, err
		}
	}
	g := &Grant{ID: newID(), SpaceID: space, ClientID: client, Name: name, Platform: platform, Role: role, CreatedAt: at, LastSeenAt: at}
	_, err := db.Exec("INSERT INTO device_grants(id,space_id,client_id,token_hash,name,platform,role,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?)", g.ID, space, client, hash(value), name, platform, role, at, at)
	return &grantedDevice{g, value}, err
}
func (s *Store) Bootstrap(name, platform, currentToken, legacyToken, legacySpace string) (*grantedDevice, error) {
	var result *grantedDevice
	err := s.Transaction(func(tx *sql.Tx) error {
		i := &deviceIdentity{}
		err := tx.QueryRow("SELECT id,space_id,name,public_key,private_key FROM local_device WHERE singleton=1").Scan(&i.ID, &i.SpaceID, &i.Name, &i.PublicKey, &i.PrivateKey)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if errors.Is(err, sql.ErrNoRows) {
			space := ""
			if legacyToken != "" && len(legacyToken) <= 128 {
				e := tx.QueryRow("SELECT user_id FROM sessions WHERE token_hash=? AND expires_at>?", hash(legacyToken), time.Now().UnixMilli()).Scan(&space)
				if e != nil && !errors.Is(e, sql.ErrNoRows) {
					return e
				}
			}
			rows, e := tx.Query("SELECT id,email FROM users ORDER BY created_at")
			if e != nil {
				return e
			}
			spaces := []Object{}
			for rows.Next() {
				var id, label string
				if e = rows.Scan(&id, &label); e != nil {
					rows.Close()
					return e
				}
				spaces = append(spaces, Object{"id": id, "label": label})
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return e
			}
			if legacySpace != "" {
				found := false
				for _, p := range spaces {
					if p["id"] == legacySpace {
						found = true
					}
				}
				if !found {
					return appErr("INVALID_SPACE", "找不到这份旧版日程，请重新选择。", 400)
				}
				space = legacySpace
			}
			if space == "" && len(spaces) > 1 {
				return appErr("MIGRATION_REQUIRED", "发现多份旧版日程，请选择在这台设备继续使用的一份；其他数据会保留。", 409, Object{"spaces": spaces})
			}
			if space == "" && len(spaces) == 1 {
				space = spaces[0]["id"].(string)
			}
			if space == "" {
				space = newID()
				if _, e = tx.Exec("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)", space, "device-"+space+"@local.invalid", "device-only", nowISO()); e != nil {
					return e
				}
			}
			key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			if e != nil {
				return e
			}
			pub, e := x509.MarshalPKIXPublicKey(&key.PublicKey)
			if e != nil {
				return e
			}
			priv, e := x509.MarshalPKCS8PrivateKey(key)
			if e != nil {
				return e
			}
			i = &deviceIdentity{newID(), space, name, base64.StdEncoding.EncodeToString(pub), base64.StdEncoding.EncodeToString(priv)}
			if _, e = tx.Exec("INSERT INTO local_device(singleton,id,space_id,name,public_key,private_key) VALUES(1,?,?,?,?,?)", i.ID, i.SpaceID, i.Name, i.PublicKey, i.PrivateKey); e != nil {
				return e
			}
		}
		g, e := lookupGrant(tx, currentToken)
		if e != nil {
			return e
		}
		if g != nil && g.Role == "owner" && g.SpaceID == i.SpaceID {
			result = &grantedDevice{g, currentToken}
			return nil
		}
		result, e = createDeviceGrant(tx, i.SpaceID, i.ID, name, platform, "owner", true)
		return e
	})
	return result, err
}
func (s *Store) DeviceProof(challenge, client string) (Object, error) {
	i, err := loadIdentity(s.DB)
	if err != nil {
		return nil, err
	}
	bytes, err := base64.StdEncoding.DecodeString(i.PrivateKey)
	if err != nil {
		return nil, err
	}
	key, err := x509.ParsePKCS8PrivateKey(bytes)
	if err != nil {
		return nil, err
	}
	ec, ok := key.(*ecdsa.PrivateKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, errors.New("invalid device key")
	}
	digest := sha256.Sum256([]byte(challenge + "\n" + client + "\n" + i.ID + "\n" + i.SpaceID))
	signature, err := ecdsa.SignASN1(rand.Reader, ec, digest[:])
	if err != nil {
		return nil, err
	}
	result := identityContext(i)
	result["identity"] = Object{"public_key": i.PublicKey, "signature": base64.StdEncoding.EncodeToString(signature)}
	return result, nil
}
func (s *Store) IssuePairing(g *Grant) (Object, error) {
	if g == nil || g.Role != "owner" {
		return nil, appErr("OWNER_REQUIRED", "请在本机生成设备配对码。", 403)
	}
	var result Object
	err := s.Transaction(func(tx *sql.Tx) error {
		if e := assertGrantActive(tx, g); e != nil {
			return e
		}
		now := time.Now()
		if _, e := tx.Exec("DELETE FROM device_pairings WHERE expires_at<=? OR owner_grant_id=?", now.UnixMilli(), g.ID); e != nil {
			return e
		}
		value := token()
		expires := now.Add(90 * time.Second)
		if _, e := tx.Exec("INSERT INTO device_pairings(token_hash,owner_grant_id,space_id,expires_at) VALUES(?,?,?,?)", hash(value), g.ID, g.SpaceID, expires.UnixMilli()); e != nil {
			return e
		}
		i, e := loadIdentity(tx)
		if e != nil {
			return e
		}
		result = identityContext(i)
		result["token"] = value
		result["expires_at"] = expires.UTC().Format("2006-01-02T15:04:05.000Z")
		result["pairing_limited"] = false
		result["session_expires_at"] = nil
		return nil
	})
	return result, err
}
func (s *Store) RedeemPairing(value, client, name, platform string) (*grantedDevice, error) {
	var result *grantedDevice
	err := s.Transaction(func(tx *sql.Tx) error {
		var space string
		e := tx.QueryRow(`SELECT p.space_id FROM device_pairings p JOIN device_grants g ON p.owner_grant_id=g.id WHERE p.token_hash=? AND p.expires_at>? AND p.consumed_at IS NULL AND g.revoked_at IS NULL AND g.role='owner'`, hash(value), time.Now().UnixMilli()).Scan(&space)
		if errors.Is(e, sql.ErrNoRows) {
			return appErr("PAIRING_INVALID", "配对码已过期或已使用，请在显示配对码的设备上重新生成。", 400)
		}
		if e != nil {
			return e
		}
		if _, e = tx.Exec("UPDATE device_pairings SET consumed_at=? WHERE token_hash=?", time.Now().UnixMilli(), hash(value)); e != nil {
			return e
		}
		result, e = createDeviceGrant(tx, space, client, name, platform, "peer", true)
		return e
	})
	return result, err
}
func (s *Store) callbackGrant(client, name, platform string) (*grantedDevice, error) {
	var result *grantedDevice
	e := s.Transaction(func(tx *sql.Tx) error {
		i, e := loadIdentity(tx)
		if e != nil {
			return e
		}
		result, e = createDeviceGrant(tx, i.SpaceID, client, name, platform, "peer", false)
		return e
	})
	return result, e
}
func (s *Store) finishCallback(g *Grant, success bool) error {
	op := "="
	if success {
		op = "<>"
	}
	_, e := s.DB.Exec("UPDATE device_grants SET revoked_at=? WHERE client_id=? AND role='peer' AND id"+op+"? AND revoked_at IS NULL", nowISO(), g.ClientID, g.ID)
	return e
}
func (s *Store) revokePeerGrant(id string) error {
	_, e := s.DB.Exec("UPDATE device_grants SET revoked_at=? WHERE id=? AND role='peer' AND revoked_at IS NULL", nowISO(), id)
	return e
}
func (s *Store) RevokeDevice(actor *Grant, id string) (Object, error) {
	var result Object
	e := s.Transaction(func(tx *sql.Tx) error {
		if e := assertGrantActive(tx, actor); e != nil {
			return e
		}
		var role, client string
		e := tx.QueryRow("SELECT role,client_id FROM device_grants WHERE id=? AND space_id=? AND revoked_at IS NULL", id, actor.SpaceID).Scan(&role, &client)
		if errors.Is(e, sql.ErrNoRows) || (actor.Role != "owner" && id != actor.ID) {
			return appErr("NOT_FOUND", "找不到这台已配对设备。", 404)
		}
		if e != nil {
			return e
		}
		if role == "owner" {
			return appErr("LOCAL_DEVICE", "本机日程无需解除配对。", 400)
		}
		if _, e = tx.Exec("UPDATE device_grants SET revoked_at=? WHERE id=?", nowISO(), id); e != nil {
			return e
		}
		if e = disablePeer(tx, client); e != nil {
			return e
		}
		result = Object{"revoked": true, "id": id, "client_id": client}
		return nil
	})
	return result, e
}
