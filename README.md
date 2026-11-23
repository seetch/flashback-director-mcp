# Flashback Director MCP

**English** · [Русский](README.ru.md)

Local Fabric addon for **Minecraft 26.2 / 26.3** and **Flashback** with a zero-dependency Node.js `stdio` MCP server. Lets local AI clients scout replay highlights, check camera paths against world blocks, create editable Flashback keyframes, inspect frames via screenshots, and trigger video export.

![Spawn reveal start frame](docs/screenshots/shot-start.png)
![Spawn reveal end frame](docs/screenshots/shot-reveal.png)

*Frames generated and verified inside Minecraft 26.2 (`Flashback 0.43.6`) via `director_check_path`, `director_set_keyframes`, and `director_screenshot`.*

## Features

- **22 MCP tools** covering replays, playback, scenes, tracks, keyframes (`CAMERA`, `FOV`, `SPEED`, `TIMELAPSE`, `TIME_OF_DAY`, `FREEZE`, `CAMERA_SHAKE`, `CAMERA_ORBIT`, `TRACK_ENTITY`), visuals, undo/redo, and video export.
- **Collision checking (`director_check_path`)**: raycasts and block-clearance checks against the loaded world so camera splines avoid walls, ceilings, and foliage.
- **Highlight scanner (`director_scan_highlights`)**: scans a tick window to locate speed spikes, jumps/falls, damage events, and entity clusters.
- **Shot presets (`director_create_shot`)**: generates editable Flashback keyframes for `flyover`, `orbit`, `dolly_reveal`, `follow_parallax`, and `impact_freeze` with automatic obstacle offset search.
- **Vulkan-safe frame previews (`director_screenshot`)**: captures PNG frames through Minecraft's Blaze3D `Screenshot` pipeline (no raw OpenGL calls) and returns them directly to multimodal AI clients.
- **Zero npm dependencies**: single-file Node.js bridge (`mcp-server/flashback-director-mcp.mjs`) communicating over `127.0.0.1` with per-session 256-bit Bearer authentication.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | `26.2` or `26.3` (Fabric) |
| Java | `25+` |
| Fabric Loader | `0.19.3+` (26.2) / `0.19.5+` (26.3) |
| Fabric API | Matching Minecraft version |
| Flashback | `0.43.3+` (26.2) / `0.43.4+` (26.3) |
| Node.js | `18+` |

## Build from Source

Flashback is proprietary and is **not** included in this repository.

1. Place your local Flashback JAR in `libs/` (ignored by Git):
   - `libs/Flashback-0.43.6-for-MC26.2.jar` for Minecraft `26.2`
   - `libs/Flashback-for-MC26.3.jar` for Minecraft `26.3`
2. Build with JDK 25:
   ```powershell
   .\gradlew.bat :mc-26.2:build
   # For 26.3 (once libs/Flashback-for-MC26.3.jar is present):
   .\gradlew.bat :mc-26.3:build
   ```
3. Copy the compiled JAR from `mc-26.2/build/libs/` into your Minecraft profile's `mods` folder.

## MCP Client Setup

Print a ready-to-merge config snippet with absolute paths:

```powershell
node "C:\path\to\flashback-director-mcp\mcp-server\flashback-director-mcp.mjs" --print-config antigravity
```

Supported `--print-config` targets: `antigravity`, `cursor`, `claude`, `vscode`, `generic`.

Optional: copy `skills/flashback-director/SKILL.md` into your AI client's skills directory to give the model a structured cinematography workflow.

## Usage in Minecraft

1. Launch Minecraft in **windowed mode (`F11`)** if you plan to take screenshots or export video.
2. Click **MCP: OFF** in the top-right corner of the title screen or pause menu to enable the bridge (**MCP: ON**).
3. In your AI client, call `director_status` to verify the connection, then ask it to open a replay and build shots.

## Security Model

- Disabled by default on first launch; enabled manually via the in-game button.
- Listens only on `127.0.0.1` using an ephemeral port.
- Rotates a 256-bit random Bearer token on every start, written to `~/.flashback-director-mcp/bridge.json` with owner-only file permissions and removed on shutdown.
- Request body size is capped at 2 MB; no external network requests or telemetry.

## Project Layout

- `core/` - pure Java 25 HTTP bridge, authentication, JSON parser, tool dispatcher, spline math, shot presets, and highlight scorer.
- `fabric-shared/` - shared Fabric layer for client-thread execution, world collision checks, Blaze3D screenshots, and config.
- `mc-26.2/` and `mc-26.3/` - version-specific Flashback adapters and Mixin accessors.
- `mcp-server/` - standalone zero-dependency Node.js `stdio` MCP server.
- `skills/flashback-director/` - cinematography workflow skill for AI assistants.

## License

Copyright (c) 2026 seetch. **All Rights Reserved.** See [LICENSE](LICENSE) for full terms.
Personal, non-commercial use and source auditing are permitted; redistribution, modification, and derivative works are prohibited without prior written permission.
