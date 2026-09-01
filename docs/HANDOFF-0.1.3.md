# SCEX ViScriptShop AE2 Bridge 0.1.3 handoff

## Deployable artifact

- File: `scex-viscriptshop-ae2-bridge-neoforge-1.21.1-0.1.3.jar`
- Size: 95,364 bytes
- SHA-256: `9A7D053B063A7B9C534CB1F8EAFF29E6F60C2CDC4315D242BDD3B27B68CEB27D`
- Source commit: `0fcd4e9e5cd5daf3e7968427ba33634f41900a8b`
- Runtime pins: Minecraft 1.21.1, NeoForge 21.1.248, ViScriptShop 1.2.0, AE2 19.2.17,
  Java 21

Only the file above is deployable. Never place the `gametest-probe`, `sources` JAR, source ZIP,
sentinels or validation logs in the production `mods` directory.

## Validation

- Clean Gradle build passed.
- Formal JAR inspection found no GameTest, crash-probe, fault-injection or sentinel classes.
- Final NeoForge suite passed 29/29.
- External hard-kill/restart matrix passed 4/4: goods extracted, inventory applied/before payment
  insert, partial payment insert, and COMMITTED fsync.
- Production under `E:\Minecarft\B-03-SCEX-LegacyGenesis` was not modified by development.

## Maintenance deployment procedure

1. Stop the production server and prevent player joins. Back up the complete instance, including
   the world, `mods`, configs and `scex_viscriptshop_ae2_transactions` if it exists.
2. Check the transaction directory before changing versions. If a v0.1.2 format-2 `.nbt` is
   pending, do not install v0.1.3: recover/checkpoint it with v0.1.2 in a closed maintenance window
   first. Never delete a pending WAL to bypass recovery.
3. Remove only the older SCEX ViScriptShop AE2 bridge JAR and copy the exact deployable artifact.
   Keep ViScriptShop/AE2/NeoForge at the pinned versions above.
4. Start without players, confirm dependency resolution, terminal bindings and absence of `Pending
   WAL`/compare-and-set errors. Perform one controlled shop purchase and verify player items, ME
   contents, stock and XP before reopening.
5. Record the installed filename and SHA-256 in the maintenance log and retain the pre-deploy
   backup through the acceptance window.

## Rollback

Stop the server first. Downgrade only when the format-3 transaction directory has no pending `.nbt`
files. If any format-3 journal remains, keep v0.1.3 installed for diagnosis/recovery or restore the
entire pre-deploy backup; do not combine an older JAR with a newer pending journal. Restore the old
bridge JAR and configs/world from the same backup set, then repeat the closed-server smoke check.
