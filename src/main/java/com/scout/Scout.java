package com.scout;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client for the Scout web-intelligence API.
 *
 * <pre>{@code
 * Scout scout = Scout.builder().apiKey("sk_...").build();
 * Object res = scout.search.create(Map.of("queries", List.of("climate tech startups")));
 * }</pre>
 *
 * <p>Zero third-party dependencies: built on the JDK's {@code java.net.http}
 * client and a small embedded JSON codec.
 */
public final class Scout {

    public static final String VERSION = "0.1.0";
    public static final String API_VERSION = "2026-06-21";

    private static final String DEFAULT_BASE_URL = "https://core.usescout.sh";
    private static final Set<Integer> RETRY_STATUSES = Set.of(408, 409, 429, 500, 502, 503, 504);

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final int maxRetries;

    // Resource groups - a faithful 1:1 mirror of the REST API tags.
    public final Search search = new Search();
    public final Page page = new Page();
    public final Extract extract = new Extract();
    public final Company company = new Company();
    public final Lists lists = new Lists();
    public final Products products = new Products();
    public final Site site = new Site();
    public final Jobs jobs = new Jobs();
    public final Monitors monitors = new Monitors();
    public final Chat chat = new Chat();

    private Scout(Builder b) {
        String key = b.apiKey != null ? b.apiKey : System.getenv("SCOUT_API_KEY");
        if (key == null || key.isEmpty()) {
            throw new ScoutException("Missing API key. Use Scout.builder().apiKey(...) or set SCOUT_API_KEY.");
        }
        this.apiKey = key;
        this.baseUrl = stripTrailingSlash(b.baseUrl != null ? b.baseUrl : DEFAULT_BASE_URL);
        this.timeout = b.timeout != null ? b.timeout : Duration.ofSeconds(60);
        this.maxRetries = b.maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Build a client; the API key falls back to {@code SCOUT_API_KEY}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: a client with an explicit API key and defaults. */
    public static Scout create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    // ------------------------------------------------------------- plumbing

    @SuppressWarnings("unchecked")
    Object request(String method, String path, Map<String, Object> body, Map<String, Object> query) {
        String url = baseUrl + path + queryString(query);
        boolean isWrite = !method.equals("GET");
        String bodyJson = (body != null && isWrite) ? Json.stringify(body) : null;
        String idempotencyKey = isWrite ? UUID.randomUUID().toString() : null;

        int attempt = 0;
        while (true) {
            try {
                return send(method, url, bodyJson, idempotencyKey);
            } catch (ScoutException e) {
                if (!isRetriable(e) || attempt >= maxRetries) {
                    throw e;
                }
                sleep(backoffMillis(attempt, e));
                attempt++;
            }
        }
    }

    private Object send(String method, String url, String bodyJson, String idempotencyKey) {
        HttpRequest.BodyPublisher publisher = bodyJson != null
                ? HttpRequest.BodyPublishers.ofString(bodyJson)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "scout-java/" + VERSION)
                .header("Scout-Version", API_VERSION)
                .method(method, publisher);
        if (bodyJson != null) {
            rb.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            rb.header("Idempotency-Key", idempotencyKey);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new TimeoutException("Request timed out after " + timeout.toSeconds() + "s", e);
        } catch (IOException e) {
            throw new ConnectionException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("Request interrupted", e);
        }
        return parse(resp);
    }

    private Object parse(HttpResponse<String> resp) {
        int status = resp.statusCode();
        String requestId = resp.headers().firstValue("x-request-id").orElse(null);
        String text = resp.body();
        Object parsed = null;
        if (text != null && !text.isEmpty()) {
            try {
                parsed = Json.parse(text);
            } catch (RuntimeException ex) {
                parsed = text;
            }
        }
        if (status >= 200 && status < 300) {
            return parsed;
        }
        ApiException e = new ApiException(errorMessage(parsed, status), status, requestId,
                errorCode(parsed), parsed);
        resp.headers().firstValue("retry-after").ifPresent(v -> {
            try {
                e.retryAfterSeconds = Double.parseDouble(v.trim());
            } catch (NumberFormatException ignored) {
                // not a delta-seconds value
            }
        });
        throw e;
    }

    private boolean isRetriable(ScoutException e) {
        if (e instanceof ConnectionException) {
            return true;
        }
        if (e instanceof ApiException) {
            return RETRY_STATUSES.contains(((ApiException) e).getStatusCode());
        }
        return false;
    }

    private long backoffMillis(int attempt, ScoutException e) {
        if (e instanceof ApiException) {
            double ra = ((ApiException) e).retryAfterSeconds;
            if (ra >= 0) {
                return (long) (Math.min(ra, 60.0) * 1000);
            }
        }
        double base = Math.min(500.0 * (1L << attempt), 8000.0);
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return (long) (base * jitter);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException("Retry sleep interrupted", e);
        }
    }

    private String queryString(Map<String, Object> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            sb.append(sb.length() == 0 ? '?' : '&')
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Object> paginate(String path) {
        int limit = 50;
        int offset = 0;
        List<Object> out = new ArrayList<>();
        while (true) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("limit", limit);
            query.put("offset", offset);
            Object page = request("GET", path, null, query);
            List<Object> items = extractItems(page);
            out.addAll(items);
            if (items.size() < limit) {
                break;
            }
            offset += items.size();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractItems(Object payload) {
        if (payload instanceof List) {
            return (List<Object>) payload;
        }
        if (payload instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) payload;
            for (String key : List.of("items", "data", "results", "searches", "runs", "jobs", "monitors")) {
                if (map.get(key) instanceof List) {
                    return (List<Object>) map.get(key);
                }
            }
            for (Object v : map.values()) {
                if (v instanceof List) {
                    return (List<Object>) v;
                }
            }
        }
        return new ArrayList<>();
    }

    private static String errorMessage(Object body, int status) {
        if (body instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) body;
            for (String key : List.of("detail", "error", "message")) {
                Object v = m.get(key);
                if (v instanceof String) {
                    return (String) v;
                }
                if (v instanceof Map && ((Map<?, ?>) v).get("message") instanceof String) {
                    return (String) ((Map<?, ?>) v).get("message");
                }
            }
        }
        if (body instanceof String && !((String) body).isEmpty()) {
            return (String) body;
        }
        return "Scout API returned HTTP " + status;
    }

    private static String errorCode(Object body) {
        if (body instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) body;
            if (m.get("code") instanceof String) {
                return (String) m.get("code");
            }
            if (m.get("error") instanceof Map && ((Map<?, ?>) m.get("error")).get("code") instanceof String) {
                return (String) ((Map<?, ?>) m.get("error")).get("code");
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Builder for {@link Scout}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private Duration timeout;
        private int maxRetries = 2;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Scout build() {
            return new Scout(this);
        }
    }

    // ------------------------------------------------------------ resources

    /** Web search, agentic AI queries, and search-run history. */
    public final class Search {
        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/search", params, null);
        }

        public Object aiQuery(Map<String, Object> params) {
            return request("POST", "/v1/ai-query", params, null);
        }

        public Object list(Integer limit, Integer offset) {
            return request("GET", "/v1/searches", null, page(limit, offset));
        }

        public List<Object> listAll() {
            return paginate("/v1/searches");
        }

        public Object get(String searchId) {
            return request("GET", "/v1/searches/" + enc(searchId), null, null);
        }

        public Object cancel(String searchId) {
            return request("POST", "/v1/searches/" + enc(searchId) + "/cancel", null, null);
        }

        public Object events(String searchId) {
            return request("GET", "/v1/searches/" + enc(searchId) + "/events", null, null);
        }
    }

    /** Single-page operations: markdown, html, screenshot, images, extract. */
    public final class Page {
        public Object markdown(Map<String, Object> params) {
            return request("POST", "/v1/page/markdown", params, null);
        }

        public Object html(Map<String, Object> params) {
            return request("POST", "/v1/page/html", params, null);
        }

        public Object screenshot(Map<String, Object> params) {
            return request("POST", "/v1/page/screenshot", params, null);
        }

        public Object images(Map<String, Object> params) {
            return request("POST", "/v1/page/images", params, null);
        }

        public Object extract(Map<String, Object> params) {
            return request("POST", "/v1/page/extract", params, null);
        }
    }

    /** Multi-URL structured extraction. */
    public final class Extract {
        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/extract", params, null);
        }
    }

    /** Company enrichment: profiles, logos, fonts, industry codes, styleguide. */
    public final class Company {
        public Object enrich(Map<String, Object> params) {
            return request("POST", "/v1/company", params, null);
        }

        public Object byEmail(Map<String, Object> params) {
            return request("POST", "/v1/company/by-email", params, null);
        }

        public Object byName(Map<String, Object> params) {
            return request("POST", "/v1/company/by-name", params, null);
        }

        public Object byTicker(Map<String, Object> params) {
            return request("POST", "/v1/company/by-ticker", params, null);
        }

        public Object simple(Map<String, Object> params) {
            return request("POST", "/v1/company/simple", params, null);
        }

        public Object fonts(Map<String, Object> params) {
            return request("POST", "/v1/company/fonts", params, null);
        }

        public Object styleguide(Map<String, Object> params) {
            return request("POST", "/v1/company/styleguide", params, null);
        }

        public Object logo(Map<String, Object> params) {
            return request("POST", "/v1/company/logo", params, null);
        }
    }

    /** Find-all ("lists"). */
    public final class Lists {
        public final ListRuns runs = new ListRuns();

        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/lists", params, null);
        }

        public Object run(Map<String, Object> params) {
            return request("POST", "/v1/lists/runs", params, null);
        }
    }

    /** Operations on async find-all runs. */
    public final class ListRuns {
        public Object list(Integer limit, Integer offset) {
            return request("GET", "/v1/lists/runs", null, page(limit, offset));
        }

        public List<Object> listAll() {
            return paginate("/v1/lists/runs");
        }

        public Object get(String findallId) {
            return request("GET", "/v1/lists/runs/" + enc(findallId), null, null);
        }

        public Object cancel(String findallId) {
            return request("POST", "/v1/lists/runs/" + enc(findallId) + "/cancel", null, null);
        }

        public Object enrich(String findallId, Map<String, Object> body) {
            return request("POST", "/v1/lists/runs/" + enc(findallId) + "/enrich", body, null);
        }

        public Object extend(String findallId, Map<String, Object> body) {
            return request("POST", "/v1/lists/runs/" + enc(findallId) + "/extend", body, null);
        }

        public Object events(String findallId) {
            return request("GET", "/v1/lists/runs/" + enc(findallId) + "/events", null, null);
        }
    }

    /** Product extraction from storefronts. */
    public final class Products {
        public Object extract(Map<String, Object> params) {
            return request("POST", "/v1/products", params, null);
        }

        public Object one(Map<String, Object> params) {
            return request("POST", "/v1/products/one", params, null);
        }
    }

    /** Whole-site operations: crawl and sitemap discovery. */
    public final class Site {
        public Object crawl(Map<String, Object> params) {
            return request("POST", "/v1/site/crawl", params, null);
        }

        public Object map(Map<String, Object> params) {
            return request("POST", "/v1/site/map", params, null);
        }
    }

    /** Async tasks ("jobs"). */
    public final class Jobs {
        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/jobs", params, null);
        }

        public Object list(Integer limit, Integer offset) {
            return request("GET", "/v1/jobs", null, page(limit, offset));
        }

        public List<Object> listAll() {
            return paginate("/v1/jobs");
        }

        public Object get(String taskId) {
            return request("GET", "/v1/jobs/" + enc(taskId), null, null);
        }

        public Object cancel(String taskId) {
            return request("POST", "/v1/jobs/" + enc(taskId) + "/cancel", null, null);
        }

        public Object events(String taskId) {
            return request("GET", "/v1/jobs/" + enc(taskId) + "/events", null, null);
        }

        public Object startRun(Map<String, Object> body) {
            return request("POST", "/v1/jobs/runs", body, null);
        }

        public Object runResult(String runId) {
            return request("GET", "/v1/jobs/runs/" + enc(runId), null, null);
        }

        public Object runEvents(String runId) {
            return request("GET", "/v1/jobs/runs/" + enc(runId) + "/events", null, null);
        }
    }

    /** Scheduled searches ("monitors"). */
    public final class Monitors {
        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/monitors", params, null);
        }

        public Object list(Integer limit, Integer offset) {
            return request("GET", "/v1/monitors", null, page(limit, offset));
        }

        public List<Object> listAll() {
            return paginate("/v1/monitors");
        }

        public Object get(String monitorId) {
            return request("GET", "/v1/monitors/" + enc(monitorId), null, null);
        }

        public Object update(String monitorId, Map<String, Object> params) {
            return request("PATCH", "/v1/monitors/" + enc(monitorId), params, null);
        }

        public Object delete(String monitorId) {
            return request("DELETE", "/v1/monitors/" + enc(monitorId), null, null);
        }

        public Object pause(String monitorId) {
            return request("POST", "/v1/monitors/" + enc(monitorId) + "/pause", null, null);
        }

        public Object resume(String monitorId) {
            return request("POST", "/v1/monitors/" + enc(monitorId) + "/resume", null, null);
        }

        public Object run(String monitorId) {
            return request("POST", "/v1/monitors/" + enc(monitorId) + "/run", null, null);
        }

        public Object events(String monitorId) {
            return request("GET", "/v1/monitors/" + enc(monitorId) + "/events", null, null);
        }
    }

    /** OpenAI-compatible chat completions, optionally grounded with web search. */
    public final class Chat {
        public final ChatCompletions completions = new ChatCompletions();
    }

    /** Creates chat completions. */
    public final class ChatCompletions {
        public Object create(Map<String, Object> params) {
            return request("POST", "/v1/chat/completions", params, null);
        }
    }

    private static Map<String, Object> page(Integer limit, Integer offset) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (limit != null) {
            q.put("limit", limit);
        }
        if (offset != null) {
            q.put("offset", offset);
        }
        return q;
    }
}
