package com.shunshoupai.app;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Shared native controls keep phone workflows readable and touch targets generous. */
final class NativeUi {
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context context){LinearLayout layout=new LinearLayout(context);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    static LinearLayout row(Context context){LinearLayout layout=new LinearLayout(context);layout.setOrientation(LinearLayout.HORIZONTAL);layout.setGravity(Gravity.CENTER_VERTICAL);return layout;}
    static LinearLayout.LayoutParams weight(Context context){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1);p.setMargins(dp(context,3),dp(context,4),dp(context,3),dp(context,4));return p;}
    static TextView text(Context context,String value,int size){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(Color.rgb(32,33,36));view.setPadding(0,dp(context,5),0,dp(context,5));return view;}
    static Button button(Context context,String label,boolean primary,Runnable action){Button button=new Button(context);button.setText(label);button.setTextSize(14);button.setAllCaps(false);button.setMinHeight(dp(context,48));button.setMinimumWidth(dp(context,40));button.setMinWidth(dp(context,40));button.setBackgroundTintList(null);button.setBackgroundResource(primary?R.drawable.button_background:R.drawable.tonal_button_background);button.setTextColor(primary?Color.WHITE:context.getResources().getColor(R.color.accent,context.getTheme()));button.setPadding(dp(context,10),dp(context,8),dp(context,10),dp(context,8));button.setStateListAnimator(null);button.setOnClickListener(v->action.run());return button;}
    static EditText field(Context context,String label,int id){EditText field=new EditText(context);field.setId(id);field.setHint(label);field.setContentDescription(label);field.setTextSize(16);field.setSingleLine(true);field.setMinHeight(dp(context,48));return field;}
    static String time(LocalDateTime value){return value.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"));}
    static void pick(Context context,LocalDateTime current,Consumer<LocalDateTime> chosen){
        DatePickerDialog dialog=new DatePickerDialog(context,(date,y,m,d)->new TimePickerDialog(context,(time,h,min)->chosen.accept(LocalDateTime.of(y,m+1,d,h,min)),current.getHour(),current.getMinute(),true).show(),current.getYear(),current.getMonthValue()-1,current.getDayOfMonth());
        dialog.getDatePicker().setMinDate(LocalDate.of(2000,1,1).atStartOfDay(LocalScheduleStore.ZONE).toInstant().toEpochMilli());
        dialog.getDatePicker().setMaxDate(LocalDate.of(2100,12,31).atStartOfDay(LocalScheduleStore.ZONE).toInstant().toEpochMilli());dialog.show();
    }
    private NativeUi(){}
}
