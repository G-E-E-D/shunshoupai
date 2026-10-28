package com.shunshoupai.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent phone model configuration. Pairing does not grant access to these settings. */
final class NativeModelSettingsDialog {
    private final Context context;
    private final LocalPlanningEngine engine;
    private final Runnable changed;
    private final ModelSettingsDraft draft;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AlertDialog dialog;
    private final EditText base,model,key;
    private final CheckBox allowPrivate;
    private final Button save,test,clear,reload;
    private final TextView provider,result;
    private boolean closed,busy,loading;

    NativeModelSettingsDialog(Context context,LocalPlanningEngine engine,ModelSettingsDraft draft,Runnable changed){
        this.context=context;this.engine=engine;this.draft=draft;this.changed=changed;
        LinearLayout content=NativeUi.column(context);int p=NativeUi.dp(context,18);content.setPadding(p,0,p,p);
        content.addView(NativeUi.text(context,"手机自己连接模型服务。模型设置与密钥仅保存在本机，不随设备配对共享；没有配置也能使用离线规则。",14));
        provider=NativeUi.text(context,"",13);provider.setId(R.id.model_provider);content.addView(provider);
        content.addView(NativeUi.text(context,"OpenAI 兼容接口地址",13));base=NativeUi.field(context,"https://你的模型服务/v1",R.id.model_base_url);base.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);content.addView(base);
        content.addView(NativeUi.text(context,"模型名称",13));model=NativeUi.field(context,"服务商提供的模型 ID",R.id.model_name);content.addView(model);
        content.addView(NativeUi.text(context,"API 密钥",13));key=NativeUi.field(context,"已保存时可留空，保留原密钥",R.id.model_api_key);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);key.setSaveEnabled(false);content.addView(key);
        allowPrivate=new CheckBox(context);allowPrivate.setId(R.id.model_private_network);allowPrivate.setText("允许我指定的自建 HTTP / 局域网模型地址");allowPrivate.setTextSize(13);content.addView(allowPrivate);
        content.addView(NativeUi.text(context,"只填写你信任的服务地址。整理时会直接向该服务发送本次输入与用于避让冲突的本机日程时间；连接测试不会保存配置。",12));
        result=NativeUi.text(context,"",13);result.setTextColor(Color.rgb(128,69,0));result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(result);
        LinearLayout actions=NativeUi.row(context);test=NativeUi.button(context,"测试连接",false,()->run(false));test.setId(R.id.model_test);actions.addView(test,NativeUi.weight(context));save=NativeUi.button(context,"保存本机设置",true,()->run(true));save.setId(R.id.model_save);actions.addView(save,NativeUi.weight(context));content.addView(actions);
        reload=NativeUi.button(context,"重新读取已保存设置",false,()->new AlertDialog.Builder(context).setTitle("重新读取设置？").setMessage("当前未保存的修改将被替换。").setNegativeButton("继续编辑",null).setPositiveButton("重新读取",(d,w)->load(true)).show());content.addView(reload);
        clear=NativeUi.button(context,"清除模型配置，使用离线规则",false,this::confirmClear);clear.setId(R.id.model_clear_key);content.addView(clear);
        load(false);base.addTextChangedListener(watch(this::capture));model.addTextChangedListener(watch(this::capture));key.addTextChangedListener(watch(this::capture));allowPrivate.setOnCheckedChangeListener((button,value)->capture());
        ScrollView scroll=new ScrollView(context);scroll.addView(content);dialog=new AlertDialog.Builder(context).setTitle("本机模型设置").setView(scroll).setNegativeButton("完成",null).create();dialog.setCanceledOnTouchOutside(false);dialog.setOnDismissListener(ignored->closeWorker());
    }
    void show(){dialog.show();}
    boolean isShowing(){return dialog.isShowing();}
    void close(){dialog.dismiss();closeWorker();}
    private void closeWorker(){if(closed)return;closed=true;engine.cancel();worker.shutdown();}
    private void capture(){if(loading)return;draft.editRevision++;draft.baseUrl=base.getText().toString();draft.model=model.getText().toString();draft.apiKey=key.getText().toString();draft.allowPrivate=allowPrivate.isChecked();}
    private void load(boolean replace){
        loading=true;
        try{JSONObject info=engine.settings();if(!draft.loaded||replace){draft.baseUrl=info.optString("base_url");draft.model=info.optString("model");draft.apiKey="";draft.allowPrivate=info.optBoolean("allow_private_network");draft.version=info.getInt("version");draft.loaded=true;}
            base.setText(draft.baseUrl);model.setText(draft.model);key.setText(draft.apiKey);allowPrivate.setChecked(draft.allowPrivate);provider.setText(info.optBoolean("configured")?"已保存："+info.optString("model")+" · "+info.optString("key_hint","密钥已加密保存"):"当前使用离线规则，无需登录或连接电脑");
            if(!info.optBoolean("readable",true))result.setText(info.optString("warning","已保存的模型配置无法读取，请重新填写或清除后继续使用离线规则。"));
            else if(replace)result.setText("已重新读取本机保存的设置。");
        }catch(Exception error){result.setText(message(error));}finally{loading=false;}
    }
    private JSONObject request()throws Exception{capture();JSONObject value=new JSONObject().put("base_url",draft.baseUrl.trim()).put("model",draft.model.trim()).put("base_version",draft.version).put("allow_private_network",draft.allowPrivate);if(!draft.apiKey.isEmpty())value.put("api_key",draft.apiKey);return value;}
    private void busy(boolean value){busy=value;for(View control:new View[]{base,model,key,allowPrivate,save,test,clear,reload})control.setEnabled(!value);}
    private void run(boolean saving){if(busy)return;final JSONObject request;try{request=request();}catch(Exception error){result.setText(message(error));return;}final int editRevision=draft.editRevision;busy(true);result.setText(saving?"正在保存到手机…":"手机正在直接测试模型连接…");
        worker.execute(()->{JSONObject response=null;String failure=null;try{response=saving?engine.saveSettings(request):engine.testSettings(request);}catch(Exception error){failure=message(error);}JSONObject output=response;String error=failure;
            handler.post(()->{if(saving&&error==null&&draft.editRevision==editRevision){draft.loaded=false;draft.apiKey="";}if(closed)return;busy(false);if(error!=null){result.setText(error+"\n输入仍保留。若设置已在别处改变，可重新读取后确认。");return;}if(saving){load(true);result.setText("已保存到手机。配对设备不会获得这些设置或密钥。");changed.run();}else result.setText(output.optString("message","连接正常，尚未保存。"));});
        });
    }
    private void confirmClear(){if(busy)return;new AlertDialog.Builder(context).setTitle("清除本机模型配置？").setMessage("只删除这部手机保存的模型地址与密钥。日程、草稿和设备配对继续保留，可以随时使用离线规则。").setNegativeButton("保留配置",null).setPositiveButton("清除并使用规则",(dialog,which)->{
            busy(true);int baseVersion=draft.version,editRevision=draft.editRevision;worker.execute(()->{String failure=null;try{engine.clearSettings(baseVersion);}catch(Exception error){failure=message(error);}String error=failure;handler.post(()->{if(error==null&&draft.editRevision==editRevision){draft.loaded=false;draft.apiKey="";}if(closed)return;busy(false);if(error!=null){result.setText(error);return;}load(true);result.setText("已清除模型配置。手机离线规则继续可用。");changed.run();});});
        }).show();}
    private static TextWatcher watch(Runnable changed){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable text){}};}
    private static String message(Exception error){String value=error.getMessage();return value==null||value.length()>300?"暂时无法完成，请重试。":value;}
}
