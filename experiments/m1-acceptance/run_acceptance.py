#!/usr/bin/env python3
"""Run only an isolated test client; validate both game-side and client-side evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=['fabric-integrated', 'forge-integrated', 'fabric-dedicated', 'forge-dedicated'])
    parser.add_argument('--server', help='Already prepared loopback test server, e.g. 127.0.0.1:25575')
    parser.add_argument('--timeout', type=int, default=420)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--skip-forge-build-cert-probe', action='store_true',
                        help='Skip only ForgeGradle environment preflight for this run; keep artifact/HTTP TLS validation')
    options = parser.parse_args()
    loader, kind = options.target.split('-')
    if kind == 'dedicated' and not options.server:
        parser.error('dedicated acceptance requires --server with a separate isolated test server')
    if options.server and not options.server.startswith(('127.0.0.1:', 'localhost:')):
        parser.error('only explicitly prepared loopback test servers are allowed')
    java_home = Path(os.environ.get('JAVA_HOME', ''))
    if not (java_home / 'bin/java').is_file():
        parser.error('set JAVA_HOME to an existing JDK 21; this script does not install software')
    production = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not production.is_file():
        parser.error('first build the production project with checkAll')
    evidence = HERE / 'build/evidence' / f'{options.target}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    command = [str(ROOT / 'experiments/player-control/gradlew'), '-p', str(HERE),
               ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient',
               '--no-daemon', '--console=plain']
    if options.offline:
        command.append('--offline')
    if options.skip_forge_build_cert_probe:
        command.append('-Dnet.minecraftforge.gradle.check.certs=false')
    if options.server:
        command.append('-PacceptanceServer=' + options.server)
    environment = dict(os.environ)
    environment['LIBGL_ALWAYS_SOFTWARE'] = 'true'
    environment['__GLX_VENDOR_LIBRARY_NAME'] = 'mesa'
    log_path = evidence / 'full.log'
    started = time.monotonic()
    timed_out = False
    with log_path.open('wb') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            exit_code = process.wait(timeout=options.timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            timed_out = True
            # This process group was created for this isolated run only.
            os.killpg(process.pid, signal.SIGTERM)
            try:
                exit_code = process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                exit_code = process.wait()
    content = log_path.read_text(errors='replace')
    server_class = ('net.minecraft.client.server.IntegratedServer' if kind == 'integrated'
                    else 'net.minecraft.server.dedicated.DedicatedServer')
    required_checks = ['client_command_created_companion', 'client_command_dismissed',
                       'summon_inventory', 'summon_health', 'death_drops_exactly_four',
                       'death_clears_inventory', 'keep_inventory_retained', 'keep_inventory_has_no_drops']
    if kind == 'integrated':
        required_checks += ['world_reopen_identity', 'world_reopen_inventory', 'world_reopen_health']
    missing = [name for name in required_checks if f'{server_class} {name} PASS' not in content]
    client_result = ('INTEGRATED_LIFECYCLE_REOPEN_PASS' if kind == 'integrated'
                     else 'DEDICATED_LIFECYCLE_PASS')
    passed = (exit_code == 0 and not timed_out and not missing
              and f'MF_ACCEPTANCE_CLIENT PASS {client_result}' in content
              and 'MF_ACCEPTANCE_CLIENT FAIL' not in content
              and f'{server_class} result FAIL' not in content)
    summary = {'target': options.target, 'passed': passed, 'exit_code': exit_code,
               'timed_out': timed_out, 'seconds': round(time.monotonic() - started, 2),
               'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
               'production_jar_sha256': digest(production), 'log_sha256': digest(log_path),
               'expected_server_class': server_class, 'missing_checks': missing,
               'client_completion': f'MF_ACCEPTANCE_CLIENT PASS {client_result}' in content}
    (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'summary': str(evidence / 'summary.json'),
                      'exit_code': exit_code, 'missing_checks': missing}))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(run())
