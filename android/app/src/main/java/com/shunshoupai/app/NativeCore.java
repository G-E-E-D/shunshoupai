package com.shunshoupai.app;

/** Shared C++ protocol validation used by real service, QR and WebView flows. */
final class NativeCore {
    static { System.loadLibrary("shunshoupai_core"); }

    private NativeCore() { }

    // [canonical root, ephemeral pairing token]. The token is never persisted.
    static native String[] parseConnection(String value, boolean allowPairing);
    static native boolean sameOrigin(String service, String destination);
}
