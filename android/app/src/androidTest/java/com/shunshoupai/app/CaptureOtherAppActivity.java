package com.shunshoupai.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public synthetic source in the instrumentation APK, never in the shipped application. */
public final class CaptureOtherAppActivity extends Activity {
    private static final String FINISH="com.shunshoupai.test.FINISH_CAPTURE_FIXTURE";
    @Override protected void onCreate(Bundle state){super.onCreate(state);if(FINISH.equals(getIntent().getAction())){finishAndRemoveTask();return;}LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(36,150,36,36);content.setBackgroundColor(Color.WHITE);TextView heading=new TextView(this);heading.setText("OCR Test Screen");heading.setTextColor(Color.BLACK);heading.setTextSize(24);content.addView(heading);TextView source=new TextView(this);source.setText("明天下午三点到四点开会");source.setTextColor(Color.BLACK);source.setTextSize(32);source.setPadding(0,120,0,100);content.addView(source);setContentView(content);}
    @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);if(FINISH.equals(intent.getAction()))finishAndRemoveTask();}
}
