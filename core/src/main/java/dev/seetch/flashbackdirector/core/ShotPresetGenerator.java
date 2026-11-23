package dev.seetch.flashbackdirector.core;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.GeneratedShot;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ShotPresetRequest;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.YawPitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates editable Flashback keyframes for YouTuber-style cinematic B-roll presets.
 */
public final class ShotPresetGenerator {

    /**
     * Resolves a candidate camera position against world geometry.
     * Returns a clear position (original or safely offset), or null if all candidate offsets are blocked.
     */
    @FunctionalInterface
    public interface CollisionResolver {
        Vec3 resolveSafePosition(Vec3 desired, Vec3 lookTarget);
    }

    private ShotPresetGenerator() {
    }

    public static GeneratedShot generate(ShotPresetRequest req, CollisionResolver resolver) {
        if (req.endTick() <= req.startTick()) {
            throw ToolError.invalidParams("end_tick must be greater than start_tick");
        }
        String preset = req.preset() == null ? "" : req.preset().trim().toLowerCase(Locale.ROOT);
        double baseFov = req.fov() > 0 ? CameraPathMath.clamp(req.fov(), 20.0, 120.0) : 48.0;

        return switch (preset) {
            case "flyover" -> buildFlyover(req, resolver, baseFov);
            case "orbit" -> buildOrbit(req, resolver, baseFov);
            case "dolly_reveal" -> buildDollyReveal(req, resolver, baseFov);
            case "follow_parallax" -> buildFollowParallax(req, resolver, baseFov);
            case "impact_freeze" -> buildImpactFreeze(req, resolver, baseFov);
            default -> throw ToolError.invalidParams(
                "Unknown preset '" + req.preset() + "'. Valid presets: flyover, orbit, dolly_reveal, follow_parallax, impact_freeze"
            );
        };
    }

    private static GeneratedShot buildFlyover(ShotPresetRequest req, CollisionResolver resolver, double fov) {
        Vec3 target = requireTarget(req);
        double dist = req.distance() > 0 ? req.distance() : 16.0;
        double height = req.heightOffset() != 0 ? req.heightOffset() : 7.0;

        Vec3 start = req.startPos() != null ? req.startPos() : target.add(-dist, height, -dist * 0.6);
        Vec3 end = req.endPos() != null ? req.endPos() : target.add(dist, height * 0.85, dist * 0.6);
        Vec3 mid = start.lerp(end, 0.5).add(0.0, 1.5, 0.0);

        List<Vec3> rawPoints = List.of(start, mid, end);
        List<Integer> ticks = distributeTicks(req.startTick(), req.endTick(), rawPoints.size());

        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> camKeys = buildCameraTrack(rawPoints, ticks, target, 0.0, "SMOOTH", req.avoidCollisions(), resolver, unresolved);
        List<Map<String, Object>> fovKeys = List.of(
            fovKeyframe(req.startTick(), fov, "EASE_IN_OUT"),
            fovKeyframe(req.endTick(), Math.max(36.0, fov - 4.0), "EASE_IN_OUT")
        );

        return new GeneratedShot(camKeys, fovKeys, List.of(), List.of(), List.of(), unresolved);
    }

    private static GeneratedShot buildOrbit(ShotPresetRequest req, CollisionResolver resolver, double fov) {
        Vec3 target = requireTarget(req);
        double dist = req.distance() > 0 ? req.distance() : 9.0;
        double height = req.heightOffset() != 0 ? req.heightOffset() : 2.5;
        double arcDeg = req.arcDegrees() != 0 ? req.arcDegrees() : 120.0;

        int keyCount = Math.max(4, Math.min(9, (int) Math.ceil(Math.abs(arcDeg) / 35.0) + 1));
        double startAngleRad = req.startPos() != null
            ? Math.atan2(req.startPos().z() - target.z(), req.startPos().x() - target.x())
            : -Math.PI / 4.0;
        double arcRad = Math.toRadians(arcDeg);

        List<Vec3> rawPoints = new ArrayList<>(keyCount);
        for (int i = 0; i < keyCount; i++) {
            double t = (double) i / (keyCount - 1);
            double angle = startAngleRad + arcRad * t;
            double yLift = height + Math.sin(t * Math.PI) * 0.8;
            rawPoints.add(new Vec3(
                target.x() + Math.cos(angle) * dist,
                target.y() + yLift,
                target.z() + Math.sin(angle) * dist
            ));
        }

        List<Integer> ticks = distributeTicks(req.startTick(), req.endTick(), keyCount);
        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> camKeys = buildCameraTrack(rawPoints, ticks, target, 0.0, "SMOOTH", req.avoidCollisions(), resolver, unresolved);
        List<Map<String, Object>> fovKeys = List.of(
            fovKeyframe(req.startTick(), fov, "SMOOTH"),
            fovKeyframe(req.endTick(), fov, "SMOOTH")
        );

        return new GeneratedShot(camKeys, fovKeys, List.of(), List.of(), List.of(), unresolved);
    }

    private static GeneratedShot buildDollyReveal(ShotPresetRequest req, CollisionResolver resolver, double fov) {
        Vec3 target = requireTarget(req);
        double dist = req.distance() > 0 ? req.distance() : 14.0;
        double height = req.heightOffset() != 0 ? req.heightOffset() : 5.0;

        Vec3 dir = req.startPos() != null
            ? req.startPos().sub(target).normalize()
            : new Vec3(0.0, 0.0, 1.0);

        Vec3 closePos = req.startPos() != null ? req.startPos() : target.add(dir.scale(Math.max(3.5, dist * 0.3))).add(0.0, 0.4, 0.0);
        Vec3 midPos = target.add(dir.scale(dist * 0.65)).add(0.0, height * 0.55, 0.0);
        Vec3 widePos = req.endPos() != null ? req.endPos() : target.add(dir.scale(dist)).add(0.0, height, 0.0);

        List<Vec3> rawPoints = List.of(closePos, midPos, widePos);
        List<Integer> ticks = distributeTicks(req.startTick(), req.endTick(), rawPoints.size());
        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> camKeys = buildCameraTrack(rawPoints, ticks, target, 0.0, "EASE_IN_OUT", req.avoidCollisions(), resolver, unresolved);

        // Classic dolly-zoom feel: slightly narrower lens at the reveal apex.
        List<Map<String, Object>> fovKeys = List.of(
            fovKeyframe(req.startTick(), Math.min(62.0, fov + 8.0), "EASE_IN_OUT"),
            fovKeyframe(req.endTick(), Math.max(36.0, fov - 4.0), "EASE_IN_OUT")
        );

        return new GeneratedShot(camKeys, fovKeys, List.of(), List.of(), List.of(), unresolved);
    }

    private static GeneratedShot buildFollowParallax(ShotPresetRequest req, CollisionResolver resolver, double fov) {
        Vec3 target = requireTarget(req);
        double dist = req.distance() > 0 ? req.distance() : 8.5;
        double height = req.heightOffset() != 0 ? req.heightOffset() : 2.2;

        Vec3 start = req.startPos() != null ? req.startPos() : target.add(-dist, height, dist * 0.45);
        Vec3 end = req.endPos() != null ? req.endPos() : target.add(dist * 0.8, height + 1.0, -dist * 0.55);
        Vec3 mid = start.lerp(end, 0.5).add(0.0, 0.6, dist * 0.25);

        List<Vec3> rawPoints = List.of(start, mid, end);
        List<Integer> ticks = distributeTicks(req.startTick(), req.endTick(), rawPoints.size());
        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> camKeys = buildCameraTrack(rawPoints, ticks, target, 1.5, "SMOOTH", req.avoidCollisions(), resolver, unresolved);
        List<Map<String, Object>> fovKeys = List.of(
            fovKeyframe(req.startTick(), fov, "EASE_IN_OUT"),
            fovKeyframe(req.endTick(), fov, "EASE_IN_OUT")
        );

        double slow = req.slowSpeed() > 0 ? CameraPathMath.clamp(req.slowSpeed(), 0.1, 1.0) : 0.65;
        int midTick = ticks.get(1);
        List<Map<String, Object>> speedKeys = List.of(
            speedKeyframe(req.startTick(), 1.0, "EASE_IN_OUT"),
            speedKeyframe(midTick, slow, "EASE_IN_OUT"),
            speedKeyframe(req.endTick(), 1.0, "EASE_IN_OUT")
        );

        return new GeneratedShot(camKeys, fovKeys, speedKeys, List.of(), List.of(), unresolved);
    }

    private static GeneratedShot buildImpactFreeze(ShotPresetRequest req, CollisionResolver resolver, double fov) {
        Vec3 target = requireTarget(req);
        double dist = req.distance() > 0 ? req.distance() : 7.0;
        double height = req.heightOffset() != 0 ? req.heightOffset() : 1.6;
        double slow = req.slowSpeed() > 0 ? CameraPathMath.clamp(req.slowSpeed(), 0.05, 0.8) : 0.2;
        int freezeTicks = Math.max(0, req.freezeTicks());

        int span = req.endTick() - req.startTick();
        int impactTick = req.startTick() + (int) Math.round(span * 0.45);
        int releaseTick = Math.min(req.endTick() - 1, impactTick + Math.max(4, span / 4));

        Vec3 pStart = req.startPos() != null ? req.startPos() : target.add(-dist * 1.15, height + 1.2, dist * 0.7);
        Vec3 pImpact = target.add(-dist * 0.45, height * 0.7, dist * 0.55);
        Vec3 pSwing = target.add(dist * 0.55, height * 0.9, dist * 0.45);
        Vec3 pEnd = req.endPos() != null ? req.endPos() : target.add(dist * 0.95, height + 1.5, -dist * 0.4);

        List<Vec3> rawPoints = List.of(pStart, pImpact, pSwing, pEnd);
        List<Integer> ticks = List.of(req.startTick(), impactTick, releaseTick, req.endTick());

        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> camKeys = buildCameraTrack(rawPoints, ticks, target, 2.0, "EASE_IN_OUT", req.avoidCollisions(), resolver, unresolved);

        List<Map<String, Object>> fovKeys = List.of(
            fovKeyframe(req.startTick(), Math.min(60.0, fov + 4.0), "EASE_IN"),
            fovKeyframe(impactTick, Math.max(32.0, fov - 10.0), "EASE_OUT"),
            fovKeyframe(releaseTick, Math.max(36.0, fov - 6.0), "EASE_IN_OUT"),
            fovKeyframe(req.endTick(), fov, "EASE_OUT")
        );

        List<Map<String, Object>> speedKeys = List.of(
            speedKeyframe(req.startTick(), 1.0, "EASE_IN"),
            speedKeyframe(impactTick, slow, "HOLD"),
            speedKeyframe(releaseTick, slow, "EASE_OUT"),
            speedKeyframe(req.endTick(), 1.0, "LINEAR")
        );

        List<Map<String, Object>> freezeKeys = freezeTicks > 0
            ? List.of(
                freezeKeyframe(impactTick, true, freezeTicks),
                freezeKeyframe(Math.min(req.endTick(), impactTick + 1), false, 0)
            )
            : List.of();

        List<Map<String, Object>> shakeKeys = List.of(
            shakeKeyframe(Math.max(req.startTick(), impactTick - 2), 0.0, 0.0),
            shakeKeyframe(impactTick, 14.0, 0.35),
            shakeKeyframe(releaseTick, 0.0, 0.0)
        );

        return new GeneratedShot(camKeys, fovKeys, speedKeys, freezeKeys, shakeKeys, unresolved);
    }

    private static List<Map<String, Object>> buildCameraTrack(
        List<Vec3> rawPoints,
        List<Integer> ticks,
        Vec3 target,
        double maxRoll,
        String interpolation,
        boolean avoidCollisions,
        CollisionResolver resolver,
        List<Map<String, Object>> unresolvedOut
    ) {
        List<Map<String, Object>> keys = new ArrayList<>(rawPoints.size());
        Double prevYaw = null;

        for (int i = 0; i < rawPoints.size(); i++) {
            Vec3 desired = rawPoints.get(i);
            Vec3 finalPos = desired;

            if (avoidCollisions && resolver != null) {
                Vec3 resolved = resolver.resolveSafePosition(desired, target);
                if (resolved != null) {
                    finalPos = resolved;
                } else {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("index", i);
                    issue.put("tick", ticks.get(i));
                    issue.put("desired_position", desired.toList());
                    issue.put("reason", "All candidate safe offsets around this waypoint are blocked by solid blocks");
                    unresolvedOut.add(issue);
                }
            }

            YawPitch aim = CameraPathMath.lookAt(finalPos, target);
            double yaw = prevYaw == null ? aim.yaw() : CameraPathMath.unwrapYaw(prevYaw, aim.yaw());
            prevYaw = yaw;

            double roll = (maxRoll != 0.0 && i > 0 && i < rawPoints.size() - 1) ? maxRoll : 0.0;

            Map<String, Object> kf = new LinkedHashMap<>();
            kf.put("tick", ticks.get(i));
            kf.put("position", finalPos.toList());
            kf.put("yaw", yaw);
            kf.put("pitch", aim.pitch());
            kf.put("roll", roll);
            kf.put("interpolation", interpolation);
            keys.add(kf);
        }
        return keys;
    }

    private static Vec3 requireTarget(ShotPresetRequest req) {
        if (req.target() == null) {
            throw ToolError.invalidParams("target [x, y, z] is required for shot preset '" + req.preset() + "'");
        }
        return req.target();
    }

    private static List<Integer> distributeTicks(int startTick, int endTick, int count) {
        List<Integer> ticks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = (double) i / (count - 1);
            int tick = startTick + (int) Math.round((endTick - startTick) * t);
            if (!ticks.isEmpty() && tick <= ticks.getLast()) {
                tick = Math.min(endTick, ticks.getLast() + 1);
            }
            ticks.add(tick);
        }
        ticks.set(count - 1, endTick);
        return ticks;
    }

    private static Map<String, Object> fovKeyframe(int tick, double fov, String interpolation) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("tick", tick);
        kf.put("fov", CameraPathMath.round2(fov));
        kf.put("interpolation", interpolation);
        return kf;
    }

    private static Map<String, Object> speedKeyframe(int tick, double speed, String interpolation) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("tick", tick);
        kf.put("speed", CameraPathMath.round2(speed));
        kf.put("interpolation", interpolation);
        return kf;
    }

    private static Map<String, Object> freezeKeyframe(int tick, boolean freeze, int freezeDelay) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("tick", tick);
        kf.put("freeze", freeze);
        kf.put("freezeDelay", freezeDelay);
        kf.put("interpolation", "HOLD");
        return kf;
    }

    private static Map<String, Object> shakeKeyframe(int tick, double freq, double amp) {
        Map<String, Object> kf = new LinkedHashMap<>();
        kf.put("tick", tick);
        kf.put("frequencyX", freq);
        kf.put("amplitudeX", amp);
        kf.put("frequencyY", freq);
        kf.put("amplitudeY", amp);
        kf.put("interpolation", "EASE_OUT");
        return kf;
    }
}
