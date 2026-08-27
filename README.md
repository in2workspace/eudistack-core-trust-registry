<div align="center">

<h1>Trust Registry</h1>
<span>part of </span><a href="https://eudistack.net">EUDIStack</a>
<p><p>

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=alert_status)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)

[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=bugs)](https://sonarcloud.io/summary/new_code?id=in2workspace_eudistack-core-trust-registry)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=security_rating)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=in2workspace_eudistack-core-trust-registry)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=ncloc)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=coverage)](https://sonarcloud.io/summary/new_code?id=in2workspace_eudistack-core-trust-registry)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=in2workspace_eudistack-core-trust-registry)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=in2workspace_eudistack-core-trust-registry)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_eudistack-core-trust-registry&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=in2workspace_eudistack-core-trust-registry)

</div>

## What this is

The single place where EUDIStack answers **is this entity trusted?**

It synchronises the official European trust infrastructure — the EU List of Trusted Lists and the
national Trusted Lists it points at (ETSI TS 119 612) — aggregates it with the **private List of
Trusted Entities** each deployment defines for its own ecosystem (ETSI TS 119 602), and publishes
the result as a **signed, versioned snapshot** that every other service caches and verifies locally.

Consumers keep taking the decision themselves: the registry distributes anchors, it does not sit on
the critical path of every credential presentation. That is what keeps the Verifier fast and the
offline proximity validator usable. The reasoning is in [docs/architecture.md](docs/architecture.md).

> **Status: partial.** Official-anchor synchronisation (EU LOTL + national Trusted Lists, via DSS)
> is implemented, including offline-cache startup and scheduled online refresh. Persistence is
> still in memory and the signing key is still generated at startup. See the roadmap in
> [docs/architecture.md](docs/architecture.md#6-roadmap).

## Who consumes it

| Consumer | Question it asks |
|----------|------------------|
| Verifier | Is the Issuer that sealed this credential trusted? |
| Wallet | Is this Relying Party registered and allowed to ask? |
| Issuer / EBW | Does this wallet instance come from a trusted Wallet Provider? |
| Proximity validator | Same as the Verifier, with no connectivity |

## Standards

| Standard | Role here |
|----------|-----------|
| [ETSI TS 119 612](https://www.etsi.org/deliver/etsi%5Fts/119600%5F119699/119612/) | Trusted List and LOTL format, signature and pivot chain |
| [ETSI TS 119 602](https://www.etsi.org/deliver/etsi%5Fts/119600%5F119699/119602/) | Lists of Trusted Entities: wallet providers, relying parties, attribute authorities |
| [eIDAS 2.0](https://eur-lex.europa.eu/eli/reg/2024/1183/oj/eng) | Legal basis for qualified trust services |
| [EUDI ARF](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework) | Trust model and the roles registered in a LoTE |

Chain handling reuses [DSS](https://github.com/esig/dss), the European Commission reference
implementation, rather than a hand-rolled PKIX layer.

## API

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/trust/v1/snapshot` | Signed snapshot (JWS) for the calling tenant |
| `GET` | `/trust/v1/snapshot/plain` | Unsigned snapshot, troubleshooting only |
| `GET` | `/trust/v1/jwks` | Keys needed to verify a snapshot |
| `GET` | `/trust/v1/entities` | Private list of the given tenant |
| `GET` | `/trust/v1/entities/{organizationIdentifier}/trusted?role=` | Point check |

The API is read-only. Trust is changed by changing the provisioned configuration, never through an endpoint — see [docs/architecture.md](docs/architecture.md) AD-9. The `X-Tenant` header selects which tenant's list to read; a list of trusted entities is a publishable artefact, so reading is not restricted. OpenAPI lives at `/swagger-ui.html`.

## Running it

This service is normally brought up as part of the wider EUDIStack development stack. Standalone,
while working on the registry itself:

```bash
docker compose up --build      # http://localhost:8085
./gradlew build                # compile, test, checkstyle, coverage
./gradlew bootRun              # only for quick local iteration
./gradlew integrationTest      # Testcontainers image tests (tag "container"), minutes-scale
```

`TRUST_REGISTRY_CACHE_DIR` defaults to `/var/cache/trust-registry`, which only exists inside the
container. Running outside Docker (`bootRun`, or `./gradlew test`/`check` on a bare host shell),
point it at a writable local directory, e.g. `TRUST_REGISTRY_CACHE_DIR=/tmp/trust-registry-cache`,
or the application fails to start with `IllegalStateException`.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `SERVER_PORT` | `8085` | HTTP port |
| `TRUST_REGISTRY_LOTL_URL` | EU LOTL | List of Trusted Lists to synchronise |
| `TRUST_REGISTRY_KEYSTORE_PATH` | bundled | Certificates allowed to sign the LOTL, validated at startup (`ES-01`) — see [docs/architecture.md](docs/architecture.md#33-known-risks-from-official-anchor-synchronisation-eud-227) for a known staleness risk in the bundled default |
| `TRUST_REGISTRY_CACHE_DIR` | `/var/cache/trust-registry` | Offline cache of synchronised lists, read on startup with no network (`AC-05`) |
| `TRUST_REGISTRY_SNAPSHOT_TTL` | `86400` | Seconds a snapshot stays usable offline |
| `TRUST_REGISTRY_MAX_AGE` | `PT24H` | Maximum age a successful synchronisation may reach before the anchor set is flagged stale; the set is kept, never emptied, past this age (`AC-06`) |
| `TRUST_REGISTRY_SYNC_INITIAL_DELAY` | `PT10S` | Delay after startup before the first scheduled online refresh |
| `TRUST_REGISTRY_SYNC_INTERVAL` | `PT6H` | Interval between scheduled online refreshes thereafter |

## Observability

Synchronisation exposes the following Micrometer metrics on `/actuator/prometheus`:

| Metric | Type | Tags | Meaning |
|--------|------|------|---------|
| `trust_registry.anchor_sync.result` | counter | `trigger` (`scheduled` or `startup_cache`), `outcome` (`success` or `failure`) | One increment per synchronisation attempt |
| `trust_registry.anchor_sync.rejections` | counter | `trigger`, `reason` (`SIGNATURE_INVALID` or `UNREACHABLE`) | One increment per rejected list; never tagged with the list identifier, to keep cardinality bounded |
| `trust_registry.anchor_sync.stale_next_update` | counter | `trigger` | One increment per list accepted despite a `NextUpdate` already in the past |
| `trust_registry.anchor_set.age_seconds` | gauge | — | Age of the currently served anchor set, read live on every scrape |
| `trust_registry.anchor_set.never_synced` | gauge | — | `1` until the first successful synchronisation, `0` after |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [Apache-2.0](LICENSE).
