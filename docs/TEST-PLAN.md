# Verification plan and v0.1.3 evidence

Automated evidence collected on 2026-09-01 with Java 21.0.12, Minecraft 1.21.1,
NeoForge 21.1.248, ViScriptShop 1.2.0 and AE2 19.2.17:

1. `gradlew runGameTestServer --no-build-cache`: **29/29 required tests passed** in 4.130 s
   using a real AE2 multipart `ItemTerminalPart`, grid, drive and storage cell. The 50-transaction
   probe measured WAL p50 31.688 ms, p95 36.046 ms and max 46.592 ms on this Windows runner.
2. `scripts/run-wal-hard-kill-probe.ps1`: **4/4 external kill/restart phases passed**. The script
   waited for a file-fsynced sentinel, forcibly stopped the Gradle/JVM process tree, started a new
   NeoForge JVM against the same world, verified recovery, and archived the transaction directory.
3. `gradlew clean build`, formal-JAR content inspection, metadata/dependency pin verification and
   SHA-256 generation are release gates.

## GameTest coverage

- Authoritative success conserves player/ME items, decrements stock once, preserves money, commits
  net XP and emits one success. Insufficient items, XP, stock, ME capacity, disconnected grids and
  exact-component mismatches fail without mutation.
- XP covers insufficient, exact balance, cost plus gain, and PREPARED rollback of total/level/
  progress. Stock covers last-stock contention, two players, duplicate cart-entry aggregation and
  negative unlimited stock across repeated transactions.
- Inventory planning covers the payment-freed slot at 1/64/65 goods, full inventories,
  non-stacking max-size-one goods, partial multi-stack capacity and component-distinct stacks.
  Rejections and all rollback boundaries assert zero spawned `ItemEntity` objects.
- Injected runtime failures occur after goods extraction, after inventory debit/before payment
  insertion and after a real partial payment insertion. PREPARED replay restores player, ME,
  money, XP and stock exactly.
- A throwing downstream `BuySuccess` listener cannot undo the already durable transaction.
- Format-3 ordering covers three same-player transactions with scrambled filenames, two players
  sharing global stock, repeat replay idempotence, and a valid two-COMMITTED prefix plus unique
  partially-applied PREPARED tail. A PREPARED file before a later COMMITTED file is rejected.
- Delta replay preserves unrelated slots. COMMITTED pre/ABA/third states fail closed and retain the
  journal; an all-post state is confirmed read-only.
- Corruption probes cover out-of-range slots, empty AE keys, negative amounts, bad side/state/
  sequence, duplicate slots and duplicate global sequences. Every case is detected before any
  resource mutation and does not escape the recovery boundary.

## External hard-kill matrix

The non-release `scex_viscriptshop_ae2_crash_probe` namespace and PowerShell harness cover:

| Boundary after PREPARED | Kill state | Restart result |
| --- | --- | --- |
| Goods extracted | PREPARED | Rolled back and fsynced `ROLLED_BACK` |
| Final inventory plan applied / before payment insert | PREPARED | Rolled back and fsynced `ROLLED_BACK` |
| One of two payment items inserted | PREPARED | Directional interval rollback succeeded |
| COMMITTED fsync completed | COMMITTED | Ambiguous restart pre-state failed closed; journal retained |

Sentinels and per-phase stdout/stderr are generated under `build/wal-hard-kill-probe`. Probe classes,
fault injection and sentinel code are in the `gametest` source set and must not appear in the formal
release JAR.

Remaining isolated-server acceptance boundaries are partial-component modes beyond exact matching,
chunk/protection-provider combinations, and live optional JEI/FTB listener implementations. The
public event boundary is tested, but those optional mods are not bundled in this harness. Production
under `E:\Minecarft\B-03-SCEX-LegacyGenesis` remains unchanged until a deployment manifest is
explicitly approved by `SCEX-长期维护`.
