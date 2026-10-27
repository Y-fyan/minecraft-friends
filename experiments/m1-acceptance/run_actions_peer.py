#!/usr/bin/env python3
"""Two real M2 clients only; parent must prepare/start the isolated DedicatedServer first."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ROLES = {'a': 'MF_M2_A', 'b': 'MF_M2_B'}
CHECKS = ('ready_uuid_and_fresh_dedicated_map', 'two_owned_companions_created_by_packets',
          'both_follow_physical_displacement', 'b_stop_isolated_a_keeps_moving',
          'a_stop_isolated_b_keeps_moving', 'extra_owner_stop_rejected_without_cross_cancel',
          'both_stopped_still_40_ticks', 'result')
SERVER = re.compile(r'MF_M2_PEER_SERVER (?:net\.minecraft\.server\.dedicated\.DedicatedServer|net\.minecraft\.class_3176) '
                    r'([a-z0-9_]+) (PASS|FAIL)(?: ([A-Za-z0-9_-]+))?')
CLIENT = re.compile(r'MF_M2_PEER_CLIENT (?:(READY|PASS) role=([ab]) username=(MF_M2_[AB])'
                    r'|FAIL role=([a-z]+) code=([A-Za-z0-9_-]+)|SENT role=([ab]))')
IDENTITY = re.compile(r'MF_M2_PEER_IDENTITY role=([ab]) owner=([0-9a-f-]{36}) companion=([0-9a-f-]{36})')
ANSI = re.compile(r'\x1b\[[0-9;]*m')
CHAT = re.compile(r'\[CHAT\]|(?:\[Not Secure\]\s*)?<[^>]+>\s')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def marker(raw, pattern, server_console=False):
    text = ANSI.sub('', raw.strip())
    if server_console:text=re.sub(r'^>\s*','',text)
    found = pattern.search(text)
    if found is None or found.end() != len(text):
        return None
    prefix = text[:found.start()]
    if prefix and ('[STDOUT]:' not in prefix or '[CHAT]' in prefix):
        return None
    return found


def source_stamp():
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard',
                                     'experiments/m1-acceptance'], cwd=ROOT).split(b'\0')
    h = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        h.update(name)
        h.update((ROOT / os.fsdecode(name)).read_bytes())
    return (subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(), h.hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=('fabric-dedicated', 'forge-dedicated'))
    parser.add_argument('--server', required=True, help='explicit 127.0.0.1:port only')
    parser.add_argument('--server-log', required=True, type=Path)
    parser.add_argument('--server-mod', required=True, type=Path)
    parser.add_argument('--server-helper', required=True, type=Path)
    parser.add_argument('--timeout', type=int, default=900)
    options = parser.parse_args()
    address = re.fullmatch(r'127\.0\.0\.1:([1-9][0-9]{0,4})', options.server)
    if not address or int(address[1]) > 65535 or not 60 <= options.timeout <= 1800:
        parser.error('explicit loopback port and timeout 60..1800 required')
    loader = options.target.split('-')[0]
    java_home = Path(os.environ.get('JAVA_HOME', '/tmp/codex-mf-jdk21-213'))
    if not (java_home / 'bin/java').is_file():
        parser.error('existing JDK21 required; runner installs nothing')
    version = subprocess.check_output([str(java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'version "21\.', version):
        parser.error('JDK21 required')
    artifact = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    for path in (artifact, options.server_log, options.server_mod, options.server_helper):
        if not path.is_file():
            parser.error(f'missing prepared input: {path}')
    if options.server_log.stat().st_size > 16 * 1024 * 1024:
        parser.error('server log exceeds bounded evidence size')
    for part in ('ServerAcceptanceDispatch.java', 'client/ClientAcceptanceDispatch.java'):
        text = (HERE / 'shared/src/main/java/io/github/yfyan/minecraftfriends/acceptance' / part).read_text()
        if 'ActionsPeer' not in text:
            parser.error('parent must wire ACTIONS scope=peer dispatch before launching')
    build_text = (HERE / loader / 'build.gradle').read_text()
    if 'acceptanceActionsScope' not in build_text:
        parser.error('parent actions Gradle flags are required')
    with zipfile.ZipFile(artifact) as archive:
        if 'io/github/yfyan/minecraftfriends/action/ActionRuntime.class' not in archive.namelist():
            parser.error('M2 production artifact required')
    with zipfile.ZipFile(options.server_helper) as archive:
        if 'io/github/yfyan/minecraftfriends/acceptance/ActionsPeerScenario.class' not in archive.namelist():
            parser.error('installed server helper lacks peer scenario')
    mod_hash, helper_hash = sha(artifact), sha(options.server_helper)
    if sha(options.server_mod) != mod_hash:
        parser.error('installed server mod differs from formal artifact')
    before = options.server_log.read_bytes()
    stamp = source_stamp()
    evidence = HERE / 'build/evidence' / f'{options.target}-actions-peer-{time.time_ns()}'
    evidence.mkdir(parents=True)
    run_id = evidence.name
    env = dict(os.environ, JAVA_HOME=str(java_home), LIBGL_ALWAYS_SOFTWARE='true',
               __GLX_VENDOR_LIBRARY_NAME='mesa', ALSOFT_DRIVERS='null')
    clients, threads, launches = {}, [], []
    events = {r: {'ready': False, 'pass': False, 'failed': False, 'done': False} for r in ROLES}
    lock = threading.Lock()
    reason = None
    started = time.monotonic()

    def consume(role, process):
        with (evidence / f'{role}-markers.log').open('w') as out:
            for raw in process.stdout:
                match = marker(raw.decode('utf-8', errors='replace'), CLIENT)
                if match is None:
                    continue  # Never persist client chat, tokens or unfiltered console output.
                kind, seen_role, username, failed_role, failure, sent_role = match.groups()
                out.write(match[0] + '\n'); out.flush()
                with lock:
                    if failed_role or seen_role and (seen_role != role or username != ROLES[role]) or sent_role and sent_role != role:
                        events[role]['failed'] = True
                    elif kind:
                        events[role]['ready' if kind == 'READY' else 'pass'] = True
        with lock:
            events[role]['done'] = True

    def unchanged():
        return (source_stamp() == stamp and sha(artifact) == mod_hash
                and sha(options.server_mod) == mod_hash and sha(options.server_helper) == helper_hash)

    try:
        for role, username in ROLES.items():
            cache = evidence / 'project-cache' / role
            run_dir = HERE / loader / 'build/peer' / run_id / role / 'run/client'
            (run_dir / 'config').mkdir(parents=True)
            (run_dir / 'config/minecraft-friends.properties').write_text(
                'enabled=true\ndiagnostics.enabled=true\nmodel.provider=mock\n')
            (run_dir / 'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\n'
                'skipMultiplayerWarning:true\ntutorialStep:none\nfullscreen:false\nmaxFps:30\n'
                'simulationDistance:5\nrenderDistance:4\nsoundCategory_master:0.0\n')
            command = [str(ROOT / 'experiments/player-control/gradlew'), '-p', str(HERE),
                       ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient',
                       '--offline', '--no-daemon', '--console=plain', '--project-cache-dir', str(cache),
                       '-Dnet.minecraftforge.gradle.check.certs=false', '-PacceptanceActions=true',
                       '-PacceptanceActionsScope=peer', f'-PacceptanceRunId={run_id}',
                       f'-PacceptanceInstance={role}', f'-PacceptanceUsername={username}',
                       f'-PacceptanceServer={options.server}']
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            clients[role] = process
            launches.append(dict(role=role, username=username, pid=process.pid, command=command,
                                 run_directory=str(run_dir), build_directory=str(run_dir.parent.parent),
                                 project_cache=str(cache)))
            thread = threading.Thread(target=consume, args=(role, process), daemon=True)
            thread.start(); threads.append(thread)
        (evidence / 'startup.json').write_text(json.dumps(dict(server_managed=False, server=options.server,
            source_commit=stamp[0], helper_sources_sha256=stamp[1], formal_mod_sha256=mod_hash,
            installed_mod=str(options.server_mod.resolve()), installed_helper=str(options.server_helper.resolve()),
            installed_helper_sha256=helper_hash, server_log_offset=len(before),
            server_log_prefix_sha256=hashlib.sha256(before).hexdigest(), client_launches=launches), indent=2)+'\n')
        while time.monotonic() - started < options.timeout:
            with lock:
                if any(x['failed'] for x in events.values()):
                    reason = 'client_failure'; break
                complete = all(x['ready'] and x['pass'] for x in events.values())
            if complete and all(p.poll() is not None for p in clients.values()):
                break
            if any(p.poll() is not None and events[r]['done'] and not events[r]['pass'] for r, p in clients.items()):
                reason = 'client_exited_before_pass'; break
            if not unchanged():
                reason = 'inputs_changed'; break
            time.sleep(1)
        else:
            reason = 'timeout'
    except (OSError, ValueError, KeyboardInterrupt) as error:
        reason = type(error).__name__
    finally:
        # Only groups created here, never the caller's existing DedicatedServer or other processes.
        for process in clients.values():
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM); process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=5)
                except ProcessLookupError:
                    process.wait(timeout=5)
        for thread in threads:
            thread.join(timeout=3)
    server_ok = False
    checks, missing, identities = {}, list(CHECKS), {}
    tail_hash = None
    try:
        if options.server_log.stat().st_size > 16 * 1024 * 1024:
            raise ValueError('server_log_limit')
        after = options.server_log.read_bytes()
        if not after.startswith(before):
            raise ValueError('server_log_replaced')
        tail = after[len(before):]
        tail_hash = hashlib.sha256(tail).hexdigest()
        text = tail.decode('utf-8', errors='replace')
        if CHAT.search(text):
            raise ValueError('unexpected_chat_not_retained')
        (evidence / 'server-tail.log').write_bytes(tail)  # Exact raw append; no chat retained.
        for line in text.splitlines():
            identity = marker(line, IDENTITY,True)
            if identity:
                identities[identity[1]] = {'owner': identity[2], 'companion': identity[3]}
            match = marker(line, SERVER,True)
            if match:
                check, outcome, detail = match.groups()
                checks[check] = outcome
                if outcome == 'FAIL':
                    reason = 'server_assertion_failed'
        missing = [x for x in CHECKS if checks.get(x) != 'PASS']
        server_ok = (not missing and 'FAIL' not in checks.values() and set(identities) == {'a', 'b'}
                     and len({v for pair in identities.values() for v in pair.values()}) == 4)
    except (OSError, ValueError) as error:
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
    try:
        stable = unchanged()
    except (OSError, subprocess.SubprocessError):
        stable = False
    codes = {r: p.returncode for r, p in clients.items()}
    passed = (reason is None and server_ok and stable and codes == {'a': 0, 'b': 0}
              and all(v['ready'] and v['pass'] and not v['failed'] for v in events.values()))
    summary = dict(passed=passed, reason=reason, target=options.target, client_markers=events,
                   exit_codes=codes, server_checks=checks, identities=identities, missing_checks=missing, inputs_unchanged=stable,
                   installed_mod_sha256=mod_hash, installed_helper_sha256=helper_hash,
                   server_log_tail_sha256=tail_hash, elapsed_seconds=round(time.monotonic()-started,2),
                   server_managed=False, real_model_called=False, m2_complete=False)
    (evidence / 'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(dict(passed=passed, summary=str(evidence / 'summary.json'))))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
