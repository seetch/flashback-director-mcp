package dev.seetch.flashbackdirector.core;

import dev.seetch.flashbackdirector.core.dto.DirectorDtos.HighlightCandidate;
import dev.seetch.flashbackdirector.core.dto.DirectorDtos.ReplaySample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks sampled replay moments by visual action heuristics (combat, speed bursts, jumps, entity clusters).
 */
public final class HighlightScorer {

    private static final int MIN_TICK_SEPARATION = 60; // 3 seconds between distinct highlight candidates

    private HighlightScorer() {
    }

    public static List<HighlightCandidate> rankSamples(List<ReplaySample> samples, int limit) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        int maxResults = Math.max(1, Math.min(limit, 30));
        List<HighlightCandidate> scored = new ArrayList<>(samples.size());

        ReplaySample prev = null;
        for (ReplaySample sample : samples) {
            double score = 0.0;
            List<String> reasons = new ArrayList<>();

            if (sample.hurt()) {
                score += 4.5;
                reasons.add("combat_damage");
            }

            double speed = sample.velocity().length();
            if (speed > 0.35) {
                score += Math.min(4.0, (speed - 0.35) * 4.5);
                reasons.add("high_speed");
            }

            if (prev != null) {
                double accel = sample.velocity().sub(prev.velocity()).length();
                if (accel > 0.30) {
                    score += Math.min(3.0, accel * 3.5);
                    reasons.add("sudden_motion");
                }
                double dt = Math.max(1, sample.tick() - prev.tick());
                double displacementPerTick = sample.position().distanceTo(prev.position()) / dt;
                if (displacementPerTick > 0.45) {
                    score += Math.min(3.0, displacementPerTick * 2.0);
                    if (!reasons.contains("high_speed")) {
                        reasons.add("fast_travel");
                    }
                }
            }

            if (!sample.onGround() && Math.abs(sample.velocity().y()) > 0.25) {
                score += 2.2;
                reasons.add("airborne_jump_or_fall");
            }

            if (sample.nearbyEntityCount() >= 3) {
                score += Math.min(3.5, sample.nearbyEntityCount() * 0.45);
                reasons.add("entity_cluster(" + sample.nearbyEntityCount() + ")");
            }

            if (reasons.isEmpty()) {
                reasons.add("calm_establishing");
                score += 0.2;
            }

            scored.add(new HighlightCandidate(
                sample.tick(),
                sample.tick() / 20.0,
                score,
                reasons,
                sample.position(),
                sample.focusEntity()
            ));
            prev = sample;
        }

        scored.sort(Comparator.comparingDouble(HighlightCandidate::score).reversed());

        // Non-maximum suppression so returned moments cover distinct parts of the replay.
        List<HighlightCandidate> selected = new ArrayList<>(maxResults);
        for (HighlightCandidate candidate : scored) {
            if (selected.size() >= maxResults) {
                break;
            }
            boolean tooClose = false;
            for (HighlightCandidate chosen : selected) {
                if (Math.abs(candidate.tick() - chosen.tick()) < MIN_TICK_SEPARATION) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                selected.add(candidate);
            }
        }
        return selected;
    }
}
