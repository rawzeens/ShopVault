package com.r4xn.shopvault.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

/**
 * Writes RFC 7807 problem responses from servlet filters, where the MVC exception handler is not
 * reachable. Only fixed, server-authored strings are written.
 */
public final class ProblemWriter {

    public static final String PROBLEM_JSON = "application/problem+json";

    private ProblemWriter() {}

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + escape(status.getReasonPhrase())
                + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + escape(detail)
                + "\",\"code\":\"" + escape(code) + "\"}");
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
