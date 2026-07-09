package it.unicam.tcpimpact.risk;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.model.MethodId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class RiskPropagator {
    private static final int DEFAULT_MAX_DEPTH = 4;

    public ImpactGraph propagate(ImpactGraph graph) {
        Map<MethodId, Double> initialRiskValues = new LinkedHashMap<>();
        for(MethodNode node : graph.nodes()){
            double initialRisk = Math.max(node.initialRiskValue(), node.propagatedRiskScore());
            if(initialRisk > 0.0){
                initialRiskValues.put(node.id(), initialRisk);
            }
        }
        Map<MethodId, Double> scores = propagateScores(graph, initialRiskValues, DEFAULT_MAX_DEPTH);

        Set<MethodNode> propagatedNodes = new LinkedHashSet<>();
        for(MethodNode node : graph.nodes()){
            propagatedNodes.add(node.withPropagatedRiskScore(scores.getOrDefault(node.id(), 0.0)));
        }
        return new ImpactGraph(propagatedNodes, graph.edges());
    }

    public Map<MethodId, Double> propagateScores(ImpactGraph graph, Map<MethodId, Double> initialRiskValues, int maxDepth) {
        Map<MethodId, Double> scores = new LinkedHashMap<>();
        Map<MethodId, Set<ImpactEdge>> outgoingEdges = outgoingEdges(graph);
        Deque<RiskContribution> frontier = new ArrayDeque<>();

        for(Map.Entry<MethodId, Double> entry : initialRiskValues.entrySet()){
            double initialRisk = entry.getValue() == null ? 0.0 : entry.getValue();
            if(initialRisk <= 0.0){
                continue;
            }
            scores.merge(entry.getKey(), initialRisk, Double::sum);
            frontier.addLast(new RiskContribution(entry.getKey(), initialRisk, 0));
        }

        while(!frontier.isEmpty()){
            RiskContribution contribution = frontier.removeFirst();
            if(contribution.depth() >= maxDepth){
                continue;
            }
            for(ImpactEdge edge : outgoingEdges.getOrDefault(contribution.methodId(), Set.of())){
                double propagated = contribution.riskValue() * edge.weight();
                if(propagated <= 0.0){
                    continue;
                }
                scores.merge(edge.targetMethodId(), propagated, Double::sum);
                frontier.addLast(new RiskContribution(edge.targetMethodId(), propagated, contribution.depth() + 1));
            }
        }

        return scores;
    }

    private Map<MethodId, Set<ImpactEdge>> outgoingEdges(ImpactGraph graph) {
        Map<MethodId, Set<ImpactEdge>> result = new LinkedHashMap<>();
        for(ImpactEdge edge : graph.edges()){
            result.computeIfAbsent(edge.sourceMethodId(), ignored -> new LinkedHashSet<>()).add(edge);
        }
        return result;
    }

    private record RiskContribution(MethodId methodId, double riskValue, int depth) {
    }
}
