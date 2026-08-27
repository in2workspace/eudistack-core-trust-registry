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

echo "Done. Signed fixtures are in $FIXTURES_DIR/*.xml"
