"""Read-only metadata audit of ONE owned, stopped pilot index. No provider or API key reader."""
import argparse,json,re,secrets
from pathlib import Path
from ai_pilot_unpaid_live import ROOT,cmd,save,restrict_owned_folder,stop_owned_container
from product_read_resources import Observer

def sql_text(value):return "'"+value.replace("'","''")+"'"

def select_like_product(rows,topk=5,per_document=2):
    selected=[];counts={};seen=set()
    for r in rows:
        if r['id'] in seen or counts.get(r['path'],0)>=per_document:continue
        seen.add(r['id']);counts[r['path']]=counts.get(r['path'],0)+1;selected.append(r)
        if len(selected)==topk:break
    return selected

def audit(source):
    if not re.fullmatch(r'pilot-linux-[a-f0-9]{16}',source):raise ValueError('AUDIT_OWNER_REQUIRED')
    base=ROOT/'deployment/runtime'/source;meta=json.loads((base/'owned.json').read_text())
    if meta['run']!=source:raise ValueError('AUDIT_OWNER_MISMATCH')
    name=source+'-pg';a=json.loads(cmd(['docker','inspect',name]))[0]
    expected=json.loads((ROOT/'deployment/ai-readiness-plan.json').read_text())['image']
    if a['Name']!='/'+name or a['Config']['Labels'].get('tms.ai.pilot-v2.owner')!=source or a['Config']['Image']!=expected or a['State']['Running']:raise ValueError('AUDIT_STOPPED_OWNED_PG_REQUIRED')
    folder=ROOT/'deployment/runtime'/('retrieval-audit-'+secrets.token_hex(8));folder.mkdir();restrict_owned_folder(folder)
    Observer(folder/'resources.jsonl').sample('READONLY_OWNED_INDEX',True)
    frozen=json.loads((ROOT/'backend/src/test/resources/ai/novel-30-v2.json').read_text(encoding='utf8'))
    atoms={key:frozen['atoms'][key] for key in ['volume','pool']}
    needle={k:sql_text(v['needle']) for k,v in atoms.items()}
    unit=sql_text(json.dumps([1.0]+[0.0]*1535,separators=(',',':')))+'::vector'
    fingerprint="select md5(coalesce(json_agg(z order by z.id)::text,'')) from (select ch.index_id,ch.id,ch.path,ch.content,ch.embedding::text from ai.chunks ch join ai.indexes i on i.id=ch.index_id where i.active) z"
    query=f"""select json_build_object(
      'active_indexes',(select json_agg(i) from (select id,model,dimensions,active from ai.indexes where active)i),
      'document_count',(select count(*) from ai.documents d join ai.indexes i on i.id=d.index_id where i.active),
      'chunk_count',(select count(*) from ai.chunks ch join ai.indexes i on i.id=ch.index_id where i.active),
      'distinct_vectors',(select count(distinct embedding::text) from ai.chunks ch join ai.indexes i on i.id=ch.index_id where i.active),
      'all_chunk_matches',(select json_agg(z order by z.id) from (
        select ch.id,ch.path,ch.heading,d.minimum_role as role,d.domain,
        strpos(ch.content,{needle['volume']})>0 as volume,strpos(ch.content,{needle['pool']})>0 as pool
        from ai.chunks ch join ai.documents d on d.index_id=ch.index_id and d.path=ch.path join ai.indexes i on i.id=ch.index_id where i.active)z),
      'user_ranked',(select json_agg(z order by z.rank) from (
        select row_number() over(order by ch.embedding <=> {unit},ch.id) as rank,ch.id,ch.path,ch.heading,d.minimum_role as role,d.domain,
        1-(ch.embedding <=> {unit}) as score,strpos(ch.content,{needle['volume']})>0 as volume,strpos(ch.content,{needle['pool']})>0 as pool
        from ai.chunks ch join ai.documents d on d.index_id=ch.index_id and d.path=ch.path join ai.indexes i on i.id=ch.index_id
        where i.active and d.minimum_role='USER' and 1-(ch.embedding <=> {unit})>=0.25)z))"""
    cleanup='UNCONFIRMED'
    try:
        cmd(['docker','start',name]);db='exchange_ai_pilot_legacy_'+source[12:]
        def read(sql):return cmd(['docker','exec',name,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','pilot','-d',db,'-c','BEGIN READ ONLY; SET LOCAL statement_timeout=5000; '+sql+'; COMMIT;'])
        before=read(fingerprint);observed=json.loads(read(query));after=read(fingerprint)
        if before!=after:raise ValueError('AUDIT_INDEX_CHANGED')
        save(folder/'index-readonly.private.json',observed)
        ranked=observed['user_ranked'];flat=observed['distinct_vectors']==1 and all(r['score']==1 for r in ranked)
        if not flat:raise ValueError('AUDIT_EXPECTED_CONTROLLED_VECTOR_REQUIRED')
        candidates=ranked[:40];selected=select_like_product(candidates)
        previous=json.loads((base/'http.private.json').read_text(encoding='utf8'));a2=next(r['reply'] for r in previous if r['case']=='A2')
        matches={key:[dict(r,candidate40=r['rank']<=40,selected5=r['id'] in {s['id'] for s in selected}) for r in ranked if r[key] and r['path']==atoms[key]['path']] for key in atoms}
        summary={'sourceRun':source,'indexExists':True,'documentCount':observed['document_count'],'chunkCount':observed['chunk_count'],
          'distinctVectors':observed['distinct_vectors'],'allUserScoresEqualOne':flat,'threshold':.25,'role':'USER','domains':None,
          'candidateLimit':40,'topK':5,'perDocument':2,'requiredAtomRanks':matches,'expectedExistsInIndex':{k:bool(v) for k,v in matches.items()},
          'replayedSelectedIds':[r['id'] for r in selected],'previousSelectedIds':[r['id'] for r in a2['knowledgeSources']],
          'selectionMatchesPrevious':[r['id'] for r in selected]==[r['id'] for r in a2['knowledgeSources']],
          'fingerprintBefore':before,'fingerprintAfter':after,'realSemanticQuality':'NOT_RUN_CONTROLLED_VECTORS','paidCalls':0,'apiKeyReads':0}
        save(folder/'audit-summary.json',summary)
    finally:
        cleanup=stop_owned_container(name,source,expected,20)
        save(folder/'cleanup.json',{'ownedPg':name,'state':cleanup,'dataPreserved':True})
    if cleanup!='STOPPED':raise ValueError('AUDIT_CLEANUP_UNCONFIRMED')
    print(json.dumps({'audit':folder.name,'documents':summary['documentCount'],'chunks':summary['chunkCount'],'requiredAtomRanks':matches,'matchesPrevious':summary['selectionMatchesPrevious'],'indexUnchanged':before==after,'paidCalls':0},ensure_ascii=True))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source-run',required=True);a=p.parse_args();audit(a.source_run)
