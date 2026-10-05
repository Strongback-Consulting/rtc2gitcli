#!/bin/sh
# Assembles an EWM 7.2 SCM Tools with the Enterprise Extensions (z/OS system definition) bundles from the
# Build System Toolkit's Installation Manager repository, without running Installation Manager.
#
# Usage: tools/assemble-ee-scmtools.sh <offering-repo> <dest-dir> [platform]
#   <offering-repo>  the Build System Toolkit IM repository (contains native/)
#   <dest-dir>       new folder; becomes the scmtools folder (contains eclipse/), use it as SCMTOOLS_HOME
#   [platform]       macosx.cocoa.x86_64 (default), linux.gtk.x86_64 or win32.win32.x86_64
#
# The result is used to build this project (the z/OS code imports the EE packages) and to run z/OS migrations.
# Plain SCM Tools still run every non-z/OS command: the EE imports are optional.
set -eu

REPO=${1:?usage: $0 <offering-repo> <dest-dir> [platform]}
DEST=${2:?usage: $0 <offering-repo> <dest-dir> [platform]}
PLATFORM=${3:-macosx.cocoa.x86_64}
NATIVE="$REPO/native"

zip_of() {
  found=$(ls "$NATIVE"/com.ibm.team.install.rtc.scmtools."$1"_*.zip 2>/dev/null | head -n 1)
  [ -n "$found" ] || { echo "missing $NATIVE/com.ibm.team.install.rtc.scmtools.$1_*.zip" >&2; exit 1; }
  echo "$found"
}

MAIN=$(zip_of "$PLATFORM.main")
EXT=$(zip_of common.ext.plugins)
FBZ=$(zip_of common.fbz.plugins)
[ ! -e "$DEST/eclipse" ] || { echo "$DEST/eclipse exists already" >&2; exit 1; }
mkdir -p "$DEST"
for zip in "$MAIN" "$EXT" "$FBZ"; do
  unzip -q -o "$zip" -d "$DEST"
done
ECLIPSE="$DEST/eclipse"
# zip extraction drops the execute bits that Installation Manager sets
chmod +x "$ECLIPSE"/scripts/unix/* 2>/dev/null || true
[ ! -f "$ECLIPSE/scm" ] || chmod +x "$ECLIPSE/scm" "$ECLIPSE/lscm"

# The main package's bundles.info lists only the platform bundles; Installation Manager registers the EE bundles
# (fbz) during the install. Register every bundle in plugins/ that is not listed yet.
python3 - "$ECLIPSE" <<'EOF'
import os, re, sys, zipfile

eclipse = sys.argv[1]
info = os.path.join(eclipse, 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info')
lines = open(info).read().splitlines()
# (name, version) of the registered bundles; one registration each, even where plugins/ holds a bundle both as
# jar and as folder
listed = {tuple(line.split(',')[:2]) for line in lines if line and not line.startswith('#')}


def manifest(path):
    try:
        if os.path.isdir(path):
            with open(os.path.join(path, 'META-INF/MANIFEST.MF'), encoding='utf-8', errors='replace') as f:
                return f.read()
        with zipfile.ZipFile(path) as z:
            return z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
    except (OSError, KeyError, zipfile.BadZipFile):
        return None


def header(text, name):
    for line in re.sub(r'\r?\n ', '', text).splitlines():
        if line.startswith(name + ':'):
            return line.split(':', 1)[1].split(';')[0].strip()
    return None


added = []
for name in sorted(os.listdir(os.path.join(eclipse, 'plugins'))):
    rel = 'plugins/' + name
    if name.startswith('org.eclipse.equinox.launcher'):
        continue
    text = manifest(os.path.join(eclipse, rel))
    bsn = text and header(text, 'Bundle-SymbolicName')
    version = text and header(text, 'Bundle-Version')
    if bsn and version and not bsn.endswith('.source') and (bsn, version) not in listed:
        listed.add((bsn, version))
        if os.path.isdir(os.path.join(eclipse, rel)):
            rel += '/'
        added.append(f'{bsn},{version},{rel},4,false\n')
with open(info, 'a') as f:
    f.writelines(added)
print(f'registered {len(added)} additional bundles')
EOF

echo "EE-enabled SCM Tools in $DEST (use it as SCMTOOLS_HOME; check with $ECLIPSE/scm help zimport)"
