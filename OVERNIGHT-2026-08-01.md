# AndEE alpha — unattended overnight run, 2026-07-31 → 2026-08-01

Device: Pixel 9 (`tokay`), Android 16, USB adb
Branch: `alpha/andee-32g-20260731` on `arweave://lapee`, pushed through `7bc81c9`
Configurator: `arweave://andee-configurator`, pushed at `9f439e8`

## Summary

The node builds, boots, serves, tunnels, takes operator config, and creates an
AndEE-attested zone. Bundling now starts and enforces payment but **cannot
accept items**. Execution **cannot run at all** — the device is not in the
build. Four defects were found and three fixed.

| Capability | Result |
| --- | --- |
| Build runtime + APK from source | pass |
| Boot on device | pass, 14–58 s |
| Operator next-boot config | pass, attested |
| Measurement / attestation | pass |
| Tunnel, public reachability | pass |
| Zone create (AndEE-gated) | pass |
| Zone survives restart | **fail** |
| Bundler boots, ledger, payment gate | pass |
| Bundler accepts an item | **fail** |
| Andock execution | **fail — device not shipped** |
| Configurator UI | pass, 12 tests |

## Fixed this run

### 1. Device priv assets never reached the runtime — fatal for the bundler

Two bugs compounding:

- `stage-android-devices.sh` ran `rm -rf priv` then copied `src/priv` over it,
  discarding the overlay's own `priv/` that `copy_device_tree` had just staged.
- `build-hb-android-runtime.sh` ran `find "$app/priv"` where rebar3 makes that
  path a **symlink**; without `-L`, find matches nothing.

Net effect: the runtime shipped **0** priv files and **0** Lua scripts. The
visible symptom was fatal — the bundler profile's P4 bootstrap hook died at boot:

```
Failed to prepare LapEE P4 ledger
missing_script hyper-token.lua (enoent at all four search paths)
→ failed_to_start_server
```

After the fix: **230 priv assets, 6 Lua scripts**, and the bundler profile boots.

### 2. Battery-optimisation prompt bricked unattended startup

The prompt ran on every `onResume` behind an instance-field guard that reset on
each cold start. It opens a modal Settings dialog over the ornament; on a dozing
phone nobody dismisses it, the ornament never resumes, and **the node never
starts**. Observed live — the phone sat dead behind
`com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`.

Now persisted in prefs (asked at most once ever) and skipped when the screen is
off or the keyguard is locked.

### 3. Notification and icon

Notification reads `PermawebOS node` / `🟢 Running`, green accent, and the copy
action only appears once a public URL has been verified to answer. Launcher icon
restored (it was missing entirely — no resources, no `android:icon`) and the
beam repositioned so its tip clears the adaptive-icon safe zone.

## Open defects

### A. Bundler rejects valid ANS-104 items

`~bundler@1.0/tx` answers, enforces payment (402 "Insufficient funds"), and
passes the gate once `bundler-free-byte-limit` is raised. It then rejects every
item:

```
{"details":"unsigned-item","error":"invalid-item","status":400}
```

Controlled: the **same signed item**, in the same process, was

- `isSigned: true`, signature verifies locally
- **accepted 200 by `bundler.mystical.computer`**
- **rejected 400 by the phone**

Five posting variants (`/tx` and `/item`, with and without `bundler-subject:
body`, three content types) all fail identically.

Lead, not yet isolated: `hb_util` routes all base64url decoding through
`b64veryfast:decode64_url_unchecked`, and this lineage has a recorded
`b64veryfast` corruption landmine for URL-decodes over 32 bytes. An ANS-104
signature is 512 bytes. That would make a valid item verify as unsigned. Also
possible: `bundler.mystical.computer` was patched on 2026-07-24 for exactly this
path and the patch is not in our pin (`c25a8183`).

Next step: decode a >32-byte base64url value on-device and compare against the
Erlang implementation.

### B. Andock execution is not in the build

`~andock@1.0/index` → 500. `dev_andock` is not in the runtime at all.

`devices/andock/` exists but the staging script only copies `devices/common` and
`devices/android`. Commit `1000485` (2026-07-18, Sam) moved andock to its own
tree — *"feat(andock): own standalone Android execution device"* — and the
staging was never updated. STATUS.md's passing validation
(`GET /~andock@1.0/index` → 200) predates that move.

**This is not a one-line fix.** `dev_andock` declares
`-device_libraries([lib_andock, lib_ouroboros_execution,
lib_ouroboros_execution_tools, lib_ouroboros_utils])` and the three ouroboros
libraries are **not in the lapee repo at all** — they live in the ouroboros
repo. Shipping andock means a cross-repo dependency decision, so I left it
rather than hack a vendor copy in unattended.

Worth noting the build still ships the payload it cannot use: the 895 MB
`andock-ubuntu-arm64.ext4.simg` and the proot manifest are in the runtime.

### C. Zones do not survive a restart

Created zone, bounced the node, `~meta@1.0/info/zones` → `not_found`.
`init` ends at `hb_http_server:set_opts` — runtime state, never persisted. The
zone status does expose a `nonvolatile-storage` section, so a mechanism may
exist (`encrypted-volumes`), but by default a restart loses the zone.

This compounds the URL problem: the peer URL is derived from the node address,
so an app-data wipe mints a new address and the old URL dies. Both zones created
during this run are already unreachable for that reason.

### D. A failing `on/start` hook kills the whole node

The P4 bootstrap failure did not degrade — it aborted server startup
(`failed_to_start_server`). One bad remote device should not take a node down.
Worth raising with Sam.

## The zone

Recreated on the current identity:

| | |
| --- | --- |
| name | `permawebos` |
| peer | `https://hiyty7pjzrg2s7ckrwcvftstxzrslegoptnm2y3umruwzul4ndla.smoke.solutions` |
| template | `measurement-device: andee@1.0` |

The attestation gate is verified **by construction** — the template carries the
AndEE measurement device, and `admit` matches the joiner's attestation against
zone templates via `peer_boot_attestation_body/3`. It is **not** verified by a
live refusal: `admit` requires the joiner to supply its own attestation, so
demonstrating a refusal needs a second attesting node. One phone, so not done.

## Configurator

`arweave://andee-configurator`, six profiles, 12 tests passing. The disk-image
half of the LapEE original is gone (no FAT/GPT reader, no wifi); output is
`next.json`. The live zone is pre-filled.

## Not done

- Live zone-admission refusal (needs a second node)
- Andock execution (needs the cross-repo decision above)
- Real bundling (blocked by defect A)
- The terminate-button scenario step, which taps a hardcoded coordinate and
  cannot land on a dozing screen. The scenario it belongs to passed
  (`verdict.json`: meta 200, boot 200, attested marker matched).
