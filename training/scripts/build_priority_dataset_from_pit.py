"""Build priority-queue training examples from a Java graph and PIT output."""

from __future__ import annotations
import argparse
import copy
import shutil
import xml.etree.ElementTree as ET
from collections import defaultdict, deque
from pathlib import Path
from typing import Any
from uuid import uuid4

from graph_propagation import load_json, method_id_to_string, write_json
from metrics import comparison_key


PRIMITIVE_TYPES = {
    "B": "byte",
    "C": "char",
    "D": "double",
    "F": "float",
    "I": "int",
    "J": "long",
    "S": "short",
    "Z": "boolean",
    "V": "void",
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Build graph + priority queue training artifacts from PIT mutations.xml.")
    parser.add_argument("--subject", required=True)
    parser.add_argument("--graph", required=True, type=Path, help="Base impact graph JSON for the project.")
    parser.add_argument("--mutations", required=True, type=Path, help="PIT mutations.xml.")
    parser.add_argument("--output-root", type=Path, default=Path("training/priority-datasets"))
    parser.add_argument("--limit", type=int, default=50, help="Maximum mutation cases to export.")
    parser.add_argument("--max-tests-per-queue", type=int, default=5)
    parser.add_argument("--max-depth", type=int, default=4, help="Maximum graph depth used to keep reachable PIT cases.")
    parser.add_argument("--risk-value", type=float, default=1.0)
    args = parser.parse_args()

    # The base graph is cloned for every exported case; each clone receives a single risk-initialized mutated method from one PIT mutation
    base_graph = load_json(args.graph)
    graph_index = GraphIndex.from_graph(base_graph)
    adjacency = graph_adjacency(base_graph)
    mutations = mutations_from_pit(args.mutations)

    # Write to a temporary dataset folder first.
    final_dataset_root = args.output_root / args.subject
    dataset_root = temporary_dataset_root(args.output_root, args.subject)
    graphs_dir = dataset_root / "graphs"
    queues_dir = dataset_root / "queues"
    graphs_dir.mkdir(parents=True, exist_ok=True)
    queues_dir.mkdir(parents=True, exist_ok=True)

    examples = []
    skipped = {
        "notKilled": 0,
        "missingMethod": 0,
        "methodNotInGraph": 0,
        "noKillingTests": 0,
        "noKillingTestsInGraph": 0,
        "noImpactPath": 0,
        "limitReached": 0,
    }
    reachability_cache: dict[tuple[str, str], bool] = {}

    for mutation in mutations:
        if len(examples) >= args.limit:
            skipped["limitReached"] += 1
            continue
        if mutation["status"] and mutation["status"] not in {"KILLED", "DETECTED"}:
            skipped["notKilled"] += 1
            continue
        if not mutation["methodId"]:
            skipped["missingMethod"] += 1
            continue

        graph_method_id = graph_index.resolve_method(mutation["methodId"])
        if graph_method_id is None:
            skipped["methodNotInGraph"] += 1
            continue
        if not mutation["killingTests"]:
            skipped["noKillingTests"] += 1
            continue

        # The expected queue is PIT's killing-test order, restricted to tests that are present as graph test nodes
        queue = graph_index.resolve_tests(mutation["killingTests"])[:args.max_tests_per_queue]
        if not queue:
            skipped["noKillingTestsInGraph"] += 1
            continue
        # If no path exists, the trainer has no edge support to update for this example, so the case would only add noise
        if not any(has_impact_path(adjacency, graph_method_id, test_id, args.max_depth, reachability_cache) for test_id in queue):
            skipped["noImpactPath"] += 1
            continue

        case_id = f"{args.subject}-mutant-{len(examples) + 1:04d}"
        graph_path = graphs_dir / f"{case_id}-graph.json"
        queue_path = queues_dir / f"{case_id}-priority.json"

        case_graph = risk_initialized_graph(base_graph, graph_method_id, mutation.get("line"), args.risk_value)
        write_json(graph_path, case_graph)
        write_json(queue_path, {
            "description": "Expected priority queue from PIT killing tests. Edit if you want a different order.",
            "source": "pit mutations.xml",
            "mutation": {
                "methodId": mutation["methodId"],
                "graphMethodId": graph_method_id,
                "line": mutation.get("line"),
                "mutator": mutation.get("mutator"),
                "description": mutation.get("description")
            },
            "priorityQueue": queue
        })
        examples.append({
            "caseId": case_id,
            "graph": relative_to(dataset_root, graph_path),
            "priorityQueue": relative_to(dataset_root, queue_path)
        })

    dataset_path = dataset_root / "dataset.json"
    final_dataset_path = final_dataset_root / "dataset.json"
    summary_path = dataset_root / "summary.json"
    write_json(dataset_path, {
        "description": "Generated from a base impact graph and PIT mutations.xml.",
        "subject": args.subject,
        "examples": examples
    })
    summary_payload = {
        "subject": args.subject,
        "baseGraph": str(args.graph),
        "mutations": str(args.mutations),
        "dataset": str(final_dataset_path),
        "totalMutations": len(mutations),
        "exported": len(examples),
        "skipped": skipped,
        "riskValue": args.risk_value,
        "maxTestsPerQueue": args.max_tests_per_queue,
        "maxDepth": args.max_depth
    }
    write_json(summary_path, summary_payload)
    published_root = publish_dataset(dataset_root, final_dataset_root)
    published_dataset_path = published_root / "dataset.json"
    if published_root != final_dataset_root:
        summary_payload["dataset"] = str(published_dataset_path)
        summary_payload["requestedDataset"] = str(final_dataset_path)
        write_json(published_root / "summary.json", summary_payload)
        print("Existing dataset directory is locked, so the new dataset was kept in a fresh folder.")
    print(f"Exported {len(examples)} training examples to {published_dataset_path}")
    print(f"Summary written to {published_root / 'summary.json'}")


def mutations_from_pit(path: Path) -> list[dict[str, Any]]:
    """Extract the PIT fields needed to create graph/queue examples."""
    root = ET.parse(path).getroot()
    mutations = []
    for index, element in enumerate(root.findall(".//mutation"), start=1):
        mutations.append({
            "id": element.get("id") or element.get("index") or text(element, "index") or str(index),
            "status": (element.get("status") or text(element, "status") or "").upper(),
            "mutator": simple_mutator(text(element, "mutator")),
            "line": int_or_none(text(element, "lineNumber")),
            "description": text(element, "description"),
            "methodId": method_id_from_pit(
                text(element, "mutatedClass"),
                text(element, "mutatedMethod"),
                text(element, "methodDescription")
            ),
            "killingTests": killing_tests_from_xml(element)
        })
    return mutations


def risk_initialized_graph(base_graph: dict[str, Any], graph_method_id: str, line: int | None, risk_value: float) -> dict[str, Any]:
    """Return a graph copy where only the mutated method starts with risk."""
    result = copy.deepcopy(base_graph)
    for node in result.get("nodes", []):
        node_id = node.get("fullSignature") or method_id_to_string(node.get("id"))
        if node_id == graph_method_id:
            node["changeStatus"] = "MODIFIED"
            node["changedLines"] = [line] if line is not None else []
            node["riskValue"] = risk_value
            node["initialRiskValue"] = risk_value
            node["propagatedRiskScore"] = 0.0
        else:
            node["changeStatus"] = "UNCHANGED"
            node["changedLines"] = []
            node["riskValue"] = 0.0
            node["initialRiskValue"] = 0.0
            node["propagatedRiskScore"] = 0.0
    return result


class GraphIndex:
    """Resolve PIT method/test ids to graph ids with progressively looser keys."""

    def __init__(
        self,
        method_by_exact: dict[str, str],
        method_by_arity: dict[str, str],
        method_by_name: dict[str, str],
        test_by_exact: dict[str, str],
        test_by_name: dict[str, str]
    ) -> None:
        self.method_by_exact = method_by_exact
        self.method_by_arity = method_by_arity
        self.method_by_name = method_by_name
        self.test_by_exact = test_by_exact
        self.test_by_name = test_by_name

    @classmethod
    def from_graph(cls, graph: dict[str, Any]) -> "GraphIndex":
        """Build exact and unique loose lookup maps for methods and tests."""
        method_by_exact = {}
        method_by_arity_candidates: dict[str, set[str]] = defaultdict(set)
        method_by_name_candidates: dict[str, set[str]] = defaultdict(set)
        test_by_exact = {}
        test_by_name_candidates: dict[str, set[str]] = defaultdict(set)
        for node in graph.get("nodes", []):
            method_id = node.get("fullSignature") or method_id_to_string(node.get("id"))
            exact_key, arity_key, name_key = signature_keys(method_id)
            method_by_exact.setdefault(exact_key, method_id)
            if arity_key:
                method_by_arity_candidates[arity_key].add(method_id)
            method_by_name_candidates[name_key].add(method_id)
            if node.get("nodeType") == "TEST_METHOD" or node.get("isTestMethod") is True:
                test_by_exact.setdefault(exact_key, method_id)
                test_by_name_candidates[name_key].add(method_id)
        return cls(
            method_by_exact,
            unique_candidates(method_by_arity_candidates),
            unique_candidates(method_by_name_candidates),
            test_by_exact,
            unique_candidates(test_by_name_candidates)
        )

    def resolve_method(self, method_id: str) -> str | None:
        """Resolve a PIT mutated method to a graph method id when unambiguous."""
        exact_key, arity_key, name_key = signature_keys(method_id)
        return (
            self.method_by_exact.get(exact_key)
            or (self.method_by_arity.get(arity_key) if arity_key else None)
            or self.method_by_name.get(name_key)
        )

    def resolve_tests(self, test_ids: list[str]) -> list[str]:
        """Resolve PIT killing tests to graph test ids while preserving order."""
        result = []
        seen = set()
        for test_id in test_ids:
            exact_key, _, name_key = signature_keys(test_id)
            graph_test_id = self.test_by_exact.get(exact_key) or self.test_by_name.get(name_key)
            if graph_test_id is None or graph_test_id in seen:
                continue
            seen.add(graph_test_id)
            result.append(graph_test_id)
        return result


def killing_tests_from_xml(element: ET.Element) -> list[str]:
    """Read killing tests from PIT's singular or pipe-separated XML formats."""
    tests = []
    for child in element.findall("killingTest"):
        if child.text:
            tests.append(normalize_test_id(child.text.strip()))
    plural = text(element, "killingTests")
    if plural:
        tests.extend(normalize_test_id(value) for value in plural.split("|") if value.strip())
    return unique_ordered(tests)


def normalize_test_id(value: Any) -> str:
    """Convert PIT/JUnit test names into the graph's Class#method() shape."""
    text_value = str(value).strip()
    if "#" in text_value:
        return text_value if text_value.endswith(")") else text_value + "()"
    if "(" in text_value and text_value.endswith(")"):
        method_part, class_name = text_value[:-1].split("(", 1)
        method_name = method_part.rsplit(".", 1)[-1]
        return f"{class_name}#{method_name}()"
    if "." in text_value:
        class_name, method_name = text_value.rsplit(".", 1)
        return f"{class_name}#{method_name}()"
    return text_value


def signature_keys(method_id: str) -> tuple[str, str | None, str]:
    """Return exact, class/method/arity, and class/method matching keys."""
    exact_key = comparison_key(str(method_id)).replace("$", ".").replace(" ", "")
    if "#" not in exact_key:
        return exact_key, None, exact_key

    class_name, method_part = exact_key.split("#", 1)
    method_name = method_part.split("(", 1)[0]
    name_key = f"{class_name}#{method_name}"
    arity = parameter_arity(method_part)
    arity_key = f"{name_key}/{arity}" if arity is not None else None
    return exact_key, arity_key, name_key


def parameter_arity(method_part: str) -> int | None:
    """Count signature parameters without trying to reinterpret their types."""
    if "(" not in method_part or ")" not in method_part:
        return None
    params_text = method_part[method_part.find("(") + 1:method_part.rfind(")")]
    if not params_text:
        return 0
    return len([param for param in params_text.split(",") if param])


def unique_candidates(candidates: dict[str, set[str]]) -> dict[str, str]:
    """Keep only loose keys that map to exactly one graph method/test."""
    return {
        key: next(iter(values))
        for key, values in candidates.items()
        if len(values) == 1
    }


def graph_adjacency(graph: dict[str, Any]) -> dict[str, list[str]]:
    """Build a simple source -> target adjacency map for reachability checks."""
    adjacency: dict[str, list[str]] = defaultdict(list)
    for edge in graph.get("edges", []):
        source = method_id_to_string(edge.get("sourceMethodId"))
        target = method_id_to_string(edge.get("targetMethodId"))
        adjacency[source].append(target)
    return dict(adjacency)


def has_impact_path(
    adjacency: dict[str, list[str]],
    source: str,
    target: str,
    max_depth: int,
    cache: dict[tuple[str, str], bool]
) -> bool:
    """Check whether risk can flow from source to target within max_depth."""
    cache_key = (source, target)
    if cache_key in cache:
        return cache[cache_key]

    target_key = comparison_key(target)
    visited = {source}
    frontier = deque([(source, 0)])
    while frontier:
        current, depth = frontier.popleft()
        if comparison_key(current) == target_key:
            cache[cache_key] = True
            return True
        if depth >= max_depth:
            continue
        for next_method in adjacency.get(current, []):
            if next_method in visited:
                continue
            visited.add(next_method)
            frontier.append((next_method, depth + 1))

    cache[cache_key] = False
    return False


def method_id_from_pit(mutated_class: str | None, method_name: str | None, descriptor: str | None) -> str | None:
    """Convert PIT bytecode method fields into the graph method-id format."""
    if not mutated_class or not method_name:
        return None
    params, return_type = parse_method_descriptor(descriptor)
    method_id = f"{mutated_class}#{method_name}({','.join(params)})"
    if return_type and method_name != "<init>":
        method_id += f":{return_type}"
    return method_id


def parse_method_descriptor(descriptor: str | None) -> tuple[list[str], str | None]:
    """Parse a JVM method descriptor into parameter and return type names."""
    if not descriptor or not descriptor.startswith("("):
        return [], None
    close = descriptor.find(")")
    if close < 0:
        return [], None
    params = parse_descriptor_types(descriptor[1:close])
    returns = parse_descriptor_types(descriptor[close + 1:])
    return params, returns[0] if returns else None


def parse_descriptor_types(descriptor: str) -> list[str]:
    """Parse JVM descriptor type tokens used by PIT method descriptions."""
    types = []
    index = 0
    while index < len(descriptor):
        array_depth = 0
        while index < len(descriptor) and descriptor[index] == "[":
            array_depth += 1
            index += 1
        if index >= len(descriptor):
            break
        marker = descriptor[index]
        if marker == "L":
            end = descriptor.find(";", index)
            if end < 0:
                break
            type_name = descriptor[index + 1:end].replace("/", ".")
            index = end + 1
        else:
            type_name = PRIMITIVE_TYPES.get(marker, marker)
            index += 1
        type_name += "[]" * array_depth
        types.append(type_name)
    return types


def text(element: ET.Element, child_name: str) -> str | None:
    child = element.find(child_name)
    if child is None or child.text is None:
        return None
    return child.text.strip()


def simple_mutator(value: str | None) -> str | None:
    if not value:
        return None
    return value.rsplit(".", 1)[-1].replace("Mutator", "")


def int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def unique_ordered(values: list[str]) -> list[str]:
    result = []
    seen = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        result.append(value)
    return result


def relative_to(root: Path, path: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def temporary_dataset_root(output_root: Path, subject: str) -> Path:
    """Create a unique staging root for a dataset build."""
    output_root.mkdir(parents=True, exist_ok=True)
    return output_root / f".{subject}.tmp-{uuid4().hex}"


def publish_dataset(staging_root: Path, final_root: Path) -> Path:
    """Move a completed staging dataset into place, tolerating Windows locks."""
    final_root.parent.mkdir(parents=True, exist_ok=True)
    if not final_root.exists():
        staging_root.rename(final_root)
        return final_root

    backup_root = final_root.parent / f".{final_root.name}.backup-{uuid4().hex}"
    try:
        final_root.rename(backup_root)
    except OSError:
        return staging_root

    try:
        staging_root.rename(final_root)
    except Exception:
        if backup_root.exists() and not final_root.exists():
            backup_root.rename(final_root)
        raise
    shutil.rmtree(backup_root, ignore_errors=True)
    return final_root


if __name__ == "__main__":
    main()
