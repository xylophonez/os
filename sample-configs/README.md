# Sample configurations

These files are exemplar operator overlays showing how independent services
can be composed on PermawebOS. They are not privileged application profiles or
replacement architecture base configurations. On AndEE, import a private copy
through the **Next boot config** picker, then terminate and reopen AndEE. The
app deep-merges the overlay with its measured, application-agnostic base
configuration and measures the resulting effective node configuration at boot.

`andee-ouroboros-with-tunnel.json` demonstrates composing `tunnel@1.0` with
Ouroboros on AndEE. Ouroboros supports interchangeable executor backends; this
example selects the generic Android `andock@1.0` device for that role. A LapEE
configuration could instead select `qemu@1.0`. The example also includes the
AndEE local-inference provider and authenticated remote providers behind the
ordinary `inference@1.0` multiplexer. These are choices made by this example,
not special properties of PermawebOS, Ouroboros, or any architecture image.

`andee-sovereign-scheduler.json` expresses a narrower role: the AndEE node
acts as sovereign scheduler and compute host over its operator's own processes
and offers no gateway surface. It composes a `tunnel@1.0` connect hook for
reachability with explicit `arweave-scheduler-*` tuning and installs no
request hooks; client-side routing sends gateway operations to public
infrastructure, so only scheduling and compute for the node's own processes
terminate here. The `arweave-scheduler-confirmation-depth` of 3 is an explicit
choice made by this example (the node default is 10), and the file's `_note`
keys record the reasoning. These are choices made by this example, not special
properties of PermawebOS or AndEE. Replace
`**[Arweave block height to sync from]**` with an integer block height in the
private copy.

Every `****` value must be replaced in a private copy before use. Replace
`**[base32 encoded node address]**` with the base32 encoding of the boot node
address. Never commit the private copy.

To materialize an ignored deployment copy from a JSON file containing provider
credentials:

```sh
arch/android/scripts/prepare-deployment-config.py \
  --secrets /path/to/provider-keys.json \
  --location-url https://BASE32_NODE_ADDRESS.smoke.solutions
```

The command writes
`arch/android/build/deployment/andee-ouroboros-with-tunnel.json` with mode
`0600`.
The location URL also supplies the corresponding `node-host`.
Providers without a key in the source file are omitted from that private copy;
credentials shown in the example are placeholders rather than a prescribed
provider set.

This tunnel-backed example intentionally omits the `name@1.0` request hook.
Tunnel host routing removes its routing `Host` before local execution, while
`name@1.0` treats a root request without a host as a 404. Keeping the manifest
and blacklist hooks allows the root `default-request` redirect to run normally.
