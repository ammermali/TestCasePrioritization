package it.unicam.tcpimpact.risk;

/**
 * Calculates the initial, interpretable risk value for one program entity.
 */
public class RiskValueCalculator {
    private final RiskWeights weights;

    public RiskValueCalculator() {
        this(RiskWeights.defaults());
    }

    public RiskValueCalculator(RiskWeights weights) {
        this.weights = weights;
    }

    /**
     * Computes {@code riskValue(e)} and clamps the final score to {@code [0, 1]}.
     *
     * @param metrics normalized diff-based metrics for one entity
     * @return interpretable initial risk value
     */
    public double riskValue(EntityRiskMetrics metrics) {
        double value = weights.callExposure() * metrics.callExposure()
                + weights.changeExtent() * metrics.changeExtent()
                + weights.changeSensitivity() * metrics.changeSensitivity();
        return EntityRiskMetrics.clamp(value);
    }
}
