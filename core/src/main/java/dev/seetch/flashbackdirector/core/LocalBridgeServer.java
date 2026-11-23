package dev.seetch.flashbackdirector.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token-authenticated loopback HTTP server exposing POST /call to the local Node.js MCP bridge.
 */
public final class LocalBridgeServer {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final String BEARER_PREFIX = "Bearer ";

    private final ToolDispatcher dispatcher;
    private final BridgeDiscoveryFile discoveryFile;
    private final String modVersion;
    private final String minecraftVersion;

    private HttpServer server;
    private ExecutorService executor;
    private byte[] tokenBytes;
    private Thread shutdownHook;

    public LocalBridgeServer(
        ToolDispatcher dispatcher,
        BridgeDiscoveryFile discoveryFile,
        String modVersion,
        String minecraftVersion
    ) {
        this.dispatcher = dispatcher;
        this.discoveryFile = discoveryFile;
        this.modVersion = modVersion;
        this.minecraftVersion = minecraftVersion;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        String token = BridgeDiscoveryFile.generateToken();
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);

        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(this.executor);
        http.createContext("/call", this::handleCall);
        http.createContext("/health", this::handleHealth);
        http.start();

        int port = http.getAddress().getPort();
        try {
            discoveryFile.write(port, token, modVersion, minecraftVersion);
        } catch (IOException e) {
            http.stop(0);
            this.executor.close();
            this.executor = null;
            this.tokenBytes = null;
            throw e;
        }

        this.server = http;
        this.shutdownHook = new Thread(this::stop, "flashback-director-mcp-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        } catch (IllegalStateException ignored) {
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.close();
            executor = null;
        }
        tokenBytes = null;
        discoveryFile.deleteQuietly();
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            shutdownHook = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "Use GET"));
            return;
        }
        if (!isAuthorized(exchange)) {
            writeJson(exchange, 401, errorBody("UNAUTHORIZED", "Invalid bearer token"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("mod_version", modVersion);
        body.put("minecraft_version", minecraftVersion);
        writeJson(exchange, 200, body);
    }

    private void handleCall(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "Use POST"));
                return;
            }
            if (!isAuthorized(exchange)) {
                writeJson(exchange, 401, errorBody("UNAUTHORIZED", "Invalid bearer token"));
                return;
            }
            byte[] raw = readBoundedBody(exchange.getRequestBody());
            if (raw == null) {
                writeJson(exchange, 413, errorBody("PAYLOAD_TOO_LARGE", "Request body exceeds 2 MB limit"));
                return;
            }

            Map<String, Object> req;
            try {
                req = MiniJson.parseObject(new String(raw, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                writeJson(exchange, 400, errorBody("INVALID_JSON", e.getMessage()));
                return;
            }

            Object toolObj = req.get("tool");
            if (!(toolObj instanceof String toolName) || toolName.isBlank()) {
                writeJson(exchange, 400, errorBody("INVALID_PARAMS", "Missing 'tool' string field"));
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> args = req.get("args") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

            try {
                Map<String, Object> result = dispatcher.dispatch(toolName, args);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("result", result);
                writeJson(exchange, 200, ok);
            } catch (ToolError e) {
                writeJson(exchange, 200, errorBody(e.code(), e.getMessage()));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                writeJson(exchange, 500, errorBody("INTERNAL_ERROR", msg));
            }
        } finally {
            exchange.close();
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        byte[] expected = this.tokenBytes;
        if (expected == null) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] provided = header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private static byte[] readBoundedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("error", message);
        return body;
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = MiniJson.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
