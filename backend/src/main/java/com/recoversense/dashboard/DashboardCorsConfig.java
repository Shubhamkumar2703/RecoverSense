package com.recoversense.dashboard;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the local Vite dev server (a different origin/port than the backend)
 * call the application's HTTP API. Each mapping is scoped to exactly the
 * methods that endpoint needs - /api/dashboard/** stays GET-only (read-only,
 * unchanged since M1.x), and /api/recovery/** (M1.20/M1.21) is POST-only,
 * matching RecoveryController's single mutating endpoint. Neither mapping
 * grants access beyond its own path, and origins stay pinned to localhost
 * (the local dev server), never a wildcard.
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
 */
@Configuration
public class DashboardCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/dashboard/payments/sync")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("POST");

        registry.addMapping("/api/dashboard/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET");

        registry.addMapping("/api/recovery/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("POST");
    }
}
