package app

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

type modelSetting struct {
	BaseURL, Model, Ciphertext, Hint sql.NullString
	Version                          int
	UpdatedAt                        string
	DeletedAt                        sql.NullString
}
type modelInput struct {
	BaseVersion *int   `json:"base_version,omitempty"`
	BaseURL     string `json:"base_url"`
	Model       string `json:"model"`
	APIKey      string `json:"api_key,omitempty"`
}

func readModelSetting(db DBTX, space string) (*modelSetting, error) {
	v := new(modelSetting)
	e := db.QueryRow("SELECT base_url,model,api_key_ciphertext,key_hint,version,updated_at,deleted_at FROM model_settings WHERE user_id=?", space).Scan(&v.BaseURL, &v.Model, &v.Ciphertext, &v.Hint, &v.Version, &v.UpdatedAt, &v.DeletedAt)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	return v, e
}
func hasPersonalModel(v *modelSetting) bool {
	return v != nil && !v.DeletedAt.Valid && v.BaseURL.String != "" && v.Model.String != "" && v.Ciphertext.String != ""
}
func serverModelConfig() *ModelConfig {
	key := strings.TrimSpace(os.Getenv("AI_API_KEY"))
	if key == "" {
		return nil
	}
	base := os.Getenv("AI_BASE_URL")
	if base == "" {
		base = "https://api.deepseek.com"
	}
	model := os.Getenv("AI_MODEL")
	if model == "" {
		model = "deepseek-flash"
	}
	return &ModelConfig{base, model, key}
}
func modelDefaults() (string, string) {
	base := "https://api.deepseek.com"
	if u, e := url.Parse(os.Getenv("AI_BASE_URL")); e == nil && u.Hostname() != "" && (u.Scheme == "http" || u.Scheme == "https") {
		u.User = nil
		u.RawQuery = ""
		u.Fragment = ""
		base = strings.TrimRight(u.String(), "/")
	}
	model := os.Getenv("AI_MODEL")
	if model == "" {
		model = "deepseek-flash"
	}
	return base, model
}
func (s *Store) effectiveAISource(space string) (string, error) {
	v, e := readModelSetting(s.DB, space)
	if e != nil {
		return "", e
	}
	if hasPersonalModel(v) || serverModelConfig() != nil {
		return "model", nil
	}
	return "rules", nil
}
func (s *Store) modelInfo(v *modelSetting) Object {
	configured := hasPersonalModel(v)
	base, model := modelDefaults()
	var hint, updated any
	version := 0
	source := "rules"
	if serverModelConfig() != nil {
		source = "server"
	}
	if v != nil {
		version = v.Version
		updated = v.UpdatedAt
	}
	if configured {
		base = v.BaseURL.String
		model = v.Model.String
		source = "personal"
		h := v.Hint.String
		hint = "已保存"
		if h == "已保存" || (strings.HasPrefix(h, "••••") && len(strings.TrimPrefix(h, "••••")) == 4 && modelASCII(strings.TrimPrefix(h, "••••"))) {
			hint = h
		}
	}
	return Object{"configured": configured, "base_url": base, "model": model, "has_api_key": configured, "key_hint": hint, "version": version, "updated_at": updated, "effective_source": source, "encryption_available": s.modelEncryptionAvailable()}
}
func modelASCII(v string) bool {
	for _, c := range v {
		if c < 33 || c > 126 {
			return false
		}
	}
	return true
}
func validateModelInput(v *modelInput, save bool) error {
	v.BaseURL = strings.TrimSpace(v.BaseURL)
	v.Model = strings.TrimSpace(v.Model)
	v.APIKey = strings.TrimSpace(v.APIKey)
	bad := v.BaseURL == "" || len(v.BaseURL) > 2048 || v.Model == "" || utf8.RuneCountInString(v.Model) > 200 || len(v.APIKey) > 4096 || !modelASCII(v.APIKey)
	for _, c := range v.Model {
		if unicode.IsControl(c) {
			bad = true
		}
	}
	if save {
		bad = bad || v.BaseVersion == nil || (v.BaseVersion != nil && *v.BaseVersion < 0)
	} else {
		bad = bad || v.BaseVersion != nil
	}
	if bad {
		return appErr("VALIDATION_ERROR", "请检查模型接口地址、名称和 API Key。", 400)
	}
	return nil
}
func (s *Store) preparedModelConfig(ctx context.Context, space string, v modelInput, old *modelSetting) (ModelConfig, error) {
	base, e := normalizeModelBaseURL(ctx, v.BaseURL)
	if e != nil {
		return ModelConfig{}, modelHTTPError(e, 400)
	}
	key := v.APIKey
	if key == "" {
		if !hasPersonalModel(old) {
			return ModelConfig{}, appErr("MODEL_KEY_REQUIRED", "首次连接个人模型时，请填写 API Key。", 400)
		}
		a, _ := url.Parse(base)
		b, e := url.Parse(old.BaseURL.String)
		if e != nil || a.Scheme != b.Scheme || !strings.EqualFold(a.Host, b.Host) {
			return ModelConfig{}, appErr("MODEL_KEY_REQUIRED", "更换模型服务地址后，请重新填写 API Key，避免把原密钥发送给其他服务。", 400)
		}
		enc, e := s.encryptionKey(false)
		if e != nil {
			return ModelConfig{}, e
		}
		key, e = decryptSecret(old.Ciphertext.String, space, enc)
		if e != nil {
			return ModelConfig{}, e
		}
	}
	return ModelConfig{base, v.Model, key}, nil
}
func (s *Store) resolvedModelConfig(space string) (*ModelConfig, error) {
	v, e := readModelSetting(s.DB, space)
	if e != nil {
		return nil, e
	}
	if !hasPersonalModel(v) {
		return serverModelConfig(), nil
	}
	key, e := s.encryptionKey(false)
	if e != nil {
		return nil, e
	}
	secret, e := decryptSecret(v.Ciphertext.String, space, key)
	if e != nil {
		return nil, e
	}
	return &ModelConfig{v.BaseURL.String, v.Model.String, secret}, nil
}
func modelVersionConflict() error {
	return appErr("VERSION_CONFLICT", "模型设置已在另一端更新，请重新加载后再操作。", 409)
}
func modelGrantActive(db DBTX, g *Grant) error {
	var found int
	e := db.QueryRow("SELECT 1 FROM device_grants WHERE id=? AND client_id=? AND space_id=? AND revoked_at IS NULL", g.ID, g.ClientID, g.SpaceID).Scan(&found)
	if errors.Is(e, sql.ErrNoRows) {
		return appErr("UNAUTHORIZED", "设备授权已解除，请重新扫码配对。", 401)
	}
	return e
}
func (s *Store) saveModel(ctx context.Context, g *Grant, v modelInput) (Object, error) {
	old, e := readModelSetting(s.DB, g.SpaceID)
	if e != nil {
		return nil, e
	}
	version := 0
	if old != nil {
		version = old.Version
	}
	if version != *v.BaseVersion {
		return nil, modelVersionConflict()
	}
	c, e := s.preparedModelConfig(ctx, g.SpaceID, v, old)
	if e != nil {
		return nil, e
	}
	key, e := s.encryptionKey(true)
	if e != nil {
		return nil, e
	}
	encrypted, e := encryptSecret(c.APIKey, g.SpaceID, key)
	if e != nil {
		return nil, e
	}
	hint := "已保存"
	if len(c.APIKey) >= 8 {
		hint = "••••" + c.APIKey[len(c.APIKey)-4:]
	}
	var saved *modelSetting
	e = s.Transaction(func(tx *sql.Tx) error {
		if e := modelGrantActive(tx, g); e != nil {
			return e
		}
		fresh, e := readModelSetting(tx, g.SpaceID)
		if e != nil {
			return e
		}
		ver := 0
		if fresh != nil {
			ver = fresh.Version
		}
		if ver != *v.BaseVersion {
			return modelVersionConflict()
		}
		_, e = tx.Exec(`INSERT INTO model_settings(user_id,base_url,model,api_key_ciphertext,key_hint,version,updated_at,deleted_at) VALUES(?,?,?,?,?,?,?,NULL) ON CONFLICT(user_id) DO UPDATE SET base_url=excluded.base_url,model=excluded.model,api_key_ciphertext=excluded.api_key_ciphertext,key_hint=excluded.key_hint,version=excluded.version,updated_at=excluded.updated_at,deleted_at=NULL`, g.SpaceID, c.BaseURL, c.Model, encrypted, hint, ver+1, nowISO())
		if e != nil {
			return e
		}
		saved, e = readModelSetting(tx, g.SpaceID)
		return e
	})
	if e != nil {
		return nil, e
	}
	return s.modelInfo(saved), nil
}
func (s *Store) deleteModel(g *Grant, base int) (Object, error) {
	var result *modelSetting
	e := s.Transaction(func(tx *sql.Tx) error {
		if e := modelGrantActive(tx, g); e != nil {
			return e
		}
		old, e := readModelSetting(tx, g.SpaceID)
		if e != nil {
			return e
		}
		if old == nil {
			if base != 0 {
				return modelVersionConflict()
			}
			return nil
		}
		if old.DeletedAt.Valid && (old.Version == base || old.Version == base+1) {
			result = old
			return nil
		}
		if old.Version != base {
			return modelVersionConflict()
		}
		now := nowISO()
		_, e = tx.Exec("UPDATE model_settings SET base_url=NULL,model=NULL,api_key_ciphertext=NULL,key_hint=NULL,version=version+1,updated_at=?,deleted_at=? WHERE user_id=? AND version=?", now, now, g.SpaceID, base)
		if e != nil {
			return e
		}
		result, e = readModelSetting(tx, g.SpaceID)
		return e
	})
	if e != nil {
		return nil, e
	}
	return s.modelInfo(result), nil
}
func (s *Store) consumeModelQuota(g *Grant) error {
	limit := 50
	if n, e := strconv.Atoi(os.Getenv("AI_DAILY_LIMIT")); e == nil {
		limit = max(1, min(n, 100))
	}
	return s.Transaction(func(tx *sql.Tx) error {
		if e := modelGrantActive(tx, g); e != nil {
			return e
		}
		day := time.Now().UTC().Format("2006-01-02")
		var count int
		e := tx.QueryRow("SELECT count FROM ai_usage WHERE user_id=? AND usage_day=?", g.SpaceID, day).Scan(&count)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		if count >= limit {
			return appErr("AI_QUOTA_EXCEEDED", "今天的解析次数已用完，仍可手工创建日程。", 429)
		}
		_, e = tx.Exec("INSERT INTO ai_usage(user_id,usage_day,count) VALUES(?,?,1) ON CONFLICT(user_id,usage_day) DO UPDATE SET count=count+1", g.SpaceID, day)
		return e
	})
}
func (s *Server) registerModels() {
	s.route("GET /api/model-settings", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireLocalFeature(r)
		if e != nil {
			return nil, e
		}
		if e = s.rateLimit("model-settings:read:"+g.SpaceID, 120, time.Minute); e != nil {
			return nil, e
		}
		v, e := readModelSetting(s.Store.DB, g.SpaceID)
		if e != nil {
			return nil, e
		}
		return Object{"settings": s.Store.modelInfo(v)}, nil
	})
	s.route("PUT /api/model-settings", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireLocalFeature(r)
		if e != nil {
			return nil, e
		}
		var v modelInput
		if e = readJSON(r, &v, 32768); e != nil {
			return nil, e
		}
		if e = validateModelInput(&v, true); e != nil {
			return nil, e
		}
		if e = s.rateLimit("model-settings:write:"+g.SpaceID, 30, time.Minute); e != nil {
			return nil, e
		}
		result, e := s.Store.saveModel(r.Context(), g, v)
		return Object{"settings": result}, e
	})
	s.route("DELETE /api/model-settings", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireLocalFeature(r)
		if e != nil {
			return nil, e
		}
		var v struct {
			BaseVersion *int `json:"base_version"`
		}
		if e = readJSON(r, &v, 32768); e != nil {
			return nil, e
		}
		if v.BaseVersion == nil || *v.BaseVersion < 0 {
			return nil, appErr("VALIDATION_ERROR", "请检查设置版本。", 400)
		}
		if e = s.rateLimit("model-settings:write:"+g.SpaceID, 30, time.Minute); e != nil {
			return nil, e
		}
		result, e := s.Store.deleteModel(g, *v.BaseVersion)
		return Object{"settings": result}, e
	})
	s.route("POST /api/model-settings/test", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireLocalFeature(r)
		if e != nil {
			return nil, e
		}
		var v modelInput
		if e = readJSON(r, &v, 32768); e != nil {
			return nil, e
		}
		if e = validateModelInput(&v, false); e != nil {
			return nil, e
		}
		if e = s.rateLimit("model-settings:test:"+g.SpaceID, 6, time.Minute); e != nil {
			return nil, e
		}
		old, e := readModelSetting(s.Store.DB, g.SpaceID)
		if e != nil {
			return nil, e
		}
		c, e := s.Store.preparedModelConfig(r.Context(), g.SpaceID, v, old)
		if e != nil {
			return nil, e
		}
		if e = s.Store.consumeModelQuota(g); e != nil {
			return nil, e
		}
		if e = checkModelConnection(r.Context(), c); e != nil {
			return nil, modelHTTPError(e, 502)
		}
		return Object{"success": true, "message": "连接成功，模型已通过 JSON 输出测试。"}, nil
	})
	s.route("POST /api/ai/parse", s.handleParse)
}
