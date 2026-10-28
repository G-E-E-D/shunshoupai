package com.shunshoupai.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Per-device causal calendar. Peer space IDs never choose the local storage namespace. */
final class PersonalReplicaStore {
    static final int PAGE_RECORDS=50,PAGE_BYTES=2*1024*1024,RECORD_BYTES=8*1024*1024;
    private final LocalScheduleStore owner;
    PersonalReplicaStore(LocalScheduleStore owner){this.owner=owner;}

    static void createSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS replica_records(scope TEXT NOT NULL,id TEXT NOT NULL,record BLOB NOT NULL,preferred TEXT,PRIMARY KEY(scope,id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS replica_versions(scope TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,record BLOB NOT NULL,PRIMARY KEY(scope,id,version))");
        db.execSQL("CREATE TABLE IF NOT EXISTS replica_seen(revision_id TEXT PRIMARY KEY,id TEXT NOT NULL,fingerprint TEXT NOT NULL,head BLOB NOT NULL,ancestor_count INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS replica_seen_event ON replica_seen(id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS replica_ancestor_claims(revision_id TEXT PRIMARY KEY,id TEXT NOT NULL)");
    }
    private static byte[] encode(JSONObject value){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)){gzip.write(value.toString().getBytes(StandardCharsets.UTF_8));}return bytes.toByteArray();}catch(Exception error){throw new IllegalStateException("无法保存本机版本",error);}}
    private static JSONObject decode(byte[] bytes){try{ByteArrayOutputStream decoded=new ByteArrayOutputStream();try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(bytes))){byte[] buffer=new byte[8192];int count;while((count=gzip.read(buffer))!=-1){if(decoded.size()+count>16*1024*1024)throw new IllegalStateException("版本记录过大");decoded.write(buffer,0,count);}}return new JSONObject(decoded.toString(StandardCharsets.UTF_8.name()));}catch(Exception error){throw new IllegalStateException("无法读取本机版本",error);}}
    private static String fingerprint(JSONObject value){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}catch(Exception error){throw new IllegalStateException(error);}}
    private JSONObject read(SQLiteDatabase db,String scope,String id){try(Cursor row=db.rawQuery("SELECT record FROM replica_records WHERE scope=? AND id=?",new String[]{scope,id})){return row.moveToFirst()?decode(row.getBlob(0)):null;}}

    void ensure(String scope)throws JSONException{
        synchronized(owner){SQLiteDatabase db=owner.getWritableDatabase();db.beginTransaction();try{
            List<String> ids=new ArrayList<>();try(Cursor rows=db.rawQuery("SELECT id FROM local_events e WHERE scope=? AND NOT EXISTS(SELECT 1 FROM replica_records r WHERE r.scope=e.scope AND r.id=e.id)",new String[]{scope})){while(rows.moveToNext())ids.add(rows.getString(0));}
            for(String id:ids){try(Cursor row=db.rawQuery("SELECT payload,version,conflict FROM local_events WHERE scope=? AND id=?",new String[]{scope,id})){
                if(!row.moveToFirst())continue;JSONObject payload=AncestryReplica.payload(new JSONObject(row.getString(0)));JSONArray heads=new JSONArray();
                heads.put(AncestryReplica.createRevision(new JSONArray(),UUID.randomUUID().toString(),payload,owner.deviceName(),System.currentTimeMillis()));
                if(!row.isNull(2)){JSONObject remote=new JSONObject(row.getString(2));if(id.equals(remote.optString("id")))heads.put(AncestryReplica.createRevision(new JSONArray(),UUID.randomUUID().toString(),LocalScheduleStore.fromRemote(remote),"旧设备版本",System.currentTimeMillis()));}
                JSONObject record=AncestryReplica.record(new JSONObject().put("id",id).put("heads",heads));
                remember(db,scope,record);persist(db,scope,record,heads.getJSONObject(0).getString("revision_id"),row.getInt(1));
            }}db.setTransactionSuccessful();}finally{db.endTransaction();}}
    }

    private void remember(SQLiteDatabase db,String scope,JSONObject record)throws JSONException{
        String id=record.getString("id");JSONArray heads=record.getJSONArray("heads");for(int i=0;i<heads.length();i++){
            JSONObject head=AncestryReplica.head(heads.getJSONObject(i));String revision=head.getString("revision_id"),hash=fingerprint(head);
            boolean previouslyClaimed;
            try(Cursor claim=db.rawQuery("SELECT 1 FROM replica_ancestor_claims WHERE revision_id=?",new String[]{revision})){previouslyClaimed=claim.moveToFirst();}
            claimAncestors(db,id,head);
            try(Cursor old=db.rawQuery("SELECT id,fingerprint FROM replica_seen WHERE revision_id=?",new String[]{revision})){
                if(old.moveToFirst()) {
                    if(!id.equals(old.getString(0))||!hash.equals(old.getString(1)))throw new AncestryReplica.Invalid("REVISION_COLLISION","收到的旧版本编号被用于不同内容或不同日程，已停止合并。");
                    continue;
                }
            }
            checkHistoricalAncestors(db,id,head);
            if(previouslyClaimed)checkHistoricalDescendants(db,id,head);
            db.execSQL("INSERT OR IGNORE INTO replica_seen(revision_id,id,fingerprint,head,ancestor_count) VALUES(?,?,?,?,?)",new Object[]{revision,id,hash,encode(head),head.getJSONArray("ancestors").length()});
        }
    }

    private void claimAncestors(SQLiteDatabase db,String eventId,JSONObject head)throws JSONException{
        List<String> claims=new ArrayList<>();claims.add(head.getString("revision_id"));JSONArray ancestors=head.getJSONArray("ancestors");for(int i=0;i<ancestors.length();i++)claims.add(ancestors.getString(i));
        for(int offset=0;offset<claims.size();offset+=200){List<String> batch=claims.subList(offset,Math.min(offset+200,claims.size()));String marks=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));try(Cursor rows=db.rawQuery("SELECT id FROM replica_ancestor_claims WHERE revision_id IN ("+marks+")",batch.toArray(new String[0]))){while(rows.moveToNext())if(!eventId.equals(rows.getString(0)))throw new AncestryReplica.Invalid("REVISION_COLLISION","同一版本或祖先编号不能属于不同日程。");}}
        try(android.database.sqlite.SQLiteStatement statement=db.compileStatement("INSERT OR IGNORE INTO replica_ancestor_claims(revision_id,id) VALUES(?,?)")){for(String id:claims){statement.bindString(1,id);statement.bindString(2,eventId);statement.executeInsert();}}
    }

    private void checkHistoricalAncestors(SQLiteDatabase db,String eventId,JSONObject head)throws JSONException{
        JSONArray array=head.getJSONArray("ancestors");java.util.Set<String> all=new java.util.HashSet<>();for(int i=0;i<array.length();i++)all.add(array.getString(i));
        List<String> ids=new ArrayList<>(all);List<JSONObject> known=new ArrayList<>();
        for(int offset=0;offset<ids.size();offset+=200){List<String> batch=ids.subList(offset,Math.min(offset+200,ids.size()));String marks=String.join(",",java.util.Collections.nCopies(batch.size(),"?"));
            try(Cursor rows=db.rawQuery("SELECT id,revision_id,ancestor_count FROM replica_seen WHERE revision_id IN ("+marks+")",batch.toArray(new String[0]))){while(rows.moveToNext()){if(!eventId.equals(rows.getString(0)))throw new AncestryReplica.Invalid("REVISION_COLLISION","版本引用了另一条日程的历史。");known.add(new JSONObject().put("revision_id",rows.getString(1)).put("count",rows.getInt(2)));}}
        }
        known.sort((a,b)->Integer.compare(b.optInt("count"),a.optInt("count")));java.util.Set<String> checked=new java.util.HashSet<>();
        for(JSONObject entry:known){String id=entry.getString("revision_id");if(checked.contains(id))continue;
            try(Cursor row=db.rawQuery("SELECT head FROM replica_seen WHERE revision_id=?",new String[]{id})){if(!row.moveToFirst())continue;JSONObject ancestor=decode(row.getBlob(0));JSONArray parents=ancestor.getJSONArray("ancestors");for(int i=0;i<parents.length();i++){String parent=parents.getString(i);if(parent.equals(head.getString("revision_id")))throw new AncestryReplica.Invalid("CAUSAL_CYCLE","历史版本形成循环。");if(!all.contains(parent))throw new AncestryReplica.Invalid("INVALID_ANCESTRY","版本缺少已保存的传递祖先。");checked.add(parent);}checked.add(id);}
        }
    }

    private void checkHistoricalDescendants(SQLiteDatabase db,String eventId,JSONObject head)throws JSONException{
        // Only a previously claimed but unseen ancestor can already have a
        // descendant. New local UUIDs and identical replays avoid this scan.
        String revision=head.getString("revision_id");JSONArray ancestors=head.getJSONArray("ancestors");
        java.util.Set<String> incoming=new java.util.HashSet<>();for(int i=0;i<ancestors.length();i++)incoming.add(ancestors.getString(i));
        try(Cursor rows=db.rawQuery("SELECT head FROM replica_seen WHERE id=?",new String[]{eventId})) {
            while(rows.moveToNext()) {
                JSONObject descendant=decode(rows.getBlob(0));JSONArray declared=descendant.getJSONArray("ancestors");
                java.util.Set<String> all=new java.util.HashSet<>();for(int i=0;i<declared.length();i++)all.add(declared.getString(i));
                if(!all.contains(revision))continue;
                if(incoming.contains(descendant.getString("revision_id")))throw new AncestryReplica.Invalid("CAUSAL_CYCLE","迟到的版本与已保存历史形成循环。");
                if(!all.containsAll(incoming))throw new AncestryReplica.Invalid("INVALID_ANCESTRY","迟到的版本与已保存的后续历史不一致，已停止合并。");
            }
        }
    }

    private void persist(SQLiteDatabase db,String scope,JSONObject record,String preferred,Integer initialVersion)throws JSONException{
        String id=record.getString("id");JSONObject previous=read(db,scope,id);String selected=preferred;
        if(selected==null)try(Cursor row=db.rawQuery("SELECT preferred FROM replica_records WHERE scope=? AND id=?",new String[]{scope,id})){if(row.moveToFirst())selected=row.getString(0);}
        JSONArray heads=record.getJSONArray("heads");JSONObject representative=heads.getJSONObject(0);
        for(int i=0;i<heads.length();i++)if(heads.getJSONObject(i).getString("revision_id").equals(selected))representative=heads.getJSONObject(i);
        if(representative.getJSONObject("payload").getBoolean("deleted")&&AncestryReplica.distinctPayloadHeads(heads).length()>1)for(int i=0;i<heads.length();i++)if(!heads.getJSONObject(i).getJSONObject("payload").getBoolean("deleted")){representative=heads.getJSONObject(i);break;}
        if(previous!=null&&previous.toString().equals(record.toString()))return;
        int version=0;try(Cursor row=db.rawQuery("SELECT version FROM local_events WHERE scope=? AND id=?",new String[]{scope,id})){if(row.moveToFirst())version=row.getInt(0);}
        version=initialVersion==null?version+1:initialVersion;
        byte[] encoded=encode(record);
        db.execSQL("INSERT OR REPLACE INTO replica_records(scope,id,record,preferred) VALUES(?,?,?,?)",new Object[]{scope,id,encoded,representative.getString("revision_id")});
        db.execSQL("INSERT OR REPLACE INTO replica_versions(scope,id,version,record) VALUES(?,?,?,?)",new Object[]{scope,id,version,encoded});
        db.execSQL("DELETE FROM replica_versions WHERE scope=? AND id=? AND version NOT IN (SELECT version FROM replica_versions WHERE scope=? AND id=? ORDER BY version DESC LIMIT 64)",new Object[]{scope,id,scope,id});
        ContentValues row=new ContentValues();row.put("scope",scope);row.put("id",id);row.put("payload",representative.getJSONObject("payload").toString());row.put("version",version);row.put("dirty",1);row.putNull("conflict");row.putNull("message");row.putNull("expected_base");
        db.insertWithOnConflict("local_events",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }

    String save(String scope,String id,JSONObject payload,Integer expectedVersion)throws JSONException{
        return save(scope,id,payload,expectedVersion,false);
    }
    String resolve(String scope,String id,JSONObject payload,int observedVersion)throws JSONException{return save(scope,id,payload,observedVersion,true);}
    private String save(String scope,String id,JSONObject payload,Integer expectedVersion,boolean resolving)throws JSONException{
        synchronized(owner){ensure(scope);SQLiteDatabase db=owner.getWritableDatabase();db.beginTransaction();try{
            if(id==null)id=UUID.randomUUID().toString();JSONObject current=read(db,scope,id);JSONArray observed=new JSONArray();
            if(current!=null){
                if(resolving){try(Cursor row=db.rawQuery("SELECT version FROM local_events WHERE scope=? AND id=?",new String[]{scope,id})){if(!row.moveToFirst()||expectedVersion==null||row.getInt(0)!=expectedVersion)throw new AncestryReplica.Invalid("VERSION_CONFLICT","日程已收到新的修改，请重新查看并确认所有版本。");}}
                if(!resolving&&AncestryReplica.distinctPayloadHeads(current.getJSONArray("heads")).length()>1)throw new AncestryReplica.Invalid("CONFLICT_REQUIRES_REVIEW","这项日程有不同版本，请先处理合并再修改。");
                if(expectedVersion==null)observed=current.getJSONArray("heads");
                else try(Cursor row=db.rawQuery("SELECT record FROM replica_versions WHERE scope=? AND id=? AND version=?",new String[]{scope,id,expectedVersion.toString()})){if(row.moveToFirst())observed=decode(row.getBlob(0)).getJSONArray("heads");}
                // A very old restored editor may outlive bounded snapshot history.
                // An empty base creates a conservative branch; never join unseen heads.
            }
            JSONObject head=AncestryReplica.createRevision(observed,UUID.randomUUID().toString(),AncestryReplica.payload(payload),owner.deviceName(),System.currentTimeMillis());
            JSONObject change=new JSONObject().put("id",id).put("heads",new JSONArray().put(head));JSONObject next=AncestryReplica.merge(current,change);
            remember(db,scope,change);persist(db,scope,next,head.getString("revision_id"),null);db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}}}

    void merge(JSONArray records)throws JSONException{
        synchronized(owner){String scope=owner.primaryScope();ensure(scope);if(records.length()>PAGE_RECORDS)throw new AncestryReplica.Invalid("LIMIT_EXCEEDED","一次最多合并50条日程。");SQLiteDatabase db=owner.getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<records.length();i++){JSONObject incoming=AncestryReplica.record(records.getJSONObject(i));if(incoming.toString().getBytes(StandardCharsets.UTF_8).length>RECORD_BYTES)throw new AncestryReplica.Invalid("LIMIT_EXCEEDED","单条日程版本过大，未丢弃本机数据。");remember(db,scope,incoming);JSONObject next=AncestryReplica.merge(read(db,scope,incoming.getString("id")),incoming);persist(db,scope,next,null,null);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}}}

    JSONObject page(String after)throws JSONException{
        synchronized(owner){String scope=owner.primaryScope();ensure(scope);return pageFor(scope,after);}
    }
    private JSONObject pageFor(String scope,String after)throws JSONException{
        SQLiteDatabase db=owner.getReadableDatabase();JSONArray records=new JSONArray();int bytes=2;boolean more=false;String last=null;
        try(Cursor rows=db.rawQuery("SELECT id,record FROM replica_records WHERE scope=? AND id>? ORDER BY id LIMIT 51",new String[]{scope,after==null?"":AncestryReplica.uuid(after)})){
            while(rows.moveToNext()){JSONObject record=decode(rows.getBlob(1));int size=record.toString().getBytes(StandardCharsets.UTF_8).length;if(size>RECORD_BYTES)throw new AncestryReplica.Invalid("LIMIT_EXCEEDED","单条日程版本过大，无法发送。");if(records.length()>=PAGE_RECORDS||(records.length()>0&&bytes+size+1>PAGE_BYTES)){more=true;break;}records.put(record);bytes+=size+1;last=rows.getString(0);}
        }
        return new JSONObject().put("records",records).put("has_more",more).put("next_cursor",more?last:JSONObject.NULL);
    }

    void markSent(JSONArray records)throws JSONException{
        synchronized(owner){String scope=owner.primaryScope();SQLiteDatabase db=owner.getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<records.length();i++){JSONObject sent=AncestryReplica.record(records.getJSONObject(i)),current=read(db,scope,sent.getString("id"));if(current!=null&&current.toString().equals(sent.toString()))db.execSQL("UPDATE local_events SET dirty=0 WHERE scope=? AND id=?",new Object[]{scope,sent.getString("id")});}
            db.setTransactionSuccessful();}finally{db.endTransaction();}}
    }

    JSONArray conflicts(String scope,String id)throws JSONException{
        synchronized(owner){JSONObject record=read(owner.getReadableDatabase(),scope,id);if(record==null)return new JSONArray();JSONArray heads=AncestryReplica.distinctPayloadHeads(record.getJSONArray("heads"));return heads.length()>1?heads:new JSONArray();}
    }

    int importFrom(String source)throws JSONException{
        synchronized(owner){source=owner.canonicalScope(source);String primary=owner.primaryScope();if(source.equals(primary))return 0;ensure(source);String cursor=null;int count=0;do{JSONObject page=pageFor(source,cursor);JSONArray records=page.getJSONArray("records");merge(records);count+=records.length();cursor=page.optBoolean("has_more")?page.getString("next_cursor"):null;}while(cursor!=null);return count;}
    }
}
