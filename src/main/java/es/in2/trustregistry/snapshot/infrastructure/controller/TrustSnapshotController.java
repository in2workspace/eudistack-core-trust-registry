package es.in2.trustregistry.snapshot.infrastructure.controller;

import com.nimbusds.jose.JWSObject;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.application.TrustSnapshotService;
import es.in2.trustregistry.snapshot.domain.model.PublicVerificationKey;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.port.SnapshotVerificationMaterialPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer-facing API: what the Verifier, the Issuer and the wallets read.
 *
 * <p>{@code X-Tenant} is mandatory on {@link #signedSnapshot} and has no default (ES-02, AD-5 of
 * the repository): a missing or blank header is rejected with {@code 400} before {@link
 * TrustSnapshotService} is ever called, so no cross-tenant read is constructible through this
 * class — {@code tenantId} is read exactly once and passed straight through to {@link
 * TrustSnapshotService#publishFor(String)}, the only call site that uses it.
 *
 * <p>{@link #signedSnapshot} implements the conditional request mechanics of {@code AD-4}:
 * {@code ETag} is derived from {@link PublishedSnapshot#version()}, not the fingerprint — {@code
 * AC-05}/{@code AC-06}/{@code ES-04} all speak in terms of "version", and {@code AD-1} already
 * guarantees the two are equivalent as comparison keys (identical fingerprint never advances the
 * version and never re-signs). The service is always called, even when {@code If-None-Match} is
 * present: trusting the client's claimed version to skip that call would risk serving a stale
 * {@code 304} if the content changed server-side since the client's last fetch, and the call is
 * cheap precisely because {@code AD-1} skips re-signing when nothing changed. There is no
 * separate "is this version known to us" lookup: any {@code If-None-Match} value that is not an
 * exact match against the freshly computed {@code ETag} — stale, malformed, absent, or from
 * another tenant's context — falls through to the {@code 200} branch by construction, which is
 * exactly the fail-open shape {@code ES-04} requires.
 *
 * <p>{@link #plainSnapshot} is retired outside the {@code DEVELOPMENT} profile ({@code AD-6}):
 * it responds {@code 404}, not {@code 403}, so a non-development deployment does not confirm the
 * route exists at all. Where it remains available, it no longer returns a {@code TrustSnapshot}
 * Java object: {@link TrustSnapshotService}'s own Javadoc explains that {@link PublishedSnapshot}
 * stores only the already-signed document, not the unsigned snapshot it was built from, so there
 * is no spec-faithful unsigned object to hand back once a version has been resolved. This
 * endpoint instead decodes the JWS payload it already has — {@code
 * JWSObject.parse(signedDocument).getPayload()} — which is exactly {@code
 * objectMapper.writeValueAsString(snapshot)} as {@link
 * es.in2.trustregistry.snapshot.infrastructure.adapter.JwsSnapshotSigner#sign} produces it, byte
 * for byte. That keeps the troubleshooting endpoint truthful to what was actually published,
 * without re-deriving a second, potentially divergent, unsigned view and without reintroducing
 * the {@code build(String)} method that {@link TrustSnapshotService} deliberately removed.
 */
@Tag(name = "Trust snapshot", description = "Signed trust anchor snapshot consumed by every service")
@RestController
@RequestMapping("/trust/v1")
public class TrustSnapshotController {

    private final TrustSnapshotService service;
    private final SnapshotVerificationMaterialPort verificationMaterial;
    private final TrustRegistryProperties properties;

    public TrustSnapshotController(TrustSnapshotService service,
                                    SnapshotVerificationMaterialPort verificationMaterial,
                                    TrustRegistryProperties properties) {
        this.service = service;
        this.verificationMaterial = verificationMaterial;
        this.properties = properties;
    }

    @Operation(summary = "Signed snapshot of the trust anchors and entities of a tenant")
    @GetMapping(value = "/snapshot", produces = "application/jose")
    public ResponseEntity<String> signedSnapshot(
            @RequestHeader(value = "X-Tenant", required = false) String tenantId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        requireTenant(tenantId);

        PublishedSnapshot published = service.publishFor(tenantId);
        String currentETag = quotedETag(published.version());

        if (currentETag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(currentETag)
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(currentETag)
                .body(published.signedDocument());
    }

    @Operation(summary = "Unsigned snapshot payload, for troubleshooting only — DEVELOPMENT profile only")
    @GetMapping(value = "/snapshot/plain", produces = MediaType.APPLICATION_JSON_VALUE)
    public String plainSnapshot(@RequestHeader(value = "X-Tenant", required = false) String tenantId) {
        if (properties.trustProfile() != TrustProfile.DEVELOPMENT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        requireTenant(tenantId);
        String signedDocument = service.buildSigned(tenantId);
        try {
            return JWSObject.parse(signedDocument).getPayload().toString();
        } catch (ParseException error) {
            throw new IllegalStateException("Unable to decode the published trust snapshot", error);
        }
    }

    @Operation(summary = "Keys a consumer needs to verify a snapshot signature")
    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = verificationMaterial.verificationMaterial().stream()
                .map(TrustSnapshotController::toJwk)
                .toList();
        return Map.of("keys", keys);
    }

    /**
     * Only checks presence — {@code ES-02}/{@code AD-5}: a missing or blank tenant is always a
     * {@code 400}, with no default tenant. The allowlist charset/length/case check itself lives
     * in {@link es.in2.trustregistry.shared.infrastructure.filter.TenantAllowlistFilter}
     * ({@code quality-report.md} B5), shared with every other {@code /trust/v1/**} route instead
     * of duplicated here — a tenant value that reaches this method already passed that filter.
     */
    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant header is required");
        }
    }

    private static String quotedETag(long version) {
        return "\"" + version + "\"";
    }

    /** Maps the domain's private-material-free key to the wire JWKS shape (RFC 7517). */
    private static Map<String, Object> toJwk(PublicVerificationKey key) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("use", "sig");
        jwk.put("alg", "ES256");
        jwk.put("crv", key.curve());
        jwk.put("kid", key.keyId());
        jwk.put("x", key.x());
        jwk.put("y", key.y());
        return jwk;
    }
}
