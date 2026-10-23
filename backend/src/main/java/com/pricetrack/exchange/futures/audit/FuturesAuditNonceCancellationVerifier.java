package com.pricetrack.exchange.futures.audit;

import java.math.BigInteger;
import java.util.*;
import org.web3j.crypto.*;
import org.web3j.crypto.transaction.type.*;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

/**
 * Pure, bounded verification of supplied direct type0/type2 cancelNonce evidence only. No RPC, DB,
 * key custody or financial mutation. The configured target is trusted input, NOT proof that its code
 * was independently checked. Header canonicality, historical deployment/code, complete range coverage
 * and stable-cut checks remain the caller's responsibility. VERIFIED is NOT an overall audit MATCH.
 * This verifier is deliberately not connected to the production observer or nonce fold.
 */
public final class FuturesAuditNonceCancellationVerifier {
    public static final int MAX_INPUT_BYTES=4096,MAX_ACCESS_ENTRIES=16,MAX_KEYS_PER_ENTRY=32,
            MAX_TOTAL_KEYS=256,MAX_HEADER_TRANSACTIONS=1024,MAX_LOGS=50;
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,MAX=ONE.shiftLeft(256).subtract(ONE);
    private static final BigInteger N=new BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",16);
    private static final String SELECTOR=Hash.sha3String("cancelNonce(uint256)").substring(0,10);
    private static final String TOPIC=Hash.sha3String("UserNonceCancelledV2(address,uint256)");
    public enum Verdict { VERIFIED, MISMATCH, INCONCLUSIVE }
    public enum Reason { CANCEL_SUCCESS_EVIDENCE, CANCEL_REVERT_CAUSE_UNDETERMINED, INPUT_REQUIRED,
        TARGET_UNSUPPORTED, TYPE_UNSUPPORTED, ABI_UNSUPPORTED, BOUNDED_INPUT_LIMIT, EVIDENCE_MALFORMED,
        EVIDENCE_REQUIRED, SIGNATURE_CONFLICT, HASH_CONFLICT, SENDER_CONFLICT, CHAIN_CONFLICT,
        TARGET_CONFLICT, RECEIPT_LINK_CONFLICT, HEADER_MEMBERSHIP_CONFLICT, STATUS_UNSUPPORTED,
        VALUE_CONFLICT, EVENT_CONFLICT, NONCE_OVERFLOW, REMOVED_EVENT }
    /** Safe output. expectedNonce is calldata, not proof of pre-transaction historical nonce. */
    public record Cancellation(BigInteger chainId,String controller,String owner,BigInteger blockNumber,
            String blockHash,String transactionHash,BigInteger transactionIndex,BigInteger logIndex,
            BigInteger expectedNonce,boolean successful) {}
    public record Verification(Verdict verdict,Reason reason,Cancellation cancellation) {}
    private static final class Rejected extends RuntimeException {
        final Verdict verdict;final Reason reason;
        Rejected(Verdict v,Reason r){super(r.name(),null,false,false);verdict=v;reason=r;}
    }
    private FuturesAuditNonceCancellationVerifier(){}
    public static Verification verify(FuturesAuditDeployment target,Transaction tx,TransactionReceipt receipt,EthBlock.Block header){
        try{return checked(target,tx,receipt,header);}
        catch(Rejected rejected){return new Verification(rejected.verdict,rejected.reason,null);}
        catch(RuntimeException malformed){return new Verification(Verdict.INCONCLUSIVE,Reason.EVIDENCE_MALFORMED,null);}
    }
    private static Verification checked(FuturesAuditDeployment target,Transaction tx,TransactionReceipt receipt,EthBlock.Block header){
        need(target!=null&&tx!=null&&receipt!=null&&header!=null,Reason.INPUT_REQUIRED);
        need(target.route()==FuturesAuditDeployment.Route.B_V2,Reason.TARGET_UNSUPPORTED);
        BigInteger chain=target.chain().chainId();need(chain.signum()>0&&chain.bitLength()<=63,Reason.TARGET_UNSUPPORTED);
        String type=tx.getType();need("0x0".equals(type)||"0x2".equals(type),Reason.TYPE_UNSUPPORTED);boolean typed="0x2".equals(type);
        String input=tx.getInput();need(input!=null,Reason.EVIDENCE_REQUIRED);
        limit(input.length()<=2+MAX_INPUT_BYTES*2);
        need(input.matches("0x(?:[0-9a-fA-F]{2})*"),Reason.EVIDENCE_MALFORMED);
        need(input.length()==74&&input.regionMatches(true,0,SELECTOR,0,10),Reason.ABI_UNSUPPORTED);
        BigInteger expected=new BigInteger(input.substring(10),16);
        BigInteger nonce=q(tx.getNonceRaw()),gas=q(tx.getGasRaw()),value=q(tx.getValueRaw());need(gas.signum()>0,Reason.EVIDENCE_MALFORMED);
        address(tx.getFrom());address(tx.getTo());hash(tx.getHash());hash(tx.getBlockHash());
        BigInteger number=q(tx.getBlockNumberRaw()),index=q(tx.getTransactionIndexRaw());
        BigInteger r=hexWord(tx.getR()),s=hexWord(tx.getS());
        conflict(r.signum()>0&&r.compareTo(N)<0&&s.signum()>0&&s.compareTo(N.shiftRight(1))<=0,Reason.SIGNATURE_CONFLICT);
        long v=tx.getV();conflict(v>=0,Reason.SIGNATURE_CONFLICT);
        if(tx.getChainIdRaw()!=null)conflict(q(tx.getChainIdRaw()).equals(chain),Reason.CHAIN_CONFLICT);
        RawTransaction unsigned;
        if(typed){
            need(tx.getChainIdRaw()!=null,Reason.EVIDENCE_REQUIRED);
            conflict(v==0||v==1,Reason.SIGNATURE_CONFLICT);
            if(tx.getyParity()!=null)conflict(q(tx.getyParity()).equals(BigInteger.valueOf(v)),Reason.SIGNATURE_CONFLICT);
            var max=q(tx.getMaxFeePerGasRaw());var priority=q(tx.getMaxPriorityFeePerGasRaw());
            need(priority.compareTo(max)<=0,Reason.EVIDENCE_MALFORMED);
            var supplied=tx.getAccessList();need(supplied!=null,Reason.EVIDENCE_REQUIRED);limit(supplied.size()<=MAX_ACCESS_ENTRIES);
            var access=new ArrayList<org.web3j.crypto.AccessListObject>();int total=0;
            for(var entry:supplied){need(entry!=null&&entry.getStorageKeys()!=null,Reason.EVIDENCE_MALFORMED);address(entry.getAddress());
                limit(entry.getStorageKeys().size()<=MAX_KEYS_PER_ENTRY);total+=entry.getStorageKeys().size();limit(total<=MAX_TOTAL_KEYS);
                for(var key:entry.getStorageKeys())hash(key);
                access.add(new org.web3j.crypto.AccessListObject(entry.getAddress(),List.copyOf(entry.getStorageKeys())));
            }
            unsigned=RawTransaction.createTransaction(chain.longValueExact(),nonce,gas,tx.getTo(),value,input,priority,max,access);
        }else{
            // EIP-155 v must encode exactly this chain; v27/28 unprotected legacy is outside coverage.
            need(v>=35,Reason.TYPE_UNSUPPORTED);
            conflict(BigInteger.valueOf(v).subtract(BigInteger.valueOf(35)).divide(BigInteger.TWO).equals(chain),Reason.CHAIN_CONFLICT);
            unsigned=RawTransaction.createTransaction(nonce,q(tx.getGasPriceRaw()),gas,tx.getTo(),value,input);
        }
        BigInteger encodedV=BigInteger.valueOf(typed?v+27:v);
        var signature=new Sign.SignatureData(Numeric.toBytesPadded(encodedV,Math.max(1,(encodedV.bitLength()+7)/8)),Numeric.toBytesPadded(r,32),Numeric.toBytesPadded(s,32));
        byte[] encoded=TransactionEncoder.encode(unsigned,signature);
        conflict(Hash.sha3(Numeric.toHexString(encoded)).equalsIgnoreCase(tx.getHash()),Reason.HASH_CONFLICT);
        String sender;
        try{sender=((SignedRawTransaction)TransactionDecoder.decode(Numeric.toHexString(encoded))).getFrom();}
        catch(Exception invalid){throw new Rejected(Verdict.MISMATCH,Reason.SIGNATURE_CONFLICT);}
        conflict(same(sender,tx.getFrom()),Reason.SENDER_CONFLICT);
        // Other top-level destinations may cause internal cancel events; that form is NOT supported.
        need(same(tx.getTo(),target.chain().engine()),Reason.TARGET_UNSUPPORTED);
        address(receipt.getFrom());address(receipt.getTo());hash(receipt.getTransactionHash());hash(receipt.getBlockHash());hash(header.getHash());
        var receiptNumber=q(receipt.getBlockNumberRaw());var receiptIndex=q(receipt.getTransactionIndexRaw());var headerNumber=q(header.getNumberRaw());
        conflict(same(receipt.getFrom(),sender)&&same(receipt.getTo(),tx.getTo())&&same(receipt.getTransactionHash(),tx.getHash())
                &&same(receipt.getBlockHash(),tx.getBlockHash())&&same(header.getHash(),tx.getBlockHash())
                &&number.equals(receiptNumber)&&number.equals(headerNumber)&&index.equals(receiptIndex),Reason.RECEIPT_LINK_CONFLICT);
        var transactions=header.getTransactions();need(transactions!=null,Reason.EVIDENCE_REQUIRED);limit(transactions.size()<=MAX_HEADER_TRANSACTIONS);
        conflict(index.compareTo(BigInteger.valueOf(transactions.size()))<0,Reason.HEADER_MEMBERSHIP_CONFLICT);
        var entry=transactions.get(index.intValueExact());need(entry!=null,Reason.EVIDENCE_REQUIRED);Object member=entry.get();
        String memberHash=member instanceof String str?str:member instanceof Transaction t?t.getHash():null;
        hash(memberHash);conflict(same(memberHash,tx.getHash()),Reason.HEADER_MEMBERSHIP_CONFLICT);
        var logs=receipt.getLogs();need(logs!=null,Reason.EVIDENCE_REQUIRED);limit(logs.size()<=MAX_LOGS);
        boolean success="0x1".equals(receipt.getStatus());need(success||"0x0".equals(receipt.getStatus()),Reason.STATUS_UNSUPPORTED);
        BigInteger logIndex=null;
        if(success){
            conflict(value.signum()==0,Reason.VALUE_CONFLICT);conflict(!expected.equals(MAX),Reason.NONCE_OVERFLOW);
            conflict(logs.size()==1,Reason.EVENT_CONFLICT);var log=logs.getFirst();need(log!=null,Reason.EVIDENCE_REQUIRED);
            need(!log.isRemoved(),Reason.REMOVED_EVENT);
            need(log.getTopics()!=null,Reason.EVIDENCE_REQUIRED);limit(log.getTopics().size()<=MAX_LOGS);
            conflict(log.getTopics().size()==2,Reason.EVENT_CONFLICT);hash(log.getTopics().get(0));hash(log.getTopics().get(1));
            hash(log.getTransactionHash());hash(log.getBlockHash());address(log.getAddress());
            need(log.getData()!=null&&log.getData().length()<=66,Reason.EVIDENCE_MALFORMED);hash(log.getData());
            logIndex=q(log.getLogIndexRaw());
            conflict(same(log.getAddress(),tx.getTo())&&same(log.getTopics().getFirst(),TOPIC)
                    &&same(log.getTopics().get(1),"0x"+"0".repeat(24)+sender.substring(2))
                    &&new BigInteger(log.getData().substring(2),16).equals(expected)
                    &&same(log.getTransactionHash(),tx.getHash())&&same(log.getBlockHash(),tx.getBlockHash())
                    &&q(log.getBlockNumberRaw()).equals(number)&&q(log.getTransactionIndexRaw()).equals(index),Reason.EVENT_CONFLICT);
        }else conflict(logs.isEmpty(),Reason.EVENT_CONFLICT); // Failure may carry nonzero value; cause remains unknown.
        var result=new Cancellation(chain,lower(tx.getTo()),lower(sender),number,lower(tx.getBlockHash()),lower(tx.getHash()),index,logIndex,expected,success);
        return new Verification(Verdict.VERIFIED,success?Reason.CANCEL_SUCCESS_EVIDENCE:Reason.CANCEL_REVERT_CAUSE_UNDETERMINED,result);
    }
    private static BigInteger q(String text){
        need(text!=null,Reason.EVIDENCE_REQUIRED);limit(text.length()<=66);
        need(text.matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]{0,63})"),Reason.EVIDENCE_MALFORMED);return new BigInteger(text.substring(2),16);
    }
    private static BigInteger hexWord(String text){need(text!=null,Reason.EVIDENCE_REQUIRED);limit(text.length()<=66);need(text.matches("0x[0-9a-fA-F]{1,64}"),Reason.EVIDENCE_MALFORMED);return new BigInteger(text.substring(2),16);}
    private static void hash(String s){need(s!=null&&s.length()==66&&s.matches("0x[0-9a-fA-F]{64}"),Reason.EVIDENCE_MALFORMED);}
    private static void address(String s){need(s!=null&&s.length()==42&&s.matches("0x[0-9a-fA-F]{40}"),Reason.EVIDENCE_MALFORMED);}
    private static boolean same(String a,String b){return a!=null&&b!=null&&a.equalsIgnoreCase(b);}
    private static String lower(String text){return text.toLowerCase(Locale.ROOT);}
    private static void need(boolean ok,Reason reason){if(!ok)throw new Rejected(Verdict.INCONCLUSIVE,reason);}
    private static void conflict(boolean ok,Reason reason){if(!ok)throw new Rejected(Verdict.MISMATCH,reason);}
    private static void limit(boolean ok){need(ok,Reason.BOUNDED_INPUT_LIMIT);}
}
