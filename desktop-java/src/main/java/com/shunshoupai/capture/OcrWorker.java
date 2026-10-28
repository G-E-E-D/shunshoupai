package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import net.sourceforge.tess4j.Tesseract;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class OcrWorker {
    static final int MAGIC = 0x53535031;
    static final int MAX_PNG = 48_000_000;
    private OcrWorker() {}
    static void run(Path models) {
        JsonObject response = new JsonObject();
        try {
            DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
            if (input.readInt() != MAGIC) throw new IOException("Invalid input");
            int length = input.readInt(); if (length < 1 || length > MAX_PNG) throw new IOException("Invalid size");
            byte[] encoded = input.readNBytes(length); if (encoded.length != length) throw new EOFException();
            if (!Files.isRegularFile(models.resolve("chi_sim.traineddata")) || !Files.isRegularFile(models.resolve("eng.traineddata")))
                throw new OcrFailure("OCR_MODEL_MISSING", "未找到内置中英文识别模型，请重新安装完整版本。");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null || (long) image.getWidth() * image.getHeight() > CaptureGeometry.MAX_PIXELS) throw new IOException("Invalid bitmap");
            Tesseract engine = new Tesseract(); engine.setDatapath(models.toString());
            engine.setLanguage("chi_sim+eng"); engine.setOcrEngineMode(1); engine.setPageSegMode(6);
            String text;
            try { text = engine.doOCR(image); } finally { image.flush(); java.util.Arrays.fill(encoded, (byte) 0); }
            text = normalize(text);
            if (text.isBlank()) throw new OcrFailure("OCR_EMPTY", "没有识别到文字，请框选更清晰的文字区域。");
            response.addProperty("ok", true); response.addProperty("text", text);
        } catch (OcrFailure error) {
            response.addProperty("ok", false); response.addProperty("code", error.code); response.addProperty("message", error.getMessage());
        } catch (LinkageError error) {
            response.addProperty("ok", false); response.addProperty("code", "OCR_ENGINE_MISSING");
            response.addProperty("message", "本机文字识别组件无法加载，请重新安装完整版本并检查 Microsoft Visual C++ v14 运行库。");
        } catch (Exception error) {
            response.addProperty("ok", false); response.addProperty("code", "OCR_FAILED"); response.addProperty("message", "文字识别未完成，请缩小选区后重试。");
        }
        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true).println(Json.GSON.toJson(response));
    }
    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?<=[\\p{IsHan}])[\\t ]+(?=[\\p{IsHan}])", "").strip();
    }
    static final class OcrFailure extends IOException {
        final String code;
        OcrFailure(String code, String message) { super(message); this.code = code; }
    }
}
