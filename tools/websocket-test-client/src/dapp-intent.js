// Positive integer units only. No floating-point arithmetic in consent conditions.
export function units(value, decimals, limit = null, zero = false) {
  if (typeof value !== 'string' || !new RegExp(`^\\d{1,30}(?:\\.\\d{1,${decimals}})?$`).test(value)) throw new Error('금액은 지수 표기 없이 정확한 소수 문자열로 입력하세요.');
  const [whole, fraction = ''] = value.split('.'); const n = BigInt(whole) * 10n ** BigInt(decimals) + BigInt(fraction.padEnd(decimals, '0'));
  if ((!zero && n === 0n) || (limit !== null && n >= limit)) throw new Error('허용 단위/범위를 벗어났습니다.');
  return n;
}
export function decimal(n, places) { n=BigInt(n); const scale=10n**BigInt(places); return `${n/scale}${n%scale?'.'+(n%scale).toString().padStart(places,'0').replace(/0+$/,''):''}`; }
export function integer(v) { if(typeof v!=='string'||!/^\d+$/.test(v)||BigInt(v)>=2n**256n)throw new Error('정확한 정수 문자열이 필요합니다.');return BigInt(v); }
export function address(v) { if(typeof v!=='string'||!/^0x[0-9a-fA-F]{40}$/.test(v)||/^0x0{40}$/i.test(v))throw new Error('유효한 지갑 주소가 아닙니다.');return v.toLowerCase(); }

// Keccak-256 used ONLY to compare IDs/accountRef with the server's typed data.
// Wallet and Solidity, not this function, sign/authorize the transaction.
const MASK=(1n<<64n)-1n, ROT=[0,1,62,28,27,36,44,6,55,20,3,10,43,25,39,41,45,15,21,8,18,2,61,56,14];
const RC=[1n,0x8082n,0x800000000000808an,0x8000000080008000n,0x808bn,0x80000001n,0x8000000080008081n,0x8000000000008009n,0x8an,0x88n,0x80008009n,0x8000000an,0x8000808bn,0x800000000000008bn,0x8000000000008089n,0x8000000000008003n,0x8000000000008002n,0x8000000000000080n,0x800an,0x800000008000000an,0x8000000080008081n,0x8000000000008080n,0x80000001n,0x8000000080008008n];
const rol=(x,n)=>n?((x<<BigInt(n))|(x>>BigInt(64-n)))&MASK:x;
export function keccak(bytes) {
  const data=Array(Math.ceil((bytes.length+1)/136)*136).fill(0);data.splice(0,bytes.length,...bytes);data[bytes.length]=1;data[data.length-1]|=128;const s=Array(25).fill(0n);
  for(let off=0;off<data.length;off+=136){for(let j=0;j<136;j++)s[Math.floor(j/8)]^=BigInt(data[off+j])<<BigInt(8*(j%8));
    for(const rc of RC){const c=Array.from({length:5},(_,x)=>s[x]^s[x+5]^s[x+10]^s[x+15]^s[x+20]);const d=c.map((_,x)=>c[(x+4)%5]^rol(c[(x+1)%5],1));for(let j=0;j<25;j++)s[j]^=d[j%5];
      const b=Array(25).fill(0n);for(let x=0;x<5;x++)for(let y=0;y<5;y++)b[y+5*((2*x+3*y)%5)]=rol(s[x+5*y],ROT[x+5*y]);
      for(let x=0;x<5;x++)for(let y=0;y<5;y++)s[x+5*y]=(b[x+5*y]^((~b[(x+1)%5+5*y])&b[(x+2)%5+5*y]))&MASK;s[0]^=rc;
    }
  }return '0x'+Array.from({length:32},(_,j)=>Number((s[Math.floor(j/8)]>>BigInt(8*(j%8)))&255n).toString(16).padStart(2,'0')).join('');
}
export const hashText=s=>keccak(new TextEncoder().encode(s));
const bytes=hex=>Uint8Array.from(hex.match(/../g).map(x=>parseInt(x,16)));
export function accountRef(owner,symbol) {return keccak(bytes(hashText('TMS_FUTURES_WALLET_ACCOUNT_V1').slice(2)+address(owner).slice(2).padStart(64,'0')+symbol.slice(2)));}
export const intentFields=[['owner','address'],['relayer','address'],['operationId','bytes32'],['positionId','bytes32'],['accountRef','bytes32'],['symbolHash','bytes32'],['action','uint8'],['expectedVersion','uint256'],['expectedCallEpoch','uint256'],['side','uint8'],['leverage','uint8'],['entryPriceE8','uint256'],['collateralBefore','uint256'],['collateralAfter','uint256'],['priceMinE8','uint256'],['priceMaxE8','uint256'],['quantityMin','uint256'],['quantityMax','uint256'],['nonce','uint256'],['issuedAt','uint256'],['deadline','uint256'],['policyHash','bytes32']];
export const domainFields=[['name','string'],['version','string'],['chainId','uint256'],['verifyingContract','address']];
const types=a=>a.map(([name,type])=>({name,type}));
export function conditions(input, selected = null) {
  const action=input.action;if(!['OPEN','TOP_UP','CLOSE'].includes(action)||!['LONG','SHORT'].includes(input.side)||!['2','5','10'].includes(String(input.leverage)))throw new Error('동작/방향/배수를 확인하세요.');
  const margin=action==='CLOSE'?0n:units(input.margin,18,10n**30n),lo=units(input.minPrice,8,10n**20n),hi=units(input.maxPrice,8,10n**20n);
  if(lo>hi)throw new Error('최소 가격은 최대 가격보다 클 수 없습니다.');
  const ttl=integer(input.ttl);if(ttl<1n||ttl>120n)throw new Error('승인 기한은 1~120초입니다.');
  let qmin,qmax,before=0n,entry=0n,version='0',epoch='0';
  if(action==='OPEN'){qmin=margin*BigInt(input.leverage)*10n**8n/hi;qmax=margin*BigInt(input.leverage)*10n**8n/lo;}
  else {if(!selected||selected.position.status!=='OPEN'||selected.position.pendingOperation)throw new Error('현재 사용자의 실행 가능한 B-v2 포지션이 필요합니다.');const p=selected.position;if(p.side!==input.side||String(p.leverage)!==String(input.leverage))throw new Error('포지션 조건이 다릅니다.');before=units(p.collateral,18,10n**30n);entry=units(p.entryPrice,8,10n**20n);qmin=qmax=units(p.quantity,18,10n**30n);version=integer(selected.positionVersion).toString();epoch=integer(selected.callEpoch).toString();}
  if(qmin<=0n||qmax>=10n**30n||before+margin>=10n**30n)throw new Error('수량/담보 허용 범위를 벗어났습니다.');
  return {request:{action,positionId:action==='OPEN'?null:selected.position.id,expectedVersion:version,side:input.side,leverage:Number(input.leverage),margin:margin.toString(),priceMinE8:lo.toString(),priceMaxE8:hi.toString(),quantityMin:qmin.toString(),quantityMax:qmax.toString(),ttlSeconds:Number(ttl)},before:before.toString(),after:(before+margin).toString(),entry:entry.toString(),epoch};
}
export function validateDraft(draft,cfg,owner,nonce,approved,now=Math.floor(Date.now()/1000)) {
  const td=draft?.typedData,m=td?.message,r=approved.request;
  const sameFields=(actual,expected)=>Array.isArray(actual)&&actual.length===expected.length&&actual.every((f,i)=>Object.keys(f).sort().join()==='name,type'&&f.name===expected[i][0]&&f.type===expected[i][1]);
  if(!td||td.primaryType!=='FuturesUserIntentV2'||Object.keys(td.types||{}).sort().join()!=='EIP712Domain,FuturesUserIntentV2'||!sameFields(td.types.EIP712Domain,domainFields)||!sameFields(td.types.FuturesUserIntentV2,intentFields))throw new Error('지원하지 않는 typed data 계약입니다.');
  for(const id of [draft.id,draft.operationId,draft.positionId])if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id))throw new Error('잘못된 주문 식별자입니다.');
  for(const h of [cfg.symbolHash,cfg.policyHash])if(!/^0x[0-9a-f]{64}$/.test(h))throw new Error('잘못된 정책 식별자입니다.');
  const domain=td.domain;if(Object.keys(domain||{}).sort().join()!=='chainId,name,verifyingContract,version'||domain.name!=='TokenizedMarketFuturesUserIntent'||domain.version!=='2'||integer(domain.chainId)!==integer(cfg.chainId)||address(domain.verifyingContract)!==address(cfg.controller))throw new Error('서명 domain이 현재 배포와 다릅니다.');
  if(Object.keys(m||{}).sort().join()!==intentFields.map(x=>x[0]).sort().join())throw new Error('서명 필드가 다릅니다.');
  const expected={owner:address(owner),relayer:address(cfg.relayer),operationId:hashText(draft.operationId),positionId:hashText(draft.positionId),accountRef:accountRef(owner,cfg.symbolHash),symbolHash:cfg.symbolHash,action:String({OPEN:0,TOP_UP:1,CLOSE:4}[r.action]),expectedVersion:r.expectedVersion,expectedCallEpoch:approved.epoch,side:r.side==='LONG'?'0':'1',leverage:String(r.leverage),entryPriceE8:approved.entry,collateralBefore:approved.before,collateralAfter:approved.after,priceMinE8:r.priceMinE8,priceMaxE8:r.priceMaxE8,quantityMin:r.quantityMin,quantityMax:r.quantityMax,nonce:integer(nonce).toString(),policyHash:cfg.policyHash};
  for(const [name,type]of intentFields){if(name==='issuedAt'||name==='deadline')continue;const actual=type==='address'?address(m[name]):type.startsWith('uint')?integer(m[name]).toString():m[name];if(actual!==expected[name])throw new Error(`확인한 조건과 서명 내용이 다릅니다: ${name}`);}
  if(r.action!=='OPEN'&&draft.positionId!==r.positionId)throw new Error('대상 포지션이 다릅니다.');
  if(integer(m.deadline)-integer(m.issuedAt)!==BigInt(r.ttlSeconds)||integer(m.issuedAt)>BigInt(now+2)||integer(m.deadline)<=BigInt(now))throw new Error('사용자 승인 기한이 만료됐거나 유효하지 않습니다.');
  return structuredClone(td);
}
