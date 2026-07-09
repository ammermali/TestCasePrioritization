"""Run the Java CLI to export an impact graph for a target project."""

from __future__ import annotations
import argparse
import shutil
import subprocess
from pathlib import Path

from graph_propagation import write_json


def main() -> None:
    parser = argparse.ArgumentParser(description="Export a Java impact graph and copy it into training/graphs.")
    parser.add_argument("--subject", required=True)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--project-path", default=".")
    parser.add_argument("--base", default="HEAD~1")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--java-output", default="impact-graph.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--maven-command", default="mvn")
    parser.add_argument("--skip-run", action="store_true", help="Only copy an already generated graph from .tcpimpact.")
    args = parser.parse_args()

    java_output = Path(args.java_output)
    if java_output.is_absolute():
        raise ValueError("--java-output must be relative to the analyzed project's .tcpimpact directory")

    if not args.skip_run:
        # The Java CLI writes the graph under the analyzed project's .tcpimpact folder. This wrapper then copies it into the local training workspace
        exec_args = (
            f'--repo "{args.repo}" '
            f'--base "{args.base}" '
            f'--head "{args.head}" '
            f'--project-path "{args.project_path}" '
            f'--impact-graph-output "{java_output}"'
        )
        subprocess.run([resolve_maven_command(args.maven_command), "-q", "compile", "exec:java", f"-Dexec.args={exec_args}"], check=True)

    generated_graph = (args.repo / args.project_path / ".tcpimpact" / java_output).resolve()
    destination = args.output or Path("training/graphs") / f"{args.subject}-impact-graph.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(generated_graph, destination)

    write_json(destination.with_suffix(".summary.json"), {
        "subject": args.subject,
        "repo": str(args.repo),
        "projectPath": args.project_path,
        "base": args.base,
        "head": args.head,
        "generatedGraph": str(generated_graph),
        "trainingGraph": str(destination),
        "javaCliInvoked": not args.skip_run
    })


def resolve_maven_command(command: str) -> str:
    """Resolve Maven on Windows as well as Unix-like shells."""
    command_path = Path(command)
    if command_path.is_file():
        return str(command_path)

    resolved = shutil.which(command)
    if resolved:
        return resolved

    if command == "mvn":
        for windows_command in ("mvn.cmd", "mvn.bat"):
            resolved = shutil.which(windows_command)
            if resolved:
                return resolved

    return command


if __name__ == "__main__":
    main()
