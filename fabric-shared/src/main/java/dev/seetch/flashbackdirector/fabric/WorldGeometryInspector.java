package dev.seetch.flashbackdirector.fabric;

import dev.seetch.flashbackdirector.core.CameraPathMath;
import dev.seetch.flashbackdirector.core.ToolError;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ReplaySample;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inspects loaded world geometry (block collisions, line-of-sight) and entities for safe cinematic framing.
 */
public final class WorldGeometryInspector {

    private static final double DEFAULT_CLEARANCE = 0.45;
    private static final double[][] OFFSET_PROBES = {
        {0.0, 1.0, 0.0},
        {0.0, 2.0, 0.0},
        {0.0, 3.0, 0.0},
        {1.0, 0.5, 0.0},
        {-1.0, 0.5, 0.0},
        {0.0, 0.5, 1.0},
        {0.0, 0.5, -1.0},
        {1.5, 1.5, 1.5},
        {-1.5, 1.5, -1.5},
        {1.5, 1.5, -1.5},
        {-1.5, 1.5, 1.5},
        {0.0, -1.0, 0.0},
        {0.0, 4.5, 0.0},
    };

    private WorldGeometryInspector() {
    }

    public static Map<String, Object> listEntities(boolean playersOnly, double radius, int limit) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            throw ToolError.notInReplay("No loaded world. Open a replay first.");
        }
        Entity cam = mc.getCameraEntity();
        net.minecraft.world.phys.Vec3 origin = cam != null
            ? cam.position()
            : mc.gameRenderer.mainCamera().position();

        double clampedRadius = Math.max(1.0, Math.min(radius, 512.0));
        int clampedLimit = Math.max(1, Math.min(limit, 200));

        AABB box = new AABB(
            origin.x - clampedRadius, origin.y - clampedRadius, origin.z - clampedRadius,
            origin.x + clampedRadius, origin.y + clampedRadius, origin.z + clampedRadius
        );

        List<Entity> candidates = level.getEntities(cam, box, entity -> {
            if (entity.isSpectator()) {
                return false;
            }
            return !playersOnly || entity instanceof Player;
        });

        candidates.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(origin)));

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (int i = 0; i < Math.min(candidates.size(), clampedLimit); i++) {
            Entity entity = candidates.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entity.getId());
            item.put("uuid", entity.getStringUUID());
            item.put("type", entity.getType().toShortString());
            item.put("name", entity.getName().getString());
            item.put("is_player", entity instanceof Player);
            item.put("position", new Vec3(entity.getX(), entity.getY(), entity.getZ()).toList());
            item.put("eye_y", CameraPathMath.round2(entity.getEyeY()));
            item.put("velocity", new Vec3(entity.getDeltaMovement().x, entity.getDeltaMovement().y, entity.getDeltaMovement().z).toList());
            item.put("yaw", CameraPathMath.round2(entity.getYRot()));
            item.put("pitch", CameraPathMath.round2(entity.getXRot()));
            item.put("distance", CameraPathMath.round2(entity.position().distanceTo(origin)));
            resultList.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", resultList.size());
        response.put("entities", resultList);
        return response;
    }

    public static ReplaySample sampleCurrentTick(int tick, String focusPlayer) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return null;
        }
        Entity cam = mc.getCameraEntity();
        List<? extends Player> players = level.players();
        Player target = null;
        for (Player p : players) {
            if (p == cam || p.isSpectator()) {
                continue;
            }
            if (focusPlayer != null && !focusPlayer.isBlank()) {
                if (p.getName().getString().equalsIgnoreCase(focusPlayer)) {
                    target = p;
                    break;
                }
            } else if (target == null) {
                target = p;
            }
        }
        if (target == null) {
            return null;
        }

        AABB vicinity = target.getBoundingBox().inflate(18.0);
        int nearby = level.getEntities(target, vicinity, e -> e instanceof LivingEntity && !e.isSpectator()).size();

        return new ReplaySample(
            tick,
            new Vec3(target.getX(), target.getY(), target.getZ()),
            new Vec3(target.getDeltaMovement().x, target.getDeltaMovement().y, target.getDeltaMovement().z),
            target.onGround(),
            target.hurtTime > 0,
            nearby,
            target.getName().getString()
        );
    }

    public static boolean isPositionClear(Level level, Vec3 cameraBase, double clearance) {
        double r = Math.max(0.2, Math.min(clearance, 2.0));
        double eyeY = cameraBase.y() + CameraPathMath.CAMERA_EYE_HEIGHT;
        AABB probe = new AABB(
            cameraBase.x() - r, eyeY - r, cameraBase.z() - r,
            cameraBase.x() + r, eyeY + r, cameraBase.z() + r
        );
        return level.noCollision(probe);
    }

    public static boolean hasLineOfSight(Level level, Vec3 cameraBase, Vec3 target) {
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(
            cameraBase.x(),
            cameraBase.y() + CameraPathMath.CAMERA_EYE_HEIGHT,
            cameraBase.z()
        );
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(target.x(), target.y(), target.z());
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return hit.getLocation().distanceTo(to) < 1.25;
    }

    /**
     * Resolves a collision-free camera position near desired, optionally preferring unobstructed line-of-sight to target.
     * Returns null if every bounded probe is blocked by solid geometry.
     */
    public static Vec3 resolveSafePosition(Level level, Vec3 desired, Vec3 target, double clearance) {
        boolean clear = isPositionClear(level, desired, clearance);
        if (clear && (target == null || hasLineOfSight(level, desired, target))) {
            return desired;
        }

        Vec3 firstClearFallback = clear ? desired : null;

        // First try pulling slightly toward target if line of sight is blocked.
        if (target != null) {
            for (double factor : new double[]{0.2, 0.4, 0.6}) {
                Vec3 candidate = desired.lerp(target, factor);
                if (isPositionClear(level, candidate, clearance)) {
                    if (hasLineOfSight(level, candidate, target)) {
                        return candidate;
                    }
                    if (firstClearFallback == null) {
                        firstClearFallback = candidate;
                    }
                }
            }
        }

        // Next try bounded upward/lateral offsets.
        for (double[] offset : OFFSET_PROBES) {
            Vec3 candidate = desired.add(offset[0], offset[1], offset[2]);
            if (isPositionClear(level, candidate, clearance)) {
                if (target == null || hasLineOfSight(level, candidate, target)) {
                    return candidate;
                }
                if (firstClearFallback == null) {
                    firstClearFallback = candidate;
                }
            }
        }
        return firstClearFallback;
    }

    public static Map<String, Object> checkPath(List<Vec3> points, Vec3 lookAt, double clearance, boolean suggestSafeOffsets) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            throw ToolError.notInReplay("No loaded world. Open a replay first.");
        }
        double r = clearance > 0 ? clearance : DEFAULT_CLEARANCE;

        List<Map<String, Object>> issues = new ArrayList<>();
        List<List<Double>> safePoints = new ArrayList<>(points.size());
        List<Map<String, Object>> unresolved = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            Vec3 pt = points.get(i);
            boolean clear = isPositionClear(level, pt, r);
            boolean los = lookAt == null || hasLineOfSight(level, pt, lookAt);

            Vec3 resolved = pt;
            if (!clear || !los) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("index", i);
                issue.put("point", pt.toList());
                issue.put("blocked_by_block", !clear);
                issue.put("obstructed_line_of_sight", !los);

                if (suggestSafeOffsets) {
                    Vec3 candidate = resolveSafePosition(level, pt, lookAt, r);
                    if (candidate != null) {
                        resolved = candidate;
                        issue.put("suggested_safe_point", candidate.toList());
                    } else {
                        issue.put("suggested_safe_point", null);
                        unresolved.add(issue);
                    }
                }
                issues.add(issue);
            }
            safePoints.add(resolved.toList());
        }

        // Check segments between consecutive waypoints for wall intersections.
        List<Map<String, Object>> segmentHits = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(a.x(), a.y() + CameraPathMath.CAMERA_EYE_HEIGHT, a.z());
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(b.x(), b.y() + CameraPathMath.CAMERA_EYE_HEIGHT, b.z());
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK) {
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("from_index", i);
                seg.put("to_index", i + 1);
                seg.put("hit_position", new Vec3(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z).toList());
                seg.put("hit_block", level.getBlockState(hit.getBlockPos()).getBlock().getName().getString());
                segmentHits.add(seg);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clear", issues.isEmpty() && segmentHits.isEmpty());
        response.put("waypoint_issues", issues);
        response.put("segment_collisions", segmentHits);
        response.put("safe_points", safePoints);
        response.put("unresolved_points", unresolved);
        return response;
    }
}
