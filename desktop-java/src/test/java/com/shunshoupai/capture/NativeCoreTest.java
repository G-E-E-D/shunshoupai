package com.shunshoupai.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class NativeCoreTest {
    @TempDir Path empty;
    @Test void hotkeyConflictIsReportedAndRegistrationReleasedOnClose() throws Exception {
        int modifiers = 0x0001 | 0x0002 | 0x0004 | 0x4000, key = 0x78; // Ctrl+Alt+Shift+F9, no physical input.
        GlobalHotkey first = new GlobalHotkey(), second = new GlobalHotkey();
        try {
            assertTrue(first.start(modifiers, key, () -> {}, available -> {}).get(4, TimeUnit.SECONDS));
            assertFalse(second.start(modifiers, key, () -> {}, available -> {}).get(4, TimeUnit.SECONDS));
        } finally { first.close(); second.close(); }
        try (GlobalHotkey after = new GlobalHotkey()) { assertTrue(after.start(modifiers, key, () -> {}, available -> {}).get(4, TimeUnit.SECONDS)); }
    }
    @Test void actualWorkerMissingModelsReturnsRecoverableError() throws Exception {
        try (OcrProcess worker = new OcrProcess(empty)) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> worker.recognize(Smoke.fixture(), Duration.ofSeconds(10)).get(15, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("识别模型"));
        }
    }
    @Test void actualLocalOcrReadsChineseEnglishMemoryImage() throws Exception {
        Path models = Path.of(System.getProperty("shunshoupai.models", ".tools/tessdata"));
        assertTrue(Files.exists(models.resolve("chi_sim.traineddata")), "Pinned models must be installed by build script");
        try (OcrProcess worker = new OcrProcess(models)) {
            String text = worker.recognize(Smoke.fixture(), Duration.ofSeconds(35)).get(45, TimeUnit.SECONDS);
            assertTrue(text.contains("开会"), text); assertTrue(text.contains("周报"), text); assertTrue(text.toLowerCase().contains("meeting"), text); assertTrue(text.contains("\n"), text);
        }
    }
    @Test void immediateCancellationNeverReturnsOldRecognitionToNewRequest() throws Exception {
        Path models = Path.of(System.getProperty("shunshoupai.models", ".tools/tessdata"));
        try (OcrProcess worker = new OcrProcess(models)) {
            CompletableFuture<String> old = worker.recognize(Smoke.fixture(), Duration.ofSeconds(35)); worker.cancel();
            assertThrows(Exception.class, () -> old.get(5, TimeUnit.SECONDS));
            assertTrue(worker.recognize(Smoke.fixture(), Duration.ofSeconds(35)).get(45, TimeUnit.SECONDS).contains("开会"));
        }
    }
    @Test void workerDeadlineTerminatesChildAndNextRecognitionStillWorks() throws Exception {
        Path models = Path.of(System.getProperty("shunshoupai.models", ".tools/tessdata"));
        try (OcrProcess worker = new OcrProcess(models)) {
            assertThrows(ExecutionException.class, () -> worker.recognize(Smoke.fixture(), Duration.ofMillis(1)).get(8, TimeUnit.SECONDS));
            assertTrue(worker.recognize(Smoke.fixture(), Duration.ofSeconds(35)).get(45, TimeUnit.SECONDS).contains("周报"));
        }
    }
    @Test void nativeModelFilesWorkUnderChinesePathWithSpaces() throws Exception {
        Path original = Path.of(System.getProperty("shunshoupai.models", ".tools/tessdata")); Path models = empty.resolve("中文模型 含空格"); Files.createDirectories(models);
        for (String language : java.util.List.of("chi_sim", "eng")) Files.copy(original.resolve(language + ".traineddata"), models.resolve(language + ".traineddata"));
        try (OcrProcess worker = new OcrProcess(models)) {
            String text = worker.recognize(Smoke.fixture(), Duration.ofSeconds(35)).get(45, TimeUnit.SECONDS);
            assertTrue(text.contains("开会"), text); assertTrue(text.toLowerCase().contains("meeting"), text);
        }
    }
}
