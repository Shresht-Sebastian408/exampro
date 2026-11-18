package com.exampro.exception;

public class InvalidQuestionException extends ValidationException {

    public InvalidQuestionException(String message) {
        super(message);
    }

    public InvalidQuestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
