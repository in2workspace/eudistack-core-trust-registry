package es.in2.trustregistry.shared.infrastructure.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Single enforcement point for the {@code X-Tenant} allowlist ({@code quality-report.md} B5):
 * every route under {@code /trust/v1/**} that accepts an {@code X-Tenant} header goes through
 * this filter before any controller method runs, so the allowlist cannot be forgotten on a new
 * or existing controller the way it originally was on {@code TrustedEntityController}.
 *
 * <p><strong>Why a shared filter, not a per-controller constant.</strong> {@code S1}'s original
 * fix put the regex as a {@code private static final} inside {@code TrustSnapshotController}
 * only — correct for the snapshot route, but silent on every other controller reading the same
 * header. {@code B5} found exactly that gap: {@code GET /trust/v1/entities} accepted
 * {@code X-Tenant: ../../etc/passwd} and 300-character values unfiltered, reaching {@code
 * InMemoryTrustedEntityRepository.findAllByTenant()} unvalidated. Enforcing the check once, at
 * the servlet filter boundary shared by every controller instead of duplicated per controller,
 * is what makes it impossible for a new route to forget it.
 *
 * <p><strong>Allowlist, not denylist.</strong> Lower-case alphanumeric plus {@code . _ -}, must
 * start with an alphanumeric, at most 64 characters — {@code ^[a-z0-9][a-z0-9._-]{0,63}$}, the
 * same regex {@code S1} introduced. {@link
 * es.in2.trustregistry.snapshot.infrastructure.adapter.persistence.FileSystemPublishedSnapshotRepository#fileFor}
 * keeps its own traversal denylist unchanged, as defence in depth — this filter is what actually
 * bounds the reachable key space, closing the case-folding/unicode-normalisation collision risk
 * and the S3/TD-05 unbounded-key amplification for every route, not only the snapshot one.
 *
 * <p>Missing or blank {@code X-Tenant} is intentionally not rejected here: {@code ES-02}/{@code
 * AD-5} require {@code 400} on a missing tenant, but only on routes that actually need one — a
 * future {@code /trust/v1/**} route with no tenant concept would otherwise be broken by this
 * filter. Each controller keeps its own "tenant is required" check ({@code
 * TrustSnapshotController#requireTenant}, {@code @RequestHeader} without {@code required = false}
 * on {@code TrustedEntityController}); this filter only ever rejects a tenant value that {@code
 * is} present but does not match the allowlist.
 */
@Slf4j
@Component
public class TenantAllowlistFilter extends OncePerRequestFilter {

    private static final String TENANT_HEADER = "X-Tenant";
    private static final Pattern VALID_TENANT = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final PathPattern PROTECTED_PATHS = PathPatternParser.defaultInstance.parse("/trust/v1/**");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain chain) throws ServletException, IOException {
        // quality-report.md B6: RequestPathNormalizer collapses repeated separators (e.g. "//
        // trust/v1/entities") before matching, so this filter's coverage of /trust/v1/** stays
        // consistent with SnapshotPublicationRateLimitFilter and with what the dispatcher
        // actually routes.
        if (!PROTECTED_PATHS.matches(RequestPathNormalizer.normalizedPath(request))) {
            chain.doFilter(request, response);
            return;
        }

        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId != null && !VALID_TENANT.matcher(tenantId).matches()) {
            log.warn("Rejected request to '{}': X-Tenant does not match the allowlist", request.getRequestURI());
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant header is not a valid tenant identifier");
            return;
        }

        chain.doFilter(request, response);
    }
}
