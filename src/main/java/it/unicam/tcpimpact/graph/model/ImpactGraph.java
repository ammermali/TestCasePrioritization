package it.unicam.tcpimpact.graph.model;

import it.unicam.tcpimpact.model.MethodId;

import java.util.Set;

/**
 * Static project graph used to propagate risk from callees to callers.
 * The graph contains all methods found in the analyzed project; its edges represent possible propagation
 * paths and do not depend on the Git diff.
 *
 * @param methods methods declared in the analyzed project
 * @param impacts directed callee-to-caller impact edges
 */
public record ImpactGraph(Set<MethodId> methods, Set<ImpactEdge> impacts) {
}
