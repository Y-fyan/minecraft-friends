#!/usr/bin/env python3
"""Run the real-client event/memory acceptance flow with a loopback protocol simulation."""
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

from events_fixture import EventsFixture
from real_model_observer import RealObserver, RealState, ObserverHandler, ObserverServer, endpoint_is_allowed

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DEFAULT_JDK = Path('/tmp/codex-mf-jdk21-213')
REQUIRED_COUNTS = ('FIRST_MEETING', 'INJURED', 'REJOIN', 'DELETE_PROBE',
                   'REAL_CHAT', 'TIMEOUT', 'DISCONNECT')
BASE_REQUIRED_CHECKS = (
    'chat_runtime_enabled', 'loopback_provider_configured', 'proactive_default_enabled',
    'first_companion_identity', 'first_meeting_live_companion', 'first_meeting_client_reply_ack',
    'first_injury_health_reduced', 'injured_client_reply_ack', 'cooldown_injury_health_reduced',
    'second_real_hurt_without_death', 'injury_cooldown_window_observed', 'proactive_disabled',
    'disabled_injury_health_reduced', 'disabled_real_hurt_without_death',
    'disabled_injury_quiet_window', 'proactive_restored_before_rejoin',
    'integrated_world_reopen_requested', 'rejoin_same_world_and_companion', 'rejoin_client_reply_ack',
    'memory_list_contains_target_and_verified_events', 'memory_list_parsed_real_uuids',
    'memory_delete_generation_advanced', 'memory_delete_count_decreased',
    'memory_list_delete_absent_events_retained', 'memory_list_delete_uuid_absent_keep_uuid_stable',
    'memory_delete_context_probe_ack', 'memory_delete_context_probe_count_stable',
)
REAL_MODEL_CHECKS = (
    'real_chat_before_memory_stable', 'real_chat_reply_ack', 'real_chat_after_memory_stable',
    'real_model_timeout_feedback_ack', 'real_model_disconnect_feedback_ack',
    'fault_recovery_memory_stable',
)
REQUIRED_CHECKS = BASE_REQUIRED_CHECKS + REAL_MODEL_CHECKS
EVENT_LOG = re.compile(
    r'^(?:MF_EVENTS_SERVER [A-Za-z0-9_.$]+ [a-z0-9_]+ (?:PASS|FAIL(?: [a-z0-9_]+)?)|'
    r'MF_EVENTS_SERVER [A-Za-z0-9_.$]+ result (?:EVENTS_PASS|FAIL [a-z0-9_]+)|'
    r'MF_EVENTS_CLIENT (?:PASS EVENTS_PASS|FAIL [a-z0-9_]+))$')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def helper_digest():
    names = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', 'experiments/m1-acceptance'],
        cwd=ROOT).split(b'\0')
    result = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        result.update(name)
        result.update((ROOT / os.fsdecode(name)).read_bytes())
    return result.hexdigest()


def fixture_snapshot(path):
    if not path.is_file() or path.stat().st_size > 65_536:
        raise ValueError('fixture summary missing or exceeds bound')
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or value.get('kind') != '协议模拟，非真实模型':
        raise ValueError('invalid event fixture summary')
    if value.get('request_body_stored') is not False:
        raise ValueError('fixture must not store request bodies')
    for field in ('counts', 'failures'):
        counters = value.get(field)
        if not isinstance(counters, dict) or len(counters) > 16 or any(
                not isinstance(key, str) or type(count) is not int or count < 0
                for key, count in counters.items()):
            raise ValueError('invalid event fixture counters')
    return value


def observer_snapshot(path):
    if not path.is_file() or path.stat().st_size > 65_536:
        raise ValueError('observer summary missing or exceeds bound')
    value = json.loads(path.read_text(encoding='utf-8'))
    if value.get('kind') != 'm1-real-model-acceptance-observer':
        raise ValueError('invalid real-model observer summary')
    if value.get('request_body_stored') is not False or value.get('response_body_stored') is not False:
        raise ValueError('observer must not store model bodies')
    if value.get('observer_is_not_a_model') is not True:
        raise ValueError('observer identity missing')
    for field in ('counts', 'semantic_passes', 'semantic_failures', 'semantic_failure_reasons', 'transport', 'failures'):
        counters = value.get(field)
        if not isinstance(counters, dict) or any(
                not isinstance(key, str) or type(count) is not int or count < 0
                for key, count in counters.items()):
            raise ValueError('invalid observer counters')
    return value


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=('fabric-integrated', 'forge-integrated'))
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--skip-forge-build-cert-probe', action='store_true',
                        help='Skip ForgeGradle environment preflight for this invocation only')
    parser.add_argument('--username', default='MF_EventsOwner',
                        help='Offline test identity; must not name a real account')
    parser.add_argument('--real', action='store_true',
                        help='Require an explicit user-authorized real model through the loopback observer')
    parser.add_argument('--endpoint', help='explicit real-service endpoint; required by --real')
    parser.add_argument('--model', help='explicit real-service model name; required by --real')
    parser.add_argument('--api-key-env', help='only this environment variable may contain the credential')
    parser.add_argument('--allow-unauthenticated-local', action='store_true')
    parser.add_argument('--model-timeout-seconds', type=int, default=6)
    options = parser.parse_args()
    loader = options.target.split('-', 1)[0]
    if not re.fullmatch(r'[A-Za-z0-9_]{1,16}', options.username):
        parser.error('username must be a short offline test identity')
    if not 30 <= options.timeout <= 1_800:
        parser.error('timeout must be 30..1800 seconds')
    if not (1 <= options.model_timeout_seconds <= 300):
        parser.error('model timeout must be 1..300 seconds')
    if options.real:
        if not options.endpoint or not endpoint_is_allowed(options.endpoint):
            parser.error('--endpoint must be an explicit HTTPS or loopback HTTP URL')
        if not options.model or not 1 <= len(options.model) <= 128:
            parser.error('--model must contain 1..128 characters')
        if not options.api_key_env or not re.fullmatch(r'[!-~]{1,128}', options.api_key_env):
            parser.error('--api-key-env must be a visible ASCII environment-variable name')
    elif any((options.endpoint, options.model, options.api_key_env,
              options.allow_unauthenticated_local)):
        parser.error('real-service options require --real')

    java_home = Path(os.environ.get('JAVA_HOME', DEFAULT_JDK))
    if not (java_home / 'bin/java').is_file() or not (java_home / 'bin/javac').is_file():
        parser.error('portable JDK 21 is required; no software is installed')
    production = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not production.is_file():
        parser.error('build the production artifact first; this runner never builds production')

    artifact_hash = digest(production)
    source_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    source_diff = subprocess.check_output(['git', 'diff', 'HEAD', '--'], cwd=ROOT)
    helper_hash = helper_digest()
    client_run = HERE / loader / 'build/run/client'
    client_run.mkdir(parents=True, exist_ok=True)
    evidence = HERE / 'build/evidence' / f'{options.target}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    config_path = client_run / 'config/minecraft-friends.properties'
    options_path = client_run / 'options.txt'
    previous_config = None
    config_written = False
    previous_options = None
    options_written = False
    summary_path = evidence / 'protocol-summary.json'
    if options.real:
        api_key = os.environ.get(options.api_key_env)
        if api_key is None or not api_key:
            if options.allow_unauthenticated_local:
                api_key = None
            else:
                parser.error(f'environment variable {options.api_key_env} is absent')
        state = RealState(summary_path)
        observer = RealObserver(state, options.endpoint, options.model, api_key,
                                options.model_timeout_seconds)
        ObserverHandler.observer = observer
        model_endpoint_server = ObserverServer(('127.0.0.1', 0), ObserverHandler)
        threading.Thread(target=model_endpoint_server.serve_forever, daemon=True).start()
        observer_server = model_endpoint_server
        observer_state = state
        model_endpoint = f'http://127.0.0.1:{observer_server.server_port}/m1-real'
        model_name = options.model
        state.record('proxy_started')
    else:
        fixture = EventsFixture(summary_path).start()
        fixture.state.record('fixture_started')
        observer_server = None
        observer_state = None
        model_endpoint = fixture.endpoint
        model_name = 'acceptance-protocol-simulation'
    if options.real:
        baseline = observer_snapshot(summary_path)
    else:
        baseline = fixture_snapshot(summary_path)
    filtered_log = evidence / 'checks.log'
    startup = {
        'target': options.target,
        'source_commit': source_commit,
        'source_diff_sha256': hashlib.sha256(source_diff).hexdigest(),
        'helper_sources_sha256': helper_hash,
        'production_jar': str(production),
        'production_jar_sha256': artifact_hash,
        'protocol_simulation_not_real_model': not options.real,
        'real_model_under_test': options.real,
        'model_endpoint': model_endpoint,
        'request_body_stored': False,
        'log_policy': 'only fixed MF_EVENTS assertion lines are retained',
    }
    (evidence / 'startup.json').write_text(json.dumps(startup, ensure_ascii=False, indent=2) + '\n',
                                           encoding='utf-8')

    command = [str(ROOT / 'experiments/player-control/gradlew'), '-p', str(HERE),
               ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient',
               '--no-daemon', '--console=plain', '-PacceptanceEvents=true',
               '-PacceptanceUsername=' + options.username]
    if options.real:
        command.append('-PacceptanceReal=true')
    if options.offline:
        command.append('--offline')
    if options.skip_forge_build_cert_probe:
        command.append('-Dnet.minecraftforge.gradle.check.certs=false')

    environment = dict(os.environ)
    if options.real and options.api_key_env:
        environment.pop(options.api_key_env, None)
    environment.update(JAVA_HOME=str(java_home), LIBGL_ALWAYS_SOFTWARE='true',
                       __GLX_VENDOR_LIBRARY_NAME='mesa', ALSOFT_DRIVERS='null',
                       NO_COLOR='1', CI='true')
    exit_code = -1
    timed_out = False
    launch_error = False
    start = time.monotonic()
    try:
        config_path.parent.mkdir(parents=True, exist_ok=True)
        if options_path.exists():
            if options_path.stat().st_size > 65_536:
                raise ValueError('isolated client options exceed bound')
            previous_options = options_path.read_bytes()
        options_path.write_text(
            'onboardAccessibility:false\nmaxFps:30\nrenderDistance:4\n'
            'simulationDistance:5\nfullscreen:false\ntutorialStep:none\n'
            'soundCategory_master:0.0\n', encoding='utf-8')
        options_written = True
        if config_path.exists():
            if config_path.stat().st_size > 65_536:
                raise ValueError('isolated client config exceeds bound')
            previous_config = config_path.read_bytes()
        if options.real:
            configuration = ('enabled=true\ndiagnostics.enabled=true\n'
                             'model.provider=chat-completions\n'
                             f'model.endpoint={model_endpoint}\nmodel.name={model_name}\n'
                             'model.api-key-env=\nmodel.allow-unauthenticated-local=true\n'
                             'model.retries=0\n'
                             f'model.timeout-seconds={options.model_timeout_seconds}\n'
                             'chat.prefix=@\n')
        else:
            configuration = fixture.configuration()
        config_path.write_text(configuration, encoding='utf-8')
        config_written = True

        with filtered_log.open('w', encoding='utf-8') as retained:
            lock = threading.Lock()
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, errors='replace',
                                       bufsize=1, start_new_session=True)

            def retain_assertions():
                assert process.stdout is not None
                for line in process.stdout:
                    candidate = line.rstrip('\r\n')
                    marker = candidate.find('MF_EVENTS_')
                    if marker >= 0:
                        candidate = candidate[marker:]
                    if EVENT_LOG.fullmatch(candidate):
                        with lock:
                            retained.write(candidate + '\n')
                            retained.flush()

            reader = threading.Thread(target=retain_assertions, daemon=True)
            reader.start()
            try:
                while process.poll() is None:
                    if not production.is_file() or digest(production) != artifact_hash:
                        timed_out = True
                        break
                    if time.monotonic() - start > options.timeout:
                        timed_out = True
                        break
                    time.sleep(0.5)
                if timed_out:
                    raise subprocess.TimeoutExpired(command, options.timeout)
                exit_code = process.wait()
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                timed_out = True
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                exit_code = process.returncode
                reader.join(timeout=5)
    except (OSError, ValueError):
        launch_error = True
    finally:
        if options.real and observer_server is not None:
            observer_server.shutdown()
            observer_server.server_close()
        elif not options.real:
            fixture.close()
        if config_written:
            if previous_config is None:
                config_path.unlink(missing_ok=True)
            else:
                config_path.write_bytes(previous_config)
        if options_written:
            if previous_options is None:
                options_path.unlink(missing_ok=True)
            else:
                options_path.write_bytes(previous_options)

    evidence_log = filtered_log.read_text(encoding='utf-8') if filtered_log.exists() else ''
    server_class = ('net.minecraft.client.server.IntegratedServer'
                    if options.target.endswith('integrated') else '')
    required_checks = BASE_REQUIRED_CHECKS + (REAL_MODEL_CHECKS if options.real else ())
    missing = [tag for tag in required_checks
               if f'MF_EVENTS_SERVER {server_class} {tag} PASS' not in evidence_log]
    counts_ok = False
    deltas = {}
    failures_added = True
    fixture_state_matches_summary = False
    semantic_ok = False
    controlled_ok = False
    try:
        if options.real:
            result = observer_snapshot(summary_path)
            live_state = observer_state.snapshot()
            fixture_state_matches_summary = (
                result.get('counts') == live_state.get('counts')
                and result.get('failures') == live_state.get('failures')
                and result.get('request_body_stored') is False
                and result.get('response_body_stored') is False)
            deltas = {tag: result['counts'].get(tag, 0) - baseline['counts'].get(tag, 0)
                      for tag in REQUIRED_COUNTS}
            counts_ok = all(value == 1 for value in deltas.values())
            failures_added = sum(result['failures'].values()) == sum(baseline['failures'].values())
            semantic_passes = result.get('semantic_passes', {})
            semantic_failures = result.get('semantic_failures', {})
            semantic_ok = (
                all(semantic_passes.get(tag) == 1
                    for tag in (*EVENTS, 'DELETE_PROBE', 'REAL_CHAT'))
                and all(semantic_failures.get(tag) == 0
                        for tag in (*EVENTS, 'DELETE_PROBE', 'REAL_CHAT', 'TIMEOUT', 'DISCONNECT')))
            transport = result.get('transport', {})
            controlled_ok = (transport.get('controlled_timeout') == 1
                             and transport.get('controlled_disconnect') == 1
                             and result.get('failures', {}).get('network_error', 0) == 0
                             and result.get('failures', {}).get('upstream_http_error', 0) == 0)
        else:
            result = fixture_snapshot(summary_path)
            memory_snapshot = fixture.state.snapshot()
            fixture_state_matches_summary = (
                result.get('counts') == memory_snapshot.get('counts')
                and result.get('failures') == memory_snapshot.get('failures')
                and result.get('request_body_stored') is False)
            deltas = {tag: result['counts'].get(tag, 0) - baseline['counts'].get(tag, 0)
                      for tag in REQUIRED_COUNTS if tag not in ('REAL_CHAT', 'TIMEOUT', 'DISCONNECT')}
            counts_ok = all(value == 1 for value in deltas.values())
            failures_added = sum(result['failures'].values()) == sum(baseline['failures'].values())
            semantic_ok = True
            controlled_ok = True
    except (OSError, ValueError, TypeError, KeyError):
        result = {'kind': '协议模拟，非真实模型', 'counts': {}, 'failures': {},
                  'request_body_stored': False}

    artifact_unchanged = production.is_file() and digest(production) == artifact_hash
    source_unchanged = (subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
                        == source_commit
                        and subprocess.check_output(['git', 'diff', 'HEAD', '--'], cwd=ROOT) == source_diff)
    helper_unchanged = helper_digest() == helper_hash
    client_pass = 'MF_EVENTS_CLIENT PASS EVENTS_PASS' in evidence_log
    server_pass = f'MF_EVENTS_SERVER {server_class} result EVENTS_PASS' in evidence_log
    passed = (exit_code == 0 and not timed_out and not launch_error and not missing and counts_ok
              and failures_added and fixture_state_matches_summary and semantic_ok and controlled_ok
              and artifact_unchanged and source_unchanged and helper_unchanged
              and client_pass and server_pass and 'MF_EVENTS_CLIENT FAIL ' not in evidence_log
              and ' result FAIL ' not in evidence_log)
    summary = dict(startup, passed=passed, exit_code=exit_code, timed_out=timed_out,
                   launch_error=launch_error, seconds=round(time.monotonic() - start, 2),
                   artifact_unchanged=artifact_unchanged, source_unchanged=source_unchanged,
                   helper_unchanged=helper_unchanged, checks_sha256=digest(filtered_log)
                   if filtered_log.exists() else None, missing_checks=missing,
                   protocol_count_delta=deltas, protocol_counts_passed=counts_ok,
                   protocol_failures_unchanged=failures_added,
                   fixture_state_matches_summary=fixture_state_matches_summary,
                   real_model_semantic_checks_passed=semantic_ok,
                   real_model_fault_transports_observed=controlled_ok,
                   client_completion=client_pass,
                   server_completion=server_pass,
                   m1_events_complete=passed,
                   m1_real_model_integrated_complete=passed if options.real else False,
                   not_covered=([] if options.real else ['真实模型服务'])
                               + ['DedicatedServer重启后恢复', '完整人工游戏体验'])
    (evidence / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n',
                                           encoding='utf-8')
    print(json.dumps({'passed': passed, 'summary': str(evidence / 'summary.json'),
                      'missing_checks': missing, 'protocol_count_delta': deltas,
                      'protocol_failures_unchanged': failures_added,
                      'real_model_semantic_checks_passed': semantic_ok,
                      'real_model_fault_transports_observed': controlled_ok}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(run())
