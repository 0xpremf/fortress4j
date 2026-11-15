package com.github.fortress4j.spring;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD,ElementType.TYPE}) // @Target({ElementType.METHOD, ElementType.TYPE}) declares that the annotation it decorates may appear only on method declarations and on type declarations (class/interface/enum/record/annotation) — anything else is a compile-time error, and nothing about runtime behaviour is implied.
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String value();

    String Key() default "";
}
