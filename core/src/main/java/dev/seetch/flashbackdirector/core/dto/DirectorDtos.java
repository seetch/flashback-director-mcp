package dev.seetch.flashbackdirector.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Immutable data transfer objects shared across the HTTP bridge, math engine, and Fabric adapters.
 */
public final class DirectorDtos {

    private DirectorDtos() {
    }

    public record Vec3(double x, double y, double z) {
        public Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        public Vec3 add(double dx, double dy, double dz) {
            return new Vec3(x + dx, y + dy, z + dz);
        }

        public Vec3 sub(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        public Vec3 scale(double factor) {
            return new Vec3(x * factor, y * factor, z * factor);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public double distanceTo(Vec3 other) {
            return sub(other).length();
        }

        public Vec3 normalize() {
            double len = length();
            if (len < 1e-9) {
                return new Vec3(0.0, 0.0, 1.0);
            }
            return new Vec3(x / len, y / len, z / len);
        }

        public Vec3 lerp(Vec3 other, double t) {
            return new Vec3(
                x + (other.x - x) * t,
                y + (other.y - y) * t,
                z + (other.z - z) * t
            );
        }

        public List<Double> toList() {
            return List.of(round3(x), round3(y), round3(z));
        }

        private static double round3(double v) {
            return Math.round(v * 1000.0) / 1000.0;
        }
    }

    public record YawPitch(double yaw, double pitch) {
    }

    public record ReplaySample(
        int tick,
        Vec3 position,
        Vec3 velocity,
        boolean onGround,
        boolean hurt,
        int nearbyEntityCount,
        String focusEntity
    ) {
    }

    public record HighlightCandidate(
        int tick,
        double seconds,
        double score,
        List<String> reasons,
        Vec3 position,
        String focusEntity
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                "tick", tick,
                "seconds", Math.round(seconds * 100.0) / 100.0,
                "score", Math.round(score * 100.0) / 100.0,
                "reasons", reasons,
                "position", position.toList(),
                "focus_entity", focusEntity == null ? "" : focusEntity
            );
        }
    }

    public record ShotPresetRequest(
        String preset,
        int startTick,
        int endTick,
        Vec3 target,
        Vec3 startPos,
        Vec3 endPos,
        double distance,
        double heightOffset,
        double arcDegrees,
        double fov,
        double slowSpeed,
        int freezeTicks,
        boolean avoidCollisions,
        boolean clearExisting
    ) {
    }

    public record GeneratedShot(
        List<Map<String, Object>> cameraKeyframes,
        List<Map<String, Object>> fovKeyframes,
        List<Map<String, Object>> speedKeyframes,
        List<Map<String, Object>> freezeKeyframes,
        List<Map<String, Object>> shakeKeyframes,
        List<Map<String, Object>> unresolvedCollisions
    ) {
    }
}
