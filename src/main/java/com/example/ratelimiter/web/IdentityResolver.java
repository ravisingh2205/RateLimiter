package com.example.ratelimiter.web;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.model.Identity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Works out who is calling.
 *
 * DEMO: employee, department and API key come from the headers X-Emp-Id, X-Dept-Id and X-Api-Key so
 * you can try any combination with curl. IN A REAL APP take empId/deptId from the verified
 * authentication (JWT claims / session) and never from client-supplied headers.
 *
 * The client IP is the socket address. X-Forwarded-For is only used when ratelimit.trust-forwarded-for
 * is true, i.e. when the app is behind a load balancer you control that overwrites that header.
 */
@Component
public class IdentityResolver {

    private final RateLimitProperties props;

    public IdentityResolver(RateLimitProperties props) {
        this.props = props;
    }

    public Identity from(HttpServletRequest req) {
        return new Identity(
                clientIp(req),
                header(req, "X-Emp-Id"),
                header(req, "X-Dept-Id"),
                header(req, "X-Api-Key"));
    }

    private String clientIp(HttpServletRequest req) {
        if (props.trustForwardedFor()) {
            String xff = header(req, "X-Forwarded-For");
            if (xff != null) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return req.getRemoteAddr();
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
