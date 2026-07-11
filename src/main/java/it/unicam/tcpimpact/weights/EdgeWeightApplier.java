package it.unicam.tcpimpact.weights;

import it.unicam.tcpimpact.graph.model.CallKind;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.ImpactEdgeType;
import it.unicam.tcpimpact.graph.model.ImpactGraph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EdgeWeightApplier {

    public ImpactGraph apply(ImpactGraph graph, EdgeWeightConfig config) {
        Map<EdgeKey, Double> overrides = specificOverrides(config);
        Set<ImpactEdge> weightedEdges = new LinkedHashSet<>();
        for(ImpactEdge edge : graph.edges()){
            double weight = resolveWeight(edge, config, overrides);
            weightedEdges.add(edge.withWeight(weight));
        }
        return new ImpactGraph(graph.nodes(), weightedEdges);
    }

    private double resolveWeight(
            ImpactEdge edge,
            EdgeWeightConfig config,
            Map<EdgeKey, Double> overrides
    ) {
        EdgeKey key = EdgeKey.from(edge);
        Double override = overrides.get(key);
        if(override != null){
            return override;
        }
        if(edge.callKind() != null && isTrainableCallKind(edge.callKind())){
            Double callKindWeight = config.callKinds().get(edge.callKind());
            if(callKindWeight != null){
                return callKindWeight;
            }
        }
        return Optional.ofNullable(config.defaults().get(edge.edgeType())).orElse(edge.weight());
    }

    private boolean isTrainableCallKind(CallKind callKind) {
        return callKind == CallKind.NORMAL
                || callKind == CallKind.CONSTRUCTOR
                || callKind == CallKind.PRIVATE
                || callKind == CallKind.SUPER;
    }

    private Map<EdgeKey, Double> specificOverrides(EdgeWeightConfig config) {
        Map<EdgeKey, Double> overrides = new LinkedHashMap<>();
        for(EdgeWeightConfig.EdgeWeightOverride override : config.overrides()){
            EdgeKey key = new EdgeKey(override.sourceMethodId(), override.targetMethodId(), override.edgeType());
            overrides.put(key, override.weight());
        }
        return overrides;
    }

    private record EdgeKey(String sourceMethodId, String targetMethodId, ImpactEdgeType edgeType) {
        static EdgeKey from(ImpactEdge edge) {
            return new EdgeKey(edge.sourceMethodId().toString(), edge.targetMethodId().toString(), edge.edgeType());
        }
    }
}
