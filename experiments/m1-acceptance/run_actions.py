#!/usr/bin/env python3
"""M2 smoke acceptance against an actual installed production JAR; no model credentials."""
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
CHECKS = ('isolated_survival_map', 'physical_follow_over_obstacle', 'wait_does_not_pursue',
          'stop_clears_movement', 'collection_requires_explicit_area',
          'eight_real_logs_delivered_without_duplication', 'outside_area_resource_untouched',
          'repeat_request_does_not_create_items', 'result')
MARKER = re.compile(r'MF_M2_(?:SERVER [A-Za-z0-9_.$]+ [a-z0-9_]+ (?:PASS|FAIL)|CLIENT (?:COMMAND_SENT|PASS M2_PASS|FAIL [a-z0-9_]+)|METRIC [-a-z0-9_= ]+)')
ANSI = re.compile(r'\x1b\[[0-9;]*m')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def marker(raw, server_console=False):
    text = ANSI.sub('', raw.strip())
    if server_console:text=re.sub(r'^>\s*','',text)
    i = text.find('MF_M2_')
    if i < 0:
        return None
    prefix = text[:i]
    if prefix and ('[STDOUT]:' not in prefix or '[CHAT]' in prefix):
        return None
    return text[i:] if MARKER.fullmatch(text[i:]) else None

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('target', choices=('fabric-integrated','forge-integrated','fabric-dedicated','forge-dedicated'))
    p.add_argument('--natural',action='store_true')
    p.add_argument('--scope', choices=('navigation','door','unreachable','planning','respawn','full'), default='full')
    p.add_argument('--server')
    p.add_argument('--server-log', type=Path)
    p.add_argument('--server-mod', type=Path)
    p.add_argument('--server-helper', type=Path)
    p.add_argument('--timeout',type=int,default=480)
    args=p.parse_args()
    dedicated=args.target.endswith('dedicated');loader=args.target.split('-')[0]
    if not 60<=args.timeout<=1200:p.error('timeout must be 60..1200')
    if dedicated and (not args.server or not re.fullmatch(r'(?:127\.0\.0\.1|localhost):[0-9]{1,5}',args.server) or not args.server_log or not args.server_mod or not args.server_helper):
        p.error('dedicated requires explicit loopback server, server-log and installed server-mod')
    if not dedicated and (args.server or args.server_log or args.server_mod or args.server_helper):p.error('server options only for dedicated')
    artifact=ROOT/'build/artifacts'/f'minecraft-friends-{loader}-1.21.1-0.1.0-SNAPSHOT.jar'
    if not artifact.is_file():p.error('build production JAR first')
    with zipfile.ZipFile(artifact) as z:
        if not any(n.endswith('/action/ActionRuntime.class') for n in z.namelist()):
            p.error('production JAR has no ActionRuntime; refuse to run M1 artifact as M2')
    proof=sha(artifact)
    before_server=args.server_log.read_bytes() if dedicated else b''
    if dedicated and sha(args.server_mod)!=proof:p.error('installed server mod differs from root artifact')
    helper_hash=sha(args.server_helper) if dedicated else None
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    diff=subprocess.check_output(['git','diff','HEAD','--'],cwd=ROOT)
    d=HERE/'build/evidence'/f'{args.target}-actions-{time.time_ns()}';d.mkdir(parents=True)
    run=HERE/loader/'build/run/client';(run/'config').mkdir(parents=True,exist_ok=True)
    files={run/'config/minecraft-friends.properties':b'enabled=true\ndiagnostics.enabled=true\nmodel.provider=mock\n',
           run/'options.txt':b'onboardAccessibility:false\npauseOnLostFocus:false\nsimulationDistance:5\nrenderDistance:4\nmaxFps:30\nsoundCategory_master:0.0\n'}
    fixture=None
    if args.scope=='planning':
        if dedicated:p.error('planning fixture currently requires integrated target')
        from actions_fixture import ActionsFixture
        fixture=ActionsFixture(d/'model-fixture.json').start()
        files[run/'config/minecraft-friends.properties']=fixture.configuration.encode()
    prior={f:f.read_bytes() if f.exists() else None for f in files}
    command=[str(ROOT/'experiments/player-control/gradlew'),'-p',str(HERE),':fabric:runAcceptance' if loader=='fabric' else ':forge:runClient',
             '--no-daemon','--console=plain','--offline','-Dnet.minecraftforge.gradle.check.certs=false','-PacceptanceActions=true','-PacceptanceUsername=MF_M2Owner']
    command+=['-PacceptanceActionsScope='+args.scope]
    if args.natural:command+=['-PacceptanceActionsNatural=true']
    if dedicated:command+=['-PacceptanceServer='+args.server]
    environment=dict(os.environ);environment.update(JAVA_HOME='/tmp/codex-mf-jdk21-213',LIBGL_ALWAYS_SOFTWARE='true',__GLX_VENDOR_LIBRARY_NAME='mesa',ALSOFT_DRIVERS='null')
    started=time.monotonic();timed_out=False;code=None;process=None;lines=[]
    try:
        for f,data in files.items():f.write_bytes(data)
        process=subprocess.Popen(command,cwd=ROOT,env=environment,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
        def read():
            with (d/'checks.log').open('w') as retained:
                for raw in process.stdout:
                    item=marker(raw)
                    if item:lines.append(item);retained.write(item+'\n');retained.flush()
        thread=threading.Thread(target=read,daemon=True);thread.start()
        try:code=process.wait(timeout=args.timeout)
        except (subprocess.TimeoutExpired,KeyboardInterrupt):
            timed_out=True;os.killpg(process.pid,signal.SIGTERM)
            try:code=process.wait(timeout=20)
            except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);code=process.wait()
        thread.join(timeout=5)
    finally:
        if fixture:fixture.close()
        for f,data in prior.items():
            if data is None:f.unlink(missing_ok=True)
            else:f.write_bytes(data)
    text='\n'.join(lines)
    scenario_checks=(CHECKS+('respawn_restored_live_instance',) if args.scope=='respawn' else CHECKS if args.scope=='full' else CHECKS[:4]+('result',) if args.scope=='navigation' else CHECKS[:4]+('wooden_door_opened_and_crossed','result') if args.scope=='door'
                     else ('isolated_survival_map','structured_model_plan_moves_companion','invalid_model_action_rejected','planning_inflight_before_stop','late_model_plan_cannot_resume_after_stop','result') if args.scope=='planning'
                     else ('isolated_survival_map','unreachable_fails_within_budget','failed_path_leaves_bot_still','result'))
    required=scenario_checks+('natural_commands_enabled' if args.natural else 'explicit_commands_enabled',)+('dedicated_server_instance' if dedicated else 'integrated_server_instance',)
    missing=[tag for tag in required if not re.search(r'MF_M2_SERVER \S+ '+re.escape(tag)+r' PASS',text)]
    unchanged=artifact.is_file() and sha(artifact)==proof
    source_unchanged=(subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()==commit and subprocess.check_output(['git','diff','HEAD','--'],cwd=ROOT)==diff)
    server_ok=True;server_hash=None
    if dedicated:
        after=args.server_log.read_bytes();tail=after[len(before_server):]
        server_ok=after.startswith(before_server) and b'MF_M2_SERVER ' in tail and b'result PASS' in tail and sha(args.server_mod)==proof and sha(args.server_helper)==helper_hash
        server_hash=hashlib.sha256(tail).hexdigest()
        server_lines=[x for raw in tail.decode('utf-8',errors='replace').splitlines() if (x:=marker(raw,True))]
        (d/'server-checks.log').write_text('\n'.join(server_lines)+'\n')
        (d/'server-tail.log').write_bytes(tail)
        text+='\n'+'\n'.join(server_lines)
        missing=[tag for tag in required if not re.search(r'MF_M2_SERVER \S+ '+re.escape(tag)+r' PASS',text)]
    fixture_ok=(fixture is None or fixture.failures==0 and all(fixture.counts.get(k)==1 for k in ('follow','bad','late','late_write_attempt')))
    passed=(fixture_ok and code==0 and not timed_out and not missing and unchanged and source_unchanged and server_ok and 'MF_M2_CLIENT PASS M2_PASS' in text and ' FAIL' not in text)
    result=dict(target=args.target,natural_commands=args.natural,scope=args.scope,model_fixture_verified=fixture_ok,passed=passed,source_commit=commit,source_diff_sha256=hashlib.sha256(diff).hexdigest(),production_jar_sha256=proof,artifact_unchanged=unchanged,source_unchanged=source_unchanged,exit_code=code,timed_out=timed_out,missing_checks=missing,server_log_verified=server_ok,server_log_tail_sha256=server_hash,server_log_start_offset=len(before_server),server_log_prefix_sha256=hashlib.sha256(before_server).hexdigest(),installed_helper_sha256=helper_hash,elapsed_seconds=round(time.monotonic()-started,2),m2_complete=False,limitations=['Smoke test only; two-player, unreachable, full-backpack, model planning, full baseline percentiles and new-member walkthrough remain separate gates'])
    (d/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':passed,'summary':str(d/'summary.json'),'missing_checks':missing}));return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
