# Verification plan and v0.2.0 evidence

Target runtime: Java 21, Minecraft 1.21.1, NeoForge 21.1.248, ViScriptShop 1.2.0 and AE2 19.2.17.

Final evidence collected on 2026-09-01 and independently audited on 2026-09-02:

- two independent `gradlew clean build --no-build-cache` runs passed and produced byte-identical
  main, sources and GameTest-probe JAR hashes;
- after isolating every parallel AE fixture from the template boundary, two consecutive
  `gradlew runGameTestServer --no-build-cache` audit runs passed all 12 required tests in 997.8 ms
  and 1.054 s, including a normal restart of the same test world;
- external hard-kill/restart matrix: all four required phase outcomes passed;
- formal JAR audit: connector classes/assets present and all GameTest/probe classes absent.

Release gates:

1. `gradlew runGameTestServer --no-build-cache` must pass all 12 required tests with a real AE2
   connector, grid, drive and storage cell.
2. `scripts/run-wal-hard-kill-probe.ps1` must pass all four external process-kill/restart phases.
3. `gradlew clean build --no-build-cache` must pass.
4. A second independent clean build must reproduce the exact archive hashes.
5. The formal JAR must contain the connector code/assets and must not contain GameTest,
   crash-probe, fault-injection or sentinel classes.
6. Release artifacts must have recorded sizes and SHA-256 hashes.

## GameTest coverage

- Player placement joins the connector to the owned powered AE network without operator coordinates.
- A native ViScriptShop trade takes payment from ME and inserts purchased items into ME.
- ME and backpack can jointly satisfy a price; insufficient combined payment causes no mutation.
- A full ME network rejects the purchase before any payment is consumed.
- ViScriptShop component matching is honored for ME items.
- Native UI availability combines backpack and ME counts.
- Missing/offline connector delegates to native backpack-only behavior.
- Native XP and stock transitions commit once beside ME item movement.
- Injected failure after ME reward insertion rolls back ME, backpack and stock without item drops.
- PREPARED WAL replay restores ME and backpack state.
- COMMITTED WAL replay confirms the forward state without rewriting it.

## External hard-kill matrix

| Durable boundary | Restart result |
|---|---|
| ME payment extracted | PREPARED rolls back and fsyncs `ROLLED_BACK` |
| Backpack remainder applied | PREPARED rolls back and fsyncs `ROLLED_BACK` |
| Purchased goods inserted into ME | PREPARED rolls back and fsyncs `ROLLED_BACK` |
| COMMITTED fsync completed, fixture restored to pre-state | ambiguous state fails closed and journal remains |

The harness waits for a file-fsynced sentinel, forcibly terminates the Gradle/JVM tree, starts a new
NeoForge JVM against the same world, verifies recovery, and archives the transaction directory.
Probe classes and fault injection live only in the `gametest` source set and must not ship.

Production under `E:\Minecarft\B-03-SCEX-LegacyGenesis` remains unchanged until an explicit release
approval is handed to `SCEX-长期维护`.
