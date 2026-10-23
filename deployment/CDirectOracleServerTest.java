import com.pricetrack.exchange.custody.abi.CAbiModels.Report;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Oracle validation only; never reads a key, starts an HTTP server or signs a transaction. */
public class CDirectOracleServerTest {
    static String a(int n){return "0x"+String.format("%040x",n);}static String h(int n){return "0x"+String.format("%064x",n);}
    static CCustodyIdentity id(){return new CCustodyIdentity(BigInteger.valueOf(31337),h(1),new CCustodyIdentity.Contract(a(1),h(2)),new CCustodyIdentity.Contract(a(2),h(3)),new CCustodyIdentity.Contract(a(3),h(4)),new CCustodyIdentity.Contract(a(4),h(5)),a(5),a(6),a(7),h(6),h(7),"http://localhost:5173");}
    static Report report(long observed,long ttl,int action,String executor){var c=id();return new Report(h(8),h(9),h(10),c.symbolHash(),BigInteger.valueOf(action),BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO,BigInteger.TEN,BigInteger.valueOf(100),BigInteger.TEN.pow(10),BigInteger.ZERO,BigInteger.valueOf(1000),BigInteger.TEN.pow(10),BigInteger.valueOf(observed),BigInteger.valueOf(observed+ttl),c.policyHash(),executor);}
    @Test void exactReportWindowAndOperationScopeAreCheckedWithoutKeys(){for(int action:new int[]{0,1,4})for(int delta:new int[]{-5,0,2})assertDoesNotThrow(()->CDirectOracleServer.validate(id(),report(256+delta,30,action,id().controller().address()),Instant.ofEpochSecond(256)));}
    @Test void expiredFutureWrongTtlAndArbitraryExecutorOrRiskCannotBeSigned(){for(Report r:new Report[]{report(250,30,0,id().controller().address()),report(259,30,0,id().controller().address()),report(256,29,0,id().controller().address()),report(256,30,0,a(99)),report(256,30,5,id().controller().address())})assertThrows(IllegalArgumentException.class,()->CDirectOracleServer.validate(id(),r,Instant.ofEpochSecond(256)));}
    @Test void compiledSelfTestReturnsBeforeAnyEnvironmentKeyOrSocketAccess(){assertDoesNotThrow(()->CDirectOracleServer.main(new String[]{"--self-test"}));}
}
