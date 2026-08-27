# `fixtures/tsl` — TSL/LOTL test fixtures (EUD-227, task 13)

Real, well-formed ETSI TS 119 612 Trusted List / List of Trusted Lists (LOTL) documents,
signed with a throwaway test PKI, structured after DSS's own test fixtures
(`dss-tsl-validation/src/test/resources/*.xml` in `esig/dss` at tag `6.4`) — never copied
verbatim (those carry real, expired national PKI content), but built to the same real
element order and shape, since that ordering is what DSS's JAXB-based parser actually
requires.

**These fixtures are served only from a container (task 15), never from a real EU
endpoint.** A test that reaches `ec.europa.eu` for TSL/LOTL content is broken by
definition for this Story — see `tasks.md` Notes.

## Fixture inventory

| File | Represents | Exercises |
|------|------------|-----------|
| `lotl-valid.xml` | A LOTL signed by the test CA, pointing at `tl-national-a-valid.xml` and `tl-national-b-valid.xml` | AC-01 (happy path) |
| `tl-national-a-valid.xml` | A national TL (territory `AA`) with one `granted` service | AC-01 |
| `tl-national-b-valid.xml` | A national TL (territory `BB`) with one `granted` service | AC-01 |
| `tl-national-tampered.xml` | `tl-national-a-valid.xml` re-saved with one signed character flipped after signing | AC-02 (list discarded whole, last-known-good preserved) |
| `lotl-tampered.xml` | `lotl-valid.xml` with the same post-signature byte edit | ES-02 (LOTL fails, no national list derived) |
| `tl-national-three-statuses.xml` | A national TL (territory `CC`) with **three services**: `granted`, `withdrawn`, and `supervisionrevoked` (see substitution note below) | AC-03 (all three anchors kept, only `granted` usable now) |
| `tl-national-stale-next-update.xml` | A national TL (territory `DD`) whose `NextUpdate` is in the past, signature still valid | EC-02, TL-level |
| `lotl-stale-next-update.xml` | Same content as `lotl-valid.xml`, `NextUpdate` in the past | EC-02, LOTL-level (the case the AC literally describes) |

None of these files reference each other by filesystem path — the LOTL's
`PointersToOtherTSL` uses fixed placeholder URLs (`http://tsl-fixtures/tl-national-*.xml`)
that task 15's container test binds to when it serves the fixture set.

## Resolved ambiguity: "un servicio suspendido" → `supervisionrevoked`

Task 13's description (`tasks.md`) literally asks for "una lista con servicio suspendido."
ETSI TS 119 612 **defines no "suspended" status URI**, before or after eIDAS — verified
directly against DSS 6.4's own `TrustServiceStatus` enum
(`dss-validation/.../qualification/trust/TrustServiceStatus.java` at tag `6.4`), which
enumerates exactly: pre-eIDAS `undersupervision`, `supervisionincessation`,
`supervisionceased`, `supervisionrevoked`, `accredited`, `accreditationceased`,
`accreditationrevoked`; post-eIDAS `granted`, `withdrawn`, `setbynationallaw`,
`recognisedatnationallevel`, `deprecatedbynationallaw`, `deprecatedatnationallevel`. This
independently reconfirms task 8's finding in `tasks.md` (made from the implementer's own
code-reading, not the enum source) and the domain model's own Javadoc on
`DssOfficialTrustListAdapter.toDomainStatus()`: only `granted`/`withdrawn` are pattern-matched;
every other real status URI — including a real one like `supervisionrevoked` — already maps
to `TrustServiceStatus.UNKNOWN`, never to `TrustServiceStatus.SUSPENDED`.
`TrustServiceStatus.SUSPENDED` (the domain enum value from task 1) is unreachable from any
real DSS sync output; no fixture, however constructed, could exercise it without DSS itself
recognising a URI that does not exist.

**Resolution (approved by the coordinator, not a unilateral implementation choice):**
`tl-national-three-statuses.xml`'s third service uses the real, DSS-recognised legacy status
`http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/supervisionrevoked` instead of a
fabricated "suspended" URI. It is the closest honest analog to what the task literally
described — a real, non-`granted`, non-`withdrawn` status — without inventing a URI DSS
would not recognise (which would just silently resolve to `UNKNOWN`, exercising nothing
different from `withdrawn`'s "preserved but not usable" path). This is documented here and
in `tasks.md` task 13 Notes at the same transparency level as task 8's `SUSPENDED`-mapping
note, per the same escalation category — see `tasks.md`.

## Test PKI

`keystore/tsl-test-signing-keystore.p12` (password `tsl-test-password`) is a throwaway
self-signed PKCS12 store, unrelated to:

- The real production keystore at `src/main/resources/keystore/oj-keystore.p12` (DSS's own
  vendored OJ keystore, task 7).
- `src/test/resources/dss-config/valid-test-keystore.p12` / `invalid-keystore.p12`, which
  exercise `DssTrustListJobConfigTest`'s ES-01 fail-fast path and sign nothing here.

Aliases (all password-protected with the same store password):

| Alias | Role |
|-------|------|
| `tsl-test-ca` | Signs every LOTL/TL fixture (the LOTL/TL signing certificate, referenced from `PointersToOtherTSL`) |
| `service-aa` | `ServiceDigitalIdentity` certificate for `tl-national-a-valid.xml`'s granted service |
| `service-bb` | Same, for `tl-national-b-valid.xml` |
| `service-granted-cc`, `service-withdrawn-cc`, `service-legacy-cc` | The three services in `tl-national-three-statuses.xml` |
| `service-dd` | The service in `tl-national-stale-next-update.xml` |

These are leaf certificates used only as `ServiceDigitalIdentity` content (what a
`TrustAnchor` embeds) — they never sign anything themselves; only `tsl-test-ca` signs list
content.

## Regenerating

```bash
src/test/resources/fixtures/tsl/regenerate-fixtures.sh
```

Run from the repository root. The script:

1. Resolves the `test` source set's runtime classpath via a throwaway Gradle init script
   (needs `dss-xades`, `dss-token`, `dss-validation`, `dss-policy-jaxb` — all declared as
   `testImplementation`/`testRuntimeOnly` in `build.gradle` for exactly this purpose).
2. Compiles `tooling/GenerateTslFixtures.java` and `tooling/VerifyTslFixtures.java` against
   that classpath (neither is part of the compiled `test` source set — both are plain,
   `package`-less files invoked as scripts, mirroring `dss-config/README.md`'s
   `keytool`-based regeneration notes).
3. Runs `GenerateTslFixtures`, which fills the XML templates under `templates/*.xml` with
   deterministic dates/certificates and signs each with `XAdESService`
   (`XAdES_BASELINE_B`, `ENVELOPED`, SHA-256 — the exact shape DSS's own cookbook example
   `SignXmlXadesBTest` produces, matching the real `ds:Signature`/
   `xades:QualifyingProperties` block found in DSS's genuine `eu-lotl.xml` test resource).
   The two `-tampered.xml` fixtures are signed normally, then have one already-signed
   `<SchemeTerritory>` character flipped afterwards, so the signature parses but its digest
   no longer matches — `AC-02`/`ES-02` need "signature does not verify," not "malformed XML."
4. Runs `VerifyTslFixtures`, which re-validates every fixture with DSS's own
   `TLValidatorTask` (the same class `TLValidatorTaskTest` in `dss-tsl-validation` uses to
   assert a real LOTL's signature) against the `tsl-test-ca` certificate, and fails loudly
   if a valid fixture does not pass or a tampered one does not fail with `HASH_FAILURE`.

To change a fixture's content (add a service, change a status, move a date), edit the
corresponding file under `templates/`, then rerun the script — never hand-edit the signed
`*.xml` fixtures directly, since any edit invalidates their signature.

## Collateral fix: `commons-lang3` version conflict

Task 13 fixed a pre-existing `commons-lang3` version conflict discovered while building
this task's signing tool: Spring Boot's dependency-management BOM was pinning
`commons-lang3` to `3.17.0` (via `springdoc` → `swagger-core-jakarta`), while
`dss-utils-apache-commons:6.4` requires `>= 3.18` at runtime
(`org.apache.commons.lang3.Strings.CS`/`CI`). Left unpinned, any real `TLValidationJob`
refresh — `onlineRefresh()` or `offlineRefresh()`, both exercised by
`DssOfficialTrustListAdapter` — would throw `NoClassDefFoundError` the first time DSS's
`TLValidationJobSummaryBuilder` called `Utils.areStringsEqual(...)`, a path no existing unit
test exercises because DSS itself is mocked there. Fixed via an explicit
`dependencyManagement { dependencies { dependency 'org.apache.commons:commons-lang3:3.20.0' } }`
override in `build.gradle` (a plain `resolutionStrategy.force` does not win against Spring's
BOM) — see the comment there for the full trace. This is a production-runtime fix, not a
test-only one: task 15's container test would otherwise have failed for a reason unrelated
to its own logic.
