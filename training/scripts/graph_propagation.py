"""Shared JSON, graph-loading, weighting, propagation, and ranking helpers."""

from __future__ import annotations
import json
from collections import defaultdict, deque
from pathlib import Path
from typing import Any


DEFAULT_FALLBACK_INITIAL_RISK = 1.0


def load_json(path: str | Path) -> Any:
    """Read UTF-8 JSON with one small helper used by all pipeline scripts."""
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: str | Path, payload: Any) -> None:
    """Write pretty, deterministic JSON and create the parent directory."""
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")


def method_id_to_string(value: Any) -> str:
    """Converts Java MethodId JSON objects into the project method-id string."""
    if isinstance(value, str):
        return value
    if not isinstance(value, dict):
        return str(value)

    package_name = value.get("packageName") or ""
    class_name = value.get("className") or ""
    method_name = value.get("methodName") or ""
    parameter_types = value.get("parameterTypes") or []
    return_type = value.get("returnType") or ""

    qualified_class = class_name
    if package_name and not class_name.startswith(package_name + "."):
        qualified_class = f"{package_name}.{class_name}"

    parameters = ",".join(str(parameter) for parameter in parameter_types)
    method_id = f"{qualified_class}#{method_name}({parameters})"
    if return_type and method_name != "<init>":
        method_id += f":{return_type}"
    return method_id


def load_graph(path: str | Path) -> dict[str, Any]:
    """Loads the exported Java impact graph JSON into normalized Python maps."""
    raw = load_json(path)
    nodes = {}
    for node in raw.get("nodes", []):
        method_id = node.get("fullSignature") or method_id_to_string(node.get("id"))
        nodes[method_id] = node

    edges = []
    adjacency = defaultdict(list)
    for edge in raw.get("edges", []):
        normalized = dict(edge)
        normalized["source"] = method_id_to_string(edge.get("sourceMethodId"))
        normalized["target"] = method_id_to_string(edge.get("targetMethodId"))
        edges.append(normalized)
        adjacency[normalized["source"]].append(normalized)

    test_methods = [
        method_id
        for method_id, node in nodes.items()
        if node.get("nodeType") == "TEST_METHOD" or node.get("isTestMethod") is True
    ]

    return {"raw": raw, "nodes": nodes, "edges": edges, "adjacency": dict(adjacency), "test_methods": test_methods}


def edge_weight(edge: dict[str, Any], weights: dict[str, Any]) -> float:
    """Resolve the effective edge weight using Java's precedence order."""
    edge_type = edge.get("edgeType")
    if "defaults" in weights:
        override = edge_override_weight(edge, weights)
        if override is not None:
            return override
        call_kind = edge.get("callKind")
        if call_kind is not None and weights.get("callKinds", {}).get(call_kind) is not None:
            return float(weights["callKinds"][call_kind])
        if edge_type in weights.get("defaults", {}):
            return float(weights["defaults"][edge_type])
        if edge.get("weight") is not None:
            return float(edge["weight"])
        return 1.0
    if edge_type in weights:
        return float(weights[edge_type])
    if edge.get("weight") is not None:
        return float(edge["weight"])
    return 1.0


def edge_override_weight(edge: dict[str, Any], weights: dict[str, Any]) -> float | None:
    """Return the matching edge-specific override, if one exists."""
    source = edge.get("source") or method_id_to_string(edge.get("sourceMethodId"))
    target = edge.get("target") or method_id_to_string(edge.get("targetMethodId"))
    edge_type = edge.get("edgeType")
    for override in weights.get("overrides", []):
        if (
            override.get("sourceMethodId") == source
            and override.get("targetMethodId") == target
            and override.get("edgeType") == edge_type
        ):
            return float(override["weight"])
    return None


def case_initial_risks(
    case: dict[str, Any],
    graph: dict[str, Any],
    fallback_initial_risk: float = DEFAULT_FALLBACK_INITIAL_RISK
) -> tuple[dict[str, float], list[str]]:
    """Returns fixed initial risk inputs for one case.

    The trainer must not learn these values. If the processed case does not
    provide them, the function first tries exported graph node risk fields and
    finally uses the documented fallback.
    """
    provided = case.get("initialRiskValues") or {}
    resolved = {}
    fallback_methods = []

    for method_id in case.get("changedMethods", []):
        resolved_method_id = resolve_graph_method_id(method_id, graph)
        if method_id in provided:
            resolved[resolved_method_id] = float(provided[method_id])
            continue
        if resolved_method_id in provided:
            resolved[resolved_method_id] = float(provided[resolved_method_id])
            continue

        node = graph["nodes"].get(resolved_method_id)
        if node is not None:
            graph_risk = float(node.get("riskValue") or node.get("initialRiskValue") or 0.0)
            if graph_risk > 0.0:
                resolved[resolved_method_id] = graph_risk
                continue

        resolved[resolved_method_id] = float(fallback_initial_risk)
        fallback_methods.append(method_id)

    return resolved, fallback_methods


def resolve_graph_method_id(method_id: str, graph: dict[str, Any]) -> str:
    """Resolve ids while ignoring return-type suffix differences if needed."""
    if method_id in graph["nodes"]:
        return method_id
    loose = method_id.split(":", 1)[0]
    for graph_method_id in graph["nodes"]:
        if graph_method_id.split(":", 1)[0] == loose:
            return graph_method_id
    return method_id


def propagate_risk(
    graph: dict[str, Any],
    changed_methods: list[str],
    initial_risk_values: dict[str, float],
    weights: dict[str, Any],
    max_depth: int = 4,
    fallback_initial_risk: float = DEFAULT_FALLBACK_INITIAL_RISK
) -> dict[str, float]:
    """Propagates risk with simple summation over bounded graph paths."""
    risk = defaultdict(float)
    frontier = deque()

    for method_id in changed_methods:
        initial_risk = float(initial_risk_values.get(method_id, fallback_initial_risk))
        if initial_risk <= 0.0:
            continue
        risk[method_id] += initial_risk
        frontier.append((method_id, initial_risk, 0))

    while frontier:
        source, source_risk, depth = frontier.popleft()
        if depth >= max_depth:
            continue

        for edge in graph["adjacency"].get(source, []):
            propagated = source_risk * edge_weight(edge, weights)
            if propagated <= 0.0:
                continue
            target = edge["target"]
            risk[target] += propagated
            frontier.append((target, propagated, depth + 1))

    return dict(risk)


def rank_tests(graph: dict[str, Any], risk_scores: dict[str, float]) -> list[str]:
    """Ranks all test method nodes by descending propagated risk."""
    return sorted(graph["test_methods"], key=lambda method_id: (-risk_scores.get(method_id, 0.0), method_id))


def rank_case(
    graph: dict[str, Any],
    case: dict[str, Any],
    weights: dict[str, Any],
    max_depth: int = 4,
    fallback_initial_risk: float = DEFAULT_FALLBACK_INITIAL_RISK
) -> tuple[list[str], dict[str, float], list[str]]:
    """Rank one case and return the ranking, scores, and fallback-risk uses."""
    initial_risks, fallback_methods = case_initial_risks(case, graph, fallback_initial_risk)
    changed_methods = [
        resolve_graph_method_id(method_id, graph)
        for method_id in case.get("changedMethods", [])
    ]
    risk_scores = propagate_risk(
        graph,
        changed_methods,
        initial_risks,
        weights,
        max_depth=max_depth,
        fallback_initial_risk=fallback_initial_risk
    )
    return rank_tests(graph, risk_scores), risk_scores, fallback_methods
