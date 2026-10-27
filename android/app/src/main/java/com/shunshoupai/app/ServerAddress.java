package com.shunshoupai.app;

/** Thin Java entry point; all protocol decisions use the shared C++ core. */
public final class ServerAddress {
    private ServerAddress() { }

    public static String normalize(String value) {
        return NativeCore.parseConnection(value, false)[0];
    }

    public static boolean sameOrigin(String service, String destination) {
        return NativeCore.sameOrigin(service, destination);
    }
}
