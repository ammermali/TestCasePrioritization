package it.unicam.tcpimpact.graph.model;

import it.unicam.tcpimpact.model.MethodId;

/**
 * Directed risk propagation relation between two method-level nodes.
 *
 * @param sourceMethodId method where risk originates
 * @param targetMethodId method that may be impacted
 * @param edgeType impact relationship kind
 * @param weight propagation weight
 * @param callKind call-derived impact kind, if applicable
 * @param coverageRatio covered-to-covering method line ratio, if available
 * @param coveredLines covered source lines, if available
 */
public record ImpactEdge(
        MethodId sourceMethodId,
        MethodId targetMethodId,
        ImpactEdgeType edgeType,
        double weight,
        CallKind callKind,
        Double coverageRatio,
        Integer coveredLines
) {

    /**
     * Creates a call-derived impact edge.
     *
     * @param sourceMethodId method where risk originates
     * @param targetMethodId method that may be impacted
     * @param callKind kind of method call
     * @return call impact edge
     */
    public static ImpactEdge callImpact(MethodId sourceMethodId, MethodId targetMethodId, CallKind callKind) {
        return new ImpactEdge(sourceMethodId, targetMethodId, ImpactEdgeType.CALL_IMPACT, 1.0, callKind, null, null);
    }

    /**
     * Creates a contract impact edge.
     *
     * @param sourceMethodId contract method where risk originates
     * @param targetMethodId implementation method that may be impacted
     * @return contract impact edge
     */
    public static ImpactEdge contractImpact(MethodId sourceMethodId, MethodId targetMethodId) {
        return new ImpactEdge(sourceMethodId, targetMethodId, ImpactEdgeType.CONTRACT_IMPACT, 1.0, null, null, null);
    }

    /**
     * Creates a coverage-derived test impact edge.
     *
     * @param sourceMethodId covered production method
     * @param targetMethodId covering test method
     * @param coverageRatio covered-to-covering line ratio
     * @param coveredLines covered source lines
     * @return test impact edge
     */
    public static ImpactEdge testImpact(MethodId sourceMethodId, MethodId targetMethodId, double coverageRatio, int coveredLines) {
        return new ImpactEdge(sourceMethodId, targetMethodId, ImpactEdgeType.TEST_IMPACT, coverageRatio, null, coverageRatio, coveredLines);
    }

    public ImpactEdge withWeight(double weight) {
        return new ImpactEdge(sourceMethodId, targetMethodId, edgeType, weight, callKind, coverageRatio, coveredLines);
    }
}
