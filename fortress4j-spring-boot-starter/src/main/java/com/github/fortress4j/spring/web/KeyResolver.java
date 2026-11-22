package com.github.fortress4j.spring.web;

import com.github.fortress4j.spring.RateLimit;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;


/** Decides which bucket a request belongs to. Define your own bean to replace the default. */
@FunctionalInterface
public interface KeyResolver {
    String resolve(HttpServletRequest request, RateLimit annotation);
}
