package es.in2.trustregistry.anchors.domain.model;

import java.time.Instant;

/**
 * A trust anchor resolved from an official source (EU LOTL or a national Trusted List).
 *
 * @param subject          distinguished name of the anchor certificate
 * @param certificatePem   the anchor certificate, PEM encoded
 * @param territory        two-letter country code of the publishing Trusted List
 * @param serviceType      ETSI service type identifier (URI)
 * @param status           service status at {@code statusStartingTime}
 * @param statusStartingTime instant the current status became effective
 */
public record TrustAnchor(
        String subject,
        String certificatePem,
        String territory,
        String serviceType,
        TrustServiceStatus status,
        Instant statusStartingTime
) {

    public boolean isUsable() {
        return status == TrustServiceStatus.GRANTED;
    }
}
