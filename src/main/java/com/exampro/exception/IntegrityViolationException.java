package com.exampro.exception;

public class IntegrityViolationException extends ProctorException {

    public IntegrityViolationException(String message) {
        super(message);
    }

    public IntegrityViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
