package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Driven adapter over the European Commission DSS library (ETSI TS 119 612).
 *
 * <p>Scaffolding stub. The implementation wires a {@code TLValidationJob} with an
 * {@code LOTLSource} (pivot support on), a {@code FileCacheDataLoader} for the offline
 * cache and the official OJ keystore as the signing-certificate source, then maps the
 * resulting {@code TrustedListsCertificateSource} entries onto {@link TrustAnchor}.
 * Delivered by US-01 of the Trust Framework epic (EUD-34).
 */
@Slf4j
@Component
public class DssOfficialTrustListAdapter implements OfficialTrustListPort {

    @Override
    public Flux<TrustAnchor> fetchAnchors() {
        log.warn("DSS LOTL synchronisation is not implemented yet (US-01); returning no anchors");
        return Flux.empty();
    }
}
