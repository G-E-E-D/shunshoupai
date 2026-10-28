package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The actual foreground client exercises pairing and independent upload/download pages over HTTP. */
@RunWith(AndroidJUnit4.class)
public class PeerClientInstrumentedTest {
    private Context context;
    private LocalScheduleStore a,b;
    private DeviceHostRuntime ha,hb;
    private ScheduleSync sa,sb;
    private String dbA,dbB,originA,originB;
    private final AtomicReference<CountDownLatch> waitingA=new AtomicReference<>(),waitingB=new AtomicReference<>();
    @Before public void open() throws Exception {
        context=ApplicationProvider.getApplicationContext();dbA="client-a-"+UUID.randomUUID()+".sqlite";dbB="client-b-"+UUID.randomUUID()+".sqlite";
        a=new LocalScheduleStore(context,dbA);b=new LocalScheduleStore(context,dbB);a.initialize("");b.initialize("");a.rememberListenPort(0);b.rememberListenPort(0);
        Handler handler=new Handler(Looper.getMainLooper());ha=new DeviceHostRuntime(context,a,handler,()->{});hb=new DeviceHostRuntime(context,b,handler,()->{});
        originA="http://127.0.0.1:"+ha.start()+"/";originB="http://127.0.0.1:"+hb.start()+"/";
        sa=new ScheduleSync(context,a,handler,ha,(o,s,m,p)->{CountDownLatch latch=waitingA.get();if(latch!=null)latch.countDown();});
        sb=new ScheduleSync(context,b,handler,hb,(o,s,m,p)->{CountDownLatch latch=waitingB.get();if(latch!=null)latch.countDown();});
        sa.setReturnOrigin(()->originA);sb.setReturnOrigin(()->originB);
    }
    @After public void close() {
        if(ha!=null)ha.stop();if(hb!=null)hb.stop();if(sa!=null)sa.close();if(sb!=null)sb.close();
        // Unique databases intentionally remain until the runner process exits: close() drains its worker.
    }
    private JSONObject payload(String title)throws Exception{return new JSONObject().put("title",title).put("start_local","2036-02-01T09:00:00").put("end_local","2036-02-01T10:00:00").put("timezone","Asia/Shanghai").put("status","planned").put("deleted",false);}
    private JSONObject row(LocalScheduleStore store,String id)throws Exception{for(JSONObject row:store.list(store.primaryScope()))if(id.equals(row.getString("id")))return row;throw new AssertionError("Missing event");}
    private void pair()throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        ScannedConnection code=ScannedConnection.parse(originB+"#pair="+hb.issueTicket().getString("token"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->sa.pair(code,(o,s,e)->{error.set(e);done.countDown();}));
        assertTrue("Pair callback",done.await(10,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(a.peer(b.deviceId()));assertNotNull(b.peer(a.deviceId()));
    }
    private void sync(ScheduleSync client,AtomicReference<CountDownLatch> pending)throws Exception{
        CountDownLatch done=new CountDownLatch(1);pending.set(done);InstrumentationRegistry.getInstrumentation().runOnMainSync(client::sync);assertTrue("Sync callback",done.await(30,TimeUnit.SECONDS));pending.set(null);
    }
    @Test public void actualClientPairsPhonesAndMergesBothIndependentPaginationDirections()throws Exception {
        for(int i=0;i<62;i++)a.save(a.primaryScope(),null,payload("A离线 "+i));
        for(int i=0;i<71;i++)b.save(b.primaryScope(),null,payload("B离线 "+i));
        String primary=a.primaryScope();pair();assertEquals(primary,a.primaryScope());assertNotEquals(a.primarySpaceId(),b.primarySpaceId());
        sync(sa,waitingA);assertEquals("已合并",sa.status(b.deviceId()));assertEquals(133,a.list(primary).size());assertEquals(133,b.list(b.primaryScope()).size());
        String id=a.list(primary).get(0).getString("id");a.save(primary,id,payload("A并发"),row(a,id).getInt("version"));b.save(b.primaryScope(),id,payload("B并发"),row(b,id).getInt("version"));
        sync(sb,waitingB);assertEquals(2,row(a,id).getJSONArray("replica_conflicts").length());assertEquals(2,row(b,id).getJSONArray("replica_conflicts").length());
        int version=row(a,id).getInt("version");sync(sa,waitingA);assertEquals(version,row(a,id).getInt("version"));
        assertNull("Phone peer must not be selected as a web workspace",a.webPeer());
    }
    @Test public void nativeUnpairRevokesBothDirectionsAndPreservesLocalData()throws Exception {
        String id=a.save(a.primaryScope(),null,payload("取消配对继续保留"));pair();sync(sa,waitingA);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->sa.unpair(b.deviceId(),error->{failure.set(error);done.countDown();}));
        assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(failure.get());assertFalse(a.peer(b.deviceId()).getBoolean("enabled"));assertFalse(b.peer(a.deviceId()).getBoolean("enabled"));
        assertEquals("取消配对继续保留",row(a,id).getString("title"));assertEquals("取消配对继续保留",row(b,id).getString("title"));
        assertNull(new DeviceCredentials(context,a.deviceId()).read(b.deviceId(),b.primarySpaceId()));
        assertNull(new DeviceCredentials(context,b.deviceId()).read(a.deviceId(),a.primarySpaceId()));
    }
    @Test public void unavailableKeystoreCredentialRequiresExplicitPairingInsteadOfEndlessRetry()throws Exception {
        String id=a.save(a.primaryScope(),null,payload("凭据失效仍保留"));pair();
        new DeviceCredentials(context,a.deviceId()).remove(b.deviceId());sync(sa,waitingA);
        assertFalse(a.peer(b.deviceId()).getBoolean("enabled"));assertTrue(sa.status(b.deviceId()).contains("重新扫码"));
        assertEquals("凭据失效仍保留",row(a,id).getString("title"));
    }
    @Test public void revokedPendingCallbackCannotBeRestoredByLateRedeemResponse()throws Exception {
        CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();PeerHttp proxyHttp=new PeerHttp();
        HostedPeerServer proxy=new HostedPeerServer(request->{
            String path=request.path.substring(1);
            if(path.equals("api/devices/identity"))path+="?challenge="+request.query.get("challenge")+"&device_id="+request.query.get("device_id");
            JSONObject response=proxyHttp.request(originB,path,request.method,"GET".equals(request.method)?null:request.body,null,()->false).body;
            if(path.equals("api/pairing/redeem")){reached.countDown();release.await(10,TimeUnit.SECONDS);}
            return new HostedPeerServer.Response(200,response);
        });
        try {
            String proxyOrigin="http://127.0.0.1:"+proxy.start(0)+"/";
            ScannedConnection code=ScannedConnection.parse(proxyOrigin+"#pair="+hb.issueTicket().getString("token"));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->sa.pair(code,(o,s,e)->{error.set(e);done.countDown();}));
            assertTrue(reached.await(10,TimeUnit.SECONDS));
            synchronized(a){ha.revokeClient(b.deviceId());a.unpair(b.deviceId());}
            release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));assertNotNull(error.get());
            JSONObject peer=a.peer(b.deviceId());assertTrue(peer==null||!peer.getBoolean("enabled"));
            assertNull(new DeviceCredentials(context,a.deviceId()).read(b.deviceId(),b.primarySpaceId()));
        } finally {release.countDown();proxy.stop();proxyHttp.cancel();}
    }
}
