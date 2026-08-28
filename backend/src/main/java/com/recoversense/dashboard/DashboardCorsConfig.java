package com.recoversense.dashboard;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the local Vite dev server (a different origin/port than the backend)
 * call the read-only dashboard API. Scoped to /api/dashboard/** and GET only
 * - it grants no write access and touches no other endpoint.
 */
@Configuration
public class DashboardCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/dashboard/**")
                .allowedOriginPatterns("http://localhost:*")
                .allowedMethods("GET");
    }
}
