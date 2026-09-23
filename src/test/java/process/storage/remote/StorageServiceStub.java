package process.storage.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** storage-service for these tests: answers one path with a canned reply and remembers every request. */
final class StorageServiceStub implements AutoCloseable {

    static final class Seen {
        final String method;
        final String uri;
        final String token;
        final String authorization;
        final String contentType;
        final byte[] body;

        Seen(HttpExchange exchange, byte[] body) {
            this.method = exchange.getRequestMethod();
            this.uri = exchange.getRequestURI().toString();
            this.token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            this.authorization = exchange.getRequestHeaders().getFirst("Authorization");
            this.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.body = body;
        }

        String bodyText() {
            return new String(this.body, StandardCharsets.UTF_8);
        }
    }

    final List<Seen> seen = new ArrayList<>();
    private final HttpServer server;

    StorageServiceStub(String path, int status, String contentType, byte[] reply) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(path, exchange -> {
            this.seen.add(new Seen(exchange, read(exchange.getRequestBody())));
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename*=UTF-8''truststore.p12");
            exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        this.server.start();
    }

    static StorageServiceStub json(String path, int status, String json) throws IOException {
        return new StorageServiceStub(path, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    String url() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    Seen last() {
        return this.seen.get(this.seen.size() - 1);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
