package com.exampro.grading;

import java.util.Map;

/**
 * Every question's score for one attempt.
 *
 * @param byQuestion question id to its entry
 * @param total      sum of the final scores that are settled
 * @param needsManual true if any answer still awaits a person
 */
public record ScoreSheet(Map<Long, Entry> byQuestion, double total, boolean needsManual) {

    /** @param finalScore null while a manual grade is outstanding */
    public record Entry(double autoScore, Double finalScore) {}
}
