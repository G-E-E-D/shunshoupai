package com.shunshoupai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.UUID;

/** Runs against a tiny device-local HTTP fixture; no cloud, model, or account is needed. */
@RunWith(AndroidJUnit4.class)
public class ConnectionInstrumentedTest {
    private ActivityScenario<MainActivity> scenario;
    private HttpFixture fixture;

    @Before
    public void clearOnlyTestAppState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
        });
    }

    @After
    public void closeFixtureAndActivity() throws IOException {
        if (scenario != null) scenario.close();
        if (fixture != null) fixture.close();
    }

    @Test
    public void firstLaunchRequiresAnExplicitServiceAndRejectsUnsafeUrls() {
        scenario = ActivityScenario.launch(MainActivity.class);
        assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
        assertEquals("", read(a -> ((EditText) a.findViewById(R.id.server_url)).getText().toString()));

        String[] invalid = {
                "javascript:alert(1)", "file:///sdcard/private.txt",
                "https://user:password@example.com", "https://example.com?token=secret",
                "https://example.com#session", "https://example.com/nested-path"
        };
        for (String address : invalid) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.server_url)).setText(address);
                activity.findViewById(R.id.connect_button).performClick();
            });
            assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
            assertTrue("Invalid service URL must produce a visible explanation: " + address,
                    read(a -> !((TextView) a.findViewById(R.id.connection_error)).getText().toString().isEmpty()));
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Rejected values must not become a remembered server",
                context.getSharedPreferences("connection", Context.MODE_PRIVATE).contains("server_url"));
    }

    @Test
    public void loadRealHttpFixtureWithRestrictedWebViewAndRememberConnection() throws IOException {
        fixture = new HttpFixture();
        fixture.start();
        scenario = ActivityScenario.launch(MainActivity.class);
        connect(fixture.origin());
        waitUntil("HTTP fixture should actually render", () -> "Fixture ready".equals(webTitle()));

        scenario.onActivity(activity -> {
            WebView web = activity.findViewById(R.id.web_view);
            WebSettings settings = web.getSettings();
            assertTrue(settings.getJavaScriptEnabled());
            assertTrue(settings.getDomStorageEnabled());
            assertFalse(settings.getAllowFileAccess());
            assertFalse(settings.getAllowContentAccess());
            assertFalse(settings.getJavaScriptCanOpenWindowsAutomatically());
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
            assertTrue(settings.getSafeBrowsingEnabled());
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web));
        });

        scenario.recreate();
        waitUntil("Saved address must survive Activity recreation", () -> "Fixture ready".equals(webTitle()));
        assertEquals(View.GONE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String saved = context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("server_url", "");
        assertNotNull(saved);
        assertTrue(saved.startsWith(fixture.origin()));
    }

    @Test
    public void connectionFailureOffersRetryAndCanRecoverWithoutChangingServer() throws IOException {
        fixture = new HttpFixture();
        fixture.start();
        scenario = ActivityScenario.launch(MainActivity.class);
        connect(fixture.origin());
        waitUntil("First fixture response", () -> "Fixture ready".equals(webTitle()));

        int previousResponses = fixture.responses.get();
        fixture.stop();
        scenario.onActivity(activity -> activity.findViewById(R.id.toolbar_refresh).performClick());
        waitUntil("Failed main document should show native retry UI",
                () -> read(a -> a.findViewById(R.id.error_panel).getVisibility()) == View.VISIBLE);

        fixture.start();
        scenario.onActivity(activity -> activity.findViewById(R.id.error_retry).performClick());
        waitUntil("Retry should load the same service after it recovers",
                () -> "Fixture ready".equals(webTitle())
                        && fixture.responses.get() > previousResponses
                        && read(a -> a.findViewById(R.id.error_panel).getVisibility()) != View.VISIBLE);
    }

    @Test
    public void liveBackendHydratesAndAuthenticatesInsideAndroidWebView() throws Exception {
        String backend = InstrumentationRegistry.getArguments().getString("backend_url", "");
        Assume.assumeFalse("Set backend_url to run the live WebView/backend integration", backend.isEmpty());
        scenario = ActivityScenario.launch(MainActivity.class);
        connect(ServerAddress.normalize(backend));
        waitUntil("The actual workbench should render inside Android WebView",
                () -> "true".equals(evaluate("Boolean(document.querySelector('header button[aria-label=\"登录账号\"]') && document.querySelector('.empty-state h3'))")));
        evaluate("document.querySelector('header button[aria-label=\"登录账号\"]').click()");
        waitUntil("React must hydrate: native browser click should open the login dialog",
                () -> "true".equals(evaluate("Boolean(document.querySelector('[role=\"dialog\"]'))")));
        evaluate("document.querySelector('button[aria-label=\"关闭弹窗\"]').click()");

        String email = JSONObject.quote("android-" + UUID.randomUUID() + "@example.com");
        String requestId = JSONObject.quote(UUID.randomUUID().toString());
        String script = "(async () => {"
                + "window.__androidIntegration = {done:false};"
                + "try {"
                + "const request = async (path, options = {}) => {"
                + "const response = await fetch(path, {cache:'no-store',headers:{'Content-Type':'application/json'},...options});"
                + "const data = await response.json(); if(!response.ok) throw new Error(path + ':' + response.status + ':' + JSON.stringify(data)); return data; };"
                + "const account = await request('/api/auth/register',{method:'POST',body:JSON.stringify({email:" + email + ",password:'Android-test-only-2026!'})});"
                + "if(!account.user) throw new Error('Registration did not create a local session');"
                + "const parsed = await request('/api/ai/parse',{method:'POST',body:JSON.stringify({text:'明天下午三点到四点安卓面试，今天晚上七点到八点改简历，八点到九点准备面试。',timezone:'Asia/Shanghai'})});"
                + "if(parsed.events.length!==3 || parsed.needs_clarification) throw new Error('Expected three validated drafts');"
                + "const saved = await request('/api/events/commit',{method:'POST',body:JSON.stringify({request_id:" + requestId + ",events:parsed.events})});"
                + "const from = parsed.events.map(event=>new Date(event.start_local+'+08:00').toISOString()).sort()[0];"
                + "const to = parsed.events.map(event=>new Date(event.end_local+'+08:00').toISOString()).sort().at(-1);"
                + "const query = new URLSearchParams({from,to});"
                + "const list = await request('/api/events?'+query.toString());"
                + "window.__androidIntegration = {done:true,eventCount:list.events.length,titles:list.events.map(event=>event.title),created:saved.events.length};"
                + "} catch(error) {window.__androidIntegration={done:true,error:String(error)};}"
                + "})()";
        evaluate(script);
        waitUntil("Same-origin WebView session should survive register, parse, commit and query",
                () -> "true".equals(evaluate("window.__androidIntegration?.done === true")));
        Object decoded = new JSONTokener(evaluate("JSON.stringify(window.__androidIntegration)")).nextValue();
        JSONObject outcome = new JSONObject((String) decoded);
        assertFalse(outcome.toString(), outcome.has("error"));
        assertEquals(3, outcome.getInt("created"));
        assertEquals(3, outcome.getInt("eventCount"));
        assertTrue(outcome.getJSONArray("titles").toString().contains("安卓面试"));

        scenario.onActivity(activity -> activity.findViewById(R.id.toolbar_refresh).performClick());
        waitUntil("After reload, HttpOnly login cookies and the saved event should appear in the real UI",
                () -> "true".equals(evaluate("Boolean(Array.from(document.querySelectorAll('.event-main h3')).find(el=>el.textContent==='改简历'))")));
        evaluate("Array.from(document.querySelectorAll('.view-switch button')).find(el=>el.textContent==='七天').click()");
        waitUntil("The real seven-day workbench should display all three saved events",
                () -> "true".equals(evaluate("document.querySelectorAll('.event-main h3').length===3")));
        evaluate("document.querySelector('header button[aria-label=\"打开账号设置\"]').click()");
        waitUntil("Android account settings should offer personal model configuration",
                () -> "true".equals(evaluate("Array.from(document.querySelectorAll('[role=\"dialog\"] button')).some(el=>el.textContent.trim()==='模型设置')")));
        evaluate("Array.from(document.querySelectorAll('[role=\"dialog\"] button')).find(el=>el.textContent.trim()==='模型设置').click()");
        waitUntil("The shared model settings form must work inside the Android client",
                () -> "true".equals(evaluate("Boolean(document.querySelector('[role=\"dialog\"][aria-label=\"模型设置\"]')) && Array.from(document.querySelectorAll('[role=\"dialog\"] label')).some(el=>el.textContent.includes('API Key')) && Array.from(document.querySelectorAll('[role=\"dialog\"] label')).some(el=>el.textContent.includes('Base URL'))")));
        evaluate("document.querySelector('[role=\"dialog\"][aria-label=\"模型设置\"] button[aria-label^=\"关闭\"]').click()");
        evaluate("document.querySelector('.agenda-card').scrollIntoView({block:'start',behavior:'instant'})");
        SystemClock.sleep(350);
        captureWorkspaceScreenshot();
    }

    @Test
    public void scannedServiceUrlUsesExistingValidationAndConnectsToTheSelectedServer() throws IOException {
        fixture = new HttpFixture();
        fixture.start();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> activity.handleScanResult("javascript:alert('not-a-service')"));
        assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("An invalid QR value must not become a remembered service",
                context.getSharedPreferences("connection", Context.MODE_PRIVATE).contains("server_url"));
        scenario.onActivity(activity -> activity.handleScanResult(fixture.origin()));
        waitUntil("A valid scanned URL should connect without manually retyping it",
                () -> "Fixture ready".equals(webTitle()));
        assertEquals(View.GONE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
        assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE)
                .getString("server_url", "").startsWith(fixture.origin()));
    }

    @Test
    public void serviceAddressValidationPreservesOriginBoundaries() {
        String base = ServerAddress.normalize("http://192.168.1.20:3000/");
        assertTrue(ServerAddress.sameOrigin(base, "http://192.168.1.20:3000/api/auth/session"));
        assertFalse(ServerAddress.sameOrigin(base, "http://192.168.1.20:3001/"));
        assertFalse(ServerAddress.sameOrigin(base, "https://192.168.1.20:3000/"));
        assertFalse(ServerAddress.sameOrigin("https://example.com", "https://example.com.evil.invalid/"));
        assertFalse(ServerAddress.sameOrigin("https://example.com", "javascript:alert(1)"));
        try {
            ServerAddress.normalize("https://example.com:99999");
            fail("Out-of-range ports must be rejected");
        } catch (IllegalArgumentException expected) {
            // The app must refuse invalid network addresses before they reach WebView.
        }
    }

    private void connect(String origin) {
        scenario.onActivity(activity -> {
            ((EditText) activity.findViewById(R.id.server_url)).setText(origin);
            activity.findViewById(R.id.connect_button).performClick();
        });
    }

    private String webTitle() {
        return read(a -> {
            WebView web = a.findViewById(R.id.web_view);
            return web == null ? null : web.getTitle();
        });
    }

    private String evaluate(String script) {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            WebView web = activity.findViewById(R.id.web_view);
            if (web == null) {
                result.set("null");
                completed.countDown();
                return;
            }
            web.evaluateJavascript(script, value -> {
                result.set(value);
                completed.countDown();
            });
        });
        try {
            assertTrue("WebView JavaScript callback timed out", completed.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for WebView", interrupted);
        }
        return result.get();
    }

    private void captureWorkspaceScreenshot() {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot == null) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir(null);
        if (directory == null) { screenshot.recycle(); return; }
        try (FileOutputStream output = new FileOutputStream(new File(directory, "android-workspace.png"))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (IOException error) {
            // Screenshot evidence should not turn a passing business assertion into a storage failure.
            Log.w("AndroidIntegration", "Workspace screenshot could not be written", error);
        } finally {
            screenshot.recycle();
        }
    }

    private <T> T read(Function<MainActivity, T> getter) {
        AtomicReference<T> result = new AtomicReference<>();
        scenario.onActivity(activity -> result.set(getter.apply(activity)));
        return result.get();
    }

    private void waitUntil(String explanation, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(80);
        }
        assertTrue(explanation, condition.getAsBoolean());
    }

    private static final class HttpFixture implements AutoCloseable {
        private ServerSocket server;
        private Thread thread;
        private volatile boolean running;
        private int port;
        final AtomicInteger responses = new AtomicInteger();

        void start() throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            port = server.getLocalPort();
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    try (Socket connection = server.accept()) {
                        connection.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                        String request = input.readLine();
                        if (request == null) continue;
                        for (String line; (line = input.readLine()) != null && !line.isEmpty();) {
                            // Consume request headers before writing a deterministic HTTP response.
                        }
                        byte[] body = ("<!doctype html><meta charset=utf-8><title>Fixture ready</title>"
                                + "<h1>Local test fixture</h1><script>localStorage.setItem('fixture','ready')</script>")
                                .getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                                + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                        connection.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                        connection.getOutputStream().write(body);
                        connection.getOutputStream().flush();
                        responses.incrementAndGet();
                    } catch (IOException ignored) {
                        if (!running) return;
                    }
                }
            }, "android-test-http");
            thread.setDaemon(true);
            thread.start();
        }

        String origin() { return "http://127.0.0.1:" + port; }

        void stop() throws IOException {
            running = false;
            if (server != null) server.close();
            if (thread != null) {
                try { thread.join(2000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }

        @Override public void close() throws IOException { stop(); }
    }
}
