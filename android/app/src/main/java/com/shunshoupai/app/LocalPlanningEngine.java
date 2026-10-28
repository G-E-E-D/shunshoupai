package com.shunshoupai.app;

import android.content.Context;
import android.net.ConnectivityManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Phone-local planning API. Call blocking methods from the UI's background executor. */
final class LocalPlanningEngine {
    static final String SYSTEM_PROMPT=LocalPlanningParser.SYSTEM_PROMPT;
    private final Context context;
    private final LocalModelSettings settings;
    private final LocalModelTransport transport;
    LocalPlanningEngine(Context context){this(context,"local-model-settings-v1");}
    LocalPlanningEngine(Context context,String namespace){this(context,namespace,java.net.InetAddress::getAllByName);}
    LocalPlanningEngine(Context context,String namespace,LocalModelTransport.Resolver resolver){this.context=context;settings=new LocalModelSettings(context,namespace);transport=new LocalModelTransport(resolver);}
    JSONObject settings()throws Exception{return settings.info();}
    JSONObject saveSettings(JSONObject input)throws Exception{return settings.save(input);}
    JSONObject clearSettings(int baseVersion)throws Exception{return settings.clear(baseVersion);}
    void cancel(){transport.cancel();}

    JSONObject testSettings(JSONObject input)throws Exception {
        LocalModelSettings.Config candidate=settings.prepare(input);long started=android.os.SystemClock.elapsedRealtime();
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","这是模型连接测试。只输出严格 JSON：{\"ok\":true}，不要输出其他内容。"))
            .put(new JSONObject().put("role","user").put("content","请返回 {\"ok\":true}。"));
        String content=transport.request(candidate,messages);
        try{JSONObject response=PlanningJson.object(content,65536);PlanningJson.keys(response,"ok");if(!PlanningJson.bool(response,"ok"))throw new IllegalArgumentException();}
        catch(Exception failure){throw new LocalPlanningException("MODEL_INVALID_RESPONSE","模型已响应，但未返回要求的 JSON 格式；配置尚未保存。");}
        return new JSONObject().put("ok",true).put("latency_ms",android.os.SystemClock.elapsedRealtime()-started).put("message","手机已直接连接模型，配置尚未保存。");
    }
    JSONObject parse(String text,String selectedDate)throws Exception{return parse(text,selectedDate,Collections.emptyList(),false);}
    JSONObject parse(String text,String selectedDate,List<JSONObject> busy,boolean rulesOnly)throws Exception {
        try{LocalDate date=LocalDate.parse(selectedDate);LocalDateTime reference=date.atTime(Instant.now().atZone(LocalPlanningParser.ZONE).toLocalTime());return parse(text,reference.atZone(LocalPlanningParser.ZONE).toInstant().toEpochMilli(),busy,rulesOnly);}
        catch(java.time.DateTimeException|NullPointerException failure){throw new LocalPlanningException("VALIDATION_ERROR","请选择有效的参考日期。");}
    }
    JSONObject parse(String text,long referenceEpochMillis,List<JSONObject> busy,boolean rulesOnly)throws Exception {
        text=LocalPlanningParser.input(text);List<JSONObject> current=busy==null?Collections.emptyList():new ArrayList<>(busy);
        JSONObject result=LocalPlanningParser.rules(text,referenceEpochMillis);
        if(!rulesOnly&&!LocalPlanningParser.mustUseRules(result)) {
            if(!networkAvailable()){result.getJSONArray("warnings").put("手机当前离线，使用本地规则整理；这不是离线大模型。");}
            else {LocalModelSettings.Config config=settings.read();if(config!=null){
                JSONArray intervals=new JSONArray();for(JSONObject event:current){if(intervals.length()>=100)break;if(event.optBoolean("deleted")||event.optString("status").equals("completed"))continue;try{String start=LocalPlanningParser.local(event.getString("start_local")).format(LocalPlanningParser.TIME),end=LocalPlanningParser.local(event.getString("end_local")).format(LocalPlanningParser.TIME);intervals.put(new JSONObject().put("start_local",start).put("end_local",end));}catch(Exception ignored){}}
                JSONObject user=new JSONObject().put("reference_time",Instant.ofEpochMilli(referenceEpochMillis).atZone(LocalPlanningParser.ZONE).toLocalDateTime().format(LocalPlanningParser.TIME)).put("timezone","Asia/Shanghai").put("text",text).put("busy",intervals);
                JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content",SYSTEM_PROMPT)).put(new JSONObject().put("role","user").put("content",user.toString()));
                result=LocalPlanningParser.model(transport.request(config,messages));
            }}
        }
        LocalPlanningParser.warnings(result,referenceEpochMillis,current);JSONArray drafts=new JSONArray(),events=result.getJSONArray("events");for(int i=0;i<events.length();i++)drafts.put(new JSONObject(events.getJSONObject(i).toString()).put("status","planned"));
        return result.put("drafts",drafts).put("source",result.getString("provider"));
    }
    private boolean networkAvailable(){try{ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);return manager!=null&&manager.getActiveNetwork()!=null;}catch(SecurityException ignored){return false;}}
}
