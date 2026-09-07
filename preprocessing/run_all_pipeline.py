#!/usr/bin/env python3
"""Run the complete Porto preprocessing pipeline with Telegram notifications.

Expected project layout (relative to --root):
    1_porto_map_match.py
    2_infer_background_from_edge_times.py
    3_build_od_route_shares.py
    4_generate_candidate_paths.py
    5_graphml_to_reroute_csv.py
    data/porto_train.csv
    data/porto_full_drive.graphml

Telegram credentials are read from environment variables by default:
    TELEGRAM_BOT_TOKEN
    TELEGRAM_CHAT_ID

Example (PowerShell):
    $env:TELEGRAM_BOT_TOKEN="123456:ABC..."; $env:TELEGRAM_CHAT_ID="123456789"; python .\\run_all_pipeline.py --root .

The runner:
  * executes all five scripts sequentially;
  * streams combined stdout/stderr to the terminal and a timestamped log;
  * sends Telegram updates at stage boundaries and periodic heartbeats;
  * stops immediately if a stage fails;
  * writes logs/pipeline_status.json for machine-readable status;
  * normalizes script 4's route/node sequence separators for the Java loader.
"""

from __future__ import annotations

import argparse
import codecs
import csv
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable, Sequence


TELEGRAM_MESSAGE_LIMIT = 4096
IMPORTANT_LINE_PATTERNS = (
    re.compile(r"Completed in ", re.IGNORECASE),
    re.compile(r"Study dates:", re.IGNORECASE),
    re.compile(r"Background edge-hour rows:", re.IGNORECASE),
    re.compile(r"Free-flow edge rows:", re.IGNORECASE),
    re.compile(r"Rows read:", re.IGNORECASE),
    re.compile(r"Routes written:", re.IGNORECASE),
    re.compile(r"Wrote .* routes", re.IGNORECASE),
    re.compile(r"Loaded .* directed edges", re.IGNORECASE),
)


@dataclass(frozen=True)
class PipelineStep:
    number: int
    name: str
    command: tuple[str, ...]
    required_inputs: tuple[Path, ...]
    outputs: tuple[Path, ...]
    postprocess: Callable[[], str | None] | None = None


class TelegramNotifier:
    """Small dependency-free Telegram Bot API client."""

    def __init__(
        self,
        bot_token: str,
        chat_id: str,
        *,
        required: bool = False,
        timeout_sec: float = 20.0,
    ) -> None:
        self.bot_token = bot_token.strip()
        self.chat_id = chat_id.strip()
        self.required = required
        self.timeout_sec = timeout_sec
        self.enabled = bool(self.bot_token and self.chat_id)
        self._warned = False

        if required and not self.enabled:
            raise ValueError(
                "Telegram is required, but TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID "
                "or --bot-token/--chat-id were not supplied."
            )

    def send(self, text: str) -> bool:
        if not self.enabled:
            return False

        success = True
        for chunk in split_telegram_message(text):
            payload = urllib.parse.urlencode(
                {
                    "chat_id": self.chat_id,
                    "text": chunk,
                    "disable_web_page_preview": "true",
                }
            ).encode("utf-8")
            request = urllib.request.Request(
                f"https://api.telegram.org/bot{self.bot_token}/sendMessage",
                data=payload,
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_sec) as response:
                    body = json.loads(response.read().decode("utf-8", errors="replace"))
                    if not body.get("ok", False):
                        raise RuntimeError(str(body))
            except Exception as exc:  # Network errors must not hide pipeline output.
                success = False
                message = f"Telegram notification failed: {type(exc).__name__}: {exc}"
                if self.required:
                    raise RuntimeError(message) from exc
                if not self._warned:
                    print(f"WARNING: {message}", file=sys.stderr, flush=True)
                    self._warned = True
        return success


def split_telegram_message(text: str) -> list[str]:
    text = str(text).strip()
    if not text:
        return []
    max_len = TELEGRAM_MESSAGE_LIMIT - 100
    chunks: list[str] = []
    remaining = text
    while len(remaining) > max_len:
        cut = remaining.rfind("\n", 0, max_len)
        if cut < max_len // 2:
            cut = max_len
        chunks.append(remaining[:cut].rstrip())
        remaining = remaining[cut:].lstrip()
    if remaining:
        chunks.append(remaining)
    return chunks


def human_duration(seconds: float) -> str:
    seconds = max(0.0, float(seconds))
    hours, remainder = divmod(int(seconds), 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h {minutes:02d}m {secs:02d}s"
    if minutes:
        return f"{minutes}m {secs:02d}s"
    return f"{secs}s"


def format_command(command: Sequence[str]) -> str:
    """Readable command formatting without exposing Telegram credentials."""
    return subprocess.list2cmdline([str(part) for part in command])


def atomic_write_json(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    temporary.replace(path)


def is_nonempty_file(path: Path) -> bool:
    try:
        return path.is_file() and path.stat().st_size > 0
    except OSError:
        return False


def validate_required_files(paths: Iterable[Path]) -> None:
    missing = [path for path in paths if not path.is_file()]
    if missing:
        joined = "\n".join(f"  - {path}" for path in missing)
        raise FileNotFoundError(f"Required file(s) not found:\n{joined}")


def normalize_candidate_route_sequences(path: Path) -> str | None:
    """Convert script 4's pipe-joined route sequences to Java's semicolon format.

    Node sequence:
        1|2|3 -> 1;2;3
    Edge sequence:
        1|2|0|2|3|0 -> 1|2|0;2|3|0

    The edge conversion groups tokens in triples because an edge ID itself has
    the form from_node|to_node|key.
    """
    if not path.is_file():
        return None

    changed_rows = 0
    total_rows = 0
    fd, temporary_name = tempfile.mkstemp(
        prefix=path.stem + "_normalized_",
        suffix=path.suffix,
        dir=str(path.parent),
    )
    os.close(fd)
    temporary = Path(temporary_name)

    try:
        with path.open("r", encoding="utf-8-sig", newline="") as source, temporary.open(
            "w", encoding="utf-8", newline=""
        ) as destination:
            reader = csv.DictReader(source)
            if not reader.fieldnames:
                raise ValueError(f"Candidate route CSV has no header: {path}")
            writer = csv.DictWriter(destination, fieldnames=reader.fieldnames)
            writer.writeheader()

            for row in reader:
                total_rows += 1
                changed = False

                node_text = (row.get("route_node_seq") or "").strip()
                if node_text and ";" not in node_text and "|" in node_text:
                    nodes = [part.strip() for part in node_text.split("|") if part.strip()]
                    row["route_node_seq"] = ";".join(nodes)
                    changed = True

                edge_text = (row.get("edge_seq") or "").strip()
                if edge_text and ";" not in edge_text and "|" in edge_text:
                    tokens = [part.strip() for part in edge_text.split("|") if part.strip()]
                    if len(tokens) % 3 != 0:
                        raise ValueError(
                            f"Cannot normalize edge_seq in row {total_rows}: "
                            f"expected a multiple of 3 pipe tokens, got {len(tokens)}."
                        )
                    edges = [
                        "|".join(tokens[index : index + 3])
                        for index in range(0, len(tokens), 3)
                    ]
                    row["edge_seq"] = ";".join(edges)
                    changed = True

                if changed:
                    changed_rows += 1
                writer.writerow(row)

        temporary.replace(path)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise

    if changed_rows:
        return f"Normalized route/node separators in {changed_rows:,}/{total_rows:,} candidate rows."
    return f"Candidate route separators already valid ({total_rows:,} rows checked)."


def terminate_process_tree(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass


def stream_reader(
    process: subprocess.Popen[bytes],
    output_queue: "queue.Queue[str | None]",
) -> None:
    """Consume bytes continuously, including tqdm carriage-return updates."""
    assert process.stdout is not None
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    pending = ""
    try:
        while True:
            chunk = os.read(process.stdout.fileno(), 8192)
            if not chunk:
                break
            pending += decoder.decode(chunk)
            pieces = re.split(r"[\r\n]+", pending)
            pending = pieces.pop() if pieces else ""
            for piece in pieces:
                line = piece.strip()
                if line:
                    output_queue.put(line)
        pending += decoder.decode(b"", final=True)
        if pending.strip():
            output_queue.put(pending.strip())
    finally:
        output_queue.put(None)


def run_step(
    step: PipelineStep,
    *,
    root: Path,
    log_handle,
    notifier: TelegramNotifier,
    heartbeat_sec: float,
    pipeline_started: float,
    status_path: Path,
) -> tuple[float, list[str]]:
    validate_required_files(step.required_inputs)

    print(f"\n{'=' * 80}", flush=True)
    print(f"STEP {step.number}: {step.name}", flush=True)
    print(format_command(step.command), flush=True)
    print(f"{'=' * 80}", flush=True)

    log_handle.write(f"\n{'=' * 80}\n")
    log_handle.write(f"STEP {step.number}: {step.name}\n")
    log_handle.write(format_command(step.command) + "\n")
    log_handle.write(f"{'=' * 80}\n")
    log_handle.flush()

    step_started = time.monotonic()
    notifier.send(
        f"▶️ Porto pipeline step {step.number}/5 started\n"
        f"{step.name}\n"
        f"Pipeline elapsed: {human_duration(step_started - pipeline_started)}"
    )

    creationflags = 0
    start_new_session = False
    if os.name == "nt":
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    else:
        start_new_session = True

    environment = os.environ.copy()
    environment["PYTHONUNBUFFERED"] = "1"
    environment.setdefault("PYTHONIOENCODING", "utf-8")

    process = subprocess.Popen(
        list(step.command),
        cwd=root,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        env=environment,
        creationflags=creationflags,
        start_new_session=start_new_session,
    )

    output_queue: "queue.Queue[str | None]" = queue.Queue()
    reader = threading.Thread(
        target=stream_reader,
        args=(process, output_queue),
        daemon=True,
    )
    reader.start()

    recent_lines: list[str] = []
    last_line = "No output yet."
    next_heartbeat = time.monotonic() + heartbeat_sec if heartbeat_sec > 0 else float("inf")
    reader_finished = False

    atomic_write_json(
        status_path,
        {
            "status": "running",
            "step": step.number,
            "step_name": step.name,
            "pid": process.pid,
            "started_at": datetime.now().astimezone().isoformat(),
            "command": list(step.command),
        },
    )

    try:
        while True:
            timeout = max(0.1, min(1.0, next_heartbeat - time.monotonic()))
            try:
                item = output_queue.get(timeout=timeout)
            except queue.Empty:
                item = "__NO_ITEM__"

            if item is None:
                reader_finished = True
            elif item != "__NO_ITEM__":
                line = str(item)
                print(line, flush=True)
                log_handle.write(line + "\n")
                log_handle.flush()
                last_line = line
                recent_lines.append(line)
                if len(recent_lines) > 30:
                    del recent_lines[:-30]

                if any(pattern.search(line) for pattern in IMPORTANT_LINE_PATTERNS):
                    notifier.send(
                        f"ℹ️ Step {step.number}/5: {step.name}\n"
                        f"{line[:3000]}"
                    )

            now = time.monotonic()
            if now >= next_heartbeat:
                notifier.send(
                    f"⏳ Porto pipeline still running\n"
                    f"Step {step.number}/5: {step.name}\n"
                    f"Step elapsed: {human_duration(now - step_started)}\n"
                    f"Pipeline elapsed: {human_duration(now - pipeline_started)}\n"
                    f"Latest output: {last_line[:2500]}"
                )
                next_heartbeat = now + heartbeat_sec

            if process.poll() is not None and reader_finished and output_queue.empty():
                break
    except KeyboardInterrupt:
        terminate_process_tree(process)
        raise

    return_code = process.wait()
    reader.join(timeout=2)
    elapsed = time.monotonic() - step_started

    if return_code != 0:
        tail = "\n".join(recent_lines[-12:]) or "(no captured output)"
        notifier.send(
            f"❌ Porto pipeline failed\n"
            f"Step {step.number}/5: {step.name}\n"
            f"Exit code: {return_code}\n"
            f"Elapsed: {human_duration(elapsed)}\n"
            f"Last output:\n{tail[:3000]}"
        )
        raise subprocess.CalledProcessError(return_code, step.command)

    missing_outputs = [path for path in step.outputs if not is_nonempty_file(path)]
    if missing_outputs:
        joined = ", ".join(str(path) for path in missing_outputs)
        raise RuntimeError(
            f"Step {step.number} exited successfully but expected output(s) are missing/empty: {joined}"
        )

    postprocess_message = step.postprocess() if step.postprocess else None
    if postprocess_message:
        print(postprocess_message, flush=True)
        log_handle.write(postprocess_message + "\n")
        log_handle.flush()

    notifier.send(
        f"✅ Porto pipeline step {step.number}/5 completed\n"
        f"{step.name}\n"
        f"Step runtime: {human_duration(elapsed)}"
        + (f"\n{postprocess_message}" if postprocess_message else "")
    )
    return elapsed, recent_lines


def build_steps(args: argparse.Namespace, root: Path) -> list[PipelineStep]:
    python_executable = str(Path(args.python).expanduser()) if args.python else sys.executable
    scripts = {
        number: root / f"{number}_{name}.py"
        for number, name in (
            (1, "porto_map_match"),
            (2, "infer_background_from_edge_times"),
            (3, "build_od_route_shares"),
            (4, "generate_candidate_paths"),
            (5, "graphml_to_reroute_csv"),
        )
    }

    graph = root / "data" / "porto_full_drive.graphml"
    raw_trips = root / "data" / "porto_train.csv"
    mapmatched = root / "outputs" / "porto_train_edge_mapmatched.csv"
    diagnostics = root / "logs" / "map_matching_diagnostics.csv"
    background = root / "outputs" / "background_congestion_flow.csv"
    free_flow = root / "outputs" / "sim_edge_lookup_freeflow.csv"
    od_shares = root / "outputs" / "od_route_share.csv"
    candidate_routes = root / "outputs" / "candidate_routes.csv"
    route_waypoints = root / "outputs" / "route_waypoints.csv"
    length_network = root / "outputs" / "length_network.csv"
    time_network = root / "outputs" / "time_network.csv"

    limit_args = ("--limit", str(args.limit)) if args.limit > 0 else ()

    return [
        PipelineStep(
            1,
            "Map-match Porto taxi trajectories",
            (
                python_executable,
                "-u",
                str(scripts[1]),
                "--input",
                str(raw_trips),
                "--output",
                str(mapmatched),
                "--diagnostics",
                str(diagnostics),
                "--graph",
                str(graph),
                "--workers",
                str(args.map_workers),
                "--chunksize",
                str(args.map_chunksize),
                "--merge-input-columns",
                *limit_args,
            ),
            (scripts[1], raw_trips, graph),
            (mapmatched, diagnostics),
        ),
        PipelineStep(
            2,
            "Infer hourly background traffic and free-flow edge costs",
            (
                python_executable,
                "-u",
                str(scripts[2]),
                "--input",
                str(mapmatched),
                "--graph",
                str(graph),
                "--output",
                str(background),
                "--free-flow-output",
                str(free_flow),
                "--workers",
                str(args.background_workers),
                "--warmup-days",
                str(args.warmup_days),
                *limit_args,
            ),
            (scripts[2], mapmatched, graph),
            (background, free_flow),
        ),
        PipelineStep(
            3,
            "Build observed OD route shares",
            (
                python_executable,
                "-u",
                str(scripts[3]),
                "--input",
                str(mapmatched),
                "--output",
                str(od_shares),
                "--graph",
                str(graph),
                "--min-route-trips",
                str(args.min_route_trips),
            ),
            (scripts[3], mapmatched, graph),
            (od_shares,),
        ),
        PipelineStep(
            4,
            "Generate candidate paths and route waypoints",
            (
                python_executable,
                "-u",
                str(scripts[4]),
                "--graph",
                str(graph),
                "--ods",
                str(od_shares),
                "--routes-out",
                str(candidate_routes),
                "--waypoints-out",
                str(route_waypoints),
                "--workers",
                str(args.candidate_workers),
                "--max-candidates",
                str(args.max_candidates),
            ),
            (scripts[4], graph, od_shares),
            (candidate_routes, route_waypoints),
            postprocess=lambda: normalize_candidate_route_sequences(candidate_routes),
        ),
        PipelineStep(
            5,
            "Convert GraphML to rerouting length/time CSVs",
            (
                python_executable,
                "-u",
                str(scripts[5]),
                str(graph),
                "--length-output",
                str(length_network),
                "--time-output",
                str(time_network),
            ),
            (scripts[5], graph),
            (length_network, time_network),
        ),
    ]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    cpu_count = os.cpu_count() or 4
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent,
        help="Project root containing scripts, data/, outputs/, and logs/.",
    )
    parser.add_argument(
        "--python",
        default=sys.executable,
        help="Python executable used to launch every child script.",
    )
    parser.add_argument(
        "--bot-token",
        default=os.environ.get("TELEGRAM_BOT_TOKEN", ""),
        help="Telegram bot token; preferably set TELEGRAM_BOT_TOKEN instead.",
    )
    parser.add_argument(
        "--chat-id",
        default=os.environ.get("TELEGRAM_CHAT_ID", ""),
        help="Telegram chat ID; preferably set TELEGRAM_CHAT_ID instead.",
    )
    parser.add_argument(
        "--telegram-required",
        action="store_true",
        help="Abort if Telegram is not configured or a notification fails.",
    )
    parser.add_argument(
        "--heartbeat-minutes",
        type=float,
        default=15.0,
        help="Send a Telegram heartbeat this often while a step runs; 0 disables it.",
    )
    parser.add_argument("--map-workers", type=int, default=min(4, max(1, cpu_count - 1)))
    parser.add_argument("--background-workers", type=int, default=min(8, max(1, cpu_count - 1)))
    parser.add_argument("--candidate-workers", type=int, default=min(4, max(1, cpu_count - 1)))
    parser.add_argument("--map-chunksize", type=int, default=8)
    parser.add_argument("--warmup-days", type=int, default=5)
    parser.add_argument("--min-route-trips", type=int, default=2)
    parser.add_argument("--max-candidates", type=int, default=15)
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Test mode: limit input rows for steps 1 and 2; 0 means all rows.",
    )
    parser.add_argument("--from-step", type=int, choices=range(1, 6), default=1)
    parser.add_argument("--to-step", type=int, choices=range(1, 6), default=5)
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="Skip a selected step when all of its expected outputs already exist and are non-empty.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate paths and print commands without executing them.",
    )
    parser.add_argument(
        "--log-file",
        type=Path,
        help="Explicit log path. Relative paths are resolved under --root.",
    )
    args = parser.parse_args(argv)

    if args.from_step > args.to_step:
        parser.error("--from-step cannot be greater than --to-step.")
    for label in ("map_workers", "background_workers", "candidate_workers", "map_chunksize"):
        if getattr(args, label) < 1:
            parser.error(f"--{label.replace('_', '-')} must be at least 1.")
    if args.heartbeat_minutes < 0:
        parser.error("--heartbeat-minutes cannot be negative.")
    if args.warmup_days < 0:
        parser.error("--warmup-days cannot be negative.")
    if args.min_route_trips < 1:
        parser.error("--min-route-trips must be at least 1.")
    if args.max_candidates < 1:
        parser.error("--max-candidates must be at least 1.")
    if args.limit < 0:
        parser.error("--limit cannot be negative.")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.root.expanduser().resolve()
    (root / "outputs").mkdir(parents=True, exist_ok=True)
    (root / "logs").mkdir(parents=True, exist_ok=True)

    if not Path(args.python).exists() and shutil.which(args.python) is None:
        raise FileNotFoundError(f"Python executable not found: {args.python}")

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    if args.log_file:
        log_path = args.log_file.expanduser()
        if not log_path.is_absolute():
            log_path = root / log_path
    else:
        log_path = root / "logs" / f"pipeline_{timestamp}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    status_path = root / "logs" / "pipeline_status.json"

    notifier = TelegramNotifier(
        args.bot_token,
        args.chat_id,
        required=args.telegram_required,
    )
    steps = [
        step
        for step in build_steps(args, root)
        if args.from_step <= step.number <= args.to_step
    ]

    print(f"Project root: {root}")
    print(f"Python: {args.python}")
    print(f"Log: {log_path}")
    print(f"Telegram: {'enabled' if notifier.enabled else 'disabled'}")
    print(f"Selected steps: {args.from_step} through {args.to_step}")

    for step in steps:
        print(f"\n[{step.number}] {step.name}")
        print(f"    {format_command(step.command)}")

    if args.dry_run:
        available = {path.resolve() for path in root.rglob("*") if path.is_file()}
        for step in steps:
            missing = [
                path for path in step.required_inputs
                if path.resolve() not in available
            ]
            if missing:
                joined = "\n".join(f"  - {path}" for path in missing)
                raise FileNotFoundError(
                    f"Step {step.number} has unavailable required input(s):\n{joined}"
                )
            available.update(path.resolve() for path in step.outputs)
        print("\nDry run successful: commands and dependency paths are valid.")
        return 0

    pipeline_started = time.monotonic()
    started_at = datetime.now().astimezone().isoformat()
    completed_steps: list[int] = []
    skipped_steps: list[int] = []
    step_runtimes: dict[str, float] = {}

    notifier.send(
        f"🚀 Porto preprocessing pipeline started\n"
        f"Steps: {args.from_step}–{args.to_step}\n"
        f"Root: {root}\n"
        f"Log: {log_path}"
    )

    atomic_write_json(
        status_path,
        {
            "status": "starting",
            "started_at": started_at,
            "root": str(root),
            "log_file": str(log_path),
            "selected_steps": [step.number for step in steps],
        },
    )

    try:
        with log_path.open("a", encoding="utf-8", buffering=1) as log_handle:
            log_handle.write(f"Pipeline started: {started_at}\n")
            log_handle.write(f"Project root: {root}\n")
            log_handle.write(f"Python: {args.python}\n")

            for step in steps:
                if args.skip_existing and all(is_nonempty_file(path) for path in step.outputs):
                    message = f"Skipped step {step.number}: outputs already exist."
                    print(f"\n{message}")
                    log_handle.write(message + "\n")
                    if step.postprocess:
                        postprocess_message = step.postprocess()
                        if postprocess_message:
                            print(postprocess_message)
                            log_handle.write(postprocess_message + "\n")
                    notifier.send(f"⏭️ {message}\n{step.name}")
                    skipped_steps.append(step.number)
                    continue

                elapsed, _ = run_step(
                    step,
                    root=root,
                    log_handle=log_handle,
                    notifier=notifier,
                    heartbeat_sec=args.heartbeat_minutes * 60.0,
                    pipeline_started=pipeline_started,
                    status_path=status_path,
                )
                completed_steps.append(step.number)
                step_runtimes[str(step.number)] = elapsed

        total_elapsed = time.monotonic() - pipeline_started
        finished_at = datetime.now().astimezone().isoformat()
        atomic_write_json(
            status_path,
            {
                "status": "completed",
                "started_at": started_at,
                "finished_at": finished_at,
                "elapsed_sec": total_elapsed,
                "completed_steps": completed_steps,
                "skipped_steps": skipped_steps,
                "step_runtimes_sec": step_runtimes,
                "log_file": str(log_path),
            },
        )
        notifier.send(
            f"🎉 Porto preprocessing pipeline completed\n"
            f"Runtime: {human_duration(total_elapsed)}\n"
            f"Completed steps: {completed_steps or '(none)'}\n"
            f"Skipped steps: {skipped_steps or '(none)'}\n"
            f"Log: {log_path}"
        )
        print(f"\nPipeline completed in {human_duration(total_elapsed)}")
        print(f"Log: {log_path}")
        print(f"Status: {status_path}")
        return 0

    except KeyboardInterrupt:
        total_elapsed = time.monotonic() - pipeline_started
        atomic_write_json(
            status_path,
            {
                "status": "cancelled",
                "started_at": started_at,
                "cancelled_at": datetime.now().astimezone().isoformat(),
                "elapsed_sec": total_elapsed,
                "completed_steps": completed_steps,
                "skipped_steps": skipped_steps,
                "log_file": str(log_path),
            },
        )
        notifier.send(
            f"🛑 Porto preprocessing pipeline cancelled\n"
            f"Elapsed: {human_duration(total_elapsed)}\n"
            f"Log: {log_path}"
        )
        print("\nPipeline cancelled.", file=sys.stderr)
        return 130

    except Exception as exc:
        total_elapsed = time.monotonic() - pipeline_started
        error_text = f"{type(exc).__name__}: {exc}"
        atomic_write_json(
            status_path,
            {
                "status": "failed",
                "started_at": started_at,
                "failed_at": datetime.now().astimezone().isoformat(),
                "elapsed_sec": total_elapsed,
                "completed_steps": completed_steps,
                "skipped_steps": skipped_steps,
                "error": error_text,
                "log_file": str(log_path),
            },
        )
        notifier.send(
            f"❌ Porto preprocessing pipeline stopped\n"
            f"Error: {error_text}\n"
            f"Elapsed: {human_duration(total_elapsed)}\n"
            f"Log: {log_path}"
        )
        print(f"\nERROR: {error_text}", file=sys.stderr)
        if os.environ.get("PIPELINE_DEBUG", "").strip() == "1":
            traceback.print_exc()
        return 1


if __name__ == "__main__":
    if os.name == "nt":
        # Child scripts use multiprocessing with spawn; keep the runner entrypoint safe.
        import multiprocessing

        multiprocessing.freeze_support()
    raise SystemExit(main())
