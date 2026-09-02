package es.in2.trustregistry.snapshot.infrastructure.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapshotPublicationRateLimitFilter} (quality-report.md S2, tech-debt.md
 * TD-04): caps requests to {@code GET /trust/v1/snapshot} per (source IP, tenant) pair within a
 * fixed window, leaving every other route untouched.
 */
class SnapshotPublicationRateLimitFilterTest {

    private static final String LIMITED_PATH = "/trust/v1/snapshot";
    private static final int MAX_REQUESTS_PER_WINDOW = 30;

    private final SnapshotPublicationRateLimitFilter filter = new SnapshotPublicationRateLimitFilter();

    private static MockHttpServletRequest requestFrom(String remoteAddr, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", LIMITED_PATH);
        request.setRemoteAddr(remoteAddr);
        if (tenant != null) {
            request.addHeader("X-Tenant", tenant);
        }
        return request;
    }

    private int callAndReturnStatus(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void doFilter_RequestsWithinTheWindowLimit_AllPassThrough() throws Exception {
        // Act & Assert
        for (int i = 0; i < MAX_REQUESTS_PER_WINDOW; i++) {
            int status = callAndReturnStatus(requestFrom("10.0.0.1", "cgcom"));
            assertThat(status).isNotEqualTo(429);
        }
    }

    @Test
    void doFilter_RequestExceedsTheWindowLimit_Returns429WithRetryAfter() throws Exception {
        // Arrange: exhaust the window for this (IP, tenant) pair
        for (int i = 0; i < MAX_REQUESTS_PER_WINDOW; i++) {
            callAndReturnStatus(requestFrom("10.0.0.2", "cgcom"));
        }

        // Act
        MockHttpServletRequest request = requestFrom("10.0.0.2", "cgcom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        // Assert
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void doFilter_DifferentTenantsFromTheSameIp_AreRateLimitedIndependently() throws Exception {
        // Arrange: exhaust the window for (ip, tenant-a)
        for (int i = 0; i < MAX_REQUESTS_PER_WINDOW; i++) {
            callAndReturnStatus(requestFrom("10.0.0.3", "tenant-a"));
        }

        // Act — a different tenant from the same IP must not be collaterally throttled
        int status = callAndReturnStatus(requestFrom("10.0.0.3", "tenant-b"));

        // Assert
        assertThat(status).isNotEqualTo(429);
    }

    @Test
    void doFilter_SameTenantFromDifferentIps_AreRateLimitedIndependently() throws Exception {
        // Arrange: exhaust the window for (10.0.0.4, cgcom)
        for (int i = 0; i < MAX_REQUESTS_PER_WINDOW; i++) {
            callAndReturnStatus(requestFrom("10.0.0.4", "cgcom"));
        }

        // Act — the same tenant queried from a different source IP must not be throttled
        int status = callAndReturnStatus(requestFrom("10.0.0.5", "cgcom"));

        // Assert
        assertThat(status).isNotEqualTo(429);
    }

    @Test
    void doFilter_PathOutsideTheLimitedRoute_IsNeverRateLimited() throws Exception {
        // Arrange & Act: exhaust well past what would trip the limit if this route were tracked
        for (int i = 0; i < MAX_REQUESTS_PER_WINDOW * 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/trust/v1/jwks");
            request.setRemoteAddr("10.0.0.6");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());

            // Assert
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }
}
