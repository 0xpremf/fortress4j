package com.github.fortress4j.spring.web;

import com.github.fortress4j.spring.RateLimit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import com.github.fortress4j.spring.web.SpELKeyResolverRoot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpELKeyResolver implements KeyResolver{

    private static final int MAX_KEY_LENGTH = 128;

    private final ExpressionParser parser = new SpelExpressionParser();

    private final Map<String, Expression> cache = new ConcurrentHashMap<>();





    @Override
    public String resolve(HttpServletRequest request, RateLimit annotation) {
        String expression = annotation.key();
        String key = null;


        if(!expression.isBlank()){
            Expression parsed = cache.computeIfAbsent(expression, parser::parseExpression);

            var context = SimpleEvaluationContext.forReadOnlyDataBinding()
                    .withInstanceMethods()
                    .withRootObject(new SpELKeyResolverRoot(request))
                    .build();

            Object value = parsed.getValue(context);
            key = value == null ? null : value.toString();
        }
        // Missing value: fall back to the IP. Skipping the limit would let a client bypass it
        // just by leaving out the header.
        if(key==null||key.isBlank()){
            key="ip:"+request.getRemoteAddr();
        }
        return bound(key);
    }


    public static String bound(String key){
        if(key.getBytes(StandardCharsets.UTF_8).length<=MAX_KEY_LENGTH) return key;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "sha256:"+ HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
