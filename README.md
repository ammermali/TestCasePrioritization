# TestCasePrioritization

TCP Impact is a Java test-case prioritization prototype. It builds a
method-level impact graph from a Git diff, optional per-test JaCoCo coverage,
and static relationships between methods, then writes a ranked list of test
methods.

The repository also contains Python entrypoints for training the priority
model from existing mutation-testing data and for evaluating the Java model on
Defects4J bugs.

## Requirements

- Java 21 for building and running the TCP Impact model.
- Maven 3.9 or newer.
- Python 3.10 or newer for training and evaluation scripts.
- Defects4J for Defects4J evaluation only.
- Java 11 for Defects4J commands, because several Defects4J projects require
  it.

On Windows, run Defects4J evaluation from WSL. The Java model can still be
compiled from either Windows or WSL, as long as Java 21 and Maven are available.

## Build

Compile the Java project:

```bash
mvn -q -DskipTests compile
```

Show the TCP Impact CLI help:

```bash
mvn -q exec:java -Dexec.args="--help"
```

## Run TCP Impact

Run TCP Impact on a Git repository:

```bash
mvn -q exec:java -Dexec.args="\
  --repo /path/to/repository \
  --project-path . \
  --base HEAD~1 \
  --head HEAD \
  --output tcp-ranking.json \
  --graph-output tcp-impact-graph.json \
  --max-depth 4"
```

If per-test JaCoCo XML reports are available, pass their directory:

```bash
mvn -q exec:java -Dexec.args="\
  --repo /path/to/repository \
  --project-path . \
  --base HEAD~1 \
  --head HEAD \
  --coverage-dir /path/to/jacoco-xml-reports \
  --output tcp-ranking.json"
```

The main output is a JSON file containing:

- the analyzed repository and revisions;
- changed methods detected from the Git diff;
- ranked test methods with propagated risk scores.

## Training

The training entrypoint is:

```bash
python training/scripts/build_priority_training_dataset.py --help
```

Run a dry-run to verify the configured projects and commands:

```bash
python training/scripts/build_priority_training_dataset.py --dry-run
```

Run the full training pipeline:

```bash
python training/scripts/build_priority_training_dataset.py --train
```

Useful options:

```bash
python training/scripts/build_priority_training_dataset.py \
  --projects training-projects.json \
  --projects-root /path/to/downloaded/maven/projects \
  --run-id my-training-run \
  --train \
  --iterations 200 \
  --learning-rate 0.05
```

Training outputs are written under `training/runs/<run-id>/`. Intermediate
datasets are written under `training/priority-datasets/`. These directories are
ignored by Git because they are generated artifacts.

## Defects4J Evaluation

The Defects4J evaluation entrypoint is:

```bash
python3 model-evaluation/run_defects4j_evaluation.py --help
```

Example WSL command for one Defects4J bug with full per-test coverage:

```bash
python3 model-evaluation/run_defects4j_evaluation.py \
  --case Lang:1 \
  --coverage all \
  --coverage-workers 4 \
  --work-root ~/tcpimpact-d4j-work \
  --coverage-cache-root ~/tcpimpact-d4j-coverage-cache \
  --defects4j-java-home /usr/lib/jvm/java-11-openjdk-amd64 \
  --model-java-home /usr/lib/jvm/java-21-openjdk-amd64
```

Example for a bounded project-level evaluation:

```bash
python3 model-evaluation/run_defects4j_evaluation.py \
  --project Lang \
  --limit 10 \
  --coverage all \
  --coverage-workers 4 \
  --work-root ~/tcpimpact-d4j-work \
  --coverage-cache-root ~/tcpimpact-d4j-coverage-cache \
  --defects4j-java-home /usr/lib/jvm/java-11-openjdk-amd64 \
  --model-java-home /usr/lib/jvm/java-21-openjdk-amd64
```

The evaluation script writes:

- `results.json`, with the full structured result;
- `results.csv`, with one row per Defects4J case;
- `run-report.md`, with a compact human-readable summary;
- per-case ranking and graph files under `model-evaluation/runs/<run-id>/`.

Per-test coverage is expensive. The evaluation script stores a case-level
coverage cache under `--coverage-cache-root`. When the same Defects4J case is
evaluated again with the same selected tests, cached `.exec` and XML reports
are reused instead of recomputing coverage.

Use `--coverage none` only for a fast smoke test. Full evaluation should use
`--coverage all`.

## Generated Files

The repository ignores generated files such as:

- Maven and IDE build outputs;
- Python cache files;
- training runs and derived datasets;
- Defects4J evaluation runs and work directories;
- JaCoCo `.exec` files and `.tcpimpact` directories.

This keeps the repository focused on source code, configuration, and reusable
entrypoints.
