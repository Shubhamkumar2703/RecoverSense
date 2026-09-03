package com.recoversense.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP front door for demo-only operator convenience. {@code @Profile("demo")}
 * means this controller bean - and therefore every route on it - does not
 * exist at all outside the demo profile: a request to either path on a
 * default/production boot resolves to Spring's ordinary 404 for an unmapped
 * route, not a runtime check inside a shared controller. No recovery/policy/
 * verification logic lives here - {@link DemoResetService} does the one
 * thing this exists for.
 */
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoController {

    private final DemoResetService demoResetService;

    public DemoController(DemoResetService demoResetService) {
        this.demoResetService = demoResetService;
    }

    /**
     * Feature-detection only - never mutates anything. The frontend calls
     * this once on load to decide whether to show the "Reset Demo" control,
     * rather than inferring it from the deployed hostname.
     */
    @GetMapping("/status")
    public DemoStatusResponse status() {
        return new DemoStatusResponse(true);
    }

    @PostMapping("/reset-payment-link")
    public DemoResetResponse resetPaymentLink() {
        return demoResetService.resetHeroPaymentLink();
    }
}
