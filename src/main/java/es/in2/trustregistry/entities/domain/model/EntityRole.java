package es.in2.trustregistry.entities.domain.model;

/**
 * Role a registered entity plays in the ecosystem, as defined by the EUDI ARF and
 * modelled by ETSI TS 119 602 Lists of Trusted Entities.
 */
public enum EntityRole {
    WALLET_PROVIDER,
    PID_PROVIDER,
    ATTESTATION_PROVIDER,
    RELYING_PARTY,
    ACCESS_CERTIFICATE_AUTHORITY
}
