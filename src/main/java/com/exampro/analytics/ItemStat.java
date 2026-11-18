package com.exampro.analytics;

import java.util.Map;

/**
 * @param attempts       students whose mark on this question is settled
 * @param pValue         mean fraction of the marks earned: 1 is easy, 0 is hard; null with no data
 * @param discrimination correlation of the item with the total score: high means strong students
 *                       get it right and weak ones do not; null when it cannot be computed
 * @param distractors    option id to how many students chose it
 * @param blanks         students who selected nothing (meaningful for choice questions only)
 */
public record ItemStat(long questionId, int attempts, Double pValue, Double discrimination,
                       Map<Long, Integer> distractors, int blanks) {

    /** The classic "look at this one again" zone: too easy, too hard, or not separating students. */
    public boolean needsRevision() {
        boolean extreme = pValue != null && (pValue < 0.2 || pValue > 0.9);
        boolean weak = discrimination != null && discrimination < 0.2;
        return extreme || weak;
    }
}
