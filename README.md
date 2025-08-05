#  Spring Boot Rate Limiter

A distributed, extensible rate limiter built with Spring Boot, Redis, and Micrometer — designed for high-traffic APIs with graceful degradation and pluggable strategies.

---

##  What It Does

-  Limits how many requests a user/client can make in a time window
-  Uses Redis for distributed request tracking across instances
-  Falls back to in-memory if Redis is down (fail-open behavior)
-  Dynamic config (max requests, time window, strategy) from Redis
-  Emits Prometheus-compatible metrics via Micrometer

---

##  Key Features

- Fixed Window rate limiting (easily pluggable to support others)
- Works across multiple nodes using Redis as a counter store
- Graceful fallback to in-memory if Redis is unavailable
- Micrometer metrics: rate_limit.allowed and rate_limit.blocked
- Spring Boot plug-and-play via HandlerInterceptor

---

##  How It Works

- Each request is identified by a key: e.g., X-User-ID + URI
- Configuration for each key is loaded from Redis:
  - maxRequests (default: 5)
  - windowInSec (default: 60)
  - strategy (default: FIXED)
- Requests are counted in Redis using INCR + EXPIRE
- If Redis fails, it switches to a synchronized in-memory bucket
- If request limit is exceeded → HTTP 429 is returned

---

##  Redis Key Design

Rate limit configuration is stored as a hash:

Key: rate_config:user123:/api/orders

Fields:
maxRequests = 5
windowInSec = 60
strategy = FIXED


The counter for requests:

---

##  Running Locally

Make sure Redis is running on localhost:6379.

Then clone and run:

```bash
git clone https://github.com/sabareeswaran30-dev/rate-limiter.git
cd rate-limiter
mvn spring-boot:run

Test with:

curl -H "X-User-ID: user123" http://localhost:8080/api/test

Once the limit is exceeded, the response will be:
Too many requests - Rate limited


When a request is made to /api/test, the following happens:

->Spring Boot receives the request.

->Before reaching the controller, the RateLimitInterceptor intercepts the request.

->The interceptor extracts the X-User-ID from the request headers and identifies the requested URI.

->It calls the RateLimiterService to check if the request is allowed based on the user’s rate limit.

->If allowed, the request proceeds to the TestController which handles and returns the response.

->If blocked, the interceptor immediately returns a 429 Too Many Requests response, preventing further processing.

[ This mechanism ensures users cannot exceed the allowed request limits, protecting the API from abuse or overload ]


Author :
Built by: Sabareeswaran Ganesan
GitHub: @sabareeswaran30-dev


