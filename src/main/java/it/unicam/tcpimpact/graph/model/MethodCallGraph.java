package it.unicam.tcpimpact.graph.model;

import it.unicam.tcpimpact.model.MethodId;

import java.util.Set;

/**
 * Directed method-level call graph for project methods.
 *
 * @param methods methods declared in the analyzed project
 * @param calls directed caller-to-callee edges between project methods
 */
public record MethodCallGraph(Set<MethodId> methods, Set<MethodCallEdge> calls) {
}
