# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial scaffolding: hexagonal package layout, trust snapshot API, private trusted
  entity API, in-memory adapters, DSS dependency set and CI pipeline.
- `docs/architecture.md` with the two-layer design and the accepted decisions.
- `DssOfficialTrustListAdapter` implementation (EUD-227): triggers `TLValidationJob.onlineRefresh()`
  and maps its `TLValidationJobSummary` to `TrustAnchor`s without filtering by service status
  (`AC-03`), preserving each service's status window for evaluation at query time (`AD-4`).
  Each list (LOTL or national) is processed independently; an unreachable or signature-invalid
  list becomes a `ListRejection` without aborting the run for the others (`AC-02`, `EC-01`,
  `ES-02`).

### Changed

- Web stack moved from WebFlux to WebMvc on virtual threads (`AD-7`): DSS is blocking, so
  the reactive layer added no concurrency and cost debuggability.
