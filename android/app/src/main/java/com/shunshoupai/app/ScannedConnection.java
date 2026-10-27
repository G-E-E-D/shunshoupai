package com.shunshoupai.app;

/** Separates the persistent service root from a short-lived QR login fragment. */
public final class ScannedConnection {
    public final String serviceRoot;
    public final String initialUrl;
    public final boolean hasPairing;

    private ScannedConnection(String serviceRoot, String initialUrl, boolean hasPairing) {
        this.serviceRoot = serviceRoot;
        this.initialUrl = initialUrl;
        this.hasPairing = hasPairing;
    }

    public static ScannedConnection parse(String value) {
        java.net.URI uri = ServerAddress.parse(value);
        String fragment = uri.getRawFragment();
        if (fragment == null) {
            String root = ServerAddress.normalize(value);
            return new ScannedConnection(root, root, false);
        }
        if (!fragment.matches("pair=[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("登录二维码格式无效。");
        String input = value.trim();
        String root = ServerAddress.normalize(input.substring(0, input.indexOf('#')));
        return new ScannedConnection(root, root + "#" + fragment, true);
    }
}
