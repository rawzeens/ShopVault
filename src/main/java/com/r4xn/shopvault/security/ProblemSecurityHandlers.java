package com.r4xn.shopvault.security;

import com.r4xn.shopvault.common.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401/403 from the security filter chain, in the same problem+json shape as the rest of the API. */
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        boolean badToken = ex instanceof OAuth2AuthenticationException;
        response.setHeader("WWW-Authenticate", badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        ProblemWriter.write(response, HttpStatus.UNAUTHORIZED, badToken ? "invalid_token" : "unauthorized",
                badToken ? "The access token is invalid, expired or revoked" : "Authentication is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        ProblemWriter.write(response, HttpStatus.FORBIDDEN, "forbidden",
                "You do not have permission to perform this action");
    }
}
