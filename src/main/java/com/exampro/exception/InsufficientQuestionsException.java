package com.exampro.exception;

public class InsufficientQuestionsException extends ExamException {

    public InsufficientQuestionsException(String message) {
        super(message);
    }

    public InsufficientQuestionsException(String message, Throwable cause) {
        super(message, cause);
    }
}
