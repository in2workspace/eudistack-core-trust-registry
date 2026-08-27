# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial scaffolding: hexagonal package layout, trust snapshot API, private trusted
  entity API, in-memory adapters, DSS dependency set and CI pipeline.
- `docs/architecture.md` with the two-layer design and the accepted decisions.
- `TrustAnchorSet` (EUD-227, `AC-06`): the aggregate holding the served anchors together with the
  instant of their last successful synchronisation, distinguishing a repository that has never
  synchronised from one that synchronised to an empty result. `isStaleAt(instant, maxAge)` marks a
  set as stale without ever emptying it.
- `SyncOutcome` and `ListRejection` (EUD-227, `AC-02`, `ES-02`, `EC-01`): the per-run result type
  reporting which anchors were gathered and, per rejected list, why (`SIGNATURE_INVALID`,
  `UNREACHABLE`, `UNKNOWN`), instead of collapsing a partially-failed run into a single boolean.
- `TrustRegistryProperties` (EUD-227, `AC-06`) gains `max-age` (default `PT24H`,
  `TRUST_REGISTRY_MAX_AGE`) and a nested `sync.initial-delay`/`sync.interval` configuration group
  for the scheduled synchronisation job.
- DSS `TLValidationJob` context (EUD-227, `AC-01`, `ES-01`): `LOTLSource` with pivot support, a
  file-backed cache loader, and the official signing-certificate keystore, validated eagerly at
  application startup (`@Configuration` bean construction) so a missing or unreadable keystore
  fails the boot instead of silently serving unverifiable trust. Vendors DSS's own `dss-cookbook`
  reference OJ keystore rather than an unofficial third-party certificate source (`AD-12`,
  `docs/architecture.md` §3.3) — most of its bundled certificates are already expired upstream,
  tracked as a known risk, not a defect introduced here.
- `DssOfficialTrustListAdapter.fetchAnchorsFromCache()` (EUD-227, `AC-05`, `EC-04`): a cache-only
  refresh path (`TLValidationJob.offlineRefresh()`) alongside the existing online refresh, run once
  at `ApplicationReadyEvent` so the service can populate its served anchor set from the last good
  synchronisation before any network call is attempted.
- `DssOfficialTrustListAdapter` implementation (EUD-227): triggers `TLValidationJob.onlineRefresh()`
  and maps its `TLValidationJobSummary` to `TrustAnchor`s without filtering by service status
  (`AC-03`), preserving each service's status window for evaluation at query time (`AD-4`).
  Each list (LOTL or national) is processed independently; an unreachable or signature-invalid
  list becomes a `ListRejection` without aborting the run for the others (`AC-02`, `EC-01`,
  `ES-02`).
- `TrustAnchorSyncScheduler` (EUD-227, `NFR-O-227-01`) now records Micrometer metrics on every
  refresh: `trust_registry.anchor_sync.result` (success/failure counter per trigger),
  `trust_registry.anchor_sync.rejections` (counter per rejection reason),
  `trust_registry.anchor_sync.stale_next_update` (counter for `EC-02`), and
  `trust_registry.anchor_set.age_seconds` / `trust_registry.anchor_set.never_synced` (gauges read
  live from the served anchor set), so operations can detect a failed or stale synchronisation
  before a consumer does.
- `src/test/resources/fixtures/tsl/` (EUD-227, task 13): a curated set of signed TSL/LOTL
  fixtures for the upcoming container test (task 15) — a valid LOTL and two valid national
  TLs (`AC-01`), a tampered national TL and a tampered LOTL (`AC-02`, `ES-02`), one national
  TL with three services in `granted`/`withdrawn`/`supervisionrevoked` statuses (`AC-03`;
  see `README.md` for why `supervisionrevoked` replaces the literal "suspended" ask — no such
  ETSI status URI exists), and a LOTL plus a national TL with a past `NextUpdate` (`EC-02`).
  Signed with a throwaway test PKI (`keystore/tsl-test-signing-keystore.p12`), never the real
  `oj-keystore.p12`. Includes `regenerate-fixtures.sh`, which compiles and runs a small
  `XAdESService`-based signing tool against the project's own DSS dependencies, and verifies
  every fixture with DSS's own `TLValidatorTask` immediately after generation.
- `TrustListSyncIT` (EUD-227, task 15): a container test running the real packaged image
  against a second container serving the fixtures above over HTTP, on a shared Testcontainers
  network with the `tsl-fixtures` alias the fixtures' signed XML already points at. Covers a
  full sync against a valid LOTL (`AC-01`); a LOTL mixing a valid list, a tampered list and a
  three-statuses list plus an unreachable pointer, verifying only the tampered/unreachable
  ones are rejected while the rest process normally (`AC-02`, `AC-03`, `EC-01`); a restart
  serving anchors from a cache populated by a prior successful sync with no source reachable
  (`AC-05`); and a cold start with no cache and no network (`EC-04`). Adds
  `lotl-valid-mixed.xml` and `keystore/official-test-truststore.p12` on top of task 13's
  fixture set — see `fixtures/tsl/README.md` for why.
- `TrustListSyncIT` (EUD-227, task 16, `NFR-P-227-01`): a timed scenario reusing the same
  mixed fixture set as task 15's `AC-02`/`EC-01` scenario, asserting a complete synchronisation
  (all four pointers dispositioned, not just anchors present) finishes within 60s. This is a
  fixture-scale regression guard, not a scaled proof of the production threshold (<10 min
  against ~27 real national lists) — proportional scaling from a 4-pointer local-fixture set
  to real EU infrastructure has no defensible basis.

### Changed

- Web stack moved from WebFlux to WebMvc on virtual threads (`AD-7`): DSS is blocking, so
  the reactive layer added no concurrency and cost debuggability.
- **Breaking:** `TrustAnchor.isUsable()` (EUD-227, `AC-03`, `AC-04`) is replaced by
  `isUsableAt(Instant)`: usability is now evaluated against the instant of the act being checked,
  not against the current clock, so a service that was granted when it signed stays usable for
  that act even after the grant later ends. Anchors are no longer filtered by status at
  synchronisation time — every status period is kept, and usability is resolved at query time.
- **Breaking:** `OfficialTrustListPort.fetchAnchors()` (EUD-227, `AC-02`) now returns a
  `SyncOutcome` instead of a plain `List<TrustAnchor>`, and `TrustAnchorRepositoryPort` (`AC-06`,
  `AC-07`) is renamed from `findAll()`/`replaceAll(List<TrustAnchor>)` to
  `current()`/`replaceAll(TrustAnchorSet)`, so the served set always carries its synchronisation
  instant and staleness together with its anchors.
- `TrustAnchorSyncService.synchronise()` (EUD-227) now builds a `TrustAnchorSet` from the
  `SyncOutcome` returned by the official trust list port and replaces the served set with a
  single atomic call, stamped with the synchronisation instant (`AC-06`, `AC-07`). A failure
  mid-run never reaches the replacement, so the previous set survives untouched (`ES-03`).
  `synchronise()` now returns the `SyncOutcome` itself instead of an anchor count, so callers
  learn about per-list rejections, not just anchors kept.
- `InMemoryTrustAnchorRepository` (EUD-227, `AC-07`) documents and verifies the atomic
  replacement guarantee already provided by its `AtomicReference<TrustAnchorSet>` storage: a
  concurrent `current()` read always returns the full previous set or the full new one, never a
  mix, backed by a dedicated concurrent-readers test.
- `SyncOutcome` (EUD-227, `EC-02`) now carries `listsWithStaleNextUpdate`: lists accepted despite
  a next-update date already in the past are still visible on the outcome, not only via log,
  closing the `TD-01` gap ahead of `NFR-O-227-01`'s dashboard requirement.

### Fixed

- Startup no longer leaves the served anchor set unpopulated (EUD-227, `AC-05`): the cache-only
  refresh run right after boot now applies its `SyncOutcome` to the repository via
  `TrustAnchorSyncService.applyOutcome(SyncOutcome)`, the same atomic-replace step the scheduled
  online refresh uses, instead of only logging the outcome.
- `commons-lang3` now resolves to `3.20.0` instead of Spring Boot's managed `3.17.0` (EUD-227,
  task 13): `dss-utils-apache-commons:6.4` requires `>= 3.18` at runtime
  (`org.apache.commons.lang3.Strings.CS`/`CI`), so any real `TLValidationJob` refresh —
  `onlineRefresh()` or `offlineRefresh()`, both used by `DssOfficialTrustListAdapter` — would
  have thrown `NoClassDefFoundError` the first time it ran, undetected until now because DSS
  is mocked in the existing unit tests.
- `TrustAnchorSyncService.applyOutcome(SyncOutcome)` (EUD-227, `EC-04`) no longer stamps a
  successful-sync instant when a refresh gathers no anchors and has at least one rejection: a
  repository that has never completed a real synchronisation now genuinely stays
  `TrustAnchorSet.neverSynced()`, instead of being marked dated-and-empty by its very first,
  fully-failed attempt. An already-synced repository is likewise left untouched — not re-dated
  or wiped — by a subsequent attempt where every list fails.
- `specs-trusted-list-v211`, `dss-validation` and `dss-policy-jaxb` moved to
  `implementation`/`runtimeOnly` (EUD-227): they were declared test-only under the incorrect
  assumption that only fixture-verification tooling needed them, but `TLValidationJob` runs
  `TLValidatorTask` internally on every real refresh to verify each TL/LOTL's signature.
  Without `dss-validation`, DSS silently caught the resulting `NoClassDefFoundError` per list
  and accepted its content anyway with zero rejection recorded — unverified list content
  reaching the served anchor set (`NFR-S-227-01`). Found and reproduced against the real
  packaged image while building task 15's container test.
