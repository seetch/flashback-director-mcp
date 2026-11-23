# Flashback 0.43.6 (Minecraft 26.2) - Verified API Notes

Extracted via JDK 25 `javap -p -c` from `libs/Flashback-0.43.6-for-MC26.2.jar`.

## Entry Points & Replay Server
- `com.moulberry.flashback.Flashback`:
  - `getReplayFolder() -> Path`
  - `openReplayWorld(Path)`
  - `getReplayServer() -> ReplayServer`
  - `isInReplay() -> boolean`
  - `isExporting() -> boolean`
  - `EXPORT_JOB` (public static `ExportJob`)
  - `getConfig() -> FlashbackConfigV1`
- `com.moulberry.flashback.playback.ReplayServer`:
  - `goToReplayTick(int)`, `getReplayTick()`, `getTotalReplayTicks()`, `isReady()`
  - `replayPaused` (public volatile boolean)
  - `forceApplyKeyframes` (`AtomicBoolean`)
  - `getDesiredTickRate(boolean manual)` / `setDesiredTickRate(float tps, boolean manual)` (TPS = `speed * 20.0f`)
  - `getEditorState() -> EditorState`, `getMetadata() -> FlashbackMeta`

## Timeline, Scenes & Undo History
- `com.moulberry.flashback.state.EditorState`:
  - `acquireRead()` / `acquireWrite()` / `release(long)` (StampedLock)
  - `getScenes(long)`, `getSceneIndex()`, `setSceneIndex(int, long)`, `getCurrentScene(long)`
  - `markDirty()`, `applyKeyframes(KeyframeHandler, float)`
  - `setExportTicks(int start, int end, int totalTicks)`, `getExportStartAndEnd()`, `getFirstAndLastTicksInTracks()`
  - `replayVisuals` (public `ReplayVisuals`)
- `com.moulberry.flashback.state.EditorScene`:
  - `name`, `keyframeTracks` (`List<KeyframeTrack>`), `exportStartTicks`, `exportEndTicks`
  - `push(EditorSceneHistoryEntry)`, `undo(Consumer<String>)`, `redo(Consumer<String>)`
- `com.moulberry.flashback.state.KeyframeTrack`:
  - `new KeyframeTrack(KeyframeType<?>)`, `keyframeType`, `keyframesByTick` (`TreeMap<Integer, Keyframe>`), `enabled`, `customName`

## Keyframes & Units
- Types (`*.INSTANCE`): `CameraKeyframeType`, `FOVKeyframeType`, `SpeedKeyframeType`, `TimelapseKeyframeType`, `TimeOfDayKeyframeType`, `FreezeKeyframeType`, `CameraShakeKeyframeType`, `CameraOrbitKeyframeType`, `TrackEntityKeyframeType`.
- `TickrateKeyframe`: stores `tickrate` in TPS (`1.0x speed = 20.0f`), private field -> Mixin `@Accessor`.
- `FreezeKeyframe`: private `frozen` and `frozenDelay` -> Mixin `@Accessor`.
- `CameraShakeKeyframe`: private `frequencyX`, `amplitudeX`, `frequencyY`, `amplitudeY`, `splitParams` -> Mixin `@Accessor`.
- `CameraKeyframe`, `FOVKeyframe`, `TimelapseKeyframe`, `TimeOfDayKeyframe`, `CameraOrbitKeyframe`, `TrackEntityKeyframe`: public fields + public constructors.

## Export Pipeline
- `ExportSettings` (25-component Java record), defaults readable from `Flashback.getConfig().internalExport`.
- Start export: `Flashback.EXPORT_JOB = new ExportJob(settings);`.
- Track progress: `Flashback.isExporting()`, `Flashback.EXPORT_JOB.progressCount`, `Flashback.EXPORT_JOB.progressOutOf`.
