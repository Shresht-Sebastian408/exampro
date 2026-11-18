package com.exampro.analytics;

/**
 * @param bands count of students per 10-point band of percentage score:
 *              index 0 is 0-9%, index 9 is 90-100%; below 0 counts in band 0
 */
public record ScoreSummary(int attempts, double mean, double median, double min, double max, int[] bands) {

    public static final ScoreSummary EMPTY = new ScoreSummary(0, 0, 0, 0, 0, new int[10]);
}
