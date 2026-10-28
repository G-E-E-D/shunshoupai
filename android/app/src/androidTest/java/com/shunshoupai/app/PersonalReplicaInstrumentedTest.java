package com.shunshoupai.app;

import static org.junit.Assert.*;
import static com.shunshoupai.app.AncestryReplicaInstrumentedTest.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class PersonalReplicaInstrumentedTest {
    private Context context;
    private final List<LocalScheduleStore> stores=new ArrayList<>();
    private final List<String> databases=new ArrayList<>();
    private LocalScheduleStore a,b,c;
    @Before public void open() {context=ApplicationProvider.getApplicationContext();a=device();b=device();c=device();}
    @After public void close() {for(LocalScheduleStore store:stores)store.close();for(String database:databases)context.deleteDatabase(database);}
    private LocalScheduleStore device() {
        String name="replica-test-"+UUID.randomUUID()+".sqlite";databases.add(name);
        LocalScheduleStore store=new LocalScheduleStore(context,name);stores.add(store);store.initialize("");return store;
    }
    private JSONObject item(LocalScheduleStore store,String event) throws Exception {
        for(JSONObject row:store.list(store.primaryScope()))if(event.equals(row.getString("id")))return row;
        throw new AssertionError("Missing event "+event);
    }
    private JSONArray records(LocalScheduleStore store) throws Exception {return store.replica().page(null).getJSONArray("records");}
    private JSONObject stored(LocalScheduleStore store,String event) throws Exception {
        JSONArray records=records(store);for(int i=0;i<records.length();i++)if(event.equals(records.getJSONObject(i).getString("id")))return records.getJSONObject(i);
        throw new AssertionError("Missing replica "+event);
    }
    private void exchange(LocalScheduleStore from,LocalScheduleStore to) throws Exception {
        String after=null;do {JSONObject page=from.replica().page(after);to.replica().merge(page.getJSONArray("records"));after=page.getBoolean("has_more")?page.getString("next_cursor"):null;}while(after!=null);
    }

    @Test public void firstOfflineUsePersistsMainCalendarAndPhysicalIdentityAcrossRestart() throws Exception {
        assertNotEquals(a.deviceId(),b.deviceId());assertNotEquals(a.primarySpaceId(),b.primarySpaceId());
        String device=a.deviceId(),scope=a.primaryScope(),space=a.primarySpaceId();
        String event=a.save(scope,null,payload("首次离线保存",false));int version=item(a,event).getInt("version");
        a.close();stores.remove(a);a=new LocalScheduleStore(context,databases.get(0));stores.add(a);a.initialize("");
        assertEquals(device,a.deviceId());assertEquals(scope,a.primaryScope());assertEquals(space,a.primarySpaceId());
        assertEquals(version,item(a,event).getInt("version"));assertEquals("首次离线保存",item(a,event).getString("title"));
        assertEquals(1,records(a).length());assertEquals(0,records(b).length());
    }

    @Test public void editorOpenedBeforeRemoteChangeKeepsBothContentsAndOldUploadCannotClearNewDirtyState() throws Exception {
        String event=a.save(a.primaryScope(),null,payload("原始",false));exchange(a,b);
        int observed=item(a,event).getInt("version");JSONArray sent=records(a);
        b.save(b.primaryScope(),event,payload("别端修改",false),item(b,event).getInt("version"));exchange(b,a);
        a.save(a.primaryScope(),event,payload("仍在编辑的本机稿",false),observed);
        assertEquals(2,item(a,event).getJSONArray("replica_conflicts").length());
        a.replica().markSent(sent);assertTrue(item(a,event).getBoolean("dirty"));
        JSONArray current=records(a);a.replica().markSent(current);assertFalse(item(a,event).getBoolean("dirty"));
        assertEquals(2,stored(a,event).getJSONArray("heads").length());
    }

    @Test public void threeDevicesConvergeWithConcurrentDeletionAndExplicitResolutionNeverRevivesOldHeads() throws Exception {
        String event=a.save(a.primaryScope(),null,payload("原始",false));exchange(a,b);exchange(b,c);JSONArray original=records(a);
        a.save(a.primaryScope(),event,payload("A修改",false),item(a,event).getInt("version"));
        b.save(b.primaryScope(),event,payload("B修改",false),item(b,event).getInt("version"));
        c.save(c.primaryScope(),event,payload("C删除",true),item(c,event).getInt("version"));
        for(int round=0;round<3;round++){exchange(a,b);exchange(b,c);exchange(c,a);}
        assertEquals(3,item(a,event).getJSONArray("replica_conflicts").length());assertFalse(item(a,event).getBoolean("deleted"));
        assertEquals(stored(a,event).toString(),stored(b,event).toString());assertEquals(stored(a,event).toString(),stored(c,event).toString());
        a.replica().resolve(a.primaryScope(),event,payload("确认删除",true),item(a,event).getInt("version"));
        exchange(a,b);exchange(b,c);exchange(c,a);a.replica().merge(original);b.replica().merge(original);c.replica().merge(original);
        for(LocalScheduleStore store:new LocalScheduleStore[]{a,b,c}) {assertTrue(item(store,event).getBoolean("deleted"));assertFalse(item(store,event).has("replica_conflicts"));assertEquals(1,stored(store,event).getJSONArray("heads").length());}
    }

    @Test public void resolutionMustBeReconfirmedWhenAnotherRevisionArrives() throws Exception {
        String event=a.save(a.primaryScope(),null,payload("原始",false));exchange(a,b);exchange(a,c);
        a.save(a.primaryScope(),event,payload("A",false));b.save(b.primaryScope(),event,payload("B",false));exchange(b,a);
        int observed=item(a,event).getInt("version");
        c.save(c.primaryScope(),event,payload("刚到达的C",true));exchange(c,a);String before=stored(a,event).toString();
        rejects("VERSION_CONFLICT",()->a.replica().resolve(a.primaryScope(),event,payload("A",false),observed));
        assertEquals(before,stored(a,event).toString());assertEquals(3,item(a,event).getJSONArray("replica_conflicts").length());
    }

    @Test public void missingAncestorsAndRewrittenPrunedRevisionsAreRejectedTransactionally() throws Exception {
        String event=id(50);JSONObject root=head(1,new JSONArray(),"原始",false),second=head(2,new JSONArray().put(root),"第二版",false),third=head(3,new JSONArray().put(second),"第三版",false);
        a.replica().merge(new JSONArray().put(record(event,root)));a.replica().merge(new JSONArray().put(record(event,second)));a.replica().merge(new JSONArray().put(record(event,third)));
        String before=stored(a,event).toString();JSONObject forged=head(4,new JSONArray(),"伪造历史",false).put("ancestors",new JSONArray().put(id(2)));
        rejects("INVALID_ANCESTRY",()->a.replica().merge(new JSONArray().put(record(event,forged))));
        JSONObject overwritten=new JSONObject(root.toString()).put("payload",payload("重写旧版本",false));
        JSONObject unrelated=record(id(60),head(60,new JSONArray(),"应回滚",false));
        rejects("REVISION_COLLISION",()->a.replica().merge(new JSONArray().put(unrelated).put(record(event,overwritten))));
        assertEquals(before,stored(a,event).toString());assertEquals(1,records(a).length());
    }

    @Test public void revisionIdsAndAncestorClaimsCannotBeSharedAcrossDifferentEvents() throws Exception {
        JSONObject ancestor=head(1,new JSONArray(),"甲",false);a.replica().merge(new JSONArray().put(record(id(50),ancestor)));
        rejects("REVISION_COLLISION",()->a.replica().merge(new JSONArray().put(record(id(51),ancestor))));
        JSONObject wrongEvent=head(2,new JSONArray().put(ancestor),"乙",false);
        rejects("REVISION_COLLISION",()->a.replica().merge(new JSONArray().put(record(id(51),wrongEvent))));
        assertEquals(1,records(a).length());
    }

    @Test public void unknownAncestorOwnershipCannotChangeWhenTheAncestorArrivesLater() throws Exception {
        JSONObject claimed=head(2,new JSONArray(),"后裔",false).put("ancestors",new JSONArray().put(id(1)));
        a.replica().merge(new JSONArray().put(record(id(50),claimed)));
        JSONObject otherEvent=record(id(51),head(1,new JSONArray(),"另一日程",false));
        rejects("REVISION_COLLISION",()->a.replica().merge(new JSONArray().put(otherEvent)));
        assertEquals(1,records(a).length());
    }

    @Test public void aValidLateAncestorDoesNotRestoreOldContentOrIncrementTheProjectionVersion() throws Exception {
        String event=id(54);JSONObject ancestor=head(1,new JSONArray(),"旧内容",false);
        JSONObject descendant=head(2,new JSONArray().put(ancestor),"当前内容",false);
        a.replica().merge(new JSONArray().put(record(event,descendant)));String before=stored(a,event).toString();int version=item(a,event).getInt("version");
        a.replica().merge(new JSONArray().put(record(event,ancestor)));a.replica().merge(new JSONArray().put(record(event,ancestor)));
        assertEquals(before,stored(a,event).toString());assertEquals(version,item(a,event).getInt("version"));assertEquals("当前内容",item(a,event).getString("title"));
    }

    @Test public void lateAncestorCannotInvalidateHistoryHeldInAnotherPreservedLocalDataset() throws Exception {
        String source="legacy:"+UUID.randomUUID(),event=id(55);
        a.getWritableDatabase().execSQL("INSERT INTO spaces(scope,space_id,label) VALUES(?,?,?)",new Object[]{source,UUID.randomUUID().toString(),"保留的旧资料"});
        // Import X into the primary ledger, then keep its replica only in an old
        // dataset. The global immutable receipt still constrains a late Y.
        JSONObject descendant=head(2,new JSONArray(),"已见后裔",false).put("ancestors",new JSONArray().put(id(1)));
        a.replica().merge(new JSONArray().put(record(event,descendant)));
        a.getWritableDatabase().execSQL("UPDATE replica_records SET scope=? WHERE scope=? AND id=?",new Object[]{source,a.primaryScope(),event});
        a.getWritableDatabase().execSQL("UPDATE local_events SET scope=? WHERE scope=? AND id=?",new Object[]{source,a.primaryScope(),event});
        JSONObject late=head(1,new JSONArray(),"迟到祖先",false).put("ancestors",new JSONArray().put(id(3)));
        rejects("INVALID_ANCESTRY",()->a.replica().merge(new JSONArray().put(record(event,late))));
        assertEquals(0,records(a).length());assertEquals(1,a.list(source).size());
    }

    @Test public void pageLimitsNeverTruncateAndInvalidBatchLeavesNoPartialData() throws Exception {
        JSONArray oversized=new JSONArray();for(int i=0;i<51;i++)oversized.put(record(id(100+i),head(1000+i,new JSONArray(),"安排"+i,false)));
        rejects("LIMIT_EXCEEDED",()->a.replica().merge(oversized));assertEquals(0,records(a).length());
        JSONArray first=new JSONArray();for(int i=0;i<50;i++)first.put(oversized.getJSONObject(i));a.replica().merge(first);a.replica().merge(new JSONArray().put(oversized.getJSONObject(50)));
        JSONObject page=a.replica().page(null);assertEquals(50,page.getJSONArray("records").length());assertTrue(page.getBoolean("has_more"));
        JSONObject next=a.replica().page(page.getString("next_cursor"));assertEquals(1,next.getJSONArray("records").length());assertFalse(next.getBoolean("has_more"));assertTrue(next.isNull("next_cursor"));
        Set<String> ids=new HashSet<>();for(int i=0;i<50;i++)ids.add(page.getJSONArray("records").getJSONObject(i).getString("id"));assertTrue(ids.add(next.getJSONArray("records").getJSONObject(0).getString("id")));assertEquals(51,ids.size());
        exchange(a,b);assertEquals(51,b.list(b.primaryScope()).size());
    }

    @Test public void expiredEditorSnapshotMakesAConservativeBranchInsteadOfJoiningUnseenUpdates() throws Exception {
        String event=a.save(a.primaryScope(),null,payload("旧表单",false));int oldVersion=item(a,event).getInt("version");
        for(int i=0;i<66;i++)a.save(a.primaryScope(),event,payload("新修改"+i,false));
        a.save(a.primaryScope(),event,payload("恢复很旧的表单",false),oldVersion);
        assertEquals(2,item(a,event).getJSONArray("replica_conflicts").length());
    }

    @Test public void historicalDatasetsRemainSeparateUntilExplicitImportAndKeepVersionsAndDraftAliases() throws Exception {
        String source="legacy:"+UUID.randomUUID(),event=id(70),legacyAlias="http://old-device/|"+UUID.randomUUID();
        a.getWritableDatabase().execSQL("INSERT INTO spaces(scope,space_id,label) VALUES(?,?,?)",new Object[]{source,UUID.randomUUID().toString(),"历史资料"});
        a.getWritableDatabase().execSQL("INSERT INTO scope_aliases(alias,scope) VALUES(?,?)",new Object[]{legacyAlias,source});
        a.getWritableDatabase().execSQL("INSERT INTO local_events(scope,id,payload,version,dirty) VALUES(?,?,?,7,1)",new Object[]{source,event,payload("历史安排",false).toString()});
        assertEquals(0,records(a).length());assertEquals(7,a.list(source).get(0).getInt("version"));assertTrue(a.draftScopes(source).contains(legacyAlias));
        assertEquals(1,a.replica().importFrom(source));assertEquals("历史安排",item(a,event).getString("title"));assertEquals(7,a.list(source).get(0).getInt("version"));
        int version=item(a,event).getInt("version");assertEquals(1,a.replica().importFrom(source));assertEquals(version,item(a,event).getInt("version"));assertEquals(1,records(a).length());
    }
}
