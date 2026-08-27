package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driven adapter over the European Commission DSS library (ETSI TS 119 612).
 *
 * <p>Scaffolding stub. The implementation wires a {@code TLValidationJob} with an
 * {@code LOTLSource} (pivot support on), a {@code FileCacheDataLoader} for the offline
 * cache and the official OJ keystore as the signing-certificate source, then maps the
 * resulting {@code TrustedListsCertificateSource} entries onto
 * {@link es.in2.trustregistry.anchors.domain.model.TrustAnchor}.
 * Delivered by US-01 of the Trust Framework epic (EUD-34).
 *
 * <p>DSS is blocking by design, which is why this service runs WebMvc on virtual
 * threads rather than WebFlux.
 */
@Slf4j
@Component
public class DssOfficialTrustListAdapter implements OfficialTrustListPort {

    @Override
    public SyncOutcome fetchAnchors() {
        log.warn("DSS LOTL synchronisation is not implemented yet (US-01); returning no anchors");
        return new SyncOutcome(List.of(), List.of());
    }
}
