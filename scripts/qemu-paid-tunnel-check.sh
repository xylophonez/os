#!/usr/bin/env bash
# Pre-flash validation for a paid-tunnel LapEE image.
#
# Boots the candidate image under QEMU and exercises the whole path a flash
# would put in front of a user: the pinned devices load, the payment hooks are
# wired, a REAL AndEE registers as a tunnel client, traffic relays, and the
# charge lands on the party that should pay. Booting is the easy part and is
# not what has gone wrong; every failure this exists to catch has been a
# config, pin or attribution fault that a boot test reports as success.
#
#   ./scripts/qemu-paid-tunnel-check.sh [--img PATH] [--no-phone]
#
# Exits 0 only if every check passes. Restores the phone to its previous
# broker on the way out, including on failure.
set -uo pipefail
cd "$(dirname "$0")/.."

IMG=${IMG:-build/images/permawebos-no-tme-signed.img}
HOST_PORT=${HOST_PORT:-18734}
LAN_PORT=${LAN_PORT:-18735}
LAN_IP=${LAN_IP:-$(ip -4 addr show scope global | grep -oP 'inet \K192\.168\.[0-9.]+' | head -1)}
PHONE_IP=${PHONE_IP:-192.168.1.125}
PHONE_ADDR=${PHONE_ADDR:-VP9ZEXVi7DpeqtP0wzXrk0_abafEkfSEQXi6CtN4igA}
PKG=org.permaweb.andee
BOOT_TIMEOUT=${BOOT_TIMEOUT:-900}
USE_PHONE=1
WORK=build/qemu-paid-check
PASS=0; FAIL=0

while (($#)); do case "$1" in
  --img) IMG=$2; shift 2;;
  --no-phone) USE_PHONE=0; shift;;
  *) echo "unknown arg: $1" >&2; exit 2;;
esac; done

mkdir -p "$WORK"
say()  { printf '\n== %s ==\n' "$*"; }
ok()   { printf '  PASS  %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL  %s\n' "$*"; FAIL=$((FAIL+1)); }
note() { printf '        %s\n' "$*"; }

cleanup() {
    [[ -n "${SOCAT_PID:-}" ]] && kill "$SOCAT_PID" 2>/dev/null
    [[ -n "${QEMU_PID:-}"  ]] && kill "$QEMU_PID"  2>/dev/null
    [[ -n "${SWTPM_PID:-}" ]] && kill "$SWTPM_PID" 2>/dev/null
    if (( USE_PHONE )) && [[ -f "$WORK/phone-restore.json" ]]; then
        note "restoring phone to its previous broker"
        adb push "$WORK/phone-restore.json" /data/local/tmp/restore.json >/dev/null 2>&1
        adb shell "run-as $PKG sh -c 'cp /data/local/tmp/restore.json no_backup/boot-config/next.json'" >/dev/null 2>&1
        adb shell am force-stop $PKG >/dev/null 2>&1
        adb shell am start -n "$PKG/.OrnamentActivity" >/dev/null 2>&1
    fi
}
trap cleanup EXIT

[[ -f "$IMG" ]] || { echo "image not found: $IMG" >&2; exit 2; }
say "image under test"
note "$IMG  ($(stat -c %s "$IMG") bytes, sha256 $(sha256sum "$IMG" | cut -c1-16))"

# ---------- what the config claims, read from the ESP, before booting -------
say "config on the image"
CFG=$(docker run --rm -v "$PWD:/w" -w /w lapee-build:local bash -c '
    START=$(parted --script --machine /w/'"$IMG"' unit s print | awk -F: "/^1:/ {gsub(\"s\",\"\",\$2); print \$2}")
    SECT=$(parted --script --machine /w/'"$IMG"' unit s print | awk -F: "/^1:/ {gsub(\"s\",\"\",\$4); print \$4}")
    dd if=/w/'"$IMG"' of=/tmp/e.img bs=512 skip=$START count=$SECT status=none
    mtype -i /tmp/e.img ::/EFI/boot/config.json' 2>/dev/null)
if [[ -n "$CFG" ]]; then
    PIN=$(jq -r '."trusted-devices"."tunnel@1.0" // "none"' <<<"$CFG")
    HOOKS=$(jq -r '[.on.request[]?.device] | join(",")' <<<"$CFG")
    note "tunnel pin : $PIN"
    note "hook order : $HOOKS"
    note "rates      : $(jq -c '."metering-rates" // {}' <<<"$CFG")"
    [[ "$PIN" != none ]] && ok "tunnel@1.0 is pinned" || bad "no tunnel@1.0 pin"
    [[ "$HOOKS" == p4@1.0,* ]] && ok "p4 runs before the tunnel" \
        || bad "p4 must precede tunnel@1.0 in on/request (got: $HOOKS)"
    NONZERO=$(jq -r '[."metering-rates"//{} | to_entries[] | select(.value>0) | .key] | join(",")' <<<"$CFG")
    [[ -n "$NONZERO" ]] && ok "non-zero rates: $NONZERO" || note "all rates zero (charge checks will be skipped)"
    # Nothing reports tunnel-* or meta-* resources. A config pricing them bills
    # zero on every relay while the node looks healthy.
    STALE=$(jq -r '[."metering-rates"//{} | keys[] | select(startswith("tunnel-") or startswith("meta-"))] | join(",")' <<<"$CFG")
    [[ -z "$STALE" ]] || bad "config prices resources nothing reports ($STALE)"
    if [[ -n "$NONZERO" ]]; then
        [[ "$NONZERO" == *bytes* ]] && ok "a metering@1.0 byte resource is priced" \
            || bad "no *-bytes resource priced - carried traffic will not be billed"
    fi
else
    bad "could not read the ESP from the image"
fi

# ---------- boot ------------------------------------------------------------
say "boot under QEMU"
OVMF_CODE=$(ls /usr/share/qemu/edk2-x86_64-code.fd /usr/share/OVMF/OVMF_CODE_4M.fd /usr/share/OVMF/OVMF_CODE.fd 2>/dev/null | head -1)
OVMF_VARS=$(ls /usr/share/qemu/edk2-i386-vars.fd /usr/share/OVMF/OVMF_VARS_4M.fd /usr/share/OVMF/OVMF_VARS.fd 2>/dev/null | head -1)
[[ -n "$OVMF_CODE" && -n "$OVMF_VARS" ]] || { bad "no OVMF firmware"; exit 1; }

cp "$IMG" "$WORK/scratch.img"; cp "$OVMF_VARS" "$WORK/vars.fd"

# Strip location publishing from the COPY the test boots. The hook uploads a
# permanent record claiming this node serves <location-url>, which belongs to
# the appliance, not to QEMU. The real image is untouched.
docker run --rm -v "$PWD:/w" -w /w lapee-build:local bash -c '
    IMG=/w/'"$WORK"'/scratch.img
    START=$(parted --script --machine $IMG unit s print | awk -F: "/^1:/ {gsub(\"s\",\"\",\$2); print \$2}")
    SECT=$(parted --script --machine $IMG unit s print | awk -F: "/^1:/ {gsub(\"s\",\"\",\$4); print \$4}")
    dd if=$IMG of=/tmp/e.img bs=512 skip=$START count=$SECT status=none
    if mtype -i /tmp/e.img ::/EFI/boot/config.json > /tmp/c.json 2>/dev/null \
       && grep -q location /tmp/c.json; then
        python3 - <<PY
import json
c = json.load(open("/tmp/c.json"))
c.pop("location-url", None); c.pop("location-ttl", None)
on = c.get("on", {})
if isinstance(on.get("start"), list):
    on["start"] = [h for h in on["start"] if h.get("device") != "location@1.0"]
    if not on["start"]: on.pop("start")
json.dump(c, open("/tmp/c.json", "w"), indent=2)
PY
        mcopy -i /tmp/e.img -o /tmp/c.json ::/EFI/boot/config.json
        dd if=/tmp/e.img of=$IMG bs=512 seek=$START count=$SECT conv=notrunc status=none
        echo stripped
    fi' 2>/dev/null | grep -q stripped \
    && note "location publishing stripped from the test copy (real image untouched)"
rm -rf "$WORK/tpm"; mkdir -p "$WORK/tpm"
swtpm socket --tpm2 --tpmstate "dir=$WORK/tpm" --ctrl "type=unixio,path=$WORK/tpm/sock" \
    --flags not-need-init,startup-clear --daemon --pid "file=$WORK/tpm/pid" 2>/dev/null
sleep 1; SWTPM_PID=$(cat "$WORK/tpm/pid" 2>/dev/null)

# Refuse to start if anything already holds a port we forward: the poll loop
# below cannot tell the image under test from whatever else answers.
for _p in "$HOST_PORT" "$LAN_PORT"; do
    if ss -tln 2>/dev/null | grep -qE "[:.]${_p}[[:space:]]"; then
        bad "port $_p is already in use - refusing to run"
        note "whatever holds it would be tested instead of the image:"
        ss -tlnp 2>/dev/null | grep -E "[:.]${_p}[[:space:]]" | sed 's/^/        /'
        note "free it, or re-run with HOST_PORT=<free> LAN_PORT=<free>"
        exit 1
    fi
done

qemu-system-x86_64 -machine q35,accel=tcg -cpu qemu64,+rdtscp,+ssse3,+sse4.1,+sse4.2,+avx \
    -m "${QEMU_MEMORY:-8192}" -smp 4 \
    -drive "if=pflash,format=raw,readonly=on,file=$OVMF_CODE" \
    -drive "if=pflash,format=raw,file=$WORK/vars.fd" \
    -device qemu-xhci,id=xhci -drive "if=none,id=usb,file=$WORK/scratch.img,format=raw" \
    -device usb-storage,drive=usb,removable=on,bootindex=0 \
    -chardev "socket,id=chrtpm,path=$WORK/tpm/sock" -tpmdev emulator,id=tpm0,chardev=chrtpm \
    -device tpm-tis,tpmdev=tpm0 \
    -netdev "user,id=n0,hostfwd=tcp:127.0.0.1:${HOST_PORT}-:8734" -device virtio-net-pci,netdev=n0 \
    -nographic > "$WORK/serial.log" 2>&1 &
QEMU_PID=$!
B=http://127.0.0.1:$HOST_PORT

# QEMU fails at startup in well under a second. Confirm it survived before
# trusting anything that answers on the port.
sleep 2
kill -0 $QEMU_PID 2>/dev/null || {
    bad "QEMU failed to start"
    tail -20 "$WORK/serial.log" | sed 's/^/        /'
    exit 1
}

deadline=$((SECONDS + BOOT_TIMEOUT))
while (( SECONDS < deadline )); do
    # Liveness first, so a reply from an unrelated listener cannot pre-empt it.
    kill -0 $QEMU_PID 2>/dev/null || { bad "QEMU exited before the node answered"; tail -20 "$WORK/serial.log" | sed 's/^/        /'; exit 1; }
    curl -fsS -m3 "$B/~meta@1.0/info/address" -o "$WORK/addr" 2>/dev/null && break
    sleep 5
done
[[ -s "$WORK/addr" ]] && ok "node answered: $(cat "$WORK/addr")" || { bad "node never answered in ${BOOT_TIMEOUT}s"; exit 1; }
curl -fsS -m120 -H "accept: application/json" -H "accept-bundle: true" "$B/~measurement@1.0/boot" -o "$WORK/attestation.json" 2>/dev/null \
    && ok "boot attestation fetched ($(stat -c %s "$WORK/attestation.json") bytes)" \
    || bad "boot attestation failed"

# ---------- the devices actually loaded, not merely pinned ------------------
say "operator config reached the node"
LIVE=$(curl -fsS -m60 "$B/~meta@1.0/info" -H 'accept: application/json' -H 'accept-bundle: true' 2>/dev/null)
LIVEPIN=$(jq -r '."trusted-devices"."tunnel@1.0" // "none"' <<<"$LIVE" 2>/dev/null)
if [[ "$LIVEPIN" == "$PIN" && "$PIN" != none ]]; then
    ok "node is running the image's config (pin $LIVEPIN)"
else
    bad "node did NOT apply the operator config (image pin $PIN, node reports $LIVEPIN)"
    note "config rejected at boot, or ESP unreadable on this platform - not a device-fetch fault"
fi

say "devices resolve at runtime"
ST=""
for _ in $(seq 1 6); do
    ST=$(curl -fsS -m120 "$B/~tunnel@1.0/status" -H 'accept: application/json' 2>/dev/null)
    grep -q open-registrations <<<"$ST" && break
    sleep 20
done
if grep -q open-registrations <<<"$ST"; then
    ok "tunnel@1.0 loaded and answering"
    grep -q dispatched-requests <<<"$ST" && note "delivery-lease build (Sam lineage)" || note "older build: no delivery-lease keys"
else
    bad "tunnel@1.0 did not load"; note "$(head -c 200 <<<"$ST")"
fi
RATES=$(curl -fsS -m30 "$B/~meta@1.0/info/metering-rates" -H 'accept: application/json' 2>/dev/null)
if grep -q '"status":200' <<<"$RATES" && [[ -n "$NONZERO" ]] && grep -q "${NONZERO%%,*}" <<<"$RATES"; then
    ok "operator metering-rates are live (${NONZERO})"
else
    bad "operator metering-rates not applied (node shows defaults only)"
fi
LEDGER=$(curl -fsS -m30 "$B/~meta@1.0/info/simple-pay-ledger" -H 'accept: application/json' 2>/dev/null)
OPEN=$(jq -r --arg a "$PHONE_ADDR" '.[$a] // empty' <<<"$LEDGER" 2>/dev/null)
[[ -n "$OPEN" && "$OPEN" =~ ^[0-9]+$ && "$OPEN" -gt 0 ]] \
    && ok "payer seeded with $OPEN" || bad "payer $PHONE_ADDR has no balance (got: ${OPEN:-empty})"

# ---------- a real client, a real relay, a real charge ----------------------
if (( USE_PHONE )) && adb devices 2>/dev/null | grep -q device$; then
    say "real AndEE as tunnel client"
    adb shell "run-as $PKG cat no_backup/boot-config/effective.json" > "$WORK/phone-restore.json" 2>/dev/null
    PEER="http://$LAN_IP:$LAN_PORT"
    socat TCP-LISTEN:$LAN_PORT,bind=$LAN_IP,fork,reuseaddr TCP:127.0.0.1:$HOST_PORT & SOCAT_PID=$!
    sleep 2
    jq -n --arg peer "$PEER" --arg pin "$PIN" '{
        "trusted-device-signers":["kaYP9bJtpqON8Kyy3RbqnqdtDBDUsPTQTNUCvZtKiFI",
                                  "ggltHF0Cnv9ylH3vM1p7amR2vXLMoPLQIUQmAEwLP-k"],
        "trusted-devices":{"tunnel@1.0":$pin},
        "on":{"start":[{"device":"tunnel@1.0","method":"POST","path":"connect",
                        "peer":$peer,"workers":16,"hook":{"result":"ignore"}}]}}' > "$WORK/phone-test.json"
    adb push "$WORK/phone-test.json" /data/local/tmp/t.json >/dev/null 2>&1
    adb shell "run-as $PKG sh -c 'cp /data/local/tmp/t.json no_backup/boot-config/next.json'" >/dev/null 2>&1
    adb shell am force-stop $PKG >/dev/null 2>&1; sleep 2
    adb shell am start -n "$PKG/.OrnamentActivity" >/dev/null 2>&1

    REG=0
    for _ in $(seq 1 30); do
        sleep 6
        R=$(curl -fsS -m20 "$B/~tunnel@1.0/status" -H 'accept: application/json' 2>/dev/null | jq -r '."open-registrations" // 0')
        [[ "${R:-0}" -gt 0 ]] && { REG=$R; break; }
    done
    (( REG > 0 )) && ok "AndEE registered ($REG workers)" || bad "AndEE never registered"

    if (( REG > 0 )); then
        B32=$(python3 -c "
import base64,sys
a='$PHONE_ADDR'; raw=base64.urlsafe_b64decode(a+'='*(-len(a)%4))
print(base64.b32encode(raw).decode().lower().rstrip('='))")
        bal() { curl -fsS -m25 "$B/~meta@1.0/info/simple-pay-ledger" -H 'accept: application/json' 2>/dev/null | jq -r --arg a "$PHONE_ADDR" '.[$a] // 0'; }
        say "relay + charge"
        I0=$(bal); sleep 30; I1=$(bal); IDLE=$((I0-I1))
        note "idle drift over 30s: $IDLE"

        A=$(bal)
        S=$(curl -s -m120 -o /dev/null -w '%{size_download}' -H "Host: $B32.tunnel.example.com" "$B/~hyperbuddy@1.0/index" 2>/dev/null)
        sleep 4; C=$(bal); SMALL=$((A-C))
        [[ "${S:-0}" -gt 0 ]] && ok "small relay served: $S bytes" || bad "small relay failed"
        note "charged $SMALL"

        A=$(bal)
        L=$(curl -s -m300 -o /dev/null -w '%{size_download}' -H "Host: $B32.tunnel.example.com" "$B/~hyperbuddy@1.0/bundle.js" 2>/dev/null)
        sleep 6; C=$(bal); LARGE=$((A-C))
        [[ "${L:-0}" -gt 0 ]] && ok "large relay served: $L bytes" || bad "large relay failed"
        note "charged $LARGE"

        # The point of the whole exercise: a payload ~1865x larger must cost
        # proportionally more. A flat or inverted charge means the bytes are
        # being metered somewhere that cannot see them, or billed to nobody.
        if [[ -n "$NONZERO" ]]; then
            if (( LARGE > SMALL * 100 )); then
                ok "charge is payload-proportional (large/small = $((LARGE / (SMALL>0?SMALL:1)))x)"
            else
                bad "charge NOT payload-proportional: ${SMALL} for ${S}B vs ${LARGE} for ${L}B"
                note "flat or inverted charge - do not flash expecting billing to work"
            fi
        fi
    fi
else
    note "skipping client/charge checks (no phone, or --no-phone)"
fi

say "result"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
(( FAIL == 0 )) && { echo "  SAFE TO FLASH"; exit 0; } || { echo "  DO NOT FLASH"; exit 1; }
