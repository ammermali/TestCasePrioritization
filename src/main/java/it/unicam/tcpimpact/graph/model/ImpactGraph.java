package it.unicam.tcpimpact.graph.model;

import java.util.Set;

/**
 * Directed method-level graph used to propagate risk.
 *
 * @param nodes method-level nodes declared in the analyzed project
 * @param edges directed impact edges
 */
public record ImpactGraph(Set<MethodNode> nodes, Set<ImpactEdge> edges) {
}
