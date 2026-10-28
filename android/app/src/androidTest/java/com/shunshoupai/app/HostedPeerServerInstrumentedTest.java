package com.shunshoupai.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class HostedPeerServerInstrumentedTest {
    private HostedPeerServer server;
    private AtomicInteger handled;

    @Before public void open() {
        handled = new AtomicInteger();
        server = new HostedPeerServer(request -> {
            handled.incrementAndGet();
            if ("/throw".equals(request.path)) throw new IllegalStateException("test-secret-must-not-leak");
            if (!"/echo".equals(request.path)) return HostedPeerServer.Response.error(404, "NOT_FOUND", "不存在此接口。");
            return new HostedPeerServer.Response(200, new JSONObject()
                .put("method", request.method).put("query", request.query.get("name"))
                .put("header", request.header("X-EXAMPLE")).put("received", request.body));
        });
    }

    @After public void close() { server.close(); }

    private String request(int port, String raw) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int size;
            while ((size = socket.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, size);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String get(int port, String target) throws Exception {
        return request(port, "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
    }

    private JSONObject body(String response) throws Exception {
        return new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4));
    }

    @Test public void jsonBodyQueryAndCaseInsensitiveHeadersReachOnlyTheDelegate() throws Exception {
        int port = server.start(0);
        String payload = new JSONObject().put("title", "手机直接交换").toString();
        String response = request(port, "POST /echo?name=%E6%89%8B%E6%9C%BA HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            + "X-Example: value\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
            + payload.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + payload);
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.contains("Cache-Control: no-store"));
        assertTrue(response.contains("Connection: close"));
        JSONObject result = body(response);
        assertEquals("POST", result.getString("method"));
        assertEquals("手机", result.getString("query"));
        assertEquals("value", result.getString("header"));
        assertEquals("手机直接交换", result.getJSONObject("received").getString("title"));
        assertEquals(1, handled.get());
    }

    @Test public void malformedFramingAndOversizedBodiesNeverReachTheDelegate() throws Exception {
        int port = server.start(0);
        String prefix = "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n";
        assertTrue(request(port, prefix + "Content-Length: 0\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(request(port, prefix + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(request(port, prefix + "Content-Length: " + (HostedPeerServer.MAX_BODY_BYTES + 1) + "\r\n\r\n").startsWith("HTTP/1.1 413"));
        assertTrue(request(port, prefix + "Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 415"));
        assertTrue(request(port, prefix + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n[]").startsWith("HTTP/1.1 400"));
        assertTrue(get(port, "/echo?name=a&name=b").startsWith("HTTP/1.1 400"));
        assertEquals(0, handled.get());
    }

    @Test public void unknownPathsDoNotServeFilesAndInternalErrorsDoNotExposeSecrets() throws Exception {
        int port = server.start(0);
        assertTrue(get(port, "/../../data/schedule-offline.sqlite").startsWith("HTTP/1.1 404"));
        String failed = get(port, "/throw");
        assertTrue(failed.startsWith("HTTP/1.1 500"));
        assertEquals("INTERNAL_ERROR", body(failed).getJSONObject("error").getString("code"));
        assertFalse(failed.contains("test-secret-must-not-leak"));
    }

    @Test public void occupiedPreferredPortUsesAnAvailableFallback() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int bound = server.start(occupied.getLocalPort());
            assertNotEquals(occupied.getLocalPort(), bound);
            assertEquals(bound, server.start(0));
            assertTrue(get(bound, "/echo").startsWith("HTTP/1.1 200"));
        }
    }

    @Test public void foregroundStopClosesInflightConnectionsAndCanRestart() throws Exception {
        server.close();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server = new HostedPeerServer(request -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS);
            return new HostedPeerServer.Response(200, new JSONObject());
        });
        int port = server.start(0);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET /echo HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            server.stop();
            assertEquals(-1, server.port());
            try { assertEquals(-1, socket.getInputStream().read()); }
            catch (SocketException closedConnection) { /* reset is also a closed connection */ }
        } finally { release.countDown(); }
        assertTrue(get(server.start(0), "/echo").startsWith("HTTP/1.1 200"));
    }

    @Test public void keystoreIdentityIsStableAndProofBindsNonceClientPeerAndSpace() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        LocalDeviceIdentity identity = new LocalDeviceIdentity(context);
        String key = identity.publicKey();
        assertEquals(key, new LocalDeviceIdentity(context).publicKey());
        String challenge = PeerIdentity.challenge(), client = UUID.randomUUID().toString();
        String peer = UUID.randomUUID().toString(), space = UUID.randomUUID().toString();
        JSONObject proof = identity.proof(challenge, client, peer, space);
        assertEquals(peer, PeerIdentity.verify(proof, challenge, client, key, peer, space).peerId);
        try { PeerIdentity.verify(proof, PeerIdentity.challenge(), client, key, peer, space); fail("old proof accepted a new nonce"); }
        catch (SecurityException expected) { }
        try { PeerIdentity.verify(proof, challenge, UUID.randomUUID().toString(), key, peer, space); fail("proof accepted a different client"); }
        catch (SecurityException expected) { }
        try { PeerIdentity.verify(proof, challenge, client, key, peer, UUID.randomUUID().toString()); fail("proof accepted a different space"); }
        catch (SecurityException expected) { }
    }
}
