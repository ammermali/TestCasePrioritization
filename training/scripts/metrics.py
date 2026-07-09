"""Ranking metrics shared by training and evaluation scripts."""

from __future__ import annotations


def recall_at_k(ranked_tests: list[str], relevant_tests: list[str], k: int) -> float:
    """Return the fraction of relevant tests appearing in the first k ranks."""
    relevant = {comparison_key(test_id) for test_id in relevant_tests}
    if not relevant:
        return 0.0
    found = {comparison_key(test_id) for test_id in ranked_tests[:k]} & relevant
    return len(found) / len(relevant)


def mean_reciprocal_rank(ranked_tests: list[str], relevant_tests: list[str]) -> float:
    """Return reciprocal rank of the first relevant test, or zero if absent."""
    relevant = {comparison_key(test_id) for test_id in relevant_tests}
    if not relevant:
        return 0.0
    for index, test_id in enumerate(ranked_tests, start=1):
        if comparison_key(test_id) in relevant:
            return 1.0 / index
    return 0.0


def apfd(ranked_tests: list[str], relevant_tests: list[str]) -> float:
    """Compute APFD over a ranked test list and relevant/failing tests."""
    relevant = {comparison_key(test_id) for test_id in relevant_tests}
    total_tests = len(ranked_tests)
    total_faults = len(relevant)
    if total_tests == 0 or total_faults == 0:
        return 0.0

    rank_by_test = {comparison_key(test_id): index for index, test_id in enumerate(ranked_tests, start=1)}
    missing_rank = total_tests + 1
    rank_sum = sum(rank_by_test.get(test_id, missing_rank) for test_id in relevant)
    return 1.0 - (rank_sum / (total_tests * total_faults)) + (1.0 / (2 * total_tests))


def comparison_key(test_id: str) -> str:
    """Normalizes Java method ids for matching PIT tests to graph test nodes."""
    return str(test_id).split(":", 1)[0]


def ranking_metrics(ranked_tests: list[str], relevant_tests: list[str]) -> dict[str, float]:
    """Return the standard retrieval metrics used by older evaluation flows."""
    return {
        "recall@1": recall_at_k(ranked_tests, relevant_tests, 1),
        "recall@3": recall_at_k(ranked_tests, relevant_tests, 3),
        "recall@5": recall_at_k(ranked_tests, relevant_tests, 5),
        "recall@10": recall_at_k(ranked_tests, relevant_tests, 10),
        "mrr": mean_reciprocal_rank(ranked_tests, relevant_tests),
        "apfd": apfd(ranked_tests, relevant_tests)
    }


def selected_metric(metrics: dict[str, float], metric_name: str) -> float:
    """Read a named metric with a useful error for unsupported names."""
    normalized = metric_name.lower()
    if normalized not in metrics:
        raise ValueError(f"Unsupported metric '{metric_name}'. Available: {', '.join(sorted(metrics))}")
    return metrics[normalized]


def average_metric_dict(metric_dicts: list[dict[str, float]]) -> dict[str, float]:
    """Average a list of metric dictionaries with stable empty defaults."""
    if not metric_dicts:
        return {
            "recall@1": 0.0,
            "recall@3": 0.0,
            "recall@5": 0.0,
            "recall@10": 0.0,
            "mrr": 0.0,
            "apfd": 0.0
        }

    keys = metric_dicts[0].keys()
    return {
        key: sum(metrics[key] for metrics in metric_dicts) / len(metric_dicts)
        for key in keys
    }
