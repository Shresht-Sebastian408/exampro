package com.exampro.exception;

public class ProctorException extends ExamProException {

    public ProctorException(String message) {
        super(message);
    }

    public ProctorException(String message, Throwable cause) {
        super(message, cause);
    }
}
