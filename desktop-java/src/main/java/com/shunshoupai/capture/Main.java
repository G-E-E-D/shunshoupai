package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        List<String> arguments = Arrays.asList(args);
        Path models = models(arguments);
        if (arguments.contains("--ocr-worker")) { OcrWorker.run(Path.of(argument(arguments, "--model-dir", models.toString()))); return; }
        if (arguments.contains("--memory-smoke") || arguments.contains("--capture-smoke")) {
            Smoke.run(arguments.contains("--capture-smoke"), models, Path.of(argument(arguments, "--output", "target/smoke"))); return;
        }
        boolean ipcMemorySmoke = arguments.contains("--ipc-memory-smoke");
        if (!arguments.contains("--ipc") && !ipcMemorySmoke) { System.err.println("Use --ipc to start the calendar capture module."); return; }
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) throw new IOException("LOCALAPPDATA is unavailable");
        PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        CaptureApp[] holder = new CaptureApp[1];
        Smoke.IpcDriver driver = ipcMemorySmoke ? new Smoke.IpcDriver(Path.of(argument(arguments, "--output", "target/ipc-memory-smoke"))) : null;
        java.util.function.Consumer<JsonObject> emit = message -> { synchronized (output) { output.println(Json.GSON.toJson(message)); } };
        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
            holder[0] = new CaptureApp(Path.of(local, "Shunshoupai", "capture-java"), models, message -> { if (driver != null) driver.outgoing(message); emit.accept(message); });
            if (driver != null) driver.attach(holder[0], emit);
        });
        Thread shutdown = new Thread(() -> { if (!holder[0].isClosed()) try { SwingUtilities.invokeAndWait(holder[0]::close); } catch (Exception ignored) {} }, "capture-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (Reader input = new InputStreamReader(System.in, StandardCharsets.UTF_8)) {
            String line;
            while ((line = readLine(input)) != null) {
                JsonObject message;
                try { message = Json.object(line); }
                catch (RuntimeException invalid) { break; }
                if ("shutdown".equals(Json.string(message, "type", ""))) break;
                SwingUtilities.invokeLater(() -> { holder[0].message(message); if (driver != null) driver.incoming(message); });
            }
        } finally { SwingUtilities.invokeAndWait(holder[0]::close); Runtime.getRuntime().removeShutdownHook(shutdown); }
        System.exit(0);
    }
    static String readLine(Reader input) throws IOException {
        StringBuilder text = new StringBuilder(); int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (text.length() >= 512 * 1024) throw new IOException("Protocol message too large");
            if (value != '\r') text.append((char) value);
        }
        return value == -1 && text.isEmpty() ? null : text.toString();
    }
    static Path models(List<String> args) {
        String fallback = System.getProperty("shunshoupai.models", System.getenv().getOrDefault("SHUNSHOUPAI_OCR_MODELS", "resources/tessdata"));
        return Path.of(argument(args, "--model-dir", fallback)).toAbsolutePath();
    }
    private static String argument(List<String> args, String key, String fallback) { int index = args.indexOf(key); return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback; }
}
