"""UTF-8, evidence-bound semantic review with the unchanged 300s deadline."""
import argparse,hashlib,json,secrets,sys,time
from pathlib import Path
from ai_pilot_preflight import canonical,need

DIMENSIONS=('accuracy','sufficiency','grounding','uncertainty','authorization')
PROTOCOL='tms-semantic-review-workflow-v1'

def digest(value):return hashlib.sha256(canonical(value)).hexdigest()

def read(path):
    path=Path(path)
    need(path.is_file() and not path.is_symlink() and path.stat().st_size<=2100000,'V2_REVIEW_FILE_REQUIRED')
    return json.loads(path.read_text(encoding='utf8'))

def row_key(row):return row['phase']+'-'+str(row['slot'])

class ReviewDesk:
    """Prepare criteria before transmission; human readiness is not a verdict.

    Files are a private mailbox, not model input. The reviewer must already know
    the policy/Tool contracts, then arm exactly one row before its HTTP request.
    No model, key, ledger, Docker or approval operations exist here.
    """
    def __init__(self,directory,rows,*,clock=time.monotonic,sleep=time.sleep):
        from ai_pilot_paid_adapter import private_write
        from ai_pilot_preflight import ROOT
        self.directory=Path(directory);self.directory.mkdir(exist_ok=True)
        self.clock,self.sleep,self.write=clock,sleep,private_write
        sources=[read(ROOT/'backend/src/test/resources/ai'/n) for n in ('pilot-acceptance-v1.json','novel-30-v2.json')]
        self.templates={}
        for row in rows:
            key=row_key(row);need(key not in self.templates,'V2_REVIEW_DUPLICATE_ROW')
            self.templates[key]={'protocol':PROTOCOL,'row':row,'criteria':sources[0]['criteria'],
                'frozenExpected':next(c for s in sources for c in s['cases'] if c['id']==row['case']),
                'dimensions':list(DIMENSIONS),'deadlineMs':300000,
                'order':['read actual answer, Tool facts and citations','check every frozen fact and forbidden claim',
                         'record all five findings and verdict immediately','wait for producer receipt','only then write detailed analysis']}
        self.write(self.directory/'prepared.private.json',{'protocol':PROTOCOL,'templates':self.templates})
        self.armed={}

    def arm(self,row):
        key=row_key(row);template=self.templates[key]
        need(template['row']==row and key not in self.armed,'V2_REVIEW_ROW_CHANGED')
        arm={'protocol':PROTOCOL,'rowKey':key,'templateSha256':digest(template),'nonce':secrets.token_hex(16)}
        self.write(self.directory/(key+'.arm.json'),arm)
        ready=self.directory/(key+'.ready.json');started=self.clock();end=started+60
        while not ready.exists():
            need(self.clock()<end,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION');self.sleep(.05)
        need(self.clock()<end,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION')
        r=read(ready)
        need(set(r)=={'armSha256','reviewer','criteriaRead'} and r['armSha256']==digest(arm)
             and r['criteriaRead'] is True and isinstance(r['reviewer'],str) and bool(r['reviewer'].strip()),'V2_REVIEWER_READY_INVALID')
        need(self.clock()<end,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION')
        self.write(self.directory/(key+'.ready-receipt.json'),{'armSha256':digest(arm),
            'elapsedMillis':int((self.clock()-started)*1000),'deadlineMs':60000})
        need(self.clock()<end,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION')
        result={'protocol':PROTOCOL,'arm':arm,'ready':r,'template':template}
        self.armed[key]=result;return result

    def prepared(self,row):
        result=self.armed.get(row_key(row));need(result is not None,'V2_REVIEWER_NOT_READY_NO_TRANSMISSION')
        need(result['template']['row']==row,'V2_REVIEW_ROW_CHANGED');return result

def brief(directory,key):
    directory=Path(directory);prepared=read(directory/'prepared.private.json')
    arm=read(directory/(key+'.arm.json'));template=prepared['templates'][key]
    need(arm['templateSha256']==digest(template) and arm['rowKey']==key,'V2_REVIEW_ROW_CHANGED')
    return {'armSha256':digest(arm),'template':template}

def ready(directory,key,arm_hash,reviewer):
    from ai_pilot_paid_adapter import private_write
    need(brief(directory,key)['armSha256']==arm_hash,'V2_REVIEW_READY_STALE')
    need(isinstance(reviewer,str) and bool(reviewer.strip()),'V2_REVIEWER_READY_INVALID')
    private_write(Path(directory)/(key+'.ready.json'),{'armSha256':arm_hash,'reviewer':reviewer,'criteriaRead':True})

def await_packet(directory,key,template_hash,reviewer,*,seconds=600,clock=time.monotonic,sleep=time.sleep):
    """Start BEFORE the producer. Pre-read human criteria bind the nonce hot path.

    This watcher may ACK preparedness but NEVER judges an answer or writes a
    verdict. One already-running process removes cold CLI/approval round trips
    from readiness. The producer still owns both deadlines.
    """
    need(type(seconds) is int and 1<=seconds<=600,'V2_REVIEW_WATCH_LIMIT')
    directory=Path(directory);end=clock()+seconds
    def wait_file(path):
        while not path.exists():
            need(clock()<end,'V2_REVIEW_WATCH_EXPIRED');sleep(.05)
        need(clock()<end,'V2_REVIEW_WATCH_EXPIRED')
    wait_file(directory/(key+'.arm.json'));b=brief(directory,key)
    need(digest(b['template'])==template_hash,'V2_REVIEW_PREPARATION_CHANGED')
    ready(directory,key,b['armSha256'],reviewer)
    wait_file(directory/(key+'.response.json'));pointer=read(directory/(key+'.response.json'))
    need(set(pointer)=={'evidenceSha256'} and type(pointer['evidenceSha256']) is str
         and len(pointer['evidenceSha256'])==64 and all(c in '0123456789abcdef' for c in pointer['evidenceSha256']),
         'V2_REVIEW_ROW_CHANGED')
    evidence=read(directory/(pointer['evidenceSha256']+'.request.private.json'))
    need(digest(evidence)==pointer['evidenceSha256'] and evidence['row']==b['template']['row']
         and evidence['criteria']==b['template']['criteria'] and evidence['frozenExpected']==b['template']['frozenExpected'],
         'V2_REVIEW_ROW_CHANGED')
    return evidence

def assessment_valid(a,evidence):
    need(type(a) is dict and set(a)=={'evidenceSha256','reviewer','quality','checks','frozenFactsChecked','forbiddenClaimsChecked','safeToContinue'},'V2_REVIEW_ASSESSMENT_INVALID')
    need(a['evidenceSha256']==digest(evidence) and isinstance(a['reviewer'],str) and bool(a['reviewer'].strip())
         and a['quality'] in {'PASS','PARTIAL','FAIL'} and a['frozenFactsChecked'] is True
         and a['forbiddenClaimsChecked'] is True and type(a['safeToContinue']) is bool,'V2_REVIEW_ASSESSMENT_INVALID')
    need(type(a['checks']) is dict and set(a['checks'])==set(DIMENSIONS),'V2_REVIEW_ASSESSMENT_INCOMPLETE')
    for check in a['checks'].values():
        need(type(check) is dict and set(check)=={'verdict','finding'} and check['verdict'] in {'PASS','PARTIAL','FAIL'}
             and isinstance(check['finding'],str) and 8<=len(check['finding'].strip())<=1600,'V2_REVIEW_ASSESSMENT_INCOMPLETE')
    need(a['checks']['authorization']['verdict']=='PASS' or a['safeToContinue'] is False,'V2_REVIEW_UNSAFE_CONTINUATION')
    verdicts={c['verdict'] for c in a['checks'].values()}
    need(a['quality']==('FAIL' if 'FAIL' in verdicts else 'PARTIAL' if 'PARTIAL' in verdicts else 'PASS'),'V2_REVIEW_GRADE_INCONSISTENT')
    return a

def decision_for(a):
    prefix='SAFE_QUALITY_FAIL:' if a['quality']=='FAIL' and a['safeToContinue'] else a['quality']+':'
    notes=prefix+' '+json.dumps({k:a['checks'][k]['finding'] for k in DIMENSIONS},ensure_ascii=False,separators=(',',':'))
    need(len(canonical({'notes':notes}))<=12000,'V2_REVIEW_FRAME_LIMIT')
    return {'evidenceSha256':a['evidenceSha256'],'accepted':a['quality']!='FAIL' and a['safeToContinue'],
            'reviewer':a['reviewer'],'notes':notes}

def submit(directory,key,assessment):
    """One bounded write transaction for substantive findings + verdict; never a receipt issuer."""
    from ai_pilot_paid_adapter import private_write
    directory=Path(directory);evidence=read(directory/(key+'.request.private.json'))
    a=assessment_valid(assessment,evidence);workflow=read(directory/(key+'.workflow.private.json'))
    need(a['reviewer']==workflow['ready']['reviewer'],'V2_REVIEWER_CHANGED')
    need(not any((directory/(key+s)).exists() for s in ('.receipt.json','.failure.json','.decision.json')),'V2_REVIEW_ALREADY_TERMINAL')
    decision=decision_for(a)
    private_write(directory/(key+'.assessment.private.json'),a)
    # Safe classification is an explicit human judgement, not inferred from HTTP200 or the grade.
    private_write(directory/(key+'.decision.json'),decision)
    return {'status':'DECISION_RECORDED_NOT_GATE_PASS','evidenceSha256':key}


class ReviewGate:
    def __init__(self,directory,evidence,*,clock=time.monotonic,sleep=time.sleep,prepared=None):
        from ai_pilot_paid_adapter import private_write,BOUNDS
        self.write,self.clock,self.sleep=private_write,clock,sleep
        self.evidence,self.prepared=evidence,prepared
        self.directory=Path(directory);self.directory.mkdir(exist_ok=True)
        self.key=hashlib.sha256(canonical(evidence)).hexdigest()
        self.decision=self.directory/(self.key+'.decision.json')
        need(not self.decision.exists() and not self.decision.is_symlink(),'V2_PREEXISTING_REVIEW_DECISION')
        self.started=clock();self.end=self.started+BOUNDS['semanticReviewMs']/1000
        if prepared is not None:
            need(prepared['template']['row']==evidence['row'] and prepared['template']['criteria']==evidence['criteria']
                 and prepared['template']['frozenExpected']==evidence['frozenExpected'],'V2_REVIEW_ROW_CHANGED')
            self.write(self.directory/(self.key+'.workflow.private.json'),prepared)
        self.write(self.directory/(self.key+'.request.private.json'),evidence)
        self.write(self.directory/(self.key+'.timing.json'),{'startedUtc':time.time(),'deadlineMs':BOUNDS['semanticReviewMs']})
        if prepared is not None:self.write(self.directory/(row_key(evidence['row'])+'.response.json'),{'evidenceSha256':self.key})

    def wait(self):
        try:return self._wait()
        except ValueError:
            self.write(self.directory/(self.key+'.failure.json'),{'evidenceSha256':self.key,'status':'FAILED_NO_TIMELY_VALID_REVIEW',
                'elapsedMillis':int((self.clock()-self.started)*1000),'deadlineMs':300000,'originalResponsePreserved':True})
            raise

    def _wait(self):
        while not self.decision.exists():
            need(self.clock()<self.end,'V2_SEMANTIC_REVIEW_REQUIRED');self.sleep(.05)
        need(not self.decision.is_symlink() and self.decision.stat().st_size<=16384,'V2_REVIEW_FRAME_LIMIT')
        d=json.loads(self.decision.read_text(encoding='utf8'))
        need(self.clock()<self.end,'V2_SEMANTIC_REVIEW_REQUIRED')
        need(set(d)=={'evidenceSha256','accepted','reviewer','notes'} and d['evidenceSha256']==self.key
             and type(d['accepted']) is bool and isinstance(d['reviewer'],str) and bool(d['reviewer'].strip())
             and isinstance(d['notes'],str) and bool(d['notes'].strip()),'V2_SEMANTIC_REVIEW_INVALID')
        if self.prepared is not None:
            a=assessment_valid(read(self.directory/(self.key+'.assessment.private.json')),self.evidence)
            need(d['reviewer']==a['reviewer']==self.prepared['ready']['reviewer']
                 and d==decision_for(a),'V2_REVIEW_ASSESSMENT_INVALID')
            need(self.clock()<self.end,'V2_SEMANTIC_REVIEW_REQUIRED')
        result={'evidenceSha256':self.key,'accepted':d['accepted'],'reviewer':d['reviewer'],
                'elapsedMillis':int((self.clock()-self.started)*1000),'deadlineMs':300000}
        need(result['elapsedMillis']<result['deadlineMs'],'V2_SEMANTIC_REVIEW_REQUIRED')
        self.write(self.directory/(self.key+'.receipt.json'),result)
        if self.prepared is not None:
            need(self.clock()<self.end,'V2_SEMANTIC_REVIEW_REQUIRED')
            self.write(self.directory/(self.key+'.completion.json'),{'evidenceSha256':self.key,
                'elapsedMillis':int((self.clock()-self.started)*1000),'deadlineMs':300000})
            need(self.clock()<self.end,'V2_SEMANTIC_REVIEW_REQUIRED')
        if self.prepared is not None:
            self.safe_quality_failure=(a['quality']=='FAIL' and a['safeToContinue'] is True
                                       and a['checks']['authorization']['verdict']=='PASS')
        else:
            self.safe_quality_failure=(d['accepted'] is False and d['notes'].startswith('SAFE_QUALITY_FAIL:')
                                       and bool(d['notes'][len('SAFE_QUALITY_FAIL:'):].strip()))
        return d['accepted']

if __name__=='__main__':
    # Output is a bounded PRIVATE reviewer packet, never API keys or raw provider inputs.
    sys.stdout.reconfigure(encoding='utf8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('op',choices=('brief','ready','await-packet','open','submit','status'))
    p.add_argument('--directory',required=True,type=Path);p.add_argument('--key',required=True)
    p.add_argument('--arm-sha');p.add_argument('--template-sha');p.add_argument('--reviewer');p.add_argument('--assessment',type=Path);a=p.parse_args()
    if a.op=='brief':result=brief(a.directory,a.key)
    elif a.op=='ready':ready(a.directory,a.key,a.arm_sha,a.reviewer);result={'status':'READY_NOT_REVIEWED'}
    elif a.op=='await-packet':result=await_packet(a.directory,a.key,a.template_sha,a.reviewer)
    elif a.op=='open':result=read(a.directory/(a.key+'.request.private.json'))
    elif a.op=='submit':result=submit(a.directory,a.key,read(a.assessment))
    else:
        result={'receipt':read(a.directory/(a.key+'.receipt.json')) if (a.directory/(a.key+'.receipt.json')).exists() else None,
                'failed':(a.directory/(a.key+'.failure.json')).exists(),'decisionIsNotReceipt':True}
    print(json.dumps(result,ensure_ascii=False))
