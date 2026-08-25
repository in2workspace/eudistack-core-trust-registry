package es.in2.trustregistry.entities.infrastructure.controller;

import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin API over the private List of Trusted Entities of a tenant. */
@Tag(name = "Trusted entities", description = "Private List of Trusted Entities (ETSI TS 119 602)")
@RestController
@RequestMapping("/trust/v1/entities")
public class TrustedEntityController {

    private final TrustedEntityService service;

    public TrustedEntityController(TrustedEntityService service) {
        this.service = service;
    }

    @Operation(summary = "List the entities registered for the calling tenant")
    @GetMapping
    public List<TrustedEntity> list(@RequestHeader("X-Tenant") String tenantId) {
        return service.list(tenantId);
    }

    @Operation(summary = "Register or update an entity in the tenant list")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrustedEntity register(@RequestHeader("X-Tenant") String tenantId,
                                  @Valid @RequestBody TrustedEntity entity) {
        return service.register(new TrustedEntity(
                tenantId,
                entity.organizationIdentifier(),
                entity.legalName(),
                entity.roles(),
                entity.certificatePem(),
                entity.validFrom(),
                entity.validUntil()));
    }

    @Operation(summary = "Check whether an organisation is trusted for a role")
    @GetMapping("/{organizationIdentifier}/trusted")
    public boolean isTrusted(@RequestHeader("X-Tenant") String tenantId,
                             @PathVariable String organizationIdentifier,
                             @RequestParam EntityRole role) {
        return service.isTrusted(tenantId, organizationIdentifier, role);
    }

    @Operation(summary = "Remove an entity from the tenant list")
    @DeleteMapping("/{organizationIdentifier}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestHeader("X-Tenant") String tenantId,
                       @PathVariable String organizationIdentifier) {
        service.revoke(tenantId, organizationIdentifier);
    }
}
