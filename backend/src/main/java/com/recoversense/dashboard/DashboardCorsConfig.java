package com.recoversense.dashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the frontend (a different origin/port than the backend - the local
 * Vite dev server, or the deployed Vercel frontend) call the application's
 * HTTP API. Each mapping is scoped to exactly the methods that endpoint
 * needs - /api/dashboard/** stays GET-only (read-only, unchanged since
 * M1.x), and /api/recovery/** (M1.20/M1.21) is POST-only, matching
 * RecoveryController's single mutating endpoint. Neither mapping grants
 * access beyond its own path, and origins are never a wildcard.
 * <p>
 * M1.32: /api/dashboard/payments/sync (RazorpaySyncController, M1.26) is the
 * one mutating exception under the otherwise-read-only /api/dashboard/**
 * prefix - it needs its own narrowly-scoped POST mapping rather than
 * widening /api/dashboard/**'s GET-only contract for every other dashboard
 * endpoint.
 * <p>
 * Registration order matters here and is deliberate: verified empirically
 * (see DashboardCorsConfigTest) that Spring's UrlBasedCorsConfigurationSource
 * does NOT resolve the most-specific pattern for an incoming request the way
 * @RequestMapping does - with /api/dashboard/** registered first, a request
 * to /api/dashboard/payments/sync incorrectly resolved to the GET-only
 * config, producing a 403 even for this mapping's own POST rule. Registering
 * the exact-path mapping BEFORE the broader /api/dashboard/** wildcard fixes
 * resolution. If more narrowly-scoped mappings are added under this prefix in
 * the future, they must also be registered before /api/dashboard/**.
 * <p>
 * M1.35: allowed origins come from {@code app.cors.allowed-origins}
 * ({@code APP_CORS_ALLOWED_ORIGINS} env var, following this project's
 * existing ${ENV_VAR:default} convention), defaulting to
 * {@code http://localhost:*} so local development is unaffected when unset.
 * Spring splits a comma-separated property value into the array here, so a
 * deployment can list multiple origins (e.g. the Vercel domain plus a
 * preview domain) without any code change.
 */
@Configuration
public class DashboardCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public DashboardCorsConfig(
            @Value("${app.cors.allowed-origins:http://localhost:*}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/dashboard/payments/sync")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("POST");

        registry.addMapping("/api/dashboard/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET");

        registry.addMapping("/api/recovery/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("POST");

        // Demo-only (DemoController, @Profile("demo")) - status is a GET
        // feature-detection probe, reset-payment-link is the one POST.
        registry.addMapping("/api/demo/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST");
    }
}
