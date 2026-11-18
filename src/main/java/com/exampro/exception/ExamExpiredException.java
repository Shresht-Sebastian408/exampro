package com.exampro.exception;

public class ExamExpiredException extends ExamException {

    public ExamExpiredException(String message) {
        super(message);
    }

    public ExamExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
