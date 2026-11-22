package com.github.fortress4j.spring.web;

import jakarta.servlet.http.HttpServletRequest;



/**
 * The object exposed to {@code @RateLimit(key = "...")} SpEL expressions.
 * Methods here are the only things a key expression can call.
 */
// Basically a Wrapper-class
public class SpELKeyResolverRoot {

    private  final HttpServletRequest request;

    public SpELKeyResolverRoot(HttpServletRequest request){
        this.request = request;
    }

    public String ip() {
        return request.getRemoteAddr();
    }

    public String header(String name) {
        return request.getHeader(name);
    }

    public String principal() {
        return request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();

    }



}
