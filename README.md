# Scout Java SDK

Official Java SDK for the [Scout](https://usescout.sh) web-intelligence API — search, scrape, screenshot, extract, crawl, and company enrichment.

- **Zero runtime dependencies.** Built on the JDK's `java.net.http` client and a small embedded JSON codec.
- **Resilient.** Automatic retries with backoff + jitter, configurable timeouts, idempotency keys.

## Requirements

- Java 11+

## Installation

Maven:

```xml
<dependency>
  <groupId>sh.usescout</groupId>
  <artifactId>scout-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'sh.usescout:scout-sdk:0.1.0'
```

## Authentication

Create an API key in the [Scout dashboard](https://usescout.sh). The client reads `SCOUT_API_KEY` from the environment by default:

```java
import com.scout.Scout;

Scout scout = Scout.builder().build();              // uses SCOUT_API_KEY
Scout scout = Scout.builder().apiKey("sk_...").build();
// or:
Scout scout = Scout.create("sk_...");
```

## Quickstart

```java
import com.scout.Scout;
import java.util.List;
import java.util.Map;

Scout scout = Scout.builder().build();

Object results = scout.search.create(Map.of(
    "queries", List.of("best climate tech startups 2026"),
    "depth", "standard",
    "country", "us"
));
System.out.println(results);
```

Request bodies are plain `Map<String, Object>`; responses are parsed into `Map`/`List`/`String`/`Long`/`Double`/`Boolean`/`null`.

## Examples

```java
// Scrape a page to Markdown
Object page = scout.page.markdown(Map.of("url", "https://example.com"));

// Structured extraction
Object data = scout.extract.create(Map.of(
    "urls", List.of("https://example.com/pricing"),
    "output_schema", Map.of("type", "object")
));

// Company enrichment + logo
Object company = scout.company.enrich(Map.of("domain", "stripe.com"));
Object logo = scout.company.logo(Map.of("domain", "stripe.com", "format", "svg"));

// Crawl a site
Object crawl = scout.site.crawl(Map.of("start_url", "https://example.com", "max_pages", 50));
```

## Error handling

Non-2xx responses throw `ApiException` (a subclass of `ScoutException`), carrying `getStatusCode()`, `getRequestId()`, `getCode()`, and `getBody()`:

```java
import com.scout.ApiException;

try {
    scout.search.create(Map.of("queries", List.of("...")));
} catch (ApiException e) {
    if (e.isRateLimited()) {
        System.out.println("Slow down.");
    } else if (e.isAuthentication()) {
        System.out.println("Check your API key.");
    } else {
        System.out.printf("HTTP %d (req %s): %s%n", e.getStatusCode(), e.getRequestId(), e.getMessage());
    }
}
```

Predicates: `isBadRequest` (400), `isAuthentication` (401), `isInsufficientCredits` (402), `isPermissionDenied` (403), `isNotFound` (404), `isRateLimited` (429), `isServerError` (5xx). Network failures throw `ConnectionException` / `TimeoutException`.

## Retries & timeouts

Transient failures (connection errors, timeouts, 408/409/429/5xx) are retried automatically — **2 times by default**, with exponential backoff + jitter, honoring `Retry-After`. Write methods send an auto-generated `Idempotency-Key`.

```java
import java.time.Duration;

Scout scout = Scout.builder()
    .apiKey("sk_...")
    .timeout(Duration.ofSeconds(30))
    .maxRetries(4)
    .build();
```

## Pagination

```java
for (Object run : scout.search.listAll()) {
    System.out.println(run);
}
```

## Versioning

This SDK follows [SemVer](https://semver.org/) and sends the targeted Scout API version on every request; see [`CHANGELOG.md`](./CHANGELOG.md).

## Contributing

Issues and pull requests are welcome at [Scout-AI-Labs/scout-java](https://github.com/Scout-AI-Labs/scout-java).

## License

[MIT](./LICENSE)
