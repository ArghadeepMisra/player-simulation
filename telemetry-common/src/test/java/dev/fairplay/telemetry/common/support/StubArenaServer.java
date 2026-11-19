package dev.fairplay.telemetry.common.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for Arena (Project 1), built on the JDK's {@link HttpServer} on a free port so it needs no extra
 * dependency. Each route ({@code METHOD path}) answers from a scripted queue of responses, then from its default
 * handler, and every request is recorded.
 *
 * <p>{@code POST /api/v1/auth/login} answers 200 with a fresh token ({@code token-1}, {@code token-2}, ...) unless
 * scripted otherwise, so tests can count logins and see a re-login.
 */
public final class StubArenaServer implements AutoCloseable {

    /** One recorded call. {@code query} is null when the URL had none. */
    public record Request(String method, String path, String query, String authorization, String body) {
    }

    public record Response(int status, String body) {
        public static Response json(int status, String body) {
            return new Response(status, body);
        }

        public static Response problem(int status, String code, String detail) {
            return new Response(status, "{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> scripted = new ConcurrentHashMap<>();
    private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokens = new AtomicInteger();

    public StubArenaServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start the stub Arena server", e);
        }
        on("POST", "/api/v1/auth/login", request ->
                Response.json(200, "{\"tokenType\":\"Bearer\",\"accessToken\":\"token-" + tokens.incrementAndGet() + "\"}"));
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Sets what a route answers when its scripted queue is empty. */
    public StubArenaServer on(String method, String path, Function<Request, Response> handler) {
        handlers.put(method + " " + path, handler);
        return this;
    }

    public StubArenaServer on(String method, String path, Response response) {
        return on(method, path, request -> response);
    }

    /** Queues responses for a route; each request takes the next one before the default handler is used. */
    public StubArenaServer enqueue(String method, String path, Response... responses) {
        Deque<Response> queue = scripted.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addAll(List.of(responses));
        }
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    /** Forgets recorded requests and scripted responses. Default handlers stay, and tokens keep counting up. */
    public void reset() {
        requests.clear();
        scripted.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"), body);
        requests.add(request);

        String route = request.method() + " " + request.path();
        Response response = null;
        Deque<Response> queue = scripted.get(route);
        if (queue != null) {
            synchronized (queue) {
                response = queue.poll();
            }
        }
        if (response == null) {
            Function<Request, Response> handler = handlers.get(route);
            response = handler == null ? Response.problem(404, "NOT_FOUND", "no stub for " + route) : handler.apply(request);
        }

        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
