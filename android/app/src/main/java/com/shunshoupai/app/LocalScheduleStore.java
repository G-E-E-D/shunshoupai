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
    private final Context context;
    private final PersonalReplicaStore replica = new PersonalReplicaStore(this);

    LocalScheduleStore(Context context) { this(context,"schedule-offline.sqlite"); }
    LocalScheduleStore(Context context,String databaseName) { super(context, databaseName, null, 5); this.context=context.getApplicationContext(); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_events(scope TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 1,conflict TEXT,message TEXT,expected_base INTEGER,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE outbox(scope TEXT NOT NULL,id TEXT NOT NULL,mutation TEXT NOT NULL,desired TEXT NOT NULL,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE bindings(origin TEXT PRIMARY KEY,scope TEXT NOT NULL,email TEXT NOT NULL)");
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        createDeviceSchema(db);
        createReplicaSchema(db);
        LocalDraftCommitter.createSchema(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2)db.execSQL("ALTER TABLE local_events ADD COLUMN expected_base INTEGER");
        if(oldVersion<3)createDeviceSchema(db);
        if(oldVersion<4)createReplicaSchema(db);
        if(oldVersion<5)LocalDraftCommitter.createSchema(db);
    }

    private void createReplicaSchema(SQLiteDatabase db){
        PersonalReplicaStore.createSchema(db);
        db.execSQL("ALTER TABLE device_peers ADD COLUMN capabilities TEXT NOT NULL DEFAULT '{}'");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_incoming(pairing_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,name TEXT NOT NULL,platform TEXT NOT NULL,token_hash TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0)");
    }

    private void createDeviceSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS spaces(scope TEXT PRIMARY KEY,space_id TEXT NOT NULL,peer_id TEXT,label TEXT NOT NULL,legacy_id TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS scope_aliases(alias TEXT PRIMARY KEY,scope TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_peers(peer_id TEXT PRIMARY KEY,space_id TEXT NOT NULL,origin TEXT NOT NULL,name TEXT NOT NULL,pairing_id TEXT NOT NULL,public_key TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1)");
        String deviceId = UUID.randomUUID().toString();
        String localId = UUID.randomUUID().toString();
        String localScope = "local:" + localId;
        db.execSQL("INSERT OR IGNORE INTO metadata(key,value) VALUES('device_id',?),('local_scope',?),('active_scope',?)",new Object[]{deviceId,localScope,localScope});
        db.execSQL("INSERT OR IGNORE INTO spaces(scope,space_id,label) VALUES(?,?,?)",new Object[]{localScope,localId,"本机日程"});
        List<String> oldScopes = new ArrayList<>();
        try(Cursor rows=db.rawQuery("SELECT DISTINCT scope FROM local_events UNION SELECT DISTINCT scope FROM outbox UNION SELECT DISTINCT scope FROM bindings",null)) {
            while(rows.moveToNext())oldScopes.add(rows.getString(0));
        }
        for(String key:context.getSharedPreferences("offline-editor",Context.MODE_PRIVATE).getAll().keySet()) {
            int separator=key.lastIndexOf('|');
            if(separator<0)continue;
            String suffix=key.substring(separator+1),old=key.substring(0,separator);
            boolean editor="new".equals(suffix);
            if(!editor)try{PeerIdentity.uuid(suffix);editor=true;}catch(IllegalArgumentException ignored){}
            if(editor&&!oldScopes.contains(old))oldScopes.add(old);
        }
        for(String old:oldScopes) {
            String next=GUEST.equals(old)?localScope:"legacy:"+UUID.randomUUID();
            String legacyId=null;
            int separator=old.lastIndexOf('|');
            if(separator>=0) {try{legacyId=PeerIdentity.uuid(old.substring(separator+1));}catch(IllegalArgumentException ignored){}}
            if(!next.equals(localScope))db.execSQL("INSERT INTO spaces(scope,space_id,label,legacy_id) VALUES(?,?,?,?)",new Object[]{next,legacyId==null?UUID.randomUUID().toString():legacyId,"原有日程",legacyId});
            db.execSQL("UPDATE local_events SET scope=? WHERE scope=?",new Object[]{next,old});
            db.execSQL("UPDATE outbox SET scope=? WHERE scope=?",new Object[]{next,old});
            db.execSQL("INSERT OR REPLACE INTO scope_aliases(alias,scope) VALUES(?,?)",new Object[]{old,next});
        }
        db.execSQL("INSERT OR IGNORE INTO scope_aliases(alias,scope) VALUES(?,?)",new Object[]{GUEST,localScope});
        // In v1.4 a guest editor could already belong to the first bound legacy
        // dataset. Retain that relationship instead of importing it into whichever
        // different dataset happens to be selected during this upgrade.
        try(Cursor owner=db.rawQuery("SELECT a.scope,s.space_id FROM metadata m JOIN scope_aliases a ON a.alias=m.value JOIN spaces s ON s.scope=a.scope WHERE m.key='guest_owner'",null)) {
            if(owner.moveToFirst()&&!localScope.equals(owner.getString(0))) {
                String ownerScope=owner.getString(0);
                db.execSQL("UPDATE spaces SET legacy_id=?,label='原有日程' WHERE scope=?",new Object[]{owner.getString(1),localScope});
                mergeSpace(db,localScope,ownerScope);
                db.execSQL("INSERT OR REPLACE INTO scope_aliases(alias,scope) VALUES(?,?)",new Object[]{GUEST,ownerScope});
            }
        }
    }

    private String meta(String key) {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE key=?",new String[]{key})) {
            return row.moveToFirst()?row.getString(0):null;
        }
    }

    synchronized void initialize(String previousOrigin) {
        if(meta("device_migration_initialized")!=null){primaryScope();return;}
        // Legacy account identifiers are read once only to retain the previously
        // visible local dataset. New sessions and pairing never use accounts.
        try(Cursor row=getReadableDatabase().rawQuery("SELECT scope FROM bindings WHERE origin=?",new String[]{previousOrigin})) {
            if(row.moveToFirst()) {
                String scope=canonicalScope(row.getString(0));
                try(Cursor known=getReadableDatabase().rawQuery("SELECT 1 FROM spaces WHERE scope=?",new String[]{scope})) {
                    if(known.moveToFirst())setActiveScope(scope);
                }
            }
        }
        getWritableDatabase().execSQL("INSERT INTO metadata(key,value) VALUES('device_migration_initialized','1')");
        primaryScope();
    }

    synchronized String deviceId(){return meta("device_id");}
    synchronized String deviceName(){String name=("手机 · "+android.os.Build.MODEL).replaceAll("[\\p{Cntrl}]","");return name.substring(0,Math.min(80,name.length()));}
    synchronized String primaryScope(){String primary=meta("primary_scope");if(primary==null){primary=activeScope();getWritableDatabase().execSQL("INSERT INTO metadata(key,value) VALUES('primary_scope',?)",new Object[]{primary});}return canonicalScope(primary);}
    synchronized String primarySpaceId(){try(Cursor row=getReadableDatabase().rawQuery("SELECT space_id FROM spaces WHERE scope=?",new String[]{primaryScope()})){if(row.moveToFirst())return row.getString(0);}throw new IllegalStateException("Missing local primary dataset");}
    synchronized boolean isPrimary(String scope){return meta("primary_scope")!=null&&primaryScope().equals(canonicalScope(scope));}
    PersonalReplicaStore replica(){return replica;}
    synchronized JSONArray commitDrafts(String scope, JSONArray drafts) throws JSONException { return LocalDraftCommitter.commit(this, scope, drafts); }
    synchronized int listenPort(){String port=meta("listen_port");return port==null?43127:Integer.parseInt(port);}
    synchronized void rememberListenPort(int port){getWritableDatabase().execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('listen_port',?)",new Object[]{Integer.toString(port)});}
    synchronized String activeScope(){return canonicalScope(meta("active_scope"));}
    synchronized String localScope(){return canonicalScope(meta("local_scope"));}
    synchronized void setActiveScope(String scope){getWritableDatabase().execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('active_scope',?)",new Object[]{canonicalScope(scope)});}

    synchronized List<JSONObject> spaces() throws JSONException {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT scope,label,peer_id FROM spaces ORDER BY label,scope",null)) {
            while(rows.moveToNext())result.add(new JSONObject().put("scope",rows.getString(0)).put("label",rows.getString(1)).put("peer_id",rows.isNull(2)?JSONObject.NULL:rows.getString(2)));
        }
        return result;
    }

    synchronized String spaceLabel(String scope) {
        scope=canonicalScope(scope);
        try(Cursor row=getReadableDatabase().rawQuery("SELECT label,peer_id FROM spaces WHERE scope=?",new String[]{scope})) {
            if(row.moveToFirst())return row.isNull(1)?row.getString(0)+" · 保存在手机":row.getString(0)+" · 本机日程";
        }
        return "本机日程 · 打开就能用";
    }

    synchronized JSONObject peer(String peerId) throws JSONException {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT peer_id,space_id,origin,name,pairing_id,public_key,enabled,capabilities FROM device_peers WHERE peer_id=?",new String[]{peerId})) {
            if(!row.moveToFirst())return null;
            return new JSONObject().put("peer_id",row.getString(0)).put("space_id",row.getString(1)).put("origin",row.getString(2))
                .put("name",row.getString(3)).put("pairing_id",row.getString(4)).put("public_key",row.getString(5)).put("enabled",row.getInt(6)!=0).put("capabilities",new JSONObject(row.getString(7)));
        }
    }

    synchronized List<JSONObject> peers()throws JSONException{List<JSONObject> peers=new ArrayList<>();try(Cursor rows=getReadableDatabase().rawQuery("SELECT peer_id FROM device_peers ORDER BY name,peer_id",null)){while(rows.moveToNext())peers.add(peer(rows.getString(0)));}return peers;}
    synchronized JSONObject webPeer()throws JSONException{for(JSONObject peer:peers())if(peer.optBoolean("enabled")&&peer.getJSONObject("capabilities").optBoolean("web"))return peer;return null;}
    synchronized void registerPeer(String origin,PeerIdentity identity,String pairingId,JSONObject capabilities)throws JSONException{
        JSONObject previous=peer(identity.peerId);if(previous!=null&&(!identity.publicKey.equals(previous.getString("public_key"))||!identity.spaceId.equals(previous.getString("space_id"))))throw new SecurityException("设备身份与已保存配对不一致。");
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO device_peers(peer_id,space_id,origin,name,pairing_id,public_key,enabled,capabilities) VALUES(?,?,?,?,?,?,1,?)",new Object[]{identity.peerId,identity.spaceId,origin,identity.name,pairingId,identity.publicKey,capabilities.toString()});
    }

    synchronized JSONObject activePeer() throws JSONException {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT peer_id FROM spaces WHERE scope=?",new String[]{activeScope()})) {
            return row.moveToFirst()&&!row.isNull(0)?peer(row.getString(0)):null;
        }
    }

    static String peerScope(String peerId,String spaceId){return "peer:"+peerId+":"+spaceId;}

    synchronized String bindPeer(String origin,PeerIdentity identity,String pairingId,JSONArray legacySpaces) throws JSONException {
        SQLiteDatabase db=getWritableDatabase();
        String target=peerScope(identity.peerId,identity.spaceId);
        db.beginTransaction();
        try {
            JSONObject previous=peer(identity.peerId);
            if(previous!=null && (!identity.publicKey.equals(previous.getString("public_key"))||!identity.spaceId.equals(previous.getString("space_id"))))throw new SecurityException("设备身份发生变化，原日程保持独立保存。");
            db.execSQL("INSERT OR IGNORE INTO spaces(scope,space_id,peer_id,label) VALUES(?,?,?,?)",new Object[]{target,identity.spaceId,identity.peerId,identity.name});
            db.execSQL("UPDATE spaces SET label=? WHERE scope=?",new Object[]{identity.name,target});
            List<String> imports=new ArrayList<>();
            if(meta("first_device_paired")==null) {
                String local=localScope();
                try(Cursor unassigned=db.rawQuery("SELECT 1 FROM spaces WHERE scope=? AND legacy_id IS NULL AND peer_id IS NULL",new String[]{local})) {
                    if(unassigned.moveToFirst())imports.add(local);
                }
            }
            for(int i=0;i<legacySpaces.length();i++) {
                String legacy=legacySpaces.getString(i);
                if(!identity.spaceId.equals(legacy))throw new SecurityException("旧日程迁移范围与当前设备不一致。");
                try(Cursor rows=db.rawQuery("SELECT scope FROM spaces WHERE legacy_id=? AND peer_id IS NULL AND scope=?",new String[]{legacy,activeScope()})) {
                    if(rows.moveToFirst()&&!imports.contains(rows.getString(0)))imports.add(rows.getString(0));
                }
            }
            for(String source:imports)mergeSpace(db,source,target);
            db.execSQL("INSERT OR REPLACE INTO device_peers(peer_id,space_id,origin,name,pairing_id,public_key,enabled) VALUES(?,?,?,?,?,?,1)",new Object[]{identity.peerId,identity.spaceId,origin,identity.name,pairingId,identity.publicKey});
            db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('first_device_paired','1'),('active_scope',?)",new Object[]{target});
            db.setTransactionSuccessful();return target;
        } finally {db.endTransaction();}
    }

    private void mergeSpace(SQLiteDatabase db,String source,String target) {
        if(source.equals(target))return;
        List<String> ids=new ArrayList<>();
        try(Cursor rows=db.rawQuery("SELECT id FROM local_events WHERE scope=?",new String[]{source})) {while(rows.moveToNext())ids.add(rows.getString(0));}
        for(String id:ids) {
            boolean duplicate;
            try(Cursor row=db.rawQuery("SELECT 1 FROM local_events WHERE scope=? AND id=?",new String[]{target,id})){duplicate=row.moveToFirst();}
            if(duplicate)continue; // Ambiguous legacy copies stay visible in their separate local dataset.
            db.execSQL("UPDATE local_events SET scope=? WHERE scope=? AND id=?",new Object[]{target,source,id});
            db.execSQL("UPDATE outbox SET scope=? WHERE scope=? AND id=?",new Object[]{target,source,id});
        }
        try(Cursor left=db.rawQuery("SELECT 1 FROM local_events WHERE scope=? LIMIT 1",new String[]{source})) {
            if(!left.moveToFirst()) {
                db.execSQL("INSERT OR REPLACE INTO scope_aliases(alias,scope) VALUES(?,?)",new Object[]{source,target});
                db.delete("spaces","scope=?",new String[]{source});
            }
        }
    }

    synchronized void unpair(String peerId){getWritableDatabase().execSQL("UPDATE device_peers SET enabled=0 WHERE peer_id=?",new Object[]{peerId});}

    synchronized List<JSONObject> list(String scope) throws JSONException {
        scope=canonicalScope(scope);
        if(isPrimary(scope))replica.ensure(scope);
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
                if(isPrimary(scope)){JSONArray conflicts=replica.conflicts(scope,rows.getString(0));if(conflicts.length()>1)item.put("replica_conflicts",conflicts);}
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
        if(isPrimary(scope))try{return replica.save(scope,id,payload,expectedBase);}catch(JSONException error){throw new IllegalArgumentException("无法保存日程版本。",error);}
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
        if (scope == null) scope = meta("local_scope");
        for (int hop = 0; hop < 16; hop++) {
            try (Cursor row = getReadableDatabase().rawQuery("SELECT scope FROM scope_aliases WHERE alias=?", new String[]{scope})) {
                if (!row.moveToFirst() || scope.equals(row.getString(0))) return scope;
                scope = row.getString(0);
            }
        }
        throw new IllegalStateException("Local dataset aliases contain a cycle");
    }

    synchronized List<String> draftScopes(String scope) {
        String target = canonicalScope(scope);
        List<String> result = new ArrayList<>(); result.add(target);
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT alias FROM scope_aliases", null)) {
            while (rows.moveToNext()) if (target.equals(canonicalScope(rows.getString(0)))) result.add(rows.getString(0));
        }
        return result;
    }
    synchronized JSONArray mutations(String scope) throws JSONException {
        scope=canonicalScope(scope);
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

    static JSONObject fromRemote(JSONObject event) throws JSONException {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.parse(event.getString("start_at")), ZONE);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.parse(event.getString("end_at")), ZONE);
        return payload(event.getString("title"), start, end, event.getString("status"), !event.isNull("deleted_at"));
    }

    synchronized void apply(String scope, JSONObject response) throws JSONException {
        scope=canonicalScope(scope);
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
        scope=canonicalScope(scope);
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
