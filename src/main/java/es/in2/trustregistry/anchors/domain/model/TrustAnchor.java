package es.in2.trustregistry.anchors.domain.model;

import java.time.Instant;

/**
 * A trust anchor resolved from an official source (EU LOTL or a national Trusted List).
 *
 * <p>The status window is part of the anchor because eIDAS judges whether a service was
 * qualified <em>at the date of the act</em>, not at the date of the query: a provider
 * withdrawn yesterday was still qualified when it sealed a credential three months ago.
 * That is why synchronisation keeps every anchor it finds, whatever its status, and
 * usability is a question asked against an instant.
 *
 * @param subject            distinguished name of the anchor certificate
 * @param certificatePem     the anchor certificate, PEM encoded
 * @param territory          two-letter country code of the publishing Trusted List
 * @param serviceType        ETSI service type identifier (URI)
 * @param status             service status during the window below
 * @param statusStartingTime instant the status became effective
 * @param statusValidUntil   instant the status ceased to apply, or {@code null} while it still does
 */
public record TrustAnchor(
        String subject,
        String certificatePem,
        String territory,
        String serviceType,
        TrustServiceStatus status,
        Instant statusStartingTime,
        Instant statusValidUntil
) {

    /**
     * Whether this anchor may be relied upon for an act that happened at {@code moment}.
     *
     * <p>There is deliberately no argument-less variant: an anchor is never usable in the
     * abstract, only with respect to a point in time.
     */
    public boolean isUsableAt(Instant moment) {
        if (status != TrustServiceStatus.GRANTED) {
            return false;
        }
        boolean started = statusStartingTime == null || !moment.isBefore(statusStartingTime);
        boolean stillApplies = statusValidUntil == null || moment.isBefore(statusValidUntil);
        return started && stillApplies;
    }
}
