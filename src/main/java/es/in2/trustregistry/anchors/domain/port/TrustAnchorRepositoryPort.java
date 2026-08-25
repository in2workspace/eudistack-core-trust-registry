package es.in2.trustregistry.anchors.domain.port;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;

import java.util.List;

/** Driven port: local persistence of the anchors resolved from official sources. */
public interface TrustAnchorRepositoryPort {

    void replaceAll(List<TrustAnchor> anchors);

    List<TrustAnchor> findAll();
}
