package com.exampro.exception;

public class InvalidBlueprintException extends ValidationException {

    public InvalidBlueprintException(String message) {
        super(message);
    }

    public InvalidBlueprintException(String message, Throwable cause) {
        super(message, cause);
    }
}
