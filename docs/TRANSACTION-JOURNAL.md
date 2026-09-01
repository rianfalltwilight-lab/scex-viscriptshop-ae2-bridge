# v0.1.2 transaction journal

Each bound trade writes a small compressed NBT write-ahead log under the world root directory
`scex_viscriptshop_ae2_transactions`.

- `PREPARED` is fsynced before the first mutation and contains the full player inventory,
  money/XP, affected shop stocks, and exact counts for every affected AE item key.
- After all state changes, `COMMITTED` is atomically fsynced with the exact post-state.
- On startup, a PREPARED-only transaction is restored to its pre-state; COMMITTED is completed
  to its post-state. Replay is idempotent and keyed by transaction UUID.
- If the player or terminal is offline, the journal remains and all bound trades fail closed.
  Recovery is retried when the player logs in or before another bound trade.
- Applied journals are removed only after the normal overworld autosave I/O has completed, or
  after a clean server stop. No trade calls `MinecraftServer.saveEverything`.

Do not manually remove pending journal files after a crash. A normal backup must include this
directory. For rollback from 0.1.2, first stop the server cleanly and confirm the directory is
empty; otherwise restart once with 0.1.2 and allow recovery to finish before downgrading.

Arbitrary command rewards are rejected for ME-backed trades because their external side effects
cannot be made idempotent by this journal.
