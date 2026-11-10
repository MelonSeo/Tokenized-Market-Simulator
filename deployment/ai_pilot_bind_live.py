"""Owned fixture authenticated binding; no provider, key, signing or old evidence access."""
import json,sys
from pathlib import Path
from ai_pilot_fixture import bind_fixture
from ai_pilot_channel import prepare_unpaid
from ai_pilot_coordinator import workload
from ai_pilot_http_adapter import PilotAuthenticatedHttp

def bind(descriptor,run):
    d=json.loads(Path(descriptor).read_text(encoding='utf8'))
    if set(d)!={'origin','accounts','recipe'}:raise ValueError('V2_BIND_DESCRIPTOR')
    adapter=PilotAuthenticatedHttp(d['origin'],d['accounts'],private_control=None,fingerprint=None,review=None,budget_snapshot=None)
    b=bind_fixture(d['recipe'],adapter.request);prepare_unpaid(run,b)
    for name,value in [('binding.private.json',b),('rows.private.json',workload(b))]:
        with (Path(run)/name).open('x',encoding='utf8') as f:json.dump(value,f,ensure_ascii=False)
    print('V2_AUTHENTICATED_FIXTURE_BOUND')

if __name__=='__main__':bind(sys.argv[1],sys.argv[2])
