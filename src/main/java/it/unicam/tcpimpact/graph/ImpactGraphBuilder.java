package it.unicam.tcpimpact.graph;
import it.unicam.tcpimpact.graph.model.ImpactEdge;
import it.unicam.tcpimpact.graph.model.ImpactGraph;
import it.unicam.tcpimpact.graph.model.MethodCallGraph;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a call graph into a static impact graph by reversing call dependencies.
 */
public class ImpactGraphBuilder {

    /**
     * Produces impact edges from a call graph. If method A calls method B, the impact graph contains B -> A.
     * All methods from the call graph are kept as nodes, including methods with no incoming or outgoing impact edge.
     *
     * @param callGraph method call graph to convert
     * @return project impact graph with callee-to-caller edges
     */
    public ImpactGraph fromCallGraph(MethodCallGraph callGraph) {
        Set<ImpactEdge> impacts = callGraph.calls()
                .stream()
                .map(call -> new ImpactEdge(call.callee(), call.caller()))
                .collect(Collectors.toSet());
        return new ImpactGraph(callGraph.methods(), impacts);
    }
}
