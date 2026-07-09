"""Evaluate priority-queue weights without changing them."""

from __future__ import annotations
import argparse
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from graph_propagation import load_json, write_json
from train_priority_queue import (
    aggregate_priority_metrics,
    ensure_java_weight_shape,
    load_training_examples,
    priority_metrics,
    propagate_graph,
    rank_tests,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate priority-queue graph weights without training.")
    parser.add_argument("--dataset", action="append", required=True, type=Path, help="Dataset JSON to evaluate. Can be passed more than once.")
    parser.add_argument("--weights", type=Path, default=Path("settings/edge-weight.json"))
    parser.add_argument("--output", type=Path, default=Path("training/results/priority-queue-evaluation.json"))
    parser.add_argument("--ranking-output", type=Path, default=Path("training/results/priority-queue-evaluation-ranking.json"))
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--top-k", type=int, default=20)
    parser.add_argument("--fallback-changed-risk", type=float, default=1.0)
    parser.add_argument("--use-overrides", action="store_true", help="Use edge-specific overrides from the weight file. Default ignores them.")
    args = parser.parse_args()

    weights = ensure_java_weight_shape(load_json(args.weights))
    if not args.use_overrides:
        # Evaluation mirrors shared training by default: edge-specific overrides are ignored unless the caller explicitly asks to include them
        weights["overrides"] = []
    # Reuse the trainer's dataset loader so train and eval interpret examples in exactly the same way
    examples = load_training_examples(SimpleNamespace(
        dataset=args.dataset,
        graph=None,
        priority_queue=None,
        fallback_changed_risk=args.fallback_changed_risk,
    ))

    cases = []
    for example in examples:
        # This is the same ranking path used during training, minus updates.
        risk_scores = propagate_graph(example["graph"], example["initialRisks"], weights, args.max_depth)
        ranking = rank_tests(example["graph"], risk_scores)
        cases.append({
            "caseId": example["caseId"],
            "graph": str(example["graphPath"]),
            "priorityQueue": str(example["priorityQueuePath"]),
            "metrics": priority_metrics(ranking, example["priorityQueue"], args.top_k),
            "rankedTests": ranking[:args.top_k],
        })

    metrics = aggregate_priority_metrics(cases)
    payload: dict[str, Any] = {
        "datasets": [str(path) for path in args.dataset],
        "weights": str(args.weights),
        "useOverrides": args.use_overrides,
        "maxDepth": args.max_depth,
        "topK": args.top_k,
        "caseCount": len(cases),
        "metrics": metrics,
        "cases": [
            {
                "caseId": case["caseId"],
                "metrics": case["metrics"]
            }
            for case in cases
        ]
    }
    write_json(args.output, payload)
    write_json(args.ranking_output, {
        **payload,
        "cases": cases
    })
    print(f"Evaluated {len(cases)} examples.")
    print(f"Top1: {metrics['top1Matches']}/{metrics['caseCount']} ({metrics['top1Accuracy']:.3f}).")
    print(f"Evaluation written to {args.output}")


if __name__ == "__main__":
    main()
