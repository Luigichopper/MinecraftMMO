# KNOWN ISSUES & ENGINEERING NOTES — XIV MMO (`xivmmo`)

## 1. Resolved Issues

### 1.1 Iris Shaderpack Telegraph & Target Ring Visibility (Resolved)
- **Symptom**: With Iris shaderpacks active (e.g., `photon_v1.3b.zip`), world-space ground telegraphs and target rings did not render, and `latest.log` reported `[Iris] Missing program minecraft:pipeline/debug_quads in override list`.
- **Root Cause**:
  1. `IrisPipelines` in `iris-fabric-1.11.4+mc26.2.jar` does not map `RenderPipelines.DEBUG_QUADS` in `coreShaderMap`.
  2. PBR shaderpacks compute a TBN matrix (`get_tbn_matrix(Normal)`) using `gl_Normal` and `at_tangent` (`NormalHelper.computeTangentSmooth`) and sample `gtexture`. Degenerate triangle-as-quad vertices (where vertex 0 and vertex 3 shared the same center point when `innerR == 0`) produced zero-length tangents.
- **Fix**: Replaced `RenderTypes.debugQuads()` in [`WorldTelegraphRenderer`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/client/java/net/xivmmo/client/render/WorldTelegraphRenderer.java) with `RenderTypes.entityTranslucent(TELEGRAPH_WHITE_TEXTURE, false)` (`DefaultVertexFormat.ENTITY`: `Position`, `Color`, `UV0`, `Overlay`, `Lightmap` `0x00F000F0`, upward `Normal` `(0,1,0)`, and non-degenerate `safeInnerR = Math.max(0.002F, innerR)`) paired with `RenderTypes.lines()` perimeter outlines. Verified in `runClient` with `photon_v1.3b.zip` enabled and zero shader warnings.

### 1.2 Vanilla Health/Hunger Bar Overlap & Hotbar Mechanics in MMO Mode (Resolved)
- **Symptom**: Hiding the vanilla 9-slot item hotbar left the vanilla Health & Hunger bars at their hardcoded screen offset (`guiHeight - 39`), overlapping the MMO Action Hotbar, and pressing `1`–`8`, `Q`, or `F` still interacted with the hidden vanilla hotbar items.
- **Fix**:
  1. Wrapped `VanillaHudElements.HEALTH_BAR`, `FOOD_BAR`, `ARMOR_BAR`, `AIR_BAR`, and `MOUNT_HEALTH` in [`MmoHudManager`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/client/java/net/xivmmo/client/hud/MmoHudManager.java) to transform and scale to the rearrangeable `HudWidgetId.PLAYER_VITALS` widget (`normY = 0.78`, cleanly above `ACTION_HOTBAR` at `normY = 0.87`) with automatic vertical overlap prevention.
  2. Added `xivmmo$disableVanillaHotbarMechanicsInMmoMode` in [`MinecraftInputMixin`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/client/java/net/xivmmo/mixin/client/MinecraftInputMixin.java) and hotbar slot locking in [`MmoInputHandler`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/client/java/net/xivmmo/client/input/MmoInputHandler.java) so `1`–`9` hotbar slot switching, `Q` item dropping, `F` offhand swapping, middle-click block picking, and held-item swings/uses are completely disabled during MMO Combat Mode (and automatically re-enabled in Build Mode `B` or while holding `Alt`).

---

## 2. Upstream 26.2 Mod Ecosystem Limitations

1. **`playerAnimator` (`gedNE4y2`) — Blocked on 26.2**:
   - No `26.2` release exists on Modrinth (latest is `1.21.1`). Custom ability animations currently use our built-in main-hand swing + cast bar + world telegraph effects.
2. **`EMI` (`fRiHVvU7`) — Blocked on 26.2**:
   - No `26.2` release exists on Modrinth (latest is `1.21.1`). The modpack ships **Roughly Enough Items (REI)** (`26.2.820+fabric`) instead.
