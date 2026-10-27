#!/usr/bin/env python3
"""Run M2 against a fresh loopback world using an existing, EULA-accepted installation.

Installs no software. Never starts the supplied template's world or changes its files.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('loader', choices=('fabric', 'forge'))
    parser.add_argument('--template', required=True, type=Path)
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--scope', choices=('full', 'respawn', 'failures', 'door', 'unreachable', 'peer'), default='full')
    parser.add_argument('--natural', action='store_true')
    args = parser.parse_args()
    template = args.template.resolve()
    if not 1024 <= args.port <= 65535 or 'eula=true' not in (template / 'eula.txt').read_text().splitlines():
        parser.error('existing accepted EULA and unprivileged port required')
    run = ROOT / 'work/runtime' / f'{args.loader}-final-{args.scope}-{time.time_ns()}'
    run.mkdir(parents=True)
    for name in ('libraries', 'versions'):
        if (template / name).is_dir():
            (run / name).symlink_to(template / name, target_is_directory=True)
    for name in ('server.jar', 'fabric-server-launch.jar', 'fabric-server-launcher.properties', 'forge-1.21.1-52.1.20-shim.jar', 'eula.txt'):
        if (template / name).is_file():
            shutil.copy2(template / name, run / name)
    (run / 'mods').mkdir()
    (run / 'config').mkdir()
    artifact = ROOT / f'build/artifacts/minecraft-friends-{args.loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    helper = HERE / args.loader / f'build/libs/{args.loader}-1.0.0.jar'
    shutil.copy2(artifact, run / 'mods/minecraft-friends.jar')
    shutil.copy2(helper, run / 'mods/mf-acceptance.jar')
    if args.loader == 'fabric':
        shutil.copy2(template / 'mods/fabric-api.jar', run / 'mods/fabric-api.jar')
    (run / 'config/minecraft-friends.properties').write_text('enabled=true\ndiagnostics.enabled=true\nmodel.provider=mock\n')
    properties = (template / 'server.properties').read_text().splitlines()
    replacements = {'server-ip': '127.0.0.1', 'server-port': str(args.port), 'online-mode': 'false',
                    'level-name': 'mf-m2-acceptance-' + run.name, 'spawn-protection': '0'}
    properties = [line for line in properties if line.split('=', 1)[0] not in replacements]
    (run / 'server.properties').write_text('\n'.join(properties + [k + '=' + v for k, v in replacements.items()]) + '\n')
    java = Path(os.environ.get('JAVA_HOME', '/tmp/codex-mf-jdk21-213')) / 'bin/java'
    launch = [str(java), '-Xmx2G', '-Dmf.acceptance=true', '-Dmf.acceptance.actions=true',
              '-Dmf.acceptance.actions.scope=' + args.scope, '-Dmf.acceptance.loader=' + args.loader]
    if args.natural:
        launch += ['-Dmf.acceptance.actions.natural=true']
    launch += ['-jar', 'fabric-server-launch.jar' if args.loader == 'fabric' else 'forge-1.21.1-52.1.20-shim.jar', 'nogui']
    log = run / 'server.log'
    child = None
    result = {'run_directory': str(run), 'server_exit_code': None, 'runner_exit_code': None, 'world_saved': False}
    try:
        with log.open('wb') as out:
            child = subprocess.Popen(launch, cwd=run, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT)
            for _ in range(180):
                if child.poll() is not None:
                    raise RuntimeError('test server exited before ready')
                if 'Done (' in log.read_text(errors='replace'):
                    break
                time.sleep(1)
            else:
                raise RuntimeError('test server startup timeout')
            runner = 'run_actions_peer.py' if args.scope == 'peer' else 'run_actions.py'
            command = ['python3', str(HERE / runner), args.loader + '-dedicated', '--server', f'127.0.0.1:{args.port}',
                       '--server-log', str(log), '--server-mod', str(run / 'mods/minecraft-friends.jar'),
                       '--server-helper', str(run / 'mods/mf-acceptance.jar')]
            if args.scope != 'peer':
                command += ['--scope', args.scope]
            if args.natural and args.scope != 'peer':
                command += ['--natural']
            result['runner_exit_code'] = subprocess.run(command, cwd=ROOT).returncode
    finally:
        if child is not None:
            if child.poll() is None:
                child.stdin.write(b'stop\n')
                child.stdin.flush()
            try:
                result['server_exit_code'] = child.wait(timeout=90)
            except subprocess.TimeoutExpired:
                # Preserve the live process and its PID; never kill a server while saving.
                result['server_pid_requires_attention'] = child.pid
            result['world_saved'] = 'All dimensions are saved' in log.read_text(errors='replace')
        (run / 'shutdown.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    return 0 if result['runner_exit_code'] == 0 and result['server_exit_code'] == 0 and result['world_saved'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
