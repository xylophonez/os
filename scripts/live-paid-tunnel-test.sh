#!/usr/bin/env bash
# Live paid-tunnel test against the freshly flashed LapEE.
#
# Unlike the QEMU harness this runs against the real appliance, the real
# phone and -- for the second relay pair -- the real public path through the
# VPS and acme TLS proxy. Read-only apart from the relays it makes.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
B=${B:-http://192.168.1.185:8734}
PHONE_ADDR=${PHONE_ADDR:-VP9ZEXVi7DpeqtP0wzXrk0_abafEkfSEQXi6CtN4igA}
PUBLIC=${PUBLIC:-tunnel.permaweb.space}
CONFIG=${CONFIG:-$ROOT/config.json}
[[ -f "$CONFIG" ]] || CONFIG=$ROOT/arch/android/config/examples/paid-tunnel-broker.json
EXPECT_TUNNEL_PIN=${EXPECT_TUNNEL_PIN:-$(jq -r '."trusted-devices"."tunnel@1.0" // empty' "$CONFIG")}
EXPECT_LEDGER_PIN=${EXPECT_LEDGER_PIN:-$(jq -r '."trusted-devices"."recharging-ledger@1.0" // empty' "$CONFIG")}
CANONICAL_TUNNEL=SVKRgrshzPceeBdnknJNvR-m2zQ0mFYJvjMx0rSscW0
ALLOW_CANONICAL_TUNNEL=${ALLOW_CANONICAL_TUNNEL:-0}

PASS=0; FAIL=0
say()  { printf '\n== %s ==\n' "$*"; }
ok()   { printf '  PASS  %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL  %s\n' "$*"; FAIL=$((FAIL+1)); }
note() { printf '        %s\n' "$*"; }

say "the appliance is running what we flashed"
ADDR=$(curl -fsS -m10 "$B/~meta@1.0/info/address" 2>/dev/null)
[[ -n "$ADDR" ]] && ok "node answered: $ADDR" || { bad "no answer from $B"; exit 1; }

LIVEDEVICES=$(curl -fsS -m20 "$B/~meta@1.0/info/trusted-devices" -H 'accept: application/json' 2>/dev/null)
LIVE_TUNNEL_PIN=$(jq -r '."tunnel@1.0" // empty' <<<"$LIVEDEVICES")
LIVE_LEDGER_PIN=$(jq -r '."recharging-ledger@1.0" // empty' <<<"$LIVEDEVICES")
[[ -n "$EXPECT_TUNNEL_PIN" && "$LIVE_TUNNEL_PIN" == "$EXPECT_TUNNEL_PIN" ]] \
    && ok "running expected tunnel@1.0 ($LIVE_TUNNEL_PIN)" \
    || bad "wrong tunnel pin: expected ${EXPECT_TUNNEL_PIN:-none}, node reports ${LIVE_TUNNEL_PIN:-none}"
[[ -n "$EXPECT_LEDGER_PIN" && "$LIVE_LEDGER_PIN" == "$EXPECT_LEDGER_PIN" ]] \
    && ok "running expected recharging-ledger@1.0 ($LIVE_LEDGER_PIN)" \
    || bad "wrong ledger pin: expected ${EXPECT_LEDGER_PIN:-none}, node reports ${LIVE_LEDGER_PIN:-none}"
if [[ "$LIVE_TUNNEL_PIN" == "$CANONICAL_TUNNEL" ]]; then
    if [[ "$ALLOW_CANONICAL_TUNNEL" == 1 ]]; then
        note "canonical tunnel allowed for integration only; served-payload billing remains flat"
    else
        bad "canonical tunnel cannot pass the paid-tunnel release gate"
        note "set ALLOW_CANONICAL_TUNNEL=1 only for integration testing"
    fi
fi

RATES=$(curl -fsS -m20 "$B/~meta@1.0/info/metering-rates" -H 'accept: application/json' 2>/dev/null)
grep -qE 'request-bytes|response-bytes' <<<"$RATES" \
    && ok "metering@1.0 byte meters are live" || bad "metering-rates not applied: $(head -c 160 <<<"$RATES")"
grep -qE 'tunnel-bytes|meta-bytes' <<<"$RATES" \
    && bad "retired tunnel-*/meta-* resources still priced" || ok "no retired resources priced"

LEDGER_DEVICE=$(curl -fsS -m20 "$B/~meta@1.0/info/on" -H 'accept: application/json' 2>/dev/null \
    | jq -r '.request[0]."ledger-device" // empty')
[[ "$LEDGER_DEVICE" == recharging-ledger@1.0 ]] \
    && ok "P4 charges recharging-ledger@1.0" \
    || bad "P4 ledger is ${LEDGER_DEVICE:-missing}"

say "the phone is registered"
REG=0
for _ in $(seq 1 40); do
    R=$(curl -fsS -m20 "$B/~tunnel@1.0/status" -H 'accept: application/json' 2>/dev/null \
        | jq -r '."open-registrations" // 0')
    [[ "${R:-0}" -gt 0 ]] && { REG=$R; break; }
    sleep 6
done
(( REG > 0 )) && ok "AndEE registered ($REG workers)" || { bad "AndEE never registered"; exit 1; }

B32=$(python3 -c "
import base64
a='$PHONE_ADDR'; raw=base64.urlsafe_b64decode(a+'='*(-len(a)%4))
print(base64.b32encode(raw).decode().lower().rstrip('='))")
note "tunnel host label: $B32"

bal() {
    curl -fsS -G -m30 "$B/~p4@1.0/balance" \
        --data-urlencode "target=$PHONE_ADDR" \
        -H 'accept: application/json' 2>/dev/null \
        | jq -er '
            if type == "number" then .
            elif type == "string" then tonumber
            elif (.body? | type) == "number" then .body
            elif (.body? | type) == "string" then (.body | tonumber)
            else error("no numeric balance") end'
}

OPEN=$(bal)
[[ "$OPEN" =~ ^[0-9]+$ && "$OPEN" -gt 0 ]] \
    && ok "payer has recharging-ledger capacity $OPEN" \
    || { bad "payer has no balance"; exit 1; }

# ---- relay pair over the LAN, addressed by host label ----------------------
say "relay + charge (LAN)"
A=$(bal)
S=$(curl -s -m120 -o /dev/null -w '%{size_download}' -H "Host: $B32.example.invalid" \
    "$B/~hyperbuddy@1.0/index" 2>/dev/null)
C=$(bal); SMALL=$((A-C))
[[ "${S:-0}" -gt 0 ]] && ok "small relay served: $S bytes" || bad "small relay failed"
note "charged $SMALL"

A=$(bal)
L=$(curl -s -m300 -o /dev/null -w '%{size_download}' -H "Host: $B32.example.invalid" \
    "$B/~hyperbuddy@1.0/bundle.js" 2>/dev/null)
C=$(bal); LARGE=$((A-C))
[[ "${L:-0}" -gt 0 ]] && ok "large relay served: $L bytes" || bad "large relay failed"
note "charged $LARGE"

if (( SMALL <= 0 || LARGE <= 0 )); then
    bad "a relay charged nothing with rates set ($SMALL, $LARGE)"
elif (( LARGE > SMALL * 100 )); then
    ok "served payload billed proportionally (large/small = $((LARGE / SMALL))x)"
elif [[ "$LIVE_TUNNEL_PIN" == "$CANONICAL_TUNNEL" && "$ALLOW_CANONICAL_TUNNEL" == 1 ]]; then
    ok "canonical integration path billed ($SMALL then $LARGE units)"
    note "flat served-payload billing is accepted only by the explicit integration override"
else
    bad "served payload did not bill proportionally ($SMALL then $LARGE units)"
fi
if (( L > 0 )); then
    note "large charge / payload ratio: $(python3 -c "print(round($LARGE/$L,4))")"
fi

# ---- the same relay over the real public path -----------------------------
say "relay + charge (public path via VPS + TLS proxy)"
PUB="https://$B32.$PUBLIC"
A=$(bal)
P=$(curl -s -m300 -o /dev/null -w '%{size_download}' "$PUB/~hyperbuddy@1.0/bundle.js" 2>/dev/null)
C=$(bal); PUBCHG=$((A-C))
if [[ "${P:-0}" -gt 0 ]]; then
    ok "public relay served: $P bytes"
    note "charged $PUBCHG"
    if (( PUBCHG > P / 2 )); then
        ok "public served payload billed proportionally"
    elif [[ "$LIVE_TUNNEL_PIN" == "$CANONICAL_TUNNEL" && "$ALLOW_CANONICAL_TUNNEL" == 1 && "$PUBCHG" -gt 0 ]]; then
        ok "canonical public integration path billed"
        note "flat billing is accepted only by the explicit integration override"
    else
        bad "public served payload did not bill proportionally ($PUBCHG for ${P}B)"
    fi
else
    bad "public relay failed - check the VPS hop and TLS proxy"
fi

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
(( FAIL == 0 )) && echo "  LIVE BILLING HOLDS" || echo "  DO NOT PUSH"
exit $(( FAIL > 0 ))
