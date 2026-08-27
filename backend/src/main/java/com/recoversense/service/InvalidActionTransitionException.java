package com.recoversense.service;

public class InvalidActionTransitionException extends RuntimeException {

    public InvalidActionTransitionException(String message) {
        super(message);
    }
}
