package com.shunshoupai.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Text and editable proposals stay on this phone until the user explicitly confirms a batch. */
final class NativePlanningDialog {
    private static final Object DRAFT_LOCK=new Object();
    private final Context context;
    private final LocalScheduleStore store;
    private final LocalPlanningEngine engine;
    private final String scope;
    private final LocalDate referenceDate;
    private final Consumer<LocalDate> saved;
    private final SharedPreferences preferences;
    private final OcrTextInbox ocrInbox;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AlertDialog dialog;
    private final EditText input;
    private final Button parse,rules;
    private final Button pendingOcr;
    private Button commit;
    private final TextView source,warnings,result;
    private final LinearLayout rows;
    private JSONObject state;
    private List<JSONObject> currentEvents=java.util.Collections.emptyList();
    private boolean closed,busy,rendering;
    private long generation;
    private String requestedOcrId;
    private boolean organizeOcrNow;
    private AlertDialog ocrPrompt;

    NativePlanningDialog(Context context,LocalScheduleStore store,LocalPlanningEngine engine,String scope,LocalDate referenceDate,Consumer<LocalDate> saved){
        this.context=context;this.store=store;this.engine=engine;this.scope=scope;this.referenceDate=referenceDate;this.saved=saved;
        preferences=context.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE);ocrInbox=new OcrTextInbox(context);state=readState();
        LinearLayout content=NativeUi.column(context);int p=NativeUi.dp(context,18);content.setPadding(p,0,p,p);
        content.addView(NativeUi.text(context,"写下安排，先检查草稿再保存。参考日期：今天 "+referenceDate+" · 上海时间",13));
        source=NativeUi.text(context,"",12);source.setId(R.id.planner_source);content.addView(source);
        input=NativeUi.field(context,"例如：明天上午9点开会一小时，下午3点散步三十分钟",R.id.planner_input);input.setSingleLine(false);input.setMinLines(3);input.setMaxLines(6);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});input.setText(state.optString("text"));content.addView(input);
        input.addTextChangedListener(watch(()->{if(!rendering){put(state,"text",input.getText().toString());persist();}}));
        pendingOcr=NativeUi.button(context,"查看待处理的识别文字",false,()->{requestedOcrId=null;organizeOcrNow=false;presentOcr();});pendingOcr.setTag("planner_ocr_pending");content.addView(pendingOcr);
        LinearLayout actions=NativeUi.row(context);parse=NativeUi.button(context,"整理成草稿",true,()->requestParse(false));parse.setId(R.id.planner_parse);actions.addView(parse,NativeUi.weight(context));
        rules=NativeUi.button(context,"仅用离线规则",false,()->requestParse(true));rules.setId(R.id.planner_rules);actions.addView(rules,NativeUi.weight(context));content.addView(actions);
        warnings=NativeUi.text(context,"",13);warnings.setId(R.id.planner_warnings);warnings.setTextColor(Color.rgb(153,83,0));content.addView(warnings);
        rows=NativeUi.column(context);rows.setId(R.id.planner_rows);content.addView(rows);
        result=NativeUi.text(context,"",13);result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(result);
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(false);scroll.addView(content);
        dialog=new AlertDialog.Builder(context).setTitle("智能整理 · 本机草稿").setView(scroll).setNegativeButton("稍后继续",null).setPositiveButton("保存已选",null).create();dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored->{commit=dialog.getButton(AlertDialog.BUTTON_POSITIVE);commit.setId(R.id.planner_commit);commit.setOnClickListener(v->commit());updateCommit();});
        dialog.setOnDismissListener(ignored->closeWorker());render();
    }
    void show(){dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);if(requestedOcrId!=null)presentOcr();}
    boolean isShowing(){return dialog.isShowing();}
    void close(){dialog.dismiss();closeWorker();}
    private void closeWorker(){if(closed)return;closed=true;generation++;if(ocrPrompt!=null)ocrPrompt.dismiss();engine.cancel();worker.shutdown();}

    /** Plain inbox review only imports text; screenshot confirmation may also request draft generation. */
    void importRecognizedText(String id){importRecognizedText(id,false);}
    void importRecognizedText(String id,boolean organizeNow){
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(()->importRecognizedText(id,organizeNow));return;}
        if(closed)return;requestedOcrId=id;organizeOcrNow=organizeNow;if(dialog.isShowing())presentOcr();
    }
    private void presentOcr(){
        if(closed||ocrPrompt!=null&&ocrPrompt.isShowing())return;
        if(busy){result.setText("识别文字已保留，当前操作完成后可点“查看待处理的识别文字”。");return;}
        try{
            OcrTextInbox.Entry entry=requestedOcrId==null?ocrInbox.peek(scope):ocrInbox.get(requestedOcrId);
            if(entry==null){requestedOcrId=null;updatePendingOcr();return;}
            if(!scope.equals(entry.scope)){requestedOcrId=null;result.setText("这段识别文字属于其他本机资料，当前输入和草稿没有改变。");updatePendingOcr();return;}
            if(hasImported(readState(),entry.id)){applyOcr(entry,true);return;}
            if(input.getText().toString().isEmpty()&&drafts().length()==0){applyOcr(entry,false);return;}
            ocrPrompt=new AlertDialog.Builder(context).setTitle("识别文字已保留")
                .setMessage("这里已有输入或未保存的草稿。追加只会把识别文字放入可编辑输入框，现有草稿继续保留，不会自动整理或保存日程。\n\n"+entry.text)
                .setNegativeButton("保留原稿，稍后处理",(d,w)->{requestedOcrId=null;organizeOcrNow=false;result.setText("原输入和草稿继续保留，可随时查看待处理的识别文字。");updatePendingOcr();})
                .setPositiveButton("追加文字",(d,w)->applyOcr(entry,true)).create();
            ocrPrompt.setOnDismissListener(d->ocrPrompt=null);ocrPrompt.show();
        }catch(Exception error){result.setText(safeMessage(error));}
    }
    private static boolean hasImported(JSONObject value,String id){JSONArray ids=value.optJSONArray("imported_ocr_ids");if(ids!=null)for(int i=0;i<ids.length();i++)if(id.equals(ids.optString(i)))return true;return false;}
    private void applyOcr(OcrTextInbox.Entry entry,boolean append){
        if(closed||busy)return;
        boolean shouldOrganize=false;
        try{
            synchronized(DRAFT_LOCK){
                OcrTextInbox.Entry pending=ocrInbox.get(entry.id);if(pending==null){requestedOcrId=null;updatePendingOcr();return;}
                if(!scope.equals(pending.scope))throw new IllegalArgumentException("识别文字与当前本机资料不匹配。");
                JSONObject latest=readState();String existing=latest.optString("text");JSONArray previousDrafts=latest.optJSONArray("drafts");
                if(!hasImported(latest,pending.id)){
                    if(!append&&(!existing.isEmpty()||previousDrafts!=null&&previousDrafts.length()>0)){state=latest;rendering=true;input.setText(latest.optString("text"));rendering=false;render();result.setText("当前内容已变化，识别文字仍保留，请再次选择追加。");return;}
                    String combined=existing.isEmpty()?pending.text:existing+"\n\n"+pending.text;
                    if(combined.length()>OcrTextInbox.MAX_TEXT_LENGTH){result.setText("追加后共有 "+combined.length()+" 字，一次最多2000字。请先修改输入或处理现有草稿；原稿和识别文字都保留，没有截断。");updatePendingOcr();return;}
                    JSONArray remembered=new JSONArray(),old=latest.optJSONArray("imported_ocr_ids");
                    if(old!=null)for(int i=0;i<old.length();i++){String id=old.optString(i);if(ocrInbox.get(id)!=null)remembered.put(id);}
                    remembered.put(pending.id);latest.put("text",combined).put("imported_ocr_ids",remembered);
                    shouldOrganize=organizeOcrNow&&pending.id.equals(requestedOcrId)&&existing.isEmpty()&&(previousDrafts==null||previousDrafts.length()==0);
                }
                // Commit before acknowledging the inbox. A replay retries this
                // commit first, so an uncertain storage write never consumes text.
                if(!preferences.edit().putString(scope,latest.toString()).commit())throw new IllegalStateException("识别文字尚未确认保存，请重试；待处理文字仍保留。");
                state=latest;rendering=true;input.setText(state.optString("text"));input.setSelection(input.length());rendering=false;
                ocrInbox.discard(pending.id);requestedOcrId=null;organizeOcrNow=false;
            }
            result.setText("识别文字已放入输入框，可继续修改。现有草稿保留；点击整理后才会生成新的草稿。");render();if(shouldOrganize)requestParse(false);
        }catch(Exception error){rendering=false;result.setText(safeMessage(error));updatePendingOcr();}
    }
    private void updatePendingOcr(){
        try{boolean pending=ocrInbox.peek(scope)!=null;pendingOcr.setVisibility(pending?View.VISIBLE:View.GONE);pendingOcr.setEnabled(!busy);}
        catch(Exception error){pendingOcr.setVisibility(View.VISIBLE);pendingOcr.setEnabled(!busy);}
    }
    private JSONObject readState(){synchronized(DRAFT_LOCK){try{JSONObject value=new JSONObject(preferences.getString(scope,"{}"));if(value.optJSONArray("drafts")==null)value.put("drafts",new JSONArray());return value;}catch(Exception error){return new JSONObject();}}}
    private JSONArray drafts(){JSONArray drafts=state.optJSONArray("drafts");if(drafts==null){drafts=new JSONArray();put(state,"drafts",drafts);}return drafts;}
    private void persist(){synchronized(DRAFT_LOCK){preferences.edit().putString(scope,state.toString()).apply();}}
    private void render(){
        if(closed)return;rendering=true;rows.removeAllViews();
        try{
            JSONObject settings=engine.settings();String parsedSource=state.optString("source");source.setText(parsedSource.isEmpty()?(settings.optBoolean("configured")?"使用手机自己的模型设置；配对设备不参与整理":"离线规则随时可用，也可在本机设置自己的模型"):("rules".equals(parsedSource)?"本次由手机离线规则整理":"本次由手机自己的模型连接整理"));
        }catch(Exception error){source.setText("手机离线规则可用，模型设置暂不可用");}
        StringBuilder notice=new StringBuilder();JSONArray notes=state.optJSONArray("warnings");if(notes!=null)for(int i=0;i<notes.length();i++){if(notice.length()>0)notice.append('\n');notice.append("• ").append(notes.optString(i));}
        warnings.setText(notice);warnings.setVisibility(notice.length()==0?View.GONE:View.VISIBLE);
        try{currentEvents=store.list(scope);}catch(Exception ignored){currentEvents=java.util.Collections.emptyList();}
        JSONArray drafts=drafts();
        for(int index=0;index<drafts.length();index++){
            JSONObject draft=drafts.optJSONObject(index);if(draft==null)continue;final String id=draft.optString("id");
            LinearLayout card=NativeUi.column(context);card.setTag(id);card.setBackgroundResource(R.drawable.panel_background);int p=NativeUi.dp(context,12);card.setPadding(p,p,p,p);LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.setMargins(0,NativeUi.dp(context,10),0,NativeUi.dp(context,8));rows.addView(card,margin);
            LinearLayout heading=NativeUi.row(context);CheckBox selected=new CheckBox(context);selected.setTag("draft_selected");selected.setChecked(draft.optBoolean("selected",true));selected.setContentDescription("保存这项草稿");selected.setEnabled(!busy);selected.setOnCheckedChangeListener((v,on)->{put(draft,"selected",on);persist();updateCommit();});heading.addView(selected);
            EditText title=NativeUi.field(context,"日程名称",View.NO_ID);title.setTag("draft_title");title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});title.setText(draft.optString("title"));title.setEnabled(!busy);title.addTextChangedListener(watch(()->{put(draft,"title",title.getText().toString());persist();}));heading.addView(title,NativeUi.weight(context));card.addView(heading);
            addTime(card,draft,"start_local","开始","draft_start");addTime(card,draft,"end_local","结束","draft_end");
            if(overlaps(draft)){TextView overlap=NativeUi.text(context,"与已有安排或其他草稿时间重叠，请调整后保存。",12);overlap.setTextColor(Color.rgb(160,78,0));card.addView(overlap);}
            Button remove=NativeUi.button(context,"移除这项草稿",false,()->{JSONArray next=new JSONArray();for(int i=0;i<drafts().length();i++){JSONObject item=drafts().optJSONObject(i);if(item!=null&&!id.equals(item.optString("id")))next.put(item);}put(state,"drafts",next);persist();render();});remove.setTag("draft_remove");remove.setEnabled(!busy);card.addView(remove);
        }
        if(drafts.length()==0)rows.addView(NativeUi.text(context,"整理结果会先出现在这里。离开页面后，输入和未保存的草稿都会保留。",14));
        input.setEnabled(!busy);parse.setEnabled(!busy);rules.setEnabled(!busy);updateCommit();updatePendingOcr();rendering=false;
    }
    private void addTime(LinearLayout card,JSONObject draft,String key,String label,String tag){
        LocalDateTime value;try{value=LocalDateTime.parse(draft.getString(key));}catch(Exception invalid){value=referenceDate.atTime(9,0);}
        final LocalDateTime current=value;Button button=NativeUi.button(context,label+"  "+NativeUi.time(value),false,()->NativeUi.pick(context,current,next->{
            if("start_local".equals(key)){try{Duration length=Duration.between(LocalDateTime.parse(draft.getString("start_local")),LocalDateTime.parse(draft.getString("end_local")));put(draft,"end_local",next.plus(length).format(LocalScheduleStore.LOCAL));}catch(Exception ignored){}}
            put(draft,key,next.format(LocalScheduleStore.LOCAL));persist();render();
        }));button.setTag(tag);button.setEnabled(!busy);card.addView(button);
    }
    private boolean overlaps(JSONObject draft){
        if(!draft.optBoolean("selected",true))return false;String start=draft.optString("start_local"),end=draft.optString("end_local");
        try{for(JSONObject event:currentEvents)if(!event.optBoolean("deleted")&&"planned".equals(event.optString("status"))&&start.compareTo(event.optString("end_local"))<0&&end.compareTo(event.optString("start_local"))>0)return true;}catch(Exception ignored){}
        for(int i=0;i<drafts().length();i++){JSONObject other=drafts().optJSONObject(i);if(other!=null&&other!=draft&&other.optBoolean("selected",true)&&start.compareTo(other.optString("end_local"))<0&&end.compareTo(other.optString("start_local"))>0)return true;}return false;
    }
    private void updateCommit(){if(commit==null)return;int count=0;for(int i=0;i<drafts().length();i++){JSONObject draft=drafts().optJSONObject(i);if(draft!=null&&draft.optBoolean("selected",true))count++;}commit.setText("保存已选（"+count+"）");commit.setEnabled(!busy&&count>0);}
    private void requestParse(boolean rulesOnly){
        if(busy)return;String text=input.getText().toString().trim();if(text.isEmpty()){input.setError("先写下要安排的事情");input.requestFocus();return;}
        if(drafts().length()>0){new AlertDialog.Builder(context).setTitle("重新整理草稿？").setMessage("新的整理结果会替换这里尚未保存的草稿。若整理失败，原草稿继续保留。").setNegativeButton("继续检查旧草稿",null).setPositiveButton("重新整理",(d,w)->parse(text,rulesOnly)).show();}
        else parse(text,rulesOnly);
    }
    private void parse(String text,boolean rulesOnly){
        busy=true;result.setText("正在本机整理，先生成草稿…");render();final long expected=++generation;
        worker.execute(()->{JSONObject response=null;String failure=null;try{List<JSONObject> busyEvents=store.list(scope);response=engine.parse(text,referenceDate.toString(),busyEvents,rulesOnly);}catch(Exception error){failure=safeMessage(error);}
            final JSONObject output=response;final String error=failure;handler.post(()->{if(closed||generation!=expected)return;busy=false;if(error!=null){result.setText(error+"\n可以直接使用离线规则，原输入和草稿都保留。");render();return;}
                try{JSONArray incoming=output.optJSONArray("drafts");if(incoming==null)incoming=output.optJSONArray("events");if(incoming==null)incoming=new JSONArray();if(incoming.length()>20)throw new IllegalArgumentException("一次最多整理20项，请分两次输入。");JSONArray next=new JSONArray();
                    for(int i=0;i<incoming.length();i++){JSONObject item=new JSONObject(incoming.getJSONObject(i).toString());item.put("id",UUID.randomUUID().toString()).put("selected",true).put("status","planned").put("deleted",false);next.put(item);}
                    put(state,"text",text);put(state,"drafts",next);put(state,"source",output.optString("source",output.optString("provider","rules")));JSONArray messages=output.optJSONArray("warnings");if(messages==null)messages=new JSONArray();JSONArray questions=output.optJSONArray("questions");if(questions!=null)for(int i=0;i<questions.length();i++)messages.put(questions.get(i));put(state,"warnings",messages);persist();
                    result.setText(next.length()==0?"需要补充一些信息，再点整理即可。":"已生成 "+next.length()+" 项草稿。可修改时间、名称或取消勾选，再确认保存。");render();
                }catch(Exception invalid){result.setText(safeMessage(invalid)+" 原草稿仍保留。");render();}
            });
        });
    }
    private void commit(){
        if(busy)return;final JSONArray batch=new JSONArray();try{
            for(int i=0;i<drafts().length();i++){JSONObject row=drafts().getJSONObject(i);if(!row.optBoolean("selected",true))continue;JSONObject value=commitPayload(row);JSONObject payload=new JSONObject(value.toString());payload.remove("id");AncestryReplica.payload(payload);batch.put(value);}
            if(batch.length()==0)return;if(batch.length()>20)throw new IllegalArgumentException("一次最多保存20项。");
        }catch(Exception error){result.setText("请检查草稿的名称和起止时间。"+safeMessage(error));return;}
        busy=true;result.setText("正在保存到手机…");render();
        worker.execute(()->{String failure=null;try{store.commitDrafts(scope,batch);acknowledge(batch);}catch(Exception error){failure=safeMessage(error);}final String error=failure;
            handler.post(()->{if(closed)return;busy=false;if(error!=null){result.setText("尚未保存："+error+"\n所有草稿都已保留，可调整后重试。");render();return;}state=readState();result.setText("已保存 "+batch.length()+" 项到本机日程，已配对设备可达后自动合并。");render();try{saved.accept(LocalDateTime.parse(batch.getJSONObject(0).getString("start_local")).toLocalDate());}catch(Exception ignored){saved.accept(referenceDate);}});
        });
    }
    private JSONObject commitPayload(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("title",row.getString("title").trim()).put("start_local",row.getString("start_local")).put("end_local",row.getString("end_local")).put("timezone","Asia/Shanghai").put("status","planned").put("deleted",false);}
    private void acknowledge(JSONArray batch)throws Exception{
        synchronized(DRAFT_LOCK){JSONObject current=readState();JSONArray existing=current.optJSONArray("drafts"),remaining=new JSONArray();if(existing==null)return;
            for(int i=0;i<existing.length();i++){JSONObject row=existing.getJSONObject(i);boolean saved=false;for(int j=0;j<batch.length();j++){JSONObject committed=batch.getJSONObject(j);if(row.optString("id").equals(committed.getString("id"))&&commitPayload(row).toString().equals(committed.toString())){saved=true;break;}}if(!saved)remaining.put(row);}
            current.put("drafts",remaining);preferences.edit().putString(scope,current.toString()).commit();
        }
    }
    private static TextWatcher watch(Runnable change){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){change.run();}public void afterTextChanged(Editable editable){}};}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String safeMessage(Exception error){String message=error.getMessage();return message==null||message.length()>300?"暂时无法完成，请稍后重试。":message;}
}
