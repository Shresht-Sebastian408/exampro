package com.exampro.model.enums;

public enum Severity {
    INFO(0), LOW(1), MEDIUM(3), HIGH(7), CRITICAL(15);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    /** Penalty contribution used by IntegrityScorer. Tunable: see AppConfig. */
    public int getWeight() {
        return weight;
    }
}
