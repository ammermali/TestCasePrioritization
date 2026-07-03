package it.unicam.tcpimpact.graph.model;

import it.unicam.tcpimpact.model.MethodId;

/**
 * Directed impact relation produced by reversing a method call.
 * If method A calls method B, the impact graph contains the edge B -> A.
 *
 * @param callee method that is called and can propagate impact to its callers
 * @param caller method that depends on the callee
 */
public record ImpactEdge(MethodId callee, MethodId caller) {
}
