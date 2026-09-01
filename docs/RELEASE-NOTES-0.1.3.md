# WITHDRAWN — DO NOT PUBLISH

This build implemented the wrong fixed-terminal direction and was never deployed. It is retained
only as historical recovery material for its format-3 journal. Use 0.2.0 or later.

# SCEX ViScriptShop AE2 Bridge 0.1.3

This release candidate replaces the v0.1.2 recovery algorithm with a format-3, globally ordered,
resource-delta WAL.

Key changes:

- Persistent total transaction sequence independent of filenames or filesystem enumeration order.
- Unique-tail PREPARED rollback and read-only COMMITTED-prefix confirmation; ambiguous COMMITTED
  pre/mixed/ABA/third states now fail closed instead of being auto-forwarded.
- Slot-level inventory transitions and exact AE/money/XP/stock deltas preserve unrelated later state.
- Full no-drop inventory planning before mutation, including component and max-stack boundaries.
- XP costs are prechecked, debited and journaled; duplicate stock entries aggregate and unlimited
  negative stock remains unlimited.
- Full pre-mutation WAL validation, durable checkpoint tombstones, operator diagnostics and an
  external four-boundary process-kill/restart harness.
- Post-commit listener/RPC failures cannot roll back a committed trade; command rewards remain
  rejected.

Compatibility remains pinned to Minecraft 1.21.1, NeoForge 21.1.248, ViScriptShop 1.2.0 and
AE2 19.2.17. Pending v0.1.2 format-2 journals must be recovered and checkpointed before upgrading.
