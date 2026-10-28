package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** An interrupt alone cannot safely abort a native OCR call. The worker is an expendable process. */
final class OcrProcess implements AutoCloseable {
    private final Path models;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ocr-process"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ocr-timeout"); t.setDaemon(true); return t; });
    private Process process;
    private long generation;
    OcrProcess(Path models) { this.models = models; }
    synchronized CompletableFuture<String> recognize(BufferedImage image, Duration timeout) {
        cancel(); long ticket = generation;
        return CompletableFuture.supplyAsync(() -> {
            Process child = null; ScheduledFuture<?> deadline = null;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "png", bytes); image.flush(); byte[] png = bytes.toByteArray();
                if (png.length > OcrWorker.MAX_PNG) throw new OcrWorker.OcrFailure("OCR_TOO_LARGE", "选区太大，请缩小后重试。");
                synchronized (this) {
                    if (ticket != generation) throw new CancellationException();
                    String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
                    // Tesseract's narrow Windows file API cannot reliably open a UTF-8 absolute path.
                    // CreateProcess receives the model working directory as UTF-16; native OCR then opens ASCII relative filenames.
                    Path working = Files.isDirectory(models) ? models.toAbsolutePath() : null;
                    String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator), -1))
                            .map(entry -> new File(entry.isEmpty() ? "." : entry).getAbsolutePath()).collect(java.util.stream.Collectors.joining(File.pathSeparator));
                    List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                            "-Dfile.encoding=UTF-8", "-cp", classpath, Main.class.getName(), "--ocr-worker", "--model-dir", working == null ? models.toString() : ".");
                    ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
                    if (working != null) builder.directory(working.toFile());
                    child = builder.start(); process = child;
                }
                Process owned = child;
                deadline = timer.schedule(owned::destroyForcibly, timeout.toMillis(), TimeUnit.MILLISECONDS);
                try (DataOutputStream input = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))) {
                    input.writeInt(OcrWorker.MAGIC); input.writeInt(png.length); input.write(png);
                } finally { java.util.Arrays.fill(png, (byte) 0); }
                byte[] output = child.getInputStream().readNBytes(512 * 1024 + 1);
                boolean exited = child.waitFor(2, TimeUnit.SECONDS);
                synchronized (this) { if (ticket != generation) throw new CancellationException(); }
                if (!exited || child.exitValue() != 0 || output.length == 0) throw new OcrWorker.OcrFailure("OCR_TIMEOUT", "识别耗时过长或组件意外退出，请缩小选区后重试。");
                if (output.length > 512 * 1024) throw new OcrWorker.OcrFailure("OCR_TOO_LARGE", "识别内容过多，请分段框选。");
                JsonObject result = Json.object(new String(output, StandardCharsets.UTF_8));
                if (!Json.bool(result, "ok")) throw new OcrWorker.OcrFailure(Json.string(result, "code", "OCR_FAILED"), Json.string(result, "message", "识别失败，请重试。"));
                return result.get("text").getAsString();
            } catch (CancellationException error) { throw error; }
            catch (Exception error) { throw new CompletionException(error); }
            finally {
                image.flush(); if (deadline != null) deadline.cancel(false);
                if (child != null) child.destroyForcibly();
                synchronized (this) { if (process == child) process = null; }
            }
        }, executor);
    }
    synchronized void cancel() { generation++; if (process != null) { process.destroyForcibly(); process = null; } }
    @Override public void close() { cancel(); executor.shutdownNow(); timer.shutdownNow(); }
}
