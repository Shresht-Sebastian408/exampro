package com.exampro.exception;

/**
 * Root of every exception this application raises deliberately.
 * Unchecked on purpose: a failure here is never something a caller
 * can meaningfully recover from mid-expression, it is handled at a
 * layer boundary (see the handling policy in plan.md section 8).
 */
public class ExamProException extends RuntimeException {

    public ExamProException(String message) {
        super(message);
    }

    public ExamProException(String message, Throwable cause) {
        super(message, cause);
    }
}
