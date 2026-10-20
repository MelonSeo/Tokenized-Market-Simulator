"""Prepare a NEW no-network DB probe only. No API secrets, approval or ledger."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import uuid

from ai_evaluation_broker import source_hash, corpus_hash


def compose(meta):
    return {'name':meta['runId'], 'services':{'ai-postgres':{
        'image':meta['dbImage'], 'pull_policy':'never', 'network_mode':'none',
        'env_file':['db.env'], 'volumes':['ai-data:/var/lib/postgresql/data'],
        'labels':{'ai-evaluation.run':meta['runId']},
        'healthcheck':{'test':['CMD','pg_isready','-U',meta['dbUser'],'-d',meta['dbName']],
                       'interval':'2s','timeout':'2s','retries':20}}},
        'volumes':{'ai-data':{'labels':{'ai-evaluation.run':meta['runId']}}}}


def write_new(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write(text); f.flush(); os.fsync(f.fileno())


def prepare(folder, port, image):
    folder = Path(folder).resolve(strict=True) # Caller creates/restricts new empty directory.
    if any(folder.iterdir()) or not 49152 <= port <= 65535 or not re.fullmatch(r'sha256:[0-9a-f]{64}',image):
        raise ValueError('NEW_EMPTY_PROBE_REQUIRED')
    token = uuid.uuid4().hex
    db = 'exchange_ai_eval_' + token
    meta = {'runId':'ai_db_probe_'+token,'dbPort':port,'dbName':db,'dbUser':db,
            'dbImage':image,'mode':'offline-database-probe','sourceHash':source_hash(),'corpusHash':corpus_hash()}
    write_new(folder/'fixture.json', json.dumps(meta))
    write_new(folder/'db.env', 'POSTGRES_USER='+db+'\nPOSTGRES_DB='+db+'\nPOSTGRES_PASSWORD='+secrets.token_urlsafe(36)+'\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n')
    write_new(folder/'compose.json', json.dumps(compose(meta)))
    return meta


if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--folder',required=True); p.add_argument('--port',required=True,type=int);p.add_argument('--image',required=True)
    a=p.parse_args()
    m=prepare(a.folder,a.port,a.image)
    print(json.dumps({'prepared':True,'runId':m['runId'],'port':m['dbPort'],'paidCalls':0,'ledgerCreated':False}))
