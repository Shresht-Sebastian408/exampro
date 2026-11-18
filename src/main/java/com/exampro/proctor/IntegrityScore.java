package com.exampro.proctor;

import com.exampro.model.enums.EventType;

import java.util.Map;

/** @param score 0 to 100, higher is cleaner; counts and penalties are per event type */
public record IntegrityScore(double score, double penalty,
                             Map<EventType, Integer> counts, Map<EventType, Double> penalties) {}
