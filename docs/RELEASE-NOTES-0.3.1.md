# SCEX ViScriptShop AE2 Bridge 0.3.1

This release includes the v0.3.0 direct-ME currency transaction feature and replaces the temporary
borrowed AE2 interface texture with the three supplied connector assets.

- `me_shop_connector_base.png` is the unchanged 16x16 opaque body texture;
- `me_shop_connector_signal.png` is the unchanged 16x192 transparent 12-frame signal animation;
- `me_shop_connector_preview.png` is the unchanged 16x16 first-frame composite used for particles;
- the block model layers the animated signal just outside every base face to avoid z-fighting;
- the animation advances every two game ticks with interpolation disabled, preserving the original
  pixel art exactly;
- the model uses the cutout render type and no longer depends on `ae2:block/interface` for its look.

The mod remains required on both the server and clients. Production publication still requires the
normal client visual smoke test, backup and coordinated server/client update.
