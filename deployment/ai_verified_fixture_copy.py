"""Cold-copy one proven, stopped AI-only legacy fixture; never use user checkpoints."""
import hashlib
import json
import shutil
import stat
from pathlib import Path


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def linked(path):
    return path.is_symlink() or bool(getattr(path.lstat(),'st_file_attributes',0)&getattr(stat,'FILE_ATTRIBUTE_REPARSE_POINT',1024))


def inventory(folder):
    if linked(folder):raise RuntimeError('COPY_LINK_FORBIDDEN')
    result={}
    for p in sorted(folder.rglob('*')):
        if linked(p):raise RuntimeError('COPY_LINK_FORBIDDEN')
        if p.is_file():result[p.relative_to(folder).as_posix()]=digest(p)
    return result


def validate(session, runtime):
    raw=Path(session)
    if linked(raw):raise RuntimeError('COPY_SOURCE_LINK_FORBIDDEN')
    source=raw.resolve(strict=True);root=Path(runtime).resolve(strict=True)
    if source.parent!=root:raise RuntimeError('OWN_AI_RUNTIME_SOURCE_REQUIRED')
    study=source/'latest-study'
    meta=json.loads((study/'run.json').read_text(encoding='utf-8'))
    fresh=json.loads((study/'fresh-fixture.private.json').read_text(encoding='utf-8'))
    after=json.loads((study/'financial-after.private.json').read_text(encoding='utf-8'))
    if not (study/'STOP').is_file() or not meta.get('finalAcceptance') or meta.get('purpose')!='LATEST_AI_QUALITY_20261030':raise RuntimeError('CLOSED_AI_SOURCE_REQUIRED')
    if fresh.get('source')!='NEW_PG_ANVIL_LEGACY_DB_COLLATERAL_NOT_C_VAULT' or after.get('unchanged') is not True or fresh['futures'].get('verification')!='VERIFIED' or fresh['futures'].get('positionStatus')!='LIQUIDATED' or fresh['spot'].get('status')!='FILLED':raise RuntimeError('VERIFIED_FINANCIAL_SOURCE_REQUIRED')
    fixtures=list((source/'fixtures').glob('futures-ai-latest-*'))
    if len(fixtures)!=1:raise RuntimeError('EXACT_AI_FIXTURE_REQUIRED')
    fixture=fixtures[0]
    if linked(source/'fixtures') or linked(study) or linked(fixture):raise RuntimeError('COPY_SOURCE_LINK_FORBIDDEN')
    if (fixture/'postgres/postmaster.pid').exists():raise RuntimeError('COLD_POSTGRES_REQUIRED')
    for child in ('postgres','chain','release','secrets'):inventory(fixture/child)
    json.loads((fixture/'chain/state.json').read_text(encoding='utf-8'))
    return source,fixture,fresh,after


def copy_verified(session,runtime,target,inspect):
    source,fixture,fresh,after=validate(session,runtime)
    old=json.loads((fixture/'run.json').read_text(encoding='utf-8'))
    for suffix in ('-postgres','-anvil'):
        container=inspect(old['name']+suffix)
        if container is not None and (container['State']['Running'] or container['Config']['Labels'].get('phase-a.fixture')!=old['name']):raise RuntimeError('SOURCE_CONTAINER_NOT_COLD_OWNED')
    originals={child:inventory(fixture/child) for child in ('postgres','chain','release','secrets')}
    for child in ('postgres','chain','release'):
        if any((target/child).iterdir()):raise RuntimeError('EMPTY_COPY_TARGET_REQUIRED')
        shutil.copytree(fixture/child,target/child,dirs_exist_ok=True)
        if inventory(target/child)!=originals[child]:raise RuntimeError('COLD_COPY_HASH_MISMATCH')
    if any(inventory(fixture/c)!=v for c,v in originals.items()):raise RuntimeError('COPY_SOURCE_CHANGED')
    proof={'scope':'COLD_COPY_VERIFIED_AI_LEGACY_FIXTURE_NOT_NEW_FINANCIAL_TRADES','sourceSession':str(source),'sourceRunId':old['runId'],
           'sourceFinancialFingerprint':after['futuresFingerprint'],'files':originals,'sourceEvidenceHash':digest(source/'latest-study/fresh-fixture.private.json')}
    return fixture,proof
