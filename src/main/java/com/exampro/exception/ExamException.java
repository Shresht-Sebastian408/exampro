package com.exampro.exception;

public class ExamException extends ExamProException {

    public ExamException(String message) {
        super(message);
    }

    public ExamException(String message, Throwable cause) {
        super(message, cause);
    }
}
