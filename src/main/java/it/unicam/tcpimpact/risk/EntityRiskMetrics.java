package it.unicam.tcpimpact.risk;

/**
 * Interpretable risk features for one graph entity.
 */
public record EntityRiskMetrics(
        double callExposure,
        double changeExtent,
        double changeSensitivity
) {

    public EntityRiskMetrics {
        callExposure = clamp(callExposure);
        changeExtent = clamp(changeExtent);
        changeSensitivity = clamp(changeSensitivity);
    }

    public static EntityRiskMetrics empty() {
        return new EntityRiskMetrics(0.0, 0.0, 0.0);
    }

    static double clamp(double value) {
        if(Double.isNaN(value) || value < 0.0){
            return 0.0;
        }
        if(value > 1.0){
            return 1.0;
        }
        return value;
    }
}
