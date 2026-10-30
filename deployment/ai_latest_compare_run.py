"""Resume only fully observed budget; no failed request replay, no trading environment restart."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import sys
from ai_latest_fixture_init import phase,resources
from ai_latest_evaluation_broker import prepare_comparison,parent_state
from ai_db_relay import Relay,verify_target,docker_json


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--session',required=True);a=parser.parse_args()
    resources();session,parent_session=phase.load_session(a.session)
    parent=session/'latest-study';pm,_,_=parent_state(parent)
    target=pm['runId']+'-ai-postgres-1';c=docker_json('inspect',target)[0]
    if c['State']['Running'] or c['Image']!=pm['dbImage'] or c['Config']['Labels'].get('ai-evaluation.run')!=pm['runId'] or c['HostConfig']['NetworkMode']!='none':
        raise RuntimeError('STOPPED_OWN_AI_DB_REQUIRED')
    child=session/'latest-comparison';child.mkdir(exist_ok=False)
    who=subprocess.run(['whoami'],check=True,capture_output=True,text=True).stdout.strip()
    subprocess.run(['icacls',str(child),'/inheritance:r','/grant:r',who+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],check=True,capture_output=True)
    port=next(p for p in secrets.SystemRandom().sample(range(49152,65536),100) if phase._port_available(p))
    meta,relay_meta=prepare_comparison(child,parent,port);relay_meta={**relay_meta,'dbPort':port}
    relay=None;started=False
    try:
        phase.execute(session,['docker','start',c['Id']],'comparison-ai-db-start');started=True
        verify_target(relay_meta);relay=Relay(relay_meta,seconds=3600,max_connections=64).start()
        env=phase.clean_env(session);env.update({'AI_LATEST_COMPARISON_TESTS':'true','AI_LATEST_STUDY_RUN':str(child),'AI_EVAL_TEST_PYTHON':sys.executable})
        phase.execute(session,[str(phase.REPO/'backend/gradlew.bat'),'test','--offline','--no-daemon',
            '-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m','--tests','*LatestAiComparisonTest'],
            'latest-prompt-comparison',phase.REPO/'backend',env,timeout=3600)
        print('COMPARISON_RESULT='+str(child/'completion.private.json'))
    finally:
        if relay:relay.close()
        if started:phase.stop_new_ai_probe(session,relay_meta,'latest-comparison')


if __name__=='__main__':main()
