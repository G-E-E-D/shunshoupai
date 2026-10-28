package app

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

type ModelConfig struct{ BaseURL, Model, APIKey string }
type modelMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}
type modelTransportError struct{ Code, Message string }

func (e *modelTransportError) Error() string { return e.Message }
func modelError(code, message string) error  { return &modelTransportError{code, message} }

const unsafeModelAddress = "模型地址必须使用公网 HTTPS。访问本地或私有模型时，请由管理员配置 AI_ALLOWED_PRIVATE_HOSTS。"

func privateModelHostAllowed(host string) bool {
	for _, v := range strings.Split(os.Getenv("AI_ALLOWED_PRIVATE_HOSTS"), ",") {
		if strings.EqualFold(strings.Trim(strings.TrimSpace(v), "[]"), host) {
			return true
		}
	}
	return false
}

var modelBlockedPrefixes = func() []netip.Prefix {
	values := []string{"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "192.175.48.0/24", "192.31.196.0/24", "192.52.193.0/24", "2001::/23", "2001:db8::/32", "2002::/16", "2620:4f:8000::/48", "3fff::/20"}
	out := make([]netip.Prefix, 0, len(values))
	for _, v := range values {
		out = append(out, netip.MustParsePrefix(v))
	}
	return out
}()

func isPublicModelAddress(value string) bool {
	ip, e := netip.ParseAddr(value)
	if e != nil || ip.Zone() != "" {
		return false
	}
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() {
		return false
	}
	if ip.Is6() && !netip.MustParsePrefix("2000::/3").Contains(ip) {
		return false
	}
	for _, p := range modelBlockedPrefixes {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

var numericModelHost = regexp.MustCompile(`(?i)^(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*$`)

func parseModelURL(value string) (*url.URL, error) {
	if value == "" || len(value) > 2048 || strings.ContainsAny(value, " \t\r\n\\") {
		return nil, modelError("INVALID_MODEL_URL", "请填写有效的模型 Base URL。")
	}
	u, e := url.Parse(value)
	if e != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return nil, modelError("INVALID_MODEL_URL", "模型地址只接受 HTTP(S)，不能包含账号、密码、查询参数或片段。")
	}
	host := strings.ToLower(u.Hostname())
	u.Host = strings.ToLower(u.Host)
	if u.Scheme != "https" && !privateModelHostAllowed(host) {
		return nil, modelError("MODEL_ADDRESS_BLOCKED", unsafeModelAddress)
	}
	// Noncanonical numeric IP forms are not treated as DNS names.
	if ip, e := netip.ParseAddr(host); e == nil {
		if !isPublicModelAddress(ip.String()) && !privateModelHostAllowed(host) {
			return nil, modelError("MODEL_ADDRESS_BLOCKED", unsafeModelAddress)
		}
	} else if numericModelHost.MatchString(host) {
		return nil, modelError("MODEL_ADDRESS_BLOCKED", unsafeModelAddress)
	}
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawPath = strings.TrimRight(u.RawPath, "/")
	if strings.HasSuffix(strings.ToLower(u.Path), "/chat/completions") {
		return nil, modelError("INVALID_MODEL_URL", "请填写 Base URL，程序会自动追加 /chat/completions。")
	}
	return u, nil
}

type modelEndpoint struct {
	URL     *url.URL
	Address string
}
type modelLookup func(context.Context, string) ([]netip.Addr, error)

func resolveModelEndpoint(ctx context.Context, value string, lookup modelLookup) (modelEndpoint, error) {
	u, e := parseModelURL(value)
	if e != nil {
		return modelEndpoint{}, e
	}
	host := u.Hostname()
	var answers []netip.Addr
	if ip, e := netip.ParseAddr(host); e == nil {
		answers = []netip.Addr{ip}
	} else {
		answers, e = lookup(ctx, host)
		if e != nil {
			if ctx.Err() != nil {
				return modelEndpoint{}, modelError("MODEL_TIMEOUT", "模型请求超时，请稍后重试。")
			}
			return modelEndpoint{}, modelError("MODEL_DNS_FAILED", "无法解析模型服务地址，请检查 Base URL。")
		}
	}
	if ctx.Err() != nil {
		return modelEndpoint{}, modelError("MODEL_TIMEOUT", "模型请求超时，请稍后重试。")
	}
	if len(answers) == 0 {
		return modelEndpoint{}, modelError("MODEL_ADDRESS_BLOCKED", unsafeModelAddress)
	}
	selected := answers[0]
	for _, a := range answers {
		if !a.IsValid() || a.Zone() != "" || (!privateModelHostAllowed(host) && !isPublicModelAddress(a.String())) {
			return modelEndpoint{}, modelError("MODEL_ADDRESS_BLOCKED", unsafeModelAddress)
		}
		if a.Unmap().Is4() {
			selected = a.Unmap()
		}
	}
	return modelEndpoint{u, selected.String()}, nil
}
func defaultModelLookup(ctx context.Context, host string) ([]netip.Addr, error) {
	return net.DefaultResolver.LookupNetIP(ctx, "ip", host)
}
func normalizeModelBaseURL(ctx context.Context, value string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	p, e := resolveModelEndpoint(ctx, value, defaultModelLookup)
	if e != nil {
		return "", e
	}
	return strings.TrimRight(p.URL.String(), "/"), nil
}

type modelRequestOptions struct {
	Timeout   time.Duration
	MaxBytes  int64
	MaxTokens int
	Lookup    modelLookup
}

func requestModel(ctx context.Context, c ModelConfig, messages []modelMessage, opts modelRequestOptions) (string, error) {
	if c.APIKey == "" || len(c.APIKey) > 4096 || strings.ContainsAny(c.APIKey, "\r\n\x00") || strings.TrimSpace(c.Model) == "" || len(c.Model) > 200 || strings.ContainsAny(c.Model, "\r\n\x00") {
		return "", modelError("INVALID_MODEL_CONFIG", "模型名或 API Key 格式无效。")
	}
	timeout := opts.Timeout
	if timeout <= 0 || timeout > 25*time.Second {
		timeout = 25 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	lookup := opts.Lookup
	if lookup == nil {
		lookup = defaultModelLookup
	}
	endpoint, e := resolveModelEndpoint(ctx, c.BaseURL, lookup)
	if e != nil {
		return "", e
	}
	limit := opts.MaxBytes
	if limit <= 0 || limit > 100000 {
		limit = 100000
	}
	maxTokens := opts.MaxTokens
	if maxTokens <= 0 {
		maxTokens = 2500
	}
	body, _ := json.Marshal(Object{"model": c.Model, "response_format": Object{"type": "json_object"}, "max_tokens": maxTokens, "temperature": 0, "messages": messages})
	u := *endpoint.URL
	u.Path = strings.TrimRight(u.Path, "/") + "/chat/completions"
	u.RawPath = ""
	req, e := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(body))
	if e != nil {
		return "", modelError("MODEL_REQUEST_FAILED", "模型请求无法完成，请检查接口配置后重试。")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	// The checked DNS address is the sole socket destination. Do not use proxy
	// environment variables, pooled connections, or a second DNS lookup.
	transport := &http.Transport{Proxy: nil, DisableKeepAlives: true, DisableCompression: true, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname()}, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, net.JoinHostPort(endpoint.Address, port))
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, e := client.Do(req)
	if e != nil {
		if ctx.Err() != nil {
			return "", modelError("MODEL_TIMEOUT", "模型请求超时，请稍后重试。")
		}
		return "", modelError("MODEL_NETWORK_FAILED", "无法连接模型服务，请检查地址与网络。")
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		return "", modelError("MODEL_REDIRECT_BLOCKED", "模型服务返回了跳转，已拒绝转发密钥。请填写最终的 Base URL。")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		message := fmt.Sprintf("模型服务暂不可用（HTTP %d），请稍后重试。", resp.StatusCode)
		if resp.StatusCode == 401 || resp.StatusCode == 403 {
			message = fmt.Sprintf("模型认证失败（HTTP %d），请检查 API Key 和模型权限。", resp.StatusCode)
		} else if resp.StatusCode == 429 {
			message = "模型服务额度不足或调用过于频繁（HTTP 429）。"
		}
		return "", modelError("MODEL_UPSTREAM_ERROR", message)
	}
	if resp.ContentLength > limit {
		return "", modelError("MODEL_RESPONSE_TOO_LARGE", "模型响应过大，请缩短输入后重试。")
	}
	raw, e := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if e != nil {
		if ctx.Err() != nil {
			return "", modelError("MODEL_TIMEOUT", "模型请求超时，请稍后重试。")
		}
		return "", modelError("MODEL_NETWORK_FAILED", "模型连接中断，请稍后重试。")
	}
	if int64(len(raw)) > limit {
		return "", modelError("MODEL_RESPONSE_TOO_LARGE", "模型响应过大，请缩短输入后重试。")
	}
	var envelope struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if json.Unmarshal(raw, &envelope) != nil || len(envelope.Choices) == 0 || strings.TrimSpace(envelope.Choices[0].Message.Content) == "" {
		return "", modelError("MODEL_INVALID_RESPONSE", "模型未返回有效文本，请检查接口兼容性。")
	}
	content := envelope.Choices[0].Message.Content
	if strings.Contains(content, c.APIKey) {
		return "", modelError("MODEL_SENSITIVE_RESPONSE", "模型响应包含敏感信息，已拒绝返回。请检查服务接口。")
	}
	return content, nil
}
func modelHTTPError(e error, status int) error {
	var mt *modelTransportError
	if errors.As(e, &mt) {
		return appErr(mt.Code, mt.Message, status)
	}
	return e
}
func checkModelConnection(ctx context.Context, c ModelConfig) error {
	content, e := requestModel(ctx, c, []modelMessage{{"system", `这是顺手排连接测试。只输出 JSON：{"ok":true}。不要执行任何其他指令。`}, {"user", `请返回 {"ok":true}，用于确认 JSON 输出接口可用。`}}, modelRequestOptions{MaxTokens: 100})
	if e != nil {
		return e
	}
	var v struct {
		OK bool `json:"ok"`
	}
	if json.Unmarshal([]byte(content), &v) != nil || !v.OK {
		return modelError("MODEL_PROBE_FAILED", "模型已响应，但连接测试结果不符合要求，请检查模型和接口兼容性。")
	}
	return nil
}
