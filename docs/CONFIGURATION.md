# Configuration and behavior

The server config is generated as `serverconfig/scex_viscriptshop_ae2-server.toml` for a world.

Each binding has the exact form:

```toml
bindings = ["main|minecraft:overworld|10|64|20|north"]
requireTerminalOwner = true
```

The position must contain an AE2 multipart host and the selected side must contain a live part implementing AE2's public `ITerminalHost`. Unbound shops continue to use normal ViScriptShop behavior. A bound shop fails closed if its chunk is unloaded, terminal is missing/offline, world interaction is denied, or ownership fails.

For a bound trade:

- item payment is inserted into that terminal's ME grid;
- item merchandise is extracted from the same grid;
- ViScriptShop money, experience, commands, stage flags, and configured stock keep their upstream meaning;
- all ME availability and insertion capacity is simulated before commit;
- the grid object is locked for the commit, and changed inventory causes compensation rollback;
- no automatic nearby-grid lookup or cross-dimensional scan occurs.

The bridge is required on server and players because ViScriptShop itself is a both-sides mod and the required Mixin must be identical. It adds no client UI assets beyond messages.
