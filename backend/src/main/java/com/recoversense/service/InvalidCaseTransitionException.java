package com.recoversense.service;

import com.recoversense.domain.RecoveryCaseStatus;

public class InvalidCaseTransitionException extends RuntimeException {

    public InvalidCaseTransitionException(RecoveryCaseStatus from, RecoveryCaseStatus to) {
        super("Invalid recovery case transition: " + from + " -> " + to);
    }
}
