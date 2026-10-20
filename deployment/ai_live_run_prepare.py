"""Generate secrets/Compose for ONE already-prepared approved run. No API calls.
The caller must first restrict the new directory ACL. Existing files are refused.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import time
import re

from ai_evaluation_broker import load_run
from ai_model_access_preflight import read_key


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--run-dir",required=True)
    p.add_argument("--credentials-file",required=True)
    p.add_argument("--image",required=True)
    args=p.parse_args()
    folder,meta=load_run(args.run_dir)
    if meta["mode"]!="live" or not re.fullmatch(r"sha256:[0-9a-f]{64}",args.image):
        raise SystemExit("LIVE_RUN_AND_PINNED_IMAGE_REQUIRED")
    targets=[folder/name for name in ("openai.key","db.env","approval.json","compose.json","relay.json")]
    if any(path.exists() for path in targets):
        raise SystemExit("EXISTING_RUN_SECRET_OR_CONFIGURATION_PRESERVED")
    key=read_key(args.credentials_file)
    password=secrets.token_urlsafe(36)
    permit={"runId":meta["runId"],"sourceHash":meta["sourceHash"],"corpusHash":meta["corpusHash"],
            "paidApproved":True,"capUsd":"2.00","llmCalls":32,"embeddingCalls":80,
            "expiresAt":int(time.time())+14340,"keyFile":str((folder/"openai.key").absolute())}
    owner={"ai-evaluation.run":meta["runId"]}
    compose={"name":meta["runId"],"services":{"ai-postgres":{
        "image":args.image,"pull_policy":"never","env_file":["db.env"],
        "network_mode":"none","volumes":["ai-data:/var/lib/postgresql/data"],"labels":owner,
        "healthcheck":{"test":["CMD","pg_isready","-U",meta["dbUser"],"-d",meta["dbName"]],"interval":"2s","timeout":"2s","retries":20}}},
        "volumes":{"ai-data":{"labels":owner}}}
    # Separate connection metadata, not a mutation of run/ledger identity.
    relay={**meta,"dbImage":args.image}
    contents=[key,"POSTGRES_USER="+meta["dbUser"]+"\nPOSTGRES_DB="+meta["dbName"]+"\nPOSTGRES_PASSWORD="+password+"\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n",json.dumps(permit),json.dumps(compose),json.dumps(relay)]
    for path,content in zip(targets,contents):
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            f.write(content);f.flush();os.fsync(f.fileno())
    print(json.dumps({"prepared":True,"runId":meta["runId"],"port":meta["dbPort"],"secretValuesPrinted":False,"apiCalls":0}))


if __name__=="__main__":
    main()
