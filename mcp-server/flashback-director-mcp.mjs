#!/usr/bin/env node
/**
 * Flashback Director MCP - zero-dependency local stdio MCP server.
 * Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout and forwards tool calls
 * to the local Flashback Director Fabric mod over authenticated 127.0.0.1 HTTP.
 */

import { readFile } from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const SERVER_VERSION = '0.1.2';
const PROTOCOL_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25'];
const LATEST_PROTOCOL_VERSION = PROTOCOL_VERSIONS.at(-1);
const BRIDGE_FILE = process.env.FLASHBACK_DIRECTOR_BRIDGE_FILE
  || path.join(os.homedir(), '.flashback-director-mcp', 'bridge.json');
const REQUEST_TIMEOUT_MS = 20 * 60 * 1000;

const INSTRUCTIONS = [
  'Controls Flashback replay editing inside a local Minecraft 26.2/26.3 client through Flashback Director MCP.',
  'Time is measured in ticks (20 ticks = 1 second). Positions are block coordinates (camera eye height is y + 1.62).',
  'Recommended YouTuber B-roll workflow:',
  '1. Call director_status and open a replay with director_list_replays / director_open_replay.',
  '2. Use director_scan_highlights to find exciting moments, then inspect candidate angles with director_seek + director_screenshot.',
  '3. Build smooth shots with director_create_shot (collision-safe presets) or fine-tune with director_check_path + director_set_keyframes.',
  '4. Verify start, mid, and end frames with director_screenshot, hide HUD with director_set_visuals, and render via director_export_video.',
  'All timeline changes integrate with Flashback undo/redo (director_history).',
].join('\n');

const TOOLS = [
  {
    name: 'director_status',
    description: 'Check Minecraft and Flashback Director state: connection, game state (menu / in_world / replay / exporting), open replay (tick, total_ticks, paused, speed), camera, fullscreen flag, and export progress.',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'director_list_replays',
    description: 'List available Flashback replays (name, duration in ticks/seconds, file size, modified timestamp, newest first).',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'director_open_replay',
    description: 'Open a replay in the Flashback editor and wait until it finishes loading.',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Replay filename or display name from director_list_replays.' },
        path: { type: 'string', description: 'Alternative to name: absolute path to a replay .zip.' },
      },
    },
  },
  {
    name: 'director_close_replay',
    description: 'Close the active replay and return to the title screen (Flashback auto-saves the timeline).',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'director_playback',
    description: 'Control replay playback (play/pause and playback speed multiplier 0.01..100).',
    inputSchema: {
      type: 'object',
      properties: {
        paused: { type: 'boolean', description: 'true to pause, false to play.' },
        speed: { type: 'number', description: 'Playback speed multiplier (1 = real time).' },
      },
    },
  },
  {
    name: 'director_seek',
    description: 'Jump to a specific tick or second and wait until entities and chunks settle.',
    inputSchema: {
      type: 'object',
      properties: {
        tick: { type: 'integer', description: 'Target replay tick (20 ticks = 1 second).' },
        seconds: { type: 'number', description: 'Alternative to tick: time in seconds.' },
        apply_keyframes: { type: 'boolean', description: 'Apply timeline keyframes at the destination tick (default true).' },
      },
    },
  },
  {
    name: 'director_list_entities',
    description: 'List loaded entities near the camera at the current tick with positions, velocities, and look directions.',
    inputSchema: {
      type: 'object',
      properties: {
        players_only: { type: 'boolean', description: 'Only return players (default true).' },
        radius: { type: 'number', description: 'Max distance from camera in blocks (default 96).' },
        limit: { type: 'integer', description: 'Max entities returned (default 60).' },
      },
    },
  },
  {
    name: 'director_scan_highlights',
    description: 'Scan a bounded replay range to find exciting YouTuber B-roll moments (speed bursts, jumps/falls, combat damage, entity clusters) ranked by action score.',
    inputSchema: {
      type: 'object',
      properties: {
        from_tick: { type: 'integer', description: 'Start tick (defaults to 0).' },
        to_tick: { type: 'integer', description: 'End tick (defaults to replay length).' },
        step_ticks: { type: 'integer', description: 'Sampling stride in ticks (default 20 = 1 second).' },
        max_samples: { type: 'integer', description: 'Hard cap on sampled ticks per call (default 120).' },
        limit: { type: 'integer', description: 'Max distinct highlight candidates to return (default 10).' },
        focus_player: { type: 'string', description: 'Optional player name to track.' },
      },
    },
  },
  {
    name: 'director_check_path',
    description: 'Validate camera waypoints against loaded world blocks (block clearance + line-of-sight raycast) and suggest safe collision-free offsets.',
    inputSchema: {
      type: 'object',
      properties: {
        points: {
          type: 'array',
          minItems: 1,
          items: { type: 'array', items: { type: 'number' }, minItems: 3, maxItems: 3 },
          description: 'Camera positions [[x, y, z], ...] to check.',
        },
        look_at: {
          type: 'array',
          items: { type: 'number' },
          minItems: 3,
          maxItems: 3,
          description: 'Optional target [x, y, z] to verify unobstructed line of sight.',
        },
        clearance: { type: 'number', description: 'Required radius around camera in blocks (default 0.45).' },
        suggest_safe_offsets: { type: 'boolean', description: 'Search for nearby clear camera positions when blocked (default true).' },
      },
      required: ['points'],
    },
  },
  {
    name: 'director_screenshot',
    description: 'Capture one frame as a PNG via Minecraft/Blaze3D (Vulkan-safe) and return it directly as an image so you can inspect framing. Requires windowed mode (F11).',
    inputSchema: {
      type: 'object',
      properties: {
        tick: { type: 'integer', description: 'Optional tick to seek before capturing.' },
        seconds: { type: 'number', description: 'Alternative to tick: seconds from start.' },
        width: { type: 'integer', description: 'Output width in pixels (default 960).' },
        height: { type: 'integer', description: 'Output height in pixels (default 540).' },
        output: { type: 'string', description: 'Optional absolute path to save the PNG.' },
        no_gui: { type: 'boolean', description: 'Hide HUD/GUI during capture.' },
        ssaa: { type: 'boolean', description: 'Enable supersampling anti-aliasing (default false).' },
        apply_keyframes: { type: 'boolean', description: 'Apply keyframes when seeking (default true).' },
      },
    },
  },
  {
    name: 'director_set_camera',
    description: 'Move the free spectator camera immediately (supports look_at [x, y, z], roll, and FOV override).',
    inputSchema: {
      type: 'object',
      properties: {
        x: { type: 'number' },
        y: { type: 'number' },
        z: { type: 'number' },
        yaw: { type: 'number' },
        pitch: { type: 'number' },
        look_at: { type: 'array', items: { type: 'number' }, minItems: 3, maxItems: 3 },
        roll: { type: 'number' },
        fov: { type: 'number' },
      },
    },
  },
  {
    name: 'director_get_timeline',
    description: 'Read scenes, export range, tracks, and keyframes of the current replay.',
    inputSchema: {
      type: 'object',
      properties: {
        include_keyframes: { type: 'boolean', description: 'Include keyframe data (default true).' },
        track: { type: 'integer', description: 'Optional track index filter.' },
        from_tick: { type: 'integer' },
        to_tick: { type: 'integer' },
      },
    },
  },
  {
    name: 'director_manage_scene',
    description: 'Select, add, or rename a Flashback editor scene.',
    inputSchema: {
      type: 'object',
      properties: {
        action: { type: 'string', enum: ['select', 'add', 'rename'] },
        index: { type: 'integer' },
        name: { type: 'string' },
        select: { type: 'boolean', description: 'For add: switch to the created scene (default true).' },
      },
      required: ['action'],
    },
  },
  {
    name: 'director_manage_track',
    description: 'Add, remove, rename, or enable/disable a timeline track.',
    inputSchema: {
      type: 'object',
      properties: {
        action: { type: 'string', enum: ['add', 'remove', 'update'] },
        track: { type: 'integer', description: 'Track index (for remove/update).' },
        type: {
          type: 'string',
          description: 'Track type for add: CAMERA, FOV, SPEED, TIMELAPSE, TIME_OF_DAY, FREEZE, CAMERA_SHAKE, CAMERA_ORBIT, TRACK_ENTITY.',
        },
        name: { type: ['string', 'null'] },
        enabled: { type: 'boolean' },
      },
      required: ['action'],
    },
  },
  {
    name: 'director_set_keyframes',
    description: 'Atomically write or replace keyframes on a track (undoable). Supports CAMERA (with optional look_at), FOV, SPEED, TIMELAPSE, TIME_OF_DAY, FREEZE, CAMERA_SHAKE, CAMERA_ORBIT, TRACK_ENTITY and interpolation modes (SMOOTH, LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD, HERMITE).',
    inputSchema: {
      type: 'object',
      properties: {
        track: { type: 'integer' },
        track_type: { type: 'string' },
        keyframes: { type: 'array', minItems: 1, items: { type: 'object' } },
        clear_existing: { type: 'boolean', description: 'Clear existing keyframes on this track first (default false).' },
      },
      required: ['keyframes'],
    },
  },
  {
    name: 'director_remove_keyframes',
    description: 'Remove keyframes from a track by exact ticks, tick range, or all=true (undoable).',
    inputSchema: {
      type: 'object',
      properties: {
        track: { type: 'integer' },
        track_type: { type: 'string' },
        ticks: { type: 'array', items: { type: 'integer' } },
        from_tick: { type: 'integer' },
        to_tick: { type: 'integer' },
        all: { type: 'boolean' },
      },
    },
  },
  {
    name: 'director_history',
    description: 'Undo or redo the last Flashback timeline edit.',
    inputSchema: {
      type: 'object',
      properties: {
        action: { type: 'string', enum: ['undo', 'redo'] },
      },
      required: ['action'],
    },
  },
  {
    name: 'director_get_visuals',
    description: 'Read current Flashback Visuals settings (HUD visibility, nametags, sky, particles, weather, time of day, fog, FOV).',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'director_set_visuals',
    description: 'Update Flashback Visuals settings using keys returned by director_get_visuals. Also supports per-entity nametag filtering: hideNametags (UUID list), addHiddenNametags (UUID list), removeHiddenNametags (UUID list), and keepNametagsOnlyFor (list of entity/player names to keep visible while hiding all other loaded NPC/hologram nametags).',
    inputSchema: {
      type: 'object',
      properties: {
        values: { type: 'object', description: 'Map of visual setting name -> value.' },
      },
      required: ['values'],
    },
  },
  {
    name: 'director_create_shot',
    description: 'Generate a complete YouTuber-style cinematic shot as editable Flashback keyframes with automatic block-collision avoidance. Presets: flyover, orbit, dolly_reveal, follow_parallax, impact_freeze.',
    inputSchema: {
      type: 'object',
      properties: {
        preset: { type: 'string', enum: ['flyover', 'orbit', 'dolly_reveal', 'follow_parallax', 'impact_freeze'] },
        start_tick: { type: 'integer' },
        end_tick: { type: 'integer' },
        start_seconds: { type: 'number' },
        end_seconds: { type: 'number' },
        target: { type: 'array', items: { type: 'number' }, minItems: 3, maxItems: 3, description: 'Subject [x, y, z] to frame.' },
        start_position: { type: 'array', items: { type: 'number' }, minItems: 3, maxItems: 3 },
        end_position: { type: 'array', items: { type: 'number' }, minItems: 3, maxItems: 3 },
        distance: { type: 'number', description: 'Preferred camera distance from target in blocks.' },
        height_offset: { type: 'number', description: 'Camera height offset relative to target.' },
        arc_degrees: { type: 'number', description: 'Orbit sweep angle in degrees (default 120).' },
        fov: { type: 'number', description: 'Base cinematic FOV (default 48).' },
        slow_speed: { type: 'number', description: 'Slow-motion speed for follow_parallax / impact_freeze.' },
        freeze_ticks: { type: 'integer', description: 'Optional freeze duration for impact_freeze.' },
        avoid_collisions: { type: 'boolean', description: 'Check and offset waypoints away from blocks (default true).' },
        clear_existing: { type: 'boolean', description: 'Clear existing keyframes on affected tracks before writing (default false).' },
      },
      required: ['preset', 'target'],
    },
  },
  {
    name: 'director_set_export_range',
    description: 'Set or clear the timeline in/out tick range for video export.',
    inputSchema: {
      type: 'object',
      properties: {
        start_tick: { type: 'integer' },
        end_tick: { type: 'integer' },
        clear: { type: 'boolean' },
      },
    },
  },
  {
    name: 'director_export_video',
    description: 'Start rendering the current Flashback scene to video (requires windowed mode F11). Poll director_status until export completes.',
    inputSchema: {
      type: 'object',
      properties: {
        output: { type: 'string', description: 'Absolute output file path.' },
        overwrite: { type: 'boolean' },
        width: { type: 'integer' },
        height: { type: 'integer' },
        framerate: { type: 'number' },
        start_tick: { type: 'integer' },
        end_tick: { type: 'integer' },
        container: { type: 'string' },
        codec: { type: 'string' },
        encoder: { type: 'string' },
        bitrate: { type: ['string', 'number'] },
        record_audio: { type: 'boolean' },
        stereo_audio: { type: 'boolean' },
        ssaa: { type: 'boolean' },
        no_gui: { type: 'boolean' },
        wait: { type: 'boolean', description: 'Wait until export completes (default false).' },
        wait_seconds: { type: 'number', description: 'Max seconds to wait when wait=true (default 600).' },
      },
    },
  },
];

// CLI helper: print client MCP config without starting stdio loop.
const cliArgs = process.argv.slice(2);
if (cliArgs.length > 0) {
  const supported = ['antigravity', 'cursor', 'claude', 'vscode', 'generic'];
  if (cliArgs.length === 1 && cliArgs[0] === '--help') {
    console.log(
      `Flashback Director MCP ${SERVER_VERSION}\n` +
      'No arguments: start MCP server over stdio.\n' +
      `Print config: node <script.mjs> --print-config <${supported.join('|')}>`
    );
    process.exit(0);
  }
  if (cliArgs.length !== 2 || cliArgs[0] !== '--print-config' || !supported.includes(cliArgs[1])) {
    console.error('Invalid arguments. Use --help for usage.');
    process.exit(2);
  }
  const client = cliArgs[1];
  const entry = {
    command: process.execPath,
    args: [fileURLToPath(import.meta.url)],
  };
  const payload = client === 'vscode'
    ? { servers: { 'flashback-director': { type: 'stdio', ...entry } } }
    : client === 'antigravity'
      ? { mcpServers: { 'flashback-director': { ...entry, timeoutSeconds: 1200 } } }
      : { mcpServers: { 'flashback-director': entry } };
  console.log(JSON.stringify(payload, null, 2));
  process.exit(0);
}

const NOT_RUNNING_MESSAGE =
  'Flashback Director MCP is disabled or Minecraft is not running. ' +
  'Start your Minecraft profile with Flashback Director MCP, click "MCP: OFF" in the title or pause menu to enable it, and retry.';

class BridgeError extends Error {}

async function locateBridge() {
  try {
    const raw = await readFile(BRIDGE_FILE, 'utf8');
    const data = JSON.parse(raw);
    if (!Number.isInteger(data.port) || data.port < 1 || data.port > 65535
        || typeof data.token !== 'string' || data.token.length < 32) {
      throw new Error('malformed');
    }
    // Always force loopback regardless of bridge file contents.
    return { host: '127.0.0.1', port: data.port, token: data.token };
  } catch {
    throw new BridgeError(NOT_RUNNING_MESSAGE);
  }
}

function postJson(bridge, body) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(JSON.stringify(body), 'utf8');
    const req = http.request(
      {
        host: bridge.host,
        port: bridge.port,
        path: '/call',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': payload.length,
          Authorization: `Bearer ${bridge.token}`,
        },
        timeout: REQUEST_TIMEOUT_MS,
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          try {
            resolve({ status: res.statusCode ?? 500, json: JSON.parse(text) });
          } catch {
            reject(new BridgeError(`Unreadable response from Minecraft bridge (HTTP ${res.statusCode}).`));
          }
        });
      },
    );

    req.on('timeout', () => req.destroy(new BridgeError('Minecraft bridge timed out waiting for response.')));
    req.on('error', (err) => {
      if (err instanceof BridgeError) {
        reject(err);
      } else if (err.code === 'ECONNREFUSED' || err.code === 'ECONNRESET') {
        reject(new BridgeError(NOT_RUNNING_MESSAGE));
      } else {
        reject(new BridgeError(`Bridge connection error: ${err.message}`));
      }
    });
    req.end(payload);
  });
}

async function callBridge(toolName, args) {
  const bridge = await locateBridge();
  const { status, json } = await postJson(bridge, { tool: toolName, args });
  if (status === 401) {
    throw new BridgeError(
      'Minecraft bridge rejected the token (the game was likely restarted). Retry the tool call to pick up the new session token.'
    );
  }
  if (!json.ok) {
    const prefix = json.code ? `[${json.code}] ` : '';
    throw new BridgeError(prefix + (json.error || `Bridge call failed (HTTP ${status}).`));
  }
  return json.result;
}

async function executeTool(name, args) {
  try {
    const isScreenshot = name === 'director_screenshot';
    const shortName = name.replace(/^director_/, '');
    const result = await callBridge(shortName, args ?? {});

    const content = [];
    if (isScreenshot && result && typeof result.image_base64 === 'string') {
      const { image_base64: data, ...meta } = result;
      content.push({ type: 'text', text: JSON.stringify(meta) });
      content.push({ type: 'image', data, mimeType: 'image/png' });
    } else {
      content.push({ type: 'text', text: JSON.stringify(result) });
    }
    return { content };
  } catch (err) {
    if (err instanceof BridgeError) {
      return { content: [{ type: 'text', text: err.message }], isError: true };
    }
    throw err;
  }
}

function send(msg) {
  process.stdout.write(`${JSON.stringify(msg)}\n`);
}

async function handleMessage(message) {
  const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
  if (!isObject(message) || message.jsonrpc !== '2.0' || typeof message.method !== 'string'
      || ('id' in message && typeof message.id !== 'string' && !Number.isInteger(message.id))) {
    send({ jsonrpc: '2.0', id: null, error: { code: -32600, message: 'Invalid Request' } });
    return;
  }

  const { id, method, params } = message;
  if (id === undefined) {
    return;
  }
  if (params !== undefined && !isObject(params)) {
    send({ jsonrpc: '2.0', id, error: { code: -32602, message: 'params must be an object' } });
    return;
  }

  try {
    let result;
    switch (method) {
      case 'initialize':
        if (typeof params?.protocolVersion !== 'string') {
          send({ jsonrpc: '2.0', id, error: { code: -32602, message: 'initialize requires protocolVersion' } });
          return;
        }
        result = {
          protocolVersion: PROTOCOL_VERSIONS.includes(params.protocolVersion)
            ? params.protocolVersion
            : LATEST_PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: { name: 'flashback-director', version: SERVER_VERSION },
          instructions: INSTRUCTIONS,
        };
        break;
      case 'ping':
        result = {};
        break;
      case 'tools/list':
        result = { tools: TOOLS };
        break;
      case 'tools/call':
        if (typeof params?.name !== 'string'
            || !TOOLS.some((t) => t.name === params.name)
            || (params.arguments !== undefined && !isObject(params.arguments))) {
          send({ jsonrpc: '2.0', id, error: { code: -32602, message: 'Unknown tool or invalid arguments object' } });
          return;
        }
        result = await executeTool(params.name, params.arguments);
        break;
      default:
        send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } });
        return;
    }
    send({ jsonrpc: '2.0', id, result });
  } catch (err) {
    send({ jsonrpc: '2.0', id, error: { code: -32603, message: err?.message ?? String(err) } });
  }
}

let pending = 0;
let closed = false;

function maybeExit() {
  if (closed && pending === 0) {
    process.stdout.write('', () => process.exit(0));
  }
}

process.stdout.on('error', () => process.exit(0));

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', (line) => {
  if (!line.trim()) {
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(line);
  } catch {
    send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
    return;
  }
  pending += 1;
  handleMessage(parsed).finally(() => {
    pending -= 1;
    maybeExit();
  });
});

rl.on('close', () => {
  closed = true;
  maybeExit();
});
