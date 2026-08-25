# Trust Registry — Architecture

> Design record for `eudistack-core-trust-registry`. Backlog: [EUD-34](https://eudistack.atlassian.net/browse/EUD-34).

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
| `AD-7` | Spring WebMvc on virtual threads, not WebFlux | The work is a periodic blocking synchronisation (DSS exposes a fully synchronous API) plus serving a cached snapshot. Reactor would wrap every DSS call in `boundedElastic` and buy nothing but harder stack traces. The Verifier already runs WebMvc |

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

The current tree is scaffolding: the DSS synchronisation adapter is a stub, persistence is
in memory and the signing key is ephemeral. In order:

1. `US-01` — DSS synchronisation of LOTL and national Trusted Lists, with offline cache.
2. `US-02` — Snapshot persistence and production key custody (KMS), published JWKS.
3. `US-03` — Private list persisted per tenant, with admin API and audit trail.
4. `US-04` to `US-06` — JVM client module, then migration of Verifier, Issuer and proximity
   validator off their own lists.
