package app

import (
	"crypto/subtle"
	"net/http"
	"os"
	"strings"
	"time"
	"unicode/utf8"
)

func credential(r *http.Request) string {
	if values, ok := r.Header["Authorization"]; ok {
		if len(values) == 1 && strings.HasPrefix(values[0], "Bearer ") {
			return strings.TrimPrefix(values[0], "Bearer ")
		}
		return ""
	}
	c, e := r.Cookie(deviceCookie)
	if e != nil {
		return ""
	}
	return c.Value
}
func setDeviceCookie(w http.ResponseWriter, value string) {
	age := 365 * 24 * 60 * 60
	if value == "" {
		age = -1
	}
	http.SetCookie(w, &http.Cookie{Name: deviceCookie, Value: value, Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: os.Getenv("COOKIE_SECURE") == "true", MaxAge: age})
}
func deviceStorage() error {
	v := os.Getenv("APP_STORAGE")
	if v != "" && v != "local" {
		return appErr("LOCAL_STORAGE_REQUIRED", "设备直连版使用本机数据。旧云端数据保持原样，请先导出后迁移到本机。", 503)
	}
	return nil
}
func (s *Server) requireAuth(r *http.Request) (*Grant, error) {
	if e := deviceStorage(); e != nil {
		return nil, e
	}
	g, e := s.Store.Authenticate(credential(r))
	if e != nil {
		return nil, e
	}
	if g == nil {
		return nil, appErr("UNAUTHORIZED", "请先扫描本机配对码，允许这台设备访问日程。", 401)
	}
	return g, nil
}
func (s *Server) requireOwner(r *http.Request) (*Grant, error) {
	g, e := s.requireAuth(r)
	if e != nil {
		return nil, e
	}
	if g.Role != "owner" {
		return nil, appErr("OWNER_REQUIRED", "请在本机管理配对设备。", 403)
	}
	return g, nil
}

// A paired device receives calendar permissions, never the local model service.
func (s *Server) requireLocalFeature(r *http.Request) (*Grant, error) {
	g, e := s.requireAuth(r)
	if e != nil {
		return nil, e
	}
	if g.Role != "owner" {
		return nil, appErr("SYNC_ONLY_DEVICE", "配对仅用于同步日程，请在当前设备配置和使用智能整理。", 403)
	}
	return g, nil
}
func (s *Server) session(g *Grant) (Object, error) {
	ai := "rules"
	if os.Getenv("AI_API_KEY") != "" {
		ai = "model"
	}
	if g == nil {
		return Object{"user": nil, "mode": "local", "ai_provider": ai, "local_first": true}, nil
	}
	ctx, e := s.Store.DeviceContext()
	if e != nil {
		return nil, e
	}
	ctx["user"] = Object{"id": g.SpaceID, "email": ""}
	ctx["mode"] = "local"
	if g.Role != "owner" {
		ai = "rules"
	}
	ctx["ai_provider"] = ai
	ctx["local_first"] = true
	ctx["role"] = g.Role
	ctx["peer"] = Object{"id": g.ID, "name": g.Name, "platform": g.Platform}
	ctx["pairing_limited"] = false
	ctx["session_expires_at"] = nil
	return ctx, nil
}
func deviceNameOK(value string) bool {
	if value == "" || utf8.RuneCountInString(value) > 80 {
		return false
	}
	for _, r := range value {
		if r < 32 || r == 127 {
			return false
		}
	}
	return true
}
func deviceValidation() error {
	return appErr("VALIDATION_ERROR", "请检查输入的字段与时间。", 400)
}
func (s *Server) registerDevices() {
	s.route("POST /api/devices/bootstrap", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := sameOrigin(r); e != nil {
			return nil, e
		}
		if e := deviceStorage(); e != nil {
			return nil, e
		}
		expected, supplied := os.Getenv("APP_DEVICE_BOOTSTRAP_KEY"), r.Header.Get("X-Device-Bootstrap")
		if len(expected) < 32 || subtle.ConstantTimeCompare([]byte(expected), []byte(supplied)) != 1 {
			return nil, appErr("BOOTSTRAP_DENIED", "请从这台电脑的原生应用打开日程；其他设备需要扫码配对。", 403)
		}
		var input struct {
			Name        string `json:"name"`
			Platform    string `json:"platform"`
			LegacySpace string `json:"legacy_space_id"`
		}
		if e := readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		input.Name = strings.TrimSpace(input.Name)
		if !deviceNameOK(input.Name) || (input.Platform != "windows" && input.Platform != "test") || (input.LegacySpace != "" && !deviceUUIDPattern.MatchString(input.LegacySpace)) {
			return nil, deviceValidation()
		}
		legacy := ""
		if c, e := r.Cookie("shunshoupai_session"); e == nil {
			legacy = c.Value
		}
		result, e := s.Store.Bootstrap(input.Name, input.Platform, credential(r), legacy, input.LegacySpace)
		if e != nil {
			return nil, e
		}
		setDeviceCookie(w, result.Token)
		http.SetCookie(w, &http.Cookie{Name: "shunshoupai_session", Value: "", Path: "/", MaxAge: -1})
		return s.session(result.Grant)
	})
	s.route("GET /api/auth/session", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := deviceStorage(); e != nil {
			return nil, e
		}
		g, e := s.Store.Authenticate(credential(r))
		if e != nil {
			return nil, e
		}
		info, e := s.session(g)
		if e != nil || g == nil || g.Role != "owner" {
			return info, e
		}
		source, e := s.Store.effectiveAISource(g.SpaceID)
		if e != nil {
			return nil, e
		}
		info["ai_provider"] = source
		return info, nil
	})
	s.route("POST /api/auth/{action}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := sameOrigin(r); e != nil {
			return nil, e
		}
		return nil, appErr("ACCOUNTS_REMOVED", "此版本无需账号。打开本机应用即可使用，其他设备通过局域网扫码配对。", 410)
	})
	s.route("GET /api/devices/info", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireAuth(r)
		if e != nil {
			return nil, e
		}
		return s.session(g)
	})
	s.route("POST /api/devices/web-session", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := sameOrigin(r); e != nil {
			return nil, e
		}
		g, e := s.requireLocalFeature(r)
		if e != nil {
			return nil, e
		}
		setDeviceCookie(w, credential(r))
		return s.session(g)
	})
	s.route("GET /api/devices/identity", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := deviceStorage(); e != nil {
			return nil, e
		}
		if e := s.rateLimit("device:identity", 120, time.Minute); e != nil {
			return nil, e
		}
		q := r.URL.Query()
		challenge, client := q.Get("challenge"), q.Get("device_id")
		if !deviceTokenPattern.MatchString(challenge) || !deviceUUIDPattern.MatchString(client) {
			return nil, deviceValidation()
		}
		return s.Store.DeviceProof(challenge, client)
	})
	s.route("POST /api/pairing/issue", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireAuth(r)
		if e != nil {
			return nil, e
		}
		var input struct{}
		if e = readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		if e = s.rateLimit("pairing:issue:"+g.ID, 10, time.Minute); e != nil {
			return nil, e
		}
		return s.Store.IssuePairing(g)
	})
	s.route("POST /api/pairing/redeem", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := deviceStorage(); e != nil {
			return nil, e
		}
		var input struct {
			Token     string            `json:"token"`
			DeviceID  string            `json:"device_id"`
			Name      string            `json:"device_name"`
			Platform  string            `json:"platform"`
			Challenge string            `json:"challenge"`
			Return    *returnConnection `json:"return_connection"`
		}
		if e := readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		input.Name = strings.TrimSpace(input.Name)
		if input.Platform == "" {
			input.Platform = "web"
		}
		if !deviceTokenPattern.MatchString(input.Token) || !deviceUUIDPattern.MatchString(input.DeviceID) || !deviceNameOK(input.Name) || (input.Challenge != "" && !deviceTokenPattern.MatchString(input.Challenge)) || (input.Platform != "windows" && input.Platform != "android" && input.Platform != "web" && input.Platform != "test") {
			return nil, deviceValidation()
		}
		if input.Return != nil {
			if e := input.Return.validate(); e != nil {
				return nil, e
			}
		}
		if e := s.rateLimit("pairing:redeem:global", 300, time.Minute); e != nil {
			return nil, e
		}
		if e := s.rateLimit("pairing:redeem:"+hash(input.Token), 4, time.Minute); e != nil {
			return nil, e
		}
		result, e := s.Store.RedeemPairing(input.Token, input.DeviceID, input.Name, input.Platform)
		if e != nil {
			return nil, e
		}
		if input.Return != nil {
			if e = s.Store.acceptReturnConnection(*input.Return, input.DeviceID, input.Name, result.Grant.ID); e != nil {
				_ = s.Store.revokePeerGrant(result.Grant.ID)
				return nil, e
			}
		}
		info, e := s.session(result.Grant)
		if e != nil {
			return nil, e
		}
		info["device_token"] = result.Token
		info["pairing_id"] = result.Grant.ID
		if input.Challenge != "" {
			proof, e := s.Store.DeviceProof(input.Challenge, input.DeviceID)
			if e != nil {
				return nil, e
			}
			for k, v := range proof {
				info[k] = v
			}
		}
		setDeviceCookie(w, result.Token)
		s.queuePeerSync()
		return info, nil
	})
	s.route("GET /api/devices", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireAuth(r)
		if e != nil {
			return nil, e
		}
		ctx, e := s.Store.DeviceContext()
		if e != nil {
			return nil, e
		}
		rows, e := s.Store.DB.Query("SELECT id,client_id,name,platform,role,created_at,last_seen_at FROM device_grants WHERE space_id=? AND revoked_at IS NULL AND (?='owner' OR id=?) ORDER BY created_at", g.SpaceID, g.Role, g.ID)
		if e != nil {
			return nil, e
		}
		list := []Object{}
		for rows.Next() {
			var id, client, name, platform, role, created, seen string
			if e = rows.Scan(&id, &client, &name, &platform, &role, &created, &seen); e != nil {
				rows.Close()
				return nil, e
			}
			list = append(list, Object{"id": id, "client_id": client, "name": name, "platform": platform, "created_at": created, "last_seen_at": seen, "current": id == g.ID, "local": role == "owner"})
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return nil, e
		}
		ctx["devices"] = list
		if g.Role == "owner" {
			peers, e := s.Store.listPeers()
			if e != nil {
				return nil, e
			}
			ctx["peers"] = peers
			ctx["connection"] = s.peerConnectionStatus()
		}
		return ctx, nil
	})
	s.route("DELETE /api/devices/{id}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		if e := sameOrigin(r); e != nil {
			return nil, e
		}
		g, e := s.requireAuth(r)
		if e != nil {
			return nil, e
		}
		id := r.PathValue("id")
		if !deviceUUIDPattern.MatchString(id) {
			return nil, deviceValidation()
		}
		result, e := s.Store.RevokeDevice(g, id)
		if e == nil && id == g.ID {
			setDeviceCookie(w, "")
		}
		return result, e
	})
}
