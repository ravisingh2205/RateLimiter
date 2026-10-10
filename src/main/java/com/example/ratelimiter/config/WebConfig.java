package com.example.ratelimiter.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Protects /admin/** with a shared token. This is deliberately simple; in a real deployment put the
 * admin API behind your normal authentication/authorization (OIDC, RBAC) instead.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitProperties props;

    public WebConfig(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
                    throws Exception {
                String token = req.getHeader("X-Admin-Token");
                boolean ok = token != null && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        props.adminToken().getBytes(StandardCharsets.UTF_8));
                if (!ok) {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Admin-Token\"}");
                }
                return ok;
            }
        }).addPathPatterns("/admin/**");
    }
}
