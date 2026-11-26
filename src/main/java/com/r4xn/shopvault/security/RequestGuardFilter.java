package com.r4xn.shopvault.security;

import com.r4xn.shopvault.common.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies before they are parsed. Bodies must declare their length, so
 * a chunked upload cannot stream past the limit.
 */
public class RequestGuardFilter extends OncePerRequestFilter {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final long maxBodyBytes;

    public RequestGuardFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (BODY_METHODS.contains(request.getMethod())) {
            long length = request.getContentLengthLong();
            if (length > maxBodyBytes) {
                ProblemWriter.write(response, HttpStatus.CONTENT_TOO_LARGE, "payload_too_large",
                        "The request body is too large");
                return;
            }
            if (length < 0 && request.getHeader("Transfer-Encoding") != null) {
                ProblemWriter.write(response, HttpStatus.LENGTH_REQUIRED, "length_required",
                        "Requests with a body must send a Content-Length header");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
