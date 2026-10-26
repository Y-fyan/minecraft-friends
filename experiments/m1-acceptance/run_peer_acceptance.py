#!/usr/bin/env python3
"""Run two offline dev clients against an already-running loopback DedicatedServer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import threading
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ROLE_DATA = (('a', 'MFAcceptA'), ('b', 'MFAcceptB'))
EXPECTED_CALLS = ('PRIVATE_A', 'PRIVATE_B', 'SHARED_A', 'SHARED_B', 'SHARED_AFTER_LEAVE')
SERVER_CHECKS = (
    'two_real_offline_clients',
    'two_independent_companions_created_by_clients',
    'proactive_disabled_and_private_addresses_set',
    'distinct_private_facts_saved',
    'private_command_reply_ack_by_owner_a',
    'private_ordinary_prefix_reply_ack_by_owner_b',
    'shared_uuid_parsed_from_real_create_message',
    'uninvited_join_rejected_for_real_client_uuid',
    'uninvited_shared_send_rejected',
    'invite_uuid_parsed_by_invited_client',
    'invitee_explicitly_joined_by_client_command',
    'both_shared_requests_replied_to_both_real_clients',
    'left_client_saw_no_echo_or_reply_during_observation',
    'client_b_dismissed_only_its_companion',
    'client_b_memory_clear_left_client_a_state_unchanged',
)
SERVER_MARKER = re.compile(
    r'^MF_PEER_ACCEPTANCE_SERVER net\.minecraft\.server\.dedicated\.DedicatedServer '
    r'([A-Za-z0-9_-]+) (PASS|FAIL)(?: ([A-Za-z0-9_-]+))?$'
)
CLIENT_MARKER = re.compile(
    r'^MF_PEER_CLIENT (READY|PASS) role=([ab]) username=(MFAccept[AB])$'
    r'|^MF_PEER_CLIENT FAIL role=([ab]) code=([A-Za-z0-9_-]+)$'
    r'|^MF_PEER_CLIENT ASSERT role=([ab]) check=([A-Za-z0-9_-]{1,64})$'
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def helper_digest():
    names = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', 'experiments/m1-acceptance'],
        cwd=ROOT,
    ).split(b'\0')
    value = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        value.update(name)
        value.update((ROOT / os.fsdecode(name)).read_bytes())
    return value.hexdigest()


def source_snapshot():
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(['git', 'diff', 'HEAD', '--'], cwd=ROOT)
    return commit, diff


def read_fixture(path):
    if not path.is_file() or path.stat().st_size > 65536:
        raise ValueError('fixture summary missing or too large')
    data = json.loads(path.read_text(encoding='utf-8'))
    if (not isinstance(data, dict) or data.get('kind') != '协议模拟，非真实模型'
            or data.get('request_body_stored') is not False):
        raise ValueError('fixture summary identity is invalid')
    endpoint = data.get('endpoint', '')
    match = re.fullmatch(r'http://127\.0\.0\.1:([1-9][0-9]{0,4})/v1/chat/completions', endpoint)
    if not match or int(match.group(1)) > 65535:
        raise ValueError('peer fixture must expose its explicit loopback endpoint')
    for field in ('counts', 'failures'):
        values = data.get(field)
        if not isinstance(values, dict) or len(values) > 64 or any(
                not isinstance(k, str) or not isinstance(v, int) or v < 0 for k, v in values.items()):
            raise ValueError('fixture counters are invalid')
    return data


def loopback_server(value):
    match = re.fullmatch(r'127\.0\.0\.1:([1-9][0-9]{0,4})', value or '')
    return bool(match and int(match.group(1)) <= 65535)


def atomic_json(path, value):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=('fabric-dedicated', 'forge-dedicated'))
    parser.add_argument('--server', required=True, help='already-running loopback server, e.g. 127.0.0.1:25575')
    parser.add_argument('--fixture-summary', required=True, type=Path,
                        help='summary.json produced by peer_protocol_stub.py --fixture')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--allow-gradle-network', action='store_true',
                        help='allow Gradle dependency resolution; default is --offline')
    parser.add_argument('--skip-forge-build-cert-probe', action='store_true',
                        help='skip only ForgeGradle environment preflight')
    options = parser.parse_args()
    loader = options.target.split('-', 1)[0]
    if not loopback_server(options.server):
        parser.error('--server must be an explicit 127.0.0.1:port')
    if not 60 <= options.timeout <= 2400:
        parser.error('--timeout must be 60..2400 seconds')
    java_home = Path(os.environ.get('JAVA_HOME', ''))
    java = java_home / 'bin/java'
    if not java.is_file():
        parser.error('set JAVA_HOME to the existing JDK 21; this runner installs no software')
    try:
        version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    except (OSError, subprocess.CalledProcessError):
        parser.error('JAVA_HOME Java could not be inspected')
    if not re.search(r'openjdk version "21\.|java version "21\.', version):
        parser.error('peer acceptance requires the configured Java 21 runtime')
    try:
        fixture_before = read_fixture(options.fixture_summary.resolve())
    except (OSError, ValueError, json.JSONDecodeError):
        parser.error('--fixture-summary is not a live peer protocol fixture summary')
    production = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not production.is_file():
        parser.error('build the formal production JAR first; runner does not build or start the server')
    try:
        with socket.create_connection(('127.0.0.1', int(options.server.rsplit(':', 1)[1])), timeout=3):
            pass
    except OSError:
        parser.error('the already-prepared loopback DedicatedServer is not accepting connections')
    fixture_port = int(fixture_before['endpoint'].split(':')[2].split('/')[0])
    try:
        with socket.create_connection(('127.0.0.1', fixture_port), timeout=3):
            pass
    except OSError:
        parser.error('the peer protocol fixture endpoint is not listening')

    evidence = HERE / 'build/evidence' / f'{options.target}-peer-{time.time_ns()}'
    evidence.mkdir(parents=True)
    run_id = evidence.name
    summary_path = evidence / 'summary.json'
    startup_path = evidence / 'startup.json'
    baseline_counts = fixture_before['counts']
    baseline_failures = fixture_before['failures']
    production_hash = digest(production)
    commit, diff = source_snapshot()
    helper_hash = helper_digest()
    cache_root = evidence / 'project-cache'
    clients = {}
    events = {role: [] for role, _ in ROLE_DATA}
    event_lock = threading.Lock()
    log_paths = {role: evidence / f'{role}-markers.log' for role, _ in ROLE_DATA}
    env = dict(os.environ)
    env['JAVA_HOME'] = str(java_home)
    env.update(LIBGL_ALWAYS_SOFTWARE='true', __GLX_VENDOR_LIBRARY_NAME='mesa', ALSOFT_DRIVERS='null')

    startup = {
        'kind': '真实开发客户端连接隔离 DedicatedServer；HTTP 服务为协议模拟，非真实模型',
        'target': options.target,
        'server_address': options.server,
        'server_process_managed': False,
        'fixture_endpoint': fixture_before['endpoint'],
        'fixture_summary': str(options.fixture_summary.resolve()),
        'offline_clients': [{'role': role, 'username': username} for role, username in ROLE_DATA],
        'java_home': str(java_home),
        'formal_jar': str(production),
        'formal_jar_sha256': production_hash,
        'source_commit': commit,
        'source_diff_sha256': hashlib.sha256(diff).hexdigest(),
        'helper_sources_sha256': helper_hash,
        'client_launches': [],
        'logs_store_chat_or_credentials': False,
    }
    atomic_json(startup_path, startup)
    started = time.monotonic()
    deadline = started + options.timeout
    timed_out = False
    launch_error = None
    termination_errors = []
    server_checks = {}
    client_ready = set()
    client_pass = set()
    client_failures = {}
    reader_done = set()

    def consume_output(role, process):
        path = log_paths[role]
        try:
            with path.open('w', encoding='utf-8') as marker_log:
                for raw in process.stdout:
                    line = raw.decode('utf-8', errors='replace').strip()
                    server_match = SERVER_MARKER.fullmatch(line)
                    if server_match:
                        check, result, reason = server_match.groups()
                        safe = f'MF_PEER_ACCEPTANCE_SERVER {check} {result}' + (f' {reason}' if reason else '')
                        marker_log.write(safe + '\n')
                        marker_log.flush()
                        if result == 'FAIL':
                            with event_lock:
                                events[role].append(('server_fail', reason or check))
                        continue
                    client_match = CLIENT_MARKER.fullmatch(line)
                    if not client_match:
                        continue
                    marker_log.write(line + '\n')
                    marker_log.flush()
                    ready_kind, ready_role, username, fail_role, fail_code, assert_role, assert_check = client_match.groups()
                    with event_lock:
                        if fail_role:
                            client_failures[fail_role] = fail_code
                            events[role].append(('client_fail', fail_code))
                        elif assert_role:
                            if assert_role == role:
                                server_checks.setdefault(assert_check, set()).add(assert_role)
                        elif ready_kind == 'READY' and ready_role == role and username == dict(ROLE_DATA)[role]:
                            client_ready.add(role)
                        elif ready_kind == 'PASS' and ready_role == role and username == dict(ROLE_DATA)[role]:
                            client_pass.add(role)
        finally:
            with event_lock:
                reader_done.add(role)

    def build_command(role):
        # Resolve from the fixed role table without accepting user-provided identities.
        username = dict(ROLE_DATA)[role]
        task = ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient'
        command = [
            str(ROOT / 'experiments/player-control/gradlew'), '-p', str(HERE), task,
            '--no-daemon', '--console=plain', '--project-cache-dir', str(cache_root / role),
            f'-PacceptanceRunId={run_id}', f'-PacceptanceInstance={role}',
            '-PacceptancePeer=true', f'-PacceptancePeerRole={role}',
            f'-PacceptanceServer={options.server}',
        ]
        if not options.allow_gradle_network:
            command.append('--offline')
        if options.skip_forge_build_cert_probe:
            command.append('-Dnet.minecraftforge.gradle.check.certs=false')
        return command, username

    def launch(role):
        command, username = build_command(role)
        run_directory = HERE / loader / 'build/peer' / run_id / role / 'run/client'
        run_directory.mkdir(parents=True, exist_ok=True)
        options_file = run_directory / 'options.txt'
        options_file.write_text(
            'onboardAccessibility:false\nmaxFps:30\nrenderDistance:6\nsimulationDistance:5\n'
            'fullscreen:false\ntutorialStep:none\nskipMultiplayerWarning:true\nsoundCategory_master:0.0\n',
            encoding='utf-8',
        )
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, start_new_session=True, bufsize=0)
        clients[role] = process
        launch_record = {
            'role': role,
            'username': username,
            'launcher_pid': process.pid,
            'process_group_pid': process.pid,
            'command': command,
            'working_directory': str(ROOT),
            'gradle_project_cache': str(cache_root / role),
            'isolated_build_directory': str(HERE / loader / 'build/peer' / run_id / role),
            'isolated_run_directory': str(run_directory),
            'simulation_distance': 5,
        }
        startup['client_launches'].append(launch_record)
        atomic_json(startup_path, startup)
        threading.Thread(target=consume_output, args=(role, process), daemon=True).start()

    def check_unchanged():
        current_commit, current_diff = source_snapshot()
        return (current_commit == commit and current_diff == diff and helper_digest() == helper_hash
                and production.is_file() and digest(production) == production_hash)

    def wait_until(predicate):
        nonlocal timed_out, launch_error
        while time.monotonic() < deadline:
            if not check_unchanged():
                launch_error = 'source, helper or production JAR changed during the run'
                return False
            with event_lock:
                if any(any(kind in ('server_fail', 'client_fail') for kind, _ in events[r]) for r in clients):
                    launch_error = 'peer client or server acceptance reported failure'
                    return False
            for role in clients:
                if (clients[role].poll() is not None and role in reader_done and role not in client_pass):
                    launch_error = f'client launcher {role} exited before its PASS marker'
                    return False
            if predicate():
                return True
            time.sleep(0.25)
        timed_out = True
        return False

    try:
        launch('a')
        if wait_until(lambda: 'a' in client_ready):
            launch('b')
            if wait_until(lambda: 'b' in client_ready):
                wait_until(lambda: client_pass == {'a', 'b'}
                           and all(process.poll() is not None for process in clients.values()))
    except (OSError, ValueError) as error:
        launch_error = type(error).__name__
    finally:
        for role, process in clients.items():
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                except OSError as error:
                    termination_errors.append(f'{role}:{type(error).__name__}')
        for process in clients.values():
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass

    exit_codes = {role: process.returncode for role, process in clients.items()}
    fixture_after = None
    fixture_delta = {}
    fixture_failures_delta = {}
    try:
        fixture_after = read_fixture(options.fixture_summary.resolve())
        fixture_delta = {label: fixture_after['counts'].get(label, 0) - baseline_counts.get(label, 0)
                         for label in EXPECTED_CALLS}
        fixture_delta['SETUP_GREETING'] = (fixture_after['counts'].get('SETUP_GREETING', 0)
                                           - baseline_counts.get('SETUP_GREETING', 0))
        fixture_failures_delta = {key: value - baseline_failures.get(key, 0)
                                  for key, value in fixture_after['failures'].items()
                                  if value - baseline_failures.get(key, 0) != 0}
    except (OSError, ValueError, json.JSONDecodeError):
        fixture_delta = {}
        fixture_failures_delta = {'fixture_summary_unreadable': 1}

    missing_checks = [name for name in SERVER_CHECKS if server_checks.get(name, set()) != {'a', 'b'}]
    expected_fixture = all(fixture_delta.get(name) == 1 for name in EXPECTED_CALLS)
    setup_greetings_bounded = 0 <= fixture_delta.get('SETUP_GREETING', -1) <= 2
    no_unmarked = not fixture_failures_delta.get('unmarked_request')
    no_failures = not fixture_failures_delta
    sources_unchanged = check_unchanged()
    passed = (
        len(clients) == 2 and not timed_out and launch_error is None
        and exit_codes == {'a': 0, 'b': 0}
        and client_ready == {'a', 'b'} and client_pass == {'a', 'b'} and not client_failures
        and not missing_checks and expected_fixture and setup_greetings_bounded and no_unmarked and no_failures
        and sources_unchanged and not termination_errors
    )
    summary = {
        'passed': passed,
        'target': options.target,
        'protocol_simulation_not_real_model': True,
        'server_process_managed': False,
        'client_processes': {role: {'pid': clients[role].pid, 'exit_code': exit_codes.get(role),
                                    'ready': role in client_ready, 'pass_marker': role in client_pass}
                            for role, _ in ROLE_DATA if role in clients},
        'server_checks_seen_by_clients': {name: sorted(roles) for name, roles in sorted(server_checks.items())},
        'missing_server_checks': missing_checks,
        'fixture_call_delta': fixture_delta,
        'fixture_failures_delta': fixture_failures_delta,
        'fixture_isolation_assertions_passed': expected_fixture and no_failures and no_unmarked,
        'setup_greeting_count_delta': fixture_delta.get('SETUP_GREETING'),
        'exit_codes': exit_codes,
        'timed_out': timed_out,
        'launch_error': launch_error,
        'termination_errors': termination_errors,
        'sources_and_jar_unchanged': sources_unchanged,
        'elapsed_seconds': round(time.monotonic() - started, 2),
        'limitations': ['真实模型服务未验证', '仅执行已选加载器的 DedicatedServer 双客户端流程',
                        '不验证真实账号、DedicatedServer 重启恢复或主动事件冷却'],
    }
    atomic_json(summary_path, summary)
    print(json.dumps({'passed': passed, 'summary': str(summary_path),
                      'missing_server_checks': missing_checks,
                      'fixture_isolation_assertions_passed': summary['fixture_isolation_assertions_passed']},
                     ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
