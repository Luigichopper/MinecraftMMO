# TESTING CHECKLIST — XIV MMO (`xivmmo`)

## 1. Automated JUnit 5 Verification (`./gradlew test`)

Run `./gradlew test` to execute all unit test suites:
- [`MmoCameraAndConfigTest`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/test/java/net/xivmmo/MmoCameraAndConfigTest.java) — Camera yaw/pitch clamping, zoom interpolation, and per-world config overrides.
- [`TargetingAndAbilityEngineTest`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/test/java/net/xivmmo/TargetingAndAbilityEngineTest.java) — 3D matrix unprojection, ray-vs-AABB slab intersection, GCD/oGCD state machine, 0.5s input queue window, movement cast cancellation & slidecast tolerance, multi-charge cooldown groups, combos, and 140° front-cone / Rear / Flank positional math.
- [`Milestone5And6Test`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/test/java/net/xivmmo/Milestone5And6Test.java) — Status snapshotting, fixed 60-tick (`3.0s`) DoT/HoT server tick cycle, potency + armor damage formula, Enmity table (`Provoke`, `Shirk`, Tank Stance `10x`), and `CIRCLE`/`CONE`/`LINE`/`DONUT` telegraph geometry.
- [`Milestone7And8Test`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/test/java/net/xivmmo/Milestone7And8Test.java) — HUD widget layout persistence/scaling (`ACTION_HOTBAR`, `PLAYER_VITALS`, `CAST_BAR`, `TARGET_FRAME`, `FOCUS_TARGET`, `PARTY_LIST`, `COMBAT_TEXT`), plus JSON Codec validation of all 4 Jobs, 18 Abilities, and 6 Statuses.
- [`Milestone9And10Test`](file:///c:/Users/Luigi/Documents/MinecraftMMO/src/test/java/net/xivmmo/Milestone9And10Test.java) — Health & Hunger (`PLAYER_VITALS`) non-overlapping layout math, `MmoDebugState` packet ring buffer & server tick profiler, 50-entity tick performance budget benchmark (`< 500 µs`), and Modpack (`pack.toml`, `index.toml`, `modrinth.index.json`) integrity checks.

---

## 2. In-Game QA Checklist (`./gradlew runClient`)

### A. Free-Cursor Controls, Camera & Screen Transitions
- [x] **Free Cursor**: Cursor is released and visible during MMO gameplay (`F12` toggles between MMO Mode and Vanilla Mode).
- [x] **RMB Orbit**: Holding Right-Click orbits the 3rd-person camera smoothly, aligns character facing to the camera, and restores the cursor to its pre-orbit screen coordinates on release.
- [x] **LMB+RMB Move Forward**: Holding both mouse buttons walks forward along the camera heading.
- [x] **Scroll Wheel Zoom**: Scrolling zooms the camera (`1.5m`–`16.0m`) with block collision (`ClipContext.Block.VISUAL`) and does **not** cycle the vanilla hotbar.
- [x] **Build Mode (`B`) & `Alt` Modifier**: Pressing `B` or holding `Left Alt` temporarily locks the mouse, restores the vanilla crosshair and 9-slot item hotbar UI + mechanics, and allows normal block mining/placing.
- [x] **Screen Transitions**: Opening Inventory (`E`), Chat (`T`), Pause Menu (`Esc`), or HUD Layout Editor (`H`) and closing them restores the exact cursor grab/release state.

### B. Vanilla Hotbar Mechanics Overhaul & Health/Hunger Bar Positioning
- [x] **Vanilla Hotbar Mechanics Disabled in MMO Mode**: Pressing `1`–`8` triggers MMO abilities without changing `player.getInventory().getSelectedSlot()`. Pressing `9`, `Q` (Drop), `F` (Swap Offhand), Middle-Click (Pick Block), or LMB/RMB does **not** drop, swap, or swing/use the hidden vanilla hotbar item.
- [x] **Health & Hunger Bars (`PLAYER_VITALS`)**: Vanilla Health, Hunger, Armor, Air, and Mount Health bars are cleanly positioned above the MMO Action Hotbar (`normY = 0.78`), never overlap the MMO Action Hotbar or Cast Bar, and can be dragged, scaled (`50%`–`200%`), or hidden in the HUD Layout Editor (`H`).

### C. Targeting, Combat Loop, Jobs & Boss Arenas
- [x] **Hard, Soft, Focus & Target-of-Target**: Hovering an entity shows a yellow Soft Target ring; Left-Clicking or pressing `Tab` selects a red Hard Target ring with a directional Rear/Flank/Front chevron; pressing `F` pins the Focus Target frame.
- [x] **4 Playable Jobs (`R` or `/mmo job <name>`)**:
  - `vanguard` (Tank): 1-2-3 combo, `Iron Bastion` (`10x` enmity stance), `Sentinel Ward` (`-20%` damage taken), `Provoke Roar`, `Shirk Step`.
  - `hierophant` (Healer): `Vital Mend` (hard cast heal), `Zephyr Pulse` (HoT), `Radiant Halo` (party AoE heal + HoT), `Solaris Bolt` (DoT).
  - `lancer` (Melee DPS): 1-2-3 combo with `REAR` (`Chaos Thrust`) and `FLANK` (`Wheeling Fang`) positional bonuses + `Skyward Dive` oGCD weave.
  - `arcanist` (Ranged Caster): `Astral Flare` (`2.0s` hard cast with `0.5s` slidecast window), `StarfallNova` (ground-targeted AoE with placement reticle), `Aether Surge` (`+15%` damage buff).
- [x] **Boss Arenas (`/mmo arena sunforge` & `/mmo arena void_sanctum`)**: Spawns bosses that cycle through `CIRCLE`, `CONE`, `LINE`, and `DONUT` ground telegraphs with fill-up animations and potency damage resolution.

### D. Sodium, Iris & Shaderpack Compatibility
- [x] **Sodium + Iris Shaders OFF**: Target rings, placement reticles, and all 4 telegraph shapes render cleanly.
- [x] **Sodium + Iris Shaders ON (`photon_v1.3b.zip` / `photon_v1.4.zip` & `ComplementaryReimagined_r5.9.3.zip`)**: Telegraphs and target rings render via `RenderTypes.entityTranslucent(TELEGRAPH_WHITE_TEXTURE, false)` (`DefaultVertexFormat.ENTITY`) + `RenderTypes.lines()` with zero `Missing program` log warnings.
