package com.shunshoupai.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ProtectedInbox {
    interface Protection { byte[] protect(byte[] value, byte[] entropy); byte[] unprotect(byte[] value, byte[] entropy); }
    static final Protection DPAPI = new Protection() {
        public byte[] protect(byte[] value, byte[] entropy) { return Crypt32Util.cryptProtectData(value, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "Shunshoupai OCR", null); }
        public byte[] unprotect(byte[] value, byte[] entropy) { return Crypt32Util.cryptUnprotectData(value, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null); }
    };
    static final class Entry {
        final String id;
        String text;
        JsonObject sent;
        Entry(String id, String text) { this.id = id; this.text = text; }
    }
    private final Path path;
    private final String scope;
    private final Protection protection;
    private final List<Entry> entries = new ArrayList<>();
    ProtectedInbox(Path root, String scope) throws IOException { this(root, scope, DPAPI); }
    ProtectedInbox(Path root, String scope, Protection protection) throws IOException {
        if (!scope.matches("[a-f0-9]{64}")) throw new IOException("本机日程尚未准备好。");
        this.scope = scope; this.path = root.resolve(scope + ".bin"); this.protection = protection;
        if (!Files.exists(path)) return;
        if (Files.size(path) > 8_000_000) throw new IOException("截图文字暂存文件异常，已保留原文件。");
        byte[] plaintext = null;
        try {
            plaintext = protection.unprotect(Files.readAllBytes(path), scope.getBytes(StandardCharsets.UTF_8));
            JsonObject data = Json.object(new String(plaintext, StandardCharsets.UTF_8));
            if (!scope.equals(Json.string(data, "scope", ""))) throw new IOException("截图文字暂存归属不匹配。");
            for (JsonElement item : data.getAsJsonArray("entries")) {
                JsonObject row = item.getAsJsonObject();
                Entry entry = new Entry(row.get("id").getAsString(), row.get("text").getAsString());
                UUID.fromString(entry.id);
                if (row.has("sent")) entry.sent = row.getAsJsonObject("sent");
                entries.add(entry);
            }
            if (entries.size() > 20) throw new IOException("截图文字暂存文件异常。");
        } catch (RuntimeException error) { throw new IOException("无法读取之前的截图文字，原文件已保留。", error); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }
    List<Entry> entries() { return List.copyOf(entries); }
    Entry first() { return entries.isEmpty() ? null : entries.get(0); }
    Entry add(String text) throws IOException {
        if (entries.size() >= 20) throw new IOException("请先处理已保留的截图文字，再开始新截图。");
        Entry entry = new Entry(UUID.randomUUID().toString(), text); entries.add(entry);
        try { save(); } catch (IOException error) { entries.remove(entry); throw error; }
        return entry;
    }
    void remove(String id) throws IOException {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) { index = i; break; }
        if (index < 0) return;
        Entry entry = entries.remove(index);
        try { save(); } catch (IOException error) { entries.add(index, entry); throw error; }
    }
    void save() throws IOException {
        JsonObject data = new JsonObject(); data.addProperty("scope", scope); JsonArray rows = new JsonArray();
        for (Entry entry : entries) {
            JsonObject row = new JsonObject(); row.addProperty("id", entry.id); row.addProperty("text", entry.text);
            if (entry.sent != null) row.add("sent", entry.sent); rows.add(row);
        }
        data.add("entries", rows); byte[] plaintext = Json.GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        Path temporary = null;
        try {
            byte[] encrypted = protection.protect(plaintext, scope.getBytes(StandardCharsets.UTF_8));
            if (encrypted.length == 0) throw new IOException("无法安全保存截图文字。");
            Files.createDirectories(path.getParent()); temporary = Files.createTempFile(path.getParent(), ".capture-", ".bin");
            Files.write(temporary, encrypted); Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException error) { throw new IOException("无法安全保存截图文字，请检查当前 Windows 用户环境。", error); }
        finally { Arrays.fill(plaintext, (byte) 0); if (temporary != null) Files.deleteIfExists(temporary); }
    }
}
