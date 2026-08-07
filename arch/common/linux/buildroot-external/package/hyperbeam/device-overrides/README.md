# Device-source overrides

Verbatim copies of HyperBEAM preloaded-device sources. `hyperbeam.mk` copies each
over the preload **copy** under `_build/lapee-preload-src`, never over
`$(@D)/src`, so the built tree matches upstream and no core file is patched.

| File | Upstream branch | Taken at |
| --- | --- | --- |
| `dev_metering.erl` | `xylophonez/hyperbeam` `feat/meter-request-response-bytes` | `edge` + 1 |

These are snapshots, not links, and drift silently. Check the upstream branch
before rebuilding, and delete the override once it lands upstream.
