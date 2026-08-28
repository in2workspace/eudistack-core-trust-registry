# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **EUD-38 — inventario CycloneDX y gate de licencias**: el repositorio genera su inventario CycloneDX 1.6 en cada construcción, lo publica como activo de cada release (`sbom-v<version>.cdx.json`, comprobando que la versión del inventario coincide con la del artefacto) y evalúa cada pull request contra la lista de licencias admitidas (`.github/license-policy.json`, transcripción de `conv-quality-security-gates.md` §16.1). El evaluador y su suite de tests viven en `.github/scripts/`, sin dependencias de terceros y sin depender de ningún otro repositorio. Guía operativa: `docs/_shared/guides/license-gate-and-sbom.md` en `eudistack-platform-dev`.

### Changed

- **`org.codehaus.woodstox:stax2-api` restringido a 4.2.2** (llega transitivamente por woodstox → xmlsec → DSS). La 4.2.1 declara en su POM el nombre ambiguo *The BSD License*, que el plugin CycloneDX resuelve como `BSD-4-Clause` — la variante con cláusula publicitaria, que no es lo que el componente realmente es. La 4.2.2 declara `BSD-2-Clause` de forma explícita: mismo código, licencia inequívoca.

### Added

- Initial scaffolding: hexagonal package layout, trust snapshot API, private trusted
  entity API, in-memory adapters, DSS dependency set and CI pipeline.
- `docs/architecture.md` with the two-layer design and the accepted decisions.

### Changed

- Web stack moved from WebFlux to WebMvc on virtual threads (`AD-7`): DSS is blocking, so
  the reactive layer added no concurrency and cost debuggability.
