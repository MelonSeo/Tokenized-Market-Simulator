package com.pricetrack.exchange.ai.tool.read;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.custody.auth.CWalletProofService;
import com.pricetrack.exchange.custody.runtime.*;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import java.math.BigInteger;

/** Same JWT->proved wallet->fixed-block reader as the product API. No signing/execution dependencies. */
public final class CVaultToolReader {
    private final CWalletProofService wallets;private final CCustodyRpcReader reads;private final ObjectMapper json;
    public CVaultToolReader(CWalletProofService wallets,CCustodyRpcReader reads,ObjectMapper json){this.wallets=wallets;this.reads=reads;this.json=json;}
    private String owner(ToolContext c){c.requireAuthenticated();return wallets.wallet(c.userId()).wallet();}
    static String tokens(String raw){return TokenUnits.fromWei(new BigInteger(raw)).toPlainString();}
    ObjectNode observation(CCustodyRpcReader.Observation<?> o){
        return json.createObjectNode().put("assetModel","C_ONCHAIN_VAULT_NOT_DB_LEDGER").put("symbol","cMKRW")
            .put("deploymentId",o.deploymentId()).set("observation",json.valueToTree(java.util.Map.of("number",o.block().number(),"hash",o.block().hash(),"observedAt",o.observedAt().toString(),"scope",o.verificationScope())));
    }
    public ObjectNode account(ToolContext c){
        var o=reads.account(owner(c));var a=o.value();var n=observation(o);
        return n.put("walletBalance",tokens(a.walletBalance())).put("free",tokens(a.free())).put("locked",tokens(a.locked()))
            .put("systemHouse",tokens(a.house())).put("systemTotalUnpaidProfit",tokens(a.totalUnpaidProfit()))
            .put("personalClaimsEnumerated",false);
    }
    public ObjectNode position(ToolContext c,String id){
        var o=reads.position(owner(c),id);var p=o.value();var n=observation(o);
        return n.put("status",p.status()).put("side",p.side()).put("sideMeaning","0_LONG_1_SHORT").put("leverage",p.leverage()).put("quantity",tokens(p.quantity())).put("quantityUnit","mSEC").put("collateralUnit","cMKRW").put("entryPriceUnit","cMKRW_PER_mSEC_SCALED_1E8")
            .put("historicalCollateral",tokens(p.historicalCollateral())).put("lockedCollateral",tokens(p.lockedCollateral()))
            .put("entryPriceE8",p.entryPriceE8()).put("lockTerminal",p.lockTerminal());
    }
}
