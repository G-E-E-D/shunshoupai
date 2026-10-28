package com.shunshoupai.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** A reviewed batch and its immutable receipts commit together, entirely on this phone. */
final class LocalDraftCommitter {
    static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_draft_commits(scope TEXT NOT NULL,draft_id TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(scope,draft_id))");
    }

    static JSONArray commit(LocalScheduleStore owner, String inputScope, JSONArray drafts) throws JSONException {
        synchronized (owner) {
            String scope = owner.canonicalScope(inputScope);
            if (!owner.primaryScope().equals(scope)) throw invalid("READ_ONLY_ARCHIVE", "请先将旧资料导入本机主日程，再保存新安排。");
            if (drafts.length() < 1 || drafts.length() > 20) throw invalid("VALIDATION_ERROR", "每次请选择 1–20 项日程。");
            List<String> ids = new ArrayList<>(); List<JSONObject> payloads = new ArrayList<>(); Set<String> unique = new HashSet<>();
            for (int i = 0; i < drafts.length(); i++) {
                JSONObject draft = drafts.getJSONObject(i);
                Set<String> fields = new HashSet<>(java.util.Arrays.asList("id", "title", "start_local", "end_local", "timezone", "status", "deleted"));
                Iterator<String> keys = draft.keys();
                while (keys.hasNext()) if (!fields.remove(keys.next())) throw invalid("VALIDATION_ERROR", "日程包含无效字段。");
                if (!fields.isEmpty() || !(draft.get("id") instanceof String)) throw invalid("VALIDATION_ERROR", "日程缺少必要字段。");
                String id = AncestryReplica.uuid(draft.getString("id"));
                if (!unique.add(id)) throw invalid("ID_CONFLICT", "批量草稿中有重复编号，请重新整理后确认。");
                JSONObject copy = new JSONObject(draft.toString()); copy.remove("id");
                JSONObject payload = AncestryReplica.payload(copy);
                if (!"planned".equals(payload.getString("status")) || payload.getBoolean("deleted")) throw invalid("VALIDATION_ERROR", "新日程必须为待进行状态。");
                ids.add(id); payloads.add(payload);
            }
            SQLiteDatabase db = owner.getWritableDatabase(); db.beginTransaction();
            try {
                List<JSONObject> occupied = new ArrayList<>();
                try (Cursor rows = db.rawQuery("SELECT payload FROM local_events WHERE scope=?", new String[]{scope})) {
                    while (rows.moveToNext()) { JSONObject payload = new JSONObject(rows.getString(0)); if (!payload.optBoolean("deleted") && "planned".equals(payload.optString("status"))) occupied.add(payload); }
                }
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i), fingerprint = fingerprint(payloads.get(i));
                    boolean replay = false;
                    try (Cursor receipt = db.rawQuery("SELECT payload_hash FROM local_draft_commits WHERE scope=? AND draft_id=?", new String[]{scope, id})) {
                        if (receipt.moveToFirst()) {
                            if (!fingerprint.equals(receipt.getString(0))) throw invalid("IDEMPOTENCY_CONFLICT", "这份草稿已经保存过，内容已改变。请重新整理为新草稿。");
                            replay = true;
                        }
                    }
                    if (replay) continue; // A retry never restores a later edit/deletion to the original draft.
                    try (Cursor existing = db.rawQuery("SELECT 1 FROM local_events WHERE scope=? AND id=?", new String[]{scope, id})) {
                        if (existing.moveToFirst()) throw invalid("ID_CONFLICT", "这项日程编号已经存在，没有覆盖原内容。");
                    }
                    JSONObject payload = payloads.get(i);
                    for (JSONObject busy : occupied) {
                        if (payload.getString("start_local").compareTo(busy.getString("end_local")) < 0 && payload.getString("end_local").compareTo(busy.getString("start_local")) > 0)
                            throw invalid("TIME_CONFLICT", "与「" + busy.optString("title", "已有日程") + "」时间重叠，请调整草稿后再保存。");
                    }
                    owner.replica().save(scope, id, payload, null);
                    db.execSQL("INSERT INTO local_draft_commits(scope,draft_id,payload_hash) VALUES(?,?,?)", new Object[]{scope, id, fingerprint});
                    occupied.add(payload);
                }
                db.setTransactionSuccessful(); return new JSONArray(ids);
            } finally { db.endTransaction(); }
        }
    }
    private static String fingerprint(JSONObject payload) throws JSONException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(AncestryReplica.payloadKey(payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(); for (byte item : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static AncestryReplica.Invalid invalid(String code, String message) { return new AncestryReplica.Invalid(code, message); }
}
