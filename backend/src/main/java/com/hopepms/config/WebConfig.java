package com.hopepms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(HopePmsProperties.class)
public class WebConfig {

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(HopePmsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins());
        // DELETE is required for the user off-boarding flow
        // (DELETE /api/admin/users/{userId}, AdminUsersController.eradicate).
        // The SPA calls the API cross-origin with credentials, so every
        // non-simple request is CORS-preflighted. Omitting DELETE here made
        // the browser reject the preflight and the actual DELETE never left
        // the client — the request never reached ECS (zero delete events in
        // /ecs/hopepms-backend), and the SPA surfaced the generic
        // "An unexpected error occurred" with no server-side trace. POST-based
        // promote/demote/activate were unaffected because POST was allowed.
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
