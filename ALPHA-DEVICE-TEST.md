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

## Tunnel defect: the router 500s on larger responses

Symptom: `~meta@1.0/info` through the public tunnel host never returns, while
`info/address` and `info/port` return in ~5 s. Reproducible on every run.

**This is a failure at the tunnel router, not on the phone.** An earlier
revision of this report described it as a ~2 KiB response-size threshold. That
was wrong, and is corrected here.

What the phone's log actually shows for a failing request:

```
received, status: 500, duration: 2491, method: POST,
  peer: https://smoke.solutions,
  path: /~tunnel@1.0/response?tunnel-request-id=ADqph…

tunnel_client_response_error, ADqph…, failure ==>
  Message { body => "<html>..." <2,501 bytes>
            content-type => text/html
            server => nginx/1.18.0 (Ubuntu)
            status => 500 }
```

The node builds and uploads the response in 1–3 s and the router answers **HTTP
500 with an nginx error page**. `dev_tunnel` then retries every
`?DEFAULT_RETRY_TIME` (1 s); the caller sees an indefinite hang, which is what
made it look like a size cutoff. Successful small responses get a single 202.

Ruled out by direct test:

- **Not a body-size limit anywhere in the path.** POSTing 1 KB → 2 MB bodies
  straight at `https://smoke.solutions/~tunnel@1.0/response` returns 403
  "Tunnel response requires a signature from its address" at *every* size, so
  nginx forwards multi-megabyte bodies and the application receives them.
- **Not link materialization on the phone.** `dev_tunnel` calls
  `hb_cache:ensure_all_loaded/2` before committing a response, and AndEE's store
  chain ends in an Arweave gateway, so this was the leading hypothesis. The node
  log shows **zero** gateway or Arweave activity during the failures.
- **Not a stale client device.** The deployed implementation
  `SVKRgrshzPceeBdnknJNvR-m2zQ0mFYJvjMx0rSscW0` already contains
  `verify_committed/2` and `without_transport_commitment`, i.e. it is newer than
  the 2026-07-18 `fix/tunnel-response-materialization` merge.

So the router rejects our larger *signed* response specifically. The 500 is
served by nginx rather than by HyperBEAM, so the upstream either errored with no
body or died handling the request. Diagnosing further needs the router's own
logs.

Also seen during the run, unexplained: `~tunnel@1.0/ack` intermittently returns
403, and some response POSTs end in `gun_await_process_down … reason: timeout`.

Next step is to repeat against a different tunnel router. Large requests are
reported to work against other hosts, which would pin this to `smoke.solutions`.

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
