package com.cinema.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Applies UTF-8 before any request parameter is read or response is written. */
public final class EncodingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setCharacterEncoding("UTF-8");
        // DefaultServlet writes static resources as raw bytes. Setting a writer
        // encoding for those responses can convert already UTF-8 assets twice.
        if (!(request instanceof HttpServletRequest httpRequest)
                || !httpRequest.getRequestURI().contains("/assets/")) {
            response.setCharacterEncoding("UTF-8");
        }
        chain.doFilter(request, response);
    }
}
