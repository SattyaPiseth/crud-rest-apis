#!/bin/sh
set -eu

refuse() {
    echo "Refusing to start Keycloak: CPU safety cannot be established ($1)." >&2
    exit 78
}

[ -r /proc/cpuinfo ] || refuse "CPU information unreadable"
# Require recognizable x86 flags; missing/empty/unsupported metadata fails closed.
if grep -Eq '^flags[[:space:]]*:' /proc/cpuinfo; then
    :
else
    refuse "CPU flags unavailable"
fi

# Linux uses avx512vbmi. Accept the alternative spelling defensively as well.
if grep -Eqw 'avx512vbmi|avx512_vbmi' /proc/cpuinfo; then
    refuse "AVX-512 VBMI present; runtime remediation required"
else
    result=$?
    [ "$result" -eq 1 ] || refuse "CPU flag inspection failed"
fi

# This guard is not a Java CVE patch and cannot attest to the physical host.
exec /opt/keycloak/bin/kc.sh "$@"
