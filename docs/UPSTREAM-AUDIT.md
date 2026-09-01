# Upstream and installed-baseline audit

Audit date: 2026-09-01 (Asia/Shanghai)

| Component | Installed baseline | Audited source | License | SHA-256 of installed JAR |
|---|---:|---|---|---|
| Minecraft | 1.21.1 | Mojang/NeoForge mappings | proprietary game runtime | n/a |
| NeoForge | 21.1.248 | server launcher `libraries/net/neoforged/neoforge/21.1.248` | LGPL-2.1 | n/a |
| ViScriptShop | 1.2.0 (`fix1` source) | `e07304f4e319b4a43b164887aec04d55e24a52ff` | GPL-3.0 | `11106bcc833de38278cbf056d48d01bc2e63d0b4709ae570930a1c216443335b` |
| AE2 | 19.2.17 | tag `neoforge/v19.2.17`, `79ee2c704ad62941a426c26b1cb1f76ef5b2ee5a` | LGPL-3.0-or-later (public API files carry MIT headers) | `460d779a0609b81409907d9956de8f6f70a1b0912257e3e5c3c7e75ac9630e95` |
| LDLib2 | 2.2.37 | binary development input | LGPL-3.0 | `41df4b79f0a3ec622f221c75e5315988d27a5c8896f3ed80993d5f87f7bdbcde` |

ViScriptShop's authoritative server entry is `BuyMerchantPayload.buyMerchant`. Its public
cancellable `BuyPre` event exposes the authoritative cost/gain and player but not `shopLocation`.
The bridge injects only a thread-local shop identifier at this entry, handles an online-linked
transaction in the event subscriber, and delegates to upstream unchanged when no connector is
available. `GetItemCountC2SPayload` is patched so the native UI sees backpack plus ME availability.
No upstream JAR is modified.

The connector uses AE2's public `AENetworkedBlockEntity`, managed grid node, active grid/storage
service, `MEStorage`, `AEItemKey`, `Actionable` and player `IActionSource` APIs. The node requests a
channel and idle power. Ownership comes from AE2's node ownership assigned when the player places
the block; the bridge additionally checks world interaction permission before a linked trade.
