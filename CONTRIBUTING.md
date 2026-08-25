# Contributing

## Ground rules

- **Hexagonal, always.** `domain/` holds the model and the ports and depends on nothing outside
  itself; `application/` holds use cases; `infrastructure/` holds adapters. `HexagonalArchitectureTest`
  fails the build if that is violated.
- **Fail closed.** Any path that cannot resolve trust must deny it. A change that makes an
  unresolvable entity trusted will be rejected in review.
- **Tenant scoping is not optional.** Trust never crosses tenant boundaries; every repository method
  takes the tenant.
- **Time is injected.** Use the `Clock` bean, never `Instant.now()` directly, so validity windows
  stay testable.
- **Constructor injection only.** No field `@Autowired`. Lombok only where it removes ceremony.
- **Blocking code on virtual threads.** This service is WebMvc, not WebFlux (`AD-7`). Write plain
  blocking code; do not introduce `Mono`/`Flux`.

## Tests

Arrange / Act / Assert, one assertion concept per test, named `MethodName_State_Expected`.
Reactive types are asserted with `StepVerifier`. Mockito classic, no `MockedStatic` unless there is
no alternative.

```bash
./gradlew build     # compile + test + checkstyle + jacoco
./gradlew test      # tests only
```

## Commits and branches

Conventional Commits, one logical change per commit, with the Jira Story in the footer:

```
feat(anchors): synchronise national Trusted Lists from the LOTL

EUD-XXX
```

Branch from `main` as `feature/EUD-XXX-short-slug`, open a PR, squash merge. Direct pushes to
`main` are for documentation only.

## Definition of done for a PR

- `./gradlew build` green, coverage not lower than before.
- Sonar quality gate green, Trivy without new HIGH or CRITICAL findings.
- Public behaviour reflected in `README.md`, design decisions in `docs/architecture.md`.
- `CHANGELOG.md` updated under `Unreleased`.
