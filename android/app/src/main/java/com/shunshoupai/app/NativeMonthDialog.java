package com.shunshoupai.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** A compact month overview displays local schedule counts without requesting another device. */
final class NativeMonthDialog {
    private final Context context;
    private final LocalScheduleStore store;
    private final String scope;
    private final LocalDate selected;
    private final Consumer<LocalDate> chosen;
    private final LinearLayout content;
    private final AlertDialog dialog;
    private YearMonth month;
    NativeMonthDialog(Context context,LocalScheduleStore store,String scope,LocalDate selected,Consumer<LocalDate> chosen){
        this.context=context;this.store=store;this.scope=scope;this.selected=selected;this.chosen=chosen;month=YearMonth.from(selected);
        content=NativeUi.column(context);int p=NativeUi.dp(context,14);content.setPadding(p,0,p,p);
        dialog=new AlertDialog.Builder(context).setTitle("月历 · 数字下方为未完成日程数").setView(content).setNegativeButton("返回",null).setPositiveButton("今天",(d,w)->chosen.accept(LocalDate.now(LocalScheduleStore.ZONE))).create();render();
    }
    void show(){dialog.show();}
    private void render(){
        content.removeAllViews();LinearLayout heading=NativeUi.row(context);
        heading.addView(NativeUi.button(context,"‹",false,()->{if(month.getYear()>2000||month.getMonthValue()>1){month=month.minusMonths(1);render();}}));
        TextView title=NativeUi.text(context,month.getYear()+"年"+month.getMonthValue()+"月",18);title.setGravity(Gravity.CENTER);heading.addView(title,NativeUi.weight(context));
        heading.addView(NativeUi.button(context,"›",false,()->{if(month.getYear()<2100||month.getMonthValue()<12){month=month.plusMonths(1);render();}}));content.addView(heading);
        LinearLayout names=NativeUi.row(context);for(String name:new String[]{"一","二","三","四","五","六","日"}){TextView label=NativeUi.text(context,name,13);label.setGravity(Gravity.CENTER);names.addView(label,NativeUi.weight(context));}content.addView(names);
        List<JSONObject> events;try{events=store.list(scope);}catch(Exception error){events=Collections.emptyList();}
        int offset=month.atDay(1).getDayOfWeek().getValue()-1;int cells=((offset+month.lengthOfMonth()+6)/7)*7;
        for(int index=0;index<cells;index+=7){LinearLayout week=NativeUi.row(context);for(int column=0;column<7;column++){
            int day=index+column-offset+1;
            if(day<1||day>month.lengthOfMonth()){week.addView(NativeUi.text(context,"",14),NativeUi.weight(context));continue;}
            LocalDate value=month.atDay(day);int count=0;String start=value.atStartOfDay().format(LocalScheduleStore.LOCAL),end=value.plusDays(1).atStartOfDay().format(LocalScheduleStore.LOCAL);
            for(JSONObject event:events)if(!event.optBoolean("deleted")&&"planned".equals(event.optString("status"))&&event.optString("start_local").compareTo(end)<0&&event.optString("end_local").compareTo(start)>0)count++;
            Button button=NativeUi.button(context,Integer.toString(day)+(count==0?"":"\n"+count+"项"),value.equals(selected),()->{chosen.accept(value);dialog.dismiss();});button.setTextSize(12);button.setPadding(0,NativeUi.dp(context,4),0,NativeUi.dp(context,4));button.setContentDescription(value+"，"+count+"项未完成日程");
            if(value.equals(LocalDate.now(LocalScheduleStore.ZONE))&&!value.equals(selected))button.setTextColor(Color.rgb(0,91,193));week.addView(button,NativeUi.weight(context));
        }content.addView(week);}
    }
}
