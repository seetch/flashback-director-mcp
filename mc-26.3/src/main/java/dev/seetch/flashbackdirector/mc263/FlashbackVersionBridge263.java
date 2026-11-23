package dev.seetch.flashbackdirector.mc263;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.combo_options.TrackingBodyPart;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.handler.MinecraftKeyframeHandler;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraOrbitKeyframe;
import com.moulberry.flashback.keyframe.impl.CameraShakeKeyframe;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.moulberry.flashback.keyframe.impl.FreezeKeyframe;
import com.moulberry.flashback.keyframe.impl.TickrateKeyframe;
import com.moulberry.flashback.keyframe.impl.TimelapseKeyframe;
import com.moulberry.flashback.keyframe.impl.TimeOfDayKeyframe;
import com.moulberry.flashback.keyframe.impl.TrackEntityKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraOrbitKeyframeType;
import com.moulberry.flashback.keyframe.types.CameraShakeKeyframeType;
import com.moulberry.flashback.keyframe.types.FOVKeyframeType;
import com.moulberry.flashback.keyframe.types.FreezeKeyframeType;
import com.moulberry.flashback.keyframe.types.SpeedKeyframeType;
import com.moulberry.flashback.keyframe.types.TimeOfDayKeyframeType;
import com.moulberry.flashback.keyframe.types.TimelapseKeyframeType;
import com.moulberry.flashback.keyframe.types.TrackEntityKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorSceneHistoryAction;
import com.moulberry.flashback.state.EditorSceneHistoryEntry;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import com.moulberry.flashback.visuals.ReplayVisuals;
import dev.seetch.flashbackdirector.core.CameraPathMath;
import dev.seetch.flashbackdirector.core.MiniJson;
import dev.seetch.flashbackdirector.core.ToolError;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.YawPitch;
import dev.seetch.flashbackdirector.fabric.FlashbackVersionBridge;
import dev.seetch.flashbackdirector.mc263.mixin.CameraShakeKeyframeAccessor;
import dev.seetch.flashbackdirector.mc263.mixin.FreezeKeyframeAccessor;
import dev.seetch.flashbackdirector.mc263.mixin.TickrateKeyframeAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3d;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Flashback 0.43.6 adapter for Minecraft 26.3.
 */
public final class FlashbackVersionBridge263 implements FlashbackVersionBridge {

    private static final String MOD_ID = "flashback-director-mcp";

    @Override
    public String minecraftVersion() {
        return "26.3";
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("0.1.0");
    }

    @Override
    public String flashbackVersion() {
        return FabricLoader.getInstance().getModContainer("flashback")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public boolean isInReplay() {
        return Flashback.isInReplay() && Flashback.getReplayServer() != null;
    }

    @Override
    public boolean isExporting() {
        return Flashback.isExporting();
    }

    @Override
    public boolean isFullscreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getWindow() != null && mc.getWindow().isExclusiveFullscreen();
    }

    @Override
    public Map<String, Object> buildStatus() {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mod_version", modVersion());
        result.put("minecraft_version", minecraftVersion());
        result.put("flashback_version", flashbackVersion());
        result.put("fullscreen", isFullscreen());

        String state;
        if (Flashback.isExporting()) {
            state = "exporting";
        } else if (isInReplay()) {
            state = "replay";
        } else if (mc.level != null) {
            state = "in_world";
        } else {
            state = "menu";
        }
        result.put("state", state);

        if (isInReplay()) {
            ReplayServer server = Flashback.getReplayServer();
            Map<String, Object> replay = new LinkedHashMap<>();
            replay.put("tick", server.getReplayTick());
            replay.put("total_ticks", server.getTotalReplayTicks());
            replay.put("seconds", CameraPathMath.round2(server.getReplayTick() / 20.0));
            replay.put("total_seconds", CameraPathMath.round2(server.getTotalReplayTicks() / 20.0));
            replay.put("paused", server.replayPaused);
            replay.put("speed", CameraPathMath.round2(server.getDesiredTickRate(true) / 20.0));
            replay.put("ready", server.isReady());
            if (server.getMetadata() != null) {
                replay.put("name", server.getMetadata().name);
                replay.put("world_name", server.getMetadata().worldName);
            }
            result.put("replay", replay);
            result.put("camera", readCameraMap());
        }

        ExportJob job = Flashback.EXPORT_JOB;
        if (job != null) {
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("running", job.isRunning());
            export.put("progress_count", job.progressCount);
            export.put("progress_out_of", job.progressOutOf);
            if (job.getSettings() != null && job.getSettings().output() != null) {
                export.put("output", job.getSettings().output().toAbsolutePath().toString());
            }
            result.put("export", export);
        }

        return result;
    }

    @Override
    public Map<String, Object> listReplays() {
        Path folder = Flashback.getReplayFolder();
        List<Map<String, Object>> replays = new ArrayList<>();
        if (folder != null && Files.isDirectory(folder)) {
            try (Stream<Path> entries = Files.list(folder)) {
                List<Path> candidates = entries
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparingLong(FlashbackVersionBridge263::lastModifiedSafe).reversed())
                    .toList();

                for (Path zipPath : candidates) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("file_name", zipPath.getFileName().toString());
                    item.put("path", zipPath.toAbsolutePath().toString());
                    item.put("size_bytes", fileSizeSafe(zipPath));
                    item.put("last_modified_ms", lastModifiedSafe(zipPath));
                    readReplayZipMeta(zipPath, item);
                    replays.add(item);
                }
            } catch (IOException e) {
                throw ToolError.internal("Failed to list replays in " + folder + ": " + e.getMessage(), e);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("replay_folder", folder == null ? "" : folder.toAbsolutePath().toString());
        response.put("count", replays.size());
        response.put("replays", replays);
        return response;
    }

    @Override
    public Map<String, Object> openReplay(String name, String path) {
        Path target = resolveReplayPath(name, path);
        if (target == null || !Files.exists(target)) {
            throw ToolError.invalidParams("Replay file not found. Call director_list_replays to see valid names.");
        }
        Flashback.openReplayWorld(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("opening", target.toAbsolutePath().toString());
        result.put("note", "Replay load triggered. Poll director_status until state == 'replay' and replay.ready == true.");
        return result;
    }

    @Override
    public Map<String, Object> closeReplay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.disconnect(new TitleScreen(), false);
        }
        return Map.of("closed", true);
    }

    @Override
    public Map<String, Object> playback(Boolean paused, Double speed) {
        ReplayServer server = requireReplayServer();
        if (paused != null) {
            server.replayPaused = paused;
        }
        if (speed != null) {
            float clamped = (float) CameraPathMath.clamp(speed, 0.01, 100.0);
            server.setDesiredTickRate(clamped * 20.0f, true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paused", server.replayPaused);
        result.put("speed", CameraPathMath.round2(server.getDesiredTickRate(true) / 20.0));
        result.put("tick", server.getReplayTick());
        return result;
    }

    @Override
    public int requestSeek(Integer tick, Double seconds, boolean applyKeyframes) {
        ReplayServer server = requireReplayServer();
        int targetTick;
        if (tick != null) {
            targetTick = tick;
        } else if (seconds != null) {
            targetTick = (int) Math.round(seconds * 20.0);
        } else {
            throw ToolError.invalidParams("Provide either 'tick' or 'seconds'");
        }
        targetTick = Math.max(0, Math.min(targetTick, Math.max(0, server.getTotalReplayTicks())));
        server.goToReplayTick(targetTick);
        if (applyKeyframes) {
            server.forceApplyKeyframes.set(true);
        }
        return targetTick;
    }

    @Override
    public boolean isSeekSettled(int targetTick) {
        ReplayServer server = requireReplayServer();
        return server.jumpToTick == -1
            && !server.fastForwarding
            && Math.abs(server.getReplayTick() - targetTick) <= 1;
    }

    @Override
    public Map<String, Object> finalizeSeek(int targetTick, boolean applyKeyframes) {
        ReplayServer server = requireReplayServer();
        if (applyKeyframes) {
            EditorState state = server.getEditorState();
            if (state != null) {
                state.applyKeyframes(new MinecraftKeyframeHandler(Minecraft.getInstance()), (float) server.getReplayTick());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested_tick", targetTick);
        result.put("current_tick", server.getReplayTick());
        result.put("seconds", CameraPathMath.round2(server.getReplayTick() / 20.0));
        result.put("camera", readCameraMap());
        return result;
    }

    @Override
    public int currentTick() {
        return requireReplayServer().getReplayTick();
    }

    @Override
    public int totalTicks() {
        return requireReplayServer().getTotalReplayTicks();
    }

    @Override
    public Map<String, Object> setCamera(Double x, Double y, Double z, Double yaw, Double pitch, Vec3 lookAt, Double roll, Double fov) {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null) {
            throw ToolError.notInReplay("No active camera entity.");
        }
        double nx = x != null ? x : cam.getX();
        double ny = y != null ? y : cam.getY();
        double nz = z != null ? z : cam.getZ();

        double nyaw = yaw != null ? yaw : cam.getYRot();
        double npitch = pitch != null ? pitch : cam.getXRot();
        if (lookAt != null) {
            YawPitch aim = CameraPathMath.lookAt(new Vec3(nx, ny, nz), lookAt);
            nyaw = aim.yaw();
            npitch = aim.pitch();
        }

        cam.setPos(nx, ny, nz);
        cam.setYRot((float) nyaw);
        cam.setXRot((float) npitch);
        cam.yRotO = (float) nyaw;
        cam.xRotO = (float) npitch;

        EditorState state = requireEditorState();
        if (roll != null) {
            state.replayVisuals.overrideRoll = true;
            state.replayVisuals.overrideRollAmount = roll.floatValue();
        }
        if (fov != null) {
            state.replayVisuals.overrideFov = true;
            state.replayVisuals.setFov((float) CameraPathMath.clamp(fov, 1.0, 170.0));
        }

        return readCameraMap();
    }

    @Override
    public Map<String, Object> getTimeline(boolean includeKeyframes, Integer trackIndex, Integer fromTick, Integer toTick) {
        EditorState state = requireEditorState();
        long stamp = state.acquireRead();
        try {
            List<EditorScene> scenes = state.getScenes(stamp);
            List<Map<String, Object>> sceneNames = new ArrayList<>();
            for (int i = 0; i < scenes.size(); i++) {
                sceneNames.add(Map.of("index", i, "name", scenes.get(i).name == null ? ("Scene " + i) : scenes.get(i).name));
            }

            EditorScene current = state.getCurrentScene(stamp);
            List<Map<String, Object>> tracksOut = new ArrayList<>();
            for (int i = 0; i < current.keyframeTracks.size(); i++) {
                if (trackIndex != null && trackIndex != i) {
                    continue;
                }
                KeyframeTrack track = current.keyframeTracks.get(i);
                Map<String, Object> tMap = new LinkedHashMap<>();
                tMap.put("index", i);
                tMap.put("type", trackTypeName(track.keyframeType));
                tMap.put("name", track.customName);
                tMap.put("enabled", track.enabled);
                tMap.put("keyframe_count", track.keyframesByTick.size());
                if (includeKeyframes) {
                    List<Map<String, Object>> kfList = new ArrayList<>();
                    for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
                        int tk = entry.getKey();
                        if (fromTick != null && tk < fromTick) {
                            continue;
                        }
                        if (toTick != null && tk > toTick) {
                            continue;
                        }
                        kfList.add(serializeKeyframe(tk, entry.getValue()));
                    }
                    tMap.put("keyframes", kfList);
                }
                tracksOut.add(tMap);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scene_index", state.getSceneIndex());
            result.put("scenes", sceneNames);
            result.put("export_start_ticks", current.exportStartTicks);
            result.put("export_end_ticks", current.exportEndTicks);
            result.put("tracks", tracksOut);
            return result;
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> manageScene(String action, Integer index, String name, boolean selectAfterAdd) {
        EditorState state = requireEditorState();
        long stamp = state.acquireWrite();
        try {
            List<EditorScene> scenes = state.getScenes(stamp);
            switch (action.toLowerCase(Locale.ROOT)) {
                case "select" -> {
                    if (index == null || index < 0 || index >= scenes.size()) {
                        throw ToolError.invalidParams("Valid scene index 0.." + (scenes.size() - 1) + " is required");
                    }
                    state.setSceneIndex(index, stamp);
                }
                case "add" -> {
                    String sceneName = (name == null || name.isBlank()) ? ("Scene " + (scenes.size() + 1)) : name;
                    scenes.add(new EditorScene(sceneName));
                    if (selectAfterAdd) {
                        state.setSceneIndex(scenes.size() - 1, stamp);
                    }
                }
                case "rename" -> {
                    int targetIdx = index != null ? index : state.getSceneIndex();
                    if (targetIdx < 0 || targetIdx >= scenes.size()) {
                        throw ToolError.invalidParams("Invalid scene index: " + targetIdx);
                    }
                    if (name == null || name.isBlank()) {
                        throw ToolError.invalidParams("Non-empty scene name is required for rename");
                    }
                    scenes.get(targetIdx).name = name;
                }
                default -> throw ToolError.invalidParams("Invalid scene action: " + action);
            }
            state.markDirty();
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < scenes.size(); i++) {
                out.add(Map.of("index", i, "name", scenes.get(i).name == null ? ("Scene " + i) : scenes.get(i).name));
            }
            return Map.of("scene_index", state.getSceneIndex(), "scenes", out);
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> manageTrack(String action, Integer trackIndex, String trackType, String name, Boolean enabled) {
        EditorState state = requireEditorState();
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            switch (action.toLowerCase(Locale.ROOT)) {
                case "add" -> {
                    KeyframeType<?> type = resolveKeyframeType(trackType);
                    int addedIndex = scene.keyframeTracks.size();
                    List<EditorSceneHistoryAction> redo = List.of(new EditorSceneHistoryAction.AddTrack(type, addedIndex));
                    List<EditorSceneHistoryAction> undo = List.of(new EditorSceneHistoryAction.RemoveTrack(type, addedIndex));
                    scene.push(new EditorSceneHistoryEntry(undo, redo, "Add track"));
                    KeyframeTrack track = scene.keyframeTracks.get(addedIndex);
                    if (name != null && !name.isBlank()) {
                        track.customName = name;
                    }
                    if (enabled != null) {
                        track.enabled = enabled;
                    }
                    state.markDirty();
                    return Map.of("index", addedIndex, "type", trackTypeName(type));
                }
                case "remove" -> {
                    int idx = requireValidTrackIndex(scene, trackIndex);
                    KeyframeTrack removed = scene.keyframeTracks.get(idx);
                    KeyframeType<?> type = removed.keyframeType;
                    List<EditorSceneHistoryAction> redo = List.of(new EditorSceneHistoryAction.RemoveTrack(type, idx));
                    List<EditorSceneHistoryAction> undo = new ArrayList<>();
                    undo.add(new EditorSceneHistoryAction.AddTrack(type, idx));
                    for (Map.Entry<Integer, Keyframe> entry : removed.keyframesByTick.entrySet()) {
                        undo.add(new EditorSceneHistoryAction.SetKeyframe(type, idx, entry.getKey(), entry.getValue().copy()));
                    }
                    scene.push(new EditorSceneHistoryEntry(undo, redo, "Remove track"));
                    state.markDirty();
                    return Map.of("removed_index", idx, "remaining_tracks", scene.keyframeTracks.size());
                }
                case "update" -> {
                    int idx = requireValidTrackIndex(scene, trackIndex);
                    KeyframeTrack track = scene.keyframeTracks.get(idx);
                    if (enabled != null) {
                        track.enabled = enabled;
                    }
                    if (name != null) {
                        track.customName = name.isBlank() ? null : name;
                    }
                    state.markDirty();
                    return Map.of("index", idx, "enabled", track.enabled, "name", track.customName == null ? "" : track.customName);
                }
                default -> throw ToolError.invalidParams("Invalid track action: " + action);
            }
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> setKeyframes(Integer trackIndex, String trackType, List<Map<String, Object>> keyframes, boolean clearExisting) {
        EditorState state = requireEditorState();
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            int idx;
            KeyframeType<?> type;
            boolean createdTrack = false;
            if (trackIndex != null) {
                idx = requireValidTrackIndex(scene, trackIndex);
                type = scene.keyframeTracks.get(idx).keyframeType;
            } else {
                type = resolveKeyframeType(trackType);
                int existingIdx = -1;
                for (int i = 0; i < scene.keyframeTracks.size(); i++) {
                    if (scene.keyframeTracks.get(i).keyframeType == type) {
                        existingIdx = i;
                        break;
                    }
                }
                if (existingIdx >= 0) {
                    idx = existingIdx;
                } else {
                    idx = scene.keyframeTracks.size();
                    createdTrack = true;
                }
            }

            String canonicalType = trackTypeName(type);
            TreeMap<Integer, Keyframe> before = createdTrack
                ? new TreeMap<>()
                : copyTrackMap(scene.keyframeTracks.get(idx).keyframesByTick);

            List<EditorSceneHistoryAction> redo = new ArrayList<>();
            List<EditorSceneHistoryAction> undo = new ArrayList<>();

            if (createdTrack) {
                redo.add(new EditorSceneHistoryAction.AddTrack(type, idx));
            } else if (clearExisting) {
                for (Map.Entry<Integer, Keyframe> entry : before.entrySet()) {
                    redo.add(new EditorSceneHistoryAction.RemoveKeyframe(type, idx, entry.getKey()));
                    undo.add(new EditorSceneHistoryAction.SetKeyframe(type, idx, entry.getKey(), entry.getValue().copy()));
                }
            }

            TreeMap<Integer, Map<String, Object>> sortedInputs = new TreeMap<>();
            for (Map<String, Object> kfMap : keyframes) {
                int tick = parseKeyframeTick(kfMap);
                sortedInputs.put(tick, kfMap);
            }

            TreeMap<Integer, Keyframe> newEntries = new TreeMap<>();
            Double prevCameraYaw = null;
            for (Map.Entry<Integer, Map<String, Object>> inputEntry : sortedInputs.entrySet()) {
                int tick = inputEntry.getKey();
                Map<String, Object> kfMap = inputEntry.getValue();
                Keyframe kf = deserializeKeyframe(canonicalType, kfMap, prevCameraYaw);
                if (kf instanceof CameraKeyframe camKf) {
                    prevCameraYaw = (double) camKf.yaw;
                }
                newEntries.put(tick, kf);
            }

            for (Map.Entry<Integer, Keyframe> entry : newEntries.entrySet()) {
                int tick = entry.getKey();
                Keyframe kf = entry.getValue();
                redo.add(new EditorSceneHistoryAction.SetKeyframe(type, idx, tick, kf.copy()));
                if (!createdTrack && !clearExisting) {
                    if (before.containsKey(tick)) {
                        undo.add(new EditorSceneHistoryAction.SetKeyframe(type, idx, tick, before.get(tick).copy()));
                    } else {
                        undo.add(new EditorSceneHistoryAction.RemoveKeyframe(type, idx, tick));
                    }
                } else if (!createdTrack) {
                    if (!before.containsKey(tick)) {
                        undo.add(new EditorSceneHistoryAction.RemoveKeyframe(type, idx, tick));
                    }
                }
            }

            if (createdTrack) {
                undo.add(new EditorSceneHistoryAction.RemoveTrack(type, idx));
            }

            scene.push(new EditorSceneHistoryEntry(undo, redo, "Set keyframes (" + canonicalType + ")"));
            state.markDirty();

            ReplayServer server = Flashback.getReplayServer();
            if (server != null) {
                server.forceApplyKeyframes.set(true);
            }

            return Map.of(
                "track", idx,
                "track_type", canonicalType,
                "written", keyframes.size(),
                "total_keyframes_on_track", scene.keyframeTracks.get(idx).keyframesByTick.size()
            );
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> removeKeyframes(Integer trackIndex, String trackType, List<Integer> ticks, Integer fromTick, Integer toTick, boolean all) {
        EditorState state = requireEditorState();
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            int idx = findExistingTrack(scene, trackIndex, trackType);
            KeyframeTrack track = scene.keyframeTracks.get(idx);
            KeyframeType<?> type = track.keyframeType;

            List<EditorSceneHistoryAction> redo = new ArrayList<>();
            List<EditorSceneHistoryAction> undo = new ArrayList<>();

            for (Map.Entry<Integer, Keyframe> entry : track.keyframesByTick.entrySet()) {
                int tk = entry.getKey();
                boolean shouldRemove = all
                    || (ticks != null && ticks.contains(tk))
                    || ((fromTick != null || toTick != null)
                        && tk >= (fromTick != null ? fromTick : Integer.MIN_VALUE)
                        && tk <= (toTick != null ? toTick : Integer.MAX_VALUE));
                if (shouldRemove) {
                    redo.add(new EditorSceneHistoryAction.RemoveKeyframe(type, idx, tk));
                    undo.add(new EditorSceneHistoryAction.SetKeyframe(type, idx, tk, entry.getValue().copy()));
                }
            }

            if (!redo.isEmpty()) {
                scene.push(new EditorSceneHistoryEntry(undo, redo, "Remove keyframes"));
                state.markDirty();
            }

            return Map.of("track", idx, "removed", redo.size(), "remaining", track.keyframesByTick.size());
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> history(String action) {
        EditorState state = requireEditorState();
        long stamp = state.acquireWrite();
        try {
            EditorScene scene = state.getCurrentScene(stamp);
            AtomicReference<String> msg = new AtomicReference<>("");
            switch (action.toLowerCase(Locale.ROOT)) {
                case "undo" -> scene.undo(msg::set);
                case "redo" -> scene.redo(msg::set);
                default -> throw ToolError.invalidParams("Action must be 'undo' or 'redo'");
            }
            state.markDirty();
            return Map.of("action", action, "description", msg.get());
        } finally {
            state.release(stamp);
        }
    }

    @Override
    public Map<String, Object> getVisuals() {
        EditorState state = requireEditorState();
        ReplayVisuals v = state.replayVisuals;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("showChat", v.showChat);
        m.put("showBossBar", v.showBossBar);
        m.put("showTitleText", v.showTitleText);
        m.put("showScoreboard", v.showScoreboard);
        m.put("showActionBar", v.showActionBar);
        m.put("showHotbar", v.showHotbar);
        m.put("renderBlocks", v.renderBlocks);
        m.put("renderEntities", v.renderEntities);
        m.put("renderPlayers", v.renderPlayers);
        m.put("renderParticles", v.renderParticles);
        m.put("renderSky", v.renderSky);
        m.put("renderNametags", v.renderNametags);
        m.put("renderBeaconBeams", v.renderBeaconBeams);
        m.put("overrideFog", v.overrideFog);
        m.put("overrideFogStart", v.overrideFogStart);
        m.put("overrideFogEnd", v.overrideFogEnd);
        m.put("overrideFov", v.overrideFov);
        m.put("overrideFovAmount", v.overrideFovAmount);
        m.put("overrideRoll", v.overrideRoll);
        m.put("overrideRollAmount", v.overrideRollAmount);
        m.put("overrideWeatherMode", v.overrideWeatherMode == null ? "NONE" : v.overrideWeatherMode.name());
        m.put("overrideTimeOfDay", v.overrideTimeOfDay);
        m.put("overrideNightVision", v.overrideNightVision);
        m.put("ruleOfThirdsGuide", v.ruleOfThirdsGuide);
        m.put("centerGuide", v.centerGuide);
        m.put("cameraPath", v.cameraPath);
        List<String> hiddenTags = state.hideNametags == null
            ? List.of()
            : state.hideNametags.stream().map(UUID::toString).toList();
        m.put("hideNametags", hiddenTags);
        return m;
    }

    @Override
    public Map<String, Object> setVisuals(Map<String, Object> values) {
        EditorState state = requireEditorState();
        ReplayVisuals v = state.replayVisuals;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            switch (key) {
                case "showChat" -> v.showChat = asBool(val, key);
                case "showBossBar" -> v.showBossBar = asBool(val, key);
                case "showTitleText" -> v.showTitleText = asBool(val, key);
                case "showScoreboard" -> v.showScoreboard = asBool(val, key);
                case "showActionBar" -> v.showActionBar = asBool(val, key);
                case "showHotbar" -> v.showHotbar = asBool(val, key);
                case "renderBlocks" -> v.renderBlocks = asBool(val, key);
                case "renderEntities" -> v.renderEntities = asBool(val, key);
                case "renderPlayers" -> v.renderPlayers = asBool(val, key);
                case "renderParticles" -> v.renderParticles = asBool(val, key);
                case "renderSky" -> v.renderSky = asBool(val, key);
                case "renderNametags" -> v.renderNametags = asBool(val, key);
                case "renderBeaconBeams" -> v.renderBeaconBeams = asBool(val, key);
                case "overrideFog" -> v.overrideFog = asBool(val, key);
                case "overrideFogStart" -> v.overrideFogStart = asFloat(val, key);
                case "overrideFogEnd" -> v.overrideFogEnd = asFloat(val, key);
                case "overrideFov" -> v.overrideFov = asBool(val, key);
                case "overrideFovAmount" -> v.setFov(asFloat(val, key));
                case "overrideRoll" -> v.overrideRoll = asBool(val, key);
                case "overrideRollAmount" -> v.overrideRollAmount = asFloat(val, key);
                case "overrideWeatherMode" -> v.overrideWeatherMode = WeatherOverride.valueOf(String.valueOf(val).toUpperCase(Locale.ROOT));
                case "overrideTimeOfDay" -> v.overrideTimeOfDay = asLong(val, key);
                case "overrideNightVision" -> v.overrideNightVision = asBool(val, key);
                case "ruleOfThirdsGuide" -> v.ruleOfThirdsGuide = asBool(val, key);
                case "centerGuide" -> v.centerGuide = asBool(val, key);
                case "cameraPath" -> v.cameraPath = asBool(val, key);
                case "hideNametags" -> {
                    state.hideNametags.clear();
                    for (UUID id : parseUuidList(val, key)) {
                        state.hideNametags.add(id);
                    }
                }
                case "addHiddenNametags" -> state.hideNametags.addAll(parseUuidList(val, key));
                case "removeHiddenNametags" -> state.hideNametags.removeAll(parseUuidList(val, key));
                case "keepNametagsOnlyFor" -> applyKeepNametagsOnlyFor(state, parseStringSetLower(val, key));
                default -> throw ToolError.invalidParams("Unknown visual setting: " + key);
            }
        }
        state.markDirty();
        return getVisuals();
    }

    private static void applyKeepNametagsOnlyFor(EditorState state, java.util.Set<String> keepLowerNames) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        state.replayVisuals.renderNametags = true;
        for (Entity entity : mc.level.entitiesForRendering()) {
            String entityName = entity.getName().getString().trim().toLowerCase(Locale.ROOT);
            if (keepLowerNames.contains(entityName)) {
                state.hideNametags.remove(entity.getUUID());
            } else {
                state.hideNametags.add(entity.getUUID());
            }
        }
    }

    private static List<UUID> parseUuidList(Object raw, String key) {
        if (!(raw instanceof List<?> list)) {
            throw ToolError.invalidParams("Expected array of UUID strings for " + key);
        }
        List<UUID> out = new ArrayList<>(list.size());
        for (Object item : list) {
            try {
                out.add(UUID.fromString(String.valueOf(item)));
            } catch (IllegalArgumentException e) {
                throw ToolError.invalidParams("Invalid UUID in " + key + ": " + item);
            }
        }
        return out;
    }

    private static java.util.Set<String> parseStringSetLower(Object raw, String key) {
        if (!(raw instanceof List<?> list)) {
            throw ToolError.invalidParams("Expected array of strings for " + key);
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> setExportRange(Integer startTick, Integer endTick, boolean clear) {
        ReplayServer server = requireReplayServer();
        EditorState state = requireEditorState();
        int total = server.getTotalReplayTicks();
        if (clear) {
            state.setExportTicks(-1, -1, total);
        } else {
            int s = startTick != null ? Math.max(0, startTick) : 0;
            int e = endTick != null ? Math.min(total, endTick) : total;
            if (e <= s) {
                throw ToolError.invalidParams("end_tick must be greater than start_tick");
            }
            state.setExportTicks(s, e, total);
        }
        EditorState.StartAndEnd range = state.getExportStartAndEnd();
        return Map.of("start_tick", range.start(), "end_tick", range.end());
    }

    @Override
    public Map<String, Object> exportVideo(Map<String, Object> options) {
        if (Flashback.isExporting()) {
            throw new ToolError("EXPORT_IN_PROGRESS", "An export job is already running. Poll director_status until it completes.");
        }
        ReplayServer server = requireReplayServer();
        EditorState state = requireEditorState();
        FlashbackConfigV1 cfg = Flashback.getConfig();
        FlashbackConfigV1.SubcategoryInternalExport ie = cfg.internalExport;

        EditorState.StartAndEnd defaultRange = state.getExportStartAndEnd();
        int startTick = options.get("start_tick") instanceof Number n ? n.intValue() : defaultRange.start();
        int endTick = options.get("end_tick") instanceof Number n ? n.intValue() : defaultRange.end();
        if (endTick <= startTick) {
            EditorState.StartAndEnd trackSpan = state.getFirstAndLastTicksInTracks();
            startTick = trackSpan.start();
            endTick = trackSpan.end() > trackSpan.start() ? trackSpan.end() : server.getTotalReplayTicks();
        }

        int width = options.get("width") instanceof Number n ? n.intValue() : (ie.resolution != null && ie.resolution.length >= 2 ? ie.resolution[0] : 1920);
        int height = options.get("height") instanceof Number n ? n.intValue() : (ie.resolution != null && ie.resolution.length >= 2 ? ie.resolution[1] : 1080);
        double framerate = options.get("framerate") instanceof Number n ? n.doubleValue() : (ie.framerate != null && ie.framerate.length >= 1 ? ie.framerate[0] : 60.0);

        VideoContainer container = options.get("container") instanceof String s
            ? VideoContainer.valueOf(s.toUpperCase(Locale.ROOT))
            : (ie.container != null ? ie.container : VideoContainer.MP4);
        VideoCodec codec = options.get("codec") instanceof String s
            ? VideoCodec.valueOf(s.toUpperCase(Locale.ROOT))
            : (ie.videoCodec != null ? ie.videoCodec : VideoCodec.H264);
        String encoder = options.get("encoder") instanceof String s && !s.isBlank()
            ? s
            : (ie.selectedVideoEncoder != null && !ie.selectedVideoEncoder.isBlank() ? ie.selectedVideoEncoder : firstEncoder(codec));
        AudioCodec audioCodec = options.get("audio_codec") instanceof String s
            ? AudioCodec.valueOf(s.toUpperCase(Locale.ROOT))
            : (ie.audioCodec != null ? ie.audioCodec : AudioCodec.AAC);
        ExportProjection projection = options.get("projection") instanceof String s
            ? ExportProjection.valueOf(s.toUpperCase(Locale.ROOT))
            : (ie.projection != null ? ie.projection : ExportProjection.PERSPECTIVE);

        int bitrate = parseBitrate(options.get("bitrate"), 20_000_000);
        boolean ssaa = options.get("ssaa") instanceof Boolean b ? b : ie.ssaa;
        boolean noGui = options.get("no_gui") instanceof Boolean b ? b : ie.noGui;
        boolean recordAudio = options.get("record_audio") instanceof Boolean b ? b : ie.recordAudio;
        boolean stereoAudio = options.get("stereo_audio") instanceof Boolean b ? b : ie.stereoAudio;
        boolean resetRng = options.get("reset_rng") instanceof Boolean b ? b : ie.resetRng;
        boolean depthMap = options.get("depth_map") instanceof Boolean b ? b : ie.depthMap;
        boolean transparent = options.get("transparent") instanceof Boolean b ? b : ie.transparentBackground;
        float orthoZoom = options.get("orthographic_zoom") instanceof Number n
            ? n.floatValue()
            : (ie.orthographicZoom != null && ie.orthographicZoom.length >= 1 ? ie.orthographicZoom[0] : 1.0f);
        String seqFormat = options.get("sequence_format") instanceof String s && !s.isBlank() ? s : "%04d";
        String jobName = options.get("name") instanceof String s && !s.isBlank() ? s : "director_shot";

        Path outputPath;
        if (options.get("output") instanceof String outStr && !outStr.isBlank()) {
            outputPath = Path.of(outStr);
        } else {
            String baseDir = (ie.defaultExportPath != null && !ie.defaultExportPath.isBlank())
                ? ie.defaultExportPath
                : Minecraft.getInstance().gameDirectory.toPath().resolve("flashback").resolve("exports").toString();
            String defaultName = StartExportWindow.getDefaultFilename(jobName, container.extension(), cfg);
            outputPath = Path.of(baseDir).resolve(defaultName);
        }

        boolean overwrite = options.get("overwrite") instanceof Boolean b && b;
        if (Files.exists(outputPath) && !overwrite) {
            throw ToolError.invalidParams("Output file already exists (pass overwrite=true to replace): " + outputPath);
        }

        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        net.minecraft.world.phys.Vec3 camPos = cam != null ? cam.position() : net.minecraft.world.phys.Vec3.ZERO;
        float camYaw = cam != null ? cam.getYRot() : 0.0f;
        float camPitch = cam != null ? cam.getXRot() : 0.0f;

        ExportSettings settings = new ExportSettings(
            jobName,
            state.copy(),
            camPos,
            camYaw,
            camPitch,
            width,
            height,
            startTick,
            endTick,
            projection,
            orthoZoom,
            framerate,
            resetRng,
            depthMap,
            container,
            codec,
            encoder,
            bitrate,
            transparent,
            ssaa,
            noGui,
            stereoAudio,
            recordAudio ? audioCodec : null,
            outputPath,
            seqFormat
        );

        Flashback.EXPORT_JOB = new ExportJob(settings);
        cfg.delayedSaveToDefaultFolder();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("started", true);
        result.put("output", outputPath.toAbsolutePath().toString());
        result.put("start_tick", startTick);
        result.put("end_tick", endTick);
        result.put("resolution", List.of(width, height));
        result.put("framerate", framerate);
        result.put("note", "Poll director_status until state is no longer 'exporting'.");
        return result;
    }

    private ReplayServer requireReplayServer() {
        ReplayServer server = Flashback.getReplayServer();
        if (!Flashback.isInReplay() || server == null) {
            throw ToolError.notInReplay("No replay is currently open.");
        }
        return server;
    }

    private EditorState requireEditorState() {
        EditorState state = requireReplayServer().getEditorState();
        if (state == null) {
            throw ToolError.notInReplay("Replay editor state is not initialized yet.");
        }
        return state;
    }

    private Map<String, Object> readCameraMap() {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        EditorState state = Flashback.getReplayServer() != null ? Flashback.getReplayServer().getEditorState() : null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (cam != null) {
            m.put("x", CameraPathMath.round2(cam.getX()));
            m.put("y", CameraPathMath.round2(cam.getY()));
            m.put("eye_y", CameraPathMath.round2(cam.getY() + CameraPathMath.CAMERA_EYE_HEIGHT));
            m.put("z", CameraPathMath.round2(cam.getZ()));
            m.put("yaw", CameraPathMath.round2(cam.getYRot()));
            m.put("pitch", CameraPathMath.round2(cam.getXRot()));
        }
        if (state != null) {
            m.put("roll", state.replayVisuals.overrideRoll ? state.replayVisuals.overrideRollAmount : 0.0f);
            m.put("fov", state.replayVisuals.overrideFov ? state.replayVisuals.overrideFovAmount : mc.options.fov().get());
        }
        if (mc.level != null) {
            m.put("dimension", mc.level.dimension().identifier().toString());
        }
        return m;
    }

    private static Path resolveReplayPath(String name, String path) {
        if (path != null && !path.isBlank()) {
            return Path.of(path);
        }
        if (name == null || name.isBlank()) {
            throw ToolError.invalidParams("Provide 'name' or 'path' for director_open_replay");
        }
        Path folder = Flashback.getReplayFolder();
        if (folder == null) {
            return null;
        }
        Path direct = folder.resolve(name);
        if (Files.exists(direct)) {
            return direct;
        }
        Path withZip = folder.resolve(name.endsWith(".zip") ? name : name + ".zip");
        return Files.exists(withZip) ? withZip : null;
    }

    private static void readReplayZipMeta(Path zipPath, Map<String, Object> target) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry metaEntry = zip.getEntry("meta.json");
            if (metaEntry == null) {
                return;
            }
            try (InputStream in = zip.getInputStream(metaEntry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> meta = MiniJson.parseObject(json);
                if (meta.get("name") instanceof String n) {
                    target.put("name", n);
                }
                if (meta.get("worldName") instanceof String w) {
                    target.put("world_name", w);
                }
                if (meta.get("versionString") instanceof String v) {
                    target.put("version", v);
                }
                if (meta.get("totalTicks") instanceof Number t) {
                    int ticks = t.intValue();
                    target.put("total_ticks", ticks);
                    target.put("duration_seconds", CameraPathMath.round2(ticks / 20.0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long fileSizeSafe(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static int requireValidTrackIndex(EditorScene scene, Integer idx) {
        if (idx == null || idx < 0 || idx >= scene.keyframeTracks.size()) {
            throw ToolError.invalidParams("Valid track index 0.." + Math.max(0, scene.keyframeTracks.size() - 1) + " is required");
        }
        return idx;
    }

    private static int findExistingTrack(EditorScene scene, Integer trackIndex, String trackType) {
        if (trackIndex != null) {
            return requireValidTrackIndex(scene, trackIndex);
        }
        KeyframeType<?> type = resolveKeyframeType(trackType);
        for (int i = 0; i < scene.keyframeTracks.size(); i++) {
            if (scene.keyframeTracks.get(i).keyframeType == type) {
                return i;
            }
        }
        throw ToolError.invalidParams("No track of type " + trackType + " exists in current scene");
    }

    private static KeyframeType<?> resolveKeyframeType(String name) {
        if (name == null || name.isBlank()) {
            throw ToolError.invalidParams("Specify either 'track' index or 'track_type'");
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "CAMERA" -> CameraKeyframeType.INSTANCE;
            case "FOV" -> FOVKeyframeType.INSTANCE;
            case "SPEED", "TICKRATE" -> SpeedKeyframeType.INSTANCE;
            case "TIMELAPSE" -> TimelapseKeyframeType.INSTANCE;
            case "TIME_OF_DAY" -> TimeOfDayKeyframeType.INSTANCE;
            case "FREEZE" -> FreezeKeyframeType.INSTANCE;
            case "CAMERA_SHAKE" -> CameraShakeKeyframeType.INSTANCE;
            case "CAMERA_ORBIT" -> CameraOrbitKeyframeType.INSTANCE;
            case "TRACK_ENTITY" -> TrackEntityKeyframeType.INSTANCE;
            default -> throw ToolError.invalidParams("Unknown track type: " + name);
        };
    }

    private static String trackTypeName(KeyframeType<?> type) {
        if (type == CameraKeyframeType.INSTANCE) return "CAMERA";
        if (type == FOVKeyframeType.INSTANCE) return "FOV";
        if (type == SpeedKeyframeType.INSTANCE) return "SPEED";
        if (type == TimelapseKeyframeType.INSTANCE) return "TIMELAPSE";
        if (type == TimeOfDayKeyframeType.INSTANCE) return "TIME_OF_DAY";
        if (type == FreezeKeyframeType.INSTANCE) return "FREEZE";
        if (type == CameraShakeKeyframeType.INSTANCE) return "CAMERA_SHAKE";
        if (type == CameraOrbitKeyframeType.INSTANCE) return "CAMERA_ORBIT";
        if (type == TrackEntityKeyframeType.INSTANCE) return "TRACK_ENTITY";
        return type.id().toUpperCase(Locale.ROOT);
    }

    private static TreeMap<Integer, Keyframe> copyTrackMap(TreeMap<Integer, Keyframe> src) {
        TreeMap<Integer, Keyframe> copy = new TreeMap<>();
        for (Map.Entry<Integer, Keyframe> entry : src.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    private static int parseKeyframeTick(Map<String, Object> map) {
        if (map.get("tick") instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (map.get("seconds") instanceof Number n) {
            return Math.max(0, (int) Math.round(n.doubleValue() * 20.0));
        }
        throw ToolError.invalidParams("Each keyframe must specify 'tick' or 'seconds'");
    }

    private static InterpolationType parseInterpolation(Map<String, Object> map) {
        if (map.get("interpolation") instanceof String s && !s.isBlank()) {
            return InterpolationType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
        return InterpolationType.SMOOTH;
    }

    private static Keyframe deserializeKeyframe(String type, Map<String, Object> m, Double prevCameraYaw) {
        InterpolationType interp = parseInterpolation(m);
        return switch (type) {
            case "CAMERA" -> {
                Vector3d pos = parseVector3d(m.get("position"), "position");
                float yaw;
                float pitch;
                if (m.get("look_at") != null) {
                    Vector3d tgt = parseVector3d(m.get("look_at"), "look_at");
                    YawPitch aim = CameraPathMath.lookAt(new Vec3(pos.x, pos.y, pos.z), new Vec3(tgt.x, tgt.y, tgt.z));
                    double unwrapped = prevCameraYaw == null ? aim.yaw() : CameraPathMath.unwrapYaw(prevCameraYaw, aim.yaw());
                    yaw = (float) unwrapped;
                    pitch = (float) aim.pitch();
                } else {
                    yaw = asFloat(m.getOrDefault("yaw", 0.0), "yaw");
                    pitch = asFloat(m.getOrDefault("pitch", 0.0), "pitch");
                }
                float roll = asFloat(m.getOrDefault("roll", 0.0), "roll");
                yield new CameraKeyframe(pos, yaw, pitch, roll, interp);
            }
            case "FOV" -> new FOVKeyframe(asFloat(m.get("fov"), "fov"), interp);
            case "SPEED" -> {
                float speed = asFloat(m.get("speed"), "speed");
                // Flashback TickrateKeyframe stores TPS where 1.0x speed = 20.0f TPS.
                yield new TickrateKeyframe(Math.max(0.01f, speed * 20.0f), interp);
            }
            case "TIMELAPSE" -> new TimelapseKeyframe(asInt(m.get("ticks"), "ticks"));
            case "TIME_OF_DAY" -> new TimeOfDayKeyframe(asInt(m.get("time"), "time"), interp);
            case "FREEZE" -> new FreezeKeyframe(
                asBool(m.getOrDefault("freeze", true), "freeze"),
                asInt(m.getOrDefault("freezeDelay", 0), "freezeDelay"),
                interp
            );
            case "CAMERA_SHAKE" -> {
                float fx = asFloat(m.getOrDefault("frequencyX", 1.0), "frequencyX");
                float ax = asFloat(m.getOrDefault("amplitudeX", 0.5), "amplitudeX");
                float fy = asFloat(m.getOrDefault("frequencyY", fx), "frequencyY");
                float ay = asFloat(m.getOrDefault("amplitudeY", ax), "amplitudeY");
                boolean split = m.get("splitParams") instanceof Boolean b ? b : (fx != fy || ax != ay);
                yield new CameraShakeKeyframe(fx, ax, fy, ay, split, interp);
            }
            case "CAMERA_ORBIT" -> new CameraOrbitKeyframe(
                parseVector3d(m.get("center"), "center"),
                asFloat(m.getOrDefault("distance", 10.0), "distance"),
                asFloat(m.getOrDefault("yaw", 0.0), "yaw"),
                asFloat(m.getOrDefault("pitch", 20.0), "pitch"),
                interp
            );
            case "TRACK_ENTITY" -> new TrackEntityKeyframe(
                UUID.fromString(String.valueOf(m.get("target"))),
                m.get("trackingBodyPart") instanceof String bp
                    ? TrackingBodyPart.valueOf(bp.toUpperCase(Locale.ROOT))
                    : TrackingBodyPart.HEAD,
                asFloat(m.getOrDefault("yawOffset", 0.0), "yawOffset"),
                asFloat(m.getOrDefault("pitchOffset", 0.0), "pitchOffset"),
                m.get("positionOffset") != null ? parseVector3d(m.get("positionOffset"), "positionOffset") : new Vector3d(),
                m.get("viewOffset") != null ? parseVector3d(m.get("viewOffset"), "viewOffset") : new Vector3d(),
                asFloat(m.getOrDefault("roll", 0.0), "roll"),
                interp
            );
            default -> throw ToolError.invalidParams("Unsupported keyframe track type: " + type);
        };
    }

    private static Map<String, Object> serializeKeyframe(int tick, Keyframe kf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick", tick);
        m.put("interpolation", kf.interpolationType() == null ? "SMOOTH" : kf.interpolationType().name());

        if (kf instanceof CameraKeyframe cam) {
            m.put("position", new Vec3(cam.position.x, cam.position.y, cam.position.z).toList());
            m.put("yaw", CameraPathMath.round2(cam.yaw));
            m.put("pitch", CameraPathMath.round2(cam.pitch));
            m.put("roll", CameraPathMath.round2(cam.roll));
        } else if (kf instanceof FOVKeyframe fov) {
            m.put("fov", CameraPathMath.round2(fov.fov));
        } else if (kf instanceof TickrateKeyframe speed) {
            float tps = ((TickrateKeyframeAccessor) speed).flashbackDirector$getTickrate();
            m.put("speed", CameraPathMath.round2(tps / 20.0));
        } else if (kf instanceof TimelapseKeyframe tl) {
            m.put("ticks", tl.ticks);
        } else if (kf instanceof TimeOfDayKeyframe tod) {
            m.put("time", tod.time);
        } else if (kf instanceof FreezeKeyframe fr) {
            FreezeKeyframeAccessor acc = (FreezeKeyframeAccessor) fr;
            m.put("freeze", acc.flashbackDirector$isFrozen());
            m.put("freezeDelay", acc.flashbackDirector$getFrozenDelay());
        } else if (kf instanceof CameraShakeKeyframe shake) {
            CameraShakeKeyframeAccessor acc = (CameraShakeKeyframeAccessor) shake;
            m.put("frequencyX", acc.flashbackDirector$getFrequencyX());
            m.put("amplitudeX", acc.flashbackDirector$getAmplitudeX());
            m.put("frequencyY", acc.flashbackDirector$getFrequencyY());
            m.put("amplitudeY", acc.flashbackDirector$getAmplitudeY());
            m.put("splitParams", acc.flashbackDirector$isSplitParams());
        } else if (kf instanceof CameraOrbitKeyframe orb) {
            m.put("center", new Vec3(orb.center.x, orb.center.y, orb.center.z).toList());
            m.put("distance", orb.distance);
            m.put("yaw", orb.yaw);
            m.put("pitch", orb.pitch);
        } else if (kf instanceof TrackEntityKeyframe te) {
            m.put("target", te.target == null ? "" : te.target.toString());
            m.put("trackingBodyPart", te.trackingBodyPart == null ? "HEAD" : te.trackingBodyPart.name());
            m.put("yawOffset", te.yawOffset);
            m.put("pitchOffset", te.pitchOffset);
            m.put("positionOffset", new Vec3(te.positionOffset.x, te.positionOffset.y, te.positionOffset.z).toList());
            m.put("viewOffset", new Vec3(te.viewOffset.x, te.viewOffset.y, te.viewOffset.z).toList());
            m.put("roll", te.roll);
        }
        return m;
    }

    private static Vector3d parseVector3d(Object raw, String key) {
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            throw ToolError.invalidParams("Field '" + key + "' must be a 3-number array [x, y, z]");
        }
        return new Vector3d(
            asDouble(list.get(0), key + "[0]"),
            asDouble(list.get(1), key + "[1]"),
            asDouble(list.get(2), key + "[2]")
        );
    }

    private static String firstEncoder(VideoCodec codec) {
        String[] encoders = codec.getEncoders();
        return (encoders != null && encoders.length > 0) ? encoders[0] : "libx264";
    }

    private static int parseBitrate(Object raw, int defaultVal) {
        if (raw == null) return defaultVal;
        if (raw instanceof Number n) return Math.max(0, n.intValue());
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("m")) {
            return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000_000.0);
        }
        if (s.endsWith("k")) {
            return (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)) * 1_000.0);
        }
        return Integer.parseInt(s);
    }

    private static boolean asBool(Object v, String key) {
        if (v instanceof Boolean b) return b;
        throw ToolError.invalidParams("Expected boolean for " + key);
    }

    private static int asInt(Object v, String key) {
        if (v instanceof Number n) return n.intValue();
        throw ToolError.invalidParams("Expected integer for " + key);
    }

    private static long asLong(Object v, String key) {
        if (v instanceof Number n) return n.longValue();
        throw ToolError.invalidParams("Expected integer for " + key);
    }

    private static float asFloat(Object v, String key) {
        if (v instanceof Number n) return n.floatValue();
        throw ToolError.invalidParams("Expected number for " + key);
    }

    private static double asDouble(Object v, String key) {
        if (v instanceof Number n) return n.doubleValue();
        throw ToolError.invalidParams("Expected number for " + key);
    }
}
