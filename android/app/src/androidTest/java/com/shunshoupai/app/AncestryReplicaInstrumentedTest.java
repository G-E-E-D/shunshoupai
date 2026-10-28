package com.shunshoupai.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class AncestryReplicaInstrumentedTest {
    static String id(int value) { return String.format(Locale.ROOT, "%08x-1234-4abc-8abc-123456789abc", value); }
    static JSONObject payload(String title, boolean deleted) throws Exception {
        return new JSONObject().put("title",title).put("start_local","2030-10-28T09:00:00")
                .put("end_local","2030-10-28T10:00:00").put("timezone","Asia/Shanghai")
                .put("status","planned").put("deleted",deleted);
    }
    static JSONObject head(int value, JSONArray base, String title, boolean deleted) throws Exception {
        return AncestryReplica.createRevision(base,id(value),payload(title,deleted),"测试设备",1_800_000_000_000L+value);
    }
    static JSONObject record(String event, JSONObject... heads) throws Exception {
        JSONArray array=new JSONArray();for(JSONObject head:heads)array.put(head);
        return new JSONObject().put("id",event).put("heads",array);
    }
    interface Checked { void run() throws Exception; }
    static void rejects(String code, Checked action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (AncestryReplica.Invalid error) { assertEquals(code,error.code); }
    }
    private static String canonical(JSONArray heads) throws Exception {return AncestryReplica.mergeHeads(heads,new JSONArray()).toString();}

    @Test public void sharedTypeScriptFixturesMatchCanonicalHeadsAndRejections() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("replica-v1.json")) {
            byte[] block=new byte[4096];int count;while((count=input.read(block))!=-1)bytes.write(block,0,count);
        }
        JSONObject fixtures=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        JSONArray valid=fixtures.getJSONArray("valid");
        for(int i=0;i<valid.length();i++) {
            JSONObject item=valid.getJSONObject(i);JSONArray actual=AncestryReplica.mergeHeads(item.getJSONArray("local"),item.getJSONArray("incoming"));
            assertEquals(item.getString("name"),canonical(item.getJSONArray("expected")),actual.toString());
            assertEquals(item.getInt("distinct"),AncestryReplica.distinctPayloadHeads(actual).length());
        }
        JSONArray invalid=fixtures.getJSONArray("invalid");
        for(int i=0;i<invalid.length();i++) {JSONObject item=invalid.getJSONObject(i);rejects(item.getString("code"),()->AncestryReplica.mergeHeads(item.getJSONArray("local"),item.getJSONArray("incoming")));}
    }

    @Test public void allValidPacketGroupingsAreAssociativeCommutativeAndIdempotent() throws Exception {
        JSONObject root=head(1,new JSONArray(),"原稿",false),a=head(2,new JSONArray().put(root),"A",false),b=head(3,new JSONArray().put(root),"B",true),c=head(4,new JSONArray().put(a),"C",false);
        JSONArray[] states={new JSONArray(),new JSONArray().put(root),new JSONArray().put(a),new JSONArray().put(b),new JSONArray().put(c),new JSONArray().put(a).put(b)};
        for(JSONArray first:states) {
            assertEquals(canonical(first),AncestryReplica.mergeHeads(first,first).toString());
            for(JSONArray second:states) {
                assertEquals(AncestryReplica.mergeHeads(first,second).toString(),AncestryReplica.mergeHeads(second,first).toString());
                for(JSONArray third:states) assertEquals(AncestryReplica.mergeHeads(AncestryReplica.mergeHeads(first,second),third).toString(),AncestryReplica.mergeHeads(first,AncestryReplica.mergeHeads(second,third)).toString());
            }
        }
    }

    @Test public void oldBackupNewRevisionDoesNotOverwriteNewerHistoryDespiteSameAuthorAndClock() throws Exception {
        JSONObject original=head(1,new JSONArray(),"原稿",false),newer=head(2,new JSONArray().put(original),"较新内容",false);
        JSONObject restored=AncestryReplica.createRevision(new JSONArray().put(original),id(3),payload("旧备份上继续编辑",false),"同一手机",-1);
        JSONArray merged=AncestryReplica.mergeHeads(new JSONArray().put(newer),new JSONArray().put(restored));
        assertEquals(2,merged.length());assertEquals(2,AncestryReplica.distinctPayloadHeads(merged).length());
        assertEquals(-1,merged.getJSONObject(1).getLong("changed_at"));
    }

    @Test public void headAndAncestorLimitsRejectWithoutMutatingInputs() throws Exception {
        JSONArray local=new JSONArray();for(int i=0;i<32;i++)local.put(head(100+i,new JSONArray(),"并发"+i,false));
        String before=local.toString();rejects("LIMIT_EXCEEDED",()->AncestryReplica.mergeHeads(local,new JSONArray().put(head(500,new JSONArray(),"额外版本",false))));
        assertEquals(before,local.toString());
        JSONObject longHistory=head(600,new JSONArray(),"长历史",false);JSONArray ancestors=new JSONArray();for(int i=0;i<2048;i++)ancestors.put(id(1000+i));longHistory.put("ancestors",ancestors);
        assertEquals(2048,AncestryReplica.head(longHistory).getJSONArray("ancestors").length());
        rejects("LIMIT_EXCEEDED",()->AncestryReplica.createRevision(new JSONArray().put(longHistory),id(900),payload("继续",false),"手机",0));
        ancestors.put(id(4000));rejects("LIMIT_EXCEEDED",()->AncestryReplica.head(longHistory));
    }

    @Test public void strictPayloadSchemaPreservesTextAndRejectsImpossibleDatesAndCoercions() throws Exception {
        assertEquals("  保留标题空格  ",AncestryReplica.payload(payload("  保留标题空格  ",false)).getString("title"));
        rejects("INVALID_REPLICA",()->AncestryReplica.payload(payload("\uFEFF\u3000",false)));
        rejects("INVALID_REPLICA",()->AncestryReplica.payload(payload("错误日期",false).put("start_local","2030-02-30T09:00:00")));
        rejects("INVALID_REPLICA",()->AncestryReplica.payload(payload("错误类型",false).put("deleted","false")));
        rejects("INVALID_REPLICA",()->AncestryReplica.head(head(10,new JSONArray(),"错误时间",false).put("changed_at",1.5)));
    }
}
