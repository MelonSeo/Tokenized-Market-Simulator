"""Owned local Anvil wall-clock repair, explicitly authorized; never signs or sends a transaction."""
import argparse, hashlib, importlib.util, json, re, time, urllib.request, uuid
from pathlib import Path


def need(ok, code):
    if not ok:
        raise ValueError('MANUAL_CLOCK_' + code)


def observe(rpc, clock=time.time):
    latest = rpc('eth_getBlockByNumber', ['latest', False])
    pending = rpc('eth_getBlockByNumber', ['pending', False])
    now = int(clock())
    return {'wall': now, 'latest': latest, 'pending': pending,
            'latestAge': now-int(latest['timestamp'], 16),
            'pendingAge': now-int(pending['timestamp'], 16)}


def require_ready(rpc, clock=time.time):
    row = observe(rpc, clock)
    # Operational readiness, not an alteration of the 5/30/120-second financial checks.
    need(-2 <= row['latestAge'] <= 5 and -2 <= row['pendingAge'] <= 5, 'NOT_WALL_ALIGNED')
    return row


def configure(rpc, clock=time.time):
    # v1.8.1 accepts Unix seconds as a JSON number. Two non-atomic calls, never retried.
    result = rpc('evm_setTime', [int(clock())])
    need(type(result) is int and result >= 0, 'TIME_RESPONSE_UNCERTAIN')
    need(rpc('evm_setIntervalMining', [1]) is None, 'INTERVAL_RESPONSE_UNCERTAIN')
    need(rpc('anvil_getIntervalMining', []) == 1 and rpc('anvil_getAutomine', []) is False,
         'MINING_MODE_UNCERTAIN')


def snapshot(rpc, meta, root, anchors=None):
    head = rpc('eth_getBlockByNumber', ['latest', False])
    pin = {'blockHash': head['hash'], 'requireCanonical': True}
    need(rpc('eth_chainId', []) == '0x7a69', 'CHAIN_ID')
    need(rpc('eth_getBlockByNumber', ['0x0', False])['hash'] == meta['genesisHash'], 'GENESIS')
    proofs = {}
    for role in ['userA', 'token', 'controller', 'rules', 'vault']:
        proof = rpc('eth_getProof', [meta[role], [], pin])
        need(proof['address'].lower() == meta[role].lower(), 'PROOF_ADDRESS')
        proofs[role] = {k: proof[k] for k in ['address', 'balance', 'nonce', 'codeHash', 'storageHash']}
    nonce = rpc('eth_getTransactionCount', [meta['userA'], pin])
    need(rpc('eth_getTransactionCount', [meta['userA'], 'pending']) == nonce, 'USER_PENDING')
    pool = rpc('txpool_status', [])
    need(all(int(pool[k], 16) == 0 for k in ['pending', 'queued']), 'TXPOOL_NOT_EMPTY')
    need(not rpc('eth_getBlockByNumber', ['pending', False])['transactions'], 'PENDING_BLOCK')
    artifact = json.loads((root/'contracts/out/CMarginVault.sol/CMarginVault.json').read_text())
    selector = artifact['methodIdentifiers']['totalLocked()']
    need(int(rpc('eth_call', [{'to': meta['vault'], 'data': '0x'+selector}, pin]), 16) == 0,
         'ACTIVE_LOCKS')
    heights = list(anchors) if anchors is not None else list(range(int(head['number'], 16)+1))
    need(len(heights) <= 256, 'HISTORY_BOUND')
    history, receipts = {}, {}
    for number in heights:
        block = rpc('eth_getBlockByNumber', [hex(int(number)), False])
        history[str(number)] = block['hash']
        for txhash in block['transactions']:
            receipt = rpc('eth_getTransactionReceipt', [txhash])
            need(receipt and receipt['blockHash'] == block['hash'], 'RECEIPT_CANONICAL')
            receipts[txhash] = hashlib.sha256(json.dumps(receipt, sort_keys=True).encode()).hexdigest()
    need(rpc('eth_getBlockByNumber', [head['number'], False])['hash'] == head['hash'], 'HEAD_REORG')
    return {'head': {'number': head['number'], 'hash': head['hash'], 'timestamp': head['timestamp']},
            'proofs': proofs, 'nonce': nonce, 'history': history, 'receiptHashes': receipts}


def verify_preserved(before, after):
    for name in ['proofs', 'nonce', 'history', 'receiptHashes']:
        need(before[name] == after[name], 'PRESERVATION_' + name.upper())
    need(int(after['head']['number'], 16) > int(before['head']['number'], 16), 'NO_NEW_BLOCK')


def manual_module():
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('clock_manual', root/'deployment/product-manual.py')
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


def pause_states(m, record, containers, roles):
    states = {}
    for role in roles:
        try:
            info = m.owned(record, role)
            need(info['Id'] == containers[role]['Id'], 'RESUME_CONTAINER_CHANGED')
            states[role] = {'running':info['State']['Running'],'paused':bool(info['State'].get('Paused'))}
        except Exception:
            states[role] = {'status':'UNKNOWN'}
    return states


def restore_admission(m, record, containers, roles, evidence):
    errors = []
    for role in reversed(roles):
        try:
            info = m.owned(record, role)
            need(info['Id'] == containers[role]['Id'], 'RESUME_CONTAINER_CHANGED')
            if info['State'].get('Paused'):
                m.docker('unpause', containers[role]['Id'])
        except Exception as error:
            code = str(error)
            errors.append({'role':role,'safeCode':code if re.fullmatch('MANUAL_[A-Z0-9_]+',code) else type(error).__name__})
    states = pause_states(m, record, containers, roles)
    m.save(evidence/'pause-recovery.json', {'states':states,'errors':errors,'automaticRetry':False})
    need(not errors and all(s.get('running') and s.get('paused') is False for s in states.values()),
         'SERVICE_RESUME_UNCERTAIN')


def resource_admission(m, path, approved_existing_maintenance=False):
    if not approved_existing_maintenance:
        return m.admission(path)
    # Narrow user-approved exception: this module cannot build/start/restart a server.
    # Preserve the original 1920-MiB observation, record the separate policy, never alter global constants.
    observer = m.resources.Observer(path, m.memory, lambda:m.shutil.disk_usage(m.ROOT).free)
    row = observer.sample('EXISTING_CLOCK_MAINTENANCE', False)
    minimum = 1024**3
    ram = max(0, minimum-row['availableBytes'])
    disk = max(0, m.resources.MIN_DISK-row['diskFreeBytes'])
    decision = 'RAM_AND_DISK_LOW' if ram and disk else 'RAM_LOW' if ram else 'DISK_LOW' if disk else 'SUFFICIENT'
    m.save(path.with_suffix('.maintenance-policy.json'), {
        'scope':'USER_APPROVED_EXISTING_CLOCK_ONLY','requiredRAMBytes':minimum,
        'originalRequiredRAMBytes':row['requiredRAMBytes'],'availableBytes':row['availableBytes'],
        'requiredDiskBytes':m.resources.MIN_DISK,'diskFreeBytes':row['diskFreeBytes'],
        'ramShortfallBytes':ram,'diskShortfallBytes':disk,'decision':decision})
    need(decision == 'SUFFICIENT', 'MAINTENANCE_RESOURCES_LOW')
    return observer


def repair(session, approved_existing_maintenance=False):
    m = manual_module()
    root = m.ROOT
    p = m.scoped(session); record = json.loads((p/'owned.json').read_text()); live = m.active_path(p, record)
    need(p.name == 'session-'+record['run'][5:] and record.get('lifecycle') == live.name, 'SESSION')
    need(record['externalOwner'] == record['identity']['userA'], 'OWNER_IDENTITY')
    need(record['rpc'] == 'http://127.0.0.1:32176' and not (live/'stopped.json').exists(), 'LIVE_IDENTITY')
    m.check_stop(p)
    evidence = live/('clock-repair-'+uuid.uuid4().hex[:16]); evidence.mkdir()
    resource_admission(m, evidence/'resources.jsonl', approved_existing_maintenance)
    containers = {role: m.owned(record, role) for role in ['app', 'oracle', 'anvil', 'pg']}
    need(all(i['State']['Running'] and not i['State'].get('Paused') for i in containers.values()), 'SERVICES')
    m.save(live/'CLOCK_REPAIR_CLAIM', {'evidence': evidence.name, 'policy': 'USER_APPROVED_WALL_ALIGNMENT',
           'existingMaintenance1GiBException':approved_existing_maintenance,
           'sourceSHA256':{name:hashlib.sha256((root/'deployment'/name).read_bytes()).hexdigest()
                          for name in ['product_manual_clock.py','product-manual.py','test_product_manual_clock.py']}})
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def rpc(method, params):
        req = urllib.request.Request(record['rpc'], data=json.dumps({'jsonrpc':'2.0','id':1,'method':method,'params':params}).encode(), headers={'Content-Type':'application/json'})
        with http.open(req, timeout=3) as response:
            data = response.read(2*1024*1024+1)
        need(len(data) <= 2*1024*1024, 'RPC_BOUND')
        value = json.loads(data); need(value.get('id') == 1 and 'error' not in value and 'result' in value, 'RPC_RESULT')
        return value['result']
    paused, mutation, verified = [], False, False
    try:
        # Admission/report creation cannot race this two-RPC maintenance window.
        for role in ['app', 'oracle']:
            paused.append(role)  # A failed response does not prove pause was not applied.
            m.docker('pause', containers[role]['Id'])
        suffix = record['run'][5:]
        sql = 'BEGIN READ ONLY; SELECT (SELECT count(*) FROM tms_c_execution.drafts)::text||\'|\'||(SELECT count(*) FROM tms_c_execution.direct_executions)::text; COMMIT;'
        result = m.execute(['docker','exec',containers['pg']['Id'],'psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-U','ccowner_'+suffix,'-d','ccpg_'+suffix,'-c',sql], 8)
        need(result.returncode == 0 and '0|0' in result.stdout.decode('utf8').splitlines(), 'UNSIGNED_EXECUTION_EXISTS_OR_DB_FAILURE')
        m.check_stop(p)
        before = snapshot(rpc, record['identity'], root); m.save(evidence/'before.json', before)
        m.save(evidence/'clock-before.json', observe(rpc))
        # No automatic rollback/retry: a timeout may follow a committed mutation.
        mutation = True; configure(rpc)
        deadline = time.monotonic()+8
        while time.monotonic() < deadline:
            m.check_stop(p)
            row = observe(rpc)
            if int(row['latest']['number'],16) > int(before['head']['number'],16):
                require_ready(rpc); break
            time.sleep(.25)
        else:
            need(False, 'NEW_BLOCK_TIMEOUT')
        after = snapshot(rpc, record['identity'], root, before['history'])
        verify_preserved(before, after); m.save(evidence/'after.json', after)
        m.save(evidence/'clock-after.json', require_ready(rpc)); verified = True
    except Exception as error:
        code = str(error)
        m.save(evidence/'failure.json', {'status':'UNCERTAIN_KEEP_APP_PAUSED' if mutation else 'BLOCKED_NO_CLOCK_CHANGE',
               'safeCode':code if re.fullmatch('MANUAL_[A-Z0-9_]+',code) else type(error).__name__,
               'mutationMayHaveOccurred':mutation,'automaticRetry':False})
        m.save(evidence/'failure-service-state.json', pause_states(m, record, containers, paused))
        raise
    finally:
        if verified or not mutation:
            restore_admission(m, record, containers, paused, evidence)
        # Otherwise keep admission paused. Supervisor checks Running, which remains true while paused.
    result = {'status':'PASS_CLOCK_AND_ASSET_PRESERVATION_NOT_OPEN_ACCEPTANCE', 'evidence':str(evidence.relative_to(root)),
              'intervalSeconds':1,'userTransactionsSent':0,'existingAssetsAndHistoryPreserved':True}
    m.save(evidence/'result.json', result)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('session'); parser.add_argument('--execute-approved-wall-sync', action='store_true', required=True)
    parser.add_argument('--approved-existing-maintenance-1gib', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(repair(args.session, args.approved_existing_maintenance_1gib)))
    except Exception as error:
        code = str(error)
        print(json.dumps({'status':'BLOCKED_NO_AUTOMATIC_RETRY','safeCode':code if re.fullmatch('MANUAL_[A-Z0-9_]+',code) else type(error).__name__}))
        raise SystemExit(1)
