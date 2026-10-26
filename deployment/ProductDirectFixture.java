import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.web3j.crypto.*;
import static com.pricetrack.exchange.custody.abi.CAbiModels.*;

/** Fresh runner-only capital. No existing snapshot or user keys. Reuses attested deployment/receipt helpers. */
public final class ProductDirectFixture {
 public static void main(String[] args){try{
  if(!Arrays.equals(args,new String[]{"/artifacts","/private-evidence","/fixture","/keys","/oracle-config"}))throw new IllegalArgumentException();
  CAnvilContractGate.verifyTransportPolicy();var g=new CAnvilContractGate(Path.of(args[0]),Path.of(args[1]));g.verifySourceMetadata();
  g.symbol=Hash.sha3String("mSEC");g.policy=Hash.sha3String("TMS_C_FIFO_CLAIMS_MMR500_GRACE60_V1");
  g.genesis=g.rpc("eth_getBlockByNumber","0x0",false).path("hash").asText();
  CAnvilContractGate.eq(CAnvilContractGate.number(g.rpc("eth_chainId")),CAnvilContractGate.CHAIN);
  for(String role:List.of("treasury","admin","relayer","oracle","userA","userB"))g.keys.put(role,Keys.createEcKeyPair(new SecureRandom()));
  CAnvilContractGate.check(g.keys.values().stream().map(CAnvilContractGate::address).distinct().count()==6,"DIRECT_KEY_SEPARATION");
  var keys=CAnvilContractGate.JSON.createObjectNode();
  for(String role:List.of("userA","userB"))keys.put(role,String.format("%064x",g.keys.get(role).getPrivateKey()));
  Files.writeString(Path.of(args[3],"wallet-keys.private.json"),keys.toString(),StandardOpenOption.CREATE_NEW);
  Files.writeString(Path.of(args[4],"oracle-key.private"),String.format("%064x",g.keys.get("oracle").getPrivateKey()),StandardOpenOption.CREATE_NEW);
  for(var key:g.keys.values())g.rpc("anvil_setBalance",CAnvilContractGate.address(key),CAnvilContractGate.hex(CAnvilContractGate.units(100)));
  g.token=g.deploy("CMockCollateral",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.addr("treasury"))),g.keys.get("treasury"));
  g.controller=g.deploy("CCollateralizedFuturesController",CAnvilContractGate.words(CAnvilContractGate.addressWord(g.token),CAnvilContractGate.addressWord(g.addr("relayer")),CAnvilContractGate.addressWord(g.addr("oracle")),CAnvilContractGate.addressWord(g.addr("admin")),g.symbol,g.policy),g.keys.get("treasury"));
  g.rules=g.callAddress(g.controller,"rules()");g.vault=g.callAddress(g.controller,"vault()");
  var meta=CAnvilContractGate.JSON.createObjectNode().put("scope","NEW_OWNER_DIRECT_CI_NOT_METAMASK").put("genesisHash",g.genesis).put("controller",g.controller).put("rules",g.rules).put("vault",g.vault).put("token",g.token).put("chainId","31337");var hashes=meta.putObject("codeHashes");
  for(var e:Map.of("token","CMockCollateral","controller","CCollateralizedFuturesController","rules","FuturesEngine","vault","CMarginVault").entrySet()){
   String address=meta.path(e.getKey()).asText();g.verifyRuntime(e.getValue(),address);hashes.put(e.getKey(),g.read(g.output.resolve("code-"+e.getValue()+".json")).path("runtimeCodeHash").asText());
  }
  CAnvilContractGate.eq(g.callAddress(g.rules,"executor()"),g.controller);CAnvilContractGate.eq(g.callAddress(g.vault,"asset()"),g.token);
  g.cash("treasury",VaultAction.FUND_HOUSE,CAnvilContractGate.units(100000));
  for(String role:List.of("userA","userB")){g.transfer("treasury",g.addr(role),CAnvilContractGate.units(1000000));meta.put(role,g.addr(role));CAnvilContractGate.eq(g.free(g.addr(role)),java.math.BigInteger.ZERO);}
  Files.writeString(Path.of(args[2],"test-chain.json"),meta.toString(),StandardOpenOption.CREATE_NEW);
  System.out.println("PRODUCT_DIRECT_FRESH_FIXTURE_PASS");
 }catch(Exception e){System.err.println("PRODUCT_DIRECT_FIXTURE_STOP_NO_RETRY");System.exit(1);}}
}
