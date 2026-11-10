"""PAID infrastructure blueprint and approval-first launch boundary, NOT an approval issuer.

This module never reads a provider key. Preview is pure and creates no Docker
resources. A future operator must bind a fresh REAL_ISOLATED fixture and permit;
UNPAID sessions/ledgers cannot be adopted. Evaluation-driver integration remains
an explicit separate check, not inferred from this infrastructure blueprint.
"""
import argparse
import hashlib
import ipaddress
import json
import re
from pathlib import Path

from ai_pilot_preflight import preflight, need

LABEL = 'tms.ai.paid-v2.owner'


def topology(owner, tools_image, pg_image, *, internal_subnet, outbound_subnet):
    need(type(owner) is str and re.fullmatch(r'pilot-paid-[0-9a-f]{16}', owner), 'V2_PAID_OWNER')
    for image in (tools_image, pg_image):
        need(type(image) is str and re.fullmatch(r'sha256:[0-9a-f]{64}', image), 'V2_PAID_IMAGE_ID')
    nets=[ipaddress.IPv4Network(n,strict=True) for n in (internal_subnet,outbound_subnet)]
    need(all(n.prefixlen==28 and n.subnet_of(ipaddress.IPv4Network('10.254.0.0/16')) for n in nets)
         and not nets[0].overlaps(nets[1]),'V2_PAID_SUBNETS')
    names = {r: owner+'-'+r for r in ('internal', 'outbound', 'pgdata', 'pg', 'app', 'egress')}
    suffix = owner[11:]
    urls = {role: 'jdbc:postgresql://127.0.0.1:5432/exchange_ai_pilot_'+role+'_'+suffix+'?connectTimeout=2&socketTimeout=5'
            for role in ('budget', 'legacy', 'pilot', 'trade')}
    authority = hashlib.sha256((urls['budget']+'\npilot').encode()).hexdigest()
    def create(role, image, net, options, command):
        return ['docker', 'create', '--pull=never', '--name', names[role], '--label', LABEL+'='+owner,
                '--network', net, *options, image, *command]
    commands = [
        ['docker', 'network', 'create', '--internal', '--subnet',str(nets[0]), '--label', LABEL+'='+owner, names['internal']],
        ['docker', 'network', 'create', '--subnet',str(nets[1]), '--label', LABEL+'='+owner, names['outbound']],
        ['docker', 'volume', 'create', '--label', LABEL+'='+owner, names['pgdata']],
        create('pg', pg_image, names['internal'], ['--memory=384m', '--network-alias', 'pg',
               '--env-file', '/PREPARED/pg.private.env', '--mount', 'type=volume,source='+names['pgdata']+',target=/var/lib/postgresql/data'],
               ['postgres', '-c', 'shared_buffers=16MB', '-c', 'max_connections=40']),
        create('egress', tools_image, names['internal'], ['--network-alias', 'pilot-egress', '--user', '1000:1000',
               '--memory=64m', '--pids-limit=32', '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--read-only',
               '--mount', 'type=bind,source=/PREPARED/source/deployment,target=/relay,readonly', '--entrypoint', 'python'],
               ['-B', '-S', '/relay/ai_pilot_egress.py', '--approved-egress']),
        ['docker', 'network', 'connect', names['outbound'], names['egress']],
        # No PAID pg:5432 relaxation. Linux namespace sharing preserves validated localhost JDBC.
        create('app', tools_image, 'container:'+names['pg'], ['--user', '1000:1000', '--memory=2560m',
               '--shm-size=256m', '--security-opt=seccomp=/PREPARED/chromium-seccomp.json',
               '--mount', 'type=bind,source=/PREPARED/runtime,target=/run/pilot',
               '--mount', 'type=bind,source=/PREPARED/provider-key,target=/run/approved-provider-key,readonly'], []),
    ]
    return {'version': 'tms-paid-environment-v1', 'owner': owner, 'names': names, 'jdbc': urls,
            'budgetAuthority': authority, 'commands': commands, 'approvalIssued': False,
            'providerChecked': False, 'evaluationDriverReady': False,
            'unresolved': ['FRESH_FIXTURE_AND_PERMIT_BINDING', 'PAID_SERVER_AND_DOM_ADAPTER_BINDING',
                           'APPROVED_DNS_TLS_MODEL_ACCESS_AND_PRICE_CHECK']}


def guarded_launch(meta, permit, current_source, launch):
    """No subprocess, resource creation or key access before a bound paid permit.

    A callable is injected so the boundary itself is testable without Docker.
    Infrastructure construction is NOT a PG/SQLite budget reservation and never
    grants the callable permission to bypass PaidSender's per-request gates.
    """
    from ai_pilot_paid_sender import authorize
    authorize(meta, permit, current_source=current_source)
    return launch()


def preview():
    audit = preflight()
    return {'status': 'PREPARATION_ONLY_NOT_AUTHORIZED', 'apiRequests': 0, 'keyReads': 0,
            'activationAllowed': False, 'capUsd': '18.00', 'llmCalls': 92, 'embeddingCalls': 70,
            'maximumReservedUsd': '17.168384', 'planAuditPassed': bool(audit),
            'paidExecution': 'BLOCKED_UNTIL_BOUND_APPROVAL_AND_DRIVER_VALIDATION'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preview'])
    parser.parse_args()
    print(json.dumps(preview()))
