package it.unicam.tcpimpact.risk;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.model.MethodId;
import java.util.Map;

/**
 * Supplies entity metrics used by the interpretable risk model.
 */
public interface RiskMetricsProvider {
    Map<MethodId, EntityRiskMetrics> metricsFor(ImpactGraph graph);
}
