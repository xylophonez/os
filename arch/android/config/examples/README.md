# Example operator configs

These are **operator** configs, imported at runtime from the ornament's config
picker (they land in `no_backup/boot-config/next.json` and are merged over the
measured base config on the next boot). They are not the base config and are not
part of the boot measurement's device set.

## `bundler-zone.json`

Makes the node a paid bundler behind a reverse tunnel, and joins a zone.

Derived from the known-good `aopayment-bundler-p4-paid-remote` profile
(`lapee-bundler-profile-paid@ac239012fcb0`, captured 2026-07-11) with two
changes:

- `tunnel@1.0` now points at `SVKRgrshzPceeBdnknJNvR-m2zQ0mFYJvjMx0rSscW0`.
  The captured profile pinned `DJb6D9QKd7tYcggYopvrrln0a0QQhfxWa1H2GQM8NG0`,
  which `smoke.solutions` no longer trusts.
- `zone-allow` is added, so the shipped `zone@1.0/start` boot hook has something
  to join.

**Before use, replace the `zone-allow` placeholder** with the zone name and its
peer URL:

```json
"zone-allow": { "my-zone": "https://peer.example" }
```

`zone-allow` is a map of zone name to peer URL. Under the shipped default the
boot hook reports `not-configured` and joins nothing, so leaving the placeholder
in place will simply fail that one join.

### What the bundler is made of

There is no built-in bundler device in this build. `~bundler@1.0` is assembled
at boot from remote devices: `on/start` runs `lapee-p4-bootstrap@1.0`
(`RSQlLsmP…`), and `trusted-devices` plus `name-resolvers` pin the payment,
pricing, settlement and GC devices it depends on. All of those IDs were
confirmed resolvable on Arweave on 2026-07-31.

`router-opts` offers `/~bundler@1.0/tx` and `/~bundler@1.0/item` at price 0, so
this is a free bundler. Set a price there, and `simple-pay-price`, to charge.

### Keys deliberately omitted

The captured profile also set `store`, `match-index`, `arweave-index-store`,
`priv-store` and `load-remote-devices`. Those are reserved boot keys: the
Android boot-config store strips them from operator configs and restores the
measured base values, so including them would be misleading. Two consequences:

- That profile ran fully volatile (RAM-only). This node keeps its measured
  persistent LMDB primary store instead.
- `load-remote-devices` does nothing in the pinned HyperBEAM anyway — the key
  does not exist in that source tree. Remote device loading is governed by
  `trusted-devices` and `trusted-device-signers`.

`render-index-page` is likewise a no-op in this HyperBEAM and is not carried
over. To actually stop serving hyperbuddy, set `default-index` — that is the
key HyperBEAM reads, and it is not reserved, so an operator config may set it.

## `zone-host.json`

Turns this node into the **host** of a `permawebos` zone whose admission is
gated on AndEE attestation, and exposes it publicly through the tunnel so other
AndEE nodes can join.

`zone-init-allow: true` permits `~zone@1.0/init`; without it the node refuses to
create a zone at all. The tunnel `connect` hook is included because the zone is
only useful if peers can reach it — a zone hosted at `127.0.0.1` can be joined
by nothing.

Import this config, let the node boot and the tunnel come up, then create the
zone (the copy action in the notification gives you the public URL):

```sh
PUB=https://<base32-address>.smoke.solutions
curl -X POST "$PUB/~zone@1.0/init?name=permawebos&self-url=$PUB&template-measurement-device=andee@1.0"
```

`template-measurement-device=andee@1.0` is what makes this an AndEE-only zone:
admission runs `~measurement@1.0/verify-peer` against that template, so a peer
that cannot produce a matching AndEE boot measurement is refused.

### The live zone

Created 2026-07-31 on the Pixel 9 node and reachable through its tunnel:

| | |
| --- | --- |
| name | `permawebos` |
| peer | `https://qd5thgjy7vkcr6pp2hyvqgardhrc426tjjaobbnetv7ctwrdsqda.smoke.solutions` |
| ring address | `isUcx_QUq_XY-Wdxk66D6FMgoyANEdsgV2na2VDZuTk` |
| initializer | `gPszmTj9VCj579HxWBgRGeIua9NKQOCFpJ1-KdojlAY` |
| template | `measurement-device: andee@1.0` |

Both example configs now carry that as their `zone-allow`, so importing either
one joins this zone. Verified publicly: `~zone@1.0/status?name=permawebos`
through the tunnel returns `identity: zone/permawebos`, `initialized: true`.

**Caveat on persistence.** The peer URL is derived from the hosting node's
address, so it only holds while that node keeps its identity. Clearing the app's
data mints a new address and the zone becomes unreachable at this URL. A
longer-lived home would be a node with a stable operator, which is why
`smoke.solutions` was the first choice — it already runs the device tree, but
creating a zone there needs its operator key.

### Why not host it on mystical.computer

`~zone@1.0` and `~measurement@1.0` live in this repo's `devices/common` and
`devices/android`, not in upstream HyperBEAM. `mystical.computer` runs stock
HyperBEAM and returns HTTP 500 for `~zone@1.0/...`, so it cannot host an
AndEE-attested zone without the whole device tree being deployed there first.
`smoke.solutions` does have the devices (`~zone@1.0/status` answers 200) but
creating a zone on it needs that node's operator key.

## Posting items to the bundler

Use `content-type: application/ans104`. Posting a signed ANS-104 item as
`application/octet-stream` is rejected with
`{"error":"invalid-item","details":"unsigned-item"}`: the body arrives as a raw
binary, `hb_message:signers/2` finds no commitments, and the item reads as
unsigned. `application/ans104` selects the ans104 codec, which decodes the body
before verification.

```sh
curl -X POST \
  -H 'content-type: application/ans104' \
  -H 'bundler-subject: body' \
  --data-binary @item.ans104 \
  http://127.0.0.1:8734/~bundler@1.0/tx
```

A 200 returns an `id` header equal to the item's own id. Note the shipped
example sets `bundler-free-byte-limit: 0`, so every item is charged and an
unfunded caller gets HTTP 402; raise it to exercise the path without tokens.

## Reading composite values with curl

`curl https://<node>/~meta@1.0/info` returns a 2,285-byte HTML index page rather
than the node message. That is not a fault. HyperBEAM's `accept-bundle` defaults
to false, and with the httpsig codec a composite value serialises to headers
with an empty body, which triggers `dev_message:index/3`'s fallback page.

Requests arriving *through the tunnel* work, because the HyperBEAM client on the
router adds `accept-bundle: true` to outgoing requests. Hand-driven curl does
not. Use either:

```sh
curl '.../~meta@1.0/info?accept-bundle=true'      # multipart bundle
curl -H 'Accept: application/json' '.../~meta@1.0/info'
```

Scalars (`info/address`, `info/port`) are unaffected.
