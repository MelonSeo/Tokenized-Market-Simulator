package com.pricetrack.exchange.custody.proof;

import static com.pricetrack.exchange.custody.proof.CAuthorizationModels.*;
import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiModels.Intent;
import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.proof.CRulesTransitionModels.Position;
import com.pricetrack.exchange.custody.proof.CRulesTransitionModels.Status;
import com.pricetrack.exchange.futures.math.FuturesMath;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.web3j.crypto.Hash;

/** Pure user approval and Oracle signature verification under explicit caller-supplied observations.
 * Must be combined with rules, Vault, Receipt and canonical verifiers before any full acceptance. */
public final class CAuthorizationVerifier {
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE,MAX=ONE.shiftLeft(256).subtract(ONE);
    private static final String ZERO_ADDRESS="0x"+"0".repeat(40),ZERO_HASH="0x"+"0".repeat(64);
    private static final HexFormat HEX=HexFormat.of();
    private static final byte[] ACCOUNT_DOMAIN=Hash.sha3("TMS_C_FUTURES_WALLET_ACCOUNT_V1".getBytes(StandardCharsets.UTF_8));
    private final CEip712Verifier crypto=new CEip712Verifier();
    public static final class Failure extends IllegalArgumentException {
        private final FailureKind kind;
        public Failure(FailureKind kind,String code) {super("C_AUTH_"+code);this.kind=kind;}
        public FailureKind kind() {return kind;}
    }
    public String accountRef(String owner,String symbol) {
        byte[] all=new byte[96];System.arraycopy(ACCOUNT_DOMAIN,0,all,0,32);
        System.arraycopy(HEX.parseHex(address(owner).substring(2)),0,all,44,20);
        System.arraycopy(HEX.parseHex(hash(symbol).substring(2)),0,all,64,32);
        return "0x"+HEX.formatHex(Hash.sha3(all));
    }
    public Result verify(Input input) {
        sufficient(input!=null&&input.context()!=null&&input.intent()!=null&&input.report()!=null,"INPUT_MISSING");
        Context c=input.context();Intent i=input.intent();Report r=input.report();
        sufficient(c.mode()!=null&&c.point()!=null&&c.blockTimestamp()!=null&&c.actualSender()!=null&&c.ethereumTransactionNonce()!=null,"CONTEXT_MISSING");
        validatePoint(c.point());uint(c.chainId());require(c.chainId().signum()>0,"CHAIN");uint(c.blockTimestamp());uint(c.ethereumTransactionNonce());
        String controller=nonzero(c.controller()),rules=nonzero(c.rules()),relayer=nonzero(c.relayer()),oracle=nonzero(c.oracleSigner());
        require(!controller.equals(rules)&&!relayer.equals(oracle),"CONTEXT_IDENTITIES");
        String sender=nonzero(c.actualSender()),symbol=hash(c.symbolHash()),policy=hash(c.policyHash());
        require(!symbol.equals(ZERO_HASH)&&!policy.equals(ZERO_HASH),"CONTEXT_IDENTITIES");
        String owner=nonzero(i.owner());
        observe(input,owner);
        Prestate pre=input.prestate();
        require(i.nonce().equals(pre.userNonce()),"USER_NONCE");require(i.nonce().compareTo(MAX)<0,"NONCE_OVERFLOW");
        require(i.relayer().equals(relayer),"RELAYER_FIELD");
        if(c.mode()==Mode.DIRECT) require(sender.equals(owner),"DIRECT_SENDER");
        else require(sender.equals(relayer),"RELAY_SENDER");
        BigInteger now=c.blockTimestamp();
        require(i.issuedAt().compareTo(now)<=0&&i.deadline().compareTo(i.issuedAt())>0
            &&i.deadline().subtract(i.issuedAt()).compareTo(BigInteger.valueOf(120))<=0&&now.compareTo(i.deadline())<=0,"USER_WINDOW");
        require(i.action().equals(ZERO)||i.action().equals(ONE)||i.action().equals(BigInteger.valueOf(4)),"USER_ACTION");
        String account=accountRef(owner,symbol);
        require(i.operationId().equals(r.operationId())&&i.positionId().equals(r.positionId())&&i.action().equals(r.action())
            &&i.accountRef().equals(account)&&i.accountRef().equals(r.accountRef())&&i.symbolHash().equals(symbol)
            &&r.symbolHash().equals(symbol)&&i.policyHash().equals(policy)&&r.policyHash().equals(policy)&&r.executor().equals(controller)
            &&i.expectedVersion().equals(r.expectedVersion())&&i.expectedCallEpoch().equals(r.expectedCallEpoch())
            &&i.side().equals(r.side())&&i.leverage().equals(r.leverage())&&i.collateralBefore().equals(r.collateralBefore())
            &&i.collateralAfter().equals(r.collateralAfter()),"INTENT_REPORT_LINK");
        require(!i.operationId().equals(ZERO_HASH)&&!i.positionId().equals(ZERO_HASH),"IDENTIFIERS");
        require(i.priceMinE8().signum()>0&&i.priceMinE8().compareTo(i.priceMaxE8())<=0&&i.priceMaxE8().compareTo(FuturesMath.PRICE_LIMIT)<0
            &&i.quantityMin().signum()>0&&i.quantityMin().compareTo(i.quantityMax())<=0&&i.quantityMax().compareTo(FuturesMath.AMOUNT_LIMIT)<0,"BOUNDS");
        require(r.priceE8().compareTo(i.priceMinE8())>=0&&r.priceE8().compareTo(i.priceMaxE8())<=0
            &&r.quantity().compareTo(i.quantityMin())>=0&&r.quantity().compareTo(i.quantityMax())<=0,"OUTSIDE_BOUNDS");
        position(i,r,pre,account);
        try {
            if(c.mode()==Mode.RELAYED) crypto.verifyIntent(i,c.chainId(),controller,owner,input.userSignature());
            crypto.verifyReport(r,c.chainId(),rules,oracle,input.priceSignature());
        } catch(IllegalArgumentException rejected) {throw failure("SIGNATURE");}
        return new Result(c.mode(),owner,account,crypto.intentDigest(i,c.chainId(),controller),crypto.reportDigest(r,c.chainId(),rules),
            i.nonce(),i.nonce().add(ONE),c.ethereumTransactionNonce(),c.point());
    }
    private void observe(Input input,String owner) {
        Context c=input.context();Prestate pre=input.prestate();CodeObservation code=input.ownerCode();
        sufficient(pre!=null&&pre.point()!=null&&pre.nonceOwnerKey()!=null&&pre.userNonce()!=null&&pre.positionOwnerKey()!=null
            &&pre.positionOwner()!=null&&pre.rulesPositionKey()!=null&&pre.rulesPosition()!=null,"PRESTATE_MISSING");
        sufficient(c.point().equals(pre.point()),"PRESTATE_POINT");
        sufficient(address(pre.nonceOwnerKey()).equals(owner)&&hash(pre.positionOwnerKey()).equals(input.intent().positionId())
            &&hash(pre.rulesPositionKey()).equals(input.intent().positionId()),"PRESTATE_KEYS");uint(pre.userNonce());
        sufficient(code!=null&&code.point()!=null&&code.ownerKey()!=null&&code.code()!=null,"OWNER_CODE_MISSING");
        sufficient(code.point().equals(c.point())&&address(code.ownerKey()).equals(owner),"OWNER_CODE_POINT_OR_KEY");
        require(code.code().length()<=2+2*24576&&(code.code().length()&1)==0&&code.code().matches("0x[0-9a-fA-F]*"),"OWNER_CODE_FORMAT");
        require(code.code().equals("0x"),"OWNER_HAS_CODE");
    }
    private void position(Intent i,Report r,Prestate pre,String account) {
        Position p=pre.rulesPosition();
        require(p.status()!=null,"PRESTATE_STATUS");uint(p.side());uint(p.leverage());uint(p.quantity());uint(p.entryPriceE8());uint(p.collateral());
        uint(p.version());uint(p.callEpoch());uint(p.deadline());uint(p.lastObservedAt());
        if(i.action().equals(ZERO)) {
            require(address(pre.positionOwner()).equals(ZERO_ADDRESS)&&p.equals(Position.empty()),"OPEN_PRESTATE");
            require(i.expectedVersion().signum()==0&&i.expectedCallEpoch().signum()==0&&i.collateralBefore().signum()==0
                &&i.entryPriceE8().signum()==0&&r.entryPriceE8().equals(r.priceE8()),"OPEN_TERMS");
            BigInteger quantity;
            try {quantity=FuturesMath.quantity(i.collateralAfter(),i.leverage().intValueExact(),r.priceE8());}
            catch(IllegalArgumentException|ArithmeticException invalid) {throw failure("OPEN_QUANTITY");}
            require(r.quantity().equals(quantity),"OPEN_QUANTITY");
        } else {
            require(address(pre.positionOwner()).equals(i.owner()),"POSITION_OWNER");
            require(p.status()==Status.ACTIVE||p.status()==Status.MARGIN_CALL,"POSITION_STATE");
            require(p.accountRef().equals(account)&&p.version().equals(i.expectedVersion())&&p.callEpoch().equals(i.expectedCallEpoch())
                &&p.side().equals(i.side())&&p.leverage().equals(i.leverage())&&p.collateral().equals(i.collateralBefore()),"POSITION_TERMS");
            require(i.quantityMin().equals(i.quantityMax())&&i.quantityMin().equals(p.quantity())&&r.quantity().equals(p.quantity())
                &&i.entryPriceE8().equals(p.entryPriceE8())&&r.entryPriceE8().equals(p.entryPriceE8()),"EXACT_ENTRY_QUANTITY");
            if(i.action().equals(ONE)) require(i.collateralAfter().compareTo(p.collateral())>0&&i.collateralAfter().compareTo(FuturesMath.AMOUNT_LIMIT)<0,"TOP_UP_COLLATERAL");
            else require(i.collateralAfter().equals(p.collateral()),"CLOSE_COLLATERAL");
        }
    }
    private void validatePoint(ObservationPoint p) {uint(p.blockNumber());uint(p.transactionIndex());hash(p.blockHash());hash(p.transactionHash());}
    private static String address(String value) {try{return CAbiModels.address(value);}catch(IllegalArgumentException invalid){throw failure("ADDRESS");}}
    private static String nonzero(String value) {String address=address(value);require(!address.equals(ZERO_ADDRESS),"ADDRESS");return address;}
    private static String hash(String value) {try{return CAbiModels.hash(value);}catch(IllegalArgumentException invalid){throw failure("HASH");}}
    private static BigInteger uint(BigInteger value) {try{return CAbiModels.uint(value);}catch(IllegalArgumentException invalid){throw failure("UINT256");}}
    private static void require(boolean condition,String code) {if(!condition)throw failure(code);}
    private static void sufficient(boolean condition,String code) {if(!condition)throw new Failure(FailureKind.INSUFFICIENT_CONTEXT,code);}
    private static Failure failure(String code) {return new Failure(FailureKind.REJECTED,code);}
}
