#!/usr/bin/env bash
# Regenerates every signed TSL/LOTL fixture under src/test/resources/fixtures/tsl/ for
# EUD-227 (task 13). Compiles and runs tooling/GenerateTslFixtures.java as a throwaway
# script against the project's own `test` runtime classpath (already carries dss-xades,
# see build.gradle) — the same pattern used for dss-config's keytool-based regeneration
# notes, just automated here because signing real XAdES-BES XML by hand is not practical.
#
# Usage: run from the repository root.
#   src/test/resources/fixtures/tsl/regenerate-fixtures.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
cd "$REPO_ROOT"

FIXTURES_DIR="src/test/resources/fixtures/tsl"
TOOLING_DIR="$FIXTURES_DIR/tooling"
BUILD_DIR="build/tsl-fixture-tooling"

echo "Resolving the test runtime classpath via Gradle..."
INIT_SCRIPT="$(mktemp)"
trap 'rm -f "$INIT_SCRIPT"' EXIT
cat > "$INIT_SCRIPT" <<'EOF'
allprojects {
    tasks.register('printTestClasspathForTslFixtures') {
        doLast {
            println sourceSets.test.runtimeClasspath.asPath
        }
    }
}
EOF

CLASSPATH="$(./gradlew -q -I "$INIT_SCRIPT" printTestClasspathForTslFixtures)"

echo "Compiling the fixture tooling..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
javac -cp "$CLASSPATH" -d "$BUILD_DIR" \
    "$TOOLING_DIR/GenerateTslFixtures.java" \
    "$TOOLING_DIR/VerifyTslFixtures.java"

echo "Running the generator..."
java -cp "$BUILD_DIR:$CLASSPATH" GenerateTslFixtures

echo "Verifying every fixture's signature with DSS's own TLValidatorTask..."
java -cp "$BUILD_DIR:$CLASSPATH" VerifyTslFixtures

echo "Rebuilding the official-test-truststore.p12 (task 15) from tsl-test-signing-ca.cer..."
# This is DssTrustListJobConfig's officialSigningCertificateSource() trust store for the
# container test (task 15): a PKCS12 file containing the fixtures' CA certificate as a
# trusted entry, password "dss-password" to match DssTrustListJobConfig's hardcoded
# LOTL_KEYSTORE_PASSWORD. Unrelated to tsl-test-signing-keystore.p12 above (that one signs
# fixture content; this one lets DSS's production config class verify it) and to
# src/test/resources/dss-config/ (that one exercises DssTrustListJobConfigTest's ES-01
# fail-fast path, unrelated certs). See fixtures/tsl/README.md for the full rationale.
rm -f "$FIXTURES_DIR/keystore/official-test-truststore.p12"
keytool -importcert -noprompt \
    -alias tsl-test-ca \
    -file "$FIXTURES_DIR/keystore/tsl-test-signing-ca.cer" \
    -keystore "$FIXTURES_DIR/keystore/official-test-truststore.p12" \
    -storetype PKCS12 \
    -storepass dss-password

echo "Done. Signed fixtures are in $FIXTURES_DIR/*.xml"
