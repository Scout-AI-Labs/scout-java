# Changelog

All notable changes to this project are documented here. This project adheres
to [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-06-21

Initial release.

- Zero runtime dependencies - built on the JDK's `java.net.http` client and an embedded JSON codec.
- Full coverage of the Scout REST API: `search`, `page`, `extract`, `company`, `lists`, `products`, `site`, `jobs`, `monitors`, `chat`.
- `ApiException` with `getStatusCode()` and `isRateLimited()`/`isAuthentication()`/... predicates, plus `ConnectionException`/`TimeoutException`.
- Automatic retries with exponential backoff + jitter, honoring `Retry-After`.
- Idempotency keys on writes.
- `listAll()` helpers that walk every page.
