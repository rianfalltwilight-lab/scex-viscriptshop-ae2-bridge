# v0.2.0 transaction journal

Every connector-backed trade writes a compressed format-4 NBT write-ahead log under the world root
at `scex_viscriptshop_ae2_transactions`. `sequence.dat` allocates a persistent, strictly increasing
global sequence before the transaction file is created. Sequence gaps are valid; duplicate,
non-positive or out-of-range sequences and duplicate transaction UUIDs fail closed.

## Durable states and deltas

- `PREPARED` is file-fsynced and installed with a same-directory atomic rename before the first
  mutation. It records the player UUID, connector dimension/position, affected inventory slots,
  exact pre/post ME counts, money, XP and aggregated stock keys.
- ME payment deltas are negative, purchased-item deltas are positive, and inventory deltas cover
  only the backpack payment remainder. Unrelated slots and ME keys are never replayed.
- `COMMITTED` is written only after every affected domain equals its planned post-state. It is
  fsynced and atomically replaced before success notification or command execution.
- Applied journals are checkpointed only after overworld save I/O completes or during a clean stop.
  Deletion first atomically renames the file to a tombstone.

File contents are fsynced. Directory forcing is attempted and logged as best-effort because common
Windows NIO providers cannot open a directory for `fsync`; the external kill/restart matrix in
`TEST-PLAN.md` verifies behavior on the target filesystem.

## Restart policy

Recovery validates every journal before mutation, sorts by persistent sequence, and rejects corrupt
identities, connector coordinates, states, directions, item keys, amounts, slots or duplicate
resource transitions.

1. A single unique-tail `PREPARED` journal can be rolled back; multiple or non-tail PREPARED files
   fail closed.
2. A valid PREPARED tail restores inventory and scalar resources to their pre-state. Affected ME
   counts may be anywhere within their directional pre/post interval, allowing recovery after a
   partial payment debit or reward insertion. `ROLLED_BACK` is fsynced before confirmation.
3. The preceding `COMMITTED`/`ROLLED_BACK` prefix is confirmed read-only. Pre-state, mixed, ABA or
   third values are ambiguous and are never auto-forwarded.
4. A missing player, missing/unloaded/offline connector, corrupt data or compare-and-set conflict
   retains the journal and blocks connector-backed trades until recovery succeeds. Recovery retries
   at startup, player login and the next linked trade.

Do not delete pending files to make a shop start. Stop the server, back up the entire world, then
compare the reported transitions with player data, connector network, shop stock, money and XP.

Format 4 is incompatible with pending format-3 journals from the withdrawn 0.1.3 build. If such a
journal exists, recover and checkpoint it with 0.1.3 in a closed maintenance copy before upgrading.
The withdrawn build was never deployed to production, so no format-3 production journal is expected;
deployment must still verify the directory before changing JARs.
