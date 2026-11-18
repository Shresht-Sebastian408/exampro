package com.exampro.exception;

public class AttemptAlreadySubmittedException extends ExamException {

    public AttemptAlreadySubmittedException(String message) {
        super(message);
    }

    public AttemptAlreadySubmittedException(String message, Throwable cause) {
        super(message, cause);
    }
}
