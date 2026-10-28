package com.shunshoupai.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** The UI, background connection or scheduled job owns this full, revocable LAN peer. */
final class DeviceHostRuntime {
    private final LocalScheduleStore store;
    private final LocalDeviceIdentity identity;
    private final DeviceCredentials credentials;
    private final Handler handler;
    private final Runnable onChanged;
    private final PeerHttp http = new PeerHttp();
    private final HostedPeerServer server;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final Object ticketLock = new Object();
    private byte[] ticketHash;
    private long ticketDeadline;
    private long rateWindow;
    private int identityRequests, redemptionRequests;

    DeviceHostRuntime(Context context, LocalScheduleStore store, Handler handler, Runnable onChanged) {
        this.store = store;
        this.handler = handler;
        this.onChanged = onChanged;
        identity = new LocalDeviceIdentity(context, store.deviceId());
        credentials = new DeviceCredentials(context, store.deviceId());
        server = new HostedPeerServer(this::handle);
    }

    synchronized int start() throws Exception {
        if (running) return server.port();
        int port = server.start(store.listenPort());
        store.rememberListenPort(port);
        running = true;
        return port;
    }
    synchronized void stop() {
        synchronized (store) { running = false; generation.incrementAndGet(); }
        synchronized (ticketLock) { ticketHash = null; ticketDeadline = 0; }
        http.cancel(); server.close();
    }
    boolean isRunning() { return running; }
    void cancelTicket() { synchronized (ticketLock) { ticketHash = null; ticketDeadline = 0; } }
    int port() { return server.port(); }
    String publicKey() throws Exception { return identity.publicKey(); }

    JSONObject context() throws JSONException {
        return new JSONObject().put("peer_id", store.deviceId()).put("space_id", store.primarySpaceId())
            .put("device", new JSONObject().put("id", store.deviceId()).put("name", store.deviceName()).put("platform", "android"))
            .put("capabilities", new JSONObject().put("replica", 1).put("web", false).put("ai", false))
            .put("legacy_space_ids", new JSONArray().put(store.primarySpaceId()));
    }

    JSONObject issueTicket() throws Exception {
        if (!running) throw new IllegalStateException("请保持顺手排打开后生成配对码。");
        String token = PeerIdentity.challenge();
        synchronized (ticketLock) { ticketHash = digest(token); ticketDeadline = SystemClock.elapsedRealtime() + 90_000; }
        return context().put("token", token).put("expires_at", Instant.ofEpochMilli(System.currentTimeMillis() + 90_000).toString())
            .put("pairing_limited", false).put("session_expires_at", JSONObject.NULL);
    }

    // Pending callback grants do not rotate an existing working connection.
    JSONObject callbackGrant(String clientId, String name, String platform) throws Exception {
        PeerIdentity.uuid(clientId); checkName(name); checkPlatform(platform);
        if (clientId.equals(store.deviceId())) throw failure(400, "SELF_PAIRING", "请在另一台设备使用本机配对码。");
        String token = PeerIdentity.challenge(), id = UUID.randomUUID().toString();
        synchronized (store) {
            store.getWritableDatabase().execSQL("INSERT INTO device_incoming(pairing_id,client_id,name,platform,token_hash,created_at) VALUES(?,?,?,?,?,?)",
                new Object[]{id, clientId, name, platform, hash(token), System.currentTimeMillis()});
        }
        return new JSONObject().put("token", token).put("pairing_id", id);
    }
    void confirmGrant(String id) throws PeerHttp.Failure {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            try (Cursor current = db.rawQuery("SELECT 1 FROM device_incoming WHERE pairing_id=? AND revoked=0", new String[]{id})) {
                if (!current.moveToFirst()) throw failure(409, "PAIRING_CANCELLED", "配对已解除，未恢复连接。");
            }
            db.execSQL("UPDATE device_incoming SET revoked=1 WHERE client_id=(SELECT client_id FROM device_incoming WHERE pairing_id=? AND revoked=0) AND pairing_id<>?", new Object[]{id, id});
        }
    }
    void revokeGrant(String id) { synchronized (store) { generation.incrementAndGet(); store.getWritableDatabase().execSQL("UPDATE device_incoming SET revoked=1 WHERE pairing_id=?", new Object[]{id}); } }
    void revokeClient(String clientId) { synchronized (store) { generation.incrementAndGet(); store.getWritableDatabase().execSQL("UPDATE device_incoming SET revoked=1 WHERE client_id=?", new Object[]{clientId}); } }

    private HostedPeerServer.Response handle(HostedPeerServer.Request request) {
        try {
            final long operation = generation.get();
            if (!running) throw failure(503, "DEVICE_PAUSED", "请将对方的顺手排切换到前台。");
            checkOrigin(request);
            if ("GET".equals(request.method) && "/api/devices/identity".equals(request.path)) {
                limit(false);
                String challenge = request.query.get("challenge"), client = request.query.get("device_id");
                if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) throw failure(400, "VALIDATION_ERROR", "身份验证请求无效。");
                if (client == null) throw failure(400, "VALIDATION_ERROR", "设备标识无效。");
                PeerIdentity.uuid(client);
                JSONObject proof = identity.proof(challenge, client, store.deviceId(), store.primarySpaceId());
                return ok(context().put("identity", proof.getJSONObject("identity")));
            }
            if ("POST".equals(request.method) && "/api/pairing/redeem".equals(request.path)) return ok(redeem(request.body, operation));
            JSONObject grant = authenticate(request.header("authorization"));
            if ("GET".equals(request.method) && ("/api/devices/info".equals(request.path) || "/api/auth/session".equals(request.path))) return ok(session(grant));
            if ("GET".equals(request.method) && "/api/devices".equals(request.path)) return ok(context().put("devices", new JSONArray().put(
                new JSONObject().put("id", grant.getString("pairing_id")).put("client_id", grant.getString("client_id"))
                    .put("name", grant.getString("name")).put("platform", grant.getString("platform")).put("local", false).put("current", true)
                    .put("created_at", Instant.ofEpochMilli(grant.getLong("created_at")).toString()).put("last_seen_at", Instant.now().toString()))));
            if ("DELETE".equals(request.method) && request.path.startsWith("/api/devices/")) {
                String id = request.path.substring("/api/devices/".length());
                if (!id.equals(grant.getString("pairing_id"))) throw failure(404, "NOT_FOUND", "只能解除当前设备自己的授权。");
                String client = grant.getString("client_id");
                synchronized (store) {
                    assertCurrent(operation);
                    authenticate(request.header("authorization"));
                    SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
                    try { revokeClient(client); store.unpair(client); db.setTransactionSuccessful(); }
                    finally { db.endTransaction(); }
                    credentials.remove(client);
                }
                changed();
                return ok(new JSONObject().put("revoked", true).put("id", id));
            }
            if ("POST".equals(request.method) && "/api/replica/exchange".equals(request.path)) {
                keys(request.body, "device_id", "records", "after?");
                String client = PeerIdentity.uuid(text(request.body, "device_id"));
                if (!client.equals(grant.getString("client_id"))) throw failure(403, "DEVICE_MISMATCH", "同步设备与授权不匹配。");
                JSONArray records = request.body.getJSONArray("records");
                String after = request.body.has("after") ? AncestryReplica.uuid(text(request.body, "after")) : null;
                JSONObject page;
                synchronized (store) {
                    // Revocation may have happened while this request waited for the database.
                    assertCurrent(operation);
                    authenticate(request.header("authorization"));
                    store.replica().merge(records);
                    page = store.replica().page(after);
                }
                JSONObject result = context();
                for (String key : new String[]{"records", "has_more", "next_cursor"}) result.put(key, page.get(key));
                if (records.length() > 0) changed();
                return ok(result);
            }
            throw failure(404, "NOT_FOUND", "这台手机仅提供设备配对与日程同步。");
        } catch (PeerHttp.Failure failure) { return HostedPeerServer.Response.error(failure.status, failure.code, failure.getMessage()); }
        catch (AncestryReplica.Invalid failure) { return HostedPeerServer.Response.error(409, failure.code, failure.getMessage()); }
        catch (SecurityException failure) { return HostedPeerServer.Response.error(409, "PEER_IDENTITY_CHANGED", "设备身份不符，原有日程已保留。请重新核对配对设备。"); }
        catch (JSONException | IllegalArgumentException failure) { return HostedPeerServer.Response.error(400, "VALIDATION_ERROR", "请检查设备请求的字段与时间。"); }
        catch (Exception failure) { return HostedPeerServer.Response.error(503, "PEER_UNAVAILABLE", "暂时无法完成设备连接，请保持两边应用打开后重试。"); }
    }

    private JSONObject redeem(JSONObject body, long operation) throws Exception {
        limit(true); keys(body, "token", "device_id", "device_name", "platform?", "challenge?", "return_connection?");
        String token = text(body, "token"), clientId = PeerIdentity.uuid(text(body, "device_id")), name = text(body, "device_name");
        String platform = body.has("platform") ? text(body, "platform") : "web";
        checkName(name); checkPlatform(platform);
        String challenge = body.has("challenge") ? text(body, "challenge") : null;
        if (challenge != null && !challenge.matches("[A-Za-z0-9_-]{43}")) throw failure(400, "VALIDATION_ERROR", "身份验证请求无效。");
        if (clientId.equals(store.deviceId())) throw failure(400, "SELF_PAIRING", "请在另一台设备扫描本机配对码。");
        synchronized (ticketLock) {
            if (!token.matches("[A-Za-z0-9_-]{43}") || ticketHash == null || SystemClock.elapsedRealtime() >= ticketDeadline || !MessageDigest.isEqual(ticketHash, digest(token)))
                throw failure(400, "PAIRING_INVALID", "配对码已过期或已使用，请在显示配对码的设备上重新生成。");
            ticketHash = null;
        }
        JSONObject callback = body.optJSONObject("return_connection");
        PeerIdentity remote = null; String origin = null; String callbackToken = null; JSONObject capabilities = new JSONObject();
        if (body.has("return_connection") && callback == null) throw failure(400, "VALIDATION_ERROR", "回连信息无效。");
        if (callback != null) {
            keys(callback, "origin", "token", "peer_id", "space_id", "public_key");
            if (!clientId.equals(text(callback, "peer_id"))) throw failure(400, "DEVICE_MISMATCH", "回连设备与扫码设备不匹配。");
            origin = PeerHttp.origin(text(callback, "origin")); callbackToken = text(callback, "token");
            if (!callbackToken.matches("[A-Za-z0-9_-]{43}")) throw failure(400, "VALIDATION_ERROR", "回连授权无效。");
            String fresh = PeerIdentity.challenge();
            JSONObject proof = http.request(origin, "api/devices/identity?challenge=" + fresh + "&device_id=" + store.deviceId(), "GET", null, null, () -> !running).body;
            remote = PeerIdentity.verify(proof, fresh, store.deviceId(), text(callback, "public_key"), clientId, PeerIdentity.uuid(text(callback, "space_id")));
            JSONObject previous = store.peer(clientId);
            if (previous != null && (!previous.getString("public_key").equals(remote.publicKey) || !previous.getString("space_id").equals(remote.spaceId))) throw new SecurityException("设备身份改变。");
            capabilities = proof.optJSONObject("capabilities"); if (capabilities == null) capabilities = new JSONObject();
        }
        JSONObject granted;
        synchronized (store) { assertCurrent(operation); granted = callbackGrant(clientId, name, platform); }
        try {
            synchronized (store) {
                assertCurrent(operation);
                if (remote != null) {
                    // Persist only the new, explicitly granted capability. No old token is sent to a new address.
                    credentials.save(remote.peerId, remote.spaceId, callbackToken);
                    store.registerPeer(origin, remote, "", capabilities);
                }
                confirmGrant(granted.getString("pairing_id"));
            }
            JSONObject response = session(grantById(granted.getString("pairing_id"))).put("device_token", granted.getString("token")).put("pairing_id", granted.getString("pairing_id"));
            if (challenge != null) response.put("identity", identity.proof(challenge, clientId, store.deviceId(), store.primarySpaceId()).getJSONObject("identity"));
            changed(); return response;
        } catch (Exception error) { revokeGrant(granted.getString("pairing_id")); throw error; }
    }

    private JSONObject authenticate(String authorization) throws Exception {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) throw failure(401, "UNAUTHORIZED", "请先扫码配对这台设备。");
        synchronized (store) {
            if (!running) throw failure(503, "DEVICE_PAUSED", "设备已停止接收同步。");
            try (Cursor row = store.getReadableDatabase().rawQuery("SELECT pairing_id,client_id,name,platform,created_at FROM device_incoming WHERE token_hash=? AND revoked=0", new String[]{hash(authorization.substring(7))})) {
                if (!row.moveToFirst()) throw failure(401, "UNAUTHORIZED", "设备配对已解除，请重新扫码。");
                return grantRow(row);
            }
        }
    }
    private JSONObject grantById(String id) throws Exception {
        synchronized (store) {
            if (!running) throw failure(503, "DEVICE_PAUSED", "设备已停止接收同步。");
            try (Cursor row = store.getReadableDatabase().rawQuery("SELECT pairing_id,client_id,name,platform,created_at FROM device_incoming WHERE pairing_id=? AND revoked=0", new String[]{id})) {
                if (!row.moveToFirst()) throw failure(401, "UNAUTHORIZED", "配对授权已失效。");
                return grantRow(row);
            }
        }
    }
    private static JSONObject grantRow(Cursor row) throws JSONException { return new JSONObject().put("pairing_id", row.getString(0)).put("client_id", row.getString(1)).put("name", row.getString(2)).put("platform", row.getString(3)).put("created_at", row.getLong(4)); }
    private JSONObject session(JSONObject grant) throws JSONException {
        return context().put("user", new JSONObject().put("id", store.primarySpaceId()).put("email", ""))
            .put("mode", "local").put("local_first", true).put("role", "peer").put("ai_provider", "rules")
            .put("pairing_limited", false).put("session_expires_at", JSONObject.NULL)
            .put("peer", new JSONObject().put("id", grant.getString("pairing_id")).put("name", grant.getString("name")).put("platform", grant.getString("platform")));
    }
    private void changed() { handler.post(onChanged); }
    private void assertCurrent(long operation) throws PeerHttp.Failure {
        if (!running || generation.get() != operation) throw failure(409, "PAIRING_CANCELLED", "连接已停止，未恢复已解除的配对。");
    }
    private synchronized void limit(boolean redemption) throws PeerHttp.Failure {
        long now = SystemClock.elapsedRealtime(); if (now - rateWindow >= 60_000) { rateWindow = now; identityRequests = redemptionRequests = 0; }
        if (redemption ? ++redemptionRequests > 60 : ++identityRequests > 120) throw failure(429, "RATE_LIMITED", "配对请求过于频繁，请稍后重试。");
    }
    private static void checkOrigin(HostedPeerServer.Request request) throws Exception {
        String origin = request.header("origin"); if (origin == null) return;
        URI parsed = new URI(origin);
        if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) || parsed.getUserInfo() != null || parsed.getRawAuthority() == null || !parsed.getRawAuthority().equalsIgnoreCase(request.header("host"))) throw failure(403, "INVALID_ORIGIN", "请求来源不受信任。");
    }
    private static String text(JSONObject object, String key) throws JSONException { Object value = object.get(key); if (!(value instanceof String)) throw new JSONException("Expected string"); return (String)value; }
    private static void keys(JSONObject object, String... allowed) throws JSONException {
        Set<String> fields = new LinkedHashSet<>(), required = new LinkedHashSet<>();
        for (String key : allowed) { if (key.endsWith("?")) fields.add(key.substring(0, key.length()-1)); else { fields.add(key); required.add(key); } }
        Iterator<String> input = object.keys(); while (input.hasNext()) { String key = input.next(); if (!fields.contains(key)) throw new JSONException("Unexpected field"); required.remove(key); }
        if (!required.isEmpty()) throw new JSONException("Missing field");
    }
    private static void checkName(String name) { if (name.trim().isEmpty() || name.length()>80 || java.util.regex.Pattern.compile("[\\x00-\\x1f\\x7f]").matcher(name).find()) throw new IllegalArgumentException("设备名称无效。"); }
    private static void checkPlatform(String platform) { if (!("android".equals(platform)||"windows".equals(platform)||"web".equals(platform)||"test".equals(platform))) throw new IllegalArgumentException("设备类型无效。"); }
    private static byte[] digest(String token) throws Exception { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(String token) throws Exception { StringBuilder value = new StringBuilder(); for (byte part : digest(token)) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255)); return value.toString(); }
    private static PeerHttp.Failure failure(int status, String code, String message) { return new PeerHttp.Failure(status, code, message); }
    private static HostedPeerServer.Response ok(JSONObject body) { return new HostedPeerServer.Response(200, body); }
}
