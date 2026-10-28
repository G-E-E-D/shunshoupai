package com.shunshoupai.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Insets;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.net.URI;

public final class MainActivity extends ComponentActivity {
    private static final String PREFS = "connection";
    private static final String SERVER_KEY = "server_url";
    private static final String STATE_SERVER = "trusted_server";
    private static final String STATE_ACTIVE = "web_active";
    private static final String STATE_COMPATIBILITY = "compatibility_rendering";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private View setupScreen;
    private View webScreen;
    private View errorPanel;
    private View loadingOverlay;
    private EditText serverInput;
    private TextView connectionError;
    private TextView errorMessage;
    private TextView serverSummary;
    private Button connectButton;
    private ProgressBar progress;
    private WebView webView;
    private String serverAddress = "";
    private String mainFrameUrl = "";
    private boolean mainFrameFailed;
    private boolean changingServer;
    private boolean pageReady;
    private boolean checkingContent;
    private boolean compatibilityRendering;
    private boolean clearHistoryOnReady;
    private boolean tryingLegacyRoot;
    private String pendingPairingFragment = "";
    private int loadGeneration;
    private OfflineScheduleView offlineScreen;
    private LocalScheduleStore scheduleStore;
    private ScheduleSync scheduleSync;
    private DeviceHostRuntime deviceHost;
    private DevicePairingDialog pairingDialog;
    private boolean foreground;
    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            syncSchedules();
            handler.postDelayed(this, 15_000);
        }
    };

    private final ActivityResultLauncher<ScanOptions> qrScanner = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) {
            handleScanResult(result.getContents());
        } else if (result.getOriginalIntent() != null
                && result.getOriginalIntent().getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false)) {
            showScanHelp(R.string.scan_camera_denied);
        } else {
            handleScanResult(null);
        }
    });

    private final Runnable loadTimeout = () -> {
        if (webView != null && !pageReady && !mainFrameFailed) {
            webView.stopLoading();
            showLoadError(getString(R.string.webview_not_ready));
        }
    };
    private final Runnable contentCheck = this::checkContentReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setupScreen = findViewById(R.id.setup_screen);
        webScreen = findViewById(R.id.web_screen);
        errorPanel = findViewById(R.id.error_panel);
        loadingOverlay = findViewById(R.id.loading_overlay);
        serverInput = findViewById(R.id.server_url);
        connectionError = findViewById(R.id.connection_error);
        errorMessage = findViewById(R.id.error_message);
        serverSummary = findViewById(R.id.server_summary);
        connectButton = findViewById(R.id.connect_button);
        progress = findViewById(R.id.loading_progress);
        webView = findViewById(R.id.web_view);
        compatibilityRendering = state != null && state.getBoolean(STATE_COMPATIBILITY, false);
        configureWebView();

        scheduleStore = new LocalScheduleStore(this);
        scheduleStore.initialize(preferences.getString(SERVER_KEY, ""));
        deviceHost = new DeviceHostRuntime(this, scheduleStore, handler, () -> {
            if (isFinishing() || isDestroyed() || offlineScreen == null) return;
            offlineScreen.refresh();
            if (setupScreen.getVisibility() == View.VISIBLE) renderPeers();
        });
        scheduleSync = new ScheduleSync(this, scheduleStore, handler, deviceHost, (origin, scope, message, pairingRequired) -> {
            if (changingServer) return;
            offlineScreen.setStatus(message);
            if (setupScreen.getVisibility() == View.VISIBLE) renderPeers();
        });
        offlineScreen = new OfflineScheduleView(this, scheduleStore, this::showConnectionSettings, this::reloadService, this::syncSchedules);
        offlineScreen.setId(R.id.offline_screen);
        offlineScreen.setScope(scheduleStore.activeScope());
        ((FrameLayout) findViewById(R.id.root)).addView(offlineScreen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        connectButton.setOnClickListener(view -> connectFromInput());
        findViewById(R.id.scan_button).setOnClickListener(view -> startQrScanner());
        findViewById(R.id.paste_pairing_link).setOnClickListener(view -> {
            EditText input=new EditText(this);input.setHint("粘贴另一台设备的配对链接");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setSingleLine(true);
            new AlertDialog.Builder(this).setTitle("粘贴配对链接").setView(input).setNegativeButton("取消",null).setPositiveButton("继续",(dialog,which)->handleScanResult(input.getText().toString())).show();
        });
        findViewById(R.id.show_pairing_code).setOnClickListener(view -> showPairingCode());
        serverInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { connectFromInput(); return true; }
            return false;
        });
        findViewById(R.id.toolbar_back).setOnClickListener(view -> navigateBack());
        findViewById(R.id.toolbar_refresh).setOnClickListener(view -> reloadService());
        findViewById(R.id.toolbar_settings).setOnClickListener(view -> showConnectionSettings());
        findViewById(R.id.error_retry).setOnClickListener(view -> {
            enableCompatibilityRendering();
            reloadService();
        });
        findViewById(R.id.toolbar_refresh).setOnLongClickListener(view -> {
            enableCompatibilityRendering();
            reloadService();
            Toast.makeText(this, R.string.compatibility_rendering, Toast.LENGTH_SHORT).show();
            return true;
        });
        findViewById(R.id.error_settings).setOnClickListener(view -> showConnectionSettings());
        findViewById(R.id.cancel_connection).setOnClickListener(view -> { cancelPairing(); showOfflineScreen(); });
        findViewById(R.id.unpair_device).setOnClickListener(view -> unpairDevice());
        findViewById(R.id.local_spaces).setOnClickListener(view -> chooseLocalSpace());
        findViewById(R.id.error_offline).setOnClickListener(view -> showOfflineScreen());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });

        String saved = preferences.getString(SERVER_KEY, "");
        if (saved != null && !saved.isEmpty()) {
            try {
                serverAddress = ServerAddress.normalize(saved);
                serverInput.setText(serverAddress);
            } catch (IllegalArgumentException error) {
                preferences.edit().remove(SERVER_KEY).apply();
                serverAddress = "";
            }
        }
        if (!preferences.getBoolean("device_pairing_migrated", false)) {
            CookieManager.getInstance().removeAllCookies(ignored -> CookieManager.getInstance().flush());
            preferences.edit().putBoolean("device_pairing_migrated", true).apply();
        }
        showOfflineScreen();
        if(state!=null)offlineScreen.restoreEditor(state.getString("offline_edit_id"));
    }

    private void syncSchedules() {
        if (scheduleSync == null || changingServer || !foreground || offlineScreen == null) return;
        if(webScreen.getVisibility()!=View.VISIBLE) scheduleSync.sync();
    }

    private void showOfflineScreen() {
        hideKeyboard();
        setupScreen.setVisibility(View.GONE);
        webScreen.setVisibility(View.GONE);
        offlineScreen.setVisibility(View.VISIBLE);
        offlineScreen.refresh();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.onPause();
            pageReady=false;
            handler.removeCallbacks(loadTimeout);
            handler.removeCallbacks(contentCheck);
        }
        syncSchedules();
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

    @SuppressWarnings("deprecation")
    // React requires JavaScript. Navigation is restricted to the configured
    // origin, file/content access is disabled, and no native JS bridge exists.
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setSafeBrowsingEnabled(true);
        settings.setSaveFormData(false);
        settings.setSavePassword(false);
        settings.setUserAgentString(settings.getUserAgentString() + " ShunshoupaiAndroid/" + BuildConfig.VERSION_NAME);
        if (compatibilityRendering) webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (view != webView) return;
                // Resource loading at 100% does not prove React hydrated or
                // produced a visible workbench. Keep the native loader until
                // the explicit DOM readiness check succeeds.
                progress.setProgress(pageReady ? 100 : Math.min(value, 95));
            }

            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMessage) {
                if (view != webView) return false;
                // User-tapped target=_blank links open in the browser, never in a
                // second privileged WebView. Script-created windows are denied.
                String destination = view.getHitTestResult().getExtra();
                if (userGesture && destination != null) openExternal(destination);
                return false;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != webView) return true;
                String destination = request.getUrl().toString();
                if (ServerAddress.sameOrigin(serverAddress, destination)) return false;
                if (request.isForMainFrame() && request.hasGesture()) openExternal(destination);
                else if (request.isForMainFrame()) showLoadError(getString(R.string.unsafe_redirect));
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (view != webView || changingServer || "about:blank".equals(url)) return;
                if (!ServerAddress.sameOrigin(serverAddress, url)) {
                    view.stopLoading();
                    showLoadError(getString(R.string.unsafe_redirect));
                    return;
                }
                mainFrameUrl = url.split("#", 2)[0];
                loadGeneration++;
                checkingContent = false;
                pageReady = false;
                mainFrameFailed = false;
                errorPanel.setVisibility(View.GONE);
                loadingOverlay.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                progress.setVisibility(View.VISIBLE);
                handler.removeCallbacks(loadTimeout);
                handler.removeCallbacks(contentCheck);
                handler.postDelayed(loadTimeout, 25_000);
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                if (view == webView && !changingServer && ServerAddress.sameOrigin(serverAddress, url)) checkContentReady();
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view != webView || changingServer || !ServerAddress.sameOrigin(serverAddress, url)) return;
                CookieManager.getInstance().flush();
                if (!mainFrameFailed) checkContentReady();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == webView && request.isForMainFrame() && currentDocument(request.getUrl().toString())) showLoadError(getString(R.string.error_default));
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view != webView || !request.isForMainFrame() || !currentDocument(request.getUrl().toString())) return;
                String path = request.getUrl().getPath();
                if (response.getStatusCode() == 404 && !tryingLegacyRoot && ("/mobile".equals(path) || "/mobile/".equals(path))) {
                    // Earlier installed desktop versions only serve the app at
                    // their root. Retry that exact trusted origin once; retain
                    // this one-use fragment in memory only for this request.
                    tryingLegacyRoot = true;
                    view.stopLoading();
                    loadWorkspaceUrl(serverAddress + pendingPairingFragment);
                    return;
                }
                showLoadError(getString(R.string.http_error, response.getStatusCode()));
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
                sslHandler.cancel();
                if (view != webView) return;
                if (error.getUrl().equals(mainFrameUrl) || error.getUrl().equals(view.getUrl())) {
                    showLoadError(getString(R.string.ssl_error));
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != webView) return true;
                compatibilityRendering = true;
                loadGeneration++;
                handler.removeCallbacks(loadTimeout);
                handler.removeCallbacks(contentCheck);
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                view.destroy();
                webView = null;
                showLoadError(getString(R.string.webview_crashed));
                return true;
            }
        });
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
        serverInput.setText(scanned.serviceRoot);
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
        if (webView != null) { webView.stopLoading(); webView.loadUrl("about:blank"); }
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
            serverAddress = origin;
            preferences.edit().putString(SERVER_KEY, origin).apply();
            serverInput.setText(origin);
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
        offlineScreen.setVisibility(View.GONE);webScreen.setVisibility(View.GONE);setupScreen.setVisibility(View.VISIBLE);
        connectionError.setVisibility(View.GONE);renderPeers();
        findViewById(R.id.cancel_connection).setVisibility(View.VISIBLE);
        if(webView!=null){webView.stopLoading();webView.onPause();}
    }

    private void showWebScreen() {
        if (serverAddress.isEmpty()) return;
        hideKeyboard();
        if(webScreen.getVisibility()!=View.VISIBLE)scheduleSync.invalidate();
        offlineScreen.setVisibility(View.GONE);
        setupScreen.setVisibility(View.GONE);
        webScreen.setVisibility(View.VISIBLE);
        try {
            URI uri = URI.create(serverAddress);
            serverSummary.setText(getString(uri.getScheme().equals("https")
                ? R.string.server_summary_https : R.string.server_summary_http, uri.getRawAuthority()));
        } catch (IllegalArgumentException ignored) { serverSummary.setText(R.string.empty_server); }
        if (webView != null) webView.onResume();
    }

    private void reloadService() {
        if(changingServer)return;
        try {
            java.util.List<org.json.JSONObject> computers=new java.util.ArrayList<>();
            for(org.json.JSONObject peer:scheduleStore.peers())if(peer.optBoolean("enabled")&&peer.getJSONObject("capabilities").optBoolean("web"))computers.add(peer);
            if(computers.isEmpty()){showConnectionSettings();connectionError.setText("电脑工具需要一台已配对的电脑。手机之间仍可直接同步日程。");connectionError.setVisibility(View.VISIBLE);return;}
            if(webScreen.getVisibility()==View.VISIBLE)for(org.json.JSONObject peer:computers)if(serverAddress.equals(peer.getString("origin"))){openComputer(peer);return;}
            if(computers.size()==1){openComputer(computers.get(0));return;}
            String[] names=new String[computers.size()];for(int i=0;i<names.length;i++)names[i]=computers.get(i).getString("name");
            new AlertDialog.Builder(this).setTitle("选择运行工具的电脑").setItems(names,(dialog,which)->openComputer(computers.get(which))).setNegativeButton("返回",null).show();
        }catch(Exception error){showConnectionSettings();}
    }
    private void openComputer(org.json.JSONObject peer) {
        serverAddress=peer.optString("origin");
        scheduleSync.openWeb(peer.optString("peer_id"),error->{
            if(isFinishing()||isDestroyed())return;
            if(error!=null){showConnectionSettings();connectionError.setText(error);connectionError.setVisibility(View.VISIBLE);}
            else reloadService(null);
        });
    }

    private void reloadService(String initialUrl) {
        if (serverAddress.isEmpty() || changingServer) return;
        if (webView == null) {
            webView = new WebView(this);
            webView.setId(R.id.web_view);
            FrameLayout container = findViewById(R.id.web_container);
            container.addView(webView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            configureWebView();
        }
        showWebScreen();
        pendingPairingFragment = "";
        tryingLegacyRoot = false;
        loadWorkspaceUrl(serverAddress + "mobile");
    }

    private void loadWorkspaceUrl(String url) {
        loadGeneration++;
        mainFrameUrl = url.split("#", 2)[0];
        pageReady = false;
        checkingContent = false;
        mainFrameFailed = false;
        errorPanel.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        handler.removeCallbacks(loadTimeout);
        handler.postDelayed(loadTimeout, 25_000);
        webView.loadUrl(url);
    }

    private boolean currentDocument(String url) {
        return url != null && url.split("#", 2)[0].equals(mainFrameUrl);
    }

    private void enableCompatibilityRendering() {
        compatibilityRendering = true;
        if (webView != null) webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    private void checkContentReady() {
        if (webView == null || changingServer || mainFrameFailed || pageReady || checkingContent) return;
        final WebView expected = webView;
        final int generation = loadGeneration;
        if (!ServerAddress.sameOrigin(serverAddress, expected.getUrl())) return;
        checkingContent = true;
        try { expected.evaluateJavascript("(function(){"
            + "if(document.querySelector('[data-shunshoupai-error]'))return 'error';"
            + "var app=document.querySelector('.app-shell');"
            + "if(document.documentElement.getAttribute('data-shunshoupai-ready')==='true'&&app&&app.getBoundingClientRect().height>0)return 'ready';"
            + (tryingLegacyRoot ? "if(app&&app.getBoundingClientRect().height>0){var buttons=app.querySelectorAll('header button');for(var i=0;i<buttons.length;i++){var keys=Object.keys(buttons[i]);for(var j=0;j<keys.length;j++){if(keys[j].indexOf('__reactProps$')===0&&buttons[i][keys[j]]&&typeof buttons[i][keys[j]].onClick==='function')return 'ready'}}}" : "")
            + "if(!document.querySelector('script[src*=\"_next\"]')&&document.body&&document.body.innerText.trim().length>0)return 'ready';"
            + "return 'waiting';})()", value -> {
                checkingContent = false;
                if (isFinishing() || isDestroyed() || expected != webView || generation != loadGeneration || changingServer || mainFrameFailed) return;
                if ("\"ready\"".equals(value)) {
                    // DOM readiness and a drawable WebView frame are separate.
                    // Keep the visible native loading state until Chromium has
                    // committed a frame reflecting that DOM (API 23+, min 26).
                    checkingContent = true;
                    expected.postVisualStateCallback(generation, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) {
                            checkingContent = false;
                            if (isFinishing() || isDestroyed() || expected != webView || generation != loadGeneration || changingServer || mainFrameFailed) return;
                            pageReady = true;
                            pendingPairingFragment = "";
                            handler.removeCallbacks(loadTimeout);
                            handler.removeCallbacks(contentCheck);
                            progress.setVisibility(View.GONE);
                            loadingOverlay.setVisibility(View.GONE);
                            errorPanel.setVisibility(View.GONE);
                            if (clearHistoryOnReady) { expected.clearHistory(); clearHistoryOnReady = false; }
                            expected.requestLayout();
                            expected.invalidate();
                            handler.postDelayed(MainActivity.this::syncSchedules, 1_000);
                        }
                    });
                } else if ("\"error\"".equals(value)) showLoadError(getString(R.string.webview_not_ready));
                else { handler.removeCallbacks(contentCheck); handler.postDelayed(contentCheck, 400); }
            });
        } catch (IllegalStateException unavailableRenderer) {
            checkingContent = false;
            showLoadError(getString(R.string.webview_crashed));
        }
    }

    private void showLoadError(String message) {
        mainFrameFailed = true;
        pendingPairingFragment = "";
        handler.removeCallbacks(loadTimeout);
        handler.removeCallbacks(contentCheck);
        progress.setVisibility(View.GONE);
        loadingOverlay.setVisibility(View.GONE);
        errorMessage.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openExternal(String destination) {
        try {
            URI uri = URI.create(destination);
            String root = ServerAddress.normalize(uri.getScheme() + "://" + uri.getRawAuthority());
            if (!ServerAddress.sameOrigin(root, destination)) {
                Toast.makeText(this, R.string.blocked_navigation, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(destination)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, R.string.blocked_navigation, Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.external_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private void navigateBack() {
        if (changingServer) cancelPairing();
        if (offlineScreen.getVisibility() == View.VISIBLE) finish();
        else showOfflineScreen();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("offline_edit_id",offlineScreen.editingId());
        state.putString(STATE_SERVER, serverAddress);
        state.putBoolean(STATE_ACTIVE, webScreen.getVisibility() == View.VISIBLE);
        state.putBoolean(STATE_COMPATIBILITY, compatibilityRendering);
        // Reload /mobile using its persistent HttpOnly cookie after recreation.
        // Never save WebView display state or a one-time QR URL into the Bundle.
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        foreground = false;
        cancelPairing();
        if(scheduleSync!=null)scheduleSync.invalidate();
        if(pairingDialog!=null){pairingDialog.dismiss();pairingDialog=null;}
        if(deviceHost!=null)deviceHost.stop();
        handler.removeCallbacks(syncTick);
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        startDeviceHost();
        if(setupScreen.getVisibility()==View.VISIBLE)renderPeers();
        handler.removeCallbacks(syncTick);
        handler.post(syncTick);
        if (webView != null && webScreen != null && webScreen.getVisibility() == View.VISIBLE) {
            webView.onResume();
            webView.invalidate();
            webView.requestLayout();
            checkContentReady();
        }
    }

    @Override protected void onDestroy() {
        if(pairingDialog!=null)pairingDialog.dismiss();
        if(deviceHost!=null)deviceHost.stop();
        if (offlineScreen != null) offlineScreen.destroy();
        if (scheduleSync != null) scheduleSync.close();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
