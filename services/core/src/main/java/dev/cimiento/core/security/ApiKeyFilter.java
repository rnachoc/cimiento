package dev.cimiento.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the caller role from the X-Api-Key header and applies the access policy to /api. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String ROLE_ATTRIBUTE = "cimiento.role";

    private final Map<Role, byte[]> keys;

    public ApiKeyFilter(ApiKeyProperties props) {
        this.keys = Map.of(
                Role.AGENT, props.agent().getBytes(StandardCharsets.UTF_8),
                Role.ANALYST, props.analyst().getBytes(StandardCharsets.UTF_8),
                Role.REVIEWER, props.reviewer().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        Role role = header == null ? null : resolve(header.getBytes(StandardCharsets.UTF_8));
        if (role == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "missing or unknown API key");
            return;
        }
        if (!AccessPolicy.allowed(role, request.getMethod(), request.getRequestURI())) {
            write(response, HttpServletResponse.SC_FORBIDDEN, "role " + role + " may not "
                    + request.getMethod() + " " + request.getRequestURI());
            return;
        }
        request.setAttribute(ROLE_ATTRIBUTE, role);
        chain.doFilter(request, response);
    }

    private Role resolve(byte[] given) {
        Role found = null;
        for (Map.Entry<Role, byte[]> e : keys.entrySet()) {
            // constant-time comparison for every key
            if (MessageDigest.isEqual(e.getValue(), given)) {
                found = e.getKey();
            }
        }
        return found;
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + (status == 401 ? "unauthorized" : "forbidden")
                + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
