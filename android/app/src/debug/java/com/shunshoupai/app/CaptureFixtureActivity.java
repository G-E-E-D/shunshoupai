package com.shunshoupai.app;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Synthetic content for local device acceptance. Not included in release builds. */
public final class CaptureFixtureActivity extends Activity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);if(getIntent().getBooleanExtra("protected",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(36,130,36,36);content.setBackgroundColor(Color.WHITE);
        TextView heading=new TextView(this);heading.setText("本机取字测试画面");heading.setTextColor(Color.BLACK);heading.setTextSize(24);content.addView(heading);
        TextView text=new TextView(this);text.setId(android.R.id.text1);text.setText("明天下午三点到四点开会");text.setTextColor(Color.BLACK);text.setTextSize(32);text.setPadding(0,120,0,100);text.setContentDescription("capture-fixture-text");content.addView(text);
        TextView footer=new TextView(this);footer.setText("这是验收用的虚构日程，不含个人资料。");footer.setTextColor(Color.DKGRAY);footer.setTextSize(18);content.addView(footer);setContentView(content);
    }
}
