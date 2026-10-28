package app

import (
	"bytes"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strings"
)

type connectionInfo struct {
	CurrentOrigin string   `json:"current_origin"`
	Candidates    []string `json:"candidates"`
	Hint          string   `json:"hint"`
}

var virtualInterface = regexp.MustCompile(`(?i)loopback|docker|veth|^br-|vbox|virtualbox|vmware|vethernet|hyper-v|wsl|tailscale|zerotier|\btun\d*\b|\btap\d*\b|\bwg\d+\b`)

func localOnlyHost(host string) bool {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if host == "localhost" || strings.HasSuffix(host, ".localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsUnspecified())
}
func privateV4Rank(value string) int {
	ip := net.ParseIP(value).To4()
	if ip == nil {
		return -1
	}
	if ip[0] == 192 && ip[1] == 168 {
		return 0
	}
	if ip[0] == 10 {
		return 1
	}
	if ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31 {
		return 2
	}
	return -1
}
func connectionURL(value string, configured bool) (*url.URL, error) {
	u, e := url.Parse(value)
	invalid := e != nil
	if !invalid {
		invalid = (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || u.Opaque != ""
		if configured {
			invalid = invalid || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || (u.Path != "" && u.Path != "/")
		}
	}
	if invalid {
		if configured {
			return nil, appErr("CONFIG_ERROR", "APP_ORIGIN 需要填写完整的 HTTP(S) 服务来源，不能包含路径、账号或参数。", 503)
		}
		return nil, appErr("INVALID_ORIGIN", "无法识别当前服务地址，请从正常的应用页面打开。", 400)
	}
	return u, nil
}
func originOf(u *url.URL) string {
	host := strings.ToLower(u.Host)
	if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
		host = strings.ToLower(u.Hostname())
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
	}
	return u.Scheme + "://" + host
}
func buildConnectionInfo(r *http.Request) (connectionInfo, error) {
	configured := strings.TrimSpace(os.Getenv("APP_ORIGIN"))
	if configured != "" {
		u, e := connectionURL(configured, true)
		if e != nil {
			return connectionInfo{}, e
		}
		origin := originOf(u)
		if localOnlyHost(u.Hostname()) {
			return connectionInfo{origin, []string{}, "APP_ORIGIN 配置为本机地址，其他设备无法通过它连接。请管理员改为可访问的服务地址，或在局域网演示中留空。"}, nil
		}
		return connectionInfo{origin, []string{origin}, "使用已配置的设备地址。连接地址本身不授予访问权限，请生成一次性设备配对码。"}, nil
	}
	host := r.Host
	if host == "" {
		host = r.URL.Host
	}
	if strings.ContainsAny(host, " \t\r\n/\\?#@") {
		return connectionInfo{}, appErr("INVALID_ORIGIN", "请求的服务地址格式无效。", 400)
	}
	scheme := "http"
	if r.TLS != nil || r.URL.Scheme == "https" {
		scheme = "https"
	}
	u, e := connectionURL(scheme+"://"+host, false)
	if e != nil {
		return connectionInfo{}, e
	}
	origin := originOf(u)
	if !localOnlyHost(u.Hostname()) {
		hint := "请使用同一局域网内的设备地址，并通过一次性配对码建立连接。"
		if privateV4Rank(u.Hostname()) >= 0 {
			hint = "两台设备连接同一局域网，扫描一次性配对码后自动同步。"
		}
		return connectionInfo{origin, []string{origin}, hint}, nil
	}
	interfaces, e := net.Interfaces()
	if e != nil {
		return connectionInfo{}, e
	}
	addresses := map[string]bool{}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagLoopback != 0 || virtualInterface.MatchString(iface.Name) {
			continue
		}
		entries, e := iface.Addrs()
		if e != nil {
			continue
		}
		for _, entry := range entries {
			ip, _, e := net.ParseCIDR(entry.String())
			if e == nil && privateV4Rank(ip.String()) >= 0 {
				addresses[ip.String()] = true
			}
		}
	}
	sorted := make([]string, 0, len(addresses))
	for address := range addresses {
		sorted = append(sorted, address)
	}
	sort.Slice(sorted, func(i, j int) bool {
		a, b := privateV4Rank(sorted[i]), privateV4Rank(sorted[j])
		if a != b {
			return a < b
		}
		return bytes.Compare(net.ParseIP(sorted[i]).To4(), net.ParseIP(sorted[j]).To4()) < 0
	})
	if len(sorted) > 8 {
		sorted = sorted[:8]
	}
	candidates := []string{}
	for _, address := range sorted {
		if u.Port() != "" {
			address = net.JoinHostPort(address, u.Port())
		}
		candidates = append(candidates, u.Scheme+"://"+address)
	}
	hint := "当前地址只指向电脑本机，未找到可用局域网 IPv4。请手动填写电脑的局域网地址或已部署网址。"
	if len(candidates) > 0 {
		hint = "已找到电脑的局域网地址。两台设备连接同一 Wi-Fi 后扫码；多张网卡时请选择对方可访问的地址。"
	}
	return connectionInfo{origin, candidates, hint}, nil
}
