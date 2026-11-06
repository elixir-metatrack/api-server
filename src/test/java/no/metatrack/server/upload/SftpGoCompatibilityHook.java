package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SftpGoCompatibilityHook implements AutoCloseable {
    static final String USERNAME = "metatrack-sftpgo-hook";
    static final int MAX_TRANSFERS_PER_BATCH = 2;
    static final int MAX_TRANSFERS_GLOBAL = 8;
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final String EVENT_PATH = "/api/internal/sftpgo/events";
    private static final String RELEASE_PATH = "/test/release-reservation";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String expectedAuthorization;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AdmissionState state = new AdmissionState();

    SftpGoCompatibilityHook(String secret, String host, int port) throws IOException {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("SFTPGO_HOOK_SECRET must contain at least 32 characters");
        }
        expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (USERNAME + ":" + secret).getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "sftpgo-compatibility-hook");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String eventUrl(String host) {
        return "http://" + host + ":" + port() + EVENT_PATH;
    }

    String localUrl(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET") && path.equals("/healthz")) {
                respond(exchange, 200, state.snapshot());
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, path.equals(EVENT_PATH) || path.equals(RELEASE_PATH) ? 405 : 404,
                        error("not_found"));
                return;
            }
            if (!path.equals(EVENT_PATH) && !path.equals(RELEASE_PATH)) {
                respond(exchange, 404, error("not_found"));
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, error("unauthorized"));
                return;
            }
            JsonNode body = readJson(exchange);
            if (body == null) {
                respond(exchange, 400, error("invalid_request"));
                return;
            }
            if (path.equals(RELEASE_PATH)) {
                releaseReservation(exchange, body);
            } else {
                handleEvent(exchange, body);
            }
        } finally {
            exchange.close();
        }
    }

    private void releaseReservation(HttpExchange exchange, JsonNode body) throws IOException {
        String username = text(body, "username");
        String path = normalizePath(text(body, "path"), false);
        if (username == null || path == null) {
            respond(exchange, 400, error("invalid_request"));
            return;
        }
        state.complete(username, path);
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("status", "released");
        respond(exchange, 200, response);
    }

    private void handleEvent(HttpExchange exchange, JsonNode body) throws IOException {
        String event = text(body, "event");
        String username = text(body, "username");
        String path = normalizePath(text(body, "path"), true);
        if (event == null || username == null || path == null) {
            respond(exchange, 400, error("invalid_path"));
            return;
        }
        if (event.equals("pre-upload")) {
            if (state.admit(username, path)) {
                ObjectNode response = OBJECT_MAPPER.createObjectNode();
                response.put("status", "accepted");
                respond(exchange, 200, response);
            } else {
                respond(exchange, 403, error("transfer_limit"));
            }
            return;
        }
        if (event.equals("upload")) {
            state.complete(username, path);
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("status", "accepted");
            respond(exchange, 200, response);
            return;
        }
        respond(exchange, 400, error("unsupported_event"));
    }

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && MessageDigest.isEqual(
                authorization.getBytes(StandardCharsets.UTF_8),
                expectedAuthorization.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length < 1 || length > MAX_REQUEST_BYTES) {
                    return null;
                }
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(bytes);
        } catch (IOException exception) {
            return null;
        }
    }

    private String normalizePath(String value, boolean escaped) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String path = escaped ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.isBlank() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
                return null;
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    return null;
                }
            }
            return path;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private ObjectNode error(String message) {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("error", message);
        return result;
    }

    private void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class AdmissionState {
        private final Set<Reservation> reservations = new HashSet<>();

        synchronized boolean admit(String username, String path) {
            Reservation reservation = new Reservation(username, path);
            if (reservations.contains(reservation)) {
                return false;
            }
            long userCount = reservations.stream().filter(item -> item.username().equals(username)).count();
            if (userCount >= MAX_TRANSFERS_PER_BATCH || reservations.size() >= MAX_TRANSFERS_GLOBAL) {
                return false;
            }
            reservations.add(reservation);
            return true;
        }

        synchronized void complete(String username, String path) {
            reservations.remove(new Reservation(username, path));
        }

        synchronized ObjectNode snapshot() {
            ObjectNode result = OBJECT_MAPPER.createObjectNode();
            result.put("active_transfers", reservations.size());
            List<String> users = reservations.stream().map(Reservation::username).distinct().sorted().toList();
            result.set("users", OBJECT_MAPPER.valueToTree(users));
            return result;
        }
    }

    private record Reservation(String username, String path) {
    }
}