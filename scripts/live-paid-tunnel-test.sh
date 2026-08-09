#!/usr/bin/env bash
# Live paid-tunnel test against the freshly flashed LapEE.
#
# Unlike the QEMU harness this runs against the real appliance, the real
# phone and -- for the second relay pair -- the real public path through the
# VPS and acme TLS proxy. Read-only apart from the relays it makes.
set -uo pipefail

B=${B:-http://192.168.1.185:8734}
PHONE_ADDR=${PHONE_ADDR:-VP9ZEXVi7DpeqtP0wzXrk0_abafEkfSEQXi6CtN4igA}
PUBLIC=${PUBLIC:-tunnel.permaweb.space}
EXPECT_PIN=UoBIgtRChLBl_MLFTbjGDdME2YLfYn_9573H02DK0Dc

PASS=0; FAIL=0
say()  { printf '\n== %s ==\n' "$*"; }
ok()   { printf '  PASS  %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL  %s\n' "$*"; FAIL=$((FAIL+1)); }
note() { printf '        %s\n' "$*"; }

say "the appliance is running what we flashed"
ADDR=$(curl -fsS -m10 "$B/~meta@1.0/info/address" 2>/dev/null)
[[ -n "$ADDR" ]] && ok "node answered: $ADDR" || { bad "no answer from $B"; exit 1; }

LIVEPIN=$(curl -fsS -m20 "$B/~meta@1.0/info/trusted-devices" -H 'accept: application/json' 2>/dev/null \
    | jq -r '."tunnel@1.0" // empty')
[[ "$LIVEPIN" == "$EXPECT_PIN" ]] \
    && ok "running the republished tunnel@1.0 ($LIVEPIN)" \
    || bad "wrong tunnel pin: expected $EXPECT_PIN, node reports ${LIVEPIN:-none}"

RATES=$(curl -fsS -m20 "$B/~meta@1.0/info/metering-rates" -H 'accept: application/json' 2>/dev/null)
grep -qE 'request-bytes|response-bytes' <<<"$RATES" \
    && ok "metering@1.0 byte meters are live" || bad "metering-rates not applied: $(head -c 160 <<<"$RATES")"
grep -qE 'tunnel-bytes|meta-bytes' <<<"$RATES" \
    && bad "retired tunnel-*/meta-* resources still priced" || ok "no retired resources priced"

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

bal() { curl -fsS -m25 "$B/~meta@1.0/info/simple-pay-ledger" -H 'accept: application/json' 2>/dev/null \
        | jq -r --arg a "$PHONE_ADDR" '.[$a] // 0'; }

OPEN=$(bal)
[[ "$OPEN" =~ ^[0-9]+$ && "$OPEN" -gt 0 ]] && ok "payer balance $OPEN" || bad "payer has no balance"

say "idle drift"
I0=$(bal); sleep 30; I1=$(bal)
note "drift over 30s with $REG workers registered: $((I0-I1))"

# ---- relay pair over the LAN, addressed by host label ----------------------
say "relay + charge (LAN)"
A=$(bal)
S=$(curl -s -m120 -o /dev/null -w '%{size_download}' -H "Host: $B32.example.invalid" \
    "$B/~hyperbuddy@1.0/index" 2>/dev/null)
sleep 4; C=$(bal); SMALL=$((A-C))
[[ "${S:-0}" -gt 0 ]] && ok "small relay served: $S bytes" || bad "small relay failed"
note "charged $SMALL"

A=$(bal)
L=$(curl -s -m300 -o /dev/null -w '%{size_download}' -H "Host: $B32.example.invalid" \
    "$B/~hyperbuddy@1.0/bundle.js" 2>/dev/null)
sleep 6; C=$(bal); LARGE=$((A-C))
[[ "${L:-0}" -gt 0 ]] && ok "large relay served: $L bytes" || bad "large relay failed"
note "charged $LARGE"

# Zero with rates set is always wrong. A flat charge is the documented state of
# a broker serving traffic back on canonical tunnel@1.0 -- see the billing note
# in the example config -- so it is reported rather than failed.
if (( SMALL <= 0 || LARGE <= 0 )); then
    bad "a relay charged nothing with rates set ($SMALL, $LARGE)"
elif (( LARGE > SMALL * 100 )); then
    ok "served payload billed proportionally (large/small = $((LARGE / SMALL))x)"
else
    ok "billing live ($SMALL then $LARGE units)"
    note "served payload billed flat - expected on canonical; uploads do bill by size"
fi
if (( L > 0 )); then
    note "large charge / payload ratio: $(python3 -c "print(round($LARGE/$L,4))")"
fi

# ---- the same relay over the real public path -----------------------------
say "relay + charge (public path via VPS + TLS proxy)"
PUB="https://$B32.$PUBLIC"
A=$(bal)
P=$(curl -s -m300 -o /dev/null -w '%{size_download}' "$PUB/~hyperbuddy@1.0/bundle.js" 2>/dev/null)
sleep 6; C=$(bal); PUBCHG=$((A-C))
if [[ "${P:-0}" -gt 0 ]]; then
    ok "public relay served: $P bytes"
    note "charged $PUBCHG"
    (( PUBCHG > P / 2 )) && ok "public path is billed too" \
        || bad "public path served but barely billed ($PUBCHG for ${P}B)"
else
    bad "public relay failed - check the VPS hop and TLS proxy"
fi

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
(( FAIL == 0 )) && echo "  LIVE BILLING HOLDS" || echo "  DO NOT PUSH"
exit $(( FAIL > 0 ))
