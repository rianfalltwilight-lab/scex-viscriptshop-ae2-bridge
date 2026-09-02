# Connector behavior

There is no server-side shop-to-terminal coordinate configuration in 0.3.2. A player crafts the
`ME Shop Connector`, places it as part of their own powered AE2 network, and the placement becomes
that player's active shop link. Placing another connector replaces the previous link. The link is
stored in world data and survives restarts.

The connector must be loaded, online, powered, have an AE channel, and retain the placing player's
AE2 node ownership. It does not open a separate shop screen and is not assigned to a shop ID: it
extends every native ViScriptShop item trade used by that player.

While the connector is available:

- native shop item counts combine matching items in the player's backpack and linked ME storage;
- item prices are taken from ME first, then from the backpack for any remainder;
- purchased item rewards are inserted directly into the linked ME network;
- the shop balance includes SCEX physical currency in ME storage: `scex:coin_1` = 1C,
  `scex:coin_2` = 5C and `scex:coin_3` = 10C;
- native money prices consume the player's digital ViScriptShop balance first and then the minimum
  sufficient value of those ME coins; unavoidable denomination remainder becomes digital change;
- the complete reward insertion is simulated before any payment is debited, so a full ME network
  rejects the whole transaction without consuming payment;
- ViScriptShop money, experience, stage rules, stock, success/failure events and commands retain
  their native meaning, with ME coin debits included in the same durable transaction;
- commands run only after the item/money/XP/stock transaction is durably committed.

If the player has no connector, or the saved connector is missing/offline/unloaded, the bridge does
not intercept the purchase. ViScriptShop then performs its normal backpack-only transaction. No
nearby-grid scan, cross-dimensional search, operator coordinate or global shared shop inventory is
used.

The mod is required on both the server and clients because it adds a block/item, patches the native
ViScriptShop item-count RPC and synchronizes the linked ME coin value into the native balance UI.
