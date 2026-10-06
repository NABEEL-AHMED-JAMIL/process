import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * MIG-132: a meter that has measured nothing, so billing-service's month close has a meter to ask
 * without the harness reaching the live one. Every read answers an empty body of the shape asked for;
 * every request is logged, so a run can see what the close asked.
 *
 * Run with the JDK in process's image: java /stub/MeterStub.java
 */
public class MeterStub {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8200), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            System.out.println("meter-stub " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String body;
            if (path.equals("/health")) {
                body = "{\"status\":\"UP\"}";
            } else if (path.startsWith("/v1/usage/events") || path.startsWith("/v1/usage/subjects") || path.equals("/v1/ratecards")) {
                body = "[]";
            } else if (path.startsWith("/v1/ratecard")) {
                body = "{\"version\":1,\"rates\":[]}";
            } else {
                body = "{\"rows\":[],\"lines\":[],\"total\":0}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        System.out.println("meter-stub listening on 8200");
    }
}
