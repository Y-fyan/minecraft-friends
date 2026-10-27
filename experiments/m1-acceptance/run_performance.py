#!/usr/bin/env python3
"""Sequential six-run local A/B benchmark; no credentials or software installation."""
import json
import os
from pathlib import Path
import platform
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def main():
    folder = HERE / 'build/evidence' / f'm2-performance-{time.time_ns()}'
    folder.mkdir(parents=True)
    cpu = next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                if line.startswith('model name')), 'unknown')
    result = dict(host=dict(os=platform.system(), kernel=platform.release(), cpu=cpu, logical_cpus=os.cpu_count()),
                  seed=4321, mode='single owner, IntegratedServer, software-rendered client, separate JVM per variant',
                  warmup_ticks=160, sample_ticks=600, network='mock; zero remote requests, no credentials', runs=[], passed=False,
                  limitations=['Local Alpha smoke budget, not a hardware-wide guarantee',
                               'Heap is whole integrated client/server JVM; no forced GC or long-duration leak conclusion',
                               'No remote inference throughput or real token/cost measurement'])
    for loader in ('fabric', 'forge'):
        for variant in ('disabled', 'idle', 'active'):
            command = ['python3', str(HERE / 'run_actions.py'), loader + '-integrated', '--scope', 'performance', '--variant', variant]
            process = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (folder / f'{loader}-{variant}.log').write_text(process.stdout)
            output = next((json.loads(line) for line in reversed(process.stdout.splitlines()) if line.startswith('{"passed":')), None)
            if output:
                summary = json.loads(Path(output['summary']).read_text())
                result['runs'].append(dict(loader=loader, variant=variant, summary=output['summary'], passed=summary['passed'],
                                           metrics=summary.get('metrics', []), jar_sha256=summary['production_jar_sha256']))
            else:
                result['runs'].append(dict(loader=loader, variant=variant, passed=False, exit_code=process.returncode))
            (folder / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(result['runs'][-1]), flush=True)
            if process.returncode or not output or not output['passed']:
                return 1
    result['passed'] = len(result['runs']) == 6 and all(run['passed'] for run in result['runs'])
    (folder / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(passed=result['passed'], summary=str(folder / 'summary.json'))))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
