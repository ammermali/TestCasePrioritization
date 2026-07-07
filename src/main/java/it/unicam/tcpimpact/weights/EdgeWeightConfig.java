package it.unicam.tcpimpact.weights;

import it.unicam.tcpimpact.graph.model.CallKind;
import it.unicam.tcpimpact.graph.model.ImpactEdgeType;

import java.util.List;
import java.util.Map;

public record EdgeWeightConfig(
        Map<ImpactEdgeType, Double> defaults,
        Map<CallKind, Double> callKinds,
        List<EdgeWeightOverride> overrides
) {

    public EdgeWeightConfig {
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        callKinds = callKinds == null ? Map.of() : Map.copyOf(callKinds);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public static EdgeWeightConfig empty() {
        return new EdgeWeightConfig(Map.of(), Map.of(), List.of());
    }

    public record EdgeWeightOverride(
            String sourceMethodId,
            String targetMethodId,
            ImpactEdgeType edgeType,
            Double weight
    ) {
    }
}
