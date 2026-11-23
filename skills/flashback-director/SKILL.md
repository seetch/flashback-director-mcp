---
name: flashback-director
description: Directing pipeline for creating cinematic YouTube-style B-roll, camera paths, and showcase cuts in Minecraft Flashback using the director_* MCP tools.
---

# Directing Cinematic B-Roll in Flashback

Use this skill whenever the user asks to create cinematic camera paths, B-roll sequences, highlight cuts, or rendered clips from a Minecraft Flashback replay.

## Required Directing Pipeline

1. **Inspect State and Open Replay**
   - Call `director_status`. If no replay is open, list available replays with `director_list_replays` and open the target replay with `director_open_replay`.
   - Check the `fullscreen` flag. If Minecraft is in fullscreen mode, immediately ask the user to press `F11` (windowed mode is required for screenshots and video export).
   - Create a dedicated scene with `director_manage_scene(action: "add")` when working on a new edit so existing timelines are preserved.

2. **Scout Strong Moments (Never Guess Blindly)**
   - If the user did not provide exact timestamps, scan the replay with `director_scan_highlights` (`step_ticks: 15..20`).
   - Pick 2-4 strong moments with distinct visual roles: an establishing landscape or build, dynamic motion/action, a hero focal moment, and a clean outro.

3. **Visual Scouting, Lighting, and Entity Cleanup**
   - Seek to candidate ticks (`director_seek`), inspect nearby entities (`director_list_entities(players_only: false)`), and capture 1-2 scouting frames (`director_screenshot`).
   - Evaluate lighting, time of day, sightlines, and obstructions (trees, walls, ceilings). Adjust atmosphere with `director_set_visuals` (e.g. golden hour `overrideTimeOfDay: 12500` or crisp morning `23200`, fog/sky colors, weather, aspect ratio) and disable HUD elements (`showHotbar: false`, `showChat: false`, `showScoreboard: false`).
   - Manage entities, skins, nametags, and server holograms with `director_configure_entities` (or `director_set_visuals`):
     - Hide all unwanted NPCs and hologram armor stands (both models and nametags) while keeping specific heroes visible: `keep_entities_only_for: ["HeroName"]` (`keep_entities_scope: "all"` or `"players"`).
     - Override a player's skin by Mojang username or UUID: `skin_overrides: {"HeroName": "SkinUsername"}`, or from a local PNG via `skin_file_overrides`.
     - Customize display names, glowing state, capes, team prefixes/suffixes, below-name text, equipment slots, player model parts, voice muting, and audio source entity (`name_overrides`, `glowing_overrides`, `hide_cape`, `hidden_equipment`, `hidden_model_parts`, `mute_voice`, `audio_source_entity`).
     - Filter entity or particle types globally via `filtered_entities` and `filtered_particles`.
     - Hide only nametags via `renderNametags: false` or `keep_nametags_only_for: ["Player1", "Player2"]`.

4. **Build Cinematic Shots Across Flashback Tracks**
   - For fast, collision-safe B-roll use `director_create_shot` with `avoid_collisions: true`:
     - `flyover`: wide establishing sweep over the scene (FOV 46-52).
     - `orbit`: smooth 90-150 degree arc around a build or player (FOV 44-48).
     - `dolly_reveal`: push-in or pull-back reveal with subtle FOV tightening.
     - `follow_parallax`: dynamic lateral tracking with gentle slow-motion (`slow_speed: 0.5..0.6`).
     - `impact_freeze`: action accent with speed-ramp, optional `freeze_ticks`, and punchy camera shake.
   - For bespoke sequences, validate waypoints and line of sight with `director_check_path` first, then write keyframes via `director_set_keyframes` across any of Flashback's 11 track types (`CAMERA`, `FOV`, `SPEED`, `TIMELAPSE`, `TIME_OF_DAY`, `FREEZE`, `CAMERA_SHAKE`, `CAMERA_ORBIT`, `TRACK_ENTITY`, `AUDIO`, `BLOCK_OVERRIDE`).
   - Avoid flat default FOV 70-90: keep cinematic framing in the `38-55` range (except deliberate wide/vertigo accents) and use smooth `HERMITE` or `EASE_IN_OUT` curves.
   - Vary shot scale and pacing: typical B-roll cuts last `2.5-5` seconds (`50-100` ticks).

5. **Mandatory Visual Verification**
   - Capture `director_screenshot(apply_keyframes: true)` at the **start**, **middle**, and **end** of every shot.
   - Inspect every frame critically for block/leaf clipping, cropped subjects, clutter, or weak lighting. Adjust keyframes and re-verify until every shot is clean.

6. **Video Export (Only When Requested)**
   - Set exact timeline boundaries with `director_set_export_range`.
   - Start rendering via `director_export_video` (`wait: false`, e.g. `1920x1080` or `2560x1440`, `60` fps, `ssaa: true`, `no_gui: true`, high bitrate) and monitor progress via `director_status` until finished.
