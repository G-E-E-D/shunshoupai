package com.shunshoupai.app;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Foreground-owned JSON HTTP transport. The delegate owns every route and authorization decision. */
final class HostedPeerServer implements AutoCloseable {
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final int MAX_HEADER_BYTES = 16 * 1024;
    static final int MAX_LINE_BYTES = 8192;
    static final int MAX_HEADERS = 64;
    static final int REQUEST_TIMEOUT_MS = 20_000;
    static final int IDLE_TIMEOUT_MS = 5_000;

    interface Delegate { Response handle(Request request) throws Exception; }

    static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final JSONObject body;
        final InetAddress remoteAddress;

        Request(String method, String path, Map<String, String> query, Map<String, String> headers,
                JSONObject body, InetAddress remoteAddress) {
            this.method = method; this.path = path;
            this.query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body; this.remoteAddress = remoteAddress;
        }

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    }

    static final class Response {
        final int status;
        final JSONObject body;

        Response(int status, JSONObject body) {
            if (status < 200 || status > 599 || body == null) throw new IllegalArgumentException("Invalid JSON response");
            this.status = status; this.body = body;
        }

        static Response error(int status, String code, String message) {
            try { return new Response(status, new JSONObject().put("error", new JSONObject().put("code", code).put("message", message))); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException("Cannot create response", impossible); }
        }
    }

    private final Delegate delegate;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    // Bound aggregate request memory as well as each individual request.
    private final Semaphore bodyBudget = new Semaphore(MAX_BODY_BYTES);
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor deadlines;

    HostedPeerServer(Delegate delegate) {
        if (delegate == null) throw new IllegalArgumentException("Delegate is required");
        this.delegate = delegate;
    }

    synchronized int start(int preferredPort) throws IOException {
        if (preferredPort < 0 || preferredPort > 65535) throw new IllegalArgumentException("Invalid port");
        if (listener != null && !listener.isClosed()) return listener.getLocalPort();
        ServerSocket bound = new ServerSocket();
        bound.setReuseAddress(true);
        try { bound.bind(new InetSocketAddress(preferredPort), 8); }
        catch (BindException occupied) {
            bound.close();
            if (preferredPort == 0) throw occupied;
            bound = new ServerSocket(); bound.setReuseAddress(true);
            try { bound.bind(new InetSocketAddress(0), 8); }
            catch (IOException failure) { bound.close(); throw failure; }
        } catch (IOException failure) { bound.close(); throw failure; }

        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), daemonThreads("peer-request"), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, daemonThreads("peer-deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        listener = bound;
        final ServerSocket accepting = bound;
        final ThreadPoolExecutor executor = workers;
        final ScheduledThreadPoolExecutor timer = deadlines;
        Thread thread = daemonThreads("peer-listener").newThread(() -> accept(accepting, executor, timer));
        thread.start();
        return bound.getLocalPort();
    }

    synchronized int port() { return listener == null || listener.isClosed() ? -1 : listener.getLocalPort(); }

    private void accept(ServerSocket accepting, ThreadPoolExecutor executor, ScheduledThreadPoolExecutor timer) {
        while (!accepting.isClosed()) {
            Socket socket = null;
            ScheduledFuture<?> deadline = null;
            try {
                socket = accepting.accept();
                socket.setSoTimeout(IDLE_TIMEOUT_MS); socket.setTcpNoDelay(true);
                connections.add(socket);
                final Socket connection = socket;
                // Closing the socket also bounds slow writes and a client that
                // trickles bytes indefinitely; SO_TIMEOUT alone cannot do that.
                deadline = timer.schedule(() -> closeSocket(connection), REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                final ScheduledFuture<?> timeout = deadline;
                executor.execute(() -> serve(connection, timeout));
            } catch (IOException | RejectedExecutionException unavailable) {
                if (deadline != null) deadline.cancel(false);
                if (socket != null) { connections.remove(socket); closeSocket(socket); }
                if (accepting.isClosed() || executor.isShutdown()) return;
            }
        }
    }

    private void serve(Socket socket, ScheduledFuture<?> deadline) {
        int reservedBytes = 0;
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String[] first = line(input).split(" ", -1);
            if (first.length != 3 || !("HTTP/1.1".equals(first[2]) || "HTTP/1.0".equals(first[2])))
                throw new HttpFailure(400, "BAD_REQUEST", "请求格式无效。");
            String method = first[0];
            if (!("GET".equals(method) || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)))
                throw new HttpFailure(405, "METHOD_NOT_ALLOWED", "不支持此请求方式。");
            URI target;
            try { target = new URI(first[1]); }
            catch (Exception invalid) { throw new HttpFailure(400, "BAD_REQUEST", "请求地址无效。"); }
            if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawFragment() != null
                    || target.getRawPath() == null || !target.getRawPath().startsWith("/"))
                throw new HttpFailure(400, "BAD_REQUEST", "请求地址无效。");

            Map<String, String> headers = new LinkedHashMap<>();
            int headerBytes = first[0].length() + first[1].length() + first[2].length() + 4;
            while (true) {
                String header = line(input); headerBytes += header.length() + 2;
                if (headerBytes > MAX_HEADER_BYTES) throw new HttpFailure(431, "HEADERS_TOO_LARGE", "请求头过长。");
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon <= 0 || !header.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                    throw new HttpFailure(400, "BAD_REQUEST", "请求头无效。");
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.containsKey(name)) throw new HttpFailure(400, "DUPLICATE_HEADER", "请求头重复。");
                headers.put(name, header.substring(colon + 1).trim());
                if (headers.size() > MAX_HEADERS) throw new HttpFailure(431, "HEADERS_TOO_LARGE", "请求头过多。");
            }
            if ("HTTP/1.1".equals(first[2]) && (!headers.containsKey("host") || headers.get("host").isEmpty()))
                throw new HttpFailure(400, "BAD_REQUEST", "缺少设备地址。");
            if (headers.containsKey("transfer-encoding")) throw new HttpFailure(400, "UNSUPPORTED_TRANSFER", "请求需提供固定内容长度。");
            if (headers.containsKey("expect")) throw new HttpFailure(417, "EXPECTATION_FAILED", "不支持此请求预期。");
            String length = headers.getOrDefault("content-length", "0");
            if (!length.matches("[0-9]{1,10}")) throw new HttpFailure(400, "BAD_REQUEST", "请求长度无效。");
            long size = Long.parseLong(length);
            if (size > MAX_BODY_BYTES) throw new HttpFailure(413, "BODY_TOO_LARGE", "请求内容过长，请分批同步。");
            int count = (int) size;
            if (count > 0) {
                String contentType = headers.getOrDefault("content-type", "").split(";", 2)[0].trim();
                if (!"application/json".equalsIgnoreCase(contentType)) throw new HttpFailure(415, "JSON_REQUIRED", "请求内容必须为 JSON。");
            }
            if (!bodyBudget.tryAcquire(count)) throw new HttpFailure(503, "BUSY", "设备正在处理其他同步，请稍后重试。");
            reservedBytes = count;
            JSONObject body = readBody(input, count);
            Request request = new Request(method, target.getRawPath(), query(target.getRawQuery()), headers, body, socket.getInetAddress());
            if (socket.isClosed()) return;
            Response response = delegate.handle(request);
            if (response == null) response = Response.error(500, "INTERNAL_ERROR", "设备暂时无法完成操作。");
            write(socket, response);
        } catch (HttpFailure failure) {
            writeQuietly(socket, Response.error(failure.status, failure.code, failure.getMessage()));
        } catch (SocketTimeoutException timeout) {
            writeQuietly(socket, Response.error(408, "REQUEST_TIMEOUT", "请求超时，请重新同步。"));
        } catch (EOFException disconnected) {
            // A closed peer must not turn into a noisy application log.
        } catch (Exception failure) {
            writeQuietly(socket, Response.error(500, "INTERNAL_ERROR", "设备暂时无法完成操作。"));
        } finally {
            if (reservedBytes > 0) bodyBudget.release(reservedBytes);
            deadline.cancel(false); connections.remove(socket); closeSocket(socket);
        }
    }

    private static String line(InputStream input) throws IOException, HttpFailure {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (value == '\r') {
                if (input.read() != '\n') throw new HttpFailure(400, "BAD_REQUEST", "请求行结束符无效。");
                return bytes.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (value == '\n' || (value < 32 && value != '\t') || value == 127)
                throw new HttpFailure(400, "BAD_REQUEST", "请求包含无效字符。");
            if (bytes.size() >= MAX_LINE_BYTES) throw new HttpFailure(431, "HEADERS_TOO_LARGE", "请求行过长。");
            bytes.write(value);
        }
    }

    private static JSONObject readBody(InputStream input, int count) throws IOException, HttpFailure {
        if (count == 0) return new JSONObject();
        byte[] bytes = new byte[count];
        int read = 0;
        while (read < count) {
            int next = input.read(bytes, read, count - read);
            if (next == -1) throw new EOFException();
            read += next;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener parser = new JSONTokener(text);
            Object result = parser.nextValue();
            if (!(result instanceof JSONObject) || parser.nextClean() != 0) throw new IllegalArgumentException("Expected JSON object");
            return (JSONObject) result;
        } catch (Exception invalid) { throw new HttpFailure(400, "INVALID_JSON", "请求不是有效的 JSON 对象。"); }
    }

    private static Map<String, String> query(String raw) throws HttpFailure {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        try {
            for (String item : raw.split("&", -1)) {
                String[] pair = item.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
                String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8.name());
                if (name.isEmpty() || values.containsKey(name)) throw new IllegalArgumentException("Duplicate query parameter");
                values.put(name, value);
            }
            return values;
        } catch (Exception invalid) { throw new HttpFailure(400, "BAD_QUERY", "请求参数无效或重复。"); }
    }

    private static void write(Socket socket, Response response) throws IOException {
        byte[] body = response.body.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_BODY_BYTES) {
            response = Response.error(503, "RESPONSE_TOO_LARGE", "同步结果过大，请分批读取。");
            body = response.body.toString().getBytes(StandardCharsets.UTF_8);
        }
        String reason = response.status < 300 ? "OK" : response.status < 400 ? "Redirect" : "Error";
        byte[] headers = ("HTTP/1.1 " + response.status + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\n"
            + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        OutputStream output = socket.getOutputStream(); output.write(headers); output.write(body); output.flush();
    }

    private static void writeQuietly(Socket socket, Response response) {
        try { if (!socket.isClosed()) write(socket, response); } catch (IOException ignored) { }
    }

    synchronized void stop() {
        if (listener != null) { try { listener.close(); } catch (IOException ignored) { } listener = null; }
        for (Socket socket : connections) closeSocket(socket);
        if (workers != null) { workers.shutdownNow(); workers = null; }
        if (deadlines != null) { deadlines.shutdownNow(); deadlines = null; }
        connections.clear();
    }

    @Override public void close() { stop(); }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger number = new AtomicInteger();
        return runnable -> { Thread thread = new Thread(runnable, prefix + "-" + number.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    private static final class HttpFailure extends Exception {
        final int status;
        final String code;
        HttpFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
}
