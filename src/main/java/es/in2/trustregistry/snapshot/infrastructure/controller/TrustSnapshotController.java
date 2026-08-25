package es.in2.trustregistry.snapshot.infrastructure.controller;

import es.in2.trustregistry.snapshot.application.TrustSnapshotService;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.infrastructure.adapter.JwsSnapshotSigner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Consumer-facing API: what the Verifier, the Issuer and the wallets read. */
@Tag(name = "Trust snapshot", description = "Signed trust anchor snapshot consumed by every service")
@RestController
@RequestMapping("/trust/v1")
public class TrustSnapshotController {

    private final TrustSnapshotService service;
    private final JwsSnapshotSigner signer;

    public TrustSnapshotController(TrustSnapshotService service, JwsSnapshotSigner signer) {
        this.service = service;
        this.signer = signer;
    }

    @Operation(summary = "Signed snapshot of the trust anchors and entities of a tenant")
    @GetMapping(value = "/snapshot", produces = "application/jose")
    public String signedSnapshot(@RequestHeader("X-Tenant") String tenantId) {
        return service.buildSigned(tenantId);
    }

    @Operation(summary = "Unsigned snapshot, for troubleshooting only")
    @GetMapping(value = "/snapshot/plain", produces = MediaType.APPLICATION_JSON_VALUE)
    public TrustSnapshot plainSnapshot(@RequestHeader("X-Tenant") String tenantId) {
        return service.build(tenantId);
    }

    @Operation(summary = "Keys a consumer needs to verify a snapshot signature")
    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(signer.publicKey().toJSONObject()));
    }
}
