# SCEX ViScriptShop AE2 Bridge 0.3.3

This release makes the connector's colored signal animation reflect its real AE node state.

- inactive, offline, unpowered or channel-starved connectors use only the supplied `1.png` base;
- active and online connectors use the existing slow, interpolated 12-frame signal animation;
- inventory and held-item rendering use the inactive base appearance;
- the block entity synchronizes an `active` block-state property on AE power, channel and grid-boot
  changes;
- the three supplied PNG files and the active six-face model remain unchanged.

Per explicit user instruction, this revision was not given another automated test run. It is built
and statically inspected, then published together with the supplied updated `new.shop` and
`shopstage.js` through the `SCEX-长期维护` release workflow.
