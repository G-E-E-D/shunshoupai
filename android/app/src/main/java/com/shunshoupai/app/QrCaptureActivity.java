package com.shunshoupai.app;

import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

import com.journeyapps.barcodescanner.CaptureActivity;

/** App-private camera screen. ZXing manages camera permission and decoding. */
public final class QrCaptureActivity extends CaptureActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            View content = findViewById(android.R.id.content);
            content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
            content.requestApplyInsets();
        }
    }
}
