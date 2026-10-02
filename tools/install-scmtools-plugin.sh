#!/bin/sh
# Installs (or upgrades) the migrate-to-git extension into an EWM 7.x SCM Tools installation.
#
# Usage: tools/install-scmtools-plugin.sh <scmtools-dir> [bundle-jar]
#   <scmtools-dir>  the jazz/scmtools folder (contains eclipse/plugins)
#   [bundle-jar]    defaults to rtc2git.cli.extension/target/to.rtc.cli.migrate-*.jar (run ./mvnw verify first)
#
# EWM 7.x SCM Tools start bundles through simpleconfigurator, which ignores dropins/, so the bundle is
# copied into eclipse/plugins and registered in bundles.info. Older registrations of the bundle are removed.
set -eu

SCMTOOLS=${1:?usage: $0 <scmtools-dir> [bundle-jar]}
ECLIPSE="$SCMTOOLS/eclipse"
BUNDLES_INFO="$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
BSN=to.rtc.cli.migrate

if [ $# -ge 2 ]; then
  JAR=$2
else
  JAR=$(ls "$(dirname "$0")"/../rtc2git.cli.extension/target/$BSN-*.jar 2>/dev/null | head -n 1)
fi
[ -f "${JAR:-}" ] || { echo "bundle jar not found; run ./mvnw verify or pass its path" >&2; exit 1; }
[ -f "$BUNDLES_INFO" ] || { echo "not an SCM Tools installation: $BUNDLES_INFO missing" >&2; exit 1; }

VERSION=$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Bundle-Version: *//p')
[ -n "$VERSION" ] || { echo "no Bundle-Version in $JAR" >&2; exit 1; }
TARGET="plugins/${BSN}_$VERSION.jar"

# drop earlier versions, both the jar and its registration
rm -f "$ECLIPSE"/plugins/${BSN}_*.jar
grep -v "^$BSN," "$BUNDLES_INFO" > "$BUNDLES_INFO.tmp" || true
echo "$BSN,$VERSION,$TARGET,4,false" >> "$BUNDLES_INFO.tmp"
cp "$JAR" "$ECLIPSE/$TARGET"
mv "$BUNDLES_INFO.tmp" "$BUNDLES_INFO"

echo "Installed $BSN $VERSION into $ECLIPSE"
echo "Check with: $ECLIPSE/scm help migrate-to-git"
