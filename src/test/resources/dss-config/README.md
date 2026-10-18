# `dss-config` test fixtures

Throwaway keystores exercising `DssTrustListJobConfigTest`'s ES-01 fail-fast behaviour
(missing/unreadable signing-certificate store aborts startup). Self-signed, generated for
this Story only — unrelated to:

- The real production keystore at `src/main/resources/keystore/oj-keystore.p12` (DSS's own
  vendored OJ keystore, see the Javadoc on `DssTrustListJobConfig`).
- Task 13's TSL-signing fixtures under `src/test/resources/fixtures/tsl/**`, which sign
  *trusted list* content, not the LOTL/TL signing-certificate store itself.

| File | Purpose |
|------|---------|
| `valid-test-keystore.p12` | One self-signed cert, password `dss-password` (matches `DssTrustListJobConfig`'s hardcoded keystore password) — regenerate with `keytool -genkeypair -alias throwaway-test-cert -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Throwaway Test Cert, O=EUDIStack Test Fixture, C=ES" -keystore valid-test-keystore.p12 -storetype PKCS12 -storepass dss-password -keypass dss-password` |
| `invalid-keystore.p12` | Not a keystore at all (plain text) — exercises the unreadable/unparseable path |
