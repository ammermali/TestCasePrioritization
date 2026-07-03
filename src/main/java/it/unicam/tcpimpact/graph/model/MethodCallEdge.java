package it.unicam.tcpimpact.graph.model;

import it.unicam.tcpimpact.model.MethodId;

/**
 * Directed call relation from a caller method to the method it invokes.
 *
 * @param caller method containing the call expression
 * @param callee project method invoked by the caller
 */
public record MethodCallEdge(MethodId caller, MethodId callee) {
}
