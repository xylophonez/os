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
