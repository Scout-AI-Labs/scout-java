import com.scout.ApiException;
import com.scout.Json;
import com.scout.Scout;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end test against a JDK HttpServer mock. Compile the SDK and this file,
 * then run with assertions on: {@code java -ea -cp out ScoutTest}.
 */
public class ScoutTest {

    static void send(HttpExchange ex, int code, String body) {
        try {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("X-Request-Id", "req_abc123");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        AtomicInteger flaky = new AtomicInteger(0);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/search", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> echo = new LinkedHashMap<>();
            echo.put("ok", true);
            echo.put("auth", ex.getRequestHeaders().getFirst("Authorization"));
            echo.put("idem", ex.getRequestHeaders().getFirst("Idempotency-Key"));
            echo.put("echo", Json.parse(body));
            send(ex, 200, Json.stringify(echo));
        });
        srv.createContext("/v1/site/map", ex -> {
            int n = flaky.incrementAndGet();
            if (n < 3) {
                send(ex, 500, "{\"detail\":\"transient\"}");
            } else {
                send(ex, 200, "{\"ok\":true,\"tries\":" + n + "}");
            }
        });
        srv.createContext("/v1/company", ex -> send(ex, 401, "{\"detail\":\"invalid api key\"}"));
        srv.createContext("/v1/searches", ex -> send(ex, 200, "{\"items\":[{\"id\":1}]}"));
        srv.start();
        int port = srv.getAddress().getPort();

        Scout scout = Scout.builder().apiKey("sk_live_xyz")
                .baseUrl("http://127.0.0.1:" + port).maxRetries(3).build();

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("queries", List.of("hello world"));
        p.put("depth", "standard");
        Map<String, Object> r = (Map<String, Object>) scout.search.create(p);
        assert r.get("auth").equals("Bearer sk_live_xyz") : "auth";
        assert r.get("idem") != null && !r.get("idem").toString().isEmpty() : "idem";
        assert ((Map<String, Object>) r.get("echo")).get("depth").equals("standard") : "echo";
        System.out.println("ok - POST round-trip + auth + idempotency");

        Map<String, Object> g = (Map<String, Object>) scout.search.list(5, 0);
        assert ((List<?>) g.get("items")).size() == 1 : "list";
        System.out.println("ok - GET + query");

        Map<String, Object> f = (Map<String, Object>) scout.site.map(Map.of("start_url", "https://x.com"));
        assert Boolean.TRUE.equals(f.get("ok")) && ((Number) f.get("tries")).intValue() == 3 : "flaky " + f;
        System.out.println("ok - retry-on-500 recovers");

        try {
            scout.company.enrich(Map.of("domain", "x.com"));
            throw new AssertionError("401 should throw");
        } catch (ApiException e) {
            assert e.getStatusCode() == 401 && "req_abc123".equals(e.getRequestId())
                    && e.getMessage().contains("invalid api key") : "401 " + e.getMessage();
            assert e.isAuthentication() : "isAuthentication";
            System.out.println("ok - 401 maps to ApiException with request id");
        }

        Object round = Json.parse(Json.stringify(Map.of(
                "s", "a\"b\\c\n\té", "n", 42, "d", 3.5,
                "arr", List.of(1, 2, 3), "nested", Map.of("k", true))));
        Map<String, Object> rm = (Map<String, Object>) round;
        assert rm.get("s").equals("a\"b\\c\n\té") : "json string";
        assert ((Number) rm.get("n")).longValue() == 42 : "json int";
        assert ((Number) rm.get("d")).doubleValue() == 3.5 : "json double";
        assert ((List<?>) rm.get("arr")).size() == 3 : "json arr";
        System.out.println("ok - JSON codec round-trip (unicode/escapes/numbers/nesting)");

        List<Object> all = scout.search.listAll();
        assert all.size() == 1 : "paginate";
        System.out.println("ok - pagination listAll");

        System.out.println("ALL PASSED");
        srv.stop(0);
    }
}
