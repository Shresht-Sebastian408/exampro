package com.exampro.proctor;

import com.exampro.config.AppConfig;
import com.exampro.model.entity.ProctorEvent;
import com.exampro.model.enums.EventType;
import com.exampro.model.enums.Severity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Starts at 100 and subtracts a weight per event, by severity. Two guards
 * against unfair results: INFO events weigh nothing, and each event type's
 * total penalty is capped, so forty harmless RAPID_ANSWERs cannot sink a
 * score the way one paste can. Weights and the cap are constructor
 * arguments because they need tuning against real cohorts.
 */
public class IntegrityScorer {

    private final Map<Severity, Integer> weights;
    private final double perTypeCap;

    public IntegrityScorer() {
        this(defaultWeights(), AppConfig.INTEGRITY_TYPE_CAP);
    }

    public IntegrityScorer(Map<Severity, Integer> weights, double perTypeCap) {
        this.weights = new EnumMap<>(weights);
        this.perTypeCap = perTypeCap;
    }

    public static Map<Severity, Integer> defaultWeights() {
        Map<Severity, Integer> w = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            w.put(s, s.getWeight());
        }
        return w;
    }

    public IntegrityScore score(List<ProctorEvent> events) {
        Map<EventType, Integer> counts = new EnumMap<>(EventType.class);
        Map<EventType, Double> raw = new EnumMap<>(EventType.class);
        for (ProctorEvent e : events) {
            counts.merge(e.getType(), 1, Integer::sum);
            raw.merge(e.getType(), (double) weights.getOrDefault(e.getSeverity(), 0), Double::sum);
        }

        Map<EventType, Double> penalties = new EnumMap<>(EventType.class);
        double total = 0;
        for (Map.Entry<EventType, Double> e : raw.entrySet()) {
            double capped = Math.min(perTypeCap, e.getValue());
            penalties.put(e.getKey(), capped);
            total += capped;
        }
        return new IntegrityScore(Math.max(0, AppConfig.INTEGRITY_START - total), total, counts, penalties);
    }
}
