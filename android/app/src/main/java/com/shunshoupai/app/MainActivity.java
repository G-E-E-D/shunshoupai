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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private View setupScreen;
    private View webScreen;
    private View errorPanel;
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
        if (webView != null && progress.getVisibility() == View.VISIBLE) {
            webView.stopLoading();
            showLoadError(getString(R.string.connection_timeout));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        applyWindowInsets();
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setupScreen = findViewById(R.id.setup_screen);
        webScreen = findViewById(R.id.web_screen);
        errorPanel = findViewById(R.id.error_panel);
        serverInput = findViewById(R.id.server_url);
        connectionError = findViewById(R.id.connection_error);
        errorMessage = findViewById(R.id.error_message);
        serverSummary = findViewById(R.id.server_summary);
        connectButton = findViewById(R.id.connect_button);
        progress = findViewById(R.id.loading_progress);
        webView = findViewById(R.id.web_view);
        configureWebView();

        connectButton.setOnClickListener(view -> connectFromInput());
        findViewById(R.id.scan_button).setOnClickListener(view -> startQrScanner());
        serverInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) { connectFromInput(); return true; }
            return false;
        });
        findViewById(R.id.toolbar_back).setOnClickListener(view -> navigateBack());
        findViewById(R.id.toolbar_refresh).setOnClickListener(view -> reloadService());
        findViewById(R.id.toolbar_settings).setOnClickListener(view -> showConnectionSettings());
        findViewById(R.id.error_retry).setOnClickListener(view -> reloadService());
        findViewById(R.id.error_settings).setOnClickListener(view -> showConnectionSettings());
        findViewById(R.id.cancel_connection).setOnClickListener(view -> showWebScreen());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });

        String saved = preferences.getString(SERVER_KEY, "");
        if (saved != null && !saved.isEmpty()) {
            try {
                serverAddress = ServerAddress.normalize(saved);
                serverInput.setText(serverAddress);
                showWebScreen();
                boolean restored = state != null
                    && state.getBoolean(STATE_ACTIVE, false)
                    && serverAddress.equals(state.getString(STATE_SERVER))
                    && webView.restoreState(state) != null;
                if (!restored) webView.loadUrl(serverAddress);
                if (state != null && !state.getBoolean(STATE_ACTIVE, true)) showConnectionSettings();
            } catch (IllegalArgumentException error) {
                preferences.edit().remove(SERVER_KEY).apply();
                serverAddress = "";
                showConnectionSettings();
            }
        }
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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                if (view != webView) return;
                progress.setProgress(value);
                if (value == 100) progress.setVisibility(View.GONE);
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
                mainFrameUrl = url;
                mainFrameFailed = false;
                errorPanel.setVisibility(View.GONE);
                progress.setProgress(0);
                progress.setVisibility(View.VISIBLE);
                handler.removeCallbacks(loadTimeout);
                handler.postDelayed(loadTimeout, 25_000);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (view != webView || changingServer || !ServerAddress.sameOrigin(serverAddress, url)) return;
                handler.removeCallbacks(loadTimeout);
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (!mainFrameFailed) errorPanel.setVisibility(View.GONE);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == webView && request.isForMainFrame()) showLoadError(getString(R.string.error_default));
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (view == webView && request.isForMainFrame()) showLoadError(getString(R.string.http_error, response.getStatusCode()));
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
                handler.removeCallbacks(loadTimeout);
                ((ViewGroup) view.getParent()).removeView(view);
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
        final String scanned;
        try {
            scanned = ServerAddress.normalize(contents);
        } catch (IllegalArgumentException error) {
            showScanHelp(R.string.scan_invalid);
            return;
        }
        serverInput.setText(scanned);
        connectionError.setVisibility(View.GONE);
        connectFromInput();
    }

    private void showScanHelp(int message) {
        if (isFinishing() || isDestroyed()) return;
        connectionError.setText(message);
        connectionError.setVisibility(View.VISIBLE);
    }

    private void connectFromInput() {
        if (changingServer) return;
        final String next;
        try {
            next = ServerAddress.normalize(serverInput.getText().toString());
        } catch (IllegalArgumentException error) {
            connectionError.setText(error.getMessage());
            connectionError.setVisibility(View.VISIBLE);
            serverInput.requestFocus();
            return;
        }
        connectionError.setVisibility(View.GONE);
        if (!serverAddress.isEmpty() && !serverAddress.equals(next)) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.switch_server_title)
                .setMessage(R.string.server_changed)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.switch_server_confirm, (dialog, which) -> connectTo(next))
                .show();
        } else connectTo(next);
    }

    private void connectTo(String next) {
        hideKeyboard();
        if (serverAddress.equals(next)) {
            showWebScreen();
            reloadService();
            return;
        }
        changingServer = true;
        connectButton.setEnabled(false);
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.clearCache(true);
            webView.clearFormData();
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(removed -> {
            if (isFinishing() || isDestroyed()) return;
            CookieManager.getInstance().flush();
            serverAddress = next;
            preferences.edit().putString(SERVER_KEY, next).apply();
            serverInput.setText(next);
            changingServer = false;
            connectButton.setEnabled(true);
            showWebScreen();
            reloadService();
        });
    }

    private void showConnectionSettings() {
        hideKeyboard();
        webScreen.setVisibility(View.GONE);
        setupScreen.setVisibility(View.VISIBLE);
        connectionError.setVisibility(View.GONE);
        serverInput.setText(serverAddress);
        findViewById(R.id.cancel_connection).setVisibility(serverAddress.isEmpty() ? View.GONE : View.VISIBLE);
        if (webView != null) webView.onPause();
    }

    private void showWebScreen() {
        if (serverAddress.isEmpty()) return;
        hideKeyboard();
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
        if (serverAddress.isEmpty() || changingServer) return;
        if (webView == null) {
            webView = new WebView(this);
            webView.setId(R.id.web_view);
            FrameLayout container = findViewById(R.id.web_container);
            container.addView(webView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            configureWebView();
        }
        showWebScreen();
        webView.loadUrl(serverAddress);
    }

    private void showLoadError(String message) {
        mainFrameFailed = true;
        handler.removeCallbacks(loadTimeout);
        progress.setVisibility(View.GONE);
        errorMessage.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void openExternal(String destination) {
        try {
            URI uri = URI.create(destination);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
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
        if (changingServer) return;
        if (setupScreen.getVisibility() == View.VISIBLE) {
            if (!serverAddress.isEmpty()) showWebScreen();
            else finish();
        } else if (webView != null && webView.canGoBack() && !mainFrameFailed) webView.goBack();
        else finish();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(STATE_SERVER, serverAddress);
        state.putBoolean(STATE_ACTIVE, webScreen.getVisibility() == View.VISIBLE);
        if (webView != null && !serverAddress.isEmpty()) webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null && webScreen != null && webScreen.getVisibility() == View.VISIBLE) webView.onResume();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }
}
