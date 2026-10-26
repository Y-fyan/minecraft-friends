#!/usr/bin/env python3
"""Run an isolated real client against official JARs; HTTP fixture is 协议模拟，非真实模型。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

from protocol_stub import ProtocolFixture

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CHAT_LABELS = ('PRIVATE', 'PREFIX', 'REJOIN', 'AFTER_CLEAR')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def helper_digest():
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard',
                                     'experiments/m1-acceptance'], cwd=ROOT).split(b'\0')
    result = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        result.update(name)
        result.update((ROOT / os.fsdecode(name)).read_bytes())
    return result.hexdigest()


def fixture_snapshot(path):
    if not path.is_file() or path.stat().st_size > 65536:
        raise ValueError('fixture summary must be an existing bounded JSON file')
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict):
        raise ValueError('invalid fixture summary shape')
    if value.get('kind') != '协议模拟，非真实模型' or value.get('request_body_stored') is not False:
        raise ValueError('not an acceptance protocol fixture summary')
    for name in ('counts', 'failures'):
        counters = value.get(name)
        if not isinstance(counters, dict) or len(counters) > 64 or any(
                not isinstance(key, str) or not isinstance(count, int) or count < 0
                for key, count in counters.items()):
            raise ValueError('invalid fixture counters')
    return value


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=['fabric-integrated', 'forge-integrated', 'fabric-dedicated', 'forge-dedicated'])
    parser.add_argument('--server', help='Already prepared loopback test server, e.g. 127.0.0.1:25575')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--lifecycle-only', action='store_true', help='Preserve the original lifecycle acceptance path')
    parser.add_argument('--username', help='Offline test identity, not a real account')
    parser.add_argument('--fixture-summary', type=Path, help='Dedicated mode: summary from protocol_stub.py --fixture')
    parser.add_argument('--skip-forge-build-cert-probe', action='store_true',
                        help='Skip only ForgeGradle environment preflight, not artifact/HTTP TLS validation')
    options = parser.parse_args()
    loader, kind = options.target.split('-')
    if options.username and not re.fullmatch(r'[A-Za-z0-9_]{1,16}', options.username):
        parser.error('username must be a short offline test identity')
    if not 30 <= options.timeout <= 1800:
        parser.error('timeout must be 30..1800 seconds')
    if kind != 'dedicated' and (options.server or options.fixture_summary):
        parser.error('server/fixture-summary are dedicated-mode options')
    if kind == 'dedicated' and not options.server:
        parser.error('dedicated acceptance requires an already isolated --server')
    if options.server and not re.fullmatch(r'(127\.0\.0\.1|localhost):([1-9][0-9]{0,4})', options.server):
        parser.error('only explicit loopback test servers are allowed')
    if options.server and not 1 <= int(options.server.rsplit(':', 1)[1]) <= 65535:
        parser.error('server port is invalid')
    if not options.lifecycle_only and kind == 'dedicated' and not options.fixture_summary:
        parser.error('dedicated chat acceptance requires --fixture-summary; runner never edits server configuration')
    java_home = Path(os.environ.get('JAVA_HOME', ''))
    if not (java_home / 'bin/java').is_file():
        parser.error('set JAVA_HOME to an existing JDK 21; no software is installed')
    production = ROOT / 'build/artifacts' / f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not production.is_file():
        parser.error('first build the production project with checkAll')
    artifact_hash = digest(production)
    source_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    source_diff = subprocess.check_output(['git', 'diff', 'HEAD', '--'], cwd=ROOT)
    client_run = HERE / loader / 'build/run/client'
    client_run.mkdir(parents=True, exist_ok=True)
    options_file = client_run / 'options.txt'
    if not options_file.exists():
        options_file.write_text('onboardAccessibility:false\nmaxFps:30\n'
                               'renderDistance:4\nsimulationDistance:5\nfullscreen:false\n'
                               'tutorialStep:none\nskipMultiplayerWarning:true\nsoundCategory_master:0.0\n')
    evidence = HERE / 'build/evidence' / f'{options.target}-{time.time_ns()}'
    evidence.mkdir(parents=True)
    baseline = {'counts': {}, 'failures': {}}
    fixture = None
    config_path = client_run / 'config/minecraft-friends.properties'
    previous_config = None
    config_written = False
    protocol_summary = None
    if not options.lifecycle_only:
        if kind == 'integrated':
            fixture = ProtocolFixture(evidence / 'protocol-summary.json')
            protocol_summary = fixture.state.summary_path
        else:
            protocol_summary = options.fixture_summary.resolve()
            try:
                baseline = fixture_snapshot(protocol_summary)
            except (OSError, ValueError, json.JSONDecodeError):
                parser.error('invalid standalone fixture summary')
    command = [str(ROOT / 'experiments/player-control/gradlew'), '-p', str(HERE),
               ':fabric:runAcceptance' if loader == 'fabric' else ':forge:runClient',
               '--no-daemon', '--console=plain']
    if options.username:
        command.append('-PacceptanceUsername=' + options.username)
    if options.offline:
        command.append('--offline')
    if options.skip_forge_build_cert_probe:
        command.append('-Dnet.minecraftforge.gradle.check.certs=false')
    if options.lifecycle_only:
        command.append('-PacceptanceLifecycleOnly=true')
    if options.server:
        command.append('-PacceptanceServer=' + options.server)
    helper_hash = helper_digest()
    startup = {'helper_sources_sha256': helper_hash, 'source_commit': source_commit, 'source_dirty': bool(source_diff),
               'source_diff_sha256': hashlib.sha256(source_diff).hexdigest(),
               'source_commit_is_launch_checkout': True,
               'production_jar': str(production), 'production_jar_sha256': artifact_hash,
               'username': options.username, 'protocol_simulation_not_real_model': not options.lifecycle_only,
               'lifecycle_only': options.lifecycle_only,
               'protocol_endpoint': fixture.endpoint if fixture else None,
               'protocol_summary_path': str(protocol_summary) if protocol_summary else None}
    (evidence / 'startup.json').write_text(json.dumps(startup, indent=2) + '\n')
    environment = dict(os.environ)
    environment.update(LIBGL_ALWAYS_SOFTWARE='true', __GLX_VENDOR_LIBRARY_NAME='mesa', ALSOFT_DRIVERS='null')
    log_path = evidence / 'full.log'
    started = time.monotonic()
    timed_out = False
    artifact_unchanged = True
    exit_code = -1
    launch_error = False
    try:
        if kind == 'integrated':
            config_path.parent.mkdir(parents=True, exist_ok=True)
            if config_path.exists():
                if config_path.stat().st_size > 65536:
                    raise ValueError('isolated client config is too large')
                previous_config = config_path.read_bytes()
            configuration = (fixture.configuration() if fixture else
                             'enabled=true\ndiagnostics.enabled=true\nmodel.provider=mock\nmodel.api-key-env=\n')
            config_path.write_text(configuration, encoding='utf-8')
            config_written = True
            if fixture:
                fixture.start()
                fixture.state.record('fixture_started')
        with log_path.open('wb') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                while process.poll() is None:
                    artifact_unchanged &= production.is_file() and digest(production) == artifact_hash
                    if not artifact_unchanged or time.monotonic() - started > options.timeout:
                        timed_out = True
                        break
                    time.sleep(0.5)
                if timed_out:
                    raise subprocess.TimeoutExpired(command, options.timeout)
                exit_code = process.wait()
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                timed_out = True
            finally:
                # This process group belongs only to this invocation, including on monitor I/O failure.
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                exit_code = process.returncode
    except (OSError, ValueError):
        launch_error = True
    finally:
        if fixture and fixture.thread.is_alive():
            fixture.close()
        elif fixture:
            fixture.server.server_close()
        if config_written:
            if previous_config is None:
                config_path.unlink(missing_ok=True)
            else:
                config_path.write_bytes(previous_config)
    content = log_path.read_text(errors='replace') if log_path.exists() else ''
    server_class = ('net.minecraft.client.server.IntegratedServer' if kind == 'integrated'
                    else 'net.minecraft.server.dedicated.DedicatedServer')
    required_checks = ['client_command_created_companion', 'client_command_dismissed',
                       'summon_inventory', 'summon_health', 'death_drops_exactly_four',
                       'death_clears_inventory', 'keep_inventory_retained', 'keep_inventory_has_no_drops']
    if kind == 'integrated' or not options.lifecycle_only:
        required_checks += ['world_reopen_identity', 'world_reopen_inventory', 'world_reopen_health']
    if not options.lifecycle_only:
        required_checks += ['chat_provider_configured', 'chat_scope_identity', 'private_address_saved',
                            'private_memory_saved', 'private_chinese_reply_ack', 'prefix_chinese_reply_ack',
                            'world_reopen_chat_uuid', 'world_reopen_address', 'world_reopen_memory',
                            'rejoin_chinese_reply_ack', 'memory_clear_generation', 'memory_clear_address',
                            'memory_clear_facts', 'after_clear_chinese_reply_ack']
    missing = [name for name in required_checks if f'{server_class} {name} PASS' not in content]
    if options.lifecycle_only:
        client_result = 'INTEGRATED_LIFECYCLE_REOPEN_PASS' if kind == 'integrated' else 'DEDICATED_LIFECYCLE_PASS'
    else:
        client_result = 'INTEGRATED_M1_CHAT_MEMORY_PASS' if kind == 'integrated' else 'DEDICATED_M1_CHAT_MEMORY_PASS'
    protocol_ok = None  # lifecycle-only means not run, never protocol PASS
    protocol_delta = {}
    if protocol_summary:
        try:
            snapshot = fixture_snapshot(protocol_summary)
            protocol_delta = {label: snapshot['counts'].get(label, 0) - baseline['counts'].get(label, 0)
                              for label in CHAT_LABELS}
            new_failures = sum(snapshot['failures'].values()) - sum(baseline['failures'].values())
            protocol_ok = all(value == 1 for value in protocol_delta.values()) and new_failures == 0
        except (OSError, ValueError, KeyError, TypeError):
            protocol_ok = False
    artifact_unchanged &= production.is_file() and digest(production) == artifact_hash
    source_unchanged = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() == source_commit
    source_unchanged &= subprocess.check_output(['git', 'diff', 'HEAD', '--'], cwd=ROOT) == source_diff
    helper_unchanged = helper_digest() == helper_hash
    passed = (exit_code == 0 and not timed_out and not launch_error and not missing and artifact_unchanged
              and source_unchanged and helper_unchanged and (options.lifecycle_only or protocol_ok is True) and f'MF_ACCEPTANCE_CLIENT PASS {client_result}' in content
              and 'MF_ACCEPTANCE_CLIENT FAIL' not in content and f'{server_class} result FAIL' not in content)
    not_covered = ['真实模型', '双玩家私聊隔离与共享邀请/退出', '三类主动事件与冷却',
                   'memory list/delete 的实际客户端路径', 'DedicatedServer重启后恢复（仅客户端重连）']
    if options.lifecycle_only:
        not_covered.append('聊天与记忆全部场景（lifecycle-only）')
    summary = dict(startup, target=options.target, passed=passed, exit_code=exit_code,
                   timed_out=timed_out, launch_error=launch_error, seconds=round(time.monotonic() - started, 2),
                   artifact_unchanged=artifact_unchanged, source_unchanged=source_unchanged, helper_unchanged=helper_unchanged,
                   log_sha256=digest(log_path) if log_path.exists() else None,
                   expected_server_class=server_class, missing_checks=missing, protocol_checks_passed=protocol_ok,
                   protocol_count_delta=protocol_delta, client_completion=f'MF_ACCEPTANCE_CLIENT PASS {client_result}' in content,
                   m1_complete=False, not_covered=not_covered)
    (evidence / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'summary': str(evidence / 'summary.json'),
                      'missing_checks': missing, 'protocol_checks_passed': protocol_ok}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(run())
