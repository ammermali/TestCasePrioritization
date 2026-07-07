package it.unicam.tcpimpact.risk;
import it.unicam.tcpimpact.graph.model.ChangeStatus;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.ImpactEdgeType;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.model.MethodId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds diff-based risk metrics from data currently available in the impact graph.
 */
public class GraphRiskMetricsProvider implements RiskMetricsProvider {
    private final Map<MethodId, Integer> callCounts;
    private final Map<MethodId, Double> changeSensitivities;

    public GraphRiskMetricsProvider() {
        this(Map.of(), Map.of());
    }

    public GraphRiskMetricsProvider(Map<MethodId, Integer> callCounts, Map<MethodId, Double> changeSensitivities) {
        this.callCounts = callCounts == null ? Map.of() : Map.copyOf(callCounts);
        this.changeSensitivities = changeSensitivities == null ? Map.of() : Map.copyOf(changeSensitivities);
    }

    @Override
    public Map<MethodId, EntityRiskMetrics> metricsFor(ImpactGraph graph) {
        Map<MethodId, Integer> effectiveCallCounts = callCounts.isEmpty() ? callCountsFromEdges(graph) : callCounts;
        int maxCallCount = effectiveCallCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        Map<MethodId, EntityRiskMetrics> result = new LinkedHashMap<>();
        for(MethodNode node : graph.nodes()){
            int changedLines = node.changedLines().size();
            int loc = Math.max(1, node.endLine() - node.startLine() + 1);

            // call exposure captures how many project call sites depend on this method
            double callExposure = maxCallCount == 0 ? 0.0 : (double) effectiveCallCounts.getOrDefault(node.id(), 0) / maxCallCount;

            // change extent combines local method ratio with a bounded line-count pressure
            double methodRatio = changedLines == 0 ? 0.0 : (double) changedLines / loc;
            double lineCountPressure = 1.0 - Math.exp(-changedLines / 10.0);
            double changeExtent = node.changeStatus() == ChangeStatus.UNCHANGED ? 0.0 : (0.60 * methodRatio) + (0.40 * lineCountPressure);

            double changeSensitivity = node.changeStatus() == ChangeStatus.UNCHANGED
                    ? 0.0
                    : changeSensitivities.getOrDefault(node.id(), changedLines == 0 ? 0.0 : 0.10);

            result.put(node.id(), new EntityRiskMetrics(callExposure, changeExtent, changeSensitivity));
        }
        return result;
    }

    private Map<MethodId, Integer> callCountsFromEdges(ImpactGraph graph) {
        Map<MethodId, Integer> result = new LinkedHashMap<>();
        for(ImpactEdge edge : graph.edges()){
            if(edge.edgeType() == ImpactEdgeType.CALL_IMPACT){
                result.merge(edge.sourceMethodId(), 1, Integer::sum);
            }
        }
        return result;
    }
}
