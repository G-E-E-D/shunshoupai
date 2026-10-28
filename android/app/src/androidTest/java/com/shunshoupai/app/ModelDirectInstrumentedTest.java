package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Real phone HTTP and Keystore, with disposable model providers and no computer backend. */
@RunWith(AndroidJUnit4.class)
public class ModelDirectInstrumentedTest {
    private Context context;
    private final List<String> namespaces=new ArrayList<>();
    private final List<ModelServer> servers=new ArrayList<>();
    private final List<LocalPlanningEngine> engines=new ArrayList<>();
    @Before public void open(){context=ApplicationProvider.getApplicationContext();}
    @After public void close()throws Exception {
        for(LocalPlanningEngine engine:engines)engine.cancel();
        for(ModelServer server:servers)server.close();
        for(String namespace:namespaces)context.getSharedPreferences(namespace,Context.MODE_PRIVATE).edit().clear().commit();
    }
    private LocalPlanningEngine engine(String namespace){namespaces.add(namespace);LocalPlanningEngine engine=new LocalPlanningEngine(context,namespace);engines.add(engine);return engine;}
    private LocalPlanningEngine engine(){return engine("model-independent-"+UUID.randomUUID());}
    private ModelServer server(String title)throws Exception {ModelServer server=new ModelServer(title);servers.add(server);return server;}
    private JSONObject config(ModelServer server,String key,int version)throws Exception {return new JSONObject().put("base_url",server.base()).put("model","fixture-model").put("api_key",key).put("base_version",version).put("allow_private_network",true);}
    private JSONObject parse(LocalPlanningEngine engine)throws Exception {return engine.parse("明天下午三点到四点阅读","2036-02-01",Collections.emptyList(),false);}

    @Test public void directRequestNeedsNoPairAndTestingDoesNotSaveSettings()throws Exception {
        LocalPlanningEngine engine=engine();ModelServer server=server("手机直接整理");String secret="sk-disposable-phone-only";
        assertFalse(engine.settings().getBoolean("configured"));
        assertTrue(engine.testSettings(config(server,secret,0)).getBoolean("ok"));
        assertFalse("Test connection must not save",engine.settings().getBoolean("configured"));
        engine.saveSettings(config(server,secret,0));
        JSONObject result=parse(engine);assertEquals("model",result.getString("source"));
        assertEquals("手机直接整理",result.getJSONArray("drafts").getJSONObject(0).getString("title"));
        assertEquals(2,server.requests.size());
        Request request=server.requests.get(1);
        assertEquals("/v1/chat/completions",request.path);assertEquals("Bearer "+secret,request.authorization);
        JSONArray messages=request.body.getJSONArray("messages");
        assertEquals("system",messages.getJSONObject(0).getString("role"));
        assertEquals(LocalPlanningEngine.SYSTEM_PROMPT,messages.getJSONObject(0).getString("content"));
        assertTrue(messages.getJSONObject(0).getString("content").contains("Asia/Shanghai"));
        assertTrue(messages.getJSONObject(0).getString("content").contains("needs_clarification"));
        assertTrue(messages.toString().contains("明天下午三点到四点阅读"));
        assertFalse(request.body.toString().contains("device_token"));
        assertFalse(engine.settings().toString().contains(secret));
    }

    @Test public void encryptedSettingsSurviveRecreationAndBlankKeyNeverMovesToAnotherOrigin()throws Exception {
        String namespace="model-settings-"+UUID.randomUUID();LocalPlanningEngine engine=engine(namespace);
        ModelServer first=server("第一服务"),second=server("第二服务");String secret="sk-sensitive-fixture-secret";
        JSONObject saved=engine.saveSettings(config(first,secret,0));
        assertFalse(context.getSharedPreferences(namespace,Context.MODE_PRIVATE).getAll().toString().contains(secret));
        LocalPlanningEngine restarted=engine(namespace);assertEquals(first.base(),restarted.settings().getString("base_url"));
        restarted.saveSettings(config(first,"",saved.getInt("version")).put("model","another-model"));parse(restarted);
        assertEquals("Bearer "+secret,first.requests.get(0).authorization);
        try{restarted.testSettings(config(second,"",0));fail("Cross-origin blank key must be refused before network");}
        catch(LocalPlanningException expected){assertEquals("MODEL_KEY_REQUIRED",expected.code);}
        assertTrue(second.requests.isEmpty());
        int version=restarted.settings().getInt("version");
        try{restarted.saveSettings(config(first,secret,version-1));fail("Stale configuration must not overwrite");}
        catch(LocalPlanningException expected){assertEquals("VERSION_CONFLICT",expected.code);}
        restarted.clearSettings(version);assertFalse(restarted.settings().getBoolean("configured"));
    }

    @Test public void modelFailuresNeverEchoProviderSecretsAndExplicitRulesStillWork()throws Exception {
        LocalPlanningEngine engine=engine();ModelServer server=server("unused");engine.saveSettings(config(server,"sk-private-error",0));
        server.status=401;server.rawBody="{\"error\":\"upstream-secret-sk-private-error\"}";
        try{parse(engine);fail("Authentication failure must not masquerade as model output");}
        catch(LocalPlanningException expected){assertFalse(expected.getMessage().contains("sk-private-error"));assertFalse(expected.getMessage().contains("upstream-secret"));}
        server.status=200;server.rawBody="not-json";
        try{parse(engine);fail("Malformed model output must not produce a draft");}
        catch(LocalPlanningException expected){assertFalse(expected.getMessage().contains("not-json"));}
        server.close();
        JSONObject result=engine.parse("明天下午三点到四点阅读","2036-02-01",Collections.emptyList(),true);
        assertEquals("rules",result.getString("source"));assertEquals(1,result.getJSONArray("drafts").length());
        assertTrue(engine.settings().getBoolean("configured"));
    }

    @Test public void redirectCannotSendTheModelKeyToAnotherEndpoint()throws Exception {
        LocalPlanningEngine engine=engine();ModelServer source=server("source"),target=server("target");
        source.status=302;source.location=target.base()+"/chat/completions";source.rawBody="{}";
        try{engine.testSettings(config(source,"sk-no-forward",0));fail("Redirect must be refused");}
        catch(LocalPlanningException expected){assertFalse(expected.getMessage().contains("sk-no-forward"));}
        assertEquals(1,source.requests.size());assertTrue(target.requests.isEmpty());
    }

    @Test public void unreadableEncryptedSettingsCanBeExplicitlyClearedWithoutLosingLocalRules()throws Exception {
        String namespace="model-recovery-"+UUID.randomUUID();LocalPlanningEngine engine=engine(namespace);ModelServer server=server("saved");
        JSONObject saved=engine.saveSettings(config(server,"sk-recovery-fixture",0));
        context.getSharedPreferences(namespace,Context.MODE_PRIVATE).edit().putString("ciphertext","broken").commit();
        JSONObject unreadable=engine.settings();assertEquals(saved.getInt("version"),unreadable.getInt("version"));assertFalse(unreadable.getBoolean("readable"));
        assertFalse(unreadable.toString().contains("sk-recovery-fixture"));
        assertEquals("rules",engine.parse("明天下午三点到四点阅读","2036-02-01",Collections.emptyList(),true).getString("source"));
        engine.clearSettings(unreadable.getInt("version"));assertFalse(engine.settings().getBoolean("configured"));assertTrue(server.requests.isEmpty());
    }

    @Test public void configuredPhoneWithoutConnectivityUsesRulesAndKeepsItsModelForLater()throws Exception {
        Context offline=new ContextWrapper(context){@Override public Object getSystemService(String name){return Context.CONNECTIVITY_SERVICE.equals(name)?null:super.getSystemService(name);}};
        String namespace="offline-model-"+UUID.randomUUID();namespaces.add(namespace);LocalPlanningEngine engine=new LocalPlanningEngine(offline,namespace);engines.add(engine);
        ModelServer server=server("must not be called offline");engine.saveSettings(config(server,"sk-kept-offline",0));
        JSONObject result=parse(engine);assertEquals("rules",result.getString("source"));assertEquals(1,result.getJSONArray("drafts").length());
        assertTrue(result.getJSONArray("warnings").toString().contains("离线"));assertTrue(server.requests.isEmpty());assertTrue(engine.settings().getBoolean("configured"));
    }

    @Test public void unreachableResolvedAddressCanFallBackBeforeSendingAnyKeyButAllAnswersMustBeSafe()throws Exception {
        ModelServer server=server("fallback");String namespace="model-address-fallback-"+UUID.randomUUID();namespaces.add(namespace);
        LocalPlanningEngine engine=new LocalPlanningEngine(context,namespace,host->new InetAddress[]{InetAddress.getByName("127.0.0.2"),InetAddress.getByName("127.0.0.1")});engines.add(engine);
        String host="model.test:"+server.listener.getLocalPort();JSONObject input=config(server,"sk-one-safe-request",0).put("base_url","http://"+host+"/v1");
        assertTrue(engine.testSettings(input).getBoolean("ok"));assertEquals(1,server.requests.size());
        assertEquals("Bearer sk-one-safe-request",server.requests.get(0).authorization);assertEquals(host,server.requests.get(0).host);
        String blockedNamespace="model-address-reject-"+UUID.randomUUID();namespaces.add(blockedNamespace);
        LocalPlanningEngine blocked=new LocalPlanningEngine(context,blockedNamespace,ignored->new InetAddress[]{InetAddress.getByName("127.0.0.1"),InetAddress.getByName("169.254.169.254")});engines.add(blocked);
        try{blocked.testSettings(input);fail("An unsafe later DNS answer must reject the whole request before sending a credential");}
        catch(LocalPlanningException expected){assertEquals("MODEL_ADDRESS_BLOCKED",expected.code);}
        assertEquals("No request reaches even the safe first answer when another answer is unsafe",1,server.requests.size());
    }

    @Test public void twoPairedPhonesKeepSeparateModelsAndExchangeOnlyCalendarRecords()throws Exception {
        LocalPlanningEngine first=engine(),second=engine();ModelServer modelA=server("A模型结果"),modelB=server("B模型结果");
        String keyA="sk-only-phone-A",keyB="sk-only-phone-B";first.saveSettings(config(modelA,keyA,0));second.saveSettings(config(modelB,keyB,0));
        String dbA="model-phone-a-"+UUID.randomUUID()+".sqlite",dbB="model-phone-b-"+UUID.randomUUID()+".sqlite";
        LocalScheduleStore a=new LocalScheduleStore(context,dbA),b=new LocalScheduleStore(context,dbB);DeviceHostRuntime ha=null,hb=null;
        PeerHttp http=new PeerHttp();
        try {
            a.initialize("");b.initialize("");a.rememberListenPort(0);b.rememberListenPort(0);
            Handler handler=new Handler(Looper.getMainLooper());ha=new DeviceHostRuntime(context,a,handler,()->{});hb=new DeviceHostRuntime(context,b,handler,()->{});
            String originA="http://127.0.0.1:"+ha.start()+"/",originB="http://127.0.0.1:"+hb.start()+"/";
            JSONObject callback=hb.callbackGrant(a.deviceId(),"测试手机A","android");String challenge=PeerIdentity.challenge();
            JSONObject grant=http.request(originA,"api/pairing/redeem","POST",new JSONObject().put("token",ha.issueTicket().getString("token"))
                .put("device_id",b.deviceId()).put("device_name","测试手机B").put("platform","android").put("challenge",challenge)
                .put("return_connection",new JSONObject().put("origin",originB).put("token",callback.getString("token")).put("peer_id",b.deviceId()).put("space_id",b.primarySpaceId()).put("public_key",hb.publicKey())),null,()->false).body;
            hb.confirmGrant(callback.getString("pairing_id"));
            LocalDateTime time=LocalDateTime.of(2036,2,1,9,0);b.save(b.primaryScope(),null,LocalScheduleStore.payload("共享的日程",time,time.plusHours(1),"planned",false));
            JSONObject outbound=new JSONObject().put("device_id",b.deviceId()).put("records",b.replica().page(null).getJSONArray("records"));
            JSONObject response=http.request(originA,"api/replica/exchange","POST",outbound,grant.getString("device_token"),()->false).body;
            b.replica().merge(response.getJSONArray("records"));assertEquals(1,a.list(a.primaryScope()).size());
            String wire=outbound.toString()+response.toString();for(String forbidden:new String[]{keyA,keyB,"api_key","base_url","fixture-model"})assertFalse("Pairing only exchanges schedules",wire.contains(forbidden));
            for(String path:new String[]{"api/ai/parse","api/model-settings","api/devices/web-session"}) {
                try{http.request(originA,path,path.equals("api/model-settings")?"GET":"POST",path.equals("api/model-settings")?null:new JSONObject(),grant.getString("device_token"),()->false);fail("Peer grant must not access "+path);}
                catch(PeerHttp.Failure expected){assertTrue(expected.status==403||expected.status==404||expected.status==410);}
            }
            assertEquals("A模型结果",parse(first).getJSONArray("drafts").getJSONObject(0).getString("title"));
            assertEquals("B模型结果",parse(second).getJSONArray("drafts").getJSONObject(0).getString("title"));
            assertEquals("Bearer "+keyA,modelA.requests.get(0).authorization);assertEquals("Bearer "+keyB,modelB.requests.get(0).authorization);
            ha.stop();hb.stop();
            assertEquals("model",parse(first).getString("source"));assertEquals("model",parse(second).getString("source"));
        } finally {http.cancel();if(ha!=null)ha.stop();if(hb!=null)hb.stop();new DeviceCredentials(context,a.deviceId()).remove(b.deviceId());a.close();b.close();context.deleteDatabase(dbA);context.deleteDatabase(dbB);}
    }

    private static final class Request {
        final String path,authorization,host;final JSONObject body;
        Request(String path,String authorization,String host,JSONObject body){this.path=path;this.authorization=authorization;this.host=host;this.body=body;}
    }
    /** A model provider HTTP server, deliberately independent of the peer-server transport. */
    static final class ModelServer implements AutoCloseable {
        final ServerSocket listener;final Thread worker;final List<Request> requests=Collections.synchronizedList(new ArrayList<>());
        volatile int status=200;volatile String rawBody,location;volatile boolean running=true;final String title;
        ModelServer(String title)throws Exception {
            this.title=title;listener=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(()->{while(running)try(Socket socket=listener.accept()){socket.setSoTimeout(5000);serve(socket);}catch(Exception ignored){if(!running)return;}},"standalone-model-fixture");worker.setDaemon(true);worker.start();
        }
        String base(){return "http://127.0.0.1:"+listener.getLocalPort()+"/v1";}
        void serve(Socket socket)throws Exception {
            InputStream input=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int previous=0,next;
            while((next=input.read())!=-1){headers.write(next);previous=(previous<<8)|next;if(previous==0x0d0a0d0a)break;if(headers.size()>16384)throw new IllegalArgumentException();}
            String[] lines=headers.toString(StandardCharsets.US_ASCII.name()).split("\r\n");int length=0;String authorization=null,host=null;
            for(String line:lines){int split=line.indexOf(':');if(split<0)continue;String name=line.substring(0,split).trim();String value=line.substring(split+1).trim();if(name.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(value);if(name.equalsIgnoreCase("Authorization"))authorization=value;if(name.equalsIgnoreCase("Host"))host=value;}
            if(length<0||length>65536)throw new IllegalArgumentException();byte[] bytes=new byte[length];int read=0;while(read<length){int count=input.read(bytes,read,length-read);if(count<0)throw new IllegalArgumentException();read+=count;}
            JSONObject request=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            requests.add(new Request(lines[0].split(" ")[1],authorization,host,request));
            JSONObject draft=new JSONObject().put("title",title).put("start_local","2036-02-02T15:00:00").put("end_local","2036-02-02T16:00:00").put("timezone","Asia/Shanghai").put("time_source","explicit").put("assumptions",new JSONArray());
            String content=new JSONObject().put("intent","create_events").put("needs_clarification",false).put("questions",new JSONArray()).put("events",new JSONArray().put(draft)).toString();
            if(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("模型连接测试"))content="{\"ok\":true}";
            String answer=rawBody!=null?rawBody:new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",content)))).toString();
            byte[] response=answer.getBytes(StandardCharsets.UTF_8);String responseHeaders="HTTP/1.1 "+status+" Result\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n"+(location==null?"":"Location: "+location+"\r\n")+"\r\n";
            socket.getOutputStream().write(responseHeaders.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(response);socket.getOutputStream().flush();
        }
        public void close()throws Exception {running=false;listener.close();worker.join(1000);}
    }
}
