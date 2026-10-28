package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class CaptureApp implements AutoCloseable {
    private final Consumer<JsonObject> output;
    private final Path dataRoot;
    private final Path models;
    private final ScreenCapture capture = new ScreenCapture();
    private final OcrProcess ocr;
    private final GlobalHotkey hotkey = new GlobalHotkey();
    private FloatingCapture floating;
    private ProtectedInbox inbox;
    private ReviewDialog review;
    private JDialog progress, settingsDialog;
    private String scope = "", contextId = "", prior = "", captureId = "";
    private boolean hasReview, hasPending, initialized, hotkeyAvailable, processing, waiting, contextReady;
    private volatile boolean closed;
    private long operation;
    private Timer hostTimeout, ackTimeout;
    private String selectedEntry = "";
    private Consumer<ReviewDialog> reviewObserver = dialog -> {};
    CaptureApp(Path dataRoot, Path models, Consumer<JsonObject> output) { this.dataRoot = dataRoot; this.models = models; this.output = output; ocr = new OcrProcess(models); }
    // Diagnostic callers supply only generated memory images. This is never exposed as an IPC command.
    void acceptMemoryImage(BufferedImage image) { if (!initialized || !contextReady) throw new IllegalStateException("Context not ready"); recognize(image, ++operation); }
    void setReviewObserver(Consumer<ReviewDialog> observer) { reviewObserver = observer; }
    boolean isClosed() { return closed; }
    void message(JsonObject message) {
        if (closed) return;
        try {
            switch (Json.string(message, "type", "")) {
                case "init" -> {
                    if (initialized || !"1".equals(Json.string(message, "protocol", ""))) throw new IOException("截图模块协议版本不兼容。");
                    updateContext(message); initialized = true;
                    JsonObject ready = Json.message("ready"); ready.addProperty("protocol", 1); output.accept(ready);
                    hotkey.start(this::requestCapture, value -> { hotkeyAvailable = value; status(); });
                    loadFloating(); status();
                }
                case "context" -> { if (initialized) updateContext(message); }
                case "capture" -> requestCapture();
                case "capture-ready" -> beginCapture(Json.string(message, "id", ""));
                case "capture-rejected" -> rejectCapture(Json.string(message, "id", ""), Json.string(message, "message", "请先完成主程序中的操作，再开始截图。"));
                case "show-review" -> showReview();
                case "settings" -> showSettings();
                case "accepted" -> acknowledge(Json.string(message, "id", ""), true, "");
                case "rejected" -> {
                    String id = Json.string(message, "id", ""), reason = Json.string(message, "message", "请检查主程序状态后重新确认。");
                    if (!captureId.isEmpty() && captureId.equals(id)) rejectCapture(id, reason);
                    else acknowledge(id, false, reason);
                }
                case "shutdown" -> close();
                default -> error("PROTOCOL_ERROR", "无法识别主程序消息，请更新完整版本。");
            }
        } catch (IOException | RuntimeException failure) { error("CAPTURE_STATE_ERROR", failure instanceof IOException ? failure.getMessage() : "截图模块状态异常，请关闭后重新打开应用。"); }
    }
    private void updateContext(JsonObject message) throws IOException {
        String next = Json.string(message, "scope", "");
        if (!next.matches("[a-f0-9]{64}")) throw new IOException("本机日程尚未准备好，请稍后重试。");
        if (!next.equals(scope)) {
            contextReady = false; cancelCapture();
            if (review != null) {
                review.setContextAvailable(false);
                if (!review.flush()) throw new IOException("旧日程的截图文字尚未保存，请先复制保留，暂不能切换截图归属。");
            }
            waiting = false; if (ackTimeout != null) ackTimeout.stop();
            ProtectedInbox loaded = new ProtectedInbox(dataRoot, next);
            if (review != null) { review.dispose(); review = null; }
            inbox = loaded; scope = next; selectedEntry = "";
        }
        contextId = Json.string(message, "context_id", ""); prior = Json.string(message, "prior_text", "");
        hasReview = Json.bool(message, "has_review"); hasPending = Json.bool(message, "has_pending");
        contextReady = true;
        if (review != null) { review.setContextAvailable(true); review.updateContext(prior, hasReview, hasPending); }
        status();
    }
    private void loadFloating() {
        boolean enabled = false; Point position = new Point(40, 100);
        try {
            Path file = dataRoot.resolve("preferences.json");
            if (Files.exists(file)) { JsonObject prefs = Json.object(Files.readString(file)); enabled = Json.bool(prefs, "floating"); position = new Point(prefs.get("x").getAsInt(), prefs.get("y").getAsInt()); }
        } catch (IOException | RuntimeException ignored) { /* Non-sensitive optional position may reset safely. */ }
        floating = new FloatingCapture(this::requestCapture, this::showSettings, position, this::saveFloatingPosition);
        floatPosition = position;
        floating.setEnabled(enabled);
    }
    private Point floatPosition = new Point(40, 100);
    private void saveFloatingPosition(Point point) { floatPosition = point; savePreferences(); }
    private void savePreferences() {
        if (floating == null) return;
        JsonObject prefs = new JsonObject(); prefs.addProperty("floating", floating.enabled()); prefs.addProperty("x", floatPosition.x); prefs.addProperty("y", floatPosition.y);
        try { Files.createDirectories(dataRoot); Files.writeString(dataRoot.resolve("preferences.json"), Json.GSON.toJson(prefs), StandardCharsets.UTF_8); }
        catch (IOException failure) { error("PREFERENCES_WRITE_FAILED", "浮窗设置暂时无法保存，下次打开可能恢复默认状态。"); }
        status();
    }
    private void requestCapture() {
        if (!initialized || !contextReady || inbox == null) { error("NOT_READY", "本机日程尚未准备好，请先处理保留的截图文字。"); return; }
        if (processing || !captureId.isEmpty()) return;
        for (Window window : Window.getWindows()) {
            if (window.isVisible() && window instanceof Dialog dialog && dialog.isModal()) {
                error("CAPTURE_BUSY", "请先完成当前确认，再开始截图。"); return;
            }
        }
        if (inbox.entries().size() >= 20) { showReview(); error("INBOX_FULL", "请先处理已保留的截图文字。"); return; }
        if (!Files.isRegularFile(models.resolve("chi_sim.traineddata")) || !Files.isRegularFile(models.resolve("eng.traineddata"))) {
            error("OCR_MODEL_MISSING", "未找到内置中英文识别模型，请重新安装完整版本。"); return;
        }
        captureId = UUID.randomUUID().toString(); JsonObject request = Json.message("capture-request"); request.addProperty("id", captureId); output.accept(request);
        hostTimeout = new Timer(5000, e -> { if (!captureId.isEmpty()) { finishCapture(true); error("CAPTURE_HANDSHAKE_TIMEOUT", "主程序暂未响应截图，请稍后重试。"); } });
        hostTimeout.setRepeats(false); hostTimeout.start();
    }
    private void beginCapture(String id) {
        if (id.isEmpty() || !id.equals(captureId) || hostTimeout == null || !hostTimeout.isRunning()) return;
        hostTimeout.stop(); if (review != null) { if (!review.flush()) { finishCapture(true); return; } review.setVisible(false); }
        if (settingsDialog != null) settingsDialog.setVisible(false); if (floating != null) floating.hideForCapture();
        long ticket = ++operation;
        capture.start(image -> { if (ticket != operation) { image.flush(); return; } finishCapture(false); recognize(image, ticket); },
                message -> { if (ticket == operation) { finishCapture(true); restoreWindows(); error("CAPTURE_FAILED", message); } },
                () -> { if (ticket == operation) { finishCapture(true); restoreWindows(); } });
    }
    private void rejectCapture(String id, String reason) {
        if (id.isEmpty() || !id.equals(captureId)) return;
        cancelCapture(); error("CAPTURE_REJECTED", reason);
    }
    private void finishCapture(boolean cancelled) {
        if (hostTimeout != null) hostTimeout.stop();
        if (captureId.isEmpty()) return;
        JsonObject response = Json.message("capture-finished"); response.addProperty("id", captureId); response.addProperty("cancelled", cancelled);
        captureId = ""; output.accept(response);
    }
    private void recognize(BufferedImage image, long ticket) {
        processing = true;
        progress = new JDialog((Frame) null, "正在本机识别", false); JPanel panel = new JPanel(new BorderLayout(0, 15));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 25, 24, 25)); panel.add(new JLabel("正在识别选区文字，图片不会上传。"), BorderLayout.CENTER);
        JButton cancel = new JButton("取消识别"); cancel.addActionListener(e -> cancelCapture()); panel.add(cancel, BorderLayout.SOUTH);
        progress.setContentPane(panel); progress.pack(); progress.setLocationRelativeTo(null); progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progress.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent e) { cancelCapture(); } });
        progress.setVisible(true);
        ocr.recognize(image, Duration.ofSeconds(35)).whenComplete((text, failure) -> SwingUtilities.invokeLater(() -> {
            if (ticket != operation || closed) return;
            processing = false; if (progress != null) { progress.dispose(); progress = null; } restoreWindows();
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                error(cause instanceof OcrWorker.OcrFailure o ? o.code : "OCR_FAILED", cause instanceof OcrWorker.OcrFailure ? cause.getMessage() : "识别未完成，请缩小选区后重试。"); return;
            }
            try { inbox.add(text); status(); showReview(); }
            catch (IOException failureToSave) { error("INBOX_WRITE_FAILED", failureToSave.getMessage()); showUnsavedText(text); }
        }));
    }
    private void showUnsavedText(String text) {
        JTextArea area = new JTextArea(text, 14, 45); area.setLineWrap(true); area.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(null, new Object[]{"识别文字未能安全保存，请复制保留后再关闭。", new JScrollPane(area)}, "请保留识别文字", JOptionPane.WARNING_MESSAGE);
    }
    private void cancelCapture() {
        operation++; capture.cancel(false); ocr.cancel(); processing = false;
        if (progress != null) { progress.dispose(); progress = null; } finishCapture(true); restoreWindows();
    }
    private void restoreWindows() { if (floating != null) floating.restore(); }
    private void showReview() {
        if (inbox == null || inbox.first() == null) { error("INBOX_EMPTY", "没有待处理的截图文字，可以先框选截图。"); return; }
        ProtectedInbox.Entry entry = inbox.first();
        if (review != null && !selectedEntry.equals(entry.id)) { review.dispose(); review = null; }
        if (review == null) {
            selectedEntry = entry.id;
            review = new ReviewDialog(entry.text, value -> {
                if (entry.sent != null) return true;
                entry.text = value; try { inbox.save(); return true; } catch (IOException failure) { review.error("修改未能保存，请先复制保留文字。"); return false; }
            }, (value, mode) -> sendEntry(entry, value, mode), () -> discardEntry(entry));
        }
        review.updateContext(prior, hasReview, hasPending); review.setContextAvailable(contextReady); review.setSending(entry.sent != null, waiting);
        review.setVisible(true); review.toFront();
        reviewObserver.accept(review);
    }
    private void discardEntry(ProtectedInbox.Entry entry) {
        if (entry.sent != null) { review.error("上次交接结果尚未确认，请先重试核对，避免丢失。 "); return; }
        try { inbox.remove(entry.id); review.dispose(); review = null; selectedEntry = ""; status(); if (inbox.first() != null) showReview(); }
        catch (IOException failure) { review.error(failure.getMessage()); }
    }
    private void sendEntry(ProtectedInbox.Entry entry, String text, String mode) {
        if (waiting || !contextReady) return;
        if (entry.sent == null) {
            if (hasPending || hasReview || text.isBlank() || text.length() > ReviewDialog.TEXT_LIMIT) return;
            JsonObject payload = Json.message("recognized-text"); payload.addProperty("scope", scope); payload.addProperty("context_id", contextId);
            payload.addProperty("id", entry.id); payload.addProperty("text", text); payload.addProperty("mode", mode);
            entry.text = text; entry.sent = payload;
            try { inbox.save(); } catch (IOException failure) { entry.sent = null; review.error("无法安全暂存，请先复制保留文字。"); return; }
        }
        waiting = true; review.setSending(true, true); output.accept(entry.sent.deepCopy());
        if (ackTimeout != null) ackTimeout.stop();
        ackTimeout = new Timer(7000, e -> { waiting = false; if (review != null) { review.setSending(true, false); review.error("尚未收到确认。文字已保留，重试不会重复追加。"); } });
        ackTimeout.setRepeats(false); ackTimeout.start();
    }
    private void acknowledge(String id, boolean accepted, String message) throws IOException {
        if (inbox == null) return;
        ProtectedInbox.Entry entry = inbox.entries().stream().filter(e -> e.id.equals(id) && e.sent != null).findFirst().orElse(null);
        if (entry == null) return;
        waiting = false; if (ackTimeout != null) ackTimeout.stop();
        if (accepted) {
            try { inbox.remove(id); } catch (IOException failure) { if (review != null) { review.setSending(true, false); review.error("确认状态暂未保存，请重试核对。"); } throw failure; }
            if (review != null && selectedEntry.equals(id)) { review.dispose(); review = null; selectedEntry = ""; }
            status(); if (inbox.first() != null) showReview();
        } else {
            JsonObject sent = entry.sent; entry.sent = null;
            try { inbox.save(); } catch (IOException failure) { entry.sent = sent; if (review != null) review.setSending(true, false); throw failure; }
            if (review != null) { review.setSending(false, false); review.error(message); }
        }
    }
    private void showSettings() {
        if (!initialized || floating == null) return;
        if (settingsDialog != null) { settingsDialog.setVisible(true); settingsDialog.toFront(); return; }
        settingsDialog = new JDialog((Frame) null, "截图设置", false);
        JPanel panel = new JPanel(); panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24)); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JCheckBox toggle = new JCheckBox("显示悬浮截图入口", floating.enabled()); toggle.addActionListener(e -> { floating.setEnabled(toggle.isSelected()); savePreferences(); });
        panel.add(toggle); panel.add(Box.createVerticalStrut(12)); panel.add(new JLabel(hotkeyAvailable ? "快捷键：Ctrl + Alt + S" : "Ctrl + Alt + S 已被占用，可用截图按钮或浮窗。"));
        panel.add(Box.createVerticalStrut(12)); panel.add(new JLabel("中英文文字在本机识别，图片不会上传。"));
        panel.add(Box.createVerticalStrut(16)); JButton recover = new JButton("继续处理截图文字"); recover.addActionListener(e -> showReview()); panel.add(recover);
        settingsDialog.setContentPane(panel); settingsDialog.pack(); settingsDialog.setLocationRelativeTo(null); settingsDialog.setVisible(true);
    }
    private void status() {
        if (!initialized) return; JsonObject value = Json.message("status"); value.addProperty("hotkey_available", hotkeyAvailable);
        value.addProperty("floating_enabled", floating != null && floating.enabled()); value.addProperty("pending_count", inbox == null ? 0 : inbox.entries().size()); output.accept(value);
    }
    private void error(String code, String message) { JsonObject value = Json.message("error"); value.addProperty("code", code); value.addProperty("message", message); output.accept(value); }
    @Override public void close() {
        if (closed) return; closed = true; cancelCapture(); if (ackTimeout != null) ackTimeout.stop();
        if (review != null) { review.flush(); review.dispose(); } if (settingsDialog != null) settingsDialog.dispose();
        if (floating != null) floating.close(); capture.close(); ocr.close(); hotkey.close();
    }
}
