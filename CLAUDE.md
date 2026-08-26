# eudistack-core-trust-registry — repo guide

Single source of truth for trust decisions in EUDIStack: synchronises the EU LOTL and national
Trusted Lists (ETSI TS 119 612), aggregates them with the private List of Trusted Entities of each
tenant (ETSI TS 119 602) and publishes a signed snapshot consumers verify locally.

Read [docs/architecture.md](docs/architecture.md) before changing anything structural.

## Invariants

- Hexagonal: `domain/` depends on nothing outside itself. Enforced by `HexagonalArchitectureTest`.
- Fail closed: unresolvable trust is denied trust, never granted.
- Tenant scoped: every repository method takes the tenant; trust never crosses tenants.
- Time is injected through the `Clock` bean, never read statically.
- Constructor injection only; no field `@Autowired`.
- WebMvc on virtual threads, never WebFlux: DSS is blocking (`AD-7` in the architecture doc).

## Commands

| Task | Command |
|------|---------|
| Build, test, checkstyle, coverage | `./gradlew build` |
| Run locally | `docker compose up --build` |
| Tests only | `./gradlew test` |

Backlog epic: `EUD-34`. The functional specification, the story breakdown and the delivery process
live outside this repository, in the internal platform documentation.
