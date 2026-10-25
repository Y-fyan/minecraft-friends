#!/usr/bin/env python3
"""Run isolated probes and require semantic assertions, not just exit code zero."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
TASKS = {
    "fabric-server": ":fabric:runGameTest",
    "forge-server": ":forge:runGameTestServer",
    "fabric-integrated": ":fabric:runIntegratedProbe",
    "forge-integrated": ":forge:runClient",
}
REQUIRED = {"joined", "movement", "mine_primitive", "pickup", "damage", "removed",
            "rejoin_inventory_7", "death", "death_drops_7", "respawn_adapter",
            "no_death_duplication", "packet_discard", "result"}


def digest():
    items = []
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if not path.is_file() or any(part in {"build", ".gradle", "node_modules", "__pycache__"}
                                     for part in relative.parts):
            continue
        items.append((str(relative), hashlib.sha256(path.read_bytes()).hexdigest()))
    return hashlib.sha256(json.dumps(items).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=TASKS)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    output = ROOT / "build" / "evidence" / args.target
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")),
               TASKS[args.target], "--no-daemon", "--console=plain"]
    if args.offline:
        command.append("--offline")
    # Keep existing local proxy settings ephemeral; never store credentials or hosts in evidence.
    proxy = urlparse(env.get("https_proxy") or env.get("HTTPS_PROXY") or "")
    if proxy.hostname and proxy.port:
        if proxy.username or proxy.password:
            raise SystemExit("Configure authenticated proxies outside this runner; credentials are not accepted.")
        for scheme in ("http", "https"):
            command.extend([f"-D{scheme}.proxyHost={proxy.hostname}",
                            f"-D{scheme}.proxyPort={proxy.port}"])
        command.append("-Dhttp.nonProxyHosts=localhost|127.*|[::1]")
    if args.target == "forge-server":
        # A headless game test has no use for client sounds and skins.
        command.extend(["-x", ":forge:downloadAssets", "-x", ":forge:downloadMCMeta"])
    if args.target.endswith("integrated"):
        env.setdefault("LIBGL_ALWAYS_SOFTWARE", "true")
        env.setdefault("ALSOFT_DRIVERS", "null")
        loader = args.target.split("-")[0]
        run = ROOT / loader / "build" / "run" / "integrated"
        run.mkdir(parents=True, exist_ok=True)
        # Only this experiment's newly generated options; never modifies a launcher profile.
        (run / "options.txt").write_text("onboardAccessibility:false\nmaxFps:20\n"
                                       "renderDistance:2\nsimulationDistance:5\n"
                                       "fullscreen:false\ntutorialStep:none\n"
                                       "soundCategory_master:0.0\n")
    code = None
    try:
        with (output / "full.log").open("w") as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=(os.name != "nt"))
            try:
                code = process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    process.terminate()
                else:
                    import signal
                    os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=20)
                code = 124
    except OSError as error:
        raise SystemExit(str(error)) from error
    records = []
    text = (output / "full.log").read_text(errors="replace")
    for line in text.splitlines():
        if "MF_PROBE {" in line:
            records.append(json.loads(line.split("MF_PROBE ", 1)[1]))
    passed = {r["check"] for r in records if r["value"] == "PASS"}
    client_ok = not args.target.endswith("integrated") or "MF_CLIENT_RESULT PASS" in text
    server_type = "net.minecraft.client.server.IntegratedServer" if args.target.endswith("integrated") \
        else "net.minecraft.gametest.framework.GameTestServer"
    context_ok = bool(records) and all(r["loader"] == args.target.split("-")[0]
                                      and r["server_class"] == server_type for r in records)
    ok = code == 0 and REQUIRED <= passed and client_ok and context_ok
    summary = {"target": args.target, "source_tree_sha256": digest(), "exit_code": code,
               "passed": ok, "missing_assertions": sorted(REQUIRED - passed),
               "client_completion": client_ok, "context_verified": context_ok, "records": records}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({k: v for k, v in summary.items() if k != "records"}, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
