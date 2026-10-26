#!/usr/bin/env python3
"""Run one real-client negative acceptance scenario with an isolated no-call HTTP probe."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = Path(__file__).resolve().parent
ACCEPTANCE = HERE.parent
ROOT = ACCEPTANCE.parent.parent
JDK_FALLBACK = Path('/tmp/codex-mf-jdk21-213')
OWNER_NAME = 'MF_NegativeOwner'
WORLD_RE = re.compile(r'mf-negative-[0-9a-f-]{36}')
PREPARE_RE = re.compile(
    r'^MF_NEGATIVE_SERVER PREPARE PASS scenario=(DISABLED|CORRUPT) '
    r'world=(mf-negative-[0-9a-f-]{36}) role=(integrated|other) '
    r'serverClass=([A-Za-z0-9_.$]+) targetInitiallyAbsent=true dataSha256=(none|[0-9a-f]{64})$')
MARKER_RE = re.compile(
    r'^MF_NEGATIVE_SERVER (?:'
    r'CHECK [a-z0-9_]+ PASS|'
    r'ACK PASS op=[A-Z_]+ sourceUuidVerified=true nonceVerified=true|'
    r'RESULT PASS scenario=(?:DISABLED|CORRUPT)|'
    r'STOP PASS scenario=(?:DISABLED|CORRUPT) dataSha256=(?:none|[0-9a-f]{64})|'
    r'FAIL reason=[a-z0-9_]+|'
    r'STOP FAIL scenario=(?:DISABLED|CORRUPT|UNKNOWN) reason=[a-z0-9_]+)'
    r'$|^MF_NEGATIVE_CLIENT (?:PASS (?:DISABLED|CORRUPT)|FAIL reason=[a-z0-9_]+)$')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_output(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def helper_digest():
    names = git_output('ls-files', '-z', '--cached', '--others', '--exclude-standard', 'experiments/m1-acceptance')
    result = hashlib.sha256()
    for name in sorted(set(filter(None, names.split(b'\0')))):
        result.update(name)
        result.update((ROOT / os.fsdecode(name)).read_bytes())
    return result.hexdigest()


class NoCallProbe:
    """Loopback-only endpoint counting TCP attempts without retaining headers or bodies."""
    def __init__(self):
        self._lock = threading.Lock()
        self.connections = 0
        state = self

        class Handler(BaseHTTPRequestHandler):
            def setup(self):
                with state._lock:
                    state.connections += 1
                super().setup()
                self.connection.settimeout(2)

            def log_message(self, *args):
                pass

            def _reject(self):
                self.send_response(503)
                self.send_header('Content-Length', '0')
                self.send_header('Connection', 'close')
                self.end_headers()

            do_GET = _reject
            do_POST = _reject
            do_PUT = _reject
            do_PATCH = _reject
            do_DELETE = _reject
            do_OPTIONS = _reject
            do_HEAD = _reject

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.server.handle_error = lambda *args: None
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.started = False

    @property
    def endpoint(self):
        return f'http://127.0.0.1:{self.server.server_port}/v1/chat/completions'

    def start(self):
        self.thread.start()
        self.started = True
        return self

    def close(self):
        if self.started and self.thread.is_alive():
            self.server.shutdown()
            self.thread.join(timeout=3)
        self.server.server_close()

    def snapshot(self):
        with self._lock:
            return {'kind': 'loopback no-call probe; not a model',
                    'tcp_connections': self.connections,
                    'request_data_stored': False}


def configuration(scenario, endpoint):
    enabled = 'false' if scenario == 'DISABLED' else 'true'
    return (f'enabled={enabled}\n'
            'diagnostics.enabled=true\n'
            'model.provider=chat-completions\n'
            'model.timeout-seconds=2\n'
            f'model.endpoint={endpoint}\n'
            'model.name=negative-acceptance-no-call\n'
            'model.api-key-env=\n'
            'model.allow-unauthenticated-local=true\n'
            'model.retries=0\n'
            'model.max-context-messages=8\n'
            'model.max-context-characters=1024\n')


def capture_markers(command, environment, timeout, marker_path):
    started = time.monotonic()
    deadline = started + timeout
    timed_out = False
    exit_code = -1
    process = subprocess.Popen(command, cwd=ROOT, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True, bufsize=0)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    pending = bytearray()

    def accept_line(raw, output):
        line = raw.decode('utf-8', errors='replace').rstrip('\r')
        if PREPARE_RE.fullmatch(line) or MARKER_RE.fullmatch(line):
            output.write(line + '\n')
            output.flush()

    try:
        with marker_path.open('w', encoding='utf-8') as output:
            while selector.get_map() or process.poll() is None:
                if time.monotonic() >= deadline:
                    timed_out = True
                    break
                for key, _ in selector.select(timeout=0.25):
                    chunk = os.read(key.fd, 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    pending.extend(chunk)
                    while b'\n' in pending:
                        raw, _, remainder = pending.partition(b'\n')
                        pending[:] = remainder
                        accept_line(raw, output)
            if pending:
                accept_line(bytes(pending), output)
    except KeyboardInterrupt:
        timed_out = True
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        exit_code = process.wait()
        selector.close()
        if process.stdout:
            process.stdout.close()
    return exit_code, timed_out, round(time.monotonic() - started, 2)


def parse_options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=('fabric-disabled', 'forge-disabled',
                                           'fabric-corrupt', 'forge-corrupt'))
    parser.add_argument('--timeout', type=int, default=600)
    options = parser.parse_args()
    if not 30 <= options.timeout <= 1800:
        parser.error('timeout must be between 30 and 1800 seconds')
    return options


def run():
    options = parse_options()
    loader, scenario = options.target.split('-', 1)
    scenario = scenario.upper()

    java_home = Path(os.environ.get('JAVA_HOME', JDK_FALLBACK))
    java = java_home / 'bin/java'
    javac = java_home / 'bin/javac'
    if not java.is_file() or not javac.is_file():
        print(json.dumps({'passed': False, 'error': 'the configured portable JDK 21 is unavailable'}, ensure_ascii=False))
        return 2
    java_version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=10)
    if java_version.returncode != 0 or not re.search(r'version "21(?:\.|[+-])',
                                                    java_version.stdout + java_version.stderr):
        print(json.dumps({'passed': False, 'error': 'JAVA_HOME must point to Java 21'}, ensure_ascii=False))
        return 2

    production = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not production.is_file():
        print(json.dumps({'passed': False, 'error': f'missing prebuilt {loader} production JAR'}, ensure_ascii=False))
        return 2

    source_commit = git_output('rev-parse', 'HEAD').decode().strip()
    source_diff = git_output('diff', 'HEAD', '--')
    helper_hash = helper_digest()
    production_hash = digest(production)
    unique = f'{options.target}-{time.time_ns()}'
    client_dir = HERE / 'build' / 'run' / unique
    evidence = HERE / 'build' / 'evidence' / unique
    client_dir.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=False)
    config_path = client_dir / 'config' / 'minecraft-friends.properties'
    config_path.parent.mkdir(parents=True, exist_ok=True)
    if config_path.exists():
        print(json.dumps({'passed': False, 'error': 'new isolated client config unexpectedly exists'}, ensure_ascii=False))
        return 2

    probe = NoCallProbe()
    config_created = False
    fixture_path = evidence / 'no-call-probe.json'
    startup = {
        'target': options.target,
        'source_commit': source_commit,
        'source_diff_sha256': hashlib.sha256(source_diff).hexdigest(),
        'source_diff_present': bool(source_diff),
        'helper_sources_sha256': helper_hash,
        'production_jar': production.name,
        'production_jar_sha256': production_hash,
        'java_home': str(java_home),
        'username': OWNER_NAME,
        'client_directory': str(client_dir),
        'config_sha256': hashlib.sha256(configuration(scenario, probe.endpoint).encode()).hexdigest(),
        'probe_endpoint': probe.endpoint,
        'probe_summary': str(fixture_path),
        'request_data_stored': False,
    }
    environment = dict(os.environ)
    environment['JAVA_HOME'] = str(java_home)
    environment['PATH'] = str(java_home / 'bin') + os.pathsep + environment.get('PATH', '')
    environment.update(LIBGL_ALWAYS_SOFTWARE='true', __GLX_VENDOR_LIBRARY_NAME='mesa', ALSOFT_DRIVERS='null')
    gradle = ROOT / 'experiments/player-control/gradlew'
    command = [str(gradle), '-p', str(ACCEPTANCE),
               ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient',
               '--offline', '--no-daemon', '--console=plain',
               '-PacceptanceNegative=true',
               f'-PacceptanceNegativeScenario={scenario}',
               f'-PacceptanceNegativeRunDir={client_dir}',
               f'-PacceptanceUsername={OWNER_NAME}']
    if loader == 'forge':
        command.append('-Dnet.minecraftforge.gradle.check.certs=false')

    exit_code = -1
    timed_out = False
    elapsed = 0.0
    launch_error = False
    try:
        probe.start()
        (evidence / 'startup.json').write_text(json.dumps(startup, ensure_ascii=False, indent=2) + '\n',
                                               encoding='utf-8')
        with config_path.open('xb') as config_file:
            config_created = True
            config_file.write(configuration(scenario, probe.endpoint).encode('utf-8'))
        if digest(config_path) != startup['config_sha256']:
            raise OSError('isolated config hash mismatch')
        exit_code, timed_out, elapsed = capture_markers(command, environment, options.timeout,
                                                        evidence / 'markers.log')
    except (OSError, subprocess.SubprocessError):
        launch_error = True
    finally:
        try:
            probe.close()
        finally:
            if config_created:
                config_path.unlink(missing_ok=True)
            fixture_path.write_text(json.dumps(probe.snapshot(), ensure_ascii=False, indent=2) + '\n',
                                    encoding='utf-8')

    marker_text = (evidence / 'markers.log').read_text(encoding='utf-8') if (evidence / 'markers.log').exists() else ''
    lines = marker_text.splitlines()
    prepare_lines = [line for line in lines if line.startswith('MF_NEGATIVE_SERVER PREPARE ')]
    prepare_matches = [PREPARE_RE.fullmatch(line) for line in prepare_lines]
    prepare = next((match for match in prepare_matches if match), None)
    world_name = prepare.group(2) if prepare else None
    server_class = prepare.group(4) if prepare else None
    sample_hash = prepare.group(5) if prepare else None
    required_checks = {
        'DISABLED': ('integrated_server_instance', 'world_path_isolated', 'world_name_safe', 'target_was_absent',
                     'owner_login', 'status_chat_disabled', 'provider_not_initialized',
                     'status_no_companion', 'status_global_disabled', 'companion_data_available',
                     'status_disabled_reason', 'player_remained_connected', 'no_companion_after_command',
                     'disabled_state_preserved'),
        'CORRUPT': ('integrated_server_instance', 'world_path_isolated', 'world_name_safe', 'target_was_absent',
                    'corrupt_data_sample_written', 'owner_login', 'status_chat_disabled',
                    'provider_not_initialized', 'status_no_companion', 'status_global_enabled_for_data_failure',
                    'status_data_unavailable', 'status_disabled_reason', 'corrupt_data_unchanged_after_start',
                    'player_remained_connected', 'no_companion_after_command',
                    'corrupt_data_safe_state_preserved', 'corrupt_data_unchanged_during_run',
                    'corrupt_data_unchanged_after_stop'),
    }[scenario]
    missing = [name for name in required_checks
               if f'MF_NEGATIVE_SERVER CHECK {name} PASS' not in marker_text]
    operation_names = (('CREATE_DISABLED', 'CHAT_DISABLED') if scenario == 'DISABLED'
                       else ('CREATE_CORRUPT', 'STATUS_CORRUPT', 'CHAT_CORRUPT'))
    observed_ack_ops = re.findall(r'MF_NEGATIVE_SERVER ACK PASS op=([A-Z_]+) sourceUuidVerified=true nonceVerified=true',
                                  marker_text)
    if observed_ack_ops != list(operation_names):
        missing.append('all_real_client_nonce_acks')
    expected_result = f'MF_NEGATIVE_SERVER RESULT PASS scenario={scenario}'
    expected_client = f'MF_NEGATIVE_CLIENT PASS {scenario}'
    expected_stop = f'MF_NEGATIVE_SERVER STOP PASS scenario={scenario}'
    no_call_probe = probe.snapshot()
    fixture_path.write_text(json.dumps(no_call_probe, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    no_model_calls = no_call_probe['tcp_connections'] == 0

    corrupt_file_unchanged = None
    data_file = None
    corrupt_file_hash_after_exit = None
    if scenario == 'CORRUPT' and prepare and sample_hash and sample_hash != 'none' and world_name:
        try:
            world_dir = (client_dir / 'saves' / world_name).resolve()
            saves_root = (client_dir / 'saves').resolve()
            data_file = world_dir / 'data' / 'minecraft_friends_companions.dat'
            if data_file.is_file():
                corrupt_file_hash_after_exit = digest(data_file)
            corrupt_file_unchanged = (world_dir.is_relative_to(saves_root)
                                      and corrupt_file_hash_after_exit == sample_hash)
        except (OSError, RuntimeError):
            corrupt_file_unchanged = False
    if scenario == 'CORRUPT' and corrupt_file_unchanged is not True:
        missing.append('corrupt_file_hash_unchanged_after_client_exit')
    if not prepare or prepare.group(1) != scenario or prepare.group(3) != 'integrated':
        missing.append('real_integrated_server_prepare_marker')
    if not world_name or not WORLD_RE.fullmatch(world_name):
        missing.append('safe_negative_world_name')
    if expected_result not in marker_text:
        missing.append('server_completion_marker')
    if expected_client not in marker_text:
        missing.append('complete_real_client_marker')
    if expected_stop not in marker_text:
        missing.append('post_stop_marker')
    if len(prepare_lines) != 1 or marker_text.count(expected_result) != 1 \
            or marker_text.count(expected_client) != 1 or marker_text.count(expected_stop) != 1:
        missing.append('unique_prepare_and_completion_markers')
    if 'MF_NEGATIVE_SERVER FAIL ' in marker_text or 'MF_NEGATIVE_CLIENT FAIL ' in marker_text:
        missing.append('no_runtime_fail_marker')
    if not no_model_calls:
        missing.append('zero_loopback_fixture_connections')

    try:
        source_unchanged = git_output('rev-parse', 'HEAD').decode().strip() == source_commit
        source_unchanged &= git_output('diff', 'HEAD', '--') == source_diff
        helper_unchanged = helper_digest() == helper_hash
        artifact_unchanged = production.is_file() and digest(production) == production_hash
    except (OSError, subprocess.CalledProcessError):
        source_unchanged = helper_unchanged = artifact_unchanged = False
    config_restored = not config_path.exists()
    passed = (exit_code == 0 and not timed_out and not launch_error and not missing
              and source_unchanged and helper_unchanged and artifact_unchanged and config_restored)
    summary = dict(startup, passed=passed, exit_code=exit_code, timed_out=timed_out,
                   launch_error=launch_error, seconds=elapsed, server_class=server_class,
                   actual_server_role='IntegratedServer' if prepare and prepare.group(3) == 'integrated' else None,
                   required_checks=list(required_checks), missing_checks=missing,
                   client_completion=expected_client in marker_text,
                   fixture_connections=no_call_probe['tcp_connections'],
                   no_model_http_calls=no_model_calls,
                   corrupt_file_sha256_after_exit=corrupt_file_hash_after_exit,
                   corrupt_file_unchanged_after_exit=corrupt_file_unchanged,
                   config_restored=config_restored, source_unchanged=source_unchanged,
                   helper_unchanged=helper_unchanged, production_jar_unchanged=artifact_unchanged,
                   m1_complete=False)
    (evidence / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n',
                                           encoding='utf-8')
    print(json.dumps({'passed': passed, 'summary': str(evidence / 'summary.json'),
                      'missing_checks': missing, 'no_model_http_calls': no_model_calls}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(run())
