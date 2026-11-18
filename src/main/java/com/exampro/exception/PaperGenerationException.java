package com.exampro.exception;

public class PaperGenerationException extends ExamException {

    public PaperGenerationException(String message) {
        super(message);
    }

    public PaperGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
