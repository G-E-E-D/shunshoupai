package com.shunshoupai.capture;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InboxTest {
    @TempDir Path root;
    final String scope = "a".repeat(64);
    final ProtectedInbox.Protection reversible = new ProtectedInbox.Protection() {
        public byte[] protect(byte[] bytes, byte[] entropy) { byte[] result = bytes.clone(); for (int i = 0; i < result.length; i++) result[i] ^= 0x55; return result; }
        public byte[] unprotect(byte[] bytes, byte[] entropy) { return protect(bytes, entropy); }
    };
    @Test void longTextAndUnacknowledgedPayloadRestoreWithoutTruncating() throws Exception {
        ProtectedInbox inbox = new ProtectedInbox(root, scope, reversible); var entry = inbox.add("中文\n".repeat(1000));
        JsonObject payload = Json.message("recognized-text"); payload.addProperty("id", entry.id); payload.addProperty("text", "已确认的原文");
        entry.sent = payload; inbox.save(); ProtectedInbox restored = new ProtectedInbox(root, scope, reversible);
        assertEquals(entry.text, restored.first().text); assertEquals(payload, restored.first().sent);
        assertFalse(new String(Files.readAllBytes(root.resolve(scope + ".bin")), StandardCharsets.UTF_8).contains("中文"));
        restored.remove(entry.id); assertNull(new ProtectedInbox(root, scope, reversible).first());
    }
    @Test void scopesNeverShareInboxAndCapacityNeverOverwrites() throws Exception {
        ProtectedInbox inbox = new ProtectedInbox(root, scope, reversible);
        for (int i = 0; i < 20; i++) inbox.add("保留 " + i);
        assertThrows(java.io.IOException.class, () -> inbox.add("不能覆盖")); assertEquals("保留 0", inbox.first().text);
        assertNull(new ProtectedInbox(root, "b".repeat(64), reversible).first());
    }
    @Test void corruptedEncryptedFileIsNotReplacedOrDeleted() throws Exception {
        Path file = root.resolve(scope + ".bin"); Files.writeString(file, "corrupt");
        assertThrows(java.io.IOException.class, () -> new ProtectedInbox(root, scope, reversible)); assertEquals("corrupt", Files.readString(file));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void realWindowsDpapiRoundTripAndWrongEntropyRejected() throws Exception {
        ProtectedInbox inbox = new ProtectedInbox(root, scope); inbox.add("实际 DPAPI 中文草稿");
        assertEquals("实际 DPAPI 中文草稿", new ProtectedInbox(root, scope).first().text);
        Files.copy(root.resolve(scope + ".bin"), root.resolve("b".repeat(64) + ".bin"));
        assertThrows(java.io.IOException.class, () -> new ProtectedInbox(root, "b".repeat(64)));
    }
}
