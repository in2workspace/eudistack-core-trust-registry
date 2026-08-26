package es.in2.trustregistry.entities.infrastructure.controller;

import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view over the private List of Trusted Entities of a tenant.
 *
 * <p>There is deliberately no write operation here. The list is provisioned as
 * external configuration of the deployment — the same way the Verifier receives
 * its trusted issuers today — so the ability to change trust is bounded by who
 * can write that configuration, and the service exposes no second path to it.
 * See {@code docs/architecture.md} AD-9.
 *
 * <p>Reading is open: a list of trusted entities is a publishable artefact by
 * design, exactly like the national Trusted Lists it mirrors. The tenant header
 * selects which list to read; it is not a confidentiality boundary.
 */
@Tag(name = "Trusted entities", description = "Private List of Trusted Entities (ETSI TS 119 602)")
@RestController
@RequestMapping("/trust/v1/entities")
public class TrustedEntityController {

    private final TrustedEntityService service;

    public TrustedEntityController(TrustedEntityService service) {
        this.service = service;
    }

    @Operation(summary = "List the entities registered for the given tenant")
    @GetMapping
    public List<TrustedEntity> list(@RequestHeader("X-Tenant") String tenantId) {
        return service.list(tenantId);
    }

    @Operation(summary = "Check whether an organisation is trusted for a role")
    @GetMapping("/{organizationIdentifier}/trusted")
    public boolean isTrusted(@RequestHeader("X-Tenant") String tenantId,
                             @PathVariable String organizationIdentifier,
                             @RequestParam EntityRole role) {
        return service.isTrusted(tenantId, organizationIdentifier, role);
    }
}
