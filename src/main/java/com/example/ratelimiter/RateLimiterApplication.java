package com.example.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// Micrometer //
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

@SpringBootApplication
@EnableScheduling
public class RateLimiterApplication {
public static void main(String[] args) {
    SpringApplication.run(RateLimiterApplication.class, args);
}

@Bean
public JedisConnectionFactory jedisConnectionFactory() {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
    return new JedisConnectionFactory(config);
}

@Bean
public StringRedisTemplate redisTemplate() {
    return new StringRedisTemplate(jedisConnectionFactory());
}
}



//  Strategy Interface //
interface RateLimitStrategy {
boolean allowRequest(String key, RateLimitConfig config);
}

//  Fixed Window Strategy //
@Component
class FixedWindowStrategy implements RateLimitStrategy {

@Autowired
private StringRedisTemplate redisTemplate;

@Override
public boolean allowRequest(String key, RateLimitConfig config) {
    String redisKey = "rl:" + key;
    Long count = redisTemplate.opsForValue().increment(redisKey);
    if (count == 1) {
        redisTemplate.expire(redisKey, config.windowInSec, TimeUnit.SECONDS);
    }
    return count <= config.maxRequests;
}

}



//  In-Memory Fallback Strategy //
@Component
class InMemoryFallbackStrategy implements RateLimitStrategy {
private final ConcurrentHashMap<String, Bucket> memory = new ConcurrentHashMap<>();

@Override
public boolean allowRequest(String key, RateLimitConfig config) {
    Bucket bucket = memory.computeIfAbsent(key, k -> new Bucket(config.maxRequests, config.windowInSec));
    return bucket.allow();
}

private static class Bucket {
    int capacity;
    int remaining;
    long resetTime;

    public Bucket(int capacity, int window) {
        this.capacity = capacity;
        this.remaining = capacity;
        this.resetTime = Instant.now().getEpochSecond() + window;
    }

    synchronized boolean allow() {
        long now = Instant.now().getEpochSecond();
        if (now > resetTime) {
            remaining = capacity;
            resetTime = now + 60;
        }
        if (remaining > 0) {
            remaining--;
            return true;
        }
        return false;
    }
}

}

//  Config Object //
class RateLimitConfig {
public int maxRequests;
public int windowInSec;
public String strategy;

public RateLimitConfig(int maxRequests, int windowInSec, String strategy) {
    this.maxRequests = maxRequests;
    this.windowInSec = windowInSec;
    this.strategy = strategy;
}
}

//  Config Service //
@Service
class RateLimitConfigService {

    private static final String CONFIG_PREFIX = "rate_config:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Default fallback values
    private static final int DEFAULT_MAX = 5;
    private static final int DEFAULT_WINDOW = 60;
    private static final String DEFAULT_STRATEGY = "FIXED";

    public RateLimitConfig getConfig(String key) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        String redisKey = CONFIG_PREFIX + key;

        try {
            String maxReq = hashOps.get(redisKey, "maxRequests");
            String window = hashOps.get(redisKey, "windowInSec");
            String strategy = hashOps.get(redisKey, "strategy");

            int max = maxReq != null ? Integer.parseInt(maxReq) : DEFAULT_MAX;
            int win = window != null ? Integer.parseInt(window) : DEFAULT_WINDOW;
            String strat = strategy != null ? strategy : DEFAULT_STRATEGY;

            return new RateLimitConfig(max, win, strat);
        } catch (Exception e) {
            return new RateLimitConfig(DEFAULT_MAX, DEFAULT_WINDOW, DEFAULT_STRATEGY);
        }
    }
}

//  Strategy Factory //
@Service
class StrategyFactory {
@Autowired FixedWindowStrategy fixed;
@Autowired InMemoryFallbackStrategy fallback;

public RateLimitStrategy get(String strategy) {
    if ("FIXED".equalsIgnoreCase(strategy)) return fixed;
    return fallback;
}

}

// Core Logic Service //
@Service
class RateLimiterService {

@Autowired private StringRedisTemplate redisTemplate;
@Autowired private RateLimitConfigService configService;
@Autowired private StrategyFactory factory;
@Autowired private MeterRegistry meterRegistry;

private final Counter allowed;
private final Counter denied;

@Autowired
public RateLimiterService(MeterRegistry registry) {
    this.allowed = registry.counter("rate_limit.allowed");
    this.denied = registry.counter("rate_limit.blocked");
}

public boolean allow(String clientKey) {
    try {
        RateLimitConfig config = configService.getConfig(clientKey);
        RateLimitStrategy strategy = factory.get(config.strategy);
        boolean allowedRequest = strategy.allowRequest(clientKey, config);
        if (allowedRequest) allowed.increment();
        else denied.increment();
        return allowedRequest;
    } catch (Exception ex) {
        return true; 
    }
}

}

//  Interceptor //
@Component
class RateLimitInterceptor implements HandlerInterceptor {

@Autowired RateLimiterService service;

@Override
public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
    String key = getKey(req);
    if (service.allow(key)) return true;
    res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    res.getWriter().write("Too many requests - Rate limited");
    return false;
}

private String getKey(HttpServletRequest req) {
    String user = req.getHeader("X-User-ID");
    String api = req.getRequestURI();
    return user + ":" + api;
}
}

//  MVC Config //
@Configuration
class WebMvcConfig implements WebMvcConfigurer {

@Autowired RateLimitInterceptor interceptor;

@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/**");
}
}

//  Controller to Test Rate Limiting //
@RestController
class TestController {

    @GetMapping("/api/test")
    public String testRateLimit() {
        return "Paperflite Request successful-You are not rate limited!";
    }
}


