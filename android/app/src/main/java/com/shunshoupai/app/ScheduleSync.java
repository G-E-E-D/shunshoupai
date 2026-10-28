package com.shunshoupai.app;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Explicit peers exchange the local primary calendar. Trusted-LAN HTTP is not channel authentication. */
final class ScheduleSync {
    interface Listener {void result(String origin,String scope,String message,boolean pairingRequired);}
    interface PairCallback {void complete(String origin,String scope,String error);}
    interface CompletionCallback {void complete(String error);}
    private static final String END_CURSOR="ffffffff-ffff-ffff-ffff-ffffffffffff";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler;private final LocalScheduleStore store;private final DeviceCredentials credentials;private final DeviceHostRuntime host;private final Listener listener;private final PeerHttp http=new PeerHttp();
    private final Object lifecycle=new Object();private final Map<String,String> statuses=new ConcurrentHashMap<>();
    private Supplier<String> returnOrigin;private boolean busy;private volatile boolean closed;private volatile long generation;

    ScheduleSync(Context context,LocalScheduleStore store,Handler handler,DeviceHostRuntime host,Listener listener){
        this.store=store;this.handler=handler;this.host=host;this.listener=listener;credentials=new DeviceCredentials(context,store.deviceId());
        returnOrigin=()->{List<String> origins=DevicePairingDialog.lanOrigins(context,host.port());return origins.isEmpty()?null:origins.get(0);};
    }
    // Endpoint discovery is injected by local integration tests to cross emulator NAT.
    // It never bypasses private-address checks, peer identity, or credential validation.
    void setReturnOrigin(Supplier<String> provider){returnOrigin=provider;}
    String status(String peerId){return statuses.getOrDefault(peerId,"设备可达时自动同步");}

    void sync(){
        if(busy||closed||!host.isRunning())return;final List<JSONObject> peers;try{peers=store.peers();}catch(Exception error){return;}
        busy=true;final long expected=generation;
        worker.execute(()->{int connected=0,unavailable=0;boolean needsPairing=false;
            for(JSONObject peer:peers){if(cancelled(expected))break;if(!peer.optBoolean("enabled"))continue;String id=peer.optString("peer_id"),token=credentials.read(id,peer.optString("space_id"));
                try{if(token==null)throw new PeerHttp.Failure(401,"MISSING_CREDENTIAL","重新扫码后继续同步");syncPeer(peer,token,expected);statuses.put(id,"已合并");connected++;}
                catch(Exception error){unavailable++;String message=message(error);statuses.put(id,message);
                    if(error instanceof PeerHttp.Failure&&(((PeerHttp.Failure)error).status==401||((PeerHttp.Failure)error).status==403)){
                        synchronized(lifecycle){synchronized(store){if(!cancelled(expected)&&java.util.Objects.equals(token,credentials.read(id,peer.optString("space_id")))){revokeLocally(id);credentials.remove(id);needsPairing=true;}}}
                    }
                }
            }
            final String message=needsPairing?"设备配对已失效，请重新扫码；本机日程继续保留":connected>0?("已与 "+connected+" 台设备合并"+(unavailable>0?"，其余设备可达后继续":"")):unavailable>0?"本机日程已保存，对方可达后自动合并":"日程保存在本机，随时可以继续安排";final boolean pairing=needsPairing;
            handler.post(()->{if(!cancelled(expected)){busy=false;listener.result("",store.primaryScope(),message,pairing);}});
        });
    }
    private void syncPeer(JSONObject peer,String token,long expected)throws Exception{
        verifyAddress(peer,expected);String pushCursor=null,pullCursor=null;boolean pushDone=false,pullDone=false;long started=SystemClock.elapsedRealtime();
        for(int pages=0;pages<10000&&SystemClock.elapsedRealtime()-started<120000;pages++){
            current(expected);JSONObject active=store.peer(peer.getString("peer_id"));if(active==null||!active.optBoolean("enabled"))return;
            JSONObject push=pushDone?new JSONObject().put("records",new JSONArray()).put("has_more",false):store.replica().page(pushCursor);
            JSONObject body=new JSONObject().put("device_id",store.deviceId()).put("records",push.getJSONArray("records"));if(pullCursor!=null)body.put("after",pullCursor);
            JSONObject response=http.request(peer.getString("origin"),"api/replica/exchange","POST",body,token,()->cancelled(expected)).body;checkPeer(response,peer);
            JSONArray received=response.getJSONArray("records");if(received.length()>50)throw new SecurityException("对方返回的同步页过大。");
            synchronized(lifecycle){current(expected);synchronized(store){
                JSONObject stillPaired=store.peer(peer.getString("peer_id"));
                if(stillPaired==null||!stillPaired.optBoolean("enabled")||!token.equals(credentials.read(peer.getString("peer_id"),peer.getString("space_id"))))return;
                store.replica().merge(received);store.replica().markSent(push.getJSONArray("records"));
            }}
            pushDone=!push.getBoolean("has_more");if(!pushDone)pushCursor=push.getString("next_cursor");
            boolean more=response.getBoolean("has_more");String cursor=more?AncestryReplica.uuid(response.getString("next_cursor")):null;
            if(!pullDone&&more&&(pullCursor!=null&&cursor.compareTo(pullCursor)<=0))throw new SecurityException("对方同步游标无效。");
            pullDone=pullDone||!more;pullCursor=pullDone?END_CURSOR:cursor;if(pushDone&&pullDone)return;
        }
        throw new java.io.IOException("本轮同步未完成，稍后继续");
    }

    void pair(ScannedConnection scanned,PairCallback callback){
        invalidate();busy=true;final long expected=generation;
        worker.execute(()->{String error=null,callbackGrant=null;boolean committed=false;
            try{
                if(!host.isRunning())throw new IllegalStateException("请保持应用前台后再配对。");String origin=PeerHttp.origin(scanned.serviceRoot),clientId=store.deviceId(),challenge=PeerIdentity.challenge();
                JSONObject advertised=http.request(origin,identityPath(challenge),"GET",null,null,()->cancelled(expected)).body;
                PeerIdentity identity=PeerIdentity.verify(advertised,challenge,clientId,null,null,null);if(clientId.equals(identity.peerId))throw new SecurityException("这是本机配对码，请在另一台设备扫描。");
                JSONObject previous=store.peer(identity.peerId);if(previous!=null)PeerIdentity.verify(advertised,challenge,clientId,previous.getString("public_key"),previous.getString("peer_id"),previous.getString("space_id"));
                String ownOrigin=returnOrigin.get();if(ownOrigin==null)throw new SecurityException("请连接同一局域网后再配对。");ownOrigin=PeerHttp.origin(ownOrigin);
                JSONObject grant; synchronized(lifecycle){current(expected);grant=host.callbackGrant(identity.peerId,identity.name,advertised.optJSONObject("device")!=null&&"windows".equals(advertised.getJSONObject("device").optString("platform"))?"windows":"android");}callbackGrant=grant.getString("pairing_id");
                JSONObject back=new JSONObject().put("origin",ownOrigin).put("token",grant.getString("token")).put("peer_id",clientId).put("space_id",store.primarySpaceId()).put("public_key",host.publicKey());
                JSONObject body=new JSONObject().put("token",scanned.pairingToken).put("device_id",clientId).put("device_name",store.deviceName()).put("platform","android").put("challenge",challenge).put("return_connection",back);
                JSONObject redeemed=http.request(origin,"api/pairing/redeem","POST",body,null,()->cancelled(expected)).body;
                PeerIdentity confirmed=PeerIdentity.verify(redeemed,challenge,clientId,identity.publicKey,identity.peerId,identity.spaceId);String token=redeemed.getString("device_token");if(!token.matches("[A-Za-z0-9_-]{43}"))throw new SecurityException("配对凭据无效。");
                String pairingId=redeemed.has("pairing_id")?PeerIdentity.uuid(redeemed.getString("pairing_id")):"";JSONObject capabilities=redeemed.optJSONObject("capabilities");if(capabilities==null)capabilities=new JSONObject();
                synchronized(lifecycle){current(expected);synchronized(store){
                    assertCallbackActive(callbackGrant,confirmed.peerId);String old=credentials.read(confirmed.peerId,confirmed.spaceId);credentials.save(confirmed.peerId,confirmed.spaceId,token);try{store.registerPeer(origin,confirmed,pairingId,capabilities);host.confirmGrant(callbackGrant);committed=true;}catch(Exception failed){if(old==null)credentials.remove(confirmed.peerId);else credentials.save(confirmed.peerId,confirmed.spaceId,old);throw failed;}}}
            }catch(Exception failure){error=message(failure);}finally{if(!committed&&callbackGrant!=null)host.revokeGrant(callbackGrant);}
            final String result=error;handler.post(()->{if(!cancelled(expected)){busy=false;callback.complete(scanned.serviceRoot,store.primaryScope(),result);}});
        });
    }

    void unpair(String peerId,CompletionCallback callback){
        invalidate();final long expected=generation;final JSONObject peer;final String token;
        try{synchronized(store){peer=store.peer(peerId);token=peer==null?null:credentials.read(peerId,peer.getString("space_id"));revokeLocally(peerId);credentials.remove(peerId);}}catch(Exception error){callback.complete("无法更新配对状态，请重试。");return;}
        worker.execute(()->{String error=null;try{if(peer!=null&&token!=null){verifyAddress(peer,expected);JSONObject info=http.request(peer.getString("origin"),"api/devices/info","GET",null,token,()->cancelled(expected)).body;checkPeer(info,peer);String grantId=info.getJSONObject("peer").getString("id");http.request(peer.getString("origin"),"api/devices/"+PeerIdentity.uuid(grantId),"DELETE",new JSONObject(),token,()->cancelled(expected));}}catch(PeerHttp.Failure failure){if(failure.status!=401&&failure.status!=404)error="本机已停止双向同步；对方可达时会发现配对已撤销。";}catch(Exception failure){error="本机已停止双向同步；对方可达时会发现配对已撤销。";}String message=error;handler.post(()->{if(!cancelled(expected))callback.complete(message);});});
    }
    private void assertCallbackActive(String grantId,String peerId) {
        if(!host.isRunning())throw new SecurityException("配对已暂停，请重新扫码。");
        try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT 1 FROM device_incoming WHERE pairing_id=? AND client_id=? AND revoked=0",new String[]{grantId,peerId})){
            if(!row.moveToFirst())throw new SecurityException("配对已撤销，请重新扫码。日程仍保留在本机。");
        }
    }
    private void revokeLocally(String peerId) {
        synchronized(store){android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
            try{host.revokeClient(peerId);store.unpair(peerId);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    }
    private void verifyAddress(JSONObject peer,long expected)throws Exception{String challenge=PeerIdentity.challenge();JSONObject proof=http.request(peer.getString("origin"),identityPath(challenge),"GET",null,null,()->cancelled(expected)).body;PeerIdentity.verify(proof,challenge,store.deviceId(),peer.getString("public_key"),peer.getString("peer_id"),peer.getString("space_id"));}
    private String identityPath(String challenge){return "api/devices/identity?challenge="+challenge+"&device_id="+store.deviceId();}
    private void checkPeer(JSONObject response,JSONObject peer)throws Exception{if(!peer.getString("peer_id").equals(response.getString("peer_id"))||!peer.getString("space_id").equals(response.getString("space_id")))throw new SecurityException("对方设备或空间不匹配，已保留本机修改并停止合并。");}
    private String message(Exception error){return error instanceof SecurityException||error instanceof PeerHttp.Failure||error instanceof AncestryReplica.Invalid?error.getMessage():"暂时无法连接对方，本机日程已保存，稍后自动重试。";}
    private boolean cancelled(long expected){return closed||generation!=expected;}
    private void current(long expected)throws java.io.IOException{if(cancelled(expected))throw new java.io.IOException("操作已取消");}
    void invalidate(){synchronized(lifecycle){generation++;busy=false;}http.cancel();}
    void close(){synchronized(lifecycle){closed=true;generation++;}http.cancel();worker.execute(store::close);worker.shutdown();}
}
