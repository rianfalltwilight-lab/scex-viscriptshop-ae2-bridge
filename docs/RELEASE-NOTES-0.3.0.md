# SCEX ViScriptShop AE2 Bridge 0.3.0

This release removes the manual money-bag step for physical SCEX currency stored in the player's
linked ME network.

- the native ViScriptShop balance display includes `scex:coin_1` (1C), `scex:coin_2` (5C) and
  `scex:coin_3` (10C) stored in the linked ME network;
- native money prices consume digital balance first, then linked ME coins;
- the payment planner uses the smallest sufficient face value and converts unavoidable excess into
  digital change;
- physical coin extraction, digital balance, item movement, XP and stock share the existing durable
  format-4 WAL and rollback boundary;
- insufficient funds, network changes or an injected commit failure leave both balances unchanged;
- physical currency received into ME can fund the next native shop purchase without being withdrawn
  or placed into the money bag.
- the connector now registers AE2's in-world grid-node capability, so neighboring AE devices can
  rediscover and reconnect it regardless of block-entity initialization order after a server restart.

The money bag remains available for manual conversion outside linked-ME shop transactions. No
production deployment is included in the development artifact; release still requires the normal
audit, client publication and server maintenance handoff.
