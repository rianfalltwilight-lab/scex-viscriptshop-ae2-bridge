# Verification plan and current evidence

Automated/build checks:

1. `gradlew clean compileJava` on Java 21.
2. `gradlew clean build` and verify JAR metadata/mixin resources.
3. Static dependency hash check against `UPSTREAM-AUDIT.md`.

Required isolated integration scenarios before production approval:

1. Buy item with adequate/insufficient ME stock.
2. Sell and item-for-item with adequate/full ME capacity.
3. Matching rules with exact components and configured partial-component modes.
4. Terminal offline, chunk unloaded, removed part, wrong side, wrong owner, protected position.
5. Two players submit the last available stock in the same tick; exactly one succeeds.
6. Disconnect terminal between simulation and modulation; no gain or payment loss.
7. Verify ViScriptShop stock, currency, XP, commands, stage flags, JEI, and FTB Quests events.
8. Kill/restart only the isolated test server around transactions and inspect world consistency.

The production instance under `E:\Minecarft\B-03-SCEX-LegacyGenesis` must remain unchanged until these scenarios pass and an exact deployment manifest is explicitly approved.
