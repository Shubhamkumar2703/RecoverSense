package com.recoversense.service;

public class RecoveryCaseNotFoundException extends RuntimeException {

    public RecoveryCaseNotFoundException(Long recoveryCaseId) {
        super("Recovery case not found: " + recoveryCaseId);
    }
}
