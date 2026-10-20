# XIV MMO — FFXIV-Inspired Combat, Camera, Movement & HUD Overhaul (Fabric 26.2)

An FFXIV-inspired action-MMO combat, free-cursor camera, targeting, telegraph, and HUD overhaul mod (`xivmmo`) plus a curated 20-project Modrinth/packwiz modpack for **Minecraft 26.2 (Fabric)**.

---

## 1. Toolchain & Requirements

- **Minecraft**: `26.2` (official Mojang unobfuscated names)
- **Java**: `25` (`Eclipse Adoptium JDK 25.0.2+10`)
- **Gradle**: `9.4.1`
- **Fabric Loom**: `1.15.5` (`id 'net.fabricmc.fabric-loom'`)
- **Fabric Loader**: `0.19.5`
- **Fabric API**: `0.161.0+26.2`

---

## 2. Build, Test & Run Commands

```powershell
# Run all unit tests (JUnit 5 pure math, combat engine, status/enmity, telegraphs, HUD & modpack checks)
./gradlew test

# Build the mod jar AND export the Modrinth .mrpack archive
./gradlew build

# Launch the development client (with Sodium, Iris, YACL, Mod Menu, and shaderpacks in run/)
./gradlew runClient

# Launch a headless smoke-test client that verifies initialization and exits cleanly on the Title Screen
./gradlew runClient -Dxivmmo.autoQuitOnTitleScreen=true
```

### Build Outputs
- **Mod Jar**: `build/libs/xivmmo-0.1.0+26.2.jar`
- **Sources Jar**: `build/libs/xivmmo-0.1.0+26.2-sources.jar`
- **Modrinth Modpack Archive**: `build/modpack/xivmmo-modpack-0.1.0+26.2.mrpack`
- **Packwiz Metadata**: `modpack/pack.toml`, `modpack/index.toml`, `modpack/mods/*.pw.toml`, `modpack/shaderpacks/*.pw.toml`

---

## 3. Controls & Keybinds (All Rebindable in Vanilla Controls)

| Input / Key | Default Binding | Action in MMO Mode |
| :--- | :--- | :--- |
| **Mouse Cursor** | Free Cursor | Visible cursor for clicking 3D targets, HUD action slots, and Party List rows |
| **Left Click (LMB)** | Mouse Button 1 | Select Hard Target under cursor (or click MMO Action Hotbar / Party List) |
| **Right Click + Drag (RMB)** | Mouse Button 2 | Orbit 3rd-person camera & align character facing to camera |
| **LMB + RMB Held** | Mouse 1 + Mouse 2 | Move forward along camera heading |
| **Scroll Wheel** | Mouse Scroll | Smooth camera zoom (`1.5m` – `16.0m`, does **not** cycle vanilla hotbar) |
| **WASD** | Movement Keys | Camera-relative movement & strafing (auto-faces movement direction unless casting) |
| **`1` – `9`, `0`, `-`, `=`** | Hotbar Keys `1`–`=` | Trigger 12-slot FFXIV Action Hotbar slots `1`–`12` (vanilla hotbar slot switching is disabled & locked) |
| **`Enter` / `T` / `/` or Click Chat Bar** | `Enter` / `T` / `/` | Focus **Bottom-Left FFXIV Chat Input Bar** (with `General`/`Battle`/`Event`/`+` filter tabs, `/s`, `/p`, `/sh`, `/y`, `/fc`, `/t`, `/r` channels, & `◖◗` Auto-Translate Dictionary) |
| **`I` or `E`** | `I` / `E` | Open **FFXIV 140-Slot Inventory Window** (`4 x 35` `5x7` quadrants + `Key Items & Crystals` tab + Auto-Sort) |
| **`C`** | `C` | Open **FFXIV Character Window** (Attributes, Armoury Gear Slots, Soul Crystal, Job Level & EXP) |
| **`P`** | `P` | Open **FFXIV Actions & Traits Window** (Job Actions, Role Actions, Traits, click to assign to hotbar) |
| **`Tab` / `Shift+Tab`** | `Tab` | Cycle forward/reverse through hostile targets in camera cone |
| **`F`** | `F` | Set / clear Focus Target on current target |
| **`Escape`** | `Esc` | Clear Hard Target first; opens Pause Menu if no target is selected |
| **`H`** | `H` | Open Interactive HUD Layout Editor (Drag, Scroll-Scale `50%`–`200%`, Right-Click Hide) |
| **`R`** | `R` | Cycle active Job (`Vanguard` Tank $\rightarrow$ `Hierophant` Healer $\rightarrow$ `Lancer` Melee DPS $\rightarrow$ `Arcanist` Ranged Caster) |
| **`F9`** | `F9` | Toggle MMO Action Hotbar visibility |
| **`B`** | `B` | Toggle **Build Mode** (restores locked mouse, crosshair, and vanilla 9-slot item hotbar UI & mechanics) |
| **Hold `Alt`** | `Left Alt` | Momentary **Block Interact Modifier** (mine/place blocks and use vanilla hotbar while held) |
| **`F12`** | `F12` | Master toggle between **MMO Mode** and **Vanilla Mode** |

---

## 4. In-Game `/mmo` Command Reference

- `/mmo inventory` — Open the FFXIV 140-Slot Inventory window (`FfxivInventoryScreen`).
- `/mmo character` — Open the FFXIV Character Attributes & Armoury window (`FfxivCharacterScreen`).
- `/mmo actions` — Open the FFXIV Actions & Traits window (`FfxivActionsScreen`).
- `/mmo chat <general|battle|event|all|clear|timestamps>` — Switch FFXIV Chat Log filter tabs, toggle `[HH:mm]` timestamps, or clear the chat log.
- `/mmo level <1..50>` — Set the current Job's level (`1..50`) and sync attributes & unlocked actions.
- `/mmo exp <amount>` — Award EXP to the active Job (applies Armoury Bonus if below your highest Job level).
- `/mmo hud` — Open the interactive drag-and-drop HUD Layout Editor (`HudLayoutScreen`).
- `/mmo config` — Open the YetAnotherConfigLib (YACL) configuration screen.
- `/mmo job <vanguard|hierophant|lancer|arcanist>` — Switch playable Job and load its 12-ability kit.
- `/mmo arena <sunforge|void_sanctum>` — Spawn a Boss Arena encounter with timed multi-shape AoE telegraphs.
- `/mmo cast <ability_id>` — Trigger an ability by ID.
- `/mmo telegraph <circle|cone|line|donut>` — Spawn a 3.0s test telegraph on your target or self.
- `/mmo reticle` — Toggle the 3D Ground AoE placement reticle.
- `/mmo hotbar mmo` — Toggle the MMO Action Hotbar on/off.
- `/mmo hotbar vanilla` — Toggle the Vanilla Item Hotbar (both UI and mechanics) on/off in MMO Mode.
- `/mmo mode <mmo|vanilla|build>` — Switch control modes.
- `/mmo worldoverride <on|off|clear>` — Configure per-world/per-server MMO mode overrides.
- `/mmo debug <overlay|ray|packets|state|enmity|perf>` — Toggle diagnostic overlays, 3D ray/hitbox wireframes, packet tracing, and performance profiling.
