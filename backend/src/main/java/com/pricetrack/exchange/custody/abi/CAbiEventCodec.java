package com.pricetrack.exchange.custody.abi;

import static com.pricetrack.exchange.custody.abi.CAbiEventModels.*;
import java.math.BigInteger;
import java.util.*;

/** Exact static C event ABI, topic constants from independent local cast keccak 1.5.1.
 * Does not establish emitter deployment identity, log order, economics, signatures, or canonical receipt. */
public final class CAbiEventCodec {
    public enum Type {
        TRANSFER(EmitterKind.TOKEN,2,1,"ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
        APPROVAL(EmitterKind.TOKEN,2,1,"8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"),
        DEPOSITED(EmitterKind.VAULT,1,1,"2da466a7b24304f47e87fa2e1e5a81b9831ce54fec19055ce277ca2f39ba42c4"),
        WITHDRAWN(EmitterKind.VAULT,1,1,"7084f5476618d8e60b11ef0d7d3f06914655adb8793e28ff7f018d4c76d505d5"),
        HOUSE_FUNDED(EmitterKind.VAULT,1,1,"68b5652e3b29601ecd57e94e383b10ba5e2f2726963da06839bb31da7e5dd0d9"),
        COLLATERAL_LOCKED(EmitterKind.VAULT,3,2,"ffe4f8c1183cc43d5389463431ce739b36c911ab0809cc2bb8bbf2b71f639ff0"),
        POSITION_SETTLED(EmitterKind.VAULT,3,5,"f0d0093595ccb214443df0640d9b24560ffe0a1b34118fabc9b7bcfc425ca909"),
        PROFIT_CLAIM_CREATED(EmitterKind.VAULT,3,2,"fdf4d26e970338cb75a69697dcd0876a1279669b1df41a6da01e2226ad327187"),
        PROFIT_CLAIM_PAID(EmitterKind.VAULT,2,2,"ea87e8a9c18a06cd8bff5490efeb29080edd7ae21403c90d370da474d6353c73"),
        USER_INTENT_EXECUTED(EmitterKind.CONTROLLER,3,5,"bde67fc07d3d2ad531b5d828d3b3d01d60cb2b2c3645e11729ba41d362234c76"),
        RISK_EXECUTED(EmitterKind.CONTROLLER,3,2,"4260c65c4e668ba8f33b7c425c01f6603b24ffcae2394bff67a46107345e8c43"),
        NONCE_CANCELLED(EmitterKind.CONTROLLER,1,1,"7b644e7863e42132ae13a74c64f69b27bbc894b15f690a353b2aad750c7c334a"),
        NEW_POSITIONS_PAUSE_CHANGED(EmitterKind.CONTROLLER,0,1,"0a4198dd4f22e2bab3c2cf34b8a31f3d2de46956a4b7f75c66b577f5977c4bc3"),
        OPERATION_APPLIED(EmitterKind.RULES,2,9,"5997542be234c7715122caa3d3458cb56eccb19559f19caa0b299736a2215fb7"),
        FUTURES_REPORT_CONSUMED(EmitterKind.RULES,1,2,"53658988139a5c832ed50acd661beaa87482d2cc105cb640afc2c34dde4636c0"),
        EIP712_DOMAIN_CHANGED(EmitterKind.CONTROLLER,0,0,"0a6387c9ea3628b88a633bb4f3b151770f70085117a15f9bf3787cda53f13d31");
        public final EmitterKind emitterKind;
        public final int indexedFields,dataWords;
        public final String topic0;
        Type(EmitterKind kind,int indexed,int words,String topic) {emitterKind=kind;indexedFields=indexed;dataWords=words;topic0="0x"+topic;}
        public boolean accepts(EmitterKind kind) {
            return kind==emitterKind||(this==EIP712_DOMAIN_CHANGED&&kind==EmitterKind.RULES);
        }
    }

    public Event decode(EmitterKind expectedKind,Log log) {
        if(expectedKind==null||log==null)throw invalid("NULL");
        Type type=Arrays.stream(Type.values()).filter(t->t.topic0.equals(log.topics().getFirst())).findFirst().orElseThrow(()->invalid("TOPIC0"));
        if(!type.accepts(expectedKind))throw invalid("EMITTER_KIND");
        if(log.topics().size()!=type.indexedFields+1)throw invalid("TOPIC_COUNT");
        if(log.data().length()!=2+type.dataWords*64)throw invalid("DATA_LENGTH");
        List<String> t=log.topics(); String d=log.data();
        return switch(type) {
            case TRANSFER -> new Transfer(addressTopic(t.get(1)),addressTopic(t.get(2)),u(d,0));
            case APPROVAL -> new Approval(addressTopic(t.get(1)),addressTopic(t.get(2)),u(d,0));
            case DEPOSITED -> new Deposited(addressTopic(t.get(1)),u(d,0));
            case WITHDRAWN -> new Withdrawn(addressTopic(t.get(1)),u(d,0));
            case HOUSE_FUNDED -> new HouseFunded(addressTopic(t.get(1)),u(d,0));
            case COLLATERAL_LOCKED -> new CollateralLocked(t.get(1),t.get(2),addressTopic(t.get(3)),u(d,0),u(d,1));
            case POSITION_SETTLED -> new PositionSettled(t.get(1),t.get(2),addressTopic(t.get(3)),u(d,0),s(d,1),u(d,2),u(d,3),u(d,4));
            case PROFIT_CLAIM_CREATED -> new ProfitClaimCreated(topicUint(t.get(1)),addressTopic(t.get(2)),t.get(3),h(d,0),u(d,1));
            case PROFIT_CLAIM_PAID -> new ProfitClaimPaid(topicUint(t.get(1)),addressTopic(t.get(2)),u(d,0),u(d,1));
            case USER_INTENT_EXECUTED -> new CUserIntentExecuted(t.get(1),t.get(2),addressTopic(t.get(3)),h(d,0),h(d,1),u(d,2),u(d,3),bool(d,4));
            case RISK_EXECUTED -> new CRiskExecuted(t.get(1),t.get(2),addressTopic(t.get(3)),h(d,0),u(d,1));
            case NONCE_CANCELLED -> new CNonceCancelled(addressTopic(t.get(1)),u(d,0));
            case NEW_POSITIONS_PAUSE_CHANGED -> new NewPositionsPauseChanged(bool(d,0));
            case OPERATION_APPLIED -> new OperationApplied(t.get(1),t.get(2),u(d,0),u(d,1),u(d,2),u(d,3),s(d,4),s(d,5),u(d,6),u(d,7),u(d,8));
            case FUTURES_REPORT_CONSUMED -> new FuturesReportConsumed(t.get(1),u(d,0),u(d,1));
            case EIP712_DOMAIN_CHANGED -> new EIP712DomainChanged();
        };
    }

    public Log encode(String emitter,Event event) {
        if(event==null)throw invalid("NULL");
        Type type=type(event); List<String> topics=new ArrayList<>();topics.add(type.topic0);String data;
        if(event instanceof Transfer e) {topics.add(aw(e.from()));topics.add(aw(e.to()));data=words(e.value());}
        else if(event instanceof Approval e) {topics.add(aw(e.owner()));topics.add(aw(e.spender()));data=words(e.value());}
        else if(event instanceof Deposited e) {topics.add(aw(e.owner()));data=words(e.amount());}
        else if(event instanceof Withdrawn e) {topics.add(aw(e.owner()));data=words(e.amount());}
        else if(event instanceof HouseFunded e) {topics.add(aw(e.contributor()));data=words(e.amount());}
        else if(event instanceof CollateralLocked e) {topics.add(e.operationId());topics.add(e.positionId());topics.add(aw(e.owner()));data=words(e.delta(),e.lockedAfter());}
        else if(event instanceof PositionSettled e) {topics.add(e.operationId());topics.add(e.positionId());topics.add(aw(e.owner()));
            data=words(e.collateral(),e.realizedPnl(),e.badDebt(),e.paidProfit(),e.claimId());}
        else if(event instanceof ProfitClaimCreated e) {topics.add(word(e.claimId()));topics.add(aw(e.owner()));topics.add(e.positionId());data=e.operationId()+body(e.amount());}
        else if(event instanceof ProfitClaimPaid e) {topics.add(word(e.claimId()));topics.add(aw(e.owner()));data=words(e.paidAmount(),e.remainingAmount());}
        else if(event instanceof CUserIntentExecuted e) {topics.add(e.operationId());topics.add(e.positionId());topics.add(aw(e.owner()));
            data=e.intentDigest()+e.reportDigest().substring(2)+body(e.action())+body(e.nonce())+body(e.direct()?BigInteger.ONE:BigInteger.ZERO);}
        else if(event instanceof CRiskExecuted e) {topics.add(e.operationId());topics.add(e.positionId());topics.add(aw(e.owner()));data=e.reportDigest()+body(e.action());}
        else if(event instanceof CNonceCancelled e) {topics.add(aw(e.owner()));data=words(e.nonce());}
        else if(event instanceof NewPositionsPauseChanged e) data=words(e.paused()?BigInteger.ONE:BigInteger.ZERO);
        else if(event instanceof OperationApplied e) {topics.add(e.operationId());topics.add(e.positionId());data=words(e.action(),e.version(),e.priceE8(),e.collateral(),e.unrealized(),e.realized(),e.badDebt(),e.callEpoch(),e.deadline());}
        else if(event instanceof FuturesReportConsumed e) {topics.add(e.operationId());data=words(e.observedAt(),e.validUntil());}
        else if(event instanceof EIP712DomainChanged) data="0x";
        else throw invalid("TYPE");
        return new Log(emitter,topics,data);
    }

    public static Type type(Event event) {
        if(event instanceof Transfer)return Type.TRANSFER;
        if(event instanceof Approval)return Type.APPROVAL;
        if(event instanceof Deposited)return Type.DEPOSITED;
        if(event instanceof Withdrawn)return Type.WITHDRAWN;
        if(event instanceof HouseFunded)return Type.HOUSE_FUNDED;
        if(event instanceof CollateralLocked)return Type.COLLATERAL_LOCKED;
        if(event instanceof PositionSettled)return Type.POSITION_SETTLED;
        if(event instanceof ProfitClaimCreated)return Type.PROFIT_CLAIM_CREATED;
        if(event instanceof ProfitClaimPaid)return Type.PROFIT_CLAIM_PAID;
        if(event instanceof CUserIntentExecuted)return Type.USER_INTENT_EXECUTED;
        if(event instanceof CRiskExecuted)return Type.RISK_EXECUTED;
        if(event instanceof CNonceCancelled)return Type.NONCE_CANCELLED;
        if(event instanceof NewPositionsPauseChanged)return Type.NEW_POSITIONS_PAUSE_CHANGED;
        if(event instanceof OperationApplied)return Type.OPERATION_APPLIED;
        if(event instanceof FuturesReportConsumed)return Type.FUTURES_REPORT_CONSUMED;
        if(event instanceof EIP712DomainChanged)return Type.EIP712_DOMAIN_CHANGED;
        throw invalid("TYPE");
    }
    private static BigInteger topicUint(String value) {return new BigInteger(value.substring(2),16);}
    private static String addressTopic(String value) {if(!value.substring(2,26).equals("0".repeat(24)))throw invalid("ADDRESS_PADDING");return "0x"+value.substring(26);}
    private static String h(String data,int word) {return "0x"+data.substring(2+word*64,2+(word+1)*64);}
    private static BigInteger u(String data,int word) {return topicUint(h(data,word));}
    private static BigInteger s(String data,int word) {BigInteger value=u(data,word);return value.testBit(255)?value.subtract(BigInteger.ONE.shiftLeft(256)):value;}
    private static boolean bool(String data,int word) {BigInteger value=u(data,word);if(value.compareTo(BigInteger.ONE)>0)throw invalid("BOOL");return value.signum()!=0;}
    private static String aw(String address) {return "0x"+"0".repeat(24)+address.substring(2);}
    private static String word(BigInteger value) {return "0x"+body(value);}
    private static String words(BigInteger... values) {StringBuilder result=new StringBuilder("0x");for(BigInteger value:values)result.append(body(value));return result.toString();}
    private static String body(BigInteger value) {String hex=(value.signum()<0?value.add(BigInteger.ONE.shiftLeft(256)):value).toString(16);return "0".repeat(64-hex.length())+hex;}
}
