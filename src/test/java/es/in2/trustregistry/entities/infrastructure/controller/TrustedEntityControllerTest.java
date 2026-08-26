package es.in2.trustregistry.entities.infrastructure.controller;

import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrustedEntityController.class)
class TrustedEntityControllerTest {

    private static final String TENANT = "sandbox";
    private static final String ORG_ID = "VATES-B12345678";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrustedEntityService service;

    private static TrustedEntity entity() {
        return new TrustedEntity(TENANT, ORG_ID, "Acme SL", Set.of(EntityRole.RELYING_PARTY),
                "pem", Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void list_TenantHeaderPresent_ReturnsTheEntitiesOfThatTenant() throws Exception {
        // Arrange
        when(service.list(TENANT)).thenReturn(List.of(entity()));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/entities").header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].organizationIdentifier").value(ORG_ID));
    }

    @Test
    void list_TenantHeaderMissing_IsRejected() throws Exception {
        // Act & Assert: trust is always tenant scoped, so an unscoped call must not resolve.
        mockMvc.perform(get("/trust/v1/entities"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void isTrusted_ServiceGrantsTrust_ReturnsTrue() throws Exception {
        // Arrange
        when(service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY)).thenReturn(true);

        // Act & Assert
        mockMvc.perform(get("/trust/v1/entities/{id}/trusted", ORG_ID)
                        .header("X-Tenant", TENANT)
                        .param("role", "RELYING_PARTY"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

}
