"""Combine per-project priority datasets into one training manifest."""

from __future__ import annotations
import argparse
from pathlib import Path
from typing import Any

from graph_propagation import load_json, write_json


def main() -> None:
    parser = argparse.ArgumentParser(description="Combine one or more priority-queue datasets into a single manifest.")
    parser.add_argument("--dataset", action="append", type=Path, help="Dataset JSON to include. Can be passed more than once.")
    parser.add_argument("--input-root", type=Path, default=Path("training/priority-datasets"), help="Scanned when --dataset is not provided.")
    parser.add_argument("--output", type=Path, default=Path("training/priority-datasets/all-projects-dataset.json"))
    args = parser.parse_args()

    # With no explicit --dataset arguments, scan the standard dataset root for each project's generated dataset.json
    dataset_paths = args.dataset or discover_datasets(args.input_root, args.output)
    if not dataset_paths:
        raise SystemExit("No dataset.json files found.")

    examples = []
    seen_case_ids = set()
    subjects = []
    for dataset_path in dataset_paths:
        payload = load_json(dataset_path)
        records = payload.get("examples") if isinstance(payload, dict) else payload
        if not isinstance(records, list):
            raise SystemExit(f"Dataset must contain an examples list: {dataset_path}")

        subject = infer_subject(payload, dataset_path)
        subjects.append(subject)
        for index, record in enumerate(records, start=1):
            normalized = normalize_record(record, dataset_path.parent, subject, index)
            case_id = normalized["caseId"]
            if case_id in seen_case_ids:
                normalized["caseId"] = f"{subject}-{len(seen_case_ids) + 1:05d}"
            seen_case_ids.add(normalized["caseId"])
            examples.append(normalized)

    write_json(args.output, {
        "description": "Combined priority-queue training dataset.",
        "subjects": sorted(set(subjects)),
        "sourceDatasets": [str(path) for path in dataset_paths],
        "examples": examples
    })
    print(f"Combined {len(examples)} examples from {len(dataset_paths)} dataset(s).")
    print(f"Output written to {args.output}")


def discover_datasets(input_root: Path, output: Path) -> list[Path]:
    """Find project dataset.json files while ignoring temporary outputs."""
    output_resolved = output.resolve()
    result = []
    for candidate in sorted(input_root.glob("*/dataset.json")):
        if candidate.parent.name.startswith("."):
            continue
        if candidate.resolve() == output_resolved:
            continue
        result.append(candidate)
    return result


def infer_subject(payload: Any, dataset_path: Path) -> str:
    """Prefer the dataset subject field, otherwise use the folder name."""
    if isinstance(payload, dict) and payload.get("subject"):
        return str(payload["subject"])
    return dataset_path.parent.name


def normalize_record(record: dict[str, Any], base_dir: Path, subject: str, index: int) -> dict[str, Any]:
    """Convert relative graph/queue paths into stable absolute paths."""
    case_id = str(record.get("caseId") or record.get("id") or f"{subject}-{index:04d}")
    return {
        "caseId": case_id,
        "subject": subject,
        "sourceDataset": str(base_dir / "dataset.json"),
        "graph": str(resolve_path(base_dir, required(record, "graph"))),
        "priorityQueue": str(resolve_path(base_dir, record.get("priorityQueue") or record.get("priority_queue") or record.get("queue")))
    }


def required(record: dict[str, Any], key: str) -> Any:
    value = record.get(key)
    if value is None:
        raise SystemExit(f"Missing '{key}' in dataset record.")
    return value


def resolve_path(base_dir: Path, value: Any) -> Path:
    path = Path(str(value))
    if path.is_absolute():
        return path
    return (base_dir / path).resolve()


if __name__ == "__main__":
    main()
