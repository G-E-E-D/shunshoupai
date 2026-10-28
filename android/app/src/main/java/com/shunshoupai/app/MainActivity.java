package com.shunshoupai.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/** Every daily workflow is native and local. Pairing is only used to replicate schedules. */
public final class MainActivity extends ComponentActivity {
    private static final String PREFS="connection", SERVER_KEY="server_url";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private View setupScreen;
    private TextView connectionError;
    private Button connectButton;
    private OfflineScheduleView offlineScreen;
    private LocalScheduleStore scheduleStore;
    private ScheduleSync scheduleSync;
    private DeviceHostRuntime deviceHost;
    private DevicePairingDialog pairingDialog;
    private LocalPlanningEngine planningEngine;
    private ReminderScheduler reminders;
    private NativePlanningDialog planningDialog;
    private NativeModelSettingsDialog modelDialog;
    private boolean foreground,changingServer;
    private final Runnable syncTick=new Runnable(){@Override public void run(){if(!foreground)return;syncSchedules();handler.postDelayed(this,15000);}};
    private final ActivityResultLauncher<ScanOptions> qrScanner=registerForActivityResult(new ScanContract(),result->{
        if(result.getContents()!=null)handleScanResult(result.getContents());
        else if(result.getOriginalIntent()!=null&&result.getOriginalIntent().getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION,false))showScanHelp(R.string.scan_camera_denied);
        else handleScanResult(null);
    });

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_main);applyWindowInsets();
        preferences=getSharedPreferences(PREFS,MODE_PRIVATE);setupScreen=findViewById(R.id.setup_screen);connectionError=findViewById(R.id.connection_error);connectButton=findViewById(R.id.connect_button);
        scheduleStore=new LocalScheduleStore(this);scheduleStore.initialize(preferences.getString(SERVER_KEY,""));planningEngine=new LocalPlanningEngine(this);
        deviceHost=new DeviceHostRuntime(this,scheduleStore,handler,()->{if(isFinishing()||isDestroyed()||offlineScreen==null)return;offlineScreen.refresh();if(reminders!=null)reminders.refresh();if(setupScreen.getVisibility()==View.VISIBLE)renderPeers();});
        scheduleSync=new ScheduleSync(this,scheduleStore,handler,deviceHost,(origin,scope,message,required)->{if(changingServer||isDestroyed())return;offlineScreen.setStatus(message);if(reminders!=null)reminders.refresh();if(setupScreen.getVisibility()==View.VISIBLE)renderPeers();});
        offlineScreen=new OfflineScheduleView(this,scheduleStore,this::showConnectionSettings,this::showPlanning,this::syncSchedules);
        offlineScreen.setModelAction(this::showModelSettings);
        reminders=new ReminderScheduler(this,scheduleStore,message->{Toast.makeText(this,message,Toast.LENGTH_LONG).show();offlineScreen.setStatus(message);});
        offlineScreen.setReminderAction(this::showReminderSettings);offlineScreen.setReminderEnabled(reminders.isEnabled());
        offlineScreen.setId(R.id.offline_screen);offlineScreen.setScope(scheduleStore.activeScope());
        ((FrameLayout)findViewById(R.id.root)).addView(offlineScreen,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        connectButton.setOnClickListener(v->connectFromInput());findViewById(R.id.scan_button).setOnClickListener(v->startQrScanner());findViewById(R.id.show_pairing_code).setOnClickListener(v->showPairingCode());
        findViewById(R.id.paste_pairing_link).setOnClickListener(v->{EditText input=new EditText(this);input.setHint("粘贴另一台设备的配对链接");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setSingleLine(true);new AlertDialog.Builder(this).setTitle("粘贴配对链接").setView(input).setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->handleScanResult(input.getText().toString())).show();});
        findViewById(R.id.cancel_connection).setOnClickListener(v->{cancelPairing();showOfflineScreen();});findViewById(R.id.unpair_device).setOnClickListener(v->unpairDevice());findViewById(R.id.local_spaces).setOnClickListener(v->chooseLocalSpace());
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){@Override public void handleOnBackPressed(){if(changingServer)cancelPairing();if(offlineScreen.getVisibility()==View.VISIBLE)finish();else showOfflineScreen();}});
        showOfflineScreen();
        if(state!=null){offlineScreen.restoreEditor(state.getString("offline_edit_id"));String panel=state.getString("local_panel","");if("planning".equals(panel))showPlanning();else if("model".equals(panel))showModelSettings();}
    }
    private void syncSchedules(){if(reminders!=null)reminders.refresh();if(scheduleSync==null||changingServer||!foreground)return;scheduleSync.sync();}
    private void showOfflineScreen(){hideKeyboard();setupScreen.setVisibility(View.GONE);offlineScreen.setVisibility(View.VISIBLE);offlineScreen.refresh();syncSchedules();}
    private void showPlanning(){
        if(!scheduleStore.isPrimary(offlineScreen.scope())){Toast.makeText(this,"请回到主日程后整理，旧资料继续独立保留。",Toast.LENGTH_LONG).show();return;}
        if(planningDialog!=null)planningDialog.close();
        planningDialog=new NativePlanningDialog(this,scheduleStore,planningEngine,offlineScreen.scope(),java.time.LocalDate.now(LocalScheduleStore.ZONE),day->{offlineScreen.showDate(day);syncSchedules();});planningDialog.show();
    }
    private void showModelSettings(){if(modelDialog!=null)modelDialog.close();modelDialog=new NativeModelSettingsDialog(this,planningEngine,new androidx.lifecycle.ViewModelProvider(this).get(ModelSettingsDraft.class),()->offlineScreen.refresh());modelDialog.show();}
    private void showReminderSettings(){
        LinearLayout content=NativeUi.column(this);int p=NativeUi.dp(this,20);content.setPadding(p,0,p,p);
        content.addView(NativeUi.text(this,"开启后，日程开始前5分钟会在手机上提示。与桌面一样，仅在顺手排保持前台时提醒；锁屏或退出后不会保证通知。",14));
        android.widget.Switch enabled=new android.widget.Switch(this);enabled.setText("开启本机前台提醒");enabled.setTextSize(16);enabled.setMinHeight(NativeUi.dp(this,52));enabled.setChecked(reminders.isEnabled());content.addView(enabled);
        enabled.setOnCheckedChangeListener((button,value)->{reminders.setEnabled(value);offlineScreen.setReminderEnabled(value);});
        new AlertDialog.Builder(this).setTitle("本机提醒").setView(content).setPositiveButton("完成",null).show();
    }
    private void applyWindowInsets() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return windowInsets;
            });
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
            root.requestApplyInsets();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The controller can be unavailable during onCreate. Apply icon contrast
        // after the window attaches so edge-to-edge status icons stay readable.
        if (hasFocus && Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
        }
    }

    private void startQrScanner() {
        if (changingServer) return;
        hideKeyboard();
        connectionError.setVisibility(View.GONE);
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setCaptureActivity(QrCaptureActivity.class);
        options.setPrompt(getString(R.string.scan_prompt));
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        options.addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false);
        try {
            qrScanner.launch(options);
        } catch (ActivityNotFoundException | SecurityException error) {
            showScanHelp(R.string.scan_unavailable);
        }
    }

    /** QR data is untrusted input and always enters the same service validation flow. */
    void handleScanResult(String contents) {
        if (isFinishing() || isDestroyed() || changingServer) return;
        if (contents == null) {
            Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        final ScannedConnection scanned;
        try {
            scanned = ScannedConnection.parse(contents);
        } catch (IllegalArgumentException error) {
            showScanHelp(R.string.scan_invalid);
            return;
        }
        connectionError.setVisibility(View.GONE);
        if (!scanned.hasPairing) { showScanHelp(R.string.scan_pair_required); return; }
        showConnectionSettings();
        new AlertDialog.Builder(this).setTitle("与此设备共享日程？")
            .setMessage("配对会合并双方主日程和未来修改，并继续传播到各自已配对的设备。取消配对只停止之后的同步，无法撤回已经传出的副本。模型密钥和设置不会传播。请确认这是你在可信局域网中的设备。")
            .setNegativeButton("取消",null).setPositiveButton("同意配对并共享日程",(dialog,which)->pairDevice(scanned)).show();
    }

    private void showScanHelp(int message) {
        if (isFinishing() || isDestroyed()) return;
        connectionError.setText(message);
        connectionError.setVisibility(View.VISIBLE);
    }

    private void connectFromInput() {
        if (changingServer) return;
        showOfflineScreen();
        syncSchedules();
    }

    private boolean startDeviceHost() {
        try { deviceHost.start(); return true; }
        catch (Exception error) { offlineScreen.setStatus("本机日程可正常使用，暂时无法开启局域网接收"); return false; }
    }

    private void showPairingCode() {
        if (changingServer || !startDeviceHost()) return;
        if (pairingDialog != null) pairingDialog.dismiss();
        pairingDialog = new DevicePairingDialog(this, deviceHost.port(), deviceHost::issueTicket);
        pairingDialog.show();
    }

    private void pairDevice(ScannedConnection scanned) {
        hideKeyboard();
        // Scanner activities pause this activity; restart the callback endpoint before redeem.
        if (!startDeviceHost()) { showScanHelp(R.string.scan_unavailable); return; }
        if (!scheduleStore.isPrimary(scheduleStore.activeScope())) {
            new AlertDialog.Builder(this).setTitle("合并本机主日程")
                .setMessage("配对会合并主日程，并继续与其他已配对设备同步。当前查看的旧资料保持独立，可在资料列表中主动导入。")
                .setNegativeButton("取消", null).setPositiveButton("配对主日程", (dialog, which) -> {
                    scheduleStore.setActiveScope(scheduleStore.primaryScope());
                    offlineScreen.setScope(scheduleStore.primaryScope()); pairDevice(scanned);
                }).show(); return;
        }
        scheduleSync.invalidate();
        changingServer = true;
        connectButton.setEnabled(false);
        findViewById(R.id.scan_button).setEnabled(false);
        connectionError.setText("正在验证设备并配对…本机日程仍可使用。");
        connectionError.setVisibility(View.VISIBLE);
        scheduleSync.pair(scanned, (origin, scope, error) -> {
            if (isFinishing() || isDestroyed()) return;
            changingServer = false;
            connectButton.setEnabled(true);
            findViewById(R.id.scan_button).setEnabled(true);
            if (error != null) {
                connectionError.setText(error); connectionError.setVisibility(View.VISIBLE);
                return;
            }
            preferences.edit().putString(SERVER_KEY, origin).apply();
            scheduleStore.setActiveScope(scope);
            offlineScreen.setScope(scope);
            offlineScreen.setStatus("设备已配对，正在合并两端日程");
            showOfflineScreen();
        });
    }

    private void cancelPairing() {
        if (!changingServer) return;
        scheduleSync.invalidate(); changingServer = false;
        connectButton.setEnabled(true); findViewById(R.id.scan_button).setEnabled(true);
    }

    private void unpairDevice() {
        try { for (org.json.JSONObject peer : scheduleStore.peers()) if (peer.optBoolean("enabled")) { unpairDevice(peer); return; } }
        catch (Exception ignored) { }
    }

    private void unpairDevice(org.json.JSONObject peer) {
        new AlertDialog.Builder(this).setTitle("取消与「" + peer.optString("name") + "」的配对？")
            .setMessage("停止双方直接同步。本机已有日程继续保留；已传到其他已配对设备的内容也会保留。")
            .setNegativeButton("保留配对", null)
            .setPositiveButton("取消配对", (dialog, which) -> {
                cancelPairing();
                scheduleSync.unpair(peer.optString("peer_id"), error -> {
                    offlineScreen.setStatus(error == null ? "已取消配对，本机日程继续保留" : error);
                    renderPeers();
                });
                renderPeers();
            }).show();
    }

    private void chooseLocalSpace() {
        try {
            java.util.List<org.json.JSONObject> spaces = scheduleStore.spaces();
            String[] labels = new String[spaces.size()];
            for (int i = 0; i < spaces.size(); i++) labels[i] = scheduleStore.isPrimary(spaces.get(i).getString("scope")) ? "本机主日程 · 自动同步" : spaces.get(i).getString("label") + " · 旧资料 " + (i + 1);
            new AlertDialog.Builder(this).setTitle("本机保存的日程").setItems(labels, (dialog, which) -> {
                String scope=spaces.get(which).optString("scope");
                if (scheduleStore.isPrimary(scope)) { selectSpace(scope); return; }
                new AlertDialog.Builder(this).setTitle(labels[which]).setMessage("旧资料独立保留。导入后内容会合并进主日程，并同步到所有已配对设备；冲突会保留供你选择。")
                    .setNegativeButton("返回", null).setNeutralButton("查看旧资料", (d,w)->selectSpace(scope))
                    .setPositiveButton("导入主日程", (d,w)->{
                        connectionError.setText("正在导入，原资料会继续保留…"); connectionError.setVisibility(View.VISIBLE);
                        new Thread(()->{try { scheduleStore.replica().importFrom(scope); handler.post(()->{if(!isDestroyed())selectSpace(scheduleStore.primaryScope());}); }
                            catch(Exception error){handler.post(()->{if(!isDestroyed()){connectionError.setText("无法导入，原资料仍保留。");connectionError.setVisibility(View.VISIBLE);}});}},"calendar-import").start();
                    }).show();
            }).setNegativeButton("返回", null).show();
        } catch (Exception error) { Toast.makeText(this, "无法读取本机日程，请重试。", Toast.LENGTH_SHORT).show(); }
    }
    private void selectSpace(String scope) {
        scheduleStore.setActiveScope(scope); offlineScreen.setScope(scope);
        offlineScreen.setStatus(scheduleStore.isPrimary(scope) ? "主日程保存在本机，设备可达后自动合并" : "正在查看保留的旧资料，可在设备与同步中导入主日程");
        showOfflineScreen();
    }
    private void renderPeers() {
        try {
            LinearLayout rows=findViewById(R.id.peer_rows);rows.removeAllViews();int count=0;
            for(org.json.JSONObject peer:scheduleStore.peers()) if(peer.optBoolean("enabled")) {
                count++; TextView text=new TextView(this);text.setText(peer.optString("name")+" · "+scheduleSync.status(peer.optString("peer_id")));text.setTextSize(14);text.setPadding(0,12,0,4);rows.addView(text);
                Button remove=new Button(this);remove.setAllCaps(false);remove.setText("取消与此设备配对");remove.setOnClickListener(v->unpairDevice(peer));rows.addView(remove);
            }
            ((TextView)findViewById(R.id.paired_device)).setText("本机主日程 · "+count+" 台已配对设备\n"+(deviceHost.isRunning()?"前台接收已开启，双方保持应用打开即可合并":"应用回到前台后接收同步"));
            findViewById(R.id.unpair_device).setVisibility(View.GONE);
            findViewById(R.id.local_spaces).setVisibility(scheduleStore.spaces().size()>1?View.VISIBLE:View.GONE);
        } catch(Exception ignored) { }
    }
    private void showConnectionSettings() {
        hideKeyboard(); scheduleSync.invalidate();
        offlineScreen.setVisibility(View.GONE);setupScreen.setVisibility(View.VISIBLE);
        connectionError.setVisibility(View.GONE);renderPeers();
        findViewById(R.id.cancel_connection).setVisibility(View.VISIBLE);
    }

    private void hideKeyboard(){View focus=getCurrentFocus();if(focus!=null){((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("offline_edit_id",offlineScreen.editingId());state.putString("local_panel",planningDialog!=null&&planningDialog.isShowing()?"planning":modelDialog!=null&&modelDialog.isShowing()?"model":"");super.onSaveInstanceState(state);}
    @Override protected void onPause(){foreground=false;if(reminders!=null)reminders.onForeground(false);handler.removeCallbacks(syncTick);cancelPairing();if(scheduleSync!=null)scheduleSync.invalidate();if(pairingDialog!=null){pairingDialog.dismiss();pairingDialog=null;}if(deviceHost!=null)deviceHost.stop();super.onPause();}
    @Override protected void onResume(){super.onResume();foreground=true;if(reminders!=null)reminders.onForeground(true);startDeviceHost();if(setupScreen.getVisibility()==View.VISIBLE)renderPeers();handler.removeCallbacks(syncTick);handler.post(syncTick);}
    @Override protected void onDestroy(){if(reminders!=null)reminders.close();if(pairingDialog!=null)pairingDialog.dismiss();if(planningDialog!=null)planningDialog.close();if(modelDialog!=null)modelDialog.close();if(planningEngine!=null)planningEngine.cancel();if(deviceHost!=null)deviceHost.stop();if(offlineScreen!=null)offlineScreen.destroy();if(scheduleSync!=null)scheduleSync.close();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
