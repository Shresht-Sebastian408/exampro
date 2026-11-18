package com.exampro.analytics;

import java.util.List;

/**
 * One student's outcome on one question of their paper.
 *
 * @param score null while a manual mark is still outstanding; such rows are left out of the maths
 * @param selectedOptionIds empty for a blank answer or a non-choice question
 */
public record ItemObservation(long attemptId, long questionId, double marks, Double score,
                              List<Long> selectedOptionIds) {}
