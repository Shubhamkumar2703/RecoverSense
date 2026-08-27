package com.recoversense.service;

public class RecoveryActionNotFoundException extends RuntimeException {

    public RecoveryActionNotFoundException(Long recoveryActionId) {
        super("Recovery action not found: " + recoveryActionId);
    }
}
