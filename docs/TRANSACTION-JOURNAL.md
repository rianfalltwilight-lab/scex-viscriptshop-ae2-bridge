# v0.1.3 transaction journal

Every bound trade writes a compressed format-3 NBT write-ahead log under the world root at
`scex_viscriptshop_ae2_transactions`. `sequence.dat` allocates a persistent, strictly increasing
global sequence before the transaction file is created. A sequence gap is valid when the process
stops after allocation but before PREPARED is installed; duplicate/non-positive/out-of-range
sequences and duplicate transaction UUIDs fail closed.

## Durable states and deltas

- `PREPARED` is file-fsynced and installed with a same-directory atomic rename before the first
  mutation. It records only affected inventory slots plus exact pre/post values for affected AE
  item keys, money, XP and aggregated stock keys. Unrelated inventory slots are never replayed.
- Stock entries with a negative pre-state are unlimited and keep that exact negative value.
  Duplicate purchase entries for one stock key are summed into one transition.
- `COMMITTED` is written only after every affected domain equals its planned post-state. The file
  is fsynced and atomically replaced before the success event or UI RPC is emitted.
- Applied files are checkpointed only after overworld save I/O completes or during a clean stop.
  Deletion first atomically renames the journal to a tombstone and then deletes it.

Java NIO cannot open a directory for `fsync` on common Windows providers. File contents are
fsynced and same-directory rename is used; directory forcing is attempted and logged as
best-effort when the provider rejects it. The external kill/restart matrix in `TEST-PLAN.md`
verifies the actual target Windows filesystem behavior.

## Restart policy

Recovery parses and validates every journal before changing any resource, sorts by the persistent
sequence, and rejects corrupt identities, states, directions, item keys, amounts, slots and
duplicate resource transitions.

1. Zero PREPARED files is valid. One PREPARED file is valid only when it is the unique sequence
   tail; multiple or non-tail PREPARED files fail closed without mutation.
2. A valid PREPARED tail is rolled back first. Inventory/scalar resources must be at a recorded
   endpoint; affected AE counts may be anywhere in the directional pre/post interval so a partial
   payment insert can be reversed. `ROLLED_BACK` is fsynced before prefix confirmation.
3. The preceding COMMITTED/ROLLED_BACK prefix is then confirmed read-only. Every affected resource
   must already equal the newest journal post/selected state. A COMMITTED pre-state, mixed state,
   ABA value or third value is ambiguous and is never auto-forwarded.
4. Missing players, unloaded/offline terminals, corrupt data or compare-and-set conflicts keep the
   journals and block all bound trades. Recovery is retried at startup, player login and the next
   bound trade.

## Operator diagnosis

The log emits one `Pending WAL` line per unresolved transaction with sequence, state, transaction
UUID, player UUID, shop and file path, plus the exact resource key/current value for compare-and-set
conflicts.

Do not delete a pending file to make the shop start. Stop the server and back up the entire world,
including this directory. Compare the journal transitions with player data, the bound ME network,
shop stock, money and XP. For an ambiguous COMMITTED file, restore every affected domain to its
recorded post-state from a trusted backup or audited operator action, then restart so recovery can
confirm it read-only. For a corrupt journal, retain an immutable copy and repair it only through a
developer-reviewed recovery tool. After successful recovery and a normal save, confirm that the
journal was checkpointed.

Format 3 is not backward-compatible with pending format-2 journals. Before replacing v0.1.2,
recover and checkpoint all format-2 files with v0.1.2 while the server is stopped from player use.
Arbitrary command rewards remain disabled because external command side effects cannot be made
idempotent by this journal.
