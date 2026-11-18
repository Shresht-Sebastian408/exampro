package com.exampro.grading;

/**
 * @param score       marks awarded; when {@code needsManual} it is only a suggestion
 * @param needsManual true when a person must set the final mark
 */
public record GradeResult(double score, boolean needsManual, String note) {

    public static GradeResult auto(double score) {
        return new GradeResult(score, false, "");
    }
}
