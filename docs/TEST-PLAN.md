# Verification plan and v0.3.2 evidence

Target runtime: Java 21, Minecraft 1.21.1, NeoForge 21.1.248, ViScriptShop 1.2.0 and AE2 19.2.17.

Baseline v0.2.0 evidence was collected on 2026-09-01 and independently audited on 2026-09-02.
The v0.3.0 currency extension was verified on 2026-09-02:

- two independent `gradlew clean build --no-build-cache` runs passed and produced byte-identical
  main, sources and GameTest-probe JAR hashes;
- after moving every parallel AE fixture onto the template's central vertical axis, two consecutive
  `gradlew runGameTestServer` runs passed all 16 required tests in 1.349 s and 1.390 s, including a
  normal restart of the same test world;
- after registering the connector's AE2 in-world grid-node capability, the final 16-test regression
  passed again in 1.197 s and a previously disconnected restart fixture rejoined the powered grid;
- external hard-kill/restart matrix: all four required phase outcomes passed;
- formal JAR audit: connector classes/assets present and all GameTest/probe classes absent.

The v0.3.1 resource refresh replaces the borrowed AE2 interface texture with the three supplied
pixel assets: a 16x16 opaque base, a 16x192 transparent 12-frame signal layer and a 16x16 composite
preview. The signal uses a two-tick frame time without interpolation and is rendered on all six
outer faces using the cutout render type.

The v0.3.1 resource refresh was verified on 2026-09-02:

- the three packaged PNG SHA-256 hashes exactly match the supplied Downloads files;
- `gradlew runGameTestServer --no-build-cache` passed all 16 required tests in 1.122 s;
- two independent `gradlew clean build --no-build-cache` runs produced byte-identical main,
  sources and GameTest-probe JARs;
- the formal JAR has 79 entries, zero duplicate entries and zero GameTest/probe/fault/sentinel
  entries, and no longer references `ae2:block/interface`;
- a deterministic enlarged preview verifies the layer composition and 12-frame animation, while a
  real Minecraft client visual smoke test remains required before publication.

The v0.3.2 animation tuning changes only the signal metadata: frame time increases from two ticks
to 20 ticks, making the 12-frame cycle ten times slower (approximately 12 seconds at 20 TPS), and
per-pixel interpolation is enabled for a softer transition without spatially filtering the pixel
art. The base, signal and preview PNG bytes and the six-face layered model remain unchanged.

The v0.3.2 candidate was verified on 2026-09-02:

- animation metadata parses as `frametime=20` and `interpolate=true` in the formal JAR;
- the three PNG hashes and block-model hash are unchanged from v0.3.1;
- `gradlew runGameTestServer --no-build-cache` loaded the target dependencies and passed all 16
  required tests in 1.188 s;
- two independent `gradlew clean build --no-build-cache` runs produced byte-identical main,
  sources and GameTest-probe JARs;
- the formal JAR has 79 entries, zero duplicates, zero GameTest/probe/fault/sentinel entries, six
  signal faces and the unchanged anti-z-fighting offsets.

Release gates:

1. `gradlew runGameTestServer --no-build-cache` must pass all 16 required tests with a real AE2
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
- Native UI money availability combines digital money and the value of linked ME currency.
- A money price can be paid entirely by ME currency or jointly by digital balance and ME currency.
- A larger physical denomination pays a smaller price and preserves the remainder as digital change.
- Insufficient combined money causes no mutation; injected failure restores coins and digital money.
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

Production under `E:\Minecarft\B-03-SCEX-LegacyGenesis` remains on the currently published version
until an explicit v0.3.2 release approval is handed to `SCEX-长期维护`.
