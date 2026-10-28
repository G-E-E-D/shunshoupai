package com.shunshoupai.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable causal heads shared with src/lib/replica.ts. Wall clocks never select content. */
final class AncestryReplica {
    static final int MAX_HEADS=32,MAX_ANCESTORS=2048;
    private static final long MAX_DATE=8_640_000_000_000_000L;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    static final class Invalid extends IllegalArgumentException {
        final String code;
        Invalid(String code,String message){super(message);this.code=code;}
    }
    private static Invalid invalid(String message){return new Invalid("INVALID_REPLICA",message);}
    private static boolean blank(String value){for(int i=0;i<value.length();i++){int c=value.charAt(i);if(!((c>=9&&c<=13)||c==0x20||c==0xa0||c==0x1680||(c>=0x2000&&c<=0x200a)||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000||c==0xfeff))return false;}return true;}
    private static String string(JSONObject object,String key)throws JSONException{Object value=object.get(key);if(!(value instanceof String))throw invalid("日程版本字段格式无效。");return (String)value;}
    private static void keys(JSONObject object,String... expected){Set<String> names=new HashSet<>(Arrays.asList(expected));Iterator<String> keys=object.keys();while(keys.hasNext())if(!names.remove(keys.next()))throw invalid("日程版本包含未知字段。");if(!names.isEmpty())throw invalid("日程版本缺少必要字段。");}
    static String uuid(String value){
        if(!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("版本编号无效。");
        UUID id=UUID.fromString(value);String canonical=id.toString().toLowerCase(Locale.ROOT);
        if(!(id.version()>=1&&id.version()<=8&&id.variant()==2)&&!canonical.equals("00000000-0000-0000-0000-000000000000")&&!canonical.equals("ffffffff-ffff-ffff-ffff-ffffffffffff"))throw invalid("版本编号无效。");
        return canonical;
    }
    static JSONObject payload(JSONObject raw)throws JSONException{
        keys(raw,"title","start_local","end_local","timezone","status","deleted");
        String title=string(raw,"title"),start=string(raw,"start_local"),end=string(raw,"end_local"),timezone=string(raw,"timezone"),status=string(raw,"status");
        if(title.isEmpty()||title.length()>120||blank(title))throw invalid("日程标题不能为空且不能超过120字。");
        if(!start.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")||!end.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"))throw invalid("日期格式无效。");
        try{LocalDateTime a=LocalDateTime.parse(start,TIME),b=LocalDateTime.parse(end,TIME);long duration=Duration.between(a,b).getSeconds();if(a.getYear()<2000||a.getYear()>2100||b.getYear()<2000||b.getYear()>2100||duration<=0||duration>86400)throw invalid("日程时间范围无效。");}catch(java.time.DateTimeException error){throw invalid("日程日期无效。");}
        if(!"Asia/Shanghai".equals(timezone)||(!"planned".equals(status)&&!"completed".equals(status))||!(raw.get("deleted") instanceof Boolean))throw invalid("日程状态或时区无效。");
        return new JSONObject().put("title",title).put("start_local",start).put("end_local",end).put("timezone",timezone).put("status",status).put("deleted",raw.getBoolean("deleted"));
    }
    static JSONObject head(JSONObject raw)throws JSONException{
        keys(raw,"revision_id","ancestors","payload","author","changed_at");String id=uuid(string(raw,"revision_id")),author=string(raw,"author");
        if(author.length()>80)throw invalid("设备名称过长。");
        JSONArray input=raw.getJSONArray("ancestors");if(input.length()>MAX_ANCESTORS)throw new Invalid("LIMIT_EXCEEDED","版本历史过长，未丢弃已有数据。");
        TreeSet<String> ancestors=new TreeSet<>();for(int i=0;i<input.length();i++){if(!(input.get(i) instanceof String))throw invalid("祖先编号无效。");ancestors.add(uuid((String)input.get(i)));}
        if(ancestors.contains(id))throw invalid("版本不能以自身为祖先。");
        Object timestamp=raw.get("changed_at");if(!(timestamp instanceof Number))throw invalid("版本时间无效。");double value=((Number)timestamp).doubleValue();
        if(!Double.isFinite(value)||value!=Math.rint(value)||value < -MAX_DATE||value>MAX_DATE)throw invalid("版本时间无效。");
        return new JSONObject().put("revision_id",id).put("ancestors",new JSONArray(ancestors)).put("payload",payload(raw.getJSONObject("payload"))).put("author",author).put("changed_at",(long)value);
    }
    private static List<JSONObject> heads(JSONArray raw)throws JSONException{
        if(raw.length()>MAX_HEADS)throw new Invalid("LIMIT_EXCEEDED","并发版本过多，未丢弃已有数据。");List<JSONObject> values=new ArrayList<>();for(int i=0;i<raw.length();i++)values.add(head(raw.getJSONObject(i)));return values;
    }
    static String payloadKey(JSONObject value)throws JSONException{return new JSONArray().put(value.getString("title")).put(value.getString("start_local")).put(value.getString("end_local")).put(value.getString("timezone")).put(value.getString("status")).put(value.getBoolean("deleted")).toString();}
    private static String headKey(JSONObject value)throws JSONException{return new JSONArray().put(value.getString("revision_id")).put(value.getJSONArray("ancestors")).put(payloadKey(value.getJSONObject("payload"))).put(value.getString("author")).put(value.getLong("changed_at")).toString();}
    private static Set<String> ancestors(JSONObject head)throws JSONException{Set<String> result=new HashSet<>();JSONArray array=head.getJSONArray("ancestors");for(int i=0;i<array.length();i++)result.add(array.getString(i));return result;}
    private static void visit(String id,Map<String,JSONObject> heads,Set<String> visiting,Set<String> visited)throws JSONException{
        if(visiting.contains(id))throw new Invalid("CAUSAL_CYCLE","版本祖先形成循环，已停止合并。");if(visited.contains(id))return;visiting.add(id);
        for(String parent:ancestors(heads.get(id)))if(heads.containsKey(parent))visit(parent,heads,visiting,visited);
        visiting.remove(id);visited.add(id);
    }
    static JSONArray mergeHeads(JSONArray local,JSONArray incoming)throws JSONException{
        List<JSONObject> all=heads(local);all.addAll(heads(incoming));Map<String,JSONObject> byId=new TreeMap<>();
        for(JSONObject value:all){String id=value.getString("revision_id");JSONObject previous=byId.get(id);if(previous!=null&&!headKey(previous).equals(headKey(value)))throw new Invalid("REVISION_COLLISION","同一版本编号的内容发生变化，已停止合并。");byId.put(id,value);}
        Set<String> visiting=new HashSet<>(),visited=new HashSet<>(),covered=new HashSet<>();for(String id:byId.keySet())visit(id,byId,visiting,visited);
        for(JSONObject value:byId.values()){Set<String> parents=ancestors(value);covered.addAll(parents);for(String parent:parents){JSONObject known=byId.get(parent);if(known!=null&&!parents.containsAll(ancestors(known)))throw new Invalid("INVALID_ANCESTRY","版本缺少已知的传递祖先，已停止合并。");}}
        JSONArray result=new JSONArray();for(Map.Entry<String,JSONObject> entry:byId.entrySet())if(!covered.contains(entry.getKey()))result.put(entry.getValue());
        if(result.length()>MAX_HEADS)throw new Invalid("LIMIT_EXCEEDED","并发版本过多，请先处理冲突；所有版本均保留。");return result;
    }
    static JSONObject record(JSONObject raw)throws JSONException{
        keys(raw,"id","heads");String id=uuid(string(raw,"id"));JSONArray incoming=raw.getJSONArray("heads");if(incoming.length()==0)throw invalid("日程版本不能为空。");return new JSONObject().put("id",id).put("heads",new JSONArray(heads(incoming)));
    }
    static JSONObject merge(JSONObject local,JSONObject incoming)throws JSONException{
        JSONObject next=record(incoming),previous=local==null?null:record(local);
        if(previous!=null&&!previous.getString("id").equals(next.getString("id")))throw new Invalid("EVENT_ID_MISMATCH","不同日程不能合并为同一条。");
        return new JSONObject().put("id",next.getString("id")).put("heads",mergeHeads(previous==null?new JSONArray():previous.getJSONArray("heads"),next.getJSONArray("heads")));
    }
    static JSONObject createRevision(JSONArray base,String revisionId,JSONObject payload,String author,long changedAt)throws JSONException{
        JSONArray observed=mergeHeads(base,new JSONArray());TreeSet<String> parents=new TreeSet<>();for(int i=0;i<observed.length();i++){JSONObject value=observed.getJSONObject(i);parents.add(value.getString("revision_id"));parents.addAll(ancestors(value));}
        String id=uuid(revisionId);if(parents.contains(id))throw new Invalid("REVISION_COLLISION","新修改必须使用新的版本编号。");if(parents.size()>MAX_ANCESTORS)throw new Invalid("LIMIT_EXCEEDED","版本历史达到上限，未截断已有历史。");
        return head(new JSONObject().put("revision_id",id).put("ancestors",new JSONArray(parents)).put("payload",payload).put("author",author).put("changed_at",changedAt));
    }
    static JSONArray distinctPayloadHeads(JSONArray heads)throws JSONException{
        Map<String,JSONObject> groups=new LinkedHashMap<>();JSONArray merged=mergeHeads(heads,new JSONArray());for(int i=0;i<merged.length();i++){JSONObject value=merged.getJSONObject(i);String key=payloadKey(value.getJSONObject("payload"));if(!groups.containsKey(key))groups.put(key,value);}return new JSONArray(groups.values());
    }
}
