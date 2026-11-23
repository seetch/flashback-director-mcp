package dev.seetch.flashbackdirector.core;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.YawPitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure camera math following Minecraft's coordinate conventions (eye height +1.62, yaw 0 = +Z south).
 */
public final class CameraPathMath {

    public static final double CAMERA_EYE_HEIGHT = 1.62;

    private CameraPathMath() {
    }

    /**
     * Computes Minecraft yaw and pitch so a camera entity at cameraBase looks directly at target.
     */
    public static YawPitch lookAt(Vec3 cameraBase, Vec3 target) {
        double dx = target.x() - cameraBase.x();
        double dy = target.y() - (cameraBase.y() + CAMERA_EYE_HEIGHT);
        double dz = target.z() - cameraBase.z();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.max(horiz, 1e-6)));
        pitch = clamp(pitch, -89.9, 89.9);
        return new YawPitch(round2(yaw), round2(pitch));
    }

    /**
     * Unwraps yaw relative to previousYaw so camera splines take the shortest angular arc.
     */
    public static double unwrapYaw(double previousYaw, double nextYaw) {
        double delta = ((nextYaw - previousYaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return round2(previousYaw + delta);
    }

    /**
     * Samples a centripetal Catmull-Rom spline through controlPoints.
     */
    public static List<Vec3> sampleCatmullRom(List<Vec3> controlPoints, int segmentsPerSpan) {
        if (controlPoints == null || controlPoints.size() < 2) {
            return controlPoints == null ? List.of() : List.copyOf(controlPoints);
        }
        int steps = Math.max(1, segmentsPerSpan);
        List<Vec3> out = new ArrayList<>();
        int n = controlPoints.size();

        for (int i = 0; i < n - 1; i++) {
            Vec3 p0 = controlPoints.get(Math.max(0, i - 1));
            Vec3 p1 = controlPoints.get(i);
            Vec3 p2 = controlPoints.get(i + 1);
            Vec3 p3 = controlPoints.get(Math.min(n - 1, i + 2));

            int maxS = (i == n - 2) ? steps : steps - 1;
            for (int s = 0; s <= maxS; s++) {
                double t = (double) s / steps;
                out.add(catmullRomPoint(p0, p1, p2, p3, t));
            }
        }
        return out;
    }

    public static Vec3 catmullRomPoint(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double x = 0.5 * ((2.0 * p1.x())
            + (-p0.x() + p2.x()) * t
            + (2.0 * p0.x() - 5.0 * p1.x() + 4.0 * p2.x() - p3.x()) * t2
            + (-p0.x() + 3.0 * p1.x() - 3.0 * p2.x() + p3.x()) * t3);
        double y = 0.5 * ((2.0 * p1.y())
            + (-p0.y() + p2.y()) * t
            + (2.0 * p0.y() - 5.0 * p1.y() + 4.0 * p2.y() - p3.y()) * t2
            + (-p0.y() + 3.0 * p1.y() - 3.0 * p2.y() + p3.y()) * t3);
        double z = 0.5 * ((2.0 * p1.z())
            + (-p0.z() + p2.z()) * t
            + (2.0 * p0.z() - 5.0 * p1.z() + 4.0 * p2.z() - p3.z()) * t2
            + (-p0.z() + 3.0 * p1.z() - 3.0 * p2.z() + p3.z()) * t3);
        return new Vec3(x, y, z);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
