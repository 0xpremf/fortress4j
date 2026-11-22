package com.github.fortress4j.spring.web;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.spring.RateLimit;
import com.github.fortress4j.spring.RateLimiterRegistry;
import com.github.fortress4j.spring.autoconfigure.Fortress4jProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;


public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Log log = LogFactory.getLog(RateLimitInterceptor.class);

    private  final RateLimiterRegistry registry;
    private final KeyResolver keyResolver;
    private final boolean headers;
    private final Fortress4jProperties.OnError onError;
    private RateLimitResult result;

    public RateLimitInterceptor(RateLimiterRegistry registry, KeyResolver keyResolver, boolean headers, Fortress4jProperties.OnError onError){
        this.registry = registry;
        this.keyResolver = keyResolver;
        this.headers = headers;
        this.onError = onError;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest req,@NonNull HttpServletResponse res, @NonNull Object handler)
    throws Exception {
        if(!(handler instanceof HandlerMethod method)) return true;

        RateLimit annotation = findAnnotation(method);
        if(annotation == null) return true; //checks if the @RateLimit annotation is present.

        RateLimiterRegistry.Entry entry = registry.get(annotation.value());

        try {
            result = entry.limiter().tryAcquire(keyResolver.resolve(req, annotation));
        } catch (RuntimeException runtimeException){
            log.warn("Rate limiter '" + annotation.value() + "' failed; on-error=" + onError, runtimeException);
            if(onError == Fortress4jProperties.OnError.ALLOW) return true;
            writeRejection(res, 1L);
        }

        if(headers) writeHeaders(res,entry.limit(),result);
        if(result.allowed()) return true;

        writeRejection(res, retryAfterSeconds(result));

        return false;
    }
    /** Whole seconds, rounded UP: waiting that long is always enough. */
    private static long retryAfterSeconds(RateLimitResult result) {
        return Math.max(1, (result.retryAfter() + 999) / 1000);
    }

    private void writeHeaders(HttpServletResponse response, int limit, RateLimitResult result) {
        long resetSeconds= Math.max(0, Duration.between(Instant.now(),result.resetAt()).toSeconds());

        response.setHeader("RateLimit-Limit",String.valueOf(limit));
        response.setHeader("RateLimit-Request-Remaining-x",String.valueOf(result.remainingRequests()));
        response.setHeader("RateLimit-Reset",String.valueOf(resetSeconds));
    }


    private static RateLimit findAnnotation(HandlerMethod method){
        RateLimit onMethod = method.getMethodAnnotation(RateLimit.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimit.class);
    }

    private static void writeRejection(HttpServletResponse response, Long retryAfterSeconds) throws java.io.IOException{
        response.setStatus(429);
        response.setHeader("Retry-After",String.valueOf(retryAfterSeconds));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\","
                + "\"status\":429,\"detail\":\"Rate limit exceeded. Retry after "
                + retryAfterSeconds + " seconds.\"}");
    }






}
