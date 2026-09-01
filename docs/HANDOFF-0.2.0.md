# SCEX ViScriptShop AE2 Bridge 0.2.0 handoff

## Deployment scope

The deployable artifact, size, SHA-256 and source commit are recorded in the packaged
`SHA256SUMS.txt` and release manifest. Only the main versioned JAR belongs in `mods`; never deploy
the sources JAR, source ZIP, GameTest logs, sentinels or crash-probe files.

## Maintenance procedure

1. Stop the server and prevent player joins. Back up the complete instance, world, `mods` and
   `scex_viscriptshop_ae2_transactions` if present.
2. Verify that no pending older-format `.nbt` file exists. Never delete one to bypass recovery.
3. Replace only the older SCEX bridge JAR, keeping the pinned runtime versions unchanged, and add
   the exact same 0.2.0 JAR to the client update.
4. Start closed to players. Confirm dependency resolution and absence of `Pending WAL` errors.
5. Place a connector on a test player's powered AE network. Verify an item sale removes the price
   from ME and a purchase deposits the reward into ME; also verify native backpack fallback after
   the connector is unavailable.
6. Record the installed filename and SHA-256, then retain the backup through the acceptance window.

## Rollback

Stop the server first. Downgrade only when the format-4 transaction directory has no pending `.nbt`
files. Otherwise diagnose with 0.2.0 or restore the complete pre-deploy backup. Do not combine an
older JAR with a newer pending journal.

Production and public client publishing require a separate explicit approval to `SCEX-长期维护`.
