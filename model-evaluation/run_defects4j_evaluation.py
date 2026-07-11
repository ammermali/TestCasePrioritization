from __future__ import annotations

import argparse
import concurrent.futures
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from statistics import mean
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / "model-evaluation"
JACOCO_AGENT = PROJECT_ROOT / "src" / "main" / "resources" / "tcpimpact-agents" / "jacocoagent.jar"
LOG_LOCK = threading.Lock()


@dataclass(frozen=True)
class EvaluationCase:
    project: str
    bug_id: int

    @property
    def case_id(self) -> str:
        return f"{self.project}-{self.bug_id}"


class CommandError(RuntimeError):
    pass


def main() -> int:
    args = parse_args()
    if args.coverage_workers < 1:
        raise SystemExit("--coverage-workers must be >= 1.")
    run_id = args.run_id or datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = args.output_root.resolve() / "runs" / run_id
    work_root = args.work_root.resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    work_root.mkdir(parents=True, exist_ok=True)
    command_log = run_dir / "commands.log"

    cases = resolve_cases(args, run_dir, command_log)
    if not cases:
        raise SystemExit("No Defects4J cases selected.")

    validate_required_commands(args, command_log)

    if not args.skip_model_compile:
        run_command(
            [args.maven, "-q", "-DskipTests", "compile"],
            cwd=PROJECT_ROOT,
            env=java_env(args.model_java_home),
            timeout=args.command_timeout_seconds,
            log_file=command_log,
        )

    results: list[dict[str, Any]] = []
    revision_cache: dict[str, dict[int, dict[str, str]]] = {}
    for case in cases:
        try:
            result = evaluate_case(args, case, run_dir, work_root, command_log, revision_cache)
            results.append(result)
            write_run_outputs(args, run_id, run_dir, results)
        except Exception as exc:
            failure = {
                "caseId": case.case_id,
                "project": case.project,
                "bugId": case.bug_id,
                "status": "failed",
                "error": str(exc),
            }
            results.append(failure)
            write_run_outputs(args, run_id, run_dir, results)
            print(f"[{case.case_id}] failed: {exc}", file=sys.stderr)
            print(f"[{case.case_id}] command log: {command_log}", file=sys.stderr)
            if not args.continue_on_error:
                raise

    payload = write_run_outputs(args, run_id, run_dir, results)

    print(f"Evaluation complete: {run_dir}")
    print(f"Cases: {len(results)}")
    print(f"Successful: {sum(1 for result in results if result.get('status') == 'ok')}")
    print(f"Report: {run_dir / 'run-report.md'}")
    return 0


def write_run_outputs(
    args: argparse.Namespace,
    run_id: str,
    run_dir: Path,
    results: list[dict[str, Any]],
) -> dict[str, Any]:
    payload = summary_payload(args, run_id, results)
    write_json(run_dir / "results.json", payload)
    write_results_csv(run_dir / "results.csv", results)
    write_markdown_report(run_dir / "run-report.md", payload)
    return payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the Java TCP Impact model on Defects4J bugs and write evaluation results."
    )
    parser.add_argument("--case", action="append", default=[], help="Defects4J case as Project:Bug, for example Lang:1.")
    parser.add_argument("--project", action="append", default=[], help="Defects4J project id, for example Lang.")
    parser.add_argument("--bug", action="append", type=int, default=[], help="Bug id. Combined with every --project.")
    parser.add_argument("--limit", type=int, help="Limit automatically discovered bug ids per project.")
    parser.add_argument(
        "--defects4j",
        nargs="+",
        default=["defects4j"],
        help="Defects4J command. Use '--defects4j perl C:\\path\\to\\defects4j' on Windows if needed.",
    )
    parser.add_argument("--maven", default="mvn", help="Path to Maven used for this model.")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--work-root", type=Path, default=DEFAULT_OUTPUT_ROOT / "work")
    parser.add_argument("--run-id")
    parser.add_argument("--max-depth", type=int, default=4)
    parser.add_argument("--coverage", choices=["all", "none"], default="all")
    parser.add_argument("--coverage-test-limit", type=int, help="Smoke-test limit for coverage generation. This makes metrics partial.")
    parser.add_argument("--coverage-progress-interval", type=int, default=25, help="Print per-test coverage progress every N tests.")
    parser.add_argument("--coverage-workers", type=int, default=1, help="Parallel workers for per-test JaCoCo coverage. Use 1 for safest execution.")
    parser.add_argument("--coverage-cache-root", type=Path, default=DEFAULT_OUTPUT_ROOT / "coverage-cache", help="Directory used to persist and reuse per-test JaCoCo reports.")
    parser.add_argument("--refresh-coverage-cache", action="store_true", help="Regenerate coverage even when a complete cache entry exists.")
    parser.add_argument("--no-coverage-cache", action="store_true", help="Do not restore or save per-test coverage reports.")
    parser.add_argument("--command-timeout-seconds", type=int, default=900)
    parser.add_argument("--test-timeout-seconds", type=int, default=180)
    parser.add_argument("--defects4j-java-home", type=Path, help="JAVA_HOME used only for Defects4J commands.")
    parser.add_argument("--model-java-home", type=Path, help="JAVA_HOME used only for this Java model.")
    parser.add_argument("--skip-model-compile", action="store_true")
    parser.add_argument("--keep-workdirs", action="store_true")
    parser.add_argument("--continue-on-error", action="store_true", default=True)
    parser.add_argument("--stop-on-error", action="store_false", dest="continue_on_error")
    return parser.parse_args()


def resolve_cases(args: argparse.Namespace, run_dir: Path, command_log: Path) -> list[EvaluationCase]:
    cases: list[EvaluationCase] = []
    for raw_case in args.case:
        if ":" not in raw_case:
            raise SystemExit(f"Invalid --case '{raw_case}'. Use Project:Bug, for example Lang:1.")
        project, bug_id = raw_case.split(":", 1)
        cases.append(EvaluationCase(project.strip(), int(bug_id)))

    if args.project and args.bug:
        for project in args.project:
            for bug_id in args.bug:
                cases.append(EvaluationCase(project, bug_id))
    elif args.project:
        for project in args.project:
            bug_ids = query_bug_ids(args, project, run_dir, command_log)
            if args.limit is not None:
                bug_ids = bug_ids[:args.limit]
            cases.extend(EvaluationCase(project, bug_id) for bug_id in bug_ids)

    deduplicated: list[EvaluationCase] = []
    seen: set[tuple[str, int]] = set()
    for case in cases:
        key = (case.project, case.bug_id)
        if key in seen:
            continue
        seen.add(key)
        deduplicated.append(case)
    return deduplicated


def evaluate_case(
    args: argparse.Namespace,
    case: EvaluationCase,
    run_dir: Path,
    work_root: Path,
    command_log: Path,
    revision_cache: dict[str, dict[int, dict[str, str]]],
) -> dict[str, Any]:
    print(f"[{case.case_id}] checkout")
    case_out = run_dir / "cases" / case.case_id
    case_out.mkdir(parents=True, exist_ok=True)
    checkout_dir = work_root / f"{case.case_id}b"
    if checkout_dir.exists():
        shutil.rmtree(checkout_dir)

    run_command(
        defects4j_command(args, "checkout", "-p", case.project, "-v", f"{case.bug_id}b", "-w", str(checkout_dir)),
        env=java_env(args.defects4j_java_home),
        timeout=args.command_timeout_seconds,
        log_file=command_log,
    )
    run_command(
        defects4j_command(args, "compile"),
        cwd=checkout_dir,
        env=java_env(args.defects4j_java_home),
        timeout=args.command_timeout_seconds,
        log_file=command_log,
    )

    print(f"[{case.case_id}] metadata")
    revisions = query_revisions(args, case.project, run_dir, command_log, revision_cache).get(case.bug_id)
    if revisions is None:
        raise RuntimeError(f"Could not find revision metadata for {case.case_id}.")

    properties = export_defects4j_properties(args, checkout_dir, case_out, command_log)
    trigger_tests = load_lines(case_out / "tests.trigger.txt")
    if not trigger_tests:
        raise RuntimeError(f"{case.case_id} has no triggering tests.")

    print(f"[{case.case_id}] test discovery")
    discovered_tests = discover_tests(args, checkout_dir, properties, command_log)
    write_json(case_out / "discovered-tests.json", discovered_tests)
    if not discovered_tests.get("tests"):
        raise RuntimeError(f"{case.case_id} has no discovered test methods.")

    coverage_summary: dict[str, Any] = {"mode": args.coverage, "xmlReports": 0}
    if args.coverage == "all":
        print(f"[{case.case_id}] per-test coverage")
        coverage_summary = run_per_test_coverage(
            args,
            case,
            revisions,
            checkout_dir,
            case_out,
            discovered_tests,
            properties,
            command_log,
        )

    print(f"[{case.case_id}] model ranking")
    run_model_cli(
        args,
        [
            "--repo", checkout_dir,
            "--project-path", ".",
            "--base", revisions["buggy"],
            "--head", revisions["fixed"],
            "--source-classes-dir", properties["dir.src.classes"],
            "--source-tests-dir", properties["dir.src.tests"],
            "--impact-graph-output", "impact-graph.json",
            "--priority-ranking-output", "priority-ranking.json",
            "--max-depth", str(args.max_depth),
        ],
        command_log,
    )

    tcpimpact_dir = checkout_dir / ".tcpimpact"
    copy_if_exists(tcpimpact_dir / "impact-graph.json", case_out / "impact-graph.json")
    copy_if_exists(tcpimpact_dir / "priority-ranking.json", case_out / "priority-ranking.json")

    ranking_payload = load_json(case_out / "priority-ranking.json")
    metrics = compute_metrics(
        [test["testId"] for test in ranking_payload.get("rankedTests", [])],
        trigger_tests,
    )
    case_payload = {
        "caseId": case.case_id,
        "project": case.project,
        "bugId": case.bug_id,
        "status": "ok",
        "revisions": revisions,
        "properties": properties,
        "triggerTests": trigger_tests,
        "testCount": len(ranking_payload.get("rankedTests", [])),
        "coverage": coverage_summary,
        "changedMethods": ranking_payload.get("changedMethods", []),
        "metrics": metrics,
        "rankingPath": str(case_out / "priority-ranking.json"),
        "graphPath": str(case_out / "impact-graph.json"),
    }
    write_json(case_out / "metrics.json", case_payload)

    if not args.keep_workdirs:
        shutil.rmtree(checkout_dir, ignore_errors=True)

    return case_payload


def query_bug_ids(args: argparse.Namespace, project: str, run_dir: Path, command_log: Path) -> list[int]:
    proc = run_command(
        defects4j_command(args, "bids", "-p", project),
        env=java_env(args.defects4j_java_home),
        timeout=args.command_timeout_seconds,
        log_file=command_log,
    )
    values = re.findall(r"\d+", proc.stdout)
    if not values:
        raise RuntimeError(f"No bug ids returned by defects4j bids -p {project}.")
    return [int(value) for value in values]


def query_revisions(
    args: argparse.Namespace,
    project: str,
    run_dir: Path,
    command_log: Path,
    revision_cache: dict[str, dict[int, dict[str, str]]],
) -> dict[int, dict[str, str]]:
    if project in revision_cache:
        return revision_cache[project]

    proc = run_command(
        defects4j_command(args, "query", "-p", project, "-q", "bug.id,revision.id.buggy,revision.id.fixed"),
        env=java_env(args.defects4j_java_home),
        timeout=args.command_timeout_seconds,
        log_file=command_log,
    )
    rows = parse_csv_rows(proc.stdout)
    revisions: dict[int, dict[str, str]] = {}
    for row in rows:
        bug_id = int(row["bug.id"])
        revisions[bug_id] = {
            "buggy": row["revision.id.buggy"],
            "fixed": row["revision.id.fixed"],
        }
    if not revisions:
        raise RuntimeError(f"No revision metadata returned for project {project}.")
    revision_cache[project] = revisions
    write_json(run_dir / "metadata" / f"{project}-revisions.json", revisions)
    return revisions


def parse_csv_rows(text: str) -> list[dict[str, str]]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return []
    reader = csv.reader(lines)
    parsed = list(reader)
    header = parsed[0]
    if header and header[0] == "bug.id":
        return [dict(zip(header, row)) for row in parsed[1:] if row]
    return [
        {
            "bug.id": row[0],
            "revision.id.buggy": row[1],
            "revision.id.fixed": row[2],
        }
        for row in parsed
        if len(row) >= 3
    ]


def export_defects4j_properties(
    args: argparse.Namespace,
    checkout_dir: Path,
    case_out: Path,
    command_log: Path,
) -> dict[str, str]:
    properties = {}
    for prop in ["tests.all", "tests.trigger", "dir.src.classes", "dir.src.tests", "dir.bin.classes"]:
        output = case_out / f"{prop}.txt"
        run_command(
            defects4j_command(args, "export", "-p", prop, "-o", str(output)),
            cwd=checkout_dir,
            env=java_env(args.defects4j_java_home),
            timeout=args.command_timeout_seconds,
            log_file=command_log,
        )
        values = load_lines(output)
        properties[prop] = values[0] if values else ""
    write_json(case_out / "defects4j-properties.json", properties)
    return properties


def discover_tests(
    args: argparse.Namespace,
    checkout_dir: Path,
    properties: dict[str, str],
    command_log: Path,
) -> dict[str, Any]:
    run_model_cli(
        args,
        [
            "--repo", checkout_dir,
            "--project-path", ".",
            "--source-tests-dir", properties["dir.src.tests"],
            "--discover-tests-output", "discovered-tests.json",
        ],
        command_log,
    )
    return load_json(checkout_dir / ".tcpimpact" / "discovered-tests.json")


def run_per_test_coverage(
    args: argparse.Namespace,
    case: EvaluationCase,
    revisions: dict[str, str],
    checkout_dir: Path,
    case_out: Path,
    discovered_tests: dict[str, Any],
    properties: dict[str, str],
    command_log: Path,
) -> dict[str, Any]:
    if not JACOCO_AGENT.exists():
        raise RuntimeError(f"Missing JaCoCo agent: {JACOCO_AGENT}")

    tcpimpact_dir = checkout_dir / ".tcpimpact"
    exec_dir = tcpimpact_dir / "per-test-coverage" / "exec"
    xml_dir = tcpimpact_dir / "per-test-coverage" / "xml"
    exec_dir.mkdir(parents=True, exist_ok=True)
    xml_dir.mkdir(parents=True, exist_ok=True)

    total_discovered_tests = len(discovered_tests.get("tests", []))
    tests = selected_coverage_tests(args, discovered_tests)
    total_selected_tests = len(tests)

    cache_dir = coverage_cache_dir(args, case)
    cache_metadata = coverage_cache_metadata(args, case, revisions, tests)

    if not args.no_coverage_cache and not args.refresh_coverage_cache:
        restored = restore_coverage_cache(
            args,
            case,
            cache_dir,
            cache_metadata,
            checkout_dir,
            case_out,
            tests,
            total_discovered_tests,
        )
        if restored is not None:
            print(f"Reusing cached per-test coverage: {cache_dir}", flush=True)
            return restored

    print(
        f"Per-test coverage will execute {total_selected_tests}/{total_discovered_tests} discovered tests.",
        flush=True,
    )
    if args.coverage_workers > 1:
        print(f"Per-test coverage workers: {args.coverage_workers}", flush=True)

    coverage_map: dict[str, str] = {}
    for test in tests:
        report_name = test["reportName"]
        exec_name = safe_file_name(report_name) + ".exec"
        coverage_map[exec_name] = report_name

    test_runs = run_coverage_tests(args, checkout_dir, exec_dir, tests, command_log)

    coverage_map_path = tcpimpact_dir / "per-test-coverage" / "coverage-map.json"
    write_json(coverage_map_path, coverage_map)
    write_json(case_out / "coverage-map.json", coverage_map)
    write_json(case_out / "coverage-test-runs.json", test_runs)

    run_model_cli(
        args,
        [
            "--repo", checkout_dir,
            "--project-path", ".",
            "--jacoco-exec-dir", exec_dir,
            "--jacoco-report-map", coverage_map_path,
            "--jacoco-classes-dir", checkout_dir / properties["dir.bin.classes"],
            "--jacoco-xml-output-dir", xml_dir,
        ],
        command_log,
    )

    xml_reports = sorted(xml_dir.glob("*.xml"))
    if not args.no_coverage_cache:
        save_coverage_cache(cache_dir, cache_metadata, exec_dir, xml_dir, coverage_map, test_runs)

    return {
        "mode": "all",
        "requestedTests": len(discovered_tests.get("tests", [])),
        "executedTests": len(tests),
        "coverageTestLimit": args.coverage_test_limit,
        "execFiles": sum(1 for run in test_runs if run["execExists"]),
        "xmlReports": len(xml_reports),
        "xmlDir": str(xml_dir),
        "cacheHit": False,
        "cacheDir": str(cache_dir),
        "coverageWorkers": max(1, args.coverage_workers),
    }


def selected_coverage_tests(args: argparse.Namespace, discovered_tests: dict[str, Any]) -> list[dict[str, Any]]:
    tests = list(discovered_tests.get("tests", []))
    if args.coverage_test_limit is not None:
        return tests[:args.coverage_test_limit]
    return tests


def coverage_cache_dir(args: argparse.Namespace, case: EvaluationCase) -> Path:
    limit_label = "all" if args.coverage_test_limit is None else f"limit-{args.coverage_test_limit}"
    return args.coverage_cache_root.resolve() / case.case_id / limit_label


def coverage_cache_metadata(
    args: argparse.Namespace,
    case: EvaluationCase,
    revisions: dict[str, str],
    tests: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "version": 1,
        "caseId": case.case_id,
        "project": case.project,
        "bugId": case.bug_id,
        "revisions": revisions,
        "coverageTestLimit": args.coverage_test_limit,
        "selectedTests": len(tests),
        "testIdsHash": test_ids_hash(tests),
    }


def test_ids_hash(tests: list[dict[str, Any]]) -> str:
    payload = json.dumps([test["testId"] for test in tests], separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def restore_coverage_cache(
    args: argparse.Namespace,
    case: EvaluationCase,
    cache_dir: Path,
    expected_metadata: dict[str, Any],
    checkout_dir: Path,
    case_out: Path,
    tests: list[dict[str, Any]],
    total_discovered_tests: int,
) -> dict[str, Any] | None:
    metadata_path = cache_dir / "metadata.json"
    coverage_map_path = cache_dir / "coverage-map.json"
    test_runs_path = cache_dir / "coverage-test-runs.json"
    cache_exec_dir = cache_dir / "exec"
    cache_xml_dir = cache_dir / "xml"
    required_paths = [metadata_path, coverage_map_path, test_runs_path, cache_exec_dir, cache_xml_dir]
    if any(not path.exists() for path in required_paths):
        return None

    try:
        metadata = load_json(metadata_path)
        coverage_map = load_json(coverage_map_path)
        test_runs = load_json(test_runs_path)
    except (OSError, json.JSONDecodeError):
        return None

    if metadata != expected_metadata:
        return None
    if len(test_runs) != len(tests) or len(coverage_map) != len(tests):
        return None

    exec_files = sorted(cache_exec_dir.glob("*.exec"))
    xml_reports = sorted(cache_xml_dir.glob("*.xml"))
    if len(exec_files) < len(tests) or len(xml_reports) < len(tests):
        return None

    tcpimpact_coverage_dir = checkout_dir / ".tcpimpact" / "per-test-coverage"
    exec_dir = tcpimpact_coverage_dir / "exec"
    xml_dir = tcpimpact_coverage_dir / "xml"
    copy_directory(cache_exec_dir, exec_dir)
    copy_directory(cache_xml_dir, xml_dir)
    write_json(tcpimpact_coverage_dir / "coverage-map.json", coverage_map)
    write_json(case_out / "coverage-map.json", coverage_map)
    write_json(case_out / "coverage-test-runs.json", test_runs)

    return {
        "mode": "all",
        "requestedTests": total_discovered_tests,
        "executedTests": len(tests),
        "coverageTestLimit": args.coverage_test_limit,
        "execFiles": len(exec_files),
        "xmlReports": len(xml_reports),
        "xmlDir": str(xml_dir),
        "cacheHit": True,
        "cacheDir": str(cache_dir),
        "coverageWorkers": 0,
    }


def save_coverage_cache(
    cache_dir: Path,
    metadata: dict[str, Any],
    exec_dir: Path,
    xml_dir: Path,
    coverage_map: dict[str, str],
    test_runs: list[dict[str, Any]],
) -> None:
    temp_dir = cache_dir.with_name(cache_dir.name + ".tmp")
    if temp_dir.exists():
        shutil.rmtree(temp_dir)
    temp_dir.mkdir(parents=True, exist_ok=True)
    copy_directory(exec_dir, temp_dir / "exec")
    copy_directory(xml_dir, temp_dir / "xml")
    write_json(temp_dir / "coverage-map.json", coverage_map)
    write_json(temp_dir / "coverage-test-runs.json", test_runs)
    write_json(temp_dir / "metadata.json", metadata)
    if cache_dir.exists():
        shutil.rmtree(cache_dir)
    shutil.move(str(temp_dir), str(cache_dir))
    print(f"Saved per-test coverage cache: {cache_dir}", flush=True)


def copy_directory(source: Path, destination: Path) -> None:
    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, destination)


def run_coverage_tests(
    args: argparse.Namespace,
    checkout_dir: Path,
    exec_dir: Path,
    tests: list[dict[str, Any]],
    command_log: Path,
) -> list[dict[str, Any]]:
    if not tests:
        return []

    workers = max(1, min(args.coverage_workers, len(tests)))
    if workers == 1:
        progress = {"done": 0}
        progress_lock = threading.Lock()
        return run_coverage_partition(
            args,
            checkout_dir,
            exec_dir,
            list(enumerate(tests, start=1)),
            len(tests),
            command_log,
            progress,
            progress_lock,
        )

    worker_dirs = prepare_coverage_worker_dirs(checkout_dir, workers)
    partitions: list[list[tuple[int, dict[str, Any]]]] = [[] for _ in range(workers)]
    for index, test in enumerate(tests, start=1):
        partitions[(index - 1) % workers].append((index, test))

    progress = {"done": 0}
    progress_lock = threading.Lock()
    test_runs: list[dict[str, Any]] = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            futures = [
                executor.submit(
                    run_coverage_partition,
                    args,
                    worker_dirs[worker_index],
                    exec_dir,
                    partition,
                    len(tests),
                    command_log,
                    progress,
                    progress_lock,
                )
                for worker_index, partition in enumerate(partitions)
                if partition
            ]
            for future in concurrent.futures.as_completed(futures):
                test_runs.extend(future.result())
    finally:
        if not args.keep_workdirs:
            for worker_dir in worker_dirs:
                shutil.rmtree(worker_dir, ignore_errors=True)

    return sorted(test_runs, key=lambda run: run["index"])


def prepare_coverage_worker_dirs(checkout_dir: Path, workers: int) -> list[Path]:
    worker_dirs: list[Path] = []
    for worker_index in range(workers):
        worker_dir = checkout_dir.parent / f"{checkout_dir.name}-coverage-worker-{worker_index + 1}"
        if worker_dir.exists():
            shutil.rmtree(worker_dir)
        shutil.copytree(checkout_dir, worker_dir, symlinks=True)
        worker_dirs.append(worker_dir)
    return worker_dirs


def run_coverage_partition(
    args: argparse.Namespace,
    worker_dir: Path,
    exec_dir: Path,
    indexed_tests: list[tuple[int, dict[str, Any]]],
    total_tests: int,
    command_log: Path,
    progress: dict[str, int],
    progress_lock: threading.Lock,
) -> list[dict[str, Any]]:
    test_runs: list[dict[str, Any]] = []
    for index, test in indexed_tests:
        test_id = test["testId"]
        report_name = test["reportName"]
        exec_file = exec_dir / (safe_file_name(report_name) + ".exec")

        env = java_env(args.defects4j_java_home)
        java_tool_options = env.get("JAVA_TOOL_OPTIONS", "")
        agent_arg = f"-javaagent:{JACOCO_AGENT}=destfile={exec_file},append=false"
        env["JAVA_TOOL_OPTIONS"] = f"{java_tool_options} {agent_arg}".strip()
        proc = run_command(
            defects4j_command(args, "test", "-t", test_id),
            cwd=worker_dir,
            env=env,
            timeout=args.test_timeout_seconds,
            check=False,
            log_file=command_log,
        )
        test_runs.append({
            "index": index,
            "testId": test_id,
            "returnCode": proc.returncode,
            "execFile": str(exec_file),
            "execExists": exec_file.exists(),
            "workerDir": str(worker_dir),
        })

        with progress_lock:
            progress["done"] += 1
            done = progress["done"]
            if should_print_coverage_progress(done, total_tests, args.coverage_progress_interval):
                print(f"Coverage progress: {done}/{total_tests} - {test_id}", flush=True)

    return test_runs


def should_print_coverage_progress(index: int, total: int, interval: int) -> bool:
    if index == 1 or index == total:
        return True
    if interval <= 0:
        return False
    return index % interval == 0


def run_model_cli(args: argparse.Namespace, cli_args: list[Any], command_log: Path) -> subprocess.CompletedProcess[str]:
    exec_args = " ".join(quote_exec_arg(value) for value in cli_args)
    return run_command(
        [args.maven, "-q", "exec:java", f"-Dexec.args={exec_args}"],
        cwd=PROJECT_ROOT,
        env=java_env(args.model_java_home),
        timeout=args.command_timeout_seconds,
        log_file=command_log,
    )


def defects4j_command(args: argparse.Namespace, *parts: Any) -> list[Any]:
    return [*args.defects4j, *parts]


def validate_required_commands(args: argparse.Namespace, command_log: Path) -> None:
    if not args.skip_model_compile and not command_exists(args.maven):
        raise CommandError(
            f"Cannot find Maven command '{args.maven}'. Install Maven, add it to PATH, "
            "or pass --maven C:\\path\\to\\mvn.cmd."
        )

    defects4j_executable = str(args.defects4j[0])
    if not command_exists(defects4j_executable):
        append_log(command_log, f"# missing command: {defects4j_executable}\n")
        raise CommandError(
            f"Cannot find Defects4J command '{defects4j_executable}'. Add defects4j to PATH, "
            "or pass something like: --defects4j perl C:\\path\\to\\defects4j\\framework\\bin\\defects4j"
        )


def command_exists(command: str) -> bool:
    command_path = Path(command)
    if command_path.is_file():
        return True
    return shutil.which(command) is not None


def compute_metrics(ranked_tests: list[str], trigger_tests: list[str]) -> dict[str, Any]:
    ranked_keys = [normalize_test_key(test) for test in ranked_tests]
    relevant_keys = [normalize_test_key(test) for test in trigger_tests]
    relevant_set = set(relevant_keys)
    rank_by_test = {test: index for index, test in enumerate(ranked_keys, start=1)}
    total_tests = len(ranked_tests)
    missing_rank = total_tests + 1
    trigger_ranks = [rank_by_test.get(test, missing_rank) for test in relevant_keys]
    first_rank = min(trigger_ranks) if trigger_ranks else None

    return {
        "testCount": total_tests,
        "triggerCount": len(relevant_keys),
        "triggerFound": sum(1 for rank in trigger_ranks if rank <= total_tests),
        "firstTriggerRank": first_rank,
        "recallAt1": recall_at_k(ranked_keys, relevant_set, 1),
        "recallAt3": recall_at_k(ranked_keys, relevant_set, 3),
        "recallAt5": recall_at_k(ranked_keys, relevant_set, 5),
        "recallAt10": recall_at_k(ranked_keys, relevant_set, 10),
        "mrr": 0.0 if first_rank is None or first_rank > total_tests else 1.0 / first_rank,
        "apfd": apfd(total_tests, trigger_ranks),
        "triggerRanks": trigger_ranks,
    }


def recall_at_k(ranked_keys: list[str], relevant_set: set[str], k: int) -> float:
    if not relevant_set:
        return 0.0
    return len(set(ranked_keys[:k]) & relevant_set) / len(relevant_set)


def apfd(total_tests: int, trigger_ranks: list[int]) -> float:
    if total_tests == 0 or not trigger_ranks:
        return 0.0
    return 1.0 - (sum(trigger_ranks) / (total_tests * len(trigger_ranks))) + (1.0 / (2 * total_tests))


def normalize_test_key(value: str) -> str:
    text = value.strip()
    if "-->" in text:
        text = text.split("-->", 1)[0].strip()
    if "#" in text:
        class_name, method_part = text.split("#", 1)
        method_name = method_part.split("(", 1)[0]
    elif "::" in text:
        class_name, method_name = text.split("::", 1)
    else:
        class_name, method_name = text.rsplit(".", 1)
    method_name = method_name.split("(", 1)[0]
    return f"{class_name.replace('$', '.')}::{method_name}"


def summary_payload(args: argparse.Namespace, run_id: str, results: list[dict[str, Any]]) -> dict[str, Any]:
    successful = [result for result in results if result.get("status") == "ok"]
    aggregate = aggregate_metrics(successful)
    return {
        "runId": run_id,
        "createdAt": datetime.now().isoformat(timespec="seconds"),
        "maxDepth": args.max_depth,
        "coverage": args.coverage,
        "caseCount": len(results),
        "successfulCases": len(successful),
        "failedCases": len(results) - len(successful),
        "aggregateMetrics": aggregate,
        "cases": results,
    }


def aggregate_metrics(results: list[dict[str, Any]]) -> dict[str, Any]:
    if not results:
        return {}
    metric_names = ["recallAt1", "recallAt3", "recallAt5", "recallAt10", "mrr", "apfd"]
    return {
        name: mean(result["metrics"][name] for result in results)
        for name in metric_names
    } | {
        "medianFirstTriggerRank": sorted(result["metrics"]["firstTriggerRank"] for result in results)[len(results) // 2],
        "meanFirstTriggerRank": mean(result["metrics"]["firstTriggerRank"] for result in results),
    }


def write_results_csv(path: Path, results: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "caseId",
        "project",
        "bugId",
        "status",
        "testCount",
        "triggerCount",
        "firstTriggerRank",
        "recallAt1",
        "recallAt3",
        "recallAt5",
        "recallAt10",
        "mrr",
        "apfd",
        "error",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for result in results:
            metrics = result.get("metrics", {})
            writer.writerow({
                "caseId": result.get("caseId"),
                "project": result.get("project"),
                "bugId": result.get("bugId"),
                "status": result.get("status"),
                "testCount": metrics.get("testCount"),
                "triggerCount": metrics.get("triggerCount"),
                "firstTriggerRank": metrics.get("firstTriggerRank"),
                "recallAt1": metrics.get("recallAt1"),
                "recallAt3": metrics.get("recallAt3"),
                "recallAt5": metrics.get("recallAt5"),
                "recallAt10": metrics.get("recallAt10"),
                "mrr": metrics.get("mrr"),
                "apfd": metrics.get("apfd"),
                "error": result.get("error"),
            })


def write_markdown_report(path: Path, payload: dict[str, Any]) -> None:
    aggregate = payload.get("aggregateMetrics", {})
    lines = [
        "# Defects4J Model Evaluation",
        "",
        f"- Run: `{payload['runId']}`",
        f"- Cases: {payload['successfulCases']}/{payload['caseCount']} successful",
        f"- Coverage: `{payload['coverage']}`",
        f"- Max depth: {payload['maxDepth']}",
        "",
        "## Aggregate metrics",
        "",
    ]
    if aggregate:
        for key, value in aggregate.items():
            lines.append(f"- {key}: {value:.4f}" if isinstance(value, float) else f"- {key}: {value}")
    else:
        lines.append("- No successful cases.")
    lines.extend(["", "## Cases", ""])
    for result in payload["cases"]:
        if result.get("status") != "ok":
            lines.append(f"- `{result['caseId']}` failed: {result.get('error')}")
            continue
        metrics = result["metrics"]
        lines.append(
            f"- `{result['caseId']}` firstTriggerRank={metrics['firstTriggerRank']} "
            f"recall@10={metrics['recallAt10']:.3f} mrr={metrics['mrr']:.3f} apfd={metrics['apfd']:.3f}"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_command(
    command: list[Any],
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    timeout: int | None = None,
    check: bool = True,
    log_file: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    text_command = " ".join(str(part) for part in command)
    if log_file is not None:
        append_log(log_file, f"$ {text_command}\n# cwd: {cwd or Path.cwd()}\n")
    try:
        proc = subprocess.run(
            [str(part) for part in command],
            cwd=str(cwd) if cwd is not None else None,
            env=env,
            text=True,
            capture_output=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        if log_file is not None:
            append_log(log_file, f"# timeout after {timeout}s\n\n")
        raise CommandError(f"Command timed out after {timeout}s: {text_command}") from exc
    except FileNotFoundError as exc:
        executable = str(command[0]) if command else "<empty command>"
        if log_file is not None:
            append_log(log_file, f"# executable not found: {executable}\n\n")
        raise CommandError(
            f"Cannot find executable '{executable}'. Check PATH or pass the full command path."
        ) from exc

    if log_file is not None:
        append_log(log_file, f"# exit: {proc.returncode}\n{proc.stdout}\n{proc.stderr}\n")
    if check and proc.returncode != 0:
        raise CommandError(f"Command failed with exit {proc.returncode}: {text_command}")
    return proc


def java_env(java_home: Path | None) -> dict[str, str]:
    env = os.environ.copy()
    env.setdefault("TZ", "America/Los_Angeles")
    if java_home is not None:
        env["JAVA_HOME"] = str(java_home)
        env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    return env


def quote_exec_arg(value: Any) -> str:
    text = str(value)
    return '"' + text.replace('"', '\\"') + '"'


def safe_file_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]", "_", value)


def load_lines(path: Path) -> list[str]:
    if not path.exists():
        return []
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")


def copy_if_exists(source: Path, destination: Path) -> None:
    if source.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        # copy2 tries to preserve POSIX metadata that may not be supported when
        # copying from WSL's Linux filesystem to /mnt/c. The file contents are
        # all we need for evaluation artifacts.
        shutil.copyfile(source, destination)


def append_log(path: Path, text: str) -> None:
    with LOG_LOCK:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(text)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except CommandError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
