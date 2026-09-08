package es.in2.trustregistry.shared.infrastructure.filter;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TenantAllowlistFilter} (quality-report.md B5): the shared enforcement
 * point covering every {@code /trust/v1/**} route, not only {@code TrustSnapshotController}
 * (already covered end-to-end via {@code TrustSnapshotControllerTest}'s allowlist cases, and
 * {@code TrustedEntityControllerTest} for the route B5 found unprotected). These tests exercise
 * the filter directly, independent of any controller wiring.
 */
class TenantAllowlistFilterTest {

    private final TenantAllowlistFilter filter = new TenantAllowlistFilter();

    private int callAndReturnStatus(String path, String tenant) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (tenant != null) {
            request.addHeader("X-Tenant", tenant);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "ACME",
            "-acme",
            "acme/../other",
            "acme\\other",
            "acme with spaces",
            "acmeé",
    })
    void doFilter_TenantHeaderFailsTheAllowlist_Returns400OnAnyTrustV1Route(String invalidTenant) throws Exception {
        // Act & Assert — quality-report.md B5: every /trust/v1/** route is covered, not just
        // /trust/v1/snapshot.
        assertThat(callAndReturnStatus("/trust/v1/entities", invalidTenant)).isEqualTo(400);
        assertThat(callAndReturnStatus("/trust/v1/snapshot", invalidTenant)).isEqualTo(400);
    }

    @Test
    void doFilter_TenantHeaderIsSixtyFiveCharacters_Returns400() throws Exception {
        // Arrange
        String tooLong = "a".repeat(65);

        // Act & Assert
        assertThat(callAndReturnStatus("/trust/v1/entities", tooLong)).isEqualTo(400);
    }

    @Test
    void doFilter_TenantHeaderHasDotsAndHyphens_PassesThrough() throws Exception {
        // Act & Assert — legitimate tenant slugs using the permitted separators (._-) are not
        // rejected by the allowlist.
        assertThat(callAndReturnStatus("/trust/v1/entities", "cgcom.demo-1")).isNotEqualTo(400);
    }

    @Test
    void doFilter_TenantHeaderMissing_PassesThroughTheFilter() throws Exception {
        // Arrange & Act & Assert — a missing/blank tenant is each controller's own responsibility
        // (ES-02/AD-5); this filter only rejects a tenant value that IS present but invalid, so a
        // request with no X-Tenant at all must reach the controller layer, not be rejected here.
        assertThat(callAndReturnStatus("/trust/v1/entities", null)).isNotEqualTo(400);
    }

    @Test
    void doFilter_PathOutsideTrustV1_IsNeverInspected() throws Exception {
        // Act & Assert — an invalid tenant value on an unrelated route is not this filter's
        // concern.
        assertThat(callAndReturnStatus("/actuator/health", "../../etc/passwd")).isNotEqualTo(400);
    }

    @Test
    void doFilter_DoubleSlashPrefixVariantOfATrustV1Route_IsStillCovered() throws Exception {
        // Act & Assert — quality-report.md B6: PathPattern alone does not collapse a repeated
        // separator, so "//trust/v1/entities" would otherwise bypass PROTECTED_PATHS.matches()
        // the same way it bypassed SnapshotPublicationRateLimitFilter's exact-string comparison.
        assertThat(callAndReturnStatus("//trust/v1/entities", "../../etc/passwd")).isEqualTo(400);
    }
}
