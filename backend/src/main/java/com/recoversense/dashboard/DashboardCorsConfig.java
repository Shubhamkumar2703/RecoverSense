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
 */
@Configuration
public class DashboardCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/dashboard/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET");

        registry.addMapping("/api/recovery/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("POST");
    }
}
