# AndEE alpha — on-device test report

Date: 2026-07-31
Branch: `alpha/andee-32g-20260731`, based on `4f1360d`
Device: Pixel 9 (`tokay`), Android 16, security patch 2026-05-05, USB adb
APK: `0.1.0-alpha.1` (versionCode 1), sha256 `da6ab5f91df371dbbeb7759a3d2cdae76111a27c88671d7ebff91a3be5ad00b8`

## Summary

The alpha builds, installs, boots, and serves traffic on an ordinary unrooted
phone, the zone start hook works end to end, and the tunnel works. One defect
was found and fixed: tunnelled requests touching the `store` key never
returned, caused by the now-removed negative-result cache.

| Gate | Result |
| --- | --- |
| Runtime + APK build from source | pass |
| `devices/common` eunit (33) | pass, 0 failures |
| `devices/android` eunit (16) | pass, 0 failures |
| Config invariants | pass |
| Install + node boot on device | pass, node up in 1–7 s |
| Operator next-boot config applied | pass |
| Zone start hook (Sam's MVP) | pass |
| Tunnel established, public round trip | pass |
| Tunnel, full `~meta@1.0/info` | pass (39027 bytes, ~4 s) |

## Base choice — why `4f1360d` and not `887eec8`

`4f1360d` is the same negative-result-cache release plus the HyperBEAM pin bump
to `c25a8183` (gateway node-options fix). Gateway behaviour is directly on the
tunnel path, so the newer pin was used. The two differ only in three files.

## Zone start hook

`dev_zone:start/3` and its bootstrap path already existed and were complete;
only the `andee.json` wiring was missing, exactly as Sam predicted ("really just
the config question again"). The hook is now the second `on.start` entry, after
the mandatory measurement boot hook so `check-andee-config-invariants.py` still
passes.

Verified on device by staging an operator next-boot config:

```json
{ "zone-allow": { "alpha": "http://127.0.0.1:1" } }
```

After restart, `~meta@1.0/info/zone-start` reported a `zone-start-status`, and
`zone-start/results/1` was:

```json
{"name":"alpha","peer-url":"http://127.0.0.1:1","http-status":400,"status":200}
```

That exercises the whole chain: operator config → boot hook → `zone-allow` read
from the request body (`target: body`) → bootstrap → recorded status. With the
shipped default the hook reports `not-configured` and starts nothing, so it is
safe to ship enabled.

## Tunnel defect: caused by the negative-result cache (FIXED)

Symptom: any tunnelled request whose response included `store` never returned,
including `~meta@1.0/info`, which embeds it. Scalars and every other composite
were fine, including a 27,996-byte `info/routes`, so it was never a size limit.
Sam's node on the same router was unaffected.

Cause: `store` was the only AndEE-specific subtree in the node message. It
carried the `hb_store_andee_gateway_cache` wrapper with a nested `inner-store`
and `preloaded-store`; `store/2/inner-store*` are keys no upstream node emits.

That shim was redundant. It and HyperBEAM `c25a8183` (PR #1032) address the same
problem — empty per-ID Arweave gateway round-trips during a send. The shim was
written against pin `8b9105d7`, before the upstream fix existed, and memoised
`not_found` IDs; `c25a8183` fixes the cause by keeping the node message Opts in
the gateway store's read/cache calls. This release carried both.

Fix: drop the wrapper, leaving the plain upstream `hb_store_gateway`. The config
invariant checker already permitted both shapes, so no checker change was
needed. The now-unused `hb_store_andee_gateway_cache` module, its
bootstrap preload and the invariant clause that permitted it were removed too.

Result through the tunnel, same node and router:

| path | before | after |
| --- | --- | --- |
| `~meta@1.0/info/address` | 200, 43 B | 200, 43 B |
| `~meta@1.0/info/routes` (control) | 200, 27996 B | 200, 27996 B |
| `~meta@1.0/info/store` | hung | **200, 883 B** |
| `~meta@1.0/info` | hung | **200, 39027 B, ~4 s (3/3 runs)** |

Tunnel response POSTs went from 1x202 / 21x500 to **7x202 / 0x500**.

Two earlier revisions of this report were wrong about this defect — first
blaming a ~2 KiB response-size threshold, then the router. Both were ruled out
by direct test: 1 KB-2 MB POSTs all reach the router application, a 28 KB
response transits fine, and Sam's node returns a 37,292-byte `info` bundle
through the same host.

### Follow-up needed

The negcache commit recorded a controlled A/B on a Pixel 9 — empty gateway
queries 50 -> 4 (~92%) — but that was measured against pin `8b9105d7`, i.e.
without the upstream fix. That win is now expected to come from `c25a8183`
instead. Re-run the same send workload to confirm the query volume is still low
without the shim, otherwise a measured improvement has been traded for an
assumed one.

## Stale pin in the tunnel smoke script

`scripts/andee-tunnel-smoke.sh` pins

```
TUNNEL_IMPL / REMOTE_TRUSTED_TUNNEL_IMPL = w1nxNAXJy0i-5ZZixUlzj0R2a99y_r8O8z3bFuGBxe8
```

but `https://smoke.solutions/~meta@1.0/info/trusted-devices/tunnel@1.0` now
returns `SVKRgrshzPceeBdnknJNvR-m2zQ0mFYJvjMx0rSscW0`. The script therefore
aborts at its first check before touching the device. All results above were
obtained by overriding both variables. The constant should be updated in the
repo.

## Host build notes (Linux, non-macOS)

Four blockers, all resolved; two required code changes, committed here.

1. No `rustup`. The prepared sysroot at
   `~/agent-work/andee-toolchain-20260701/rust-sysroot-1.93.1` carries the
   `aarch64-linux-android` std; `rust-wrap/bin` shims `cargo`/`rustc` onto it.
2. No arm64 emulation, so the template container exited immediately. Fixed with
   `docker run --privileged --rm tonistiigi/binfmt --install arm64`.
3. The rootfs copy runs under `pipefail`, so tar exiting 1 with "file changed as
   we read it" on the `/sys` mount point failed the build. Now tolerates exactly
   that, still failing on tar's fatal exit 2 or any failure of the extracting
   tar. Archive contents unchanged — confirmed by the template reproducing
   byte-identically (`image-sha256 = 29a8d9f0…`, same as the 2026-07-21 build).
4. The privileged container allocates loop devices via `/dev/loop-control` but
   has no udev to create the resulting node, and the host had only `loop0`.
   Pre-create `/dev/loop1`–`loop7`.

The Android emulator cannot run on this host at all: no `/dev/kvm`, VT-x
disabled in firmware. On-device testing over adb is the only option here.

## Not covered

- Andock execution (isolated workers, member images) was not exercised.
- No zone was joined against a real peer; the bootstrap was proven against an
  intentionally unreachable URL.
- Measurement/attestation content was not verified beyond the node booting with
  the measurement hook enabled.
- The `STATUS.md` release validation ladder was not re-run.
