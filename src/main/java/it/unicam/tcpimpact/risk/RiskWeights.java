package it.unicam.tcpimpact.risk;

/**
 * Coefficients for the diff-based initial risk model.
 */
public record RiskWeights(
        double callExposure,
        double changeExtent,
        double changeSensitivity
) {

    public static RiskWeights defaults() {
        return new RiskWeights(0.40, 0.35, 0.25);
    }
}
