# Verification plan and v0.1.1 evidence

Automated/build checks (2026-09-01, Java 21 / NeoForge 21.1.248):

1. `gradlew runGameTestServer`: **6/6 required tests passed** using a real AE2 multipart
   `ItemTerminalPart`, grid, drive, storage cell and the authoritative
   `BuyMerchantPayload.buyMerchant` entry point.
2. `gradlew clean build` and verify JAR metadata/mixin resources.
3. Static dependency hash check against `UPSTREAM-AUDIT.md`.

Covered by the non-release `gametest-probe` JAR:

1. Successful item trade conserves player inventory + ME contents, decrements stock once,
   preserves money, grants XP once, and emits exactly one `BuySuccess` event.
2. Insufficient player payment emits `BuyFail` with no inventory/ME mutation.
3. Full ME cell rejects the payment before mutation and emits `BuyFail`.
4. Removing the real ME drive between simulation and the commit barrier is caught by the
   second simulation; inventory, stock, money/XP and success events remain unchanged.
5. Exact data-component matching rejects a plain variant and deposits the exact named variant.
6. Two players plus a duplicate click contend for the final stock in one server tick; one
   succeeds, two fail, and exactly one payment/goods pair moves. The event listeners use the
   public NeoForge `BuySuccess`/`BuyFail` surface consumed by JEI/FTB integrations.
7. NeoForge `FakePlayer` provides a connectionless player: attachment reads and isolated
   shop notification RPC cannot abort or reverse an already committed transaction.

Remaining isolated-server acceptance boundaries (not claimed by the probe):

1. Partial-component match modes beyond exact components.
2. Chunk-unloaded/wrong-side/protection-provider combinations.
3. Actual JEI and FTB Quests mod listeners in the production dependency set (the public event
   contract is tested, but those optional mods are not bundled in this harness).
4. Hard JVM kill at instruction-level transaction boundaries and subsequent server restart.
5. Command rewards, because arbitrary command side effects are not generally reversible.

The production instance under `E:\Minecarft\B-03-SCEX-LegacyGenesis` must remain unchanged until these scenarios pass and an exact deployment manifest is explicitly approved.
