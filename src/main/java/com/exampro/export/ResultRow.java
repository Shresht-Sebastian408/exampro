package com.exampro.export;

/**
 * One line of a result sheet.
 *
 * @param score     null if the attempt has not been scored
 * @param integrity null if no integrity score was stored
 */
public record ResultRow(String student, String status, Double score, double maxScore, Double integrity) {}
