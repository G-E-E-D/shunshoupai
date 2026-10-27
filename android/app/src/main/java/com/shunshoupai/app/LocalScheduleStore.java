package com.shunshoupai.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local desired state and an immutable, retryable outbox are committed together. */
final class LocalScheduleStore extends SQLiteOpenHelper {
    static final String GUEST = "guest";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    LocalScheduleStore(Context context) { super(context, "schedule-offline.sqlite", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_events(scope TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 1,conflict TEXT,message TEXT,expected_base INTEGER,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE outbox(scope TEXT NOT NULL,id TEXT NOT NULL,mutation TEXT NOT NULL,desired TEXT NOT NULL,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE bindings(origin TEXT PRIMARY KEY,scope TEXT NOT NULL,email TEXT NOT NULL)");
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2)db.execSQL("ALTER TABLE local_events ADD COLUMN expected_base INTEGER");
    }

    synchronized String cachedScope(String origin) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT scope FROM bindings WHERE origin=?", new String[]{origin})) {
            if(row.moveToFirst())return row.getString(0);
        }
        try(Cursor owner=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE key='guest_owner'",null)) {
            return owner.moveToFirst()?"local:"+origin:GUEST;
        }
    }

    synchronized String accountLabel(String scope) {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT email FROM bindings WHERE scope=? LIMIT 1",new String[]{scope})) {
            return row.moveToFirst()?"手机离线副本 · "+row.getString(0):"手机日程 · 无需登录即可使用";
        }
    }

    synchronized String bind(String origin, JSONObject user) throws JSONException {
        String scope = origin + "|" + user.getString("id");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try(Cursor previous=db.rawQuery("SELECT scope FROM bindings WHERE origin=?",new String[]{origin})) {
                if(!previous.moveToFirst())db.execSQL("UPDATE local_events SET scope=? WHERE scope=?",new Object[]{scope,"local:"+origin});
            }
            // Guest data belongs only to the first authenticated identity. Never
            // move a previous account's drafts after a sign-in/server change.
            try (Cursor row = db.rawQuery("SELECT value FROM metadata WHERE key='guest_owner'", null)) {
                if (!row.moveToFirst()) {
                    db.execSQL("UPDATE local_events SET scope=? WHERE scope=?", new Object[]{scope, GUEST});
                    db.execSQL("INSERT INTO metadata(key,value) VALUES('guest_owner',?)", new Object[]{scope});
                }
            }
            db.execSQL("INSERT OR REPLACE INTO bindings(origin,scope,email) VALUES(?,?,?)", new Object[]{origin, scope, user.optString("email")});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return scope;
    }

    synchronized List<JSONObject> list(String scope) throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id,payload,version,dirty,conflict,message,COALESCE(expected_base,version) FROM local_events WHERE scope=?", new String[]{scope})) {
            while (rows.moveToNext()) {
                JSONObject item = new JSONObject(rows.getString(1));
                item.put("id", rows.getString(0));
                item.put("version", rows.getInt(2));
                item.put("base_version",rows.getInt(6));
                item.put("dirty", rows.getInt(3) != 0);
                if (!rows.isNull(4)) item.put("conflict", new JSONObject(rows.getString(4)));
                if (!rows.isNull(5)) item.put("conflict_message", rows.getString(5));
                result.add(item);
            }
        }
        result.sort((a, b) -> a.optString("start_local").compareTo(b.optString("start_local")));
        return result;
    }

    static JSONObject payload(String title, LocalDateTime start, LocalDateTime end, String status, boolean deleted) throws JSONException {
        return new JSONObject().put("title", title).put("start_local", start.format(LOCAL)).put("end_local", end.format(LOCAL))
            .put("timezone", "Asia/Shanghai").put("status", status).put("deleted", deleted);
    }

    static JSONObject desired(JSONObject item) throws JSONException {
        return new JSONObject().put("title", item.getString("title")).put("start_local", item.getString("start_local"))
            .put("end_local", item.getString("end_local")).put("timezone", "Asia/Shanghai")
            .put("status", item.getString("status")).put("deleted", item.optBoolean("deleted"));
    }

    synchronized String save(String scope, String id, JSONObject payload) {
        return save(scope,id,payload,null);
    }

    synchronized String save(String scope,String id,JSONObject payload,Integer expectedBase) {
        scope=canonicalScope(scope);
        if (id == null) id = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("payload", payload.toString()); values.put("dirty", 1);
        SQLiteDatabase db = getWritableDatabase();
        if(expectedBase!=null) {
            try(Cursor existing=db.rawQuery("SELECT expected_base FROM local_events WHERE scope=? AND id=?",new String[]{scope,id})) {
                if(existing.moveToFirst()&&!existing.isNull(0))expectedBase=Math.min(expectedBase,existing.getInt(0));
            }
            values.put("expected_base",expectedBase);
        }
        if (db.update("local_events", values, "scope=? AND id=?", new String[]{scope,id}) == 0) {
            values.put("scope",scope); values.put("id",id);
            db.insertOrThrow("local_events", null, values);
        }
        return id;
    }

    synchronized String canonicalScope(String scope) {
        // An editor may have opened just before the first identity was bound.
        if(GUEST.equals(scope)) {
            try(Cursor owner=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE key='guest_owner'",null)) {
                if(owner.moveToFirst())scope=owner.getString(0);
            }
        }
        else if(scope.startsWith("local:")) {
            try(Cursor binding=getReadableDatabase().rawQuery("SELECT scope FROM bindings WHERE origin=?",new String[]{scope.substring(6)})) {
                if(binding.moveToFirst())scope=binding.getString(0);
            }
        }
        return scope;
    }

    synchronized JSONArray mutations(String scope) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor rows = db.rawQuery("SELECT id,payload,COALESCE(expected_base,version),version FROM local_events e WHERE scope=? AND dirty=1 AND conflict IS NULL AND NOT EXISTS(SELECT 1 FROM outbox o WHERE o.scope=e.scope AND o.id=e.id) LIMIT 20", new String[]{scope})) {
                while (rows.moveToNext()) {
                    JSONObject wanted = new JSONObject(rows.getString(1));
                    // A never-uploaded local deletion needs no server tombstone.
                    if (wanted.optBoolean("deleted") && rows.getInt(3) == 0) {
                        db.delete("local_events", "scope=? AND id=?", new String[]{scope, rows.getString(0)});
                        continue;
                    }
                    JSONObject mutation = new JSONObject().put("mutation_id", UUID.randomUUID().toString()).put("id", rows.getString(0))
                        .put("base_version", rows.getInt(2)).put("deleted", wanted.optBoolean("deleted"));
                    if (!wanted.optBoolean("deleted")) { JSONObject event = new JSONObject(wanted.toString()); event.remove("deleted"); mutation.put("event", event); }
                    db.execSQL("INSERT INTO outbox(scope,id,mutation,desired) VALUES(?,?,?,?)", new Object[]{scope,rows.getString(0),mutation.toString(),wanted.toString()});
                }
            }
            JSONArray result = new JSONArray();
            try (Cursor rows = db.rawQuery("SELECT mutation FROM outbox WHERE scope=? ORDER BY id LIMIT 20", new String[]{scope})) {
                while (rows.moveToNext()) result.put(new JSONObject(rows.getString(0)));
            }
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }

    private static JSONObject fromRemote(JSONObject event) throws JSONException {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.parse(event.getString("start_at")), ZONE);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.parse(event.getString("end_at")), ZONE);
        return payload(event.getString("title"), start, end, event.getString("status"), !event.isNull("deleted_at"));
    }

    synchronized void apply(String scope, JSONObject response) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray results = response.getJSONArray("results");
            for (int i=0;i<results.length();i++) {
                JSONObject result = results.getJSONObject(i);
                try (Cursor rows = db.rawQuery("SELECT o.id,o.mutation,o.desired,e.payload,e.version,e.expected_base FROM outbox o JOIN local_events e ON e.scope=o.scope AND e.id=o.id WHERE o.scope=?", new String[]{scope})) {
                    while (rows.moveToNext()) {
                        if (!new JSONObject(rows.getString(1)).getString("mutation_id").equals(result.getString("mutation_id"))) continue;
                        String id=rows.getString(0); JSONObject remote=result.optJSONObject("event");
                        if ("applied".equals(result.getString("status")) && remote != null) {
                            boolean unchanged = rows.getString(2).equals(rows.getString(3));
                            ContentValues values = new ContentValues(); values.put("version",Math.max(rows.getInt(4),remote.getInt("version")));
                            if (unchanged && remote.getInt("version")>=rows.getInt(4)) { values.put("payload",fromRemote(remote).toString()); values.put("dirty",0); values.putNull("expected_base"); }
                            else if(!rows.isNull(5)&&rows.getInt(5)==new JSONObject(rows.getString(1)).getInt("base_version"))values.put("expected_base",remote.getInt("version"));
                            db.update("local_events",values,"scope=? AND id=?",new String[]{scope,id});
                        } else if ("conflict".equals(result.getString("status"))) {
                            ContentValues values=new ContentValues(); values.put("conflict",remote == null ? "{}" : remote.toString());
                            values.put("message",result.optString("message","两台设备修改了同一日程，请选择保留方式。"));
                            db.update("local_events",values,"scope=? AND id=?",new String[]{scope,id});
                        } else continue;
                        db.delete("outbox","scope=? AND id=?",new String[]{scope,id});
                        break;
                    }
                }
            }
            JSONArray events=response.getJSONArray("events");
            for(int i=0;i<events.length();i++) {
                JSONObject remote=events.getJSONObject(i); String id=remote.getString("id");
                try(Cursor row=db.rawQuery("SELECT dirty,version FROM local_events WHERE scope=? AND id=?",new String[]{scope,id})) {
                    if(row.moveToFirst() && (row.getInt(0)!=0 || row.getInt(1)>=remote.getInt("version"))) continue;
                }
                ContentValues values=new ContentValues(); values.put("scope",scope); values.put("id",id); values.put("payload",fromRemote(remote).toString());
                values.put("version",remote.getInt("version")); values.put("dirty",0);
                db.insertWithOnConflict("local_events",null,values,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    synchronized void resolve(String scope, JSONObject local, boolean keepPhone) throws JSONException {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            JSONObject remote=local.getJSONObject("conflict"); String id=local.getString("id");
            db.delete("local_events","scope=? AND id=?",new String[]{scope,id});
            if(remote.has("id")) {
                ContentValues values=new ContentValues(); values.put("scope",scope); values.put("id",id); values.put("payload",fromRemote(remote).toString());
                values.put("version",remote.getInt("version")); values.put("dirty",0); db.insertOrThrow("local_events",null,values);
            }
            if(keepPhone) {
                JSONObject wanted=desired(local);
                if(wanted.optBoolean("deleted") && remote.has("id") && remote.isNull("deleted_at")) save(scope,id,wanted);
                else if(!wanted.optBoolean("deleted")) {
                    String title=wanted.getString("title");
                    wanted.put("title",title.substring(0,Math.min(title.length(),110))+"（手机副本）");
                    save(scope,null,wanted);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
