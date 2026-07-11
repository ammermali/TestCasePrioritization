"""Create reproducible train/validation/test splits for priority datasets."""

from __future__ import annotations

import argparse
import random
from pathlib import Path
from typing import Any

from graph_propagation import load_json, write_json


def main() -> None:
    parser = argparse.ArgumentParser(description="Split a priority-queue dataset into train/validation/test manifests.")
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--output-dir", type=Path, default=Path("training/priority-datasets/splits"))
    parser.add_argument("--subject", default="all-projects")
    parser.add_argument("--train-ratio", type=float, default=0.70)
    parser.add_argument("--validation-ratio", type=float, default=0.15)
    parser.add_argument("--test-ratio", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    validate_ratios(args.train_ratio, args.validation_ratio, args.test_ratio)
    payload = load_json(args.dataset)
    records = payload.get("examples") if isinstance(payload, dict) else payload
    if not isinstance(records, list):
        raise SystemExit("--dataset must contain an examples list.")
    if not records:
        raise SystemExit("--dataset contains no examples.")

    # Convert paths before shuffling so each split can be used independently of
    # the combined dataset file's location.
    normalized = [normalize_record(record, args.dataset.parent) for record in records]
    rng = random.Random(args.seed)
    rng.shuffle(normalized)

    train_count, validation_count, test_count = split_counts(
        len(normalized),
        args.train_ratio,
        args.validation_ratio
    )
    splits = {
        "train": normalized[:train_count],
        "validation": normalized[train_count:train_count + validation_count],
        "test": normalized[train_count + validation_count:train_count + validation_count + test_count]
    }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    for split_name, split_examples in splits.items():
        write_json(args.output_dir / f"{args.subject}-{split_name}.json", {
            "description": f"{split_name} split for priority-queue training.",
            "sourceDataset": str(args.dataset),
            "subject": args.subject,
            "split": split_name,
            "seed": args.seed,
            "examples": split_examples
        })

    write_json(args.output_dir / f"{args.subject}-split-summary.json", {
        "dataset": str(args.dataset),
        "subject": args.subject,
        "seed": args.seed,
        "total": len(normalized),
        "counts": {name: len(examples) for name, examples in splits.items()},
        "ratios": {
            "train": args.train_ratio,
            "validation": args.validation_ratio,
            "test": args.test_ratio
        }
    })
    print(f"Split {len(normalized)} examples into train={train_count}, validation={validation_count}, test={test_count}.")
    print(f"Outputs written to {args.output_dir}")


def normalize_record(record: dict[str, Any], base_dir: Path) -> dict[str, Any]:
    """Resolve graph and queue paths before writing split manifests."""
    normalized = dict(record)
    normalized["graph"] = str(resolve_path(base_dir, required_path(record, "graph")))
    queue_value = record.get("priorityQueue") or record.get("priority_queue") or record.get("queue")
    normalized["priorityQueue"] = str(resolve_path(base_dir, queue_value))
    return normalized


def required_path(record: dict[str, Any], key: str) -> Any:
    value = record.get(key)
    if value is None:
        raise SystemExit(f"Missing '{key}' in dataset record.")
    return value


def resolve_path(base_dir: Path, value: Any) -> Path:
    if value is None:
        raise SystemExit("Missing priority queue path in dataset record.")
    path = Path(str(value))
    if path.is_absolute():
        return path
    return (base_dir / path).resolve()


def split_counts(total: int, train_ratio: float, validation_ratio: float) -> tuple[int, int, int]:
    """Compute split sizes while keeping all three splits non-empty when possible."""
    train_count = round(total * train_ratio)
    validation_count = round(total * validation_ratio)
    if total >= 3:
        train_count = max(1, min(train_count, total - 2))
        validation_count = max(1, min(validation_count, total - train_count - 1))
    test_count = total - train_count - validation_count
    return train_count, validation_count, test_count


def validate_ratios(train: float, validation: float, test: float) -> None:
    """Reject accidental split ratios that do not describe the full dataset."""
    total = train + validation + test
    if abs(total - 1.0) > 0.0001:
        raise SystemExit(f"Split ratios must sum to 1.0, got {total}")


if __name__ == "__main__":
    main()
