package com.example.demo.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(2)
public class AuthenticationFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String authHeader = httpRequest.getHeader("Authorization");
//
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            httpResponse.setStatus(HttpStatus.UNAUTHORIZED.value());
//            httpResponse.getWriter().write("Missing or invalid authorization header");
//            return;
//        }

        // Validate token (simplified example)
//        String token = authHeader.substring(7);
//        if (!isValidToken(token)) {
//            httpResponse.setStatus(HttpStatus.UNAUTHORIZED.value());
//            httpResponse.getWriter().write("Invalid token");
//            return;
//        }

        chain.doFilter(request, response);
    }

    private boolean isValidToken(String token) {
        // Implement token validation logic
        return token != null && !token.trim().isEmpty();
    }
}