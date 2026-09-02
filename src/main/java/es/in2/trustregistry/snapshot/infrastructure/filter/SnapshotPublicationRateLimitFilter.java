package es.in2.trustregistry.snapshot.infrastructure.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pragmatic rate limit for {@code GET /trust/v1/snapshot} ({@code quality-report.md} S2, {@code
 * tech-debt.md} TD-04): caps how often a given (source IP, {@code X-Tenant}) pair may call the
 * publication endpoint in a fixed window.
 *
 * <p><strong>Scope decision (public endpoint, rate-limit only, no authentication).</strong> The
 * signed snapshot and its JWKS are deliberately fetchable without a prior handshake — {@code
 * AC-02} requires a consumer to verify it entirely offline, with nothing but material it cached
 * earlier, which only works if any consumer can fetch that material without first authenticating.
 * Adding real authentication (mTLS, API keys, OAuth2 client-credentials) would be a materially
 * larger scope — a new dependency, new configuration, and a breaking contract change for every
 * existing consumer (Verifier, Issuer, wallets) that would need to start sending credentials —
 * closer to its own Story than a fix bundled into this one. This filter closes the resource-
 * exhaustion half of S2 completely; it narrows, but does not eliminate, tenant-existence
 * enumeration by response-shape diffing (making it slow and costly rather than free), which is
 * accepted as residual risk here and tracked as its own follow-up (see {@code tech-debt.md}
 * TD-09) rather than solved by changing {@code ES-05}'s existing "unknown tenant publishes an
 * empty-but-signed snapshot" response shape — that is a spec-level decision, not a security fix.
 *
 * <p><strong>Mechanism.</strong> Fixed window, not a token bucket or sliding log: a {@link
 * Caffeine} cache keyed by {@code "<remoteAddr>|<tenant>"}, holding an {@link AtomicInteger}
 * counter that {@link Caffeine#expireAfterWrite} evicts {@link #WINDOW} after its <em>first</em>
 * increment in that window — simple, bounded, and, as a side effect, exactly the eviction S3/TD-05
 * asks for: an idle (IP, tenant) pair's counter disappears on its own once the window lapses,
 * instead of living in an unbounded {@code ConcurrentHashMap} forever. {@code maximumSize} is a
 * second, independent bound in case an attacker cycles through enough distinct keys inside one
 * window to otherwise grow the cache unboundedly before eviction catches up.
 *
 * <p>Only {@code /trust/v1/snapshot} is limited — {@code /trust/v1/jwks} is small, static-ish
 * verification material with no per-tenant work behind it, and {@code /trust/v1/snapshot/plain}
 * is already gated to the {@code DEVELOPMENT} profile ({@code AD-6}/{@code AD-22}), not a
 * production-reachable surface.
 */
@Slf4j
@Component
public class SnapshotPublicationRateLimitFilter extends OncePerRequestFilter {

    private static final String LIMITED_PATH = "/trust/v1/snapshot";
    private static final String TENANT_HEADER = "X-Tenant";
    private static final int MAX_REQUESTS_PER_WINDOW = 30;
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final long MAXIMUM_TRACKED_KEYS = 10_000;

    private final Cache<String, AtomicInteger> requestCounts = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(MAXIMUM_TRACKED_KEYS)
            .build();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        if (!LIMITED_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String key = rateLimitKey(request);
        AtomicInteger count = requestCounts.get(key, ignored -> new AtomicInteger());
        int requestsInWindow = count.incrementAndGet();

        if (requestsInWindow > MAX_REQUESTS_PER_WINDOW) {
            log.warn("Rate limit exceeded for '{}': {} requests within the current window", key, requestsInWindow);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Composite key so a burst against one tenant from one caller does not throttle every other
     * (IP, tenant) pair sharing either half — a noisy tenant does not collaterally rate-limit a
     * different tenant from a different caller, and vice versa.
     */
    private static String rateLimitKey(HttpServletRequest request) {
        String tenant = request.getHeader(TENANT_HEADER);
        return request.getRemoteAddr() + "|" + (tenant == null ? "" : tenant);
    }
}
