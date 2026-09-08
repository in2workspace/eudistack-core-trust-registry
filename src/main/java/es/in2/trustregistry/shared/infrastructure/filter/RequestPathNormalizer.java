package es.in2.trustregistry.shared.infrastructure.filter;

import org.springframework.http.server.PathContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared helper for every {@code /trust/v1/**}-scoped {@code OncePerRequestFilter} that matches
 * {@link HttpServletRequest#getRequestURI()} against a {@link org.springframework.web.util.pattern.PathPattern}.
 *
 * <p><strong>Why this exists.</strong> {@code quality-report.md} B6 found that {@code
 * PathPattern} matching alone, applied directly to the raw {@code getRequestURI()}, does not
 * bypass-proof a filter: {@code PathPattern} treats a repeated path separator as a real, distinct
 * empty segment rather than collapsing it, so {@code "//trust/v1/snapshot"} does not match a
 * pattern for {@code "/trust/v1/snapshot"} even though Spring MVC's own dispatcher (which
 * normalises the lookup path via {@code UrlPathHelper} before routing) sends it to that exact
 * controller. A servlet {@code Filter} runs before that dispatcher-side normalisation, so it must
 * normalise the URI itself before matching — otherwise a request that reaches the controller as
 * {@code /trust/v1/snapshot} can be matched inconsistently by different filters looking at the
 * same raw URI. Matrix parameters ({@code ;a=b}) need no special handling: {@link
 * PathContainer#parsePath} already strips them per path segment, matching {@code PathPattern}'s
 * own semantics.
 */
public final class RequestPathNormalizer {

    private RequestPathNormalizer() {
    }

    /**
     * Collapses consecutive {@code /} in the request's raw URI into a single {@code /} before
     * parsing it into a {@link PathContainer}, so a {@link org.springframework.web.util.pattern.PathPattern}
     * built from a normal, single-slash pattern matches the same requests the dispatcher would
     * route to that path.
     */
    public static PathContainer normalizedPath(HttpServletRequest request) {
        return PathContainer.parsePath(request.getRequestURI().replaceAll("/{2,}", "/"));
    }
}
