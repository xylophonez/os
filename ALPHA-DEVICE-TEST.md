# AndEE alpha — on-device test report

Date: 2026-07-31
Branch: `alpha/andee-32g-20260731`, based on `4f1360d`
Device: Pixel 9 (`tokay`), Android 16, security patch 2026-05-05, USB adb
APK: `0.1.0-alpha.1` (versionCode 1), sha256 `da6ab5f91df371dbbeb7759a3d2cdae76111a27c88671d7ebff91a3be5ad00b8`

## Summary

The alpha builds, installs, boots, and serves traffic on an ordinary unrooted
phone, and the zone start hook works end to end. One real defect was found:
the tunnel drops responses larger than about 2 KiB.

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
| Tunnel, responses over ~2 KiB | **fail** |

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

## Tunnel defect: responses over ~2 KiB never arrive

Reproducible on every run. Public host derived from the node address as usual.

| Response size (bytes) | Public result |
| --- | --- |
| 4 (`info/port`) | 200, 5.4 s |
| 43 (`info/address`) | 200, 5.6 s |
| 2035 (404 body) | 404 delivered, 7.8 s |
| 2285 (`info`) | **no response, 0 bytes, times out** |
| 9152 (`info` as JSON) | **no response, 0 bytes, times out** |

The boundary lies between 2035 and 2285 bytes, i.e. almost certainly 2048.
The client receives zero bytes — the response never starts, rather than
truncating partway.

The node is not at fault: it serves the same paths locally in under 0.5 s and
emits much larger bodies locally without trouble (2406, 2501, 2550, 9152 and
273706 bytes all logged as sent).

Node-side evidence points at the response upload leg. Successful tunnel
requests produce exactly one `POST /~tunnel@1.0/response?tunnel-request-id=…`
returning 202. The three failing `info` probes produced three request-ids
retried 8, 9 and 10 times respectively — the node keeps re-uploading and the
caller keeps waiting.

Also seen in tunnel control traffic during the run: `~tunnel@1.0/ack` and
`~tunnel@1.0/register` returned 403 five times and 404 eight times. Not
investigated; may or may not be related.

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
