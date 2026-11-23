package dev.seetch.flashbackdirector.core;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ShotPresetRequest;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates JSON arguments and dispatches all 22 director_* tools to the active DirectorBackend.
 */
public final class ToolDispatcher {

    private final DirectorBackend backend;

    public ToolDispatcher(DirectorBackend backend) {
        this.backend = backend;
    }

    public Map<String, Object> dispatch(String tool, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return switch (tool) {
            case "status" -> backend.status();
            case "list_replays" -> backend.listReplays();
            case "open_replay" -> backend.openReplay(optString(safeArgs, "name"), optString(safeArgs, "path"));
            case "close_replay" -> backend.closeReplay();
            case "playback" -> backend.playback(optBoolean(safeArgs, "paused"), optDouble(safeArgs, "speed"));
            case "seek" -> backend.seek(
                optInt(safeArgs, "tick"),
                optDouble(safeArgs, "seconds"),
                optBool(safeArgs, "apply_keyframes", true)
            );
            case "list_entities" -> backend.listEntities(
                optBool(safeArgs, "players_only", true),
                optDouble(safeArgs, "radius", 96.0),
                optInt(safeArgs, "limit", 60)
            );
            case "scan_highlights" -> backend.scanHighlights(
                optInt(safeArgs, "from_tick"),
                optInt(safeArgs, "to_tick"),
                optInt(safeArgs, "step_ticks", 20),
                optInt(safeArgs, "max_samples", 120),
                optInt(safeArgs, "limit", 10),
                optString(safeArgs, "focus_player")
            );
            case "check_path" -> backend.checkPath(
                requireVec3List(safeArgs, "points"),
                optVec3(safeArgs, "look_at"),
                optDouble(safeArgs, "clearance", 0.45),
                optBool(safeArgs, "suggest_safe_offsets", true)
            );
            case "screenshot" -> backend.screenshot(
                optInt(safeArgs, "tick"),
                optDouble(safeArgs, "seconds"),
                optInt(safeArgs, "width", 960),
                optInt(safeArgs, "height", 540),
                optString(safeArgs, "output"),
                optBoolean(safeArgs, "no_gui"),
                optBool(safeArgs, "ssaa", false),
                optBool(safeArgs, "apply_keyframes", true)
            );
            case "set_camera" -> backend.setCamera(
                optDouble(safeArgs, "x"),
                optDouble(safeArgs, "y"),
                optDouble(safeArgs, "z"),
                optDouble(safeArgs, "yaw"),
                optDouble(safeArgs, "pitch"),
                optVec3(safeArgs, "look_at"),
                optDouble(safeArgs, "roll"),
                optDouble(safeArgs, "fov")
            );
            case "get_timeline" -> backend.getTimeline(
                optBool(safeArgs, "include_keyframes", true),
                optInt(safeArgs, "track"),
                optInt(safeArgs, "from_tick"),
                optInt(safeArgs, "to_tick")
            );
            case "manage_scene" -> backend.manageScene(
                requireString(safeArgs, "action"),
                optInt(safeArgs, "index"),
                optString(safeArgs, "name"),
                optBool(safeArgs, "select", true)
            );
            case "manage_track" -> backend.manageTrack(
                requireString(safeArgs, "action"),
                optInt(safeArgs, "track"),
                optString(safeArgs, "type"),
                optString(safeArgs, "name"),
                optBoolean(safeArgs, "enabled")
            );
            case "set_keyframes" -> backend.setKeyframes(
                optInt(safeArgs, "track"),
                optString(safeArgs, "track_type"),
                requireObjectList(safeArgs, "keyframes"),
                optBool(safeArgs, "clear_existing", false)
            );
            case "remove_keyframes" -> backend.removeKeyframes(
                optInt(safeArgs, "track"),
                optString(safeArgs, "track_type"),
                optIntList(safeArgs, "ticks"),
                optInt(safeArgs, "from_tick"),
                optInt(safeArgs, "to_tick"),
                optBool(safeArgs, "all", false)
            );
            case "history" -> backend.history(requireString(safeArgs, "action"));
            case "get_visuals" -> backend.getVisuals();
            case "set_visuals" -> backend.setVisuals(requireObject(safeArgs, "values"));
            case "configure_entities" -> backend.configureEntities(safeArgs);
            case "create_shot" -> backend.createShot(parseShotRequest(safeArgs));
            case "set_export_range" -> backend.setExportRange(
                optInt(safeArgs, "start_tick"),
                optInt(safeArgs, "end_tick"),
                optBool(safeArgs, "clear", false)
            );
            case "export_video" -> backend.exportVideo(safeArgs);
            default -> throw ToolError.invalidParams("Unknown tool: " + tool);
        };
    }

    private static ShotPresetRequest parseShotRequest(Map<String, Object> args) {
        String preset = requireString(args, "preset");
        Integer startTick = optInt(args, "start_tick");
        Integer endTick = optInt(args, "end_tick");
        if (startTick == null) {
            Double startSec = optDouble(args, "start_seconds");
            if (startSec != null) {
                startTick = (int) Math.round(startSec * 20.0);
            }
        }
        if (endTick == null) {
            Double endSec = optDouble(args, "end_seconds");
            if (endSec != null) {
                endTick = (int) Math.round(endSec * 20.0);
            }
        }
        if (startTick == null || endTick == null) {
            throw ToolError.invalidParams("start_tick/end_tick (or start_seconds/end_seconds) are required");
        }
        return new ShotPresetRequest(
            preset,
            startTick,
            endTick,
            optVec3(args, "target"),
            optVec3(args, "start_position"),
            optVec3(args, "end_position"),
            optDouble(args, "distance", 0.0),
            optDouble(args, "height_offset", 0.0),
            optDouble(args, "arc_degrees", 0.0),
            optDouble(args, "fov", 48.0),
            optDouble(args, "slow_speed", 0.0),
            optInt(args, "freeze_ticks", 0),
            optBool(args, "avoid_collisions", true),
            optBool(args, "clear_existing", false)
        );
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof String s && !s.isBlank()) {
            return s;
        }
        throw ToolError.invalidParams("Missing or invalid string parameter: " + key);
    }

    private static String optString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof String s) {
            return s;
        }
        throw ToolError.invalidParams("Parameter '" + key + "' must be a string");
    }

    private static Boolean optBoolean(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        throw ToolError.invalidParams("Parameter '" + key + "' must be a boolean");
    }

    private static boolean optBool(Map<String, Object> args, String key, boolean def) {
        Boolean b = optBoolean(args, key);
        return b == null ? def : b;
    }

    private static Integer optInt(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        throw ToolError.invalidParams("Parameter '" + key + "' must be an integer");
    }

    private static int optInt(Map<String, Object> args, String key, int def) {
        Integer i = optInt(args, key);
        return i == null ? def : i;
    }

    private static Double optDouble(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) {
                throw ToolError.invalidParams("Parameter '" + key + "' must be finite");
            }
            return d;
        }
        throw ToolError.invalidParams("Parameter '" + key + "' must be a number");
    }

    private static double optDouble(Map<String, Object> args, String key, double def) {
        Double d = optDouble(args, key);
        return d == null ? def : d;
    }

    private static Vec3 optVec3(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        return parseVec3(val, key);
    }

    private static Vec3 parseVec3(Object val, String label) {
        if (!(val instanceof List<?> list) || list.size() != 3) {
            throw ToolError.invalidParams("Parameter '" + label + "' must be a 3-element number array [x, y, z]");
        }
        double[] coords = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                throw ToolError.invalidParams("Parameter '" + label + "' must contain finite numbers");
            }
            coords[i] = n.doubleValue();
        }
        return new Vec3(coords[0], coords[1], coords[2]);
    }

    private static List<Vec3> requireVec3List(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (!(val instanceof List<?> list) || list.isEmpty()) {
            throw ToolError.invalidParams("Parameter '" + key + "' must be a non-empty array of [x, y, z] points");
        }
        List<Vec3> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(parseVec3(list.get(i), key + "[" + i + "]"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw ToolError.invalidParams("Missing or invalid object parameter: " + key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> requireObjectList(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (!(val instanceof List<?> list) || list.isEmpty()) {
            throw ToolError.invalidParams("Parameter '" + key + "' must be a non-empty array of objects");
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw ToolError.invalidParams("Every item in '" + key + "' must be a JSON object");
            }
            out.add((Map<String, Object>) map);
        }
        return out;
    }

    private static List<Integer> optIntList(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return null;
        }
        if (!(val instanceof List<?> list)) {
            throw ToolError.invalidParams("Parameter '" + key + "' must be an array of integers");
        }
        List<Integer> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Number n)) {
                throw ToolError.invalidParams("Every item in '" + key + "' must be an integer");
            }
            out.add(n.intValue());
        }
        return out;
    }
}
