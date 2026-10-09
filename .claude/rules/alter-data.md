---
paths:
  - "alter-data/**"
---

# Editing alter-data

- Pure Kotlin: no imports from `org.alter.game`, `org.alter.api`, `org.alter.plugins` or `net.rsprot`. `PurityTests` fails the build otherwise.
- Classes take their dependencies through constructors. Cache access goes through `CacheView` so tests can use fakes from `testFixtures`.
- All IO uses `IoScope` or `AtomicFiles`. Never write a file non-atomically.
- Output that is committed (the wiki snapshot) must be deterministic: sorted, stable key order, `\n` line endings, trailing newline. A no-op sync must produce zero diff.
- Every tool writes a `Report` (`.json` for machines, `.md` for humans) instead of ad-hoc logging.
- Wiki etiquette: sequential requests, at least `minRequestIntervalMs` apart, a descriptive User-Agent, back off on 429/5xx and `Retry-After`.
