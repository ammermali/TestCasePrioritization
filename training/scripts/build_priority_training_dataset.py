"""Orchestrate graph + existing PIT reports + priority-queue training.

This is the top-level entrypoint for the simplified training pipeline.  It
keeps the trainer and all pipeline logic in TestCasePrioritization, while a
foreign repository such as predictiveRTS is used only as a convenient source of
downloaded Maven projects.

Pipeline:

  Maven project -> exported graph -> existing PIT mutations.xml -> priority dataset
  -> combined dataset -> train/validation/test split -> shared-weight train/eval

The trainer is called with ``--update-scope shared`` and this script never
enables edge overrides.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from collections import Counter, OrderedDict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


KNOWN_PACKAGE_OVERRIDES = {
    "apache_commons-codec": "org.apache.commons.codec.*",
    "apache_commons-collections": "org.apache.commons.collections4.*",
    "apache_commons-io": "org.apache.commons.io.*",
    "apache_commons-lang": "org.apache.commons.lang3.*",
    "apache_commons-text": "org.apache.commons.text.*",
    "apache_commons-validator": "org.apache.commons.validator.*",
    "asterisk-java_asterisk-java": "org.asteriskjava.*",
    "Bukkit_Bukkit": "org.bukkit.*",
    "frizbog_gedcom4j": "org.folg.gedcom.*",
    "google_gson": "com.google.gson.*",
    "jhy_jsoup": "org.jsoup.*",
    "jodaorg_joda-time": "org.joda.time.*",
    "mikera_vectorz": "mikera.vectorz.*",
    "zeroturnaround_zt-exec": "org.zeroturnaround.exec.*",
}

@dataclass
class CommandRecord:
    """Small serializable record for one external command."""

    label: str
    command: List[str]
    cwd: str
    log: str
    returncode: Optional[int] = None
    timed_out: bool = False
    duration_seconds: float = 0.0

    def as_dict(self) -> Dict[str, Any]:
        return {
            "label": self.label,
            "command": self.command,
            "cwd": self.cwd,
            "log": self.log,
            "returncode": self.returncode,
            "timedOut": self.timed_out,
            "durationSeconds": round(self.duration_seconds, 3),
        }


@dataclass
class ProjectRun:
    """Runtime state for one Maven project."""

    subject: str
    repo: str
    project_path: str = "."
    enabled: bool = True
    main_package: Optional[str] = None
    test_package: Optional[str] = None
    status: str = "pending"
    stage: Optional[str] = None
    reason: Optional[str] = None
    graph: Optional[str] = None
    mutations: Optional[str] = None
    dataset: Optional[str] = None
    examples: int = 0
    total_mutations: Optional[int] = None
    skipped: Dict[str, Any] = field(default_factory=dict)
    commands: List[CommandRecord] = field(default_factory=list)

    def as_dict(self) -> Dict[str, Any]:
        return {
            "subject": self.subject,
            "repo": self.repo,
            "projectPath": self.project_path,
            "enabled": self.enabled,
            "mainPackage": self.main_package,
            "testPackage": self.test_package,
            "status": self.status,
            "stage": self.stage,
            "reason": self.reason,
            "graph": self.graph,
            "mutations": self.mutations,
            "dataset": self.dataset,
            "examples": self.examples,
            "totalMutations": self.total_mutations,
            "skipped": self.skipped,
            "commands": [command.as_dict() for command in self.commands],
        }


class StageFailure(Exception):
    """Failure in one stage of one project."""

    def __init__(self, stage: str, reason: str) -> None:
        super().__init__(reason)
        self.stage = stage
        self.reason = reason


def main() -> int:
    args = parse_args()
    workspace = args.workspace.resolve()
    script_dir = workspace / "training" / "scripts" / "internal"
    run_id = args.run_id or datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    run_dir = resolve_workspace_path(workspace, args.run_root) / run_id
    logs_dir = run_dir / "logs"
    run_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    report: Dict[str, Any] = {
        "runId": run_id,
        "status": "running",
        "startedAt": iso_now(),
        "workspace": str(workspace),
        "runDir": str(run_dir),
        "command": sys.argv,
        "projects": [],
        "outputs": {},
        "training": {"status": "skipped"},
        "evaluations": {},
    }

    try:
        validate_local_pipeline(workspace, script_dir)
        maven_command = resolve_maven_command(args.maven_command)
        java_home = resolve_java_home(args.java_home)
        env = build_command_env(java_home, maven_command)

        report["mavenCommand"] = maven_command
        report["javaHome"] = str(java_home) if java_home else None
        report["projectsRoot"] = str(resolve_projects_root(args, workspace))

        projects = load_projects(args, workspace)
        report["projects"] = [project.as_dict() for project in projects]

        if args.dry_run:
            for project in projects:
                print_project_plan(project, workspace)
            report["status"] = "dry-run"
            exit_code = write_reports(report, run_dir)
            cleanup_after_execution(args, workspace, script_dir, run_dir, report)
            return exit_code

        successful_datasets: List[Path] = []
        for project in projects:
            if not project.enabled:
                project.status = "skipped"
                project.stage = "config"
                project.reason = "Project disabled in config."
                print(f"[{project.subject}] skipped: disabled")
                continue

            run_project(
                args=args,
                project=project,
                workspace=workspace,
                script_dir=script_dir,
                maven_command=maven_command,
                env=env,
                logs_dir=logs_dir,
            )
            if project.status == "success" and project.dataset:
                successful_datasets.append(Path(project.dataset))
            if args.fail_fast and project.status == "failed":
                break

        report["projects"] = [project.as_dict() for project in projects]
        if not successful_datasets:
            report["status"] = "failed"
            report["reason"] = "No per-project datasets were generated."
            exit_code = write_reports(report, run_dir, exit_code=1)
            cleanup_after_execution(args, workspace, script_dir, run_dir, report)
            return exit_code

        combined_dataset = run_combine(args, workspace, script_dir, successful_datasets, run_dir, logs_dir, env)
        split_outputs = run_split(args, workspace, script_dir, combined_dataset, run_dir, logs_dir, env)
        report["outputs"]["combinedDataset"] = str(combined_dataset)
        report["outputs"]["splits"] = {name: str(path) for name, path in split_outputs.items()}

        if args.train:
            training_outputs = run_training(args, workspace, script_dir, split_outputs["train"], run_dir, logs_dir, env)
            report["training"] = training_outputs
            if not args.no_evaluate:
                report["evaluations"] = run_evaluations(args, workspace, script_dir, split_outputs, training_outputs["weights"], run_dir, logs_dir, env)
        else:
            report["training"] = {"status": "skipped", "reason": "Pass --train to run shared-weight training."}

        report["projects"] = [project.as_dict() for project in projects]
        failed_count = sum(1 for project in projects if project.status == "failed")
        report["status"] = "partial-success" if failed_count else "success"
        exit_code = write_reports(report, run_dir)
        cleanup_after_execution(args, workspace, script_dir, run_dir, report)
        return exit_code
    except KeyboardInterrupt:
        report["status"] = "failed"
        report["reason"] = "Interrupted by user."
        exit_code = write_reports(report, run_dir, exit_code=130)
        cleanup_after_execution(args, workspace, script_dir, run_dir, report)
        return exit_code
    except Exception as exc:  # noqa: BLE001 - preserve errors in run-report
        report["status"] = "failed"
        report["reason"] = str(exc)
        exit_code = write_reports(report, run_dir, exit_code=1)
        cleanup_after_execution(args, workspace, script_dir, run_dir, report)
        return exit_code


def parse_args() -> argparse.Namespace:
    workspace_default = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Build graph + priority datasets from existing PIT reports.")
    parser.add_argument("--workspace", type=Path, default=workspace_default, help="TestCasePrioritization repository root.")
    parser.add_argument("--projects-root", type=Path, help="Directory containing downloaded Maven projects.")
    parser.add_argument("--projects", type=Path, default=Path("training-projects.json"), help="Optional project config JSON.")
    parser.add_argument("--run-root", type=Path, default=Path("training/runs"), help="Run report directory relative to workspace.")
    parser.add_argument("--run-id", help="Stable run id. Defaults to timestamp.")

    parser.add_argument("--maven-command", help="Maven executable. Defaults to MAVEN_COMMAND, mvn, or local Maven path.")
    parser.add_argument("--java-home", type=Path, help="JAVA_HOME for graph export commands.")
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--head", default="HEAD")

    parser.add_argument("--only", action="append", help="Only run this subject. Can be passed multiple times.")
    parser.add_argument("--exclude", action="append", help="Skip this subject. Can be passed multiple times.")
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument("--dry-run", action="store_true")

    parser.add_argument("--skip-test-compile", action="store_true")
    parser.add_argument("--skip-graph", action="store_true", help="Reuse training/graphs/<subject>-base.json.")

    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--max-tests-per-queue", type=int, default=5)
    parser.add_argument("--dataset-max-depth", type=int, default=4)
    parser.add_argument("--risk-value", type=float, default=1.0)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--train-ratio", type=float, default=0.70)
    parser.add_argument("--validation-ratio", type=float, default=0.15)
    parser.add_argument("--test-ratio", type=float, default=0.15)

    parser.add_argument("--train", action="store_true")
    parser.add_argument("--no-evaluate", action="store_true")
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--learning-rate", type=float, default=0.05)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--train-max-depth", type=int, default=4)

    parser.add_argument("--test-compile-timeout-seconds", type=int, default=900)
    parser.add_argument("--graph-timeout-seconds", type=int, default=900)
    parser.add_argument("--dataset-timeout-seconds", type=int, default=600)
    parser.add_argument("--global-timeout-seconds", type=int, default=600)
    parser.add_argument("--keep-logs", action="store_true", help="Keep training/runs/<run-id>/logs after execution.")
    parser.add_argument("--keep-case-artifacts", action="store_true", help="Keep per-case graph/queue files after --train.")
    parser.add_argument("--keep-pycache", action="store_true", help="Keep Python __pycache__ folders after execution.")
    return parser.parse_args()


def validate_local_pipeline(workspace: Path, script_dir: Path) -> None:
    required = [
        script_dir / "export_java_graph.py",
        script_dir / "build_priority_dataset_from_pit.py",
        script_dir / "combine_priority_datasets.py",
        script_dir / "split_priority_dataset.py",
        script_dir / "train_priority_queue.py",
        script_dir / "evaluate_priority_queue.py",
        workspace / "settings" / "edge-weight.json",
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError(f"Local training pipeline is incomplete: missing {missing}")


def resolve_projects_root(args: argparse.Namespace, workspace: Path) -> Path:
    if args.projects_root:
        return args.projects_root.resolve()
    env_root = os.environ.get("PRIORITY_PROJECTS_ROOT")
    if env_root:
        return Path(env_root).resolve()
    sibling_predictiverts = workspace.parent / "predictiverts" / "_downloads"
    if sibling_predictiverts.is_dir():
        return sibling_predictiverts.resolve()
    return (workspace / "_downloads").resolve()


def resolve_maven_command(command: Optional[str]) -> str:
    if command:
        candidate = Path(command)
        if candidate.is_file():
            return str(candidate.resolve())
        resolved = shutil.which(command)
        return resolved or command

    env_command = os.environ.get("MAVEN_COMMAND")
    if env_command:
        return resolve_maven_command(env_command)

    for name in ("mvn.cmd", "mvn.bat", "mvn"):
        resolved = shutil.which(name)
        if resolved:
            return resolved

    bundled = Path.home() / "Desktop" / "apache-maven-3.9.16" / "bin" / "mvn.cmd"
    return str(bundled) if bundled.is_file() else "mvn"


def resolve_java_home(java_home: Optional[Path]) -> Optional[Path]:
    if java_home:
        return java_home.resolve()
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"]).resolve()
    default = Path("C:/Program Files/Java/jdk-21")
    return default if default.exists() else None


def build_command_env(java_home: Optional[Path], maven_command: str) -> Dict[str, str]:
    env = os.environ.copy()
    path_key = "Path" if "Path" in env else "PATH"
    path_parts: List[str] = []
    if java_home:
        env["JAVA_HOME"] = str(java_home)
        path_parts.append(str(java_home / "bin"))
    maven_path = Path(maven_command)
    if maven_path.is_file():
        path_parts.append(str(maven_path.parent))
    if path_parts:
        env[path_key] = os.pathsep.join(path_parts + [env.get(path_key, "")])
    return env


def load_projects(args: argparse.Namespace, workspace: Path) -> List[ProjectRun]:
    projects_root = resolve_projects_root(args, workspace)
    config_path = resolve_workspace_path(workspace, args.projects)
    config_payload: Dict[str, Any] = load_json(config_path) if config_path.is_file() else {}
    defaults = config_payload.get("defaults", {}) if isinstance(config_payload, dict) else {}
    auto_discover = bool(config_payload.get("autoDiscover", True)) if isinstance(config_payload, dict) else True
    ordered: "OrderedDict[str, Dict[str, Any]]" = OrderedDict()

    if auto_discover:
        for raw in discover_project_configs(projects_root):
            ordered[raw["subject"]] = raw

    for raw_project in config_payload.get("projects", []) if isinstance(config_payload, dict) else []:
        if not isinstance(raw_project, dict):
            continue
        subject = str(raw_project.get("subject") or Path(str(raw_project.get("repo", ""))).name)
        if not subject:
            continue
        base = ordered.get(subject, {})
        merged = {**base, **raw_project, "subject": subject}
        ordered[subject] = merged

    projects = [project_from_config(raw, defaults, projects_root) for raw in ordered.values()]
    projects = apply_project_filters(projects, args.only, args.exclude)
    for project in projects:
        infer_project_packages(project)
    return projects


def discover_project_configs(projects_root: Path) -> List[Dict[str, Any]]:
    if not projects_root.is_dir():
        return []
    result = []
    for child in sorted(projects_root.iterdir(), key=lambda path: path.name.lower()):
        if child.is_dir() and (child / "pom.xml").is_file():
            result.append({"subject": child.name, "repo": str(child.resolve()), "enabled": True})
    return result


def project_from_config(raw: Dict[str, Any], defaults: Dict[str, Any], projects_root: Path) -> ProjectRun:
    subject = str(raw["subject"])
    repo_value = raw.get("repo") or str(projects_root / subject)
    repo_path = Path(str(repo_value))
    if not repo_path.is_absolute():
        repo_path = projects_root / repo_path
    return ProjectRun(
        subject=subject,
        repo=str(repo_path.resolve()),
        project_path=str(raw.get("projectPath") or raw.get("project_path") or "."),
        enabled=bool(raw.get("enabled", defaults.get("enabled", True))),
        main_package=raw.get("mainPackage") or raw.get("main_package") or raw.get("targetClasses"),
        test_package=raw.get("testPackage") or raw.get("test_package") or raw.get("targetTests"),
    )


def apply_project_filters(projects: List[ProjectRun], only: Optional[List[str]], exclude: Optional[List[str]]) -> List[ProjectRun]:
    only_set = {value.lower() for value in only or []}
    exclude_set = {value.lower() for value in exclude or []}
    result = []
    for project in projects:
        lowered = project.subject.lower()
        if only_set and lowered not in only_set:
            continue
        if lowered in exclude_set:
            continue
        result.append(project)
    return result


def infer_project_packages(project: ProjectRun) -> None:
    project_root = (Path(project.repo) / project.project_path).resolve()
    if not project.main_package:
        project.main_package = KNOWN_PACKAGE_OVERRIDES.get(project.subject)
    if not project.main_package:
        project.main_package = infer_java_package(project_root / "src" / "main" / "java")
    if not project.test_package:
        project.test_package = infer_java_package(project_root / "src" / "test" / "java") or project.main_package


def infer_java_package(source_root: Path) -> Optional[str]:
    if not source_root.is_dir():
        return None
    package_pattern = re.compile(r"^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;")
    packages: List[str] = []
    for java_file in source_root.rglob("*.java"):
        try:
            for line in java_file.read_text(encoding="utf-8", errors="ignore").splitlines()[:80]:
                match = package_pattern.match(line)
                if match:
                    packages.append(match.group(1))
                    break
        except OSError:
            continue
    if not packages:
        return None
    prefix = choose_package_prefix(packages)
    return f"{prefix}.*" if prefix else None


def choose_package_prefix(packages: List[str]) -> Optional[str]:
    split_packages = [package.split(".") for package in packages]
    common = list(split_packages[0])
    for parts in split_packages[1:]:
        limit = min(len(common), len(parts))
        index = 0
        while index < limit and common[index] == parts[index]:
            index += 1
        common = common[:index]
        if not common:
            break
    if len(common) >= 2:
        return ".".join(common)

    total = len(packages)
    candidates: Counter[str] = Counter()
    for parts in split_packages:
        for length in range(min(4, len(parts)), 1, -1):
            candidates[".".join(parts[:length])] += 1
    viable = [(prefix, count, len(prefix.split("."))) for prefix, count in candidates.items() if count / total >= 0.5]
    if viable:
        prefix, _, _ = sorted(viable, key=lambda item: (item[1], item[2], item[0]), reverse=True)[0]
        return prefix
    return candidates.most_common(1)[0][0] if candidates else None


def run_project(
    args: argparse.Namespace,
    project: ProjectRun,
    workspace: Path,
    script_dir: Path,
    maven_command: str,
    env: Dict[str, str],
    logs_dir: Path,
) -> None:
    subject = project.subject
    repo_root = Path(project.repo).resolve()
    project_root = (repo_root / project.project_path).resolve()
    project.status = "running"

    try:
        require_project_layout(project, project_root)

        reuse_existing_artifacts = args.skip_graph
        if reuse_existing_artifacts and not args.skip_test_compile:
            print(f"[{subject}] test-compile skipped: reusing existing graph and PIT report")
        elif not args.skip_test_compile:
            project.stage = "test-compile"
            run_maven_stage(
                project,
                "test-compile",
                [maven_command, "test-compile"],
                project_root,
                env,
                args.test_compile_timeout_seconds,
                logs_dir / f"{subject}-test-compile.log",
            )
            print(f"[{subject}] test-compile OK")

        graph_path = workspace / "training" / "graphs" / f"{subject}-base.json"
        project.stage = "graph"
        if args.skip_graph:
            if not graph_path.is_file():
                fail_project(project, "graph", f"Missing graph for --skip-graph: {graph_path}")
                return
        else:
            run_python_stage(
                project,
                "export-graph",
                script_dir / "export_java_graph.py",
                [
                    "--subject", subject,
                    "--repo", str(repo_root),
                    "--project-path", project.project_path,
                    "--base", args.base,
                    "--head", args.head,
                    "--output", str(graph_path),
                    "--maven-command", maven_command,
                ],
                workspace,
                env,
                args.graph_timeout_seconds,
                logs_dir / f"{subject}-export-graph.log",
            )
        project.graph = str(graph_path)
        print(f"[{subject}] graph OK: {graph_path}")

        project.stage = "mutations"
        mutations_path = locate_existing_mutations(project_root)
        if not mutations_path:
            fail_project(project, "mutations", "No existing target/pit-reports/**/mutations.xml found.")
            return
        project.mutations = str(mutations_path)
        print(f"[{subject}] mutations OK: {mutations_path}")

        project.stage = "dataset"
        dataset_path, summary = build_project_dataset(args, project, workspace, script_dir, graph_path, mutations_path, env, logs_dir)
        project.dataset = str(dataset_path)
        project.examples = int(summary.get("exported", 0))
        project.total_mutations = summary.get("totalMutations")
        project.skipped = summary.get("skipped", {})
        if project.examples <= 0:
            fail_project(project, "dataset", "Dataset builder exported 0 examples.")
            return
        project.status = "success"
        project.stage = "done"
        project.reason = None
        print(f"[{subject}] dataset OK: {project.examples} examples")
    except StageFailure as exc:
        fail_project(project, exc.stage, exc.reason)
        print(f"[{subject}] {exc.stage} FAILED: {exc.reason}")


def require_project_layout(project: ProjectRun, project_root: Path) -> None:
    if not project_root.is_dir():
        raise StageFailure("verify", f"Project path does not exist: {project_root}")
    if not (project_root / "pom.xml").is_file():
        raise StageFailure("verify", f"Missing pom.xml: {project_root / 'pom.xml'}")
    if not (project_root / "src" / "main" / "java").is_dir():
        raise StageFailure("verify", "src/main/java not found; configure projectPath or disable this project.")
    if not project.main_package:
        raise StageFailure("verify", "Could not infer mainPackage; add it to training-projects.json.")


def locate_existing_mutations(project_root: Path) -> Optional[Path]:
    """Return the latest PIT mutations.xml already present in the project."""
    return find_latest_mutations(project_root, since=None)


def find_latest_mutations(project_root: Path, since: Optional[float]) -> Optional[Path]:
    pit_root = project_root / "target" / "pit-reports"
    if not pit_root.is_dir():
        return None
    candidates = []
    for path in pit_root.rglob("mutations.xml"):
        try:
            stat = path.stat()
        except OSError:
            continue
        if since is not None and stat.st_mtime < since - 5:
            continue
        candidates.append((stat.st_mtime, path))
    return sorted(candidates, key=lambda item: item[0], reverse=True)[0][1].resolve() if candidates else None


def build_project_dataset(
    args: argparse.Namespace,
    project: ProjectRun,
    workspace: Path,
    script_dir: Path,
    graph_path: Path,
    mutations_path: Path,
    env: Dict[str, str],
    logs_dir: Path,
) -> Tuple[Path, Dict[str, Any]]:
    started = time.time()
    run_python_stage(
        project,
        "build-dataset",
        script_dir / "build_priority_dataset_from_pit.py",
        [
            "--subject", project.subject,
            "--graph", str(graph_path),
            "--mutations", str(mutations_path),
            "--limit", str(args.limit),
            "--max-tests-per-queue", str(args.max_tests_per_queue),
            "--max-depth", str(args.dataset_max_depth),
            "--risk-value", str(args.risk_value),
        ],
        workspace,
        env,
        args.dataset_timeout_seconds,
        logs_dir / f"{project.subject}-build-dataset.log",
    )
    dataset_path, summary = find_project_dataset(workspace, project.subject, since=started)
    if not dataset_path:
        raise StageFailure("dataset", "Dataset builder completed but dataset.json was not found.")
    return dataset_path, summary


def find_project_dataset(workspace: Path, subject: str, since: float) -> Tuple[Optional[Path], Dict[str, Any]]:
    root = workspace / "training" / "priority-datasets"
    candidates: List[Tuple[float, Path, Dict[str, Any]]] = []
    for summary_path in root.glob("*/summary.json"):
        try:
            summary = load_json(summary_path)
        except (OSError, json.JSONDecodeError):
            continue
        if summary.get("subject") != subject:
            continue
        try:
            mtime = summary_path.stat().st_mtime
        except OSError:
            continue
        if mtime < since - 5:
            continue
        dataset_value = summary.get("dataset") or str(summary_path.parent / "dataset.json")
        dataset_path = Path(dataset_value)
        if not dataset_path.is_absolute():
            dataset_path = (workspace / dataset_path).resolve()
        if dataset_path.is_file():
            candidates.append((mtime, dataset_path, summary))
    if candidates:
        _, dataset_path, summary = sorted(candidates, key=lambda item: item[0], reverse=True)[0]
        return dataset_path.resolve(), summary

    fallback = root / subject / "dataset.json"
    if fallback.is_file():
        summary_path = fallback.parent / "summary.json"
        return fallback.resolve(), load_json(summary_path) if summary_path.is_file() else {}
    return None, {}


def run_combine(
    args: argparse.Namespace,
    workspace: Path,
    script_dir: Path,
    datasets: List[Path],
    run_dir: Path,
    logs_dir: Path,
    env: Dict[str, str],
) -> Path:
    output = run_dir / "all-projects-dataset.json"
    command_args: List[str] = ["--output", str(output)]
    for dataset in datasets:
        command_args.extend(["--dataset", str(dataset)])
    record = run_command(
        "combine-datasets",
        [sys.executable, str(script_dir / "combine_priority_datasets.py")] + command_args,
        workspace,
        env,
        args.global_timeout_seconds,
        logs_dir / "combine-datasets.log",
    )
    if record.returncode != 0:
        raise RuntimeError(f"combine_priority_datasets.py failed: {command_failure_summary(Path(record.log))}")
    print(f"[all-projects] combine OK: {output}")
    return output.resolve()


def run_split(
    args: argparse.Namespace,
    workspace: Path,
    script_dir: Path,
    combined_dataset: Path,
    run_dir: Path,
    logs_dir: Path,
    env: Dict[str, str],
) -> Dict[str, Path]:
    output_dir = run_dir / "splits"
    command = [
        sys.executable,
        str(script_dir / "split_priority_dataset.py"),
        "--dataset", str(combined_dataset),
        "--output-dir", str(output_dir),
        "--subject", "all-projects",
        "--train-ratio", str(args.train_ratio),
        "--validation-ratio", str(args.validation_ratio),
        "--test-ratio", str(args.test_ratio),
        "--seed", str(args.seed),
    ]
    record = run_command("split-dataset", command, workspace, env, args.global_timeout_seconds, logs_dir / "split-dataset.log")
    if record.returncode != 0:
        raise RuntimeError(f"split_priority_dataset.py failed: {command_failure_summary(Path(record.log))}")
    outputs = {
        "train": (output_dir / "all-projects-train.json").resolve(),
        "validation": (output_dir / "all-projects-validation.json").resolve(),
        "test": (output_dir / "all-projects-test.json").resolve(),
        "summary": (output_dir / "all-projects-split-summary.json").resolve(),
    }
    print(f"[all-projects] split OK: {output_dir}")
    return outputs


def run_training(
    args: argparse.Namespace,
    workspace: Path,
    script_dir: Path,
    train_dataset: Path,
    run_dir: Path,
    logs_dir: Path,
    env: Dict[str, str],
) -> Dict[str, Any]:
    weights = (workspace / "settings" / "edge-weight.json").resolve()
    summary = (run_dir / "priority-queue-training-summary.json").resolve()
    ranking = (run_dir / "priority-queue-final-ranking.json").resolve()
    command = [
        sys.executable,
        str(script_dir / "train_priority_queue.py"),
        "--dataset", str(train_dataset),
        "--weights", str(weights),
        "--output", str(weights),
        "--summary", str(summary),
        "--ranking-output", str(ranking),
        "--update-scope", "shared",
        "--iterations", str(args.iterations),
        "--learning-rate", str(args.learning_rate),
        "--max-depth", str(args.train_max_depth),
        "--top-k", str(args.top_k),
    ]
    record = run_command("train", command, workspace, env, args.global_timeout_seconds, logs_dir / "train.log")
    if record.returncode != 0:
        raise RuntimeError(f"train_priority_queue.py failed: {command_failure_summary(Path(record.log))}")
    payload = load_json(summary) if summary.is_file() else {}
    print(f"[all-projects] train OK: {summary}")
    if payload:
        print(
            "[all-projects] train summary: "
            f"examples={payload.get('trainingExampleCount')}, "
            f"iterations={payload.get('iterationsRun')}, "
            f"updates={payload.get('totalUpdates')}, "
            f"changedWeights={len(payload.get('changedWeights', []))}"
        )
        print(f"[all-projects] train statuses: {payload.get('statusCounts', {})}")
        print(f"[all-projects] train weights: {payload.get('updateWeightCounts', {})}")
    return {
        "status": "success",
        "dataset": str(train_dataset),
        "weights": str(weights),
        "summary": str(summary),
        "ranking": str(ranking),
        "metrics": payload.get("metrics", {}),
    }


def run_evaluations(
    args: argparse.Namespace,
    workspace: Path,
    script_dir: Path,
    split_outputs: Dict[str, Path],
    weights: str,
    run_dir: Path,
    logs_dir: Path,
    env: Dict[str, str],
) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for split_name in ("train", "validation", "test"):
        dataset = split_outputs[split_name]
        output = (run_dir / f"priority-queue-{split_name}-evaluation.json").resolve()
        ranking = (run_dir / f"priority-queue-{split_name}-ranking.json").resolve()
        command = [
            sys.executable,
            str(script_dir / "evaluate_priority_queue.py"),
            "--dataset", str(dataset),
            "--weights", weights,
            "--output", str(output),
            "--ranking-output", str(ranking),
            "--max-depth", str(args.train_max_depth),
            "--top-k", str(args.top_k),
        ]
        record = run_command(
            f"evaluate-{split_name}",
            command,
            workspace,
            env,
            args.global_timeout_seconds,
            logs_dir / f"evaluate-{split_name}.log",
        )
        if record.returncode != 0:
            raise RuntimeError(f"evaluate_priority_queue.py failed for {split_name}: {command_failure_summary(Path(record.log))}")
        payload = load_json(output) if output.is_file() else {}
        result[split_name] = {
            "status": "success",
            "dataset": str(dataset),
            "output": str(output),
            "ranking": str(ranking),
            "metrics": payload.get("metrics", {}),
        }
        print(f"[all-projects] evaluate {split_name} OK: {output}")
    return result


def run_maven_stage(
    project: ProjectRun,
    label: str,
    command: List[str],
    cwd: Path,
    env: Dict[str, str],
    timeout: int,
    log_path: Path,
) -> CommandRecord:
    record = run_command(label, command, cwd, env, timeout, log_path)
    project.commands.append(record)
    if record.returncode != 0:
        raise StageFailure(label, command_failure_summary(log_path))
    return record


def run_python_stage(
    project: ProjectRun,
    label: str,
    script: Path,
    script_args: List[str],
    cwd: Path,
    env: Dict[str, str],
    timeout: int,
    log_path: Path,
) -> CommandRecord:
    return run_maven_stage(project, label, [sys.executable, str(script)] + script_args, cwd, env, timeout, log_path)


def run_command(
    label: str,
    command: List[str],
    cwd: Path,
    env: Dict[str, str],
    timeout: int,
    log_path: Path,
) -> CommandRecord:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    record = CommandRecord(label=label, command=command, cwd=str(cwd), log=str(log_path))
    started = time.monotonic()
    output = ""
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            check=False,
        )
        record.returncode = completed.returncode
        output = completed.stdout or ""
    except subprocess.TimeoutExpired as exc:
        record.returncode = 124
        record.timed_out = True
        output = exc.stdout if isinstance(exc.stdout, str) else ""
        output += f"\nCommand timed out after {timeout} seconds.\n"
    finally:
        record.duration_seconds = time.monotonic() - started
        write_command_log(log_path, record, output)
    return record


def write_command_log(log_path: Path, record: CommandRecord, output: str) -> None:
    payload = [
        f"Label: {record.label}",
        f"Cwd: {record.cwd}",
        f"Command: {format_command(record.command)}",
        f"Exit code: {record.returncode}",
        f"Timed out: {record.timed_out}",
        f"Duration seconds: {record.duration_seconds:.3f}",
        "",
        "----- output -----",
        output,
    ]
    log_path.write_text("\n".join(payload), encoding="utf-8")


def command_failure_summary(log_path: Path, max_lines: int = 25) -> str:
    if not log_path.is_file():
        return f"Command failed; log not found: {log_path}"
    lines = log_path.read_text(encoding="utf-8", errors="ignore").splitlines()
    meaningful = [line.strip() for line in lines if line.strip()]
    if not meaningful:
        return f"Command failed; see {log_path}"
    tail = meaningful[-max_lines:]
    return f"{tail[-1]} (see {log_path})"


def fail_project(project: ProjectRun, stage: str, reason: str) -> None:
    project.status = "failed"
    project.stage = stage
    project.reason = reason


def print_project_plan(project: ProjectRun, workspace: Path) -> None:
    print(
        f"[{project.subject}] enabled={project.enabled} "
        f"repo={relative_or_absolute(workspace, Path(project.repo))} "
        f"projectPath={project.project_path} "
        f"mainPackage={project.main_package or '<missing>'} "
        f"testPackage={project.test_package or '<missing>'}"
    )


def write_reports(report: Dict[str, Any], run_dir: Path, exit_code: int = 0) -> int:
    report["finishedAt"] = iso_now()
    report_json = run_dir / "run-report.json"
    report_md = run_dir / "run-report.md"
    report_json.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    report_md.write_text(render_markdown_report(report), encoding="utf-8")
    print(f"Report JSON: {report_json}")
    print(f"Report MD:   {report_md}")
    return exit_code


def cleanup_after_execution(
    args: argparse.Namespace,
    workspace: Path,
    script_dir: Path,
    run_dir: Path,
    report: Dict[str, Any],
) -> None:
    """Remove outputs the pipeline does not keep as final artifacts."""

    if not args.keep_logs:
        safe_rmtree(run_dir / "logs", run_dir)

    training_succeeded = report.get("training", {}).get("status") == "success"
    if args.train and training_succeeded and not args.keep_case_artifacts:
        cleanup_case_artifacts(workspace)

    if not args.keep_pycache:
        for cache_dir in script_dir.rglob("__pycache__"):
            safe_rmtree(cache_dir, workspace)


def cleanup_case_artifacts(workspace: Path) -> None:
    datasets_root = workspace / "training" / "priority-datasets"
    if not datasets_root.is_dir():
        return

    for dataset_dir in datasets_root.iterdir():
        if not dataset_dir.is_dir() or dataset_dir.name.startswith("."):
            continue
        safe_rmtree(dataset_dir / "graphs", workspace)
        safe_rmtree(dataset_dir / "queues", workspace)


def safe_rmtree(path: Path, allowed_root: Path) -> None:
    if not path.exists():
        return

    resolved = path.resolve()
    root = allowed_root.resolve()
    try:
        resolved.relative_to(root)
    except ValueError:
        raise RuntimeError(f"Refusing to delete outside {root}: {resolved}")

    if resolved == root:
        raise RuntimeError(f"Refusing to delete root directory: {resolved}")

    shutil.rmtree(resolved)
    print(f"Cleaned: {resolved}")


def render_markdown_report(report: Dict[str, Any]) -> str:
    lines = [
        f"# Priority Training Dataset Run {report.get('runId')}",
        "",
        f"- Status: {report.get('status')}",
        f"- Started: {report.get('startedAt')}",
        f"- Finished: {report.get('finishedAt')}",
        f"- Workspace: `{report.get('workspace')}`",
        f"- Projects root: `{report.get('projectsRoot', '')}`",
        "",
        "## Projects",
        "",
        "| Subject | Status | Stage | Examples | Reason |",
        "| --- | --- | --- | ---: | --- |",
    ]
    for project in report.get("projects", []):
        lines.append(
            "| {subject} | {status} | {stage} | {examples} | {reason} |".format(
                subject=project.get("subject", ""),
                status=project.get("status", ""),
                stage=project.get("stage") or "",
                examples=project.get("examples", 0),
                reason=(project.get("reason") or "").replace("|", "\\|"),
            )
        )

    outputs = report.get("outputs", {})
    lines.extend(["", "## Outputs", ""])
    if outputs.get("combinedDataset"):
        lines.append(f"- Combined dataset: `{outputs['combinedDataset']}`")
    if outputs.get("splits"):
        for split_name, path in outputs["splits"].items():
            lines.append(f"- {split_name}: `{path}`")

    training = report.get("training", {})
    lines.extend(["", "## Training", ""])
    lines.append(f"- Status: {training.get('status', 'skipped')}")
    if training.get("weights"):
        lines.append(f"- Weights: `{training['weights']}`")
    if training.get("summary"):
        lines.append(f"- Summary: `{training['summary']}`")
    if training.get("metrics"):
        lines.extend(render_metrics_lines("train-summary", training["metrics"]))

    evaluations = report.get("evaluations", {})
    if evaluations:
        lines.extend(["", "## Evaluation Metrics", ""])
        for split_name, payload in evaluations.items():
            lines.extend(render_metrics_lines(split_name, payload.get("metrics", {})))
    return "\n".join(lines) + "\n"


def render_metrics_lines(name: str, metrics: Dict[str, Any]) -> List[str]:
    if not metrics:
        return [f"- {name}: metrics unavailable"]
    fields = [
        ("caseCount", "cases"),
        ("top1Accuracy", "top1"),
        ("meanTopKOrderAccuracy", "topKOrder"),
        ("meanAbsoluteRankError", "meanAbsRankError"),
    ]
    values = []
    for key, label in fields:
        if key not in metrics:
            continue
        value = metrics[key]
        if isinstance(value, float):
            value = f"{value:.4f}"
        values.append(f"{label}={value}")
    return [f"- {name}: " + ", ".join(values)] if values else [f"- {name}: metrics unavailable"]


def resolve_workspace_path(workspace: Path, path: Path) -> Path:
    return path.resolve() if path.is_absolute() else (workspace / path).resolve()


def relative_or_absolute(root: Path, path: Path) -> str:
    try:
        return str(path.resolve().relative_to(root.resolve()))
    except ValueError:
        return str(path.resolve())


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def format_command(command: List[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline(command)
    try:
        import shlex

        return shlex.join(command)
    except AttributeError:
        return " ".join(command)


def iso_now() -> str:
    return datetime.now().isoformat(timespec="seconds")


if __name__ == "__main__":
    raise SystemExit(main())
