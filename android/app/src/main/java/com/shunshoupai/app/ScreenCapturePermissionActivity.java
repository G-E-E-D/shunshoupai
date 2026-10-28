package com.shunshoupai.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

/** Android owns the consent UI; every new session obtains a new, single-use result. */
public final class ScreenCapturePermissionActivity extends ComponentActivity {
    static final String EXTRA_SCOPE="capture_scope";
    private String requestId,scope;
    private boolean requested,finished;
    private final ActivityResultLauncher<Intent> permission=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
        if(finished)return;finished=true;
        if(result.getResultCode()!=Activity.RESULT_OK||result.getData()==null||!Settings.canDrawOverlays(this)||!CaptureGrantGate.pending(requestId)){
            CaptureGrantGate.cancel(requestId);setResult(Activity.RESULT_CANCELED);Toast.makeText(this,"未开启屏幕取字，本机日程照常可用。",Toast.LENGTH_SHORT).show();finish();return;
        }
        try{Intent service=new Intent(this,ScreenCaptureService.class).setAction(ScreenCaptureService.ACTION_START).putExtra(EXTRA_SCOPE,scope).putExtra(ScreenCaptureService.EXTRA_REQUEST,requestId).putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,result.getResultCode()).putExtra(ScreenCaptureService.EXTRA_RESULT_DATA,result.getData());startForegroundService(service);setResult(Activity.RESULT_OK);}
        catch(RuntimeException denied){CaptureGrantGate.cancel(requestId);setResult(Activity.RESULT_CANCELED);Toast.makeText(this,"系统暂时不允许启动取字，请返回应用重试。",Toast.LENGTH_LONG).show();}
        finish();
    });
    @Override protected void onCreate(Bundle state){super.onCreate(state);TextView note=new TextView(this);note.setText("由系统请求屏幕捕获授权。你可以选择单个应用或整个屏幕；停止后下次会重新请求。");note.setPadding(40,60,40,40);note.setTextSize(17);setContentView(note);
        scope=state==null?getIntent().getStringExtra(EXTRA_SCOPE):state.getString(EXTRA_SCOPE);requested=state!=null&&state.getBoolean("requested");requestId=state==null?CaptureGrantGate.begin():state.getString("request_id");
        if(scope==null||scope.isEmpty()||requestId==null||!CaptureGrantGate.restore(requestId)||ScreenCaptureService.isRunning()||!Settings.canDrawOverlays(this)){finish();return;}
        if(!requested){requested=true;try{permission.launch(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());}catch(RuntimeException unavailable){CaptureGrantGate.cancel(requestId);finished=true;Toast.makeText(this,"此设备无法启动系统屏幕捕获。",Toast.LENGTH_LONG).show();finish();}}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString(EXTRA_SCOPE,scope);state.putString("request_id",requestId);state.putBoolean("requested",requested);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){if(!isChangingConfigurations()&&!finished)CaptureGrantGate.cancel(requestId);super.onDestroy();}
}
