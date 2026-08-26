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

> **Status: scaffolding.** The package layout, the API surface and the dependency set are in place;
> the DSS synchronisation adapter is a stub, persistence is in memory and the signing key is
> generated at startup. See the roadmap in [docs/architecture.md](docs/architecture.md#6-roadmap).

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
| `GET` | `/trust/v1/entities` | Private list of the calling tenant |
| `POST` | `/trust/v1/entities` | Register or update an entity |
| `GET` | `/trust/v1/entities/{organizationIdentifier}/trusted?role=` | Point check |
| `DELETE` | `/trust/v1/entities/{organizationIdentifier}` | Remove an entity |

Every call is tenant scoped through the `X-Tenant` header. OpenAPI lives at `/swagger-ui.html`.

## Running it

The platform stack in `eudistack-platform-dev` is the normal way to run this service. Standalone,
while working on the registry itself:

```bash
docker compose up --build      # http://localhost:8085
./gradlew build                # compile, test, checkstyle, coverage
./gradlew bootRun              # only for quick local iteration
```

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `SERVER_PORT` | `8085` | HTTP port |
| `TRUST_REGISTRY_LOTL_URL` | EU LOTL | List of Trusted Lists to synchronise |
| `TRUST_REGISTRY_KEYSTORE_PATH` | bundled | Certificates allowed to sign the LOTL |
| `TRUST_REGISTRY_CACHE_DIR` | `/var/cache/trust-registry` | Offline cache of synchronised lists |
| `TRUST_REGISTRY_SNAPSHOT_TTL` | `86400` | Seconds a snapshot stays usable offline |
| `TRUST_REGISTRY_SYNC_INTERVAL` | `PT6H` | Interval between synchronisations |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under [Apache-2.0](LICENSE).
