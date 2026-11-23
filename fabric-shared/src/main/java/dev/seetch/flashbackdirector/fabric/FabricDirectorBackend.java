package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.DirectorBackend;
import dev.seetch.flashbackdirector.core.HighlightScorer;
import dev.seetch.flashbackdirector.core.ShotPresetGenerator;
import dev.seetch.flashbackdirector.core.ToolError;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.GeneratedShot;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.HighlightCandidate;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ReplaySample;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ShotPresetRequest;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared DirectorBackend implementation connecting core tool dispatch, world geometry checks,
 * Blaze3D screenshots, and the version-specific FlashbackVersionBridge.
 */
public final class FabricDirectorBackend implements DirectorBackend {

    private static final double DEFAULT_CLEARANCE = 0.45;
    private final FlashbackVersionBridge fb;

    public FabricDirectorBackend(FlashbackVersionBridge versionBridge) {
        this.fb = versionBridge;
    }

    @Override
    public Map<String, Object> status() {
        return ClientThreadExecutor.runSync(fb::buildStatus, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_status");
    }

    @Override
    public Map<String, Object> listReplays() {
        return ClientThreadExecutor.runSync(fb::listReplays, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_list_replays");
    }

    @Override
    public Map<String, Object> openReplay(String name, String path) {
        return ClientThreadExecutor.runSync(
            () -> fb.openReplay(name, path),
            ClientThreadExecutor.LONG_OPERATION_TIMEOUT_MS,
            "director_open_replay"
        );
    }

    @Override
    public Map<String, Object> closeReplay() {
        return ClientThreadExecutor.runSync(fb::closeReplay, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_close_replay");
    }

    @Override
    public Map<String, Object> playback(Boolean paused, Double speed) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.playback(paused, speed);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_playback");
    }

    @Override
    public Map<String, Object> seek(Integer tick, Double seconds, boolean applyKeyframes) {
        return performSeekAndWait(tick, seconds, applyKeyframes, 15_000L);
    }

    private Map<String, Object> performSeekAndWait(Integer tick, Double seconds, boolean applyKeyframes, long timeoutMs) {
        int targetTick = ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.requestSeek(tick, seconds, applyKeyframes);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_seek request");

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean settled = ClientThreadExecutor.runSync(() -> {
                requireReplay();
                return fb.isSeekSettled(targetTick);
            }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_seek poll");
            if (settled) {
                break;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ToolError.timeout("Interrupted while waiting for replay seek to tick " + targetTick);
            }
        }

        // Allow 2 client ticks for packet delivery and entity position interpolation.
        try {
            Thread.sleep(75L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.finalizeSeek(targetTick, applyKeyframes);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_seek finalize");
    }

    @Override
    public Map<String, Object> listEntities(boolean playersOnly, double radius, int limit) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return WorldGeometryInspector.listEntities(playersOnly, radius, limit);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_list_entities");
    }

    @Override
    public Map<String, Object> scanHighlights(
        Integer fromTick,
        Integer toTick,
        int stepTicks,
        int maxSamples,
        int limit,
        String focusPlayer
    ) {
        int[] bounds = ClientThreadExecutor.runSync(() -> {
            requireReplay();
            int total = fb.totalTicks();
            int orig = fb.currentTick();
            int start = fromTick == null ? 0 : Math.max(0, Math.min(fromTick, total));
            int end = toTick == null ? total : Math.max(start, Math.min(toTick, total));
            return new int[]{start, end, orig, total};
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "scan_highlights bounds");

        int start = bounds[0];
        int end = bounds[1];
        int originalTick = bounds[2];
        int safeStep = Math.max(5, stepTicks);
        int safeMaxSamples = Math.max(2, Math.min(maxSamples, 200));

        int span = Math.max(1, end - start);
        if (span / safeStep > safeMaxSamples) {
            safeStep = Math.max(safeStep, (int) Math.ceil((double) span / safeMaxSamples));
        }

        List<ReplaySample> samples = new ArrayList<>();
        try {
            for (int t = start; t <= end && samples.size() < safeMaxSamples; t += safeStep) {
                final int targetTick = t;
                performSeekAndWait(targetTick, null, false, 8_000L);
                ReplaySample sample = ClientThreadExecutor.runSync(() -> {
                    requireReplay();
                    return WorldGeometryInspector.sampleCurrentTick(targetTick, focusPlayer);
                }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "scan_highlights sample tick " + targetTick);
                if (sample != null) {
                    samples.add(sample);
                }
            }
        } finally {
            // Restore original tick after scouting.
            if (ClientThreadExecutor.runSync(fb::isInReplay, 5_000L, "check replay before restore")) {
                performSeekAndWait(originalTick, null, true, 8_000L);
            }
        }

        List<HighlightCandidate> ranked = HighlightScorer.rankSamples(samples, limit);
        List<Map<String, Object>> highlightMaps = ranked.stream().map(HighlightCandidate::toMap).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanned_from_tick", start);
        response.put("scanned_to_tick", end);
        response.put("step_ticks", safeStep);
        response.put("samples_collected", samples.size());
        response.put("highlights", highlightMaps);
        return response;
    }

    @Override
    public Map<String, Object> checkPath(List<Vec3> points, Vec3 lookAtTarget, double clearance, boolean suggestSafeOffsets) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return WorldGeometryInspector.checkPath(points, lookAtTarget, clearance, suggestSafeOffsets);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_check_path");
    }

    @Override
    public Map<String, Object> screenshot(
        Integer tick,
        Double seconds,
        int width,
        int height,
        String outputPath,
        Boolean noGui,
        boolean ssaa,
        boolean applyKeyframes
    ) {
        if (tick != null || seconds != null) {
            performSeekAndWait(tick, seconds, applyKeyframes, 15_000L);
        } else {
            ClientThreadExecutor.runSync(() -> {
                requireReplay();
                return null;
            }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "screenshot check");
        }
        Map<String, Object> prevHud = null;
        if (Boolean.TRUE.equals(noGui)) {
            prevHud = ClientThreadExecutor.runSync(() -> {
                Map<String, Object> current = fb.getVisuals();
                Map<String, Object> saved = new LinkedHashMap<>();
                for (String key : List.of("showChat", "showBossBar", "showTitleText", "showScoreboard", "showActionBar", "showHotbar")) {
                    if (current.containsKey(key)) {
                        saved.put(key, current.get(key));
                    }
                }
                Map<String, Object> hidden = new LinkedHashMap<>();
                for (String key : saved.keySet()) {
                    hidden.put(key, false);
                }
                fb.setVisuals(hidden);
                return saved;
            }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "hide hud for screenshot");
        }
        try {
            return SafeScreenshotCapturer.capture(fb, width, height, outputPath, noGui);
        } finally {
            if (prevHud != null && !prevHud.isEmpty()) {
                final Map<String, Object> toRestore = prevHud;
                ClientThreadExecutor.runSync(() -> {
                    if (fb.isInReplay()) {
                        fb.setVisuals(toRestore);
                    }
                    return null;
                }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "restore hud after screenshot");
            }
        }
    }

    @Override
    public Map<String, Object> setCamera(Double x, Double y, Double z, Double yaw, Double pitch, Vec3 lookAt, Double roll, Double fov) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.setCamera(x, y, z, yaw, pitch, lookAt, roll, fov);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_set_camera");
    }

    @Override
    public Map<String, Object> getTimeline(boolean includeKeyframes, Integer trackIndex, Integer fromTick, Integer toTick) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.getTimeline(includeKeyframes, trackIndex, fromTick, toTick);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_get_timeline");
    }

    @Override
    public Map<String, Object> manageScene(String action, Integer index, String name, boolean selectAfterAdd) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.manageScene(action, index, name, selectAfterAdd);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_manage_scene");
    }

    @Override
    public Map<String, Object> manageTrack(String action, Integer trackIndex, String trackType, String name, Boolean enabled) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.manageTrack(action, trackIndex, trackType, name, enabled);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_manage_track");
    }

    @Override
    public Map<String, Object> setKeyframes(Integer trackIndex, String trackType, List<Map<String, Object>> keyframes, boolean clearExisting) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.setKeyframes(trackIndex, trackType, keyframes, clearExisting);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_set_keyframes");
    }

    @Override
    public Map<String, Object> removeKeyframes(Integer trackIndex, String trackType, List<Integer> ticks, Integer fromTick, Integer toTick, boolean all) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.removeKeyframes(trackIndex, trackType, ticks, fromTick, toTick, all);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_remove_keyframes");
    }

    @Override
    public Map<String, Object> history(String action) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.history(action);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_history");
    }

    @Override
    public Map<String, Object> getVisuals() {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.getVisuals();
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_get_visuals");
    }

    @Override
    public Map<String, Object> setVisuals(Map<String, Object> values) {
        Map<?, ?> skinMap = extractSkinMap(values, "skinOverrides", "skin_overrides");
        Map<String, Object> resolvedProfiles = resolveSkinsFromOption(values, "skinOverrides", "skin_overrides");
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            if (skinMap != null && !skinMap.isEmpty()) {
                fb.configureEntities(Map.of("skin_overrides", skinMap), resolvedProfiles);
            }
            return fb.setVisuals(values);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_set_visuals");
    }

    @Override
    public Map<String, Object> configureEntities(Map<String, Object> options) {
        Map<String, Object> resolvedProfiles = resolveSkinsFromOption(options, "skin_overrides", "skinOverrides");
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.configureEntities(options == null ? Map.of() : options, resolvedProfiles);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_configure_entities");
    }

    private Map<String, Object> resolveSkinsFromOption(Map<String, Object> options, String key1, String key2) {
        Map<String, Object> resolvedProfiles = new LinkedHashMap<>();
        Map<?, ?> skinMap = extractSkinMap(options, key1, key2);
        if (skinMap != null) {
            for (Map.Entry<?, ?> entry : skinMap.entrySet()) {
                Object rawValue = entry.getValue();
                if (rawValue instanceof String s && !s.isBlank()) {
                    String trimmed = s.trim();
                    if (!resolvedProfiles.containsKey(trimmed)) {
                        resolvedProfiles.put(trimmed, fb.resolveSkinProfile(trimmed));
                    }
                }
            }
        }
        return resolvedProfiles;
    }

    private static Map<?, ?> extractSkinMap(Map<String, Object> options, String key1, String key2) {
        if (options == null) {
            return null;
        }
        if (options.get(key1) instanceof Map<?, ?> m1) {
            return m1;
        }
        if (options.get(key2) instanceof Map<?, ?> m2) {
            return m2;
        }
        return null;
    }

    @Override
    public Map<String, Object> createShot(ShotPresetRequest request) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            Minecraft mc = Minecraft.getInstance();
            ShotPresetGenerator.CollisionResolver resolver = (desired, target) ->
                mc.level == null ? desired : WorldGeometryInspector.resolveSafePosition(mc.level, desired, target, DEFAULT_CLEARANCE);

            GeneratedShot shot = ShotPresetGenerator.generate(request, resolver);

            if (!shot.cameraKeyframes().isEmpty()) {
                fb.setKeyframes(null, "CAMERA", shot.cameraKeyframes(), request.clearExisting());
            }
            if (!shot.fovKeyframes().isEmpty()) {
                fb.setKeyframes(null, "FOV", shot.fovKeyframes(), request.clearExisting());
            }
            if (!shot.speedKeyframes().isEmpty()) {
                fb.setKeyframes(null, "SPEED", shot.speedKeyframes(), request.clearExisting());
            }
            if (!shot.freezeKeyframes().isEmpty()) {
                fb.setKeyframes(null, "FREEZE", shot.freezeKeyframes(), request.clearExisting());
            }
            if (!shot.shakeKeyframes().isEmpty()) {
                fb.setKeyframes(null, "CAMERA_SHAKE", shot.shakeKeyframes(), request.clearExisting());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("preset", request.preset());
            response.put("start_tick", request.startTick());
            response.put("end_tick", request.endTick());
            response.put("camera_keyframes", shot.cameraKeyframes().size());
            response.put("fov_keyframes", shot.fovKeyframes().size());
            response.put("speed_keyframes", shot.speedKeyframes().size());
            response.put("freeze_keyframes", shot.freezeKeyframes().size());
            response.put("shake_keyframes", shot.shakeKeyframes().size());
            response.put("unresolved_collisions", shot.unresolvedCollisions());
            return response;
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_create_shot");
    }

    @Override
    public Map<String, Object> setExportRange(Integer startTick, Integer endTick, boolean clear) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            return fb.setExportRange(startTick, endTick, clear);
        }, ClientThreadExecutor.DEFAULT_TIMEOUT_MS, "director_set_export_range");
    }

    @Override
    public Map<String, Object> exportVideo(Map<String, Object> options) {
        return ClientThreadExecutor.runSync(() -> {
            requireReplay();
            if (fb.isFullscreen()) {
                throw ToolError.fullscreenUnsupported();
            }
            return fb.exportVideo(options);
        }, ClientThreadExecutor.LONG_OPERATION_TIMEOUT_MS, "director_export_video");
    }

    private void requireReplay() {
        if (!fb.isInReplay()) {
            throw ToolError.notInReplay("No Flashback replay is currently open. Call director_open_replay first.");
        }
    }
}
