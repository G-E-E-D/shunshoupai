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
        String[] parsed = NativeCore.parseConnection(value, true);
        boolean pairing = !parsed[1].isEmpty();
        return new ScannedConnection(parsed[0], pairing ? parsed[0] + "#pair=" + parsed[1] : parsed[0], pairing);
    }
}
