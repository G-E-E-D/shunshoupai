package com.shunshoupai.app;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Pure Java HTTP(S) validation; no native library or DNS lookup for hostnames. */
public final class ServerAddress {
    private ServerAddress() { }

    public static String normalize(String value) {
        URI uri = parse(value);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException("服务地址不能包含查询参数或 # 片段。");
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) throw new IllegalArgumentException("请填写服务的根地址，不要附加页面路径。");
        return origin(uri);
    }

    public static boolean sameOrigin(String service, String destination) {
        try { return normalize(service).equals(origin(parse(destination))); }
        catch (IllegalArgumentException error) { return false; }
    }

    static URI parse(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("请先填写服务地址。");
        if (input.length() > 2048) throw new IllegalArgumentException("服务地址过长，请检查后重试。");
        for (int index = 0; index < input.length(); index++) {
            char c = input.charAt(index);
            if (c <= 32 || c >= 127 || c == '\\') throw new IllegalArgumentException("服务地址不能包含空格、换行或不明确的字符。");
        }
        try {
            URI uri = new URI(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("请填写以 http:// 或 https:// 开头的地址。");
            String authority = uri.getRawAuthority();
            if (uri.getHost() == null || authority == null || authority.endsWith(":")) throw new IllegalArgumentException("请填写有效的域名或电脑的局域网 IP 地址。");
            if (uri.getRawUserInfo() != null || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0) throw new IllegalArgumentException("服务地址不能包含账号、密码或编码后的主机名。");
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) throw new IllegalArgumentException("端口应在 1–65535 之间。");
            canonicalHost(uri.getHost());
            return uri;
        } catch (URISyntaxException error) { throw new IllegalArgumentException("这个服务地址格式不正确，请检查后重试。"); }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = canonicalHost(uri.getHost());
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        int port = uri.getPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + "/";
    }

    private static String canonicalHost(String value) {
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.indexOf(':') >= 0) return canonicalIpv6(host);
        String bare = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String last = bare.substring(bare.lastIndexOf('.') + 1);
        if (last.matches("[0-9]+") || last.startsWith("0x")) {
            if (host.endsWith(".")) throw invalidHost();
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) throw invalidHost();
            for (String part : parts) if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) throw invalidHost();
        }
        return host;
    }

    private static String canonicalIpv6(String host) {
        if (host.indexOf('%') >= 0) throw invalidHost();
        try {
            // URI has validated this colon-containing IP literal; no DNS name
            // reaches InetAddress, so no network resolution is performed here.
            byte[] bytes = InetAddress.getByName(host).getAddress();
            int[] words = new int[8];
            if (bytes.length == 4) {
                words[5] = 0xffff;
                words[6] = ((bytes[0] & 255) << 8) | (bytes[1] & 255);
                words[7] = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
            } else for (int i = 0; i < 8; i++) words[i] = ((bytes[i * 2] & 255) << 8) | (bytes[i * 2 + 1] & 255);
            int bestStart = -1, bestLength = 1;
            for (int i = 0; i < 8;) {
                if (words[i] != 0) { i++; continue; }
                int start = i;
                while (i < 8 && words[i] == 0) i++;
                if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
            }
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < 8;) {
                if (i == bestStart) { output.append("::"); i += bestLength; continue; }
                if (output.length() > 0 && output.charAt(output.length() - 1) != ':') output.append(':');
                output.append(Integer.toHexString(words[i++]));
            }
            return output.toString();
        } catch (UnknownHostException error) { throw invalidHost(); }
    }

    private static IllegalArgumentException invalidHost() { return new IllegalArgumentException("请使用明确的域名或标准 IP 地址。"); }
}
