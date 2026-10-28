package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class ReviewProtocolTest {
    @TempDir Path root;
    private final String scope = "c".repeat(64);
    static Component named(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container child) { Component found = named(child, name); if (found != null) return found; }
        }
        return null;
    }
    @Test void oversizedRecognitionIsFullyEditableAndNeverSilentlyTruncated() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String oversized = "中".repeat(2001); AtomicReference<String> sent = new AtomicReference<>();
            ReviewDialog dialog = new ReviewDialog(oversized, value -> true, (value, mode) -> sent.set(value), () -> {});
            try {
                dialog.updateContext("", false, false); JButton confirm = (JButton) named(dialog, "capture-confirm");
                assertEquals(oversized, dialog.text.getText()); assertFalse(confirm.isEnabled()); confirm.doClick(); assertNull(sent.get());
                dialog.text.setText("明天开会"); assertTrue(confirm.isEnabled()); confirm.doClick(); assertEquals("明天开会", sent.get());
            } finally { dialog.dispose(); }
        });
    }
    @Test void existingInputDefaultsToExplicitAppendAndDraftsBlockNewSubmission() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> mode = new AtomicReference<>();
            ReviewDialog dialog = new ReviewDialog("截图新文字", value -> true, (value, selected) -> mode.set(selected), () -> {});
            try {
                JButton confirm = (JButton) named(dialog, "capture-confirm"); dialog.updateContext("原来输入", false, false);
                assertEquals("追加并整理", confirm.getText()); confirm.doClick(); assertEquals("append", mode.get());
                dialog.updateContext("原来输入", true, false); assertFalse(confirm.isEnabled());
                dialog.updateContext("原来输入", false, true); assertFalse(confirm.isEnabled());
                dialog.updateContext("原".repeat(2000), false, false); assertFalse(confirm.isEnabled()); assertEquals("截图新文字", dialog.text.getText());
            } finally { dialog.dispose(); }
        });
    }
    @Test void unsuccessfulDurableWritePreventsConfirm() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<String> sent = new AtomicReference<>(); ReviewDialog dialog = new ReviewDialog("不丢内容", value -> false, (value, mode) -> sent.set(value), () -> {});
            try { dialog.updateContext("", false, false); ((JButton) named(dialog, "capture-confirm")).doClick(); assertNull(sent.get()); }
            finally { dialog.dispose(); }
        });
    }
    @Test void unacknowledgedRestoredPayloadRetriesSameIdContextAndTextThenClearsOnlyOnAck() throws Exception {
        ProtectedInbox store = new ProtectedInbox(root, scope); ProtectedInbox.Entry entry = store.add("已确认文本");
        JsonObject sent = Json.message("recognized-text"); sent.addProperty("scope", scope); sent.addProperty("context_id", "7");
        sent.addProperty("id", entry.id); sent.addProperty("text", entry.text); sent.addProperty("mode", "append"); entry.sent = sent; store.save();
        List<JsonObject> messages = new ArrayList<>(); CaptureApp[] app = new CaptureApp[1];
        SwingUtilities.invokeAndWait(() -> {
            app[0] = new CaptureApp(root, root, value -> messages.add(value.deepCopy())); JsonObject init = Json.message("init");
            init.addProperty("protocol", 1); init.addProperty("scope", scope); init.addProperty("context_id", "99"); init.addProperty("prior_text", "后来变化的输入");
            app[0].message(init); app[0].message(Json.message("show-review"));
            for (Window window : Window.getWindows()) if (window instanceof ReviewDialog dialog && window.isDisplayable()) {
                assertFalse(dialog.text.isEditable()); ((JButton) named(dialog, "capture-confirm")).doClick();
            }
        });
        try {
            JsonObject replay = messages.stream().filter(v -> "recognized-text".equals(Json.string(v, "type", ""))).findFirst().orElseThrow();
            assertEquals(sent, replay); assertNotNull(new ProtectedInbox(root, scope).first());
            SwingUtilities.invokeAndWait(() -> { JsonObject ack = Json.message("accepted"); ack.addProperty("id", entry.id); app[0].message(ack); app[0].message(ack); });
            assertNull(new ProtectedInbox(root, scope).first());
        } finally { SwingUtilities.invokeAndWait(app[0]::close); }
    }
    @Test void scopeSwitchWithFailedSaveKeepsEditedTextAndBlocksCapture() throws Exception {
        new ProtectedInbox(root, scope).add("最初文字"); List<JsonObject> messages = new ArrayList<>(); CaptureApp[] app = new CaptureApp[1];
        AtomicReference<ReviewDialog> current = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            app[0] = new CaptureApp(root, root, value -> messages.add(value.deepCopy())); JsonObject init = Json.message("init");
            init.addProperty("protocol", 1); init.addProperty("scope", scope); init.addProperty("context_id", "old:1"); app[0].message(init); app[0].message(Json.message("show-review"));
            for (Window window : Window.getWindows()) if (window instanceof ReviewDialog dialog && window.isDisplayable()) current.set(dialog);
        });
        try {
            Path file = root.resolve(scope + ".bin"); Files.move(file, root.resolve("preserved-original.bin")); Files.createDirectory(file); Files.writeString(file.resolve("block"), "test fixture");
            SwingUtilities.invokeAndWait(() -> {
                ReviewDialog dialog = current.get(); dialog.text.setText("编辑后的内容必须保留");
                JsonObject context = Json.message("context"); context.addProperty("scope", "e".repeat(64)); context.addProperty("context_id", "new:1"); app[0].message(context);
                assertTrue(dialog.isDisplayable()); assertEquals("编辑后的内容必须保留", dialog.text.getText()); assertFalse(((JButton) named(dialog, "capture-confirm")).isEnabled());
                app[0].message(Json.message("capture"));
            });
            assertTrue(messages.stream().anyMatch(value -> "NOT_READY".equals(Json.string(value, "code", ""))));
            assertFalse(messages.stream().anyMatch(value -> "capture-request".equals(Json.string(value, "type", ""))));
        } finally { SwingUtilities.invokeAndWait(app[0]::close); }
    }
    @Test void hostCaptureRejectionImmediatelyCancelsHandshakeAndLateReadyIsIgnored() throws Exception {
        Files.writeString(root.resolve("chi_sim.traineddata"), "not used"); Files.writeString(root.resolve("eng.traineddata"), "not used");
        List<JsonObject> messages = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            CaptureApp app = new CaptureApp(root, root, value -> messages.add(value.deepCopy()));
            try {
                JsonObject init = Json.message("init"); init.addProperty("protocol", 1); init.addProperty("scope", scope); init.addProperty("context_id", "run:1"); app.message(init);
                for (String rejectionType : List.of("capture-rejected", "rejected")) {
                    app.message(Json.message("capture"));
                    JsonObject request = messages.stream().filter(value -> "capture-request".equals(Json.string(value, "type", ""))).reduce((first, last) -> last).orElseThrow();
                    String id = request.get("id").getAsString(); JsonObject reject = Json.message(rejectionType); reject.addProperty("id", id); reject.addProperty("message", "先完成主程序确认"); app.message(reject);
                    assertTrue(messages.stream().anyMatch(value -> "capture-finished".equals(Json.string(value, "type", "")) && id.equals(Json.string(value, "id", "")) && Json.bool(value, "cancelled")));
                    JsonObject late = Json.message("capture-ready"); late.addProperty("id", id); app.message(late);
                }
                assertEquals(2, messages.stream().filter(value -> "CAPTURE_REJECTED".equals(Json.string(value, "code", ""))).count());
            } finally { app.close(); }
        });
    }
}
