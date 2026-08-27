package com.recoversense.service;

import com.recoversense.domain.PaymentStatus;

public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(Long paymentId, PaymentStatus actualStatus) {
        super("Payment " + paymentId + " is not FAILED (status=" + actualStatus + "); cannot open a recovery case");
    }
}
