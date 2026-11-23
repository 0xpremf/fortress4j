package com.github.fortress4j.spring.autoconfigure;


import com.github.fortress4j.redis.RedisTokenBucketRateLimiter;
import com.github.fortress4j.spring.RateLimiterRegistry;
import com.github.fortress4j.spring.web.KeyResolver;
import com.github.fortress4j.spring.web.RateLimitInterceptor;
import com.github.fortress4j.spring.web.SpELKeyResolver;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashMap;
import java.util.Map;

@AutoConfiguration
@EnableConfigurationProperties(Fortress4jProperties.class)
@ConditionalOnProperty(prefix = "fortress4j", name = "enabled",havingValue = "true", matchIfMissing = true)
public class Fortress4jAutoConfiguration {


    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "fortress4j", name="backend", havingValue = "memory", matchIfMissing = true)
    static class MemoryBackend{

        @Bean
        @ConditionalOnMissingBean(LimiterFactory.class)
        LimiterFactory fortres4jInMemoryLimiterFactory(){
            return new InMemoryLimiterFactory();
        }
    }


    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix="fortress4j", name="backend", havingValue = "redis")
    @ConditionalOnClass({RedisClient.class, RedisTokenBucketRateLimiter.class})
    static class RedisBackend{

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name="fortress4jRedisClient")
        RedisClient fortress4jRedisClient(Fortress4jProperties properties){
            RedisURI redisURI = RedisURI.builder(RedisURI.create(properties.redis().uri())).
                    withTimeout(properties.redis().timeout())
                    .build();

            return RedisClient.create(redisURI);
        }

    }


    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "fortress4jRedisConnection")
    StatefulRedisConnection<String,String> fortress4jRedisConnection(
            @Qualifier("fortress4jRedisClient") RedisClient client){

        return client.connect();

    }

    @Bean
    @ConditionalOnMissingBean(LimiterFactory.class)
    LimiterFactory fortress4jRedisLimiterFactory(
         @Qualifier("fortress4jRedisConnection") StatefulRedisConnection<String,String> connection,
         Fortress4jProperties properties
    ){
        return new RedisLimiterFactory(connection, properties.redis().keyPrefix());
    }

    // ---- registry -----------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    RateLimiterRegistry fortress4jRateLimiterRegistry(
        ObjectProvider<LimiterFactory> factoryObjectProvider,
        Fortress4jProperties properties
    ){
        LimiterFactory factory = factoryObjectProvider.getIfAvailable();
        if (factory == null) {
            throw new IllegalStateException("fortress4j.backend=" + properties.backend()
                    + " could not be set up. For 'redis', add fortress4j-redis and lettuce-core to the classpath.");
        }
        Map<String, RateLimiterRegistry.Entry> entries = new LinkedHashMap<>();
        properties.limiters().forEach((name, spec) -> entries.put(name, factory.create(name, spec)));
        return new RateLimiterRegistry(entries);
    }


    // ---- web ----------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication (type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(HandlerInterceptor.class)
    static class WebSupport implements WebMvcConfigurer {

        private final RateLimiterRegistry registry;
        private final KeyResolver keyResolver;
        private final Fortress4jProperties properties;

        WebSupport(RateLimiterRegistry registry, ObjectProvider<KeyResolver> keyResolver,
                   Fortress4jProperties properties) {
            this.registry = registry;
            this.keyResolver = keyResolver.getIfAvailable(SpELKeyResolver::new);
            this.properties = properties;
        }

        @Override
        public void addInterceptors(InterceptorRegistry interceptors) {
            interceptors.addInterceptor(new RateLimitInterceptor(
                    registry, keyResolver, properties.headers(), properties.onError()));
        }
    }


}
