# Trust Registry — Architecture

> Design record for `eudistack-core-trust-registry`. Backlog epic: `EUD-34`.

## 1. Problem

Every EUDIStack component asks the same question at some point: *is this entity trusted?*

| Asked by | About | Answered from |
|----------|-------|---------------|
| Verifier | the Issuer that sealed a presented credential | official Trusted Lists + private list |
| Wallet | the Relying Party asking for attributes | private list + Relying Party registration |
| Wallet | the Issuer offering a credential | official Trusted Lists + private list |
| Issuer | the Wallet Provider that attested the wallet instance | private list |
| Proximity validator | the Issuer, while offline | cached signed snapshot |

Before this service the same question was answered three different ways: a YAML list inside the
Verifier, a separate YAML list inside the Issuer, and a JSON file bundled into the proximity
validator. Three implementations, three refresh mechanisms, no audit trail.

## 2. Design — two layers

**Layer A — this service (deployable).** Does the stateful, expensive work once per deployment:

- synchronises the EU LOTL and the national Trusted Lists it points at, verifying the signature of
  every list before trusting its content (ETSI TS 119 612);
- holds the private List of Trusted Entities of each tenant — wallet providers, relying parties,
  attestation providers (ETSI TS 119 602);
- publishes a **versioned, signed snapshot** of both, plus the JWKS needed to verify it;
- exposes an admin API and keeps an audit trail of every change.

**Layer B — thin client (per stack).** Caches the snapshot, verifies its signature and evaluates
certificate chains **locally**. A trust decision never becomes a network round trip.

```
        EU LOTL + national TLs            tenant admin
                 |                              |
                 v                              v
        +------------------------------------------------+
        |            trust registry service              |   Layer A
        |  sync + verify + aggregate + sign + audit      |
        +------------------------------------------------+
                 |  signed snapshot (JWS) + JWKS
      -----------+-----------+-----------+-----------
      v                      v                       v
  Verifier               Issuer / EBW           Wallet PWA,
  (JVM client)           (JVM client)           proximity validator
                                                (snapshot verify)
```

### Why not a library only

The Wallet PWA runs Angular in a browser and the proximity validator runs offline. Neither can
synchronise 27 XML Trusted Lists, and the JVM validation stack does not run there. They need a
pre-aggregated artefact served to them. Registering and administering the private lists is shared
state and needs an audit trail, which a library cannot own.

### Why not a service only

A remote yes/no call on every validation puts the registry on the critical path of every
presentation: its latency becomes the Verifier latency, and its downtime becomes a fail-closed
outage. The proximity validator has to work with no connectivity at all. Distribution is
centralised; **evaluation stays distributed**.

## 3. Decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| `AD-1` | Two layers: central distribution of anchors, local evaluation of chains | Keeps availability and offline operation; avoids a single point of failure on the presentation path |
| `AD-2` | Reuse DSS (`eu.europa.ec.joinup.sd-dss`) for ETSI TS 119 612 handling | European Commission reference implementation: pivot LOTLs, MRA, list signature verification, offline cache. Licence `LGPL-2.1`, linked and unmodified |
| `AD-3` | Evaluate `eudi-lib-kmp-etsi-1196x2` for the unified TS 119 602 / TS 119 612 abstraction | Apache-2.0, covers LoTE as well as TL; the JVM target fits the backends. Adopted or rejected in `US-01` |
| `AD-4` | The snapshot is signed and versioned, never served as plain JSON in production | A consumer must be able to trust an artefact it cached hours ago without calling back |
| `AD-5` | Trust never crosses tenant boundaries; the private list is scoped per tenant | Same isolation invariant as the rest of the platform |
| `AD-6` | Fail closed: an entity that cannot be resolved is not trusted | A trust registry that fails open is worse than no registry |
| `AD-11` | Our private LoTEs are sealed with a qualified electronic seal, generated and held in a real QTSP | The write boundary left by AD-9 was the configuration store. A seal moves it back to key custody: possession of the bucket stops being enough to change who the platform trusts. It is also what the EU does with its own lists, and the platform already integrates a QTSP for credential signing |
| `AD-10` | Each list carries the certification authority as its trust anchor, never an end-entity certificate | HAIP forbids the trust anchor from travelling in the `x5c` of an SD-JWT VC or of signed metadata, and forbids the signing certificate from being self-signed. The anchor must therefore arrive out of band, and it has to be the CA the operational certificates chain to |
| `AD-9` | The private list arrives as external configuration, in the ETSI TS 119 602 data model, and the service exposes no write endpoint | A list of trusted entities is a publishable artefact, not a database: reads are open, and the ability to change trust is bounded by who can write the configuration. Same delivery path the Verifier already uses for its trusted issuers — object storage synced to a shared volume, reloaded on a schedule — so a change reaches consumers with no restart and no deploy. Adopting the standard model now means that replacing our file with an official European list later is a change of source, not of model |
| `AD-8` | Stay on the latest Spring Boot 3.x and avoid anything Boot 4 drops | The migration to Boot 4 is a real one — `spring-boot-starter-aop` is no longer managed and `@WebMvcTest` changes package — so the cheapest preparation is to run the newest 3.x and not depend on what disappears |
| `AD-7` | Spring WebMvc on virtual threads, not WebFlux | The work is a periodic blocking synchronisation (DSS exposes a fully synchronous API) plus serving a cached snapshot. Reactor would wrap every DSS call in `boundedElastic` and buy nothing but harder stack traces. The Verifier already runs WebMvc |
| `AD-12` | Vendor DSS's own reference OJ keystore (`dss-cookbook` `oj_2019/ec.europa.eu.1-8.cer`) instead of an unofficial third-party source | `AD-2` already delegates the full list lifecycle to DSS; diverging the trust anchor from the one DSS tests against would leave our anchor untested by DSS's own suite. Accepted consequence: most of the bundled certificates are already expired — see §3.3 |
| `AD-13` | The synchronised anchor set is never filtered by status; `TrustAnchor` keeps every status period and utilisability is resolved against the instant of the check | Filtering at sync time would destroy the information a later "was this qualified when it signed" check needs. `TrustAnchor.isUsable()` takes the instant as an argument instead of reading the clock |
| `AD-14` | An anchor set that fails to synchronise, entirely, keeps its previous instant and anchors instead of stamping a new "synced" instant on an empty result | A wholly failed attempt (no anchors, at least one rejection) is not a successful empty sync; conflating the two broke the never-synced/synced-and-stale distinction the set exists to preserve (`EC-04`) |
| `AD-15` | The official signing-certificate keystore is loaded and parsed while the `@Configuration` bean is constructed, not lazily on first sync | A service that starts without being able to verify anything would silently serve unverifiable trust; failing at startup surfaces the problem immediately instead of on the next scheduled sync (`ES-01`) |
| `AD-16` | `DssOfficialTrustListAdapter` reads `TLValidationJob.getSummary()` (per-list download/parsing/validation info), never `TrustedListsCertificateSource` | The certificate source exists only so DSS keeps itself internally in sync; the per-list summary is the only place that carries the download/signature outcome `SyncOutcome` needs to report rejections |

## 3.1 Validation strategy

There is no deployed environment for this service yet, and there will not be one until the design settles, so correctness is established by tests rather than by a smoke test against staging:

- **Unit tests** for the domain and the use cases, with no Spring context.
- **Integration tests** (`TrustRegistryEndToEndTest`) that boot the whole application on a real port and go through HTTP. They assert the two properties everything else rests on: a consumer verifies a published snapshot with nothing but the JWKS, and trust never crosses a tenant boundary.
- **Container tests** (`TrustRegistryImageIT`, Testcontainers, tag `container`) that build and boot the actual image, so the Dockerfile, the non-root user, the cache directory and the entrypoint are exercised too. They run under `./gradlew integrationTest`, not under `test`, because building the image takes minutes.

As stories land, this is where their infrastructure gets covered: a container serving Trusted List fixtures for the LOTL synchronisation, and a PostgreSQL container once persistence replaces the in-memory adapters.

`EUD-227` added the first of those: `TrustListSyncIT` (`integrationTest`, tag `container`) boots the packaged image against a second `nginx:alpine` container serving signed TSL/LOTL fixtures over a shared Docker network (the fixtures' cross-references are hostnames baked into already-signed XML, so they only resolve between containers, never from a host JVM). It is also what caught the two real DSS wiring defects below — both invisible to unit tests, which mock `TLValidationJob` rather than exercising the real pipeline.

## 3.2 Where trust changes come from

There is no administrative API. The private list of each tenant is provisioned as configuration and reloaded periodically; an invalid file leaves the previous version in force rather than emptying the list. Three consequences worth stating plainly:

- **The write boundary is the configuration store**, not an endpoint. Whoever can write the file can change who the platform trusts. That is the control to protect.
- **Reading is open by design.** Trusted lists are published documents; the tenant header selects which list to read and is not a confidentiality boundary. What must never cross a tenant boundary is the *decision*.
- **The file is a list of trusted entities in the standard model**, so the day an official European list covers these roles, swapping ours for it is a change of source.

## 3.3 Known risks from official-anchor synchronisation (`EUD-227`)

These are documented deliberately rather than smoothed over — a reader of this document should be able to reconstruct the actual risk surface, not a sanitised summary.

**OJ keystore staleness (DSS upstream, unresolved).** `AD-12`'s vendored keystore (`classpath:keystore/oj-keystore.p12`, password `dss-password`) ships eight certificates; **seven are already expired**, confirmed with `openssl x509 -dates`. Only one remains valid, through 2028. This is an upstream staleness issue in DSS's `dss-cookbook` module, not something introduced or patchable in this repository — replacing it with a hand-picked, currently-valid OJ certificate set was considered and rejected (`AD-12`: it would untether the anchor from what DSS's own test suite exercises). An attempt was made to report this against `github.com/esig/dss`; that repository has GitHub Issues disabled and redirects to the European Commission's own JIRA (`ec.europa.eu/digital-building-blocks/tracker/projects/DSS/issues`), which requires an EC-affiliated account — **reporting it upstream is still pending**. Not a blocker today: signature verification only needs the certificate that actually signed the LOTL currently in force, selected via DSS's pivot mechanism, but the risk should be re-checked whenever the OJ rotates a signing certificate.

**Two real DSS wiring defects found by the container test, not by any unit test.** Both existed in code already marked `completed` before the container test (`TrustListSyncIT`) first exercised a real `TLValidationJob.onlineRefresh()` against packaged, real fixtures — every unit test up to that point mocked `TLValidationJob` directly, so neither was reachable from the unit suite:

- *Missing `specs-trusted-list-v211` on the runtime classpath.* LOTL parsing (`AbstractParsingTask.verifyTLVersionConformity()`) needs this artifact; it only reached `testRuntimeClasspath` transitively via a test-only DSS dependency. Any real `onlineRefresh()`/`offlineRefresh()` would have failed with `NoClassDefFoundError` the first time it ran outside a test. Fixed by declaring it `runtimeOnly` in `build.gradle`.
- *Missing `dss-validation`/`dss-policy-jaxb` on the runtime classpath — security-relevant.* `TLValidationJob` internally uses `TLValidatorTask` (from `dss-validation`) to verify each list's signature. With that dependency `testImplementation`-only, the same `NoClassDefFoundError` was caught **internally by DSS** and logged as a non-fatal `WARN` ("Error performing analysis") — and the affected list was accepted anyway, with **zero rejections recorded**, i.e. unverified content would have reached the served anchor set. This directly contradicts `NFR-S-227-01`. Reproduced against the real packaged image before fixing, not inferred from a stack trace. Fixed by moving `dss-validation` and `dss-policy-jaxb` to `implementation`/`runtimeOnly`; the tampered-list rejection scenario was re-verified manually against the image afterwards.

Also found and fixed during the same work: a Spring dependency-management BOM (`springdoc-openapi-starter-webmvc-ui` → `swagger-core-jakarta`) pins `commons-lang3` to `3.17.0`, below the `3.18+` that `dss-utils-apache-commons` requires (`Strings.CS`/`Strings.CI`). A normal `resolutionStrategy.force` does not win against a Spring BOM pin; `dependencyManagement { dependencies { dependency '...:commons-lang3:3.20.0' } }` does. This one was also unreachable from any test that mocks `TLValidationJob`/`TLValidationJobSummary`.

**`TrustServiceStatus.SUSPENDED` is a domain value with no real-world path.** ETSI TS 119 612 defines no "suspended" status URI in either its pre- or post-eIDAS status vocabulary (confirmed against DSS 6.4's own `TrustServiceStatus` enum). The domain model keeps `SUSPENDED` as a valid enum value (in case a future normative revision introduces one, or a non-DSS source is added later), but no real DSS synchronisation can ever produce it — any status URI other than `granted`/`withdrawn` maps to `WITHDRAWN` or `UNKNOWN`, never to `SUSPENDED`. Test fixtures use the real, DSS-recognised `supervisionrevoked` status as the closest honest analogue to "a service in a state other than granted or withdrawn," rather than inventing a URI DSS would not recognise.

## 4. Out of scope

Issuing certificates (that is a QTSP), qualified signature of documents, authorisation decisions
(powers and PBAC answer *what may this entity do*, not *who is it*), credential revocation status,
and formal certification as a notified Trusted List Provider.

## 5. Layout

```
src/main/java/es/in2/trustregistry/
├── anchors/     official anchors from LOTL and national Trusted Lists
├── entities/    private List of Trusted Entities, per tenant
├── snapshot/    versioned signed artefact consumed by every service
└── shared/      cross-cutting configuration
```

Each feature follows ports and adapters: `domain/` holds the model and the ports, `application/`
the use cases, `infrastructure/` the adapters. The dependency rule is enforced by
`HexagonalArchitectureTest`, not just documented.

## 6. Roadmap

Persistence is in memory and the signing key is ephemeral; both are still scaffolding. The
official-anchor side is no longer a stub. In order:

1. ~~`US-01` — DSS synchronisation of LOTL and national Trusted Lists, with offline cache.~~
   Done (`EUD-227`): see §3.1 and §3.3 for the container test that validates it and the
   risks it surfaced.
2. `US-02` — Snapshot persistence and production key custody (KMS), published JWKS.
3. `US-03` — Private list persisted per tenant, with admin API and audit trail.
4. `US-04` to `US-06` — JVM client module, then migration of Verifier, Issuer and proximity
   validator off their own lists.
