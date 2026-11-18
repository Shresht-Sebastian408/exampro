package com.exampro.exception;

public class ExamNotStartedException extends ExamException {

    public ExamNotStartedException(String message) {
        super(message);
    }

    public ExamNotStartedException(String message, Throwable cause) {
        super(message, cause);
    }
}
