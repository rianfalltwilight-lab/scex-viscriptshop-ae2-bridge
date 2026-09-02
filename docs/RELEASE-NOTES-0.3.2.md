# SCEX ViScriptShop AE2 Bridge 0.3.2

This release includes all v0.3.1 connector textures and slows the signal animation to one tenth of
its previous speed.

- the 12-frame animation now holds each frame for 20 game ticks, producing an approximately
  12-second full cycle at 20 TPS;
- per-pixel frame interpolation is enabled for a softer crossfade without spatially blurring or
  resampling the supplied 16x16 pixel art;
- the supplied base, signal and preview PNG files remain byte-identical to v0.3.1;
- the six-face layered block model and 0.01-unit anti-z-fighting offsets remain unchanged;
- placed blocks, inventory rendering and held-item rendering all continue to share the same block
  model and animation metadata.

The mod remains required on both the server and clients. Production publication still requires a
real Minecraft client visual smoke test, backup and coordinated server/client update.
