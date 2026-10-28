package com.shunshoupai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
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
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.UUID;

/** Pure Java connection fixtures and real backend flows; no external model or cloud. */
@RunWith(AndroidJUnit4.class)
public class ConnectionInstrumentedTest {
    private ActivityScenario<MainActivity> scenario;
    private HttpFixture fixture;

    @Before
    public void clearOnlyTestAppState() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("offline-editor", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("device-credentials", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("schedule-offline.sqlite");
        for(String name:new String[]{"native-workbench","planning-drafts","local-model-settings-v1"})context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void closeFixtureAndActivity() throws IOException {
        if (scenario != null) scenario.close();
        if (fixture != null) fixture.close();
    }

    @Test
    public void firstLaunchWorksOfflineAndConnectionRejectsUnsafeUrls() {
        scenario = ActivityScenario.launch(MainActivity.class);
        assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.offline_screen).getVisibility()).intValue());
        assertTrue(read(a -> a.findViewById(R.id.offline_add).isEnabled()));
        scenario.onActivity(activity -> activity.findViewById(R.id.offline_connect).performClick());
        assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());

        String[] invalid = {
                "javascript:alert(1)", "file:///sdcard/private.txt",
                "https://user:password@example.com", "https://example.com?token=secret",
                "https://example.com#session", "https://example.com/nested-path"
        };
        for (String address : invalid) {
            scenario.onActivity(activity -> activity.handleScanResult(address));
            assertEquals(View.VISIBLE, read(a -> a.findViewById(R.id.setup_screen).getVisibility()).intValue());
            assertTrue("Invalid service URL must produce a visible explanation: " + address,
                    read(a -> !((TextView) a.findViewById(R.id.connection_error)).getText().toString().isEmpty()));
        }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Rejected values must not become a remembered server",
                context.getSharedPreferences("connection", Context.MODE_PRIVATE).contains("server_url"));
    }

    @Test
    public void pairedComputerCanBeClosedWithoutBlockingLocalPlanningOrRestoringRemoteUi() throws Exception {
        fixture=new HttpFixture();fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        String primary=read(a->((OfflineScheduleView)a.findViewById(R.id.offline_screen)).scope());
        fixture.stop();scenario.recreate();
        assertEquals(primary,read(a->((OfflineScheduleView)a.findViewById(R.id.offline_screen)).scope()));
        scenario.onActivity(activity->activity.findViewById(R.id.offline_online).performClick());
        waitUntil("Intelligent planning must be native and immediately usable with the computer closed",()->read(activity->{
            for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())if(window.findViewById(R.id.planner_input)!=null)return true;
            return false;
        }));
        assertEquals("Pairing must not fetch or restore a remote workspace",0,fixture.mobileRequests.get());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse(context.getSharedPreferences("connection",Context.MODE_PRIVATE).getAll().toString().contains(fixture.pairToken));
    }

    @Test
    public void decliningSharingConsentNeverRedeemsOrSendsLocalSchedules() throws IOException {
        fixture=new HttpFixture();fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity->activity.handleScanResult(fixture.origin()+"/#pair="+fixture.pairToken));
        waitUntil("The sharing notice must include transitive copies and model-key boundary",()->{
            android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            return root!=null&&!root.findAccessibilityNodeInfosByText("模型密钥和设置不会传播").isEmpty();
        });
        clickDialogButton("取消");assertEquals(0,fixture.redeems.get());assertTrue(fixture.receivedRevisions.isEmpty());assertTrue(fixture.authorizations.isEmpty());
    }

    @Test
    public void scannedAddressRequiresExplicitPairingAndNeverOpensRemoteUi() throws IOException {
        fixture=new HttpFixture();fixture.blankPage=true;fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity->activity.findViewById(R.id.offline_connect).performClick());
        scenario.onActivity(activity->activity.handleScanResult(fixture.origin()));
        assertEquals(0,fixture.redeems.get());
        String[] invalid={"javascript:alert(1)",fixture.origin()+"/#pair=short",fixture.origin()+"/#pair="+fixture.pairToken+"&extra=1",fixture.origin()+"/nested#pair="+fixture.pairToken};
        for(String value:invalid)scenario.onActivity(activity->activity.handleScanResult(value));
        assertEquals(0,fixture.redeems.get());
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        assertEquals("Pairing must not request or wait for any WebView document",0,fixture.mobileRequests.get());
    }

    @Test
    public void liveBackendPairingMergesIndependentSchedulesAndRevocationKeepsLocalData() throws Exception {
        String root=backendRoot();PairingGrant grant=pairingGrant(root,true);
        String desktopEvent=UUID.randomUUID().toString();
        JSONObject event=new JSONObject().put("title","电脑先记的日程").put("start_local","2026-10-28T10:00:00").put("end_local","2026-10-28T11:00:00").put("timezone","Asia/Shanghai").put("status","planned").put("deleted",false);
        JSONObject revision=AncestryReplica.createRevision(new org.json.JSONArray(),UUID.randomUUID().toString(),event,"测试电脑",System.currentTimeMillis());
        replicaRequest(root,grant.cookie,grant.device,new org.json.JSONArray().put(new JSONObject().put("id",desktopEvent).put("heads",new org.json.JSONArray().put(revision))),null);
        String phoneEvent;
        try(LocalScheduleStore local=new LocalScheduleStore(InstrumentationRegistry.getInstrumentation().getTargetContext())){phoneEvent=local.save("guest",null,LocalScheduleStore.payload("手机独立安排",java.time.LocalDateTime.of(2026,10,28,10,30),java.time.LocalDateTime.of(2026,10,28,11,30),"planned",false));}
        scenario=ActivityScenario.launch(MainActivity.class);scanAndAgree(root+"#pair="+grant.token);awaitPairedHome(grant.space);
        waitUntil("Native pairing must merge both sides without loading a WebView",()->{
            try(LocalScheduleStore local=new LocalScheduleStore(InstrumentationRegistry.getInstrumentation().getTargetContext())){java.util.List<JSONObject> rows=local.list(local.activeScope());return rows.stream().filter(row->!row.optBoolean("deleted")).count()==2&&rows.stream().noneMatch(row->row.optBoolean("dirty"));}catch(Exception e){return false;}
        });
        org.json.JSONArray synced=allReplicaRecords(root,grant.cookie,grant.device);int live=0;for(int i=0;i<synced.length();i++){org.json.JSONArray heads=synced.getJSONObject(i).getJSONArray("heads");for(int j=0;j<heads.length();j++)if(!heads.getJSONObject(j).getJSONObject("payload").getBoolean("deleted")){live++;break;}}assertEquals(2,live);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject paired;
        try(LocalScheduleStore local=new LocalScheduleStore(context)){paired=local.peers().get(0);assertTrue(local.list(local.primaryScope()).stream().anyMatch(row->phoneEvent.equals(row.optString("id"))));}
        apiRequest(root,"api/devices/"+paired.getString("pairing_id"),"DELETE",grant.cookie,null,null);
        scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
        waitUntil("Revoked device capability should stop future synchronization",()->{try(LocalScheduleStore local=new LocalScheduleStore(context)){return !local.peer(paired.getString("peer_id")).getBoolean("enabled");}catch(Exception e){return false;}});
        try(LocalScheduleStore local=new LocalScheduleStore(context)){assertEquals(2,local.list(local.activeScope()).stream().filter(row->!row.optBoolean("deleted")).count());}
    }

    @Test
    public void serviceAddressValidationPreservesOriginBoundaries() {
        String base = ServerAddress.normalize("http://192.168.1.20:3000/");
        assertEquals("https://example.com/", ServerAddress.normalize(" HTTPS://EXAMPLE.COM:443/ "));
        assertEquals("http://[::1]/", ServerAddress.normalize("http://[0:0:0:0:0:0:0:1]:80/"));
        assertTrue(ServerAddress.sameOrigin("http://[::1]/", "http://[0:0:0:0:0:0:0:1]/api"));
        assertTrue(ServerAddress.sameOrigin(base, "http://192.168.1.20:3000/api/auth/session"));
        assertFalse(ServerAddress.sameOrigin(base, "http://192.168.1.20:3001/"));
        assertFalse(ServerAddress.sameOrigin(base, "https://192.168.1.20:3000/"));
        assertFalse(ServerAddress.sameOrigin("https://example.com", "https://example.com.evil.invalid/"));
        assertFalse(ServerAddress.sameOrigin("https://example.com", "javascript:alert(1)"));
        assertFalse(ServerAddress.sameOrigin("https://example.com", "https://example.com\\@evil.invalid/"));
        for (String invalid : new String[] {
                "https://example.com:99999", "http://127.1", "http://0177.0.0.1",
                "http://2130706433", "http://0x7f000001", "http://[:::1]",
                "https://example.com%2f.evil.invalid", "https://example.com\\@evil.invalid"
        }) {
            try {
                ServerAddress.normalize(invalid);
                fail("Java validation must reject unsafe or ambiguous addresses");
            } catch (IllegalArgumentException expected) {
                // Java validation refuses these before any connection.
            }
        }
    }

    @Test
    public void rescanAtNewAddressKeepsPendingRevisionAndNeverSendsOldBearer() throws Exception {
        fixture=new HttpFixture();fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        scenario.moveToState(Lifecycle.State.CREATED);String oldToken=fixture.deviceToken;
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String pending,scope;
        try(LocalScheduleStore local=new LocalScheduleStore(context)){scope=local.primaryScope();local.save(scope,null,LocalScheduleStore.payload("换地址后继续合并",java.time.LocalDateTime.of(2026,10,28,12,0),java.time.LocalDateTime.of(2026,10,28,13,0),"planned",false));pending=local.replica().page(null).getJSONArray("records").getJSONObject(0).getJSONArray("heads").getJSONObject(0).getString("revision_id");}
        fixture.changeAddress();fixture.authorizations.clear();scenario.moveToState(Lifecycle.State.RESUMED);
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        waitUntil("The pending revision must survive address and capability rotation",()->fixture.receivedRevisions.contains(pending));
        assertFalse("New address must never receive the previous long-lived capability",fixture.authorizations.contains("Bearer "+oldToken));
        assertEquals(scope,read(a->((OfflineScheduleView)a.findViewById(R.id.offline_screen)).scope()));
        try(LocalScheduleStore local=new LocalScheduleStore(context)){assertEquals(ServerAddress.normalize(fixture.origin()),local.peer(fixture.identity.peerId).getString("origin"));}
    }

    @Test
    public void sameIdsWithAnotherSigningKeyCannotReceiveCredentialsOrLocalChanges() throws Exception {
        fixture=new HttpFixture();fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        scenario.moveToState(Lifecycle.State.CREATED);String peer=fixture.identity.peerId,space=fixture.identity.spaceId;
        fixture.changeAddress();fixture.identity=new TestPeer(peer,space);fixture.authorizations.clear();int redeemed=fixture.redeems.get();
        scenario.moveToState(Lifecycle.State.RESUMED);scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);
        waitUntil("A mismatching pinned key must visibly reject the new address",()->read(a->((TextView)a.findViewById(R.id.connection_error)).getText().toString()).contains("身份"));
        assertEquals(redeemed,fixture.redeems.get());assertTrue(fixture.authorizations.isEmpty());
        scenario.onActivity(activity->activity.findViewById(R.id.cancel_connection).performClick());
        assertEquals(View.VISIBLE,read(a->a.findViewById(R.id.offline_screen).getVisibility()).intValue());
    }

    @Test
    public void cancellingPairingRevokesTheDeviceAndKeepsNativeSchedules() throws Exception {
        fixture=new HttpFixture();fixture.start();scenario=ActivityScenario.launch(MainActivity.class);
        scanAndAgree(fixture.origin()+"/#pair="+fixture.pairToken);awaitPairedHome(fixture.identity.spaceId);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(LocalScheduleStore local=new LocalScheduleStore(context)){local.save(local.activeScope(),null,LocalScheduleStore.payload("取消配对仍保留",java.time.LocalDateTime.of(2026,10,28,16,0),java.time.LocalDateTime.of(2026,10,28,17,0),"planned",false));}
        scenario.onActivity(activity->activity.findViewById(R.id.offline_connect).performClick());
        SystemClock.sleep(300);captureScreenshot("android-devices.png");
        scenario.onActivity(activity->{android.view.ViewGroup rows=activity.findViewById(R.id.peer_rows);assertTrue(rows.getChildCount()>=2);rows.getChildAt(1).performClick();});
        waitUntil("The native confirmation should be actionable",()->{
            android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("取消配对"))if("取消配对".contentEquals(node.getText()==null?"":node.getText())&&node.isClickable())return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            return false;
        });
        waitUntil("The paired peer should receive capability revocation",()->"revoked".equals(fixture.deviceToken));
        try(LocalScheduleStore local=new LocalScheduleStore(context)){assertFalse(local.peer(fixture.identity.peerId).getBoolean("enabled"));assertTrue(local.list(local.primaryScope()).stream().anyMatch(row->"取消配对仍保留".equals(row.optString("title"))));}
    }

    private String backendRoot(){String root=InstrumentationRegistry.getArguments().getString("backend_url","");String key=InstrumentationRegistry.getArguments().getString("bootstrap_key","");Assume.assumeFalse("Live device tests need an isolated backend and injected test bootstrap key",root.isEmpty()||key.isEmpty());return ServerAddress.normalize(root);}
    private void awaitPairedHome(String remoteSpace){waitUntil("QR pairing should preserve the local primary calendar and register a separate peer",()->read(activity->{try(LocalScheduleStore local=new LocalScheduleStore(activity)){return ((OfflineScheduleView)activity.findViewById(R.id.offline_screen)).scope().equals(local.primaryScope())&&activity.findViewById(R.id.offline_screen).getVisibility()==View.VISIBLE&&local.peers().stream().anyMatch(peer->remoteSpace.equals(peer.optString("space_id"))&&peer.optBoolean("enabled"));}catch(Exception error){return false;}}));}
    private JSONObject replicaRequest(String root,String cookie,String device,org.json.JSONArray records,String after)throws Exception{
        JSONObject body=new JSONObject().put("device_id",device).put("records",records);if(after!=null)body.put("after",after);
        return apiRequest(root,"api/replica/exchange","POST",cookie,body,null).body;
    }
    private org.json.JSONArray allReplicaRecords(String root,String cookie,String device)throws Exception{
        org.json.JSONArray result=new org.json.JSONArray();String after=null;
        do{JSONObject page=replicaRequest(root,cookie,device,new org.json.JSONArray(),after);org.json.JSONArray records=page.getJSONArray("records");for(int i=0;i<records.length();i++)result.put(records.getJSONObject(i));after=page.getBoolean("has_more")?page.getString("next_cursor"):null;}while(after!=null);
        return result;
    }
    private static final class HttpResult{final JSONObject body;final String cookie;HttpResult(JSONObject body,String cookie){this.body=body;this.cookie=cookie;}}
    private HttpResult apiRequest(String root,String path,String method,String cookie,JSONObject body,String bootstrap)throws Exception{
        HttpURLConnection request=(HttpURLConnection)new URL(root+path).openConnection();request.setRequestMethod(method);request.setConnectTimeout(10000);request.setReadTimeout(10000);request.setRequestProperty("Content-Type","application/json");
        if(cookie!=null)request.setRequestProperty("Cookie",cookie);if(bootstrap!=null)request.setRequestProperty("X-Device-Bootstrap",bootstrap);
        try{if(body!=null){request.setDoOutput(true);try(java.io.OutputStream out=request.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            assertEquals(path,200,request.getResponseCode());String resultCookie=null;
            for(java.util.Map.Entry<String,java.util.List<String>> header:request.getHeaderFields().entrySet())if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))for(String value:header.getValue())if(value.startsWith("shunshoupai_device="))resultCookie=value.split(";",2)[0];
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(request.getInputStream(),StandardCharsets.UTF_8))){StringBuilder json=new StringBuilder();for(String line;(line=reader.readLine())!=null;)json.append(line);return new HttpResult(new JSONObject(json.toString()),resultCookie);}
        }finally{request.disconnect();}
    }
    private static final class PairingGrant{final String space,token,cookie,device;PairingGrant(String space,String token,String cookie,String device){this.space=space;this.token=token;this.cookie=cookie;this.device=device;}}
    private PairingGrant pairingGrant(String root,boolean cleanup)throws Exception{
        String key=InstrumentationRegistry.getArguments().getString("bootstrap_key","");
        HttpResult owner=apiRequest(root,"api/devices/bootstrap","POST",null,new JSONObject().put("name","Android test desktop").put("platform","test"),key);
        String space=owner.body.getString("space_id"),device=owner.body.getJSONObject("device").getString("id");assertNotNull(owner.cookie);
        if(cleanup){org.json.JSONArray all=allReplicaRecords(root,owner.cookie,device),batch=new org.json.JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject event=all.getJSONObject(i);org.json.JSONArray heads=event.getJSONArray("heads");boolean live=false;
                for(int j=0;j<heads.length();j++)if(!heads.getJSONObject(j).getJSONObject("payload").getBoolean("deleted"))live=true;
                if(!live)continue;JSONObject deleted=new JSONObject(heads.getJSONObject(0).getJSONObject("payload").toString()).put("deleted",true);
                JSONObject revision=AncestryReplica.createRevision(heads,UUID.randomUUID().toString(),deleted,"隔离测试清理",System.currentTimeMillis());
                batch.put(new JSONObject().put("id",event.getString("id")).put("heads",new org.json.JSONArray().put(revision)));
                if(batch.length()==50){replicaRequest(root,owner.cookie,device,batch,null);batch=new org.json.JSONArray();}
            }
            if(batch.length()>0)replicaRequest(root,owner.cookie,device,batch,null);
        }
        JSONObject issued=apiRequest(root,"api/pairing/issue","POST",owner.cookie,new JSONObject(),null).body;return new PairingGrant(space,issued.getString("token"),owner.cookie,device);
    }
    private void scanAndAgree(String link) {
        scenario.onActivity(activity->{try {
            java.lang.reflect.Field hostField=MainActivity.class.getDeclaredField("deviceHost"),syncField=MainActivity.class.getDeclaredField("scheduleSync");hostField.setAccessible(true);syncField.setAccessible(true);
            DeviceHostRuntime host=(DeviceHostRuntime)hostField.get(activity);ScheduleSync sync=(ScheduleSync)syncField.get(activity);
            String backend=InstrumentationRegistry.getArguments().getString("backend_url","");
            String callback=InstrumentationRegistry.getArguments().getString("callback_origin","");
            boolean live=!backend.isEmpty()&&link.startsWith(ServerAddress.normalize(backend));
            if(live){assertFalse("Live pairing needs an adb-forwarded callback_origin",callback.isEmpty());assertEquals("Forwarded device port must match the running endpoint",43127,host.port());}
            final String returnOrigin=live?callback:"http://127.0.0.1:"+host.port();sync.setReturnOrigin(()->returnOrigin);
            activity.handleScanResult(link);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}});
        clickDialogButton("同意配对并共享日程");
    }
    private void clickDialogButton(String label) {
        waitUntil("The native confirmation must offer "+label,()->{
            android.view.accessibility.AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(node.getText()==null?"":node.getText())&&node.isClickable())return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            return false;
        });
    }
    private void captureScreenshot(String filename) {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot == null) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir(null);
        if (directory == null) { screenshot.recycle(); return; }
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
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
        volatile boolean blankPage;
        volatile boolean legacyRootOnly;
        private int port;
        TestPeer identity;
        final String pairToken=PeerIdentity.challenge();
        volatile String deviceToken=PeerIdentity.challenge();
        final String pairingId=UUID.randomUUID().toString();
        final AtomicInteger responses=new AtomicInteger(),redeems=new AtomicInteger(),mobileRequests=new AtomicInteger();
        final java.util.List<String> authorizations=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<String> receivedRevisions=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.Map<String,JSONObject> events=new java.util.LinkedHashMap<>();

        HttpFixture(){try{identity=new TestPeer();}catch(Exception error){throw new IllegalStateException(error);}}
        void start() throws IOException {
            server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));port=server.getLocalPort();running=true;
            thread=new Thread(()->{
                while(running){
                    try(Socket connection=server.accept()){
                        connection.setSoTimeout(3000);
                        java.io.InputStream input=connection.getInputStream();java.io.ByteArrayOutputStream headerBytes=new java.io.ByteArrayOutputStream();int previous=0,current;
                        while((current=input.read())!=-1){headerBytes.write(current);previous=((previous<<8)|current);if(previous==0x0d0a0d0a)break;if(headerBytes.size()>16384)throw new IOException("headers too large");}
                        String[] lines=headerBytes.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
                        String[] request=lines[0].split(" ");String method=request[0],path=request[1];
                        java.util.Map<String,String> headers=new java.util.HashMap<>();
                        for(int i=1;i<lines.length;i++){int split=lines[i].indexOf(':');if(split>0)headers.put(lines[i].substring(0,split).toLowerCase(java.util.Locale.ROOT),lines[i].substring(split+1).trim());}
                        int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length>131072)throw new IOException("request too large");
                        byte[] bytes=new byte[length];int offset=0;while(offset<length){int count=input.read(bytes,offset,length-offset);if(count<0)throw new IOException("truncated request");offset+=count;}
                        JSONObject body=length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                        String authorization=headers.get("authorization");if(authorization!=null)authorizations.add(authorization);
                        JSONObject result=null;String cookie="";int status=200;
                        if(path.startsWith("/api/devices/identity?")){
                            android.net.Uri uri=android.net.Uri.parse("http://fixture"+path);result=identity.proof(uri.getQueryParameter("challenge"),uri.getQueryParameter("device_id"));
                        }else if(path.equals("/api/pairing/redeem")){
                            if(!pairToken.equals(body.optString("token"))){status=400;result=new JSONObject().put("error",new JSONObject().put("message","配对码无效"));}
                            else{redeems.incrementAndGet();deviceToken=PeerIdentity.challenge();result=identity.proof(body.getString("challenge"),body.getString("device_id")).put("device_token",deviceToken).put("pairing_id",pairingId).put("legacy_space_ids",new org.json.JSONArray().put(identity.spaceId));}
                        }else if(path.startsWith("/api/")){
                            if(!("Bearer "+deviceToken).equals(authorization)){status=401;result=new JSONObject().put("error",new JSONObject().put("message","设备配对失效"));}
                            else if(path.equals("/api/devices/info"))result=identity.context().put("peer",new JSONObject().put("id",pairingId));
                            else if(path.equals("/api/devices/web-session")){result=identity.context();cookie="Set-Cookie: fixture_device="+deviceToken+"; Path=/; HttpOnly; SameSite=Lax\r\n";}
                            else if(path.startsWith("/api/devices/")&&method.equals("DELETE")){result=new JSONObject().put("revoked",true);deviceToken="revoked";}
                            else if(path.equals("/api/replica/exchange"))result=sync(body);
                            else{status=404;result=new JSONObject();}
                        }
                        byte[] response;
                        String contentType;
                        if(result!=null){response=result.toString().getBytes(StandardCharsets.UTF_8);contentType="application/json";}
                        else{
                            mobileRequests.incrementAndGet();boolean missing=legacyRootOnly&&path.startsWith("/mobile");if(missing)status=404;
                            response=(missing?"<!doctype html><title>Not found</title><body>Not found</body>":blankPage?"<!doctype html><title>Empty fixture</title><body></body>":"<!doctype html><meta charset=utf-8><title>Fixture ready</title><h1>Local test fixture</h1><script>window.receivedFragment=location.hash;history.replaceState(null,'',location.pathname);localStorage.setItem('fixture','ready')</script>").getBytes(StandardCharsets.UTF_8);contentType="text/html; charset=utf-8";
                        }
                        String responseHeaders="HTTP/1.1 "+status+" OK\r\nContent-Type: "+contentType+"\r\n"+cookie+"Cache-Control: no-store\r\nConnection: close\r\nContent-Length: "+response.length+"\r\n\r\n";
                        connection.getOutputStream().write(responseHeaders.getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().write(response);connection.getOutputStream().flush();responses.incrementAndGet();
                    }catch(Exception ignored){if(!running)return;}
                }
            },"android-paired-device-fixture");thread.setDaemon(true);thread.start();
        }
        private JSONObject sync(JSONObject body)throws Exception{
            org.json.JSONArray incoming=body.getJSONArray("records");assertTrue(incoming.length()<=50);
            for(int i=0;i<incoming.length();i++){
                JSONObject record=AncestryReplica.record(incoming.getJSONObject(i));String id=record.getString("id");
                org.json.JSONArray heads=record.getJSONArray("heads");for(int j=0;j<heads.length();j++)receivedRevisions.add(heads.getJSONObject(j).getString("revision_id"));
                events.put(id,AncestryReplica.merge(events.get(id),record));
            }
            org.json.JSONArray page=new org.json.JSONArray();String after=body.optString("after","");
            for(String id:new java.util.TreeSet<>(events.keySet()))if(id.compareTo(after)>0)page.put(events.get(id));
            return identity.context().put("records",page).put("has_more",false).put("next_cursor",JSONObject.NULL);
        }
        String origin(){return "http://127.0.0.1:"+port;}
        void changeAddress()throws IOException{stop();port=0;start();}
        void stop()throws IOException{running=false;if(server!=null)server.close();if(thread!=null)try{thread.join(3000);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        @Override public void close()throws IOException{stop();}
    }
}
