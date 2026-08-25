# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial scaffolding: hexagonal package layout, trust snapshot API, private trusted
  entity API, in-memory adapters, DSS dependency set and CI pipeline.
- `docs/architecture.md` with the two-layer design and the accepted decisions.

### Changed

- Web stack moved from WebFlux to WebMvc on virtual threads (`AD-7`): DSS is blocking, so
  the reactive layer added no concurrency and cost debuggability.
