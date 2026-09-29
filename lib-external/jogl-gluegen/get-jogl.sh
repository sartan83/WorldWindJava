#!/usr/bin/env bash
#
# Fetches the JOGL/GlueGen jars from the immutable JogAmp release directory and
# verifies each one against the SHA-256 digests pinned in jogl-gluegen.sha256.
# Nothing is written to the destination unless every jar matches its digest.
#
# Usage:
#   get-jogl.sh [dest-dir]         download, verify and install jars (default: current directory)
#   get-jogl.sh --verify [dir]     verify jars already present in dir (default: current directory)
#
# When upgrading JOGL, update JOGL_VERSION and regenerate jogl-gluegen.sha256 only
# after checking the new jars against JogAmp's GPG-signed sha512sum.txt for that release.

set -euo pipefail

JOGL_VERSION="v2.5.0"
BASE_URL="https://jogamp.org/deployment/${JOGL_VERSION}/jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCKFILE="${SCRIPT_DIR}/jogl-gluegen.sha256"

sha256_check() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum --check --strict "$1"
    else
        shasum --algorithm 256 --check --strict "$1"
    fi
}

verify_dir() {
    (cd "$1" && sha256_check "${LOCKFILE}")
}

if [[ ! -f "${LOCKFILE}" ]]; then
    echo "Missing digest lockfile: ${LOCKFILE}" >&2
    exit 1
fi

if [[ "${1:-}" == "--verify" ]]; then
    verify_dir "${2:-.}"
    exit 0
fi

DEST_DIR="$(cd "${1:-.}" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

JARS=()
while read -r _ jar; do
    [[ -n "${jar}" ]] && JARS+=("${jar}")
done < "${LOCKFILE}"

for jar in "${JARS[@]}"; do
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        --output "${WORK_DIR}/${jar}" "${BASE_URL}/${jar}"
done

if ! verify_dir "${WORK_DIR}"; then
    echo "SHA-256 verification failed; no jars were installed." >&2
    exit 1
fi

for jar in "${JARS[@]}"; do
    mv -f "${WORK_DIR}/${jar}" "${DEST_DIR}/${jar}"
done

echo "Installed ${#JARS[@]} verified JOGL/GlueGen ${JOGL_VERSION} jars into ${DEST_DIR}"
