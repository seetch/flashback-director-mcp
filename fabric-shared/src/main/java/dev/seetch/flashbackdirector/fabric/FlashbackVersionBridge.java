package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Version-specific seam isolating internal Flashback 0.43.x editor calls per Minecraft version (26.2 / 26.3).
 * All methods are invoked on the Minecraft client thread via ClientThreadExecutor.
 */
public interface FlashbackVersionBridge {

    String minecraftVersion();

    String modVersion();

    String flashbackVersion();

    boolean isInReplay();

    boolean isExporting();

    boolean isFullscreen();

    Map<String, Object> buildStatus();

    Map<String, Object> listReplays();

    Map<String, Object> openReplay(String name, String path);

    Map<String, Object> closeReplay();

    Map<String, Object> playback(Boolean paused, Double speed);

    int requestSeek(Integer tick, Double seconds, boolean applyKeyframes);

    boolean isSeekSettled(int targetTick);

    Map<String, Object> finalizeSeek(int targetTick, boolean applyKeyframes);

    int currentTick();

    int totalTicks();

    Map<String, Object> setCamera(Double x, Double y, Double z, Double yaw, Double pitch, Vec3 lookAt, Double roll, Double fov);

    Map<String, Object> getTimeline(boolean includeKeyframes, Integer trackIndex, Integer fromTick, Integer toTick);

    Map<String, Object> manageScene(String action, Integer index, String name, boolean selectAfterAdd);

    Map<String, Object> manageTrack(String action, Integer trackIndex, String trackType, String name, Boolean enabled);

    Map<String, Object> setKeyframes(Integer trackIndex, String trackType, List<Map<String, Object>> keyframes, boolean clearExisting);

    Map<String, Object> removeKeyframes(Integer trackIndex, String trackType, List<Integer> ticks, Integer fromTick, Integer toTick, boolean all);

    Map<String, Object> history(String action);

    Map<String, Object> getVisuals();

    Map<String, Object> setVisuals(Map<String, Object> values);

    Map<String, Object> setExportRange(Integer startTick, Integer endTick, boolean clear);

    Map<String, Object> exportVideo(Map<String, Object> options);
}
