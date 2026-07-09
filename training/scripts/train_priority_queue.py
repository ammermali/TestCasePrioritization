"""Train graph edge weights from risk-initialized graph/priority-queue cases.

The default mode updates shared Java edge-weight buckets (`callKinds` and
`defaults`) instead of writing edge-specific overrides. Edge overrides remain
available only for compatibility experiments.
"""

from __future__ import annotations

import argparse
from collections import defaultdict, deque
from pathlib import Path
from typing import Any

from graph_propagation import edge_weight, load_graph, load_json, write_json
from metrics import comparison_key


CALL_KINDS = ["NORMAL", "CONSTRUCTOR", "SUPER", "PRIVATE", "METHOD_REFERENCE", "LAMBDA", "EXTERNAL", "UNRESOLVED"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Train edge weights from a risk-initialized graph and an expected test priority queue.")
    parser.add_argument("--graph", type=Path, help="Risk-initialized impact graph JSON.")
    parser.add_argument("--priority-queue", type=Path, help="Expected test priority queue JSON.")
    parser.add_argument("--dataset", action="append", type=Path, help="JSON list of graph/priority-queue training examples. Can be passed more than once.")
    parser.add_argument("--weights", type=Path, default=Path("settings/edge-weight.json"), help="Initial edge weights JSON.")
    parser.add_argument("--output", type=Path, default=Path("settings/edge-weight.json"), help="Trained edge weights output JSON.")
    parser.add_argument("--summary", type=Path, default=Path("training/results/priority-queue-training-summary.json"))
    parser.add_argument("--ranking-output", type=Path, default=Path("training/results/priority-queue-final-ranking.json"))
    parser.add_argument("--update-scope", choices=("shared", "edge-override"), default="shared", help="Use shared callKind/default weights by default; edge-override keeps the old edge-specific behavior.")
    parser.add_argument("--use-overrides", action="store_true", help="Use existing edge-specific overrides while training. By default shared training ignores them.")
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--learning-rate", type=float, default=0.05)
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--top-k", type=int, default=20)
    parser.add_argument("--max-edges-per-update", type=int, default=30)
    parser.add_argument("--max-buckets-per-update", type=int, default=10)
    parser.add_argument("--fallback-changed-risk", type=float, default=1.0)
    parser.add_argument("--min-weight", type=float, default=0.0)
    parser.add_argument("--max-weight", type=float, default=1.0)
    args = parser.parse_args()

    weights = ensure_java_weight_shape(load_json(args.weights))
    if args.update_scope == "shared" and not args.use_overrides:
        # Shared training should learn reusable weights, not memorize individual
        # source/target edges from the current dataset.
        weights["overrides"] = []
    examples = load_training_examples(args)
    if not examples:
        raise SystemExit("No training examples loaded.")

    history = []
    consecutive_matches = 0
    for iteration in range(1, args.iterations + 1):
        # Cycle through the examples so small datasets can still run for more
        # iterations without duplicating files on disk.
        example = examples[(iteration - 1) % len(examples)]
        risk_scores = propagate_graph(example["graph"], example["initialRisks"], weights, args.max_depth)
        ranked_tests = rank_tests(example["graph"], risk_scores)
        metrics = priority_metrics(ranked_tests, example["priorityQueue"], args.top_k)
        mismatch = first_mismatch(ranked_tests, example["priorityQueue"], args.top_k)

        entry = {
            "iteration": iteration,
            "caseId": example["caseId"],
            "graph": str(example["graphPath"]),
            "priorityQueue": str(example["priorityQueuePath"]),
            "metrics": metrics,
            "mismatch": mismatch,
            "updates": []
        }

        if mismatch is None:
            entry["status"] = "matched"
            history.append(entry)
            consecutive_matches += 1
            # Stop early only after a full pass where every example already matches
            if consecutive_matches >= len(examples):
                break
            continue
        consecutive_matches = 0

        update = update_weights_for_mismatch(
            example["graph"],
            example["initialRisks"],
            weights,
            expected_test=mismatch["expectedTest"],
            predicted_test=mismatch["predictedTest"],
            learning_rate=args.learning_rate,
            max_depth=args.max_depth,
            max_edges=args.max_edges_per_update,
            max_buckets=args.max_buckets_per_update,
            min_weight=args.min_weight,
            max_weight=args.max_weight,
            update_scope=args.update_scope,
        )
        entry["status"] = update["status"]
        entry["updates"] = update["updates"]
        history.append(entry)

    final_cases = []
    for example in examples:
        final_risk_scores = propagate_graph(example["graph"], example["initialRisks"], weights, args.max_depth)
        final_ranking = rank_tests(example["graph"], final_risk_scores)
        final_cases.append({
            "caseId": example["caseId"],
            "graph": str(example["graphPath"]),
            "priorityQueue": str(example["priorityQueuePath"]),
            "metrics": priority_metrics(final_ranking, example["priorityQueue"], args.top_k),
            "rankedTests": final_ranking[:args.top_k]
        })

    aggregate_metrics = aggregate_priority_metrics(final_cases)
    total_updates = sum(len(entry.get("updates", [])) for entry in history)
    write_json(args.output, weights)
    write_json(args.ranking_output, {
        "datasets": dataset_paths(args),
        "maxDepth": args.max_depth,
        "topK": args.top_k,
        "metrics": aggregate_metrics,
        "cases": final_cases
    })
    write_json(args.summary, {
        "datasets": dataset_paths(args),
        "inputWeights": str(args.weights),
        "outputWeights": str(args.output),
        "rankingOutput": str(args.ranking_output),
        "updateScope": args.update_scope,
        "useOverrides": args.use_overrides,
        "iterationsRequested": args.iterations,
        "iterationsRun": len(history),
        "learningRate": args.learning_rate,
        "maxDepth": args.max_depth,
        "topK": args.top_k,
        "maxBucketsPerUpdate": args.max_buckets_per_update,
        "maxEdgesPerUpdate": args.max_edges_per_update,
        "trainingExampleCount": len(examples),
        "totalUpdates": total_updates,
        "metrics": aggregate_metrics,
        "finalCases": [
            {
                "caseId": case["caseId"],
                "metrics": case["metrics"]
            }
            for case in final_cases
        ],
        "history": history
    })
    print(f"Trained {len(history)} iterations on {len(examples)} examples.")
    print(f"Update scope: {args.update_scope}; updates applied: {total_updates}.")
    print(f"Final top1: {aggregate_metrics['top1Matches']}/{aggregate_metrics['caseCount']} ({aggregate_metrics['top1Accuracy']:.3f}).")
    print(f"Weights written to {args.output}")
    print(f"Summary written to {args.summary}")


def load_training_examples(args: argparse.Namespace) -> list[dict[str, Any]]:
    """Load either one graph/queue pair or one or more dataset manifests."""
    if args.dataset:
        examples = []
        next_index = 1
        for dataset_path in args.dataset:
            payload = load_json(dataset_path)
            if isinstance(payload, dict):
                records = payload.get("examples") or payload.get("cases") or []
            else:
                records = payload
            if not isinstance(records, list):
                raise SystemExit("--dataset must be a JSON list or an object with examples/cases.")
            base_dir = dataset_path.parent
            for record in records:
                examples.append(load_training_example(record, base_dir, args.fallback_changed_risk, next_index))
                next_index += 1
        return examples

    if not args.graph or not args.priority_queue:
        raise SystemExit("Provide either --dataset or both --graph and --priority-queue.")
    return [
        load_training_example(
            {
                "caseId": args.graph.stem,
                "graph": str(args.graph),
                "priorityQueue": str(args.priority_queue)
            },
            Path("."),
            args.fallback_changed_risk,
            1
        )
    ]


def dataset_paths(args: argparse.Namespace) -> list[str] | None:
    """Return dataset paths in a JSON-friendly shape for summaries."""
    if not args.dataset:
        return None
    return [str(path) for path in args.dataset]


def load_training_example(record: dict[str, Any], base_dir: Path, fallback_changed_risk: float, index: int) -> dict[str, Any]:
    """Load one graph and its expected queue into the trainer's runtime form."""
    graph_path = resolve_record_path(base_dir, required_record_value(record, "graph"))
    queue_path = resolve_record_path(base_dir, record.get("priorityQueue") or record.get("priority_queue") or record.get("queue"))
    graph = load_graph(graph_path)
    priority_queue = load_priority_queue(load_json(queue_path))
    if not priority_queue:
        raise SystemExit(f"Priority queue is empty: {queue_path}")
    initial_risks = graph_initial_risks(graph, fallback_changed_risk)
    if not initial_risks:
        raise SystemExit(f"Graph has no initial risk values and no changed nodes: {graph_path}")
    return {
        "caseId": record.get("caseId") or record.get("id") or f"case-{index:04d}",
        "graphPath": graph_path,
        "priorityQueuePath": queue_path,
        "graph": graph,
        "priorityQueue": priority_queue,
        "initialRisks": initial_risks
    }


def resolve_record_path(base_dir: Path, value: Any) -> Path:
    if value is None:
        raise SystemExit("Missing path in training example.")
    path = Path(str(value))
    if path.is_absolute():
        return path
    return (base_dir / path).resolve()


def required_record_value(record: dict[str, Any], key: str) -> Any:
    value = record.get(key)
    if value is None:
        raise SystemExit(f"Missing '{key}' in training example.")
    return value


def load_priority_queue(payload: Any) -> list[str]:
    """Accept the queue field names used by generated and hand-written files."""
    if isinstance(payload, list):
        return normalize_queue(payload)
    if not isinstance(payload, dict):
        return []

    for key in ("priorityQueue", "testPriorityQueue", "rankedTests", "tests", "expectedRanking"):
        value = payload.get(key)
        if value:
            return normalize_queue(value)
    return []


def normalize_queue(values: list[Any]) -> list[str]:
    """Normalize queue entries and remove duplicates without changing order."""
    result = []
    seen = set()
    for value in values:
        test_id = test_id_from_value(value)
        if not test_id:
            continue
        key = comparison_key(test_id)
        if key in seen:
            continue
        seen.add(key)
        result.append(test_id)
    return result


def test_id_from_value(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("testId", "test", "id", "methodId", "name"):
            if value.get(key):
                return str(value[key])
    return None


def graph_initial_risks(graph: dict[str, Any], fallback_changed_risk: float) -> dict[str, float]:
    """Read fixed initial risk values from the risk-initialized case graph."""
    risks = {}
    changed_nodes = []
    for method_id, node in graph["nodes"].items():
        risk = float(node.get("initialRiskValue") or node.get("riskValue") or 0.0)
        if risk > 0.0:
            risks[method_id] = risk
        if node.get("changeStatus") and node.get("changeStatus") != "UNCHANGED":
            changed_nodes.append(method_id)

    if risks:
        return risks
    return {method_id: fallback_changed_risk for method_id in changed_nodes if fallback_changed_risk > 0.0}


def propagate_graph(
    graph: dict[str, Any],
    initial_risks: dict[str, float],
    weights: dict[str, Any],
    max_depth: int
) -> dict[str, float]:
    """Propagate risk by summing bounded path contributions."""
    scores = defaultdict(float)
    frontier = deque()

    for method_id, risk_value in initial_risks.items():
        if risk_value <= 0.0:
            continue
        scores[method_id] += risk_value
        frontier.append((method_id, risk_value, 0))

    while frontier:
        source, source_risk, depth = frontier.popleft()
        if depth >= max_depth:
            continue
        for edge in graph["adjacency"].get(source, []):
            propagated = source_risk * edge_weight(edge, weights)
            if propagated <= 0.0:
                continue
            target = edge["target"]
            scores[target] += propagated
            frontier.append((target, propagated, depth + 1))

    return dict(scores)


def rank_tests(graph: dict[str, Any], risk_scores: dict[str, float]) -> list[dict[str, Any]]:
    """Rank test methods by score, then method id for deterministic ties."""
    return [
        {"testId": test_id, "score": risk_scores.get(test_id, 0.0)}
        for test_id in sorted(graph["test_methods"], key=lambda method_id: (-risk_scores.get(method_id, 0.0), method_id))
    ]


def priority_metrics(ranked_tests: list[dict[str, Any]], priority_queue: list[str], top_k: int) -> dict[str, Any]:
    """Compute the ranking metrics used by the priority-queue objective."""
    predicted_keys = [comparison_key(entry["testId"]) for entry in ranked_tests]
    expected_keys = [comparison_key(test_id) for test_id in priority_queue]
    top_k = min(top_k, len(expected_keys), len(predicted_keys))

    exact_prefix = 0
    for index in range(top_k):
        if predicted_keys[index] != expected_keys[index]:
            break
        exact_prefix += 1

    expected_top = expected_keys[0] if expected_keys else None
    predicted_top = predicted_keys[0] if predicted_keys else None
    expected_ranks = {
        test_key: index + 1
        for index, test_key in enumerate(expected_keys)
    }
    predicted_rank_by_test = {
        test_key: index + 1
        for index, test_key in enumerate(predicted_keys)
    }
    rank_error = 0.0
    compared = 0
    missing_rank = len(predicted_keys) + 1
    for test_key, expected_rank in expected_ranks.items():
        if expected_rank > top_k:
            continue
        predicted_rank = predicted_rank_by_test.get(test_key, missing_rank)
        rank_error += abs(predicted_rank - expected_rank)
        compared += 1

    return {
        "top1Match": expected_top is not None and expected_top == predicted_top,
        "exactPrefix": exact_prefix,
        "topK": top_k,
        "topKOrderAccuracy": 0.0 if top_k == 0 else exact_prefix / top_k,
        "meanAbsoluteRankError": 0.0 if compared == 0 else rank_error / compared,
        "expectedTopTest": priority_queue[0] if priority_queue else None,
        "predictedTopTest": ranked_tests[0]["testId"] if ranked_tests else None
    }


def first_mismatch(ranked_tests: list[dict[str, Any]], priority_queue: list[str], top_k: int) -> dict[str, Any] | None:
    """Return the first rank where predicted and expected queues disagree."""
    limit = min(top_k, len(priority_queue), len(ranked_tests))
    for index in range(limit):
        expected = priority_queue[index]
        predicted = ranked_tests[index]["testId"]
        if comparison_key(expected) != comparison_key(predicted):
            return {
                "rank": index + 1,
                "expectedTest": expected,
                "predictedTest": predicted
            }
    return None


def update_weights_for_mismatch(
    graph: dict[str, Any],
    initial_risks: dict[str, float],
    weights: dict[str, Any],
    expected_test: str,
    predicted_test: str,
    learning_rate: float,
    max_depth: int,
    max_edges: int,
    max_buckets: int,
    min_weight: float,
    max_weight: float,
    update_scope: str
) -> dict[str, Any]:
    """Update weights using support difference between expected and wrong paths."""
    expected_support = path_edge_support(graph, initial_risks, weights, expected_test, max_depth)
    predicted_support = path_edge_support(graph, initial_risks, weights, predicted_test, max_depth)
    expected_norm = normalize_support(expected_support)
    predicted_norm = normalize_support(predicted_support)

    if not expected_norm and not predicted_norm:
        return {"status": "no-path-support", "updates": []}

    edge_by_key = {
        **{key: entry["edge"] for key, entry in expected_support.items()},
        **{key: entry["edge"] for key, entry in predicted_support.items()}
    }
    deltas = []
    for key in set(expected_norm) | set(predicted_norm):
        delta = expected_norm.get(key, 0.0) - predicted_norm.get(key, 0.0)
        if abs(delta) > 1e-12:
            deltas.append((key, delta))
    deltas.sort(key=lambda item: abs(item[1]), reverse=True)

    if update_scope == "edge-override":
        return update_edge_overrides(
            weights,
            edge_by_key,
            deltas,
            expected_norm,
            predicted_norm,
            learning_rate,
            max_edges,
            min_weight,
            max_weight
        )
    return update_shared_weight_buckets(
        weights,
        edge_by_key,
        deltas,
        expected_norm,
        predicted_norm,
        learning_rate,
        max_buckets,
        min_weight,
        max_weight
    )


def update_edge_overrides(
    weights: dict[str, Any],
    edge_by_key: dict[tuple[str, str, str], dict[str, Any]],
    deltas: list[tuple[tuple[str, str, str], float]],
    expected_norm: dict[tuple[str, str, str], float],
    predicted_norm: dict[tuple[str, str, str], float],
    learning_rate: float,
    max_edges: int,
    min_weight: float,
    max_weight: float
) -> dict[str, Any]:
    """Apply the old edge-specific update rule."""
    updates = []
    for key, delta in deltas[:max_edges]:
        edge = edge_by_key[key]
        old_weight = edge_weight(edge, weights)
        new_weight = clamp(old_weight + (learning_rate * delta), min_weight, max_weight)
        set_edge_override(weights, edge, new_weight)
        updates.append({
            "sourceMethodId": edge["source"],
            "targetMethodId": edge["target"],
            "edgeType": edge.get("edgeType"),
            "oldWeight": old_weight,
            "newWeight": new_weight,
            "delta": delta,
            "expectedSupport": expected_norm.get(key, 0.0),
            "predictedSupport": predicted_norm.get(key, 0.0)
        })

    return {"status": "updated" if updates else "zero-delta", "updates": updates}


def update_shared_weight_buckets(
    weights: dict[str, Any],
    edge_by_key: dict[tuple[str, str, str], dict[str, Any]],
    deltas: list[tuple[tuple[str, str, str], float]],
    expected_norm: dict[tuple[str, str, str], float],
    predicted_norm: dict[tuple[str, str, str], float],
    learning_rate: float,
    max_buckets: int,
    min_weight: float,
    max_weight: float
) -> dict[str, Any]:
    """Aggregate edge support deltas into reusable callKind/default buckets."""
    bucket_deltas: dict[tuple[str, str], dict[str, Any]] = {}
    for key, delta in deltas:
        edge = edge_by_key[key]
        bucket = weight_bucket(edge)
        if bucket is None:
            continue
        entry = bucket_deltas.setdefault(bucket, {
            "delta": 0.0,
            "expectedSupport": 0.0,
            "predictedSupport": 0.0,
            "edgeCount": 0
        })
        entry["delta"] += delta
        entry["expectedSupport"] += expected_norm.get(key, 0.0)
        entry["predictedSupport"] += predicted_norm.get(key, 0.0)
        entry["edgeCount"] += 1

    ordered = [
        item
        for item in sorted(bucket_deltas.items(), key=lambda item: abs(item[1]["delta"]), reverse=True)
        if abs(item[1]["delta"]) > 1e-12
    ]
    updates = []
    for bucket, entry in ordered[:max_buckets]:
        old_weight = shared_weight(weights, bucket)
        new_weight = clamp(old_weight + (learning_rate * entry["delta"]), min_weight, max_weight)
        if abs(new_weight - old_weight) <= 1e-12:
            continue
        set_shared_weight(weights, bucket, new_weight)
        updates.append({
            "scope": "shared",
            "weightGroup": bucket[0],
            "weightName": bucket[1],
            "oldWeight": old_weight,
            "newWeight": new_weight,
            "delta": entry["delta"],
            "expectedSupport": entry["expectedSupport"],
            "predictedSupport": entry["predictedSupport"],
            "edgeCount": entry["edgeCount"]
        })

    return {"status": "updated" if updates else "zero-delta", "updates": updates}


def path_edge_support(
    graph: dict[str, Any],
    initial_risks: dict[str, float],
    weights: dict[str, Any],
    target_test: str,
    max_depth: int
) -> dict[tuple[str, str, str], dict[str, Any]]:
    """Collect how much each edge contributes to paths reaching target_test."""
    target_key = comparison_key(target_test)
    support: dict[tuple[str, str, str], dict[str, Any]] = {}
    frontier = deque((method_id, risk_value, 0, []) for method_id, risk_value in initial_risks.items() if risk_value > 0.0)

    while frontier:
        source, source_risk, depth, path_edges = frontier.popleft()
        if depth >= max_depth:
            continue
        for edge in graph["adjacency"].get(source, []):
            propagated = source_risk * edge_weight(edge, weights)
            if propagated <= 0.0:
                continue
            next_path = [*path_edges, edge]
            target = edge["target"]
            if comparison_key(target) == target_key:
                for path_edge in next_path:
                    key = edge_key(path_edge)
                    entry = support.setdefault(key, {"edge": path_edge, "support": 0.0})
                    entry["support"] += propagated
            frontier.append((target, propagated, depth + 1, next_path))

    return support


def normalize_support(support: dict[tuple[str, str, str], dict[str, Any]]) -> dict[tuple[str, str, str], float]:
    """Convert absolute path support into a distribution that sums to one."""
    total = sum(entry["support"] for entry in support.values())
    if total <= 0.0:
        return {}
    return {key: entry["support"] / total for key, entry in support.items()}


def set_edge_override(weights: dict[str, Any], edge: dict[str, Any], value: float) -> None:
    """Insert or replace one edge-specific override entry."""
    overrides = weights.setdefault("overrides", [])
    for override in overrides:
        if (
            override.get("sourceMethodId") == edge["source"]
            and override.get("targetMethodId") == edge["target"]
            and override.get("edgeType") == edge.get("edgeType")
        ):
            override["weight"] = value
            return
    overrides.append({
        "sourceMethodId": edge["source"],
        "targetMethodId": edge["target"],
        "edgeType": edge.get("edgeType"),
        "weight": value
    })


def weight_bucket(edge: dict[str, Any]) -> tuple[str, str] | None:
    """Map an edge to the shared Java weight that controls it."""
    edge_type = edge.get("edgeType")
    call_kind = edge.get("callKind")
    if edge_type == "CALL_IMPACT" and call_kind:
        return "callKinds", str(call_kind)
    if edge_type:
        return "defaults", str(edge_type)
    return None


def shared_weight(weights: dict[str, Any], bucket: tuple[str, str]) -> float:
    """Read a shared bucket weight with the same fallback as Java/Python."""
    group, name = bucket
    if group == "callKinds":
        fallback = weights.get("defaults", {}).get("CALL_IMPACT", 0.75)
        return float(weights.get("callKinds", {}).get(name, fallback))
    return float(weights.get("defaults", {}).get(name, 1.0))


def set_shared_weight(weights: dict[str, Any], bucket: tuple[str, str], value: float) -> None:
    """Write a shared callKind/default bucket weight."""
    group, name = bucket
    if group == "callKinds":
        weights.setdefault("callKinds", {})[name] = value
        return
    weights.setdefault("defaults", {})[name] = value


def ensure_java_weight_shape(weights: dict[str, Any]) -> dict[str, Any]:
    """Normalize legacy or partial weight files into the Java JSON shape."""
    if "defaults" not in weights:
        call_weight = float(weights.get("CALL_IMPACT", 0.75))
        weights = {
            "defaults": {
                "CALL_IMPACT": call_weight,
                "CONTRACT_IMPACT": float(weights.get("CONTRACT_IMPACT", 0.85)),
                "TEST_IMPACT": float(weights.get("TEST_IMPACT", 1.0)),
            },
            "callKinds": {call_kind: call_weight for call_kind in CALL_KINDS},
            "overrides": [],
        }
    weights.setdefault("defaults", {})
    weights.setdefault("callKinds", {})
    weights.setdefault("overrides", [])
    for call_kind in CALL_KINDS:
        weights["callKinds"].setdefault(call_kind, weights["defaults"].get("CALL_IMPACT", 0.75))
    return weights


def edge_key(edge: dict[str, Any]) -> tuple[str, str, str]:
    return edge["source"], edge["target"], edge.get("edgeType", "")


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def aggregate_priority_metrics(final_cases: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate per-case priority metrics for a training/evaluation summary."""
    case_count = len(final_cases)
    if case_count == 0:
        return {
            "caseCount": 0,
            "top1Matches": 0,
            "top1Accuracy": 0.0,
            "meanExactPrefix": 0.0,
            "meanTopKOrderAccuracy": 0.0,
            "meanAbsoluteRankError": 0.0
        }
    top1_matches = sum(1 for case in final_cases if case["metrics"].get("top1Match"))
    return {
        "caseCount": case_count,
        "top1Matches": top1_matches,
        "top1Accuracy": top1_matches / case_count,
        "meanExactPrefix": average_metric(final_cases, "exactPrefix"),
        "meanTopKOrderAccuracy": average_metric(final_cases, "topKOrderAccuracy"),
        "meanAbsoluteRankError": average_metric(final_cases, "meanAbsoluteRankError")
    }


def average_metric(final_cases: list[dict[str, Any]], key: str) -> float:
    return sum(float(case["metrics"].get(key, 0.0)) for case in final_cases) / len(final_cases)


if __name__ == "__main__":
    main()
