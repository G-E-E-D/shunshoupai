package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Only --capture-smoke may read the desktop or generate physical input. Memory smoke never does. */
final class Smoke {
    private Smoke() {}
    static BufferedImage fixture() {
        BufferedImage image = new BufferedImage(1100, 270, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 48));
            g.drawString("明天上午9点开会", 30, 74); g.drawString("明天下午3点写周报", 30, 151);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 33)); g.drawString("Project meeting 09:00", 30, 225);
        } finally { g.dispose(); }
        return image;
    }
    static void run(boolean physical, Path models, Path output) throws Exception {
        Files.createDirectories(output); BufferedImage input = physical ? physicalCapture() : fixture();
        String text;
        try (OcrProcess worker = new OcrProcess(models)) { text = worker.recognize(input, Duration.ofSeconds(35)).get(45, TimeUnit.SECONDS); }
        if (!text.contains("开会") || !text.contains("周报") || !text.contains("9") || !text.contains("3")) throw new IllegalStateException("Real OCR did not recognize the fictional Chinese fixture: " + text);
        AtomicReference<ReviewDialog> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ReviewDialog dialog = new ReviewDialog(text, value -> true, (value, mode) -> {}, () -> {});
            dialog.updateContext("明天晚上整理资料", false, false); reference.set(dialog);
            dialog.getRootPane().doLayout(); dialog.getContentPane().doLayout();
        });
        SwingUtilities.invokeAndWait(() -> {
            ReviewDialog dialog = reference.get(); JRootPane root = dialog.getRootPane();
            BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); try { root.printAll(g); } finally { g.dispose(); }
            try { ImageIO.write(image, "png", output.resolve("java-ocr-review.png").toFile()); } catch (Exception failure) { throw new RuntimeException(failure); }
            dialog.dispose();
        });
        JsonObject report = new JsonObject(); report.addProperty("ok", true); report.addProperty("physical_capture", physical); report.addProperty("real_local_ocr", true); report.addProperty("text", text);
        Files.writeString(output.resolve("java-ocr-smoke.json"), Json.GSON.toJson(report), StandardCharsets.UTF_8);
        new java.io.PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true).println(Json.GSON.toJson(report)); System.exit(0);
    }
    private static BufferedImage physicalCapture() throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Interactive Windows desktop required");
        JFrame[] fixtureWindow = new JFrame[1]; ScreenCapture[] capture = new ScreenCapture[1];
        CompletableFuture<BufferedImage> captured = new CompletableFuture<>();
        CountDownLatch hotkeyCalled = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Shunshoupai fictional capture fixture"); frame.setUndecorated(true); frame.setAlwaysOnTop(true);
            frame.setContentPane(new JLabel(new ImageIcon(fixture()))); frame.pack(); frame.setLocation(80, 100); frame.setVisible(true);
            fixtureWindow[0] = frame; capture[0] = new ScreenCapture();
        });
        GlobalHotkey hotkey = new GlobalHotkey();
        try {
            boolean registered = hotkey.start(() -> {
                hotkeyCalled.countDown(); capture[0].start(captured::complete, error -> captured.completeExceptionally(new IllegalStateException(error)), () -> captured.completeExceptionally(new IllegalStateException("Selection cancelled")));
            }, available -> {}).get(5, TimeUnit.SECONDS);
            if (!registered) throw new IllegalStateException("Ctrl+Alt+S unavailable on CI desktop");
            Robot robot = new Robot(); robot.setAutoDelay(90); robot.waitForIdle(); Thread.sleep(700);
            robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_ALT); robot.keyPress(KeyEvent.VK_S);
            robot.keyRelease(KeyEvent.VK_S); robot.keyRelease(KeyEvent.VK_ALT); robot.keyRelease(KeyEvent.VK_CONTROL);
            if (!hotkeyCalled.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Physical global hotkey was not delivered; interactive desktop required");
            Thread.sleep(1200); robot.mouseMove(95, 112); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            for (int i = 1; i <= 10; i++) robot.mouseMove(95 + i * 103, 112 + i * 25);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            return captured.get(8, TimeUnit.SECONDS);
        } finally { hotkey.close(); SwingUtilities.invokeAndWait(() -> { capture[0].close(); fixtureWindow[0].dispose(); }); }
    }
    /** Only installed by the explicit --ipc-memory-smoke diagnostic command. */
    static final class IpcDriver {
        private final Path output;
        private CaptureApp app;
        private java.util.function.Consumer<JsonObject> emit;
        private JsonObject recognized;
        private int acknowledgements;
        private boolean started, confirmed, finished;
        private javax.swing.Timer deadline;
        IpcDriver(Path output) throws Exception { this.output = output; Files.createDirectories(output); }
        void attach(CaptureApp app, java.util.function.Consumer<JsonObject> emit) {
            this.app = app; this.emit = emit;
            app.setReviewObserver(dialog -> {
                if (confirmed || finished) return;
                try {
                    if (!dialog.text.getText().contains("开会") || !dialog.text.getText().contains("周报")) throw new IllegalStateException("Real OCR fixture mismatch");
                    JRootPane root = dialog.getRootPane(); BufferedImage render = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = render.createGraphics(); try { root.printAll(graphics); } finally { graphics.dispose(); }
                    ImageIO.write(render, "png", output.resolve("java-ipc-ocr-review.png").toFile());
                    JButton button = findConfirm(dialog);
                    if (button == null || !button.isEnabled()) throw new IllegalStateException("Review confirmation is disabled");
                    confirmed = true; SwingUtilities.invokeLater(button::doClick);
                } catch (Exception error) { finish(false, error.getMessage()); }
            });
            deadline = new javax.swing.Timer(65000, event -> finish(false, "IPC memory smoke timed out")); deadline.setRepeats(false); deadline.start();
        }
        void outgoing(JsonObject message) {
            if ("recognized-text".equals(Json.string(message, "type", ""))) recognized = message.deepCopy();
            if ("error".equals(Json.string(message, "type", ""))) finish(false, Json.string(message, "code", "IPC_ERROR"));
        }
        void incoming(JsonObject message) {
            if (finished) return;
            if ("init".equals(Json.string(message, "type", "")) && !started) {
                started = true; BufferedImage complete = fixture(); BufferedImage twoLines = new BufferedImage(1100, 180, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = twoLines.createGraphics(); try { graphics.drawImage(complete, 0, 0, null); } finally { graphics.dispose(); complete.flush(); }
                app.acceptMemoryImage(twoLines);
            }
            if ("accepted".equals(Json.string(message, "type", "")) && recognized != null && Json.string(message, "id", "").equals(Json.string(recognized, "id", ""))) {
                acknowledgements++;
                if (acknowledgements == 1) emit.accept(recognized.deepCopy()); // Verify the host's durable receipt path.
                else if (acknowledgements == 2) finish(true, "");
            }
            if ("rejected".equals(Json.string(message, "type", ""))) finish(false, Json.string(message, "message", "Host rejected fixture"));
        }
        private JButton findConfirm(Container parent) {
            for (Component component : parent.getComponents()) {
                if (component instanceof JButton button && "capture-confirm".equals(button.getName())) return button;
                if (component instanceof Container child) { JButton found = findConfirm(child); if (found != null) return found; }
            }
            return null;
        }
        private void finish(boolean success, String message) {
            if (finished) return; finished = true; if (deadline != null) deadline.stop();
            JsonObject report = new JsonObject(); report.addProperty("ok", success); report.addProperty("physical_capture", false); report.addProperty("real_local_ocr", recognized != null);
            report.addProperty("accepted_count", acknowledgements); report.addProperty("message", message);
            if (recognized != null) report.add("recognized", recognized);
            try { Files.writeString(output.resolve("java-ipc-memory-smoke.json"), Json.GSON.toJson(report), StandardCharsets.UTF_8); }
            catch (Exception ignored) { success = false; }
            int exit = success ? 0 : 1;
            app.close();
            // Exit off the EDT so the normal shutdown hook can dispose Swing windows on the EDT.
            new Thread(() -> System.exit(exit), "ipc-smoke-finish").start();
        }
    }
}
