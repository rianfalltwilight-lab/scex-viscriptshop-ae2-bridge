# SCEX ViScriptShop AE2 Bridge 0.2.0

This release replaces the withdrawn fixed-terminal design with a player-owned `ME Shop Connector`.
It extends the native ViScriptShop experience rather than adding a separate shop or terminal UI.

Key behavior:

- players craft and place a connector on their own AE2 network; the last connector placed becomes
  their persistent active link;
- the native shop shows the combined backpack and linked-ME item availability;
- item payments are extracted from ME first, with backpack fallback for the remainder;
- purchased item rewards are inserted directly into the same ME network;
- reward capacity is simulated before payment, and failures roll back without dropped items;
- an unavailable connector delegates to unmodified native backpack-only trading;
- money, XP, stock, stages, events and commands retain native ViScriptShop behavior;
- the format-4 WAL records connector identity and bidirectional ME item deltas.

Runtime versions are pinned to Minecraft 1.21.1, NeoForge 21.1.248, ViScriptShop 1.2.0 and AE2
19.2.17. Install the mod on both server and clients.

