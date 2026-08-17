#!/usr/bin/env bash
#
# Installs headless Blender 4.2 LTS and puts it on PATH as `blender`.
#
# Why this exists: the development sandbox has no Blender, and every note in this repository
# that says "needs Blender, which the sandbox does not have" was written before anybody tried
# to install one. It takes about ninety seconds and it works (DISC-064). The sandbox is
# ephemeral, so this has to be re-run once per session, which is the whole reason it is a
# script rather than a sentence in a memory entry.
#
# D02-R12's lookup order is `-Pblender.exe` -> SYNDICATE_BLENDER_EXE -> `blender` on PATH.
# This installs the third, which is the one the Gradle tasks find with no extra configuration.
#
# The pinned version is a 4.2 LTS point release, matching the 4.2 that D09 and D02-S5.5 name.
# `bpy==4.2.13` from PyPI is the same codebase as a Python extension and is the other valid
# host (DEV-002); prefer this one, because it is the invocation D09-R1 specifies.
#
# Usage:  bash blender-tool/tools/install-blender.sh
#         blender --version

set -euo pipefail

BLENDER_VERSION="${BLENDER_VERSION:-4.2.13}"
BLENDER_SERIES="${BLENDER_VERSION%.*}"          # 4.2.13 -> 4.2
PREFIX="${BLENDER_PREFIX:-/opt/blender}"
LINK_DIR="${BLENDER_LINK_DIR:-/usr/local/bin}"

TARBALL="blender-${BLENDER_VERSION}-linux-x64.tar.xz"
URL="https://download.blender.org/release/Blender${BLENDER_SERIES}/${TARBALL}"

if command -v blender >/dev/null 2>&1; then
    echo "blender already on PATH: $(blender --version | head -1)"
    exit 0
fi

echo "Fetching ${URL} (~350 MB)..."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "${tmp}/${TARBALL}" "$URL"

echo "Extracting to ${PREFIX}..."
mkdir -p "$PREFIX"
tar -xf "${tmp}/${TARBALL}" -C "$PREFIX" --strip-components=1

mkdir -p "$LINK_DIR"
ln -sf "${PREFIX}/blender" "${LINK_DIR}/blender"

echo "Installed: $("${LINK_DIR}/blender" --version | head -1)"
echo
echo "Note: Blender's bundled Python ignores PYTHONPATH and the working directory, so the"
echo "tool packages must be added to sys.path explicitly in the --python-expr, and"
echo "--python-exit-code is required or a crashing script still exits 0 (DISC-064)."
