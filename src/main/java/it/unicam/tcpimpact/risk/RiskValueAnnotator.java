package it.unicam.tcpimpact.risk;
import it.unicam.tcpimpact.graph.model.ChangeStatus;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.model.MethodId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Adds computed risk values to method nodes before propagation.
 */
public class RiskValueAnnotator {
    private final RiskMetricsProvider metricsProvider;
    private final RiskValueCalculator calculator;

    public RiskValueAnnotator(RiskMetricsProvider metricsProvider, RiskValueCalculator calculator) {
        this.metricsProvider = metricsProvider;
        this.calculator = calculator;
    }

    /**
     * Computes current-diff {@code riskValue} for changed nodes. Unchanged nodes keep zero base risk.
     */
    public ImpactGraph annotate(ImpactGraph graph) {
        Map<MethodId, EntityRiskMetrics> metricsByMethod = metricsProvider.metricsFor(graph);
        Set<MethodNode> nodes = new LinkedHashSet<>();
        for(MethodNode node : graph.nodes()){
            EntityRiskMetrics metrics = metricsByMethod.getOrDefault(node.id(), EntityRiskMetrics.empty());
            double riskValue = node.changeStatus() == ChangeStatus.UNCHANGED ? 0.0 : calculator.riskValue(metrics);
            nodes.add(node.withRiskValues(riskValue, riskValue));
        }
        return new ImpactGraph(nodes, graph.edges());
    }
}
