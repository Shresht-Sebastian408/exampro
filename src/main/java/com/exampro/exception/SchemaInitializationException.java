package com.exampro.exception;

public class SchemaInitializationException extends DataAccessException {

    public SchemaInitializationException(String message) {
        super(message);
    }

    public SchemaInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
