# MODLIST — XIV MMO Modpack (`0.1.0+26.2`)

All mods and shaderpacks below are verified against **Minecraft `26.2`** on Modrinth (`fabric-loader 0.19.5`) and pinned with exact SHA-512 hashes in `modpack/mods/*.pw.toml`, `modpack/shaderpacks/*.pw.toml`, and `modpack/modrinth.index.json`.

---

## 1. Included Mods (`modpack/mods/`)

| Mod Name | Slug / Project ID | Pinned 26.2 Version | Filename | Side | License | Purpose in Pack |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **XIV MMO Overhaul** | `xivmmo` (In-House) | `0.1.0+26.2` | `xivmmo-0.1.0+26.2.jar` | Both | All-Rights-Reserved | Core FFXIV-inspired combat, camera, free-cursor targeting, enmity, telegraphs & rearrangeable HUD mod |
| **Fabric API** | `fabric-api` (`P7dR8mSH`) | `0.161.0+26.2` | `fabric-api-0.161.0+26.2.jar` | Both | Apache-2.0 | Official Fabric rendering (`HudElementRegistry`, `LevelRenderEvents`), networking, lifecycle, and command hooks |
| **Sodium** | `sodium` (`AANobbMI`) | `mc26.2-0.9.2-fabric` | `sodium-fabric-0.9.2+mc26.2.jar` | Client | Polyform-Shield-1.0.0 | High-performance chunk & world rendering engine |
| **Iris Shaders** | `iris` (`YL57xq9U`) | `1.11.4+26.2-fabric` | `iris-fabric-1.11.4+mc26.2.jar` | Client | LGPL-3.0-only | Shaderpack pipeline loader; verified with `WorldTelegraphRenderer`'s `entityTranslucent` + `lines` pipelines |
| **Lithium** | `lithium` (`gvQqBUqZ`) | `mc26.2-0.25.3-fabric` | `lithium-fabric-0.25.3+mc26.2.jar` | Both | LGPL-3.0-only | Server & physics tick optimization |
| **FerriteCore** | `ferrite-core` (`uXXizFIs`) | `9.0.0-fabric` | `ferritecore-9.0.0-fabric.jar` | Both | MIT | Blockstate & model memory usage reduction |
| **ImmediatelyFast** | `immediatelyfast` (`5ZwdcRci`) | `1.16.5+26.2-fabric` | `ImmediatelyFast-Fabric-1.16.5+26.2.jar` | Client | LGPL-3.0-or-later | Immediate-mode HUD, text, and entity batch rendering optimization |
| **EntityCulling** | `entityculling` (`NNAgCjsB`) | `1.11.3` | `entityculling-fabric-1.11.3-mc26.2.jar` | Client | tr7zw-Protective | Async raytraced occlusion culling for non-visible entities |
| **YetAnotherConfigLib (YACL)** | `yacl` (`1eAoo2KR`) | `3.9.7+26.2-fabric` | `yet_another_config_lib_v3-3.9.7+26.2-fabric.jar` | Both | LGPL-3.0-or-later | Powers the categorized `YaclConfigScreenFactory` settings UI |
| **Cloth Config API** | `cloth-config` (`9s6osm5g`) | `26.2.155+fabric` | `cloth-config-26.2.155.jar` | Both | LGPL-3.0-only | Configuration dependency required by REI and ecosystem mods |
| **Architectury API** | `architectury-api` (`lhGA9TYQ`) | `21.1.12+fabric` | `architectury-fabric-21.1.12.jar` | Both | LGPL-3.0-only | Cross-platform library required by Roughly Enough Items (REI) |
| **GeckoLib** | `geckolib` (`8BmcQJ2H`) | `5.5.5` | `geckolib-fabric-26.2-5.5.5.jar` | Both | MIT | Keyframe model & entity animation library for 26.2 |
| **oωo (owo-lib)** | `owo-lib` (`ccKDOlHs`) | `0.13.1+26.2` | `owo-lib-0.13.1+26.2.jar` | Both | MIT | UI & utility library for Fabric 26.2 |
| **Cardinal Components API** | `cardinal-components-api` (`K01OU20C`) | `8.0.1` | `cardinal-components-api-8.0.1.jar` | Both | MIT | Entity/world data component attachment library for 26.2 |
| **Mod Menu** | `modmenu` (`mOgUt4GM`) | `20.0.3` | `modmenu-20.0.3.jar` | Client | MIT | Mod list screen integration (launches `XIV MMO` config & HUD Layout Editor) |
| **Jade** | `jade` (`nvQzSEkH`) | `26.2.11+fabric` | `Jade-mc26.2-Fabric-26.2.11.jar` | Both | CC-BY-NC-SA-4.0 | World block & entity inspection tooltip HUD |
| **Roughly Enough Items (REI)** | `rei` (`nfn13YXA`) | `26.2.820+fabric` | `RoughlyEnoughItems-26.2.820.jar` | Both | MIT | Recipe & item viewer for 26.2 |
| **Xaero's Minimap** | `xaeros-minimap` (`1bokaNcj`) | `fabric-26.2-26.6.0` | `xaerominimap-fabric-26.2-26.6.0.jar` | Client | All-Rights-Reserved | Minimap & waypoint overlay |
| **spark** | `spark` (`l6YH9Als`) | `1.10.187-fabric` | `spark-1.10.187-fabric.jar` | Both | GPL-3.0-only | CPU/memory/tick profiler for QA & performance verification |

---

## 2. Included Shaderpacks (`modpack/shaderpacks/` & `run/shaderpacks/`)

| Shaderpack | Slug / Project ID | Pinned Version | Filename | License | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Photon Shader** | `photon-shader` (`lLqFfGNs`) | `v1.4` (and `v1.3b` in dev) | `photon_v1.4.zip` | Custom (`LicenseRef`) | Tested in `runClient` with `WorldTelegraphRenderer`; renders circles, cones, lines, donuts, and target rings cleanly |
| **Complementary Reimagined** | `complementary-reimagined` (`HVnmMxH1`) | `r5.9.3` | `ComplementaryReimagined_r5.9.3.zip` | Custom (`LicenseRef-Custom`) | Second verified 26.2 shaderpack for Iris compatibility testing |

---

## 3. BLOCKED Mods & Fallbacks (Per Section 3 & 7 Rules)

| Candidate Mod | Modrinth ID | Status on 26.2 | Reason | Fallback Implemented |
| :--- | :--- | :--- | :--- | :--- |
| **playerAnimator** | `gedNE4y2` | **BLOCKED** | Latest release on Modrinth is for Minecraft `1.21.1`; no `26.2` build exists. | Implemented procedural combat swing, cast-bar progress, and particle/telegraph visual feedback directly in `ServerCombatEngine` & `ClientCombatPredictor`. |
| **EMI** | `fRiHVvU7` | **BLOCKED** | Latest release on Modrinth is for Minecraft `1.21.1`; no `26.2` build exists. | Integrated **Roughly Enough Items (REI)** (`26.2.820+fabric`) + `architectury-api` (`21.1.12+fabric`) + `cloth-config` (`26.2.155+fabric`), which all have native `26.2` builds. |
| **Third-Party Camera Mods (e.g. Better Third Person / Shoulder Surfing)** | Various | **INTENTIONALLY EXCLUDED** | Directly conflict with FFXIV's free-cursor LMB selection + RMB orbit + LMB+RMB movement rules. | Built native `MmoCameraController` + `MouseHandlerMixin` + `CameraMixin` in-house. |
