package com.shunshoupai.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Only HTTP(S) service roots can become the trusted WebView origin. */
public final class ServerAddress {
    private ServerAddress() { }

    public static String normalize(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("请先填写服务地址。");
        if (input.length() > 2048 || input.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("服务地址不能包含空格或换行。");
        }
        try {
            URI uri = new URI(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("请填写以 http:// 或 https:// 开头的地址。");
            }
            if (uri.getHost() == null || uri.getRawAuthority() == null || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("请填写有效的域名或电脑的局域网 IP 地址。");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("服务地址不能包含账号、密码、查询参数或 # 片段。");
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                throw new IllegalArgumentException("请填写服务的根地址，不要附加页面路径。");
            }
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) {
                throw new IllegalArgumentException("端口应在 1–65535 之间。");
            }
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, "/", null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("这个服务地址格式不正确，请检查后重试。");
        }
    }

    public static boolean sameOrigin(String service, String destination) {
        try {
            URI first = new URI(normalize(service));
            URI second = new URI(destination);
            if (second.getHost() == null || second.getRawUserInfo() != null || second.getScheme() == null) return false;
            String scheme = second.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            return first.getScheme().equals(scheme)
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
        } catch (IllegalArgumentException | URISyntaxException error) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
    }
}
