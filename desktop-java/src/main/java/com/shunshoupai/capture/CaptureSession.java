package com.shunshoupai.capture;

/** All UI operations are serialized on the EDT; tickets invalidate every delayed callback. */
final class CaptureSession {
    private volatile long generation;
    private volatile boolean active;
    long begin() { active = true; return ++generation; }
    void cancel() { active = false; generation++; }
    boolean valid(long ticket) { return active && ticket == generation; }
    boolean active() { return active; }
}
