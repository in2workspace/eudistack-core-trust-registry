package es.in2.trustregistry.anchors.domain.model;

/**
 * Service status of a trust service as published in an ETSI TS 119 612 Trusted List.
 * Only {@link #GRANTED} makes an anchor usable for chain validation.
 */
public enum TrustServiceStatus {
    GRANTED,
    WITHDRAWN,
    SUSPENDED,
    UNKNOWN
}
