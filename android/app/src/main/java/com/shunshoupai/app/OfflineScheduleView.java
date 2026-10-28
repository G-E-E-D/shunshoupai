package com.shunshoupai.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The launch screen is native and usable even before the first connection. */
final class OfflineScheduleView extends LinearLayout {
    private final LocalScheduleStore store;
    private final Runnable changed;
    private final TextView status;
    private final TextView identity;
    private final Button date;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final EditText search;
    private final SharedPreferences drafts;
    private final Button addButton;
    private final Button modelButton,reminderButton,captureButton;
    private final Button dayTab,weekTab,completedTab;
    private final LinearLayout dateRow;
    private final SharedPreferences uiState;
    private String mode="day";
    private String editingId;
    private AlertDialog activeEditor;
    private String rendered="";
    private String scope=LocalScheduleStore.GUEST;
    private LocalDate selected=LocalDate.now(LocalScheduleStore.ZONE);

    OfflineScheduleView(Context context,LocalScheduleStore store,Runnable connect,Runnable online,Runnable changed) {
        super(context);this.store=store;this.changed=changed;
        drafts=context.getSharedPreferences("offline-editor",Context.MODE_PRIVATE);
        uiState=context.getSharedPreferences("native-workbench",Context.MODE_PRIVATE);
        mode=uiState.getString("view","day");
        try{selected=LocalDate.parse(uiState.getString("date",selected.toString()));}catch(Exception ignored){}
        setOrientation(VERTICAL);setPadding(dp(18),dp(16),dp(18),dp(8));setBackgroundColor(Color.rgb(248,250,253));
        LinearLayout heading=row();TextView title=text("顺手排",26,true);heading.addView(title,weighted());
        modelButton=button("模型",()->{});modelButton.setId(R.id.offline_model_settings);modelButton.setContentDescription("本机模型设置");heading.addView(modelButton);
        captureButton=button("取字",()->{});captureButton.setId(R.id.offline_capture);captureButton.setContentDescription("悬浮屏幕取字设置");heading.addView(captureButton);
        reminderButton=button("提醒",()->{});reminderButton.setId(R.id.offline_reminders);reminderButton.setContentDescription("本机提醒设置");heading.addView(reminderButton);addView(heading);
        identity=text("本机日程 · 打开就能用",12,false);addView(identity);
        status=text("日程保存在本机，已配对设备可达后自动合并",12,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);addView(status);
        LinearLayout actions=row();
        addButton=button("＋ 新日程",()->edit(null));addButton.setId(R.id.offline_add);actions.addView(addButton,weighted());
        addButton.setBackgroundResource(R.drawable.button_background);addButton.setTextColor(Color.WHITE);
        Button intelligent=button("智能整理",online);intelligent.setId(R.id.offline_online);actions.addView(intelligent,weighted());
        Button connection=button("设备与同步",connect);connection.setId(R.id.offline_connect);actions.addView(connection,weighted());addView(actions);
        LinearLayout tabs=row();dayTab=button("日程",()->setMode("day"));dayTab.setId(R.id.offline_tab_day);tabs.addView(dayTab,weighted());
        weekTab=button("七天",()->setMode("week"));weekTab.setId(R.id.offline_tab_week);tabs.addView(weekTab,weighted());
        completedTab=button("已完成",()->setMode("completed"));completedTab.setId(R.id.offline_tab_completed);tabs.addView(completedTab,weighted());addView(tabs);
        dateRow=row();dateRow.addView(button("‹",()->showDate(selected.minusDays("week".equals(mode)?7:1))));
        date=button("",()->new NativeMonthDialog(getContext(),store,scope,selected,this::showDate).show());date.setId(R.id.offline_month);date.setContentDescription("打开月历并选择日期");
        dateRow.addView(date,weighted());dateRow.addView(button("›",()->showDate(selected.plusDays("week".equals(mode)?7:1))));dateRow.addView(button("今天",()->showDate(LocalDate.now(LocalScheduleStore.ZONE))));addView(dateRow);
        search=new EditText(context);search.setSingleLine(true);search.setHint("搜索全部日程");search.setTextSize(14);search.setContentDescription("搜索全部日程");search.setImeOptions(EditorInfo.IME_ACTION_DONE);addView(search);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){refresh();}public void afterTextChanged(Editable e){}});
        scroll=new ScrollView(context);list=new LinearLayout(context);list.setOrientation(VERTICAL);scroll.addView(list);addView(scroll,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));
        refresh();
    }

    void setCaptureAction(Runnable action){captureButton.setOnClickListener(v->action.run());}
    void setCaptureActive(boolean active){captureButton.setText(active?"取字中":"取字");captureButton.setContentDescription(active?"屏幕取字已开启，点此停止":"开启悬浮屏幕取字");}
    void setModelAction(Runnable action){modelButton.setOnClickListener(v->action.run());}
    void setReminderEnabled(boolean enabled){reminderButton.setText(enabled?"提醒开":"提醒");reminderButton.setContentDescription(enabled?"本机前台提醒已开启":"本机前台提醒已关闭");}
    void setReminderAction(Runnable action){reminderButton.setOnClickListener(v->action.run());}
    LocalDate selectedDate(){return selected;}
    void showDate(LocalDate value){selected=value;uiState.edit().putString("date",selected.toString()).apply();refresh();}
    private void setMode(String value){mode=value;uiState.edit().putString("view",mode).apply();refresh();}
    private void updateTabs(){Button[] buttons={dayTab,weekTab,completedTab};String[] modes={"day","week","completed"};for(int i=0;i<buttons.length;i++){boolean active=modes[i].equals(mode);buttons[i].setSelected(active);buttons[i].setBackgroundResource(active?R.drawable.button_background:R.drawable.tonal_button_background);buttons[i].setTextColor(active?Color.WHITE:getResources().getColor(R.color.accent,getContext().getTheme()));}}
    void setScope(String value){if(scope.equals(value))return;if(activeEditor!=null&&!store.canonicalScope(scope).equals(value))activeEditor.dismiss();scope=value;refresh();}
    void setStatus(String message){status.setText(message);refresh();}
    String scope(){return scope;}
    String editingId(){return editingId;}
    void restoreEditor(String id){if(id==null)return;try{if("new".equals(id))edit(null);else for(JSONObject event:store.list(scope))if(id.equals(event.getString("id"))){edit(event);break;}}catch(Exception error){showError();}}
    void destroy(){if(activeEditor!=null)activeEditor.dismiss();}
    private String draftKey(String draftScope,String id){return draftScope+"|"+id;}
    private String readDraft(String id){for(String candidate:store.draftScopes(scope)){String saved=drafts.getString(draftKey(candidate,id),null);if(saved!=null)return saved;}return null;}

    void refresh() {
        if(list==null)return;
        addButton.setText(readDraft("new")==null?"＋ 新日程":"继续草稿");
        identity.setText(store.isPrimary(scope)?"本机主日程 · 打开就能用":"旧资料 · 独立保留，仅供查看");
        addButton.setEnabled(store.isPrimary(scope));
        search.setHint("completed".equals(mode)?"搜索全部已完成日程":"搜索全部未完成日程");
        updateTabs();dateRow.setVisibility("completed".equals(mode)?View.GONE:View.VISIBLE);
        date.setText("week".equals(mode)?selected.format(DateTimeFormatter.ofPattern("M/d"))+" — "+selected.plusDays(6).format(DateTimeFormatter.ofPattern("M/d")):selected.format(DateTimeFormatter.ofPattern("M月d日 E",java.util.Locale.CHINA)));
        int scrollY=scroll.getScrollY();
        try {
            List<JSONObject> events=store.list(scope);String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);int shown=0,pending=0,conflicts=0;
            String fingerprint=mode+"|"+scope+"|"+selected+"|"+query+"|"+events.toString();
            if(fingerprint.equals(rendered))return;
            rendered=fingerprint;list.removeAllViews();
            for(JSONObject event:events){if(event.optBoolean("dirty"))pending++;if(hasConflict(event))conflicts++;}
            if(conflicts>0){TextView notice=text(conflicts+" 项日程需要合并确认，手机修改已保留",13,true);notice.setTextColor(Color.rgb(160,78,0));list.addView(notice);}
            else if(pending>0){boolean paired=false;for(JSONObject peer:store.peers())if(peer.optBoolean("enabled"))paired=true;list.addView(text(pending+(paired&&store.isPrimary(scope)?" 项修改等待同步":" 项修改已保存在本机"),12,false));}
            for(JSONObject event:events) {
                boolean conflict=hasConflict(event);
                if(event.optBoolean("deleted")&&!conflict)continue;
                boolean completed="completed".equals(event.optString("status"));
                if(!conflict && ("completed".equals(mode)!=completed))continue;
                String start=event.getString("start_local"),end=event.getString("end_local");
                if(!query.isEmpty()){if(!event.getString("title").toLowerCase(java.util.Locale.ROOT).contains(query))continue;}
                else if(!conflict && !"completed".equals(mode) && !(start.compareTo(selected.plusDays("week".equals(mode)?7:1).atStartOfDay().format(LocalScheduleStore.LOCAL))<0 && end.compareTo(selected.atStartOfDay().format(LocalScheduleStore.LOCAL))>0))continue;
                shown++;LinearLayout card=new LinearLayout(getContext());card.setOrientation(VERTICAL);card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackgroundResource(R.drawable.panel_background);
                LayoutParams margins=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);margins.topMargin=dp(10);list.addView(card,margins);
                LinearLayout heading=row();CheckBox done=new CheckBox(getContext());done.setChecked("completed".equals(event.getString("status")));done.setContentDescription("完成日程："+event.getString("title"));done.setEnabled(store.isPrimary(scope)&&!conflict&&!event.optBoolean("deleted"));
                done.setOnCheckedChangeListener((view,checked)->{try{JSONObject wanted=LocalScheduleStore.desired(event);wanted.put("status",checked?"completed":"planned");store.save(scope,event.getString("id"),wanted,event.getInt("base_version"));refresh();changed.run();}catch(Exception error){showError();}});
                heading.addView(done);TextView label=text(event.getString("title")+(event.optBoolean("deleted")?"（手机已删除）":""),17,true);heading.addView(label,weighted());card.addView(heading);
                card.addView(text(start.substring(5,10)+" "+start.substring(11,16)+" — "+(start.substring(0,10).equals(end.substring(0,10))?"":end.substring(5,10)+" ")+end.substring(11,16),14,false));
                if(overlaps(event,events)) {TextView warning=text("时间重叠",12,false);warning.setTextColor(Color.rgb(160,78,0));card.addView(warning);}
                LinearLayout buttons=row();buttons.addView(button(conflict?"处理合并":"编辑",()->{if(conflict)resolve(event);else edit(event);}),weighted());
                if(!conflict)buttons.addView(button("删除",()->new AlertDialog.Builder(getContext()).setTitle("删除这项日程？").setMessage(event.optString("title")).setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{try{JSONObject wanted=LocalScheduleStore.desired(event);wanted.put("deleted",true);store.save(scope,event.getString("id"),wanted,event.getInt("base_version"));refresh();changed.run();}catch(Exception error){showError();}}).show()),weighted());
                if(store.isPrimary(scope))card.addView(buttons);
            }
            if(shown==0){TextView empty=text(query.isEmpty()?("completed".equals(mode)?"还没有完成的日程\n完成一项安排后会在这里保留。":"week".equals(mode)?"这七天还没有安排\n写下一句话，手机就能帮你整理成草稿。":"这一天还没有安排\n点击「新日程」或「智能整理」，没联网也能用。"):"没有找到相关日程",16,false);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(48),0,0);list.addView(empty);}
            scroll.post(()->scroll.scrollTo(0,scrollY));
        } catch(Exception error){showError();}
    }

    private boolean overlaps(JSONObject event,List<JSONObject> events) {
        if(!"planned".equals(event.optString("status"))||event.optBoolean("deleted"))return false;
        for(JSONObject other:events)if(!event.optString("id").equals(other.optString("id"))&&!other.optBoolean("deleted")&&"planned".equals(other.optString("status"))
            &&event.optString("start_local").compareTo(other.optString("end_local"))<0&&event.optString("end_local").compareTo(other.optString("start_local"))>0)return true;
        return false;
    }

    private boolean hasConflict(JSONObject event) {return event.has("conflict") || (event.optJSONArray("replica_conflicts")!=null&&event.optJSONArray("replica_conflicts").length()>1);}
    private void resolve(JSONObject event) {
        org.json.JSONArray choices=event.optJSONArray("replica_conflicts");
        if(choices==null || choices.length()<2) return;
        String[] labels=new String[choices.length()];
        try {
            for(int i=0;i<choices.length();i++) {
                JSONObject head=choices.getJSONObject(i),value=head.getJSONObject("payload");
                labels[i]=value.getString("title")+"\n"+value.getString("start_local").replace('T',' ')+" — "+value.getString("end_local").replace('T',' ')+"\n"+stateLabel(value.getString("status"),value.getBoolean("deleted"))+" · "+head.optString("author","设备");
            }
            final int[] selected={-1};AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle("不同修改都已保留 · 选择保留版本")
                .setSingleChoiceItems(labels,-1,(d,which)->selected[0]=which).setNegativeButton("稍后",null).setPositiveButton("确认合并",null).create();
            dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(selected[0]<0){Toast.makeText(getContext(),"先选择要保留的版本",Toast.LENGTH_SHORT).show();return;}
                try{store.replica().resolve(scope,event.getString("id"),choices.getJSONObject(selected[0]).getJSONObject("payload"),event.getInt("version"));dialog.dismiss();refresh();changed.run();}
                catch(Exception error){dialog.dismiss();refresh();Toast.makeText(getContext(),"日程又有新修改，请重新查看后选择。所有版本仍保留。",Toast.LENGTH_LONG).show();}
            }));dialog.show();
        }catch(Exception error){showError();}
    }

    private String stateLabel(String value,boolean deleted){return deleted?"已删除":"completed".equals(value)?"已完成":"未完成";}
    private String remoteSummary(JSONObject remote){
        if(remote==null||!remote.has("id"))return "该日程已不存在";
        try{return remote.optString("title")+"\n"+LocalDateTime.ofInstant(java.time.Instant.parse(remote.getString("start_at")),LocalScheduleStore.ZONE).format(LocalScheduleStore.LOCAL).replace('T',' ')+" — "+LocalDateTime.ofInstant(java.time.Instant.parse(remote.getString("end_at")),LocalScheduleStore.ZONE).format(LocalScheduleStore.LOCAL).replace('T',' ')+"\n"+stateLabel(remote.optString("status"),!remote.isNull("deleted_at"));}catch(Exception ignored){return remote.optString("title");}
    }

    private void edit(JSONObject existing) {
        if(!store.isPrimary(scope))return;
        if(activeEditor!=null&&activeEditor.isShowing())return;
        String editingScope=scope;
        try {
            String draftId=existing==null?"new":existing.getString("id");
            editingId=draftId;
            LocalDateTime now=LocalDateTime.now(LocalScheduleStore.ZONE).withSecond(0).withNano(0);
            final LocalDateTime[] times={existing==null?selected.atTime(now.toLocalTime()).plusMinutes(30-now.getMinute()%30):LocalDateTime.parse(existing.getString("start_local")),existing==null?null:LocalDateTime.parse(existing.getString("end_local"))};
            if(times[1]==null)times[1]=times[0].plusHours(1);
            JSONObject savedDraft=readDraft(draftId)==null?null:new JSONObject(readDraft(draftId));
            final int baseVersion=savedDraft!=null?savedDraft.optInt("base_version",existing==null?0:existing.optInt("base_version")):existing==null?0:existing.getInt("base_version");
            if(savedDraft!=null){times[0]=LocalDateTime.parse(savedDraft.getString("start_local"));times[1]=LocalDateTime.parse(savedDraft.getString("end_local"));}
            LinearLayout form=new LinearLayout(getContext());form.setOrientation(VERTICAL);form.setPadding(dp(20),dp(8),dp(20),0);
            EditText title=new EditText(getContext());title.setHint("要做什么？");title.setTextSize(18);title.setSingleLine(true);title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});title.setText(savedDraft!=null?savedDraft.optString("title"):existing==null?"":existing.getString("title"));form.addView(title);
            Runnable persist=()->{try{drafts.edit().putString(draftKey(store.canonicalScope(editingScope),draftId),LocalScheduleStore.payload(title.getText().toString(),times[0],times[1],"planned",false).put("base_version",baseVersion).toString()).apply();}catch(Exception ignored){}};
            title.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){persist.run();}public void afterTextChanged(Editable e){}});
            Button start=button("",()->{}),end=button("",()->{});Runnable labels=()->{start.setText("开始  "+times[0].format(DateTimeFormatter.ofPattern("M月d日 HH:mm")));end.setText("结束  "+times[1].format(DateTimeFormatter.ofPattern("M月d日 HH:mm")));};labels.run();
            start.setOnClickListener(v->pick(times[0],value->{Duration duration=Duration.between(times[0],times[1]);times[0]=value;times[1]=value.plus(duration);labels.run();persist.run();}));
            end.setOnClickListener(v->pick(times[1],value->{times[1]=value;labels.run();persist.run();}));form.addView(start);form.addView(end);
            form.addView(text("上海时间 · 立即保存在本机",12,false));
            TextView error=text("",13,false);error.setTextColor(Color.rgb(179,38,30));form.addView(error);
            AlertDialog dialog=new AlertDialog.Builder(getContext()).setTitle(existing==null?"新日程":"编辑日程").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
            activeEditor=dialog;
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(ignored->{editingId=null;activeEditor=null;refresh();});
            dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String value=title.getText().toString().trim();long duration=Duration.between(times[0],times[1]).toMinutes();
                if(value.isEmpty()){title.setError("写下日程名称");title.requestFocus();return;}
                if(duration<=0||duration>1440||times[0].getYear()<2000||times[1].getYear()>2100){error.setText("结束时间需晚于开始，单次不超过 24 小时，日期范围为 2000–2100 年。");return;}
                try {store.save(editingScope,existing==null?null:existing.getString("id"),LocalScheduleStore.payload(value,times[0],times[1],existing==null?"planned":existing.getString("status"),false),baseVersion);SharedPreferences.Editor cleared=drafts.edit();for(String candidate:store.draftScopes(editingScope))cleared.remove(draftKey(candidate,draftId));cleared.apply();showDate(times[0].toLocalDate());changed.run();dialog.dismiss();}catch(Exception failure){error.setText(failure instanceof AncestryReplica.Invalid ? failure.getMessage()+"，草稿仍保留。" : "保存失败，请重试，内容仍在。");}
            }));dialog.show();title.requestFocus();
        } catch(Exception error){showError();}
    }

    private interface Picked {void accept(LocalDateTime value);}
    private void pick(LocalDateTime current,Picked result) {
        DatePickerDialog picker=new DatePickerDialog(getContext(),(view,year,month,day)->new TimePickerDialog(getContext(),(v,h,m)->result.accept(LocalDateTime.of(year,month+1,day,h,m)),current.getHour(),current.getMinute(),true).show(),current.getYear(),current.getMonthValue()-1,current.getDayOfMonth());
        picker.getDatePicker().setMinDate(LocalDate.of(2000,1,1).atStartOfDay(LocalScheduleStore.ZONE).toInstant().toEpochMilli());
        picker.getDatePicker().setMaxDate(LocalDate.of(2100,12,31).atStartOfDay(LocalScheduleStore.ZONE).toInstant().toEpochMilli());picker.show();
    }
    private TextView text(String value,int size,boolean strong){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(32,33,36));view.setPadding(0,dp(5),0,dp(5));if(strong)view.setTypeface(null,android.graphics.Typeface.BOLD);return view;}
    private Button button(String label,Runnable action){Button button=new Button(getContext());button.setText(label);button.setTextSize(13);button.setAllCaps(false);button.setMinHeight(dp(48));button.setMinimumWidth(dp(40));button.setMinWidth(dp(40));button.setBackgroundTintList(null);button.setBackgroundResource(R.drawable.tonal_button_background);button.setStateListAnimator(null);button.setTextColor(getResources().getColor(R.color.accent,getContext().getTheme()));button.setPadding(dp(8),dp(8),dp(8),dp(8));button.setOnClickListener(v->action.run());return button;}
    private LinearLayout row(){LinearLayout row=new LinearLayout(getContext());row.setOrientation(HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private LayoutParams weighted(){LayoutParams params=new LayoutParams(0,LayoutParams.WRAP_CONTENT,1);params.setMargins(dp(3),dp(4),dp(3),dp(4));return params;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void showError(){Toast.makeText(getContext(),"暂时无法读取或保存日程，请重试。",Toast.LENGTH_LONG).show();}
}
