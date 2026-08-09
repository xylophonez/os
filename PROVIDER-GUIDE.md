# Setting up a LapEE tunnel provider

Everything here was executed and verified on 2026-08-06 — QEMU, then a real
laptop, then live on the public internet. Where something bit us, the guide
says so rather than describing the version that was supposed to work.

**What you end up with:** a sealed LapEE appliance acting as a public tunnel
provider, so nodes with no inbound reachability — phones, other LapEEs,
anything behind CGNAT — become addressable at
`https://<node-b32>.<your-domain>/`, with a browser-trusted certificate.

**What makes this different from a VPS broker:** the trust-bearing part runs on
an attested appliance you control, not a rented box with operator-custody keys.
That was the whole point of moving it.

---

## 1. Decide the topology before you build anything

One question determines the rest: **can anything on the internet open a
connection to your provider?**

- **Public IP you control, able to port-forward** → *Direct edge*. Forward
  `443` to the LapEE and you are done. Simplest, fewest moving parts.
- **CGNAT, filtered inbound, or no public IP** → *Relay edge*. Something with
  a public address must accept connections on your behalf, and the link to it
  must be **initiated from your side**.

Check honestly, because a "real" public IP proves nothing: Sky handed out a
genuine routable address with every inbound port filtered, and Starlink
residential is CGNAT with a rotating address. Look at your router's WAN
address — a `100.64.x.x` there is conclusive CGNAT. Reverse-DNS on your
observed public IP is the other tell (`customer.*.isp.starlink.com`).

**A LapEE cannot hold the outbound leg itself.** It runs HyperBEAM and nothing
else — no package manager, no shell, no WireGuard. So a relay edge needs a
**companion box on your LAN** (a Pi, a NAS, any always-on Linux host) to hold
the tunnel and forward to the LapEE. The LapEE stays sealed; the companion is
not part of its trust boundary.

```
   DIRECT EDGE                          RELAY EDGE
   internet                             internet
      │ :443                               │ :443
      ▼                                    ▼
   router (forward)                     public host (VPS)
      │                                    ▲ outbound-initiated
      ▼                                    │ (ssh -R / WireGuard)
   LapEE :8443                          companion box (LAN)
                                           │
                                           ▼
                                        LapEE :8734
```

## 2. Prerequisites

**Hardware.** A machine that boots UEFI, with a working **TPM 2.0**. Note that
`LAPEE_NO_TME=1` only skips the *memory-encryption* gate — measured boot still
expects a TPM, and the baseline checkout has no "skip preconditions" target.
If the TPM is disabled in firmware, enable it before anything else.

**Two USB sticks.** Operator config lives on the ESP and is read at boot, so
every config change is a re-flash and reboot. With one stick you are walking it
back and forth; with two you can prepare the next while the current one runs.

**Secure Boot keys.** Either the machine is already enrolled with a key set you
still have, or you enroll one with the provisioner image. Read §6 before
assuming you know which key that is — this is the single most likely thing to
waste your afternoon.

**A domain with API-driven DNS**, for the wildcard certificate. Wildcards force
DNS-01 validation; there is no HTTP-01 path for `*.example.com`.

**A published `tunnel@1.0`**, pinned by Arweave ID. The pins used here:

| Device | Implementation ID |
|---|---|
| `tunnel@1.0` | `u3K9cV05p14IZ_t0dWTptuu87J1NT-LhgirldS9SsVM` |
| `acme@1.0` | `72XYKqaXO5JVj6_LMpkNVjWrv1wGQH3ZW9_2eNyQVsk` |

A cold appliance fetches these through its gateway store on first resolve —
verified working on hardware with no local device archive.

## 3. Build the image

From the LapEE checkout (`~/Dev/lapee-baseline-sam-20260721` here):

```sh
make no-tme          # TME=0; drop to `make tme` on TME-capable hardware
```

Produces `build/images/permawebos-no-tme-signed.img`, signed with whatever sits
in `secureboot/`. Put your `wifi.conf` in the repo root first if the machine
will use wireless; the credentials are baked into the ESP.

Sanity-check before you touch hardware:

```sh
make qemu IMAGE=build/images/permawebos-no-tme-signed.img
```

This boots under OVMF + swtpm and fetches the boot attestation. **It does not
test Secure Boot** — QEMU here runs without enrolled keys, so a signature your
firmware rejects will still sail through. Passing QEMU proves the image
structure and boot path, nothing about trust.

Budget a few minutes per boot: without KVM this is TCG emulation.

## 4. Write the operator config

This is where most silent failures come from. LapEE's init **validates the
operator config and discards the entire file** if it breaks the rules — you get
a node that boots perfectly and ignores everything you asked for.

Rejected outright: `load-remote-devices`, `preloaded-store`, `forge-bootstrap`,
`loaded-device-store` at the top level, and `measurement-body-source` **at any
depth**. That last one is the trap: copying the base config's measurement hook
into your file — the obvious "be explicit" instinct — poisons the whole config.

You don't need to restate the base hooks anyway. Init preserves the measured
`measurement@1.0` and `zone@1.0` start hooks and **prepends** your `on/start`
entries to them. `on/request` passes through untouched because the base defines
none.

Broker role — `config.json` in the repo root:

```json
{
  "trusted-device-signers": ["kaYP9bJtpqON8Kyy3RbqnqdtDBDUsPTQTNUCvZtKiFI"],
  "trusted-devices": {
    "tunnel@1.0": "u3K9cV05p14IZ_t0dWTptuu87J1NT-LhgirldS9SsVM",
    "acme@1.0":   "72XYKqaXO5JVj6_LMpkNVjWrv1wGQH3ZW9_2eNyQVsk"
  },
  "on": {
    "request": [{ "device": "tunnel@1.0", "path": "request" }]
  }
}
```

Client role — same shape, but a start hook instead:

```json
{
  "trusted-device-signers": ["kaYP9bJtpqON8Kyy3RbqnqdtDBDUsPTQTNUCvZtKiFI"],
  "trusted-devices": { "tunnel@1.0": "u3K9cV05p14IZ_t0dWTptuu87J1NT-LhgirldS9SsVM" },
  "on": {
    "start": [{
      "device": "tunnel@1.0", "method": "POST", "path": "connect",
      "peer": "https://tunnel.example.com", "workers": 3,
      "hook": { "result": "ignore" }
    }]
  }
}
```

Two details that are load-bearing: hb config keys are **dash-form binaries**
(atom keys are silently ignored), and an `on/start` hook needs
`"hook": {"result": "ignore"}` or its return value breaks the next handler in
the chain. Never name a hook function `start` — it collides with hb dispatch;
the convention is `run`, `boot` or `connect`.

## 5. Flash and verify

```sh
make operator-config-apply IMAGE=build/images/permawebos-no-tme-signed.img
sudo dd if=build/images/permawebos-no-tme-signed.img of=/dev/sdX bs=4M conv=fsync
```

Identify `/dev/sdX` carefully — compare `lsblk` before and after inserting, and
check `/dev/disk/by-id/` for the `usb-` symlink. Then **read it back**, don't
trust the write:

```sh
SZ=$(stat -c %s build/images/permawebos-no-tme-signed.img)
sha256sum build/images/permawebos-no-tme-signed.img
sudo dd if=/dev/sdX bs=4M status=none | head -c $SZ | sha256sum
```

Confirm the config actually landed by mounting the ESP and reading
`/EFI/boot/config.json` back off the stick.

## 6. Booting, and the Secure Boot trap

**Symptom: you pick the USB in the boot menu and land straight back on the boot
menu.** No splash, no kernel output, no error. That is firmware refusing to
execute the binary, and it looks identical to a corrupt image.

Diagnose cheapest-first:

1. **Take the `UEFI:`-prefixed entry** if the menu lists the stick twice. The
   image is GPT/ESP-only, so a legacy/CSM entry bounces exactly this way.
2. **Disable Secure Boot and retry.** If it boots, the cause is signature trust
   and nothing else. Two minutes, fully reversible, and it saves you from
   "fixing" the wrong layer.

**Why this happens.** `make signing-keys` stamps every key set it generates
with the same certificate CN — `LapEE operator db signing key (<hostname>)`.
Generate keys a few times over months and you accumulate several **different
keys with identical names**. Nothing in the image or the certificate tells you
which one a given machine enrolled, and `sbverify --cert` will cheerfully
report `Signature verification OK` against the key you happen to hold while the
firmware disagrees. This box had four.

**Fix, without touching firmware.** A PE binary can carry multiple signatures
and UEFI accepts the image if *any* of them validates — so sign with every
candidate key and stop guessing:

```sh
cp build/images/<image>.signed.efi /tmp/uki-multi.efi
for d in ~/Dev/lapee-*/secureboot; do           # each distinct key set
    sbsign --key $d/db.key --cert $d/db.crt \
           --output /tmp/uki-multi.next /tmp/uki-multi.efi \
        && mv /tmp/uki-multi.next /tmp/uki-multi.efi
done
sbverify --list /tmp/uki-multi.efi              # expect N signatures
```

Then `mcopy` that binary over `::/EFI/boot/BootX64.efi` in the image's ESP —
the same extract/inject dance `operator-config-apply` performs — and re-flash.
Secure Boot stays on and firmware key state is untouched.

**Reprovisioning is the wrong first move.** It rewrites PK/KEK/db, it is the
least reversible operation available, and it only becomes correct if Secure
Boot off boots fine *and* a multi-signed image still fails — which would mean
the enrolled key was generated on a machine you no longer have.

## 7. Prove it on the LAN before going public

Host routing lives inside the device, so you need no DNS for this stage.

1. Boot the broker LapEE. Confirm it answers:
   `curl http://<lapee-ip>:8734/~meta@1.0/info/address`
2. Confirm the device loaded and the broker role is active:
   `curl -H 'accept: application/json' http://<lapee-ip>:8734/~tunnel@1.0/status`
   → expect `open-registrations: 0`, not a `device_not_loadable` error.
3. Connect a client (§8), then route by Host header alone:

```sh
curl -H "Host: <client-b32>.tunnel.example.com" \
     http://<lapee-ip>:8734/~hyperbuddy@1.0/index
```

The hostname is base32 of the base64url-decoded node address, lowercase,
unpadded — always 52 characters:

```python
base64.b32encode(base64.urlsafe_b64decode(addr + '=' * (-len(addr) % 4))).decode().lower().rstrip('=')
```

## 8. Connecting clients

`POST /~tunnel@1.0/connect` returns **403 "requires local execution or operator
signature"** for every ordinary HTTP call. The gate is not source-IP:
`connect_scope/4` admits the call only when the message carries **no `host`
key**, meaning it came from in-node resolution — a config hook. Every HTTP
request has a Host header, `adb forward` doesn't change that, and a Host-less
HTTP/1.0 request just earns a Cowboy 400. So: connect from config, or sign with
the operator wallet.

For another LapEE, that's the client config from §4. For an **AndEE**, stage it
as a next-boot config over adb — no rebuild, no reinstall:

```sh
P=org.permaweb.andee
adb push client-config.json /data/local/tmp/andee-next-boot-config.json
adb shell "run-as $P sh -c 'mkdir -p no_backup/boot-config; \
    cp /data/local/tmp/andee-next-boot-config.json no_backup/boot-config/next.json'"
adb shell am force-stop $P
adb shell am start -W -n "$P/.OrnamentActivity"
adb shell run-as $P cat no_backup/boot-config/effective.json    # verify the merge
```

The app consumes `next.json`, merges your start hooks ahead of the enforced
`measurement@1.0` and `zone@1.0` hooks, and writes `effective.json`. To revert
the phone to its baked-in config, clear `no_backup/boot-config/*` and restart.

Registration is quick — a Pixel 9 registered 3 workers in about 8 seconds on
LAN, and about 5 seconds through a public URL.

## 9. Going public

### Direct edge

Forward router `443` to the LapEE's TLS port (`8443` — the node needn't be root
to bind it), set `acme.public-ip` to your public address, and let `acme@1.0`
publish its own A records.

### Relay edge (CGNAT, filtered inbound)

The public host accepts `:443`; the link to your house is opened from your
side by the companion box. What we run today:

```sh
# companion box → public host, durable
AUTOSSH_GATETIME=0 autossh -M 0 -f -N \
  -o "ServerAliveInterval 30" -o "ServerAliveCountMax 3" \
  -o ExitOnForwardFailure=yes \
  -i ~/.ssh/id_ed25519_vpstunnel \
  -R 9080:<lapee-lan-ip>:8734 root@<public-host>
```

`autossh` exits immediately without `AUTOSSH_GATETIME=0`. Make it a systemd
unit with `Restart=always` and `WantedBy=multi-user.target` so it survives
reboots — a hand-started tunnel is a demo, not a service.

The public host then needs `:443` to reach `127.0.0.1:9080`. If it already runs
an `acme@1.0` terminator, point its `clear_port` at the forwarded port and move
its own broker aside; keep the old config file so rollback is one `cp` and a
restart.

**Whatever you use as the edge, do not let it terminate TLS if you can avoid
it** — see §12. Terminating at the edge is the pragmatic near-term; SNI
passthrough with the certificate held on the LapEE is the destination.

### Certificates

DNS-01 issuance is entirely outbound, so a box with zero inbound reachability
can still obtain a browser-trusted wildcard. Two constraints decide *where* you
run it:

- **Namecheap's API authorises by whitelisted caller IP.** On a rotating CGNAT
  address that breaks unpredictably and silently. Either issue from a host with
  a stable whitelisted IP, or move DNS to a provider with zone-scoped API
  tokens and no whitelist.
- **The DNS credential is the real root of trust** (§12). Scope it to a zone
  you're willing to lose.

Set `acme.public-ip` to whatever address actually holds `:443` — the edge, not
the LapEE. `ensure_a` writes that value into the A records on boot, so if the
records already point there it is a harmless no-op. **Point a provider config
at a hostname that is serving live traffic and you will cut it over the moment
the node boots.** That has happened, in production, for real.

## 10. Verify it properly

Two failure modes make a broken tunnel look healthy. Both bit us.

**Test HTTP/2 and HTTP/1.1 separately.** HTTP/2 carries no `Host` header — the
authority arrives as the `:authority` pseudo-header — so any proxy that
rebuilds upstream headers from `cowboy_req:headers/1` drops it, and the broker
answers *as itself* instead of routing. Every browser negotiates h2, so this
breaks all real users while `curl --http1.1` looks perfect. (Fixed in
`lib_acme_tls_proxy` by setting `host` explicitly from `cowboy_req:host/1`.)

```sh
curl -s https://<b32>.<domain>/~meta@1.0/info/address              # h2
curl -s --http1.1 https://<b32>.<domain>/~meta@1.0/info/address    # h1
```

**Only one common path distinguishes nodes.** `/`,
`/~hyperbuddy@1.0/index` and `/~hyperbuddy@1.0/bundle.js` are byte-identical on
*any* two nodes running the same HyperBEAM build — they "MATCH" whether or not
routing happened. **`/~meta@1.0/info/address` is the only one that proves the
request reached the tunnelled node.** Put it first; treat the rest as
payload-integrity checks.

The full acceptance matrix, each byte-compared against a direct fetch from the
client node:

| Path | Proves |
|---|---|
| `/~meta@1.0/info/address` | routing reached the right node |
| `/` | status + `Location` survive the envelope (307) |
| `/~hyperbuddy@1.0/index` | ~2.3 KB body, multipart boundary |
| `/~hyperbuddy@1.0/bundle.js` | 4.2 MB, streaming and size limits |

Also run it from **off your own network** — a host that is neither the LapEE
nor your workstation — or you are only proving the LAN works.

## 11. Charging for the traffic you carry

A broker can run free — leave `metering-rates` unset and everything here is
inert. This section is for operators who want to be paid.

### Where the meter lives

`metering@1.0` prices the node. `p4@1.0` hands it the inbound request when it
opens a session and the result when it closes one, and it sizes both:
`request-bytes` for what arrives, `response-bytes` for what the node returns.
These sit beside `beam-reductions`, so a node is priced on the traffic it
carries as well as the compute it does. The tunnel device holds no pricing
logic at all.

`p4@1.0` must come **before** `tunnel@1.0` in `on/request`, or the tunnel is
handed no relay target and the broker answers callers locally — a silent 404,
not an error. It also must run in `on/response`, which is where the price is
taken.

### What actually bills today, and what does not

Measured 2026-08-09 against `tunnel@1.0` `49d5fa8`:

| direction | 2,285 octets | 4,262,645 octets |
| --- | --- | --- |
| caller uploads **through** the tunnel | 8,812 | 4,269,172 |
| tunnel **serves** a payload back | 6,591 | 6,591 |

Uploads bill by size. Downloads bill a flat figure regardless of payload — a
charge ratio of 1.0000 against a payload ratio of 1,865.

The cause is a hook-chain halt, not a meter. A tunnelled node posts its payload
to `/~tunnel@1.0/response`; the tunnel device answers that hook with
`{error, _}` to mean *handled*; `hb_hook:execute_handlers` halts on any non-ok
status; and `dev_meta` then skips its response hook — which is where `p4@1.0`
calls `price`. The bytes are consumed by `estimate` and never collected.

So **a broker today sells uploads and gives downloads away**. If a tunnel's job
is to serve a node's content to the public, that is most of its traffic. Set
rates knowing this; when the halt is fixed upstream the served direction starts
billing with no config change.

### Setting a rate

```json
"metering-rates": {
  "request-bytes": 1,
  "response-bytes": 1,
  "beam-reductions": 0
}
```

One base unit per byte is roughly **$2/TB**: an AO token is 1e12 base units and
trades around $2. Sizes are of the ETF encoding, not wire octets — stable and
monotone in payload size, but not reproducible by a payer from the bytes it
sent, and the ratio to wire size varies by request shape. Calibrate against
your own traffic.

`tunnel-seconds` is also available and does bill: the tunnel device consumes it
on the register path, which completes its hook chain normally. A ~4.3 s hold at
rate 1e6 charged 4–5M units. It measures how long a registration waited, so at
any non-zero rate a tunnelled node pays to sit idle.

### Who pays

The tunnelled node, from its own balance, seeded in `simple-pay-ledger` against
its address. Not the caller: `dev_simple_pay` derives the payer from the request
signers, and a public tunnel's callers are anonymous by construction. The node
renting the hostname pays for the traffic that hostname attracts, which is also
the party benefiting and the only one holding a balance. An anonymous caller's
session is priced but uncollected — no charge, and no 402.

Seed generously while testing. A 402 writes the negative balance before testing
it and never reverses it, and requeued deliveries re-charge per dispatch, so an
exhausted balance becomes a burn loop rather than a clean refusal.

### Checking it works

Relay something small and something several megabytes large and compare the
balance delta. Zero with rates set means nothing is metered or it is billed to
nobody. A flat charge on the served direction is expected — see above. Uploads
should scale with payload; if they do not, check that `p4@1.0` precedes
`tunnel@1.0`.

`scripts/qemu-paid-tunnel-check.sh` runs this against an image before you flash
it, and `scripts/live-paid-tunnel-test.sh` against a booted appliance.

## 12. What performance to expect

Measured on the live stack, 4.2 MB bundle:

| Path | Time |
|---|---|
| Direct from the client node, LAN | 0.64 s |
| Through the LapEE broker, LAN | 4.1 s cold, then 2.0 s / 1.0 s warm |
| Through the full public path | 13–18 s |

The first request pays device load and worker warm-up. The public figure is
dominated by the home **uplink**, crossed twice on a relay edge. Small
responses are unaffected; large assets are the weak point of home-hosted
providers, and worth knowing before you promise anyone a CDN.

Respect the tunnel's own windows when configuring any hop: registrations are
held **45 s** and responses up to **300 s**. A proxy or load-balancer with a
default 60 s idle timeout will sever long-polls. Set edge timeouts to 330 s+.

## 13. Trust model

| | Direct edge | Relay, TCP passthrough | Relay that terminates TLS |
|---|---|---|---|
| Read request/response plaintext | no | **no** | **yes** |
| Modify traffic undetected by browsers | no | **no** | **yes** |
| Hold the TLS private key | no | **no** | **yes** |
| Deny service | ISP can | yes | yes |
| Observe metadata (SNI, timing, volumes) | ISP can | yes | yes |
| Forge hb-signed responses | no | no | **no** — commitments are end-to-end |

Passthrough is strictly better and worth getting to. Terminating at the edge is
acceptable while you're standing things up, but be honest that the edge is then
fully trusted.

**Above all of it sits DNS.** Whoever holds the registrar API credentials can
re-point your records *and* satisfy DNS-01 to mint their own valid certificate
— complete interception, regardless of edge mode. So the DNS credential lives
on the LapEE (or on one host you trust), never scattered onto edge boxes, and
the registrar account's own security is part of your provider's security.

The broker relays at HTTP level, so it sees plaintext for the nodes it fronts.
That is the argument for the broker being your attested appliance and the
untrusted box being demoted to a ciphertext pipe.

## 14. Rollback

Keep every step reversible and know the command before you need it:

- **Config change** — re-flash the previous stick, reboot.
- **Edge cutover** — restore the saved config on the public host and restart;
  disable the companion box's tunnel unit. Because DNS never changed, there is
  no propagation wait in either direction.
- **Secure Boot** — the multi-signed image changes no firmware state, so there
  is nothing to undo.

## 15. Known gaps

- **Broker registration is unauthenticated.** Any node can register. Fine while
  clients are hand-picked; gate it (payment, allowlist, or zone proof) before
  advertising a public broker.
- **A relay edge's companion box is a single point of failure.** One ssh
  process holds the public path; WireGuard, or a second companion, is sturdier.
- **Zone membership and location are separate protocols**, so there is no clean
  "find the providers in my zone" query yet. The intended shape is a location
  record signed by the zone ID, intersected with zone proofs over GraphQL, glued
  with a Lua script rather than a new device.
- **Certificate renewal must be verified, not assumed.** Confirm a renewer is
  actually running on whichever host holds the cert, and that its DNS
  credentials still work from that host's current IP.
