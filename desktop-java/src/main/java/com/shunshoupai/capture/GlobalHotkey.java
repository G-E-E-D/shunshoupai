package com.shunshoupai.capture;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class GlobalHotkey implements AutoCloseable {
    static final int DEFAULT_MODIFIERS = 0x0001 | 0x0002 | 0x4000;
    private static final int ID = 0x5350;
    private volatile int nativeThread;
    private volatile boolean closed;
    private Thread thread;
    CompletableFuture<Boolean> start(Runnable action, Consumer<Boolean> status) { return start(DEFAULT_MODIFIERS, 0x53, action, status); }
    CompletableFuture<Boolean> start(int modifiers, int key, Runnable action, Consumer<Boolean> status) {
        if (thread != null) throw new IllegalStateException("Hotkey already started");
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        thread = new Thread(() -> {
            boolean registered = false;
            try {
                User32 user = User32.INSTANCE;
                WinUser.MSG message = new WinUser.MSG();
                // Initialize this platform thread's Windows queue before publishing its id.
                user.PeekMessage(message, null, 0, 0, 0);
                nativeThread = Kernel32.INSTANCE.GetCurrentThreadId();
                if (!closed) registered = user.RegisterHotKey(null, ID, modifiers, key);
                boolean available = registered;
                ready.complete(available); SwingUtilities.invokeLater(() -> { if (!closed) status.accept(available); });
                if (!registered) return;
                while (!closed) {
                    int result = user.GetMessage(message, null, 0, 0);
                    if (result <= 0) break;
                    if (message.message == 0x0312 && message.wParam.intValue() == ID)
                        SwingUtilities.invokeLater(() -> { if (!closed) action.run(); });
                }
            } catch (RuntimeException | LinkageError error) {
                ready.complete(false); SwingUtilities.invokeLater(() -> { if (!closed) status.accept(false); });
            } finally {
                if (registered) User32.INSTANCE.UnregisterHotKey(null, ID);
                nativeThread = 0;
            }
        }, "global-capture-hotkey");
        thread.setDaemon(true); thread.start(); return ready;
    }
    @Override public void close() {
        closed = true; int id = nativeThread;
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, null, null);
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
}
