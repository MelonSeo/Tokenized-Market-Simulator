import java.nio.file.*;
import com.pricetrack.exchange.custody.runtime.*;
import java.net.URI;

/** Reuse the verified public C recipe on this runner's newly owned Anvil only. */
public final class PilotV2ChainFixture {
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!args[0].matches("http://127\\.0\\.0\\.1:[0-9]{4,5}"))throw new IllegalArgumentException("V2_ORIGIN");
        Path artifacts=Path.of("/artifacts"),evidence=Path.of("/private-evidence"),out=Path.of("/fixture"),keys=Path.of("/keys");
        CAnvilContractGate.verifyTransportPolicy();new ProductReadFixture(artifacts,evidence).run(out,keys);
        var meta=ProductReadDriver.J.readTree(out.resolve("test-chain.json").toFile());
        try(var rpc=new CCustodyRpcClient(URI.create("http://anvil:8545"))){
            var i=ProductReadDriver.observe(rpc,meta).identity();
            var identity=new CCustodyIdentity(i.chainId(),i.genesisHash(),i.token(),i.controller(),i.rules(),i.vault(),i.relayer(),i.oracle(),i.pauseAdmin(),i.symbolHash(),i.policyHash(),args[0]);
            Files.writeString(out.resolve("c-identity.json"),ProductReadDriver.J.writeValueAsString(identity),StandardOpenOption.CREATE_NEW);
        }
        System.out.println("V2_NEW_C_FIXTURE_PASS_NOT_METAMASK");
    }
}
