package com.exampro.exception;

public class AccountLockedException extends AuthenticationException {

    public AccountLockedException(String message) {
        super(message);
    }

    public AccountLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
