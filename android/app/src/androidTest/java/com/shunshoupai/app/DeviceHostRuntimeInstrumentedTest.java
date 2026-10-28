package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class DeviceHostRuntimeInstrumentedTest {
    private Context context;
    private LocalScheduleStore a, b;
    private DeviceHostRuntime ha, hb;
    private String databaseA, databaseB, originA, originB;
    private final PeerHttp http = new PeerHttp();

    @Before public void open() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        databaseA = "host-a-" + UUID.randomUUID() + ".sqlite";
        databaseB = "host-b-" + UUID.randomUUID() + ".sqlite";
        a = new LocalScheduleStore(context, databaseA); b = new LocalScheduleStore(context, databaseB);
        a.initialize(""); b.initialize(""); a.rememberListenPort(0); b.rememberListenPort(0);
        ha = new DeviceHostRuntime(context, a, new Handler(Looper.getMainLooper()), () -> {});
        hb = new DeviceHostRuntime(context, b, new Handler(Looper.getMainLooper()), () -> {});
        originA = "http://127.0.0.1:" + ha.start() + "/";
        originB = "http://127.0.0.1:" + hb.start() + "/";
    }
    @After public void close() {
        http.cancel(); if (ha != null) ha.stop(); if (hb != null) hb.stop();
        String idA = a == null ? null : a.deviceId(), idB = b == null ? null : b.deviceId();
        if (a != null) { new DeviceCredentials(context, idA).remove(idB); a.close(); }
        if (b != null) { new DeviceCredentials(context, idB).remove(idA); b.close(); }
        if (context != null) { context.deleteDatabase(databaseA); context.deleteDatabase(databaseB); }
    }
    private JSONObject request(String origin, String path, String method, JSONObject body, String token) throws Exception {
        return http.request(origin, path, method, body, token, () -> false).body;
    }
    private JSONObject payload(String title) throws Exception { return new JSONObject().put("title", title).put("start_local", "2034-01-01T09:00:00").put("end_local", "2034-01-01T10:00:00").put("timezone", "Asia/Shanghai").put("status", "planned").put("deleted", false); }
    private JSONObject item(LocalScheduleStore store, String id) throws Exception { for (JSONObject value : store.list(store.primaryScope())) if (id.equals(value.getString("id"))) return value; throw new AssertionError("Missing local event"); }
    private JSONObject pair() throws Exception {
        String challenge = PeerIdentity.challenge();
        JSONObject proof = request(originA, "api/devices/identity?challenge=" + challenge + "&device_id=" + b.deviceId(), "GET", null, null);
        PeerIdentity peer = PeerIdentity.verify(proof, challenge, b.deviceId(), null, null, null);
        JSONObject callback = hb.callbackGrant(a.deviceId(), a.deviceName(), "android");
        JSONObject result = request(originA, "api/pairing/redeem", "POST", new JSONObject().put("token", ha.issueTicket().getString("token"))
            .put("device_id", b.deviceId()).put("device_name", b.deviceName()).put("platform", "android").put("challenge", challenge)
            .put("return_connection", new JSONObject().put("origin", originB).put("token", callback.getString("token"))
                .put("peer_id", b.deviceId()).put("space_id", b.primarySpaceId()).put("public_key", hb.publicKey())), null);
        PeerIdentity.verify(result, challenge, b.deviceId(), peer.publicKey, peer.peerId, peer.spaceId);
        new DeviceCredentials(context, b.deviceId()).save(peer.peerId, peer.spaceId, result.getString("device_token"));
        b.registerPeer(originA, peer, result.getString("pairing_id"), proof.getJSONObject("capabilities"));
        hb.confirmGrant(callback.getString("pairing_id"));
        return result;
    }
    private void exchange(LocalScheduleStore sender, LocalScheduleStore receiver, String origin) throws Exception {
        String token = new DeviceCredentials(context, sender.deviceId()).read(receiver.deviceId(), receiver.primarySpaceId());
        assertNotNull(token);
        JSONObject result = request(origin, "api/replica/exchange", "POST", new JSONObject().put("device_id", sender.deviceId())
            .put("records", sender.replica().page(null).getJSONArray("records")), token);
        assertEquals(receiver.deviceId(), result.getString("peer_id"));
        assertEquals(receiver.primarySpaceId(), result.getString("space_id"));
        sender.replica().merge(result.getJSONArray("records"));
    }

    @Test public void twoPhonesPairWithoutComputerAndEitherCanMergeOfflineEdits() throws Exception {
        String eventA = a.save(a.primaryScope(), null, payload("手机A离线安排"));
        String eventB = b.save(b.primaryScope(), null, payload("手机B离线安排"));
        JSONObject paired = pair();
        assertEquals(1, a.peers().size()); assertEquals(1, b.peers().size());
        assertFalse(paired.getJSONObject("capabilities").getBoolean("web"));
        exchange(b, a, originA);
        assertEquals("手机B离线安排", item(a, eventB).getString("title"));
        assertEquals("手机A离线安排", item(b, eventA).getString("title"));
        a.save(a.primaryScope(), eventA, payload("A后续修改"), item(a, eventA).getInt("version"));
        exchange(a, b, originB);
        assertEquals("A后续修改", item(b, eventA).getString("title"));
        a.save(a.primaryScope(), eventA, payload("A并发修改"), item(a, eventA).getInt("version"));
        b.save(b.primaryScope(), eventA, payload("B并发修改"), item(b, eventA).getInt("version"));
        exchange(a, b, originB); exchange(b, a, originA);
        assertEquals(2, item(a, eventA).getJSONArray("replica_conflicts").length());
        assertEquals(2, item(b, eventA).getJSONArray("replica_conflicts").length());
        int version = item(a, eventA).getInt("version");
        exchange(a, b, originB); exchange(b, a, originA);
        assertEquals(version, item(a, eventA).getInt("version"));
    }

    @Test public void grantsAndIdentitySurviveRestartButExplicitUnpairStopsExchange() throws Exception {
        JSONObject paired = pair(); String token = paired.getString("device_token"); String key = ha.publicKey();
        String event = b.save(b.primaryScope(), null, payload("重启前保留")); exchange(b, a, originA);
        String oldTicket = ha.issueTicket().getString("token");
        ha.stop(); ha = new DeviceHostRuntime(context, a, new Handler(Looper.getMainLooper()), () -> {});
        originA = "http://127.0.0.1:" + ha.start() + "/";
        assertEquals(key, ha.publicKey());
        assertEquals(a.primarySpaceId(), request(originA, "api/devices/info", "GET", null, token).getString("space_id"));
        try { request(originA, "api/pairing/redeem", "POST", new JSONObject().put("token", oldTicket).put("device_id", UUID.randomUUID().toString()).put("device_name", "第三台").put("platform", "android"), null); fail("Restart must invalidate outstanding QR"); }
        catch (PeerHttp.Failure expected) { assertEquals("PAIRING_INVALID", expected.code); }
        request(originA, "api/devices/" + paired.getString("pairing_id"), "DELETE", new JSONObject(), token);
        try { request(originA, "api/replica/exchange", "POST", new JSONObject().put("device_id", b.deviceId()).put("records", new JSONArray()), token); fail("Revoked grant must fail"); }
        catch (PeerHttp.Failure expected) { assertEquals(401, expected.status); }
        assertEquals("重启前保留", item(a, event).getString("title")); assertEquals("重启前保留", item(b, event).getString("title"));
    }

    @Test public void anonymousAndMismatchedClientsCannotReadOrWriteSchedules() throws Exception {
        try { request(originA, "api/devices/info", "GET", null, null); fail("Anonymous access must fail"); }
        catch (PeerHttp.Failure expected) { assertEquals(401, expected.status); }
        JSONObject paired = pair();
        try { request(originA, "api/replica/exchange", "POST", new JSONObject().put("device_id", UUID.randomUUID().toString()).put("records", new JSONArray()), paired.getString("device_token")); fail("Wrong physical client must fail"); }
        catch (PeerHttp.Failure expected) { assertEquals(403, expected.status); }
        assertEquals(0, a.list(a.primaryScope()).size());
        try { request(originA, "api/devices/" + UUID.randomUUID(), "DELETE", new JSONObject(), paired.getString("device_token")); fail("Cannot revoke a different client"); }
        catch (PeerHttp.Failure expected) { assertEquals(404, expected.status); }
    }

    @Test public void revokedPendingCallbackGrantCannotBeConfirmedByALatePairResponse() throws Exception {
        JSONObject pending = ha.callbackGrant(b.deviceId(), b.deviceName(), "android");
        ha.revokeClient(b.deviceId());
        try { ha.confirmGrant(pending.getString("pairing_id")); fail("A late callback must not revive a revoked grant"); }
        catch (PeerHttp.Failure expected) { assertEquals("PAIRING_CANCELLED", expected.code); }
        JSONObject fresh = ha.callbackGrant(b.deviceId(), b.deviceName(), "android");
        ha.confirmGrant(fresh.getString("pairing_id"));
        try { request(originA, "api/devices/" + pending.getString("pairing_id"), "DELETE", new JSONObject(), pending.getString("token")); fail("Old grant must not revoke a new pairing"); }
        catch (PeerHttp.Failure expected) { assertEquals(401, expected.status); }
        assertEquals(a.primarySpaceId(), request(originA, "api/devices/info", "GET", null, fresh.getString("token")).getString("space_id"));
    }

    @Test public void stoppingDuringCallbackVerificationCannotRestoreAnOldPendingPairing() throws Exception {
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        LocalDeviceIdentity identityB = new LocalDeviceIdentity(context, b.deviceId());
        HostedPeerServer delayed = new HostedPeerServer(request -> {
            reached.countDown(); release.await(5, TimeUnit.SECONDS);
            JSONObject proof = identityB.proof(request.query.get("challenge"), request.query.get("device_id"), b.deviceId(), b.primarySpaceId());
            proof.put("device", new JSONObject().put("name", "B"));
            return new HostedPeerServer.Response(200, proof);
        });
        String slowOrigin = "http://127.0.0.1:" + delayed.start(0) + "/";
        String ticket = ha.issueTicket().getString("token");
        JSONObject body = new JSONObject().put("token", ticket).put("device_id", b.deviceId()).put("device_name", "B").put("platform", "android")
            .put("return_connection", new JSONObject().put("origin", slowOrigin).put("token", PeerIdentity.challenge()).put("peer_id", b.deviceId()).put("space_id", b.primarySpaceId()).put("public_key", identityB.publicKey()));
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> { try { request(originA, "api/pairing/redeem", "POST", body, null); return true; } catch (Exception expected) { return false; } });
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS)); ha.stop(); ha.start(); release.countDown();
            assertFalse(result.get(10, TimeUnit.SECONDS)); assertEquals(0, a.peers().size());
        } finally { release.countDown(); delayed.close(); }
    }
}
