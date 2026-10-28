package app

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type AppError struct {
	Code, Message string
	Status        int
	Details       any
}

func (e *AppError) Error() string { return e.Message }
func appErr(code, message string, status int, details ...any) error {
	e := &AppError{Code: code, Message: message, Status: status}
	if len(details) > 0 {
		e.Details = details[0]
	}
	return e
}

type response struct {
	Status int
	Data   any
}
type rateBucket struct {
	Count   int
	Expires time.Time
}
type Server struct {
	Store     *Store
	mux       *http.ServeMux
	peerState peerWorker
	rates     map[string]rateBucket
	rateMu    sync.Mutex
}

func NewServer(store *Store, webDir string) *Server {
	s := &Server{Store: store, mux: http.NewServeMux(), rates: make(map[string]rateBucket)}
	s.registerDevices()
	s.registerPeers()
	s.registerEvents()
	s.registerModels()
	s.route("GET /healthz", func(w http.ResponseWriter, r *http.Request) (any, error) {
		return Object{"ok": true, "backend": "go"}, nil
	})
	s.mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, appErr("NOT_FOUND", "接口不存在。", 404))
	})
	s.mux.Handle("/", staticFiles(webDir))
	return s
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "same-origin")
	w.Header().Set("X-Frame-Options", "DENY")
	defer func() {
		if recover() != nil {
			log.Print("request failed: panic")
			writeError(w, appErr("INTERNAL_ERROR", "服务暂时无法完成操作，请稍后重试。", 500))
		}
	}()
	if storage := os.Getenv("APP_STORAGE"); storage != "" && storage != "local" {
		writeError(w, appErr("LOCAL_STORAGE_REQUIRED", "设备直连版使用本机数据。旧云端数据保持原样，请先导出后迁移到本机。", 503))
		return
	}
	s.mux.ServeHTTP(w, r)
}
func (s *Server) route(pattern string, fn func(http.ResponseWriter, *http.Request) (any, error)) {
	s.mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "private, no-store, max-age=0")
		w.Header().Set("Vary", "Cookie, Authorization")
		data, err := fn(w, r)
		if err != nil {
			writeError(w, err)
			return
		}
		status := 200
		if v, ok := data.(response); ok {
			status = v.Status
			data = v.Data
		}
		writeJSON(w, status, data)
	})
}
func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	e := json.NewEncoder(w)
	e.SetEscapeHTML(false)
	_ = e.Encode(data)
}
func writeError(w http.ResponseWriter, err error) {
	var e *AppError
	if !errors.As(err, &e) {
		log.Print("request failed: internal error")
		e = &AppError{Code: "INTERNAL_ERROR", Message: "服务暂时无法完成操作，请稍后重试。", Status: 500}
	}
	v := Object{"code": e.Code, "message": e.Message}
	if e.Details != nil {
		v["details"] = e.Details
	}
	writeJSON(w, e.Status, Object{"error": v})
}
func sameOrigin(r *http.Request) error {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return nil
	}
	u, err := url.Parse(origin)
	valid := err == nil && u.User == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != "" && u.RawQuery == "" && u.Fragment == "" && (u.Path == "" || u.Path == "/")
	if allowed := os.Getenv("APP_ORIGIN"); allowed != "" {
		a, e := url.Parse(allowed)
		valid = valid && e == nil && u.Scheme == a.Scheme && u.Host == a.Host
	} else {
		valid = valid && u.Host == r.Host
	}
	if !valid {
		return appErr("INVALID_ORIGIN", "请求来源不受信任，请从应用页面重试。", 403)
	}
	return nil
}
func readJSON(r *http.Request, target any, maxBytes int64) error {
	if err := sameOrigin(r); err != nil {
		return err
	}
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		return appErr("INVALID_CONTENT_TYPE", "请求必须使用 JSON 格式。", 415)
	}
	if maxBytes <= 0 {
		maxBytes = 32768
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, maxBytes+1))
	if err != nil {
		return appErr("INVALID_JSON", "请求不是有效的 JSON。", 400)
	}
	if int64(len(data)) > maxBytes {
		return appErr("BODY_TOO_LARGE", "请求内容过长。", 413)
	}
	if !json.Valid(data) {
		return appErr("INVALID_JSON", "请求不是有效的 JSON。", 400)
	}
	// Optional fields may be omitted, but the original wire schemas never allow
	// explicit null. Go's decoder otherwise treats null strings/pointers as absent.
	var shape any
	_ = json.Unmarshal(data, &shape)
	if containsNull(shape) {
		return appErr("VALIDATION_ERROR", "请求字段不能为 null。", 400)
	}
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if err := d.Decode(target); err != nil {
		return appErr("VALIDATION_ERROR", "请检查输入的字段与时间。", 400)
	}
	return nil
}

func containsNull(value any) bool {
	if value == nil {
		return true
	}
	switch v := value.(type) {
	case map[string]any:
		for _, item := range v {
			if containsNull(item) {
				return true
			}
		}
	case []any:
		for _, item := range v {
			if containsNull(item) {
				return true
			}
		}
	}
	return false
}
func (s *Server) rateLimit(key string, limit int, window time.Duration) error {
	s.rateMu.Lock()
	defer s.rateMu.Unlock()
	now := time.Now()
	if len(s.rates) > 10000 {
		for k, b := range s.rates {
			if !b.Expires.After(now) {
				delete(s.rates, k)
			}
		}
		if len(s.rates) > 10000 {
			return appErr("RATE_LIMITED", "服务正忙，请稍后再试。", 429)
		}
	}
	b := s.rates[key]
	if !b.Expires.After(now) {
		b = rateBucket{Expires: now.Add(window)}
	}
	if b.Count >= limit {
		return appErr("RATE_LIMITED", "操作过于频繁，请稍后再试。", 429)
	}
	b.Count++
	s.rates[key] = b
	return nil
}
func staticFiles(root string) http.Handler {
	absolute, _ := filepath.Abs(root)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" && r.Method != "HEAD" {
			http.Error(w, "Method not allowed", 405)
			return
		}
		p := path.Clean("/" + r.URL.Path)
		if p == "/" {
			if strings.Contains(r.UserAgent(), "ShunshoupaiAndroid/") || os.Getenv("APP_MOBILE_UI_TEST") == "true" {
				p = "/mobile/index.html"
			} else {
				p = "/index.html"
			}
		}
		if p == "/mobile" || p == "/mobile/" {
			p = "/mobile/index.html"
		}
		for _, segment := range strings.Split(p, "/") {
			if strings.HasPrefix(segment, ".") {
				http.NotFound(w, r)
				return
			}
		}
		filename := filepath.Join(absolute, filepath.FromSlash(strings.TrimPrefix(p, "/")))
		rel, err := filepath.Rel(absolute, filename)
		if err != nil || strings.HasPrefix(rel, "..") {
			http.NotFound(w, r)
			return
		}
		info, err := os.Stat(filename)
		if err != nil || !info.Mode().IsRegular() {
			http.NotFound(w, r)
			return
		}
		// ServeContent avoids /index.html redirects: /mobile is a stable WebView entry.
		file, err := os.Open(filename)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		defer file.Close()
		if strings.HasPrefix(p, "/_next/static/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		http.ServeContent(w, r, info.Name(), info.ModTime(), file)
	})
}
