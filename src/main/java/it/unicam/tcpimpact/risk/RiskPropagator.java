package it.unicam.tcpimpact.risk;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodNode;
import it.unicam.tcpimpact.model.MethodId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class RiskPropagator {
    // maxdepth for the propagation
    private static final int MAX_ITERATIONS = 20;
    private static final double MIN_IMPROVEMENT = 0.0001;

    public ImpactGraph propagate(ImpactGraph graph) {
        Map<MethodId, Double> scores = new LinkedHashMap<>();
        for(MethodNode node : graph.nodes()){
            scores.put(node.id(), Math.max(node.initialRiskValue(), node.propagatedRiskScore()));
        }

        for(int iteration = 0; iteration < MAX_ITERATIONS; iteration++){
            boolean changed = false;
            Map<MethodId, Double> nextScores = new LinkedHashMap<>(scores);
            for(ImpactEdge edge : graph.edges()){
                double sourceScore = scores.getOrDefault(edge.sourceMethodId(), 0.0);
                if(sourceScore <= 0.0){
                    continue;
                }
                double propagated = sourceScore * edge.weight();
                double current = nextScores.getOrDefault(edge.targetMethodId(), 0.0);
                if(propagated > current + MIN_IMPROVEMENT){
                    nextScores.put(edge.targetMethodId(), propagated);
                    changed = true;
                }
            }
            scores = nextScores;
            if(!changed){
                break;
            }
        }

        Set<MethodNode> propagatedNodes = new LinkedHashSet<>();
        for(MethodNode node : graph.nodes()){
            propagatedNodes.add(node.withPropagatedRiskScore(scores.getOrDefault(node.id(), 0.0)));
        }
        return new ImpactGraph(propagatedNodes, graph.edges());
    }
}
