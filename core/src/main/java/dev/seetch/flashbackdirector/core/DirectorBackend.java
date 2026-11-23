package dev.seetch.flashbackdirector.core;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ShotPresetRequest;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Contract implemented by the Fabric layer to execute all 23 director_* MCP tools.
 */
public interface DirectorBackend {

    // Session & Replay (6)
    Map<String, Object> status();

    Map<String, Object> listReplays();

    Map<String, Object> openReplay(String name, String path);

    Map<String, Object> closeReplay();

    Map<String, Object> playback(Boolean paused, Double speed);

    Map<String, Object> seek(Integer tick, Double seconds, boolean applyKeyframes);

    // Spatial Scouting & Highlight Discovery (4)
    Map<String, Object> listEntities(boolean playersOnly, double radius, int limit);

    Map<String, Object> scanHighlights(Integer fromTick, Integer toTick, int stepTicks, int maxSamples, int limit, String focusPlayer);

    Map<String, Object> checkPath(List<Vec3> points, Vec3 lookAtTarget, double clearance, boolean suggestSafeOffsets);

    Map<String, Object> screenshot(Integer tick, Double seconds, int width, int height, String outputPath, Boolean noGui, boolean ssaa, boolean applyKeyframes);

    // Camera, Timeline & Visuals (9)
    Map<String, Object> setCamera(Double x, Double y, Double z, Double yaw, Double pitch, Vec3 lookAt, Double roll, Double fov);

    Map<String, Object> getTimeline(boolean includeKeyframes, Integer trackIndex, Integer fromTick, Integer toTick);

    Map<String, Object> manageScene(String action, Integer index, String name, boolean selectAfterAdd);

    Map<String, Object> manageTrack(String action, Integer trackIndex, String trackType, String name, Boolean enabled);

    Map<String, Object> setKeyframes(Integer trackIndex, String trackType, List<Map<String, Object>> keyframes, boolean clearExisting);

    Map<String, Object> removeKeyframes(Integer trackIndex, String trackType, List<Integer> ticks, Integer fromTick, Integer toTick, boolean all);

    Map<String, Object> history(String action);

    Map<String, Object> getVisuals();

    Map<String, Object> setVisuals(Map<String, Object> values);

    Map<String, Object> configureEntities(Map<String, Object> options);

    // Cinematic Macros & Export (3)
    Map<String, Object> createShot(ShotPresetRequest request);

    Map<String, Object> setExportRange(Integer startTick, Integer endTick, boolean clear);

    Map<String, Object> exportVideo(Map<String, Object> options);
}
