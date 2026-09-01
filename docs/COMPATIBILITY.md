# Compatibility matrix

| Component | Status | Notes |
|---|---|---|
| Minecraft 1.21.1 | required | exact minor line |
| NeoForge 21.1.248 | verified compile target | metadata range 21.1.248–21.1.x |
| ViScriptShop 1.2.0 fix1 | required | exact purchase-method descriptor and event model |
| AE2 19.2.17 | required | exact public API baseline |
| JEI 19.44.0.405 | passive compatibility | no JEI hooks changed |
| FTB Quests 2101.1.34 | passive compatibility | commands/events retained after commit |
| multiplayer | server-authoritative design | server-thread execution plus per-grid monitor lock |

No claim is made for other ViScriptShop or AE2 versions. Metadata deliberately fails dependency resolution outside the pinned patch versions.
