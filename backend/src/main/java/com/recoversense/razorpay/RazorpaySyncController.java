package com.recoversense.razorpay;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1.26 Phase 1: the one HTTP entry point for real Razorpay payment
 * ingestion - the frontend never calls Razorpay directly (RAZORPAY_KEY_ID/
 * RAZORPAY_KEY_SECRET never leave this server). Separate from
 * DashboardController on purpose: that class is documented read-only
 * (queries only), while this one writes new Payment/Customer rows - see
 * RazorpayPaymentSyncService for exactly what it will and will not do.
 */
@RestController
@RequestMapping("/api/dashboard/payments")
public class RazorpaySyncController {

    private final RazorpayPaymentSyncService razorpayPaymentSyncService;

    public RazorpaySyncController(RazorpayPaymentSyncService razorpayPaymentSyncService) {
        this.razorpayPaymentSyncService = razorpayPaymentSyncService;
    }

    @PostMapping("/sync")
    public ResponseEntity<RazorpaySyncResponse> sync() {
        RazorpaySyncResult result = razorpayPaymentSyncService.syncFailedPayments();
        RazorpaySyncResponse body = RazorpaySyncResponse.from(result);
        return result.available() ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }
}
