"""Bounded host-loopback readiness for copied AI fixtures; read-only RPC, no retries of transactions."""
import json
import socket
import time


def probe(port,timeout,*,clock=time.monotonic):
    end=clock()+timeout;connection=None;stage='CONNECT'
    def remaining():
        value=end-clock()
        if value<=0:raise TimeoutError()
        return value
    def receive(size):
        connection.settimeout(remaining());chunk=connection.recv(size);remaining()
        if not chunk:raise ValueError()
        return chunk
    try:
        connection=socket.create_connection(('127.0.0.1',port),timeout=remaining());remaining();stage='REQUEST'
        body=json.dumps({'jsonrpc':'2.0','id':1,'method':'eth_chainId','params':[]},separators=(',',':')).encode('ascii')
        request=(f'POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode('ascii')+body
        connection.settimeout(remaining());connection.sendall(request);remaining();stage='HEADERS';raw=b''
        while b'\r\n\r\n' not in raw:
            raw+=receive(4096)
            if len(raw)>16384:raise ValueError()
        header,body=raw.split(b'\r\n\r\n',1);lines=header.decode('ascii').split('\r\n')
        if lines[0].split()[:2] not in (['HTTP/1.1','200'],['HTTP/1.0','200']):raise ValueError()
        headers={}
        for line in lines[1:]:
            key,value=line.split(':',1);key=key.strip().lower()
            if key in headers:raise ValueError()
            headers[key]=value.strip()
        # This local Anvil readiness probe accepts only bounded Content-Length framing.
        if 'transfer-encoding' in headers:raise ValueError()
        length=int(headers['content-length'])
        if not 0<length<=65536 or len(body)>length:raise ValueError()
        stage='BODY'
        while len(body)<length:body+=receive(min(4096,length-len(body)))
        decoded=json.loads(body)
        remaining()
        if not isinstance(decoded,dict):raise ValueError()
        if type(decoded.get('id')) is not int or decoded.get('id')!=1 or decoded.get('result')!='0x7a69' or 'error' in decoded:return {'ok':False,'stage':stage,'code':'WRONG_CHAIN_RESPONSE'}
        return {'ok':True,'stage':'BODY','code':'CHAIN_ID_OK'}
    except (socket.timeout,TimeoutError):return {'ok':False,'stage':stage,'code':'TIMEOUT'}
    except ConnectionRefusedError:return {'ok':False,'stage':stage,'code':'CONNECTION_REFUSED'}
    except OSError:return {'ok':False,'stage':stage,'code':'CONNECTION_FAILURE'}
    except (ValueError,KeyError):return {'ok':False,'stage':stage,'code':'INVALID_RESPONSE'}
    finally:
        if connection is not None:connection.close()


def await_rpc(port,seconds=20,*,send=probe,clock=time.monotonic,sleep=time.sleep):
    if type(port) is not int or not 30000<=port<=49000 or not 0<seconds<=30:raise ValueError('INVALID_READINESS_SCOPE')
    end=clock()+seconds;rows=[]
    while clock()<end:
        start=clock();row=send(port,min(2,end-start));row={**row,'elapsedMs':round((clock()-start)*1000)};rows.append(row)
        if clock()>=end:
            row.update({'ok':False,'code':'DEADLINE_EXCEEDED'});break
        if row['ok']:return {'ready':True,'attempts':rows,'scope':'HOST_LOOPBACK_ETH_CHAIN_ID_ONLY_NOT_PRODUCT_RPC_RECOVERY'}
        sleep(min(.25,max(0,end-clock())))
    return {'ready':False,'attempts':rows,'scope':'HOST_LOOPBACK_ETH_CHAIN_ID_ONLY_NOT_PRODUCT_RPC_RECOVERY'}
