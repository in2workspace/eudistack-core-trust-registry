package es.in2.trustregistry.snapshot.domain.model;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stable, deterministic digest of everything a snapshot combines: the official anchors, the
 * tenant's private entities, and the trust profile.
 *
 * <p>This is the basis on which the version resolver decides whether anything actually changed
 * (AD-1): two publications with the same fingerprint are the same content and must not sell a
 * new version, whatever order their sources happened to be read in. Getting that "same order
 * insensitivity" wrong reintroduces, by a different path, the very bug ({@code FR-19} broken by
 * a per-request counter) this Story exists to fix — so every collection is sorted into a
 * canonical order before it is hashed, and no reliance is placed on {@code hashCode()},
 * {@code Object.toString()}, or the iteration order a caller happened to build a collection in.
 *
 * <p>Deliberately excluded from the digest: {@link TrustAnchorSet#lastSuccessfulSyncAt()} and any
 * other publication metadata (generation instant, validity window, staleness). Those describe
 * <em>when</em> the sources were last checked, not <em>what</em> they contain — including them
 * would make the fingerprint change on every re-sync even when the anchor set's content is
 * unchanged, which is exactly the "version moves without the sources changing" failure AD-1
 * forbids. One consequence, accepted deliberately: a set that has {@link
 * TrustAnchorSet#isNeverSynced() never synced} and a set that synced successfully to an empty
 * result fingerprint identically, since both carry the same (empty) anchor content. The
 * distinction between the two is transported separately, on {@code TrustSnapshot} itself.
 */
public record SnapshotFingerprint(String value) {

    private static final String SECTION_ANCHORS = "ANCHORS";
    private static final String SECTION_ENTITIES = "ENTITIES";
    private static final String SECTION_PROFILE = "PROFILE";
    private static final String FIELD_DELIMITER = "|";
    private static final String LINE_DELIMITER = "\n";
    private static final String ROLE_DELIMITER = ",";
    private static final String NULL_SENTINEL = "-";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    public SnapshotFingerprint {
        Objects.requireNonNull(value, "value must not be null");
    }

    /**
     * Computes the fingerprint of the given anchors, entities and trust profile.
     *
     * <p>Order of {@code anchorSet.anchors()} and {@code entities}, and insertion order of each
     * entity's {@link TrustedEntity#roles()}, are all irrelevant to the result: this method sorts
     * every collection into a canonical order before hashing.
     */
    public static SnapshotFingerprint of(TrustAnchorSet anchorSet, List<TrustedEntity> entities, TrustProfile trustProfile) {
        Objects.requireNonNull(anchorSet, "anchorSet must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(trustProfile, "trustProfile must not be null");

        String payload = String.join(LINE_DELIMITER,
                SECTION_ANCHORS,
                canonicalLines(anchorSet.anchors(), SnapshotFingerprint::anchorLine),
                SECTION_ENTITIES,
                canonicalLines(entities, SnapshotFingerprint::entityLine),
                SECTION_PROFILE,
                trustProfile.name());

        return new SnapshotFingerprint(sha256Hex(payload));
    }

    private static <T> String canonicalLines(List<T> items, Function<T, String> toLine) {
        return items.stream()
                .map(toLine)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(LINE_DELIMITER));
    }

    private static String anchorLine(TrustAnchor anchor) {
        return String.join(FIELD_DELIMITER,
                orSentinel(anchor.subject()),
                orSentinel(anchor.certificatePem()),
                orSentinel(anchor.territory()),
                orSentinel(anchor.serviceType()),
                orSentinel(anchor.status() == null ? null : anchor.status().name()),
                orSentinel(anchor.statusStartingTime() == null ? null : anchor.statusStartingTime().toString()),
                orSentinel(anchor.statusValidUntil() == null ? null : anchor.statusValidUntil().toString()));
    }

    private static String entityLine(TrustedEntity entity) {
        return String.join(FIELD_DELIMITER,
                orSentinel(entity.tenantId()),
                orSentinel(entity.organizationIdentifier()),
                orSentinel(entity.legalName()),
                canonicalRoles(entity.roles()),
                orSentinel(entity.certificatePem()),
                orSentinel(entity.validFrom() == null ? null : entity.validFrom().toString()),
                orSentinel(entity.validUntil() == null ? null : entity.validUntil().toString()));
    }

    private static String canonicalRoles(Set<EntityRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return NULL_SENTINEL;
        }
        return roles.stream()
                .map(EntityRole::name)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(ROLE_DELIMITER));
    }

    private static String orSentinel(String field) {
        return field == null ? NULL_SENTINEL : field;
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " must be available on every supported JVM", e);
        }
    }
}
