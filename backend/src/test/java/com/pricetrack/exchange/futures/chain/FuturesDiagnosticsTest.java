package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;

class FuturesDiagnosticsTest {
    Logger logger;Level previous;ListAppender<ILoggingEvent> events;
    @BeforeEach void setup(){logger=(Logger)LoggerFactory.getLogger(FuturesDiagnostics.class);previous=logger.getLevel();logger.setLevel(Level.INFO);events=new ListAppender<>();events.start();logger.addAppender(events);}
    @AfterEach void close(){logger.detachAppender(events);events.stop();logger.setLevel(previous);}
    String output(){return events.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));}
    @Test void timeoutRetainsCauseAndTraceWithoutProviderBodyOrStack()throws Exception {
        var transport=mock(Web3jService.class);var problem=new InterruptedIOException("JWT SECRET rawTransaction userSignature provider-body");
        when(transport.send(any(Request.class),eq(EthChainId.class))).thenThrow(problem);
        var service=new ObservedFuturesRpc(transport,UserIntentV2CodecTest.C);
        assertThatThrownBy(()->FuturesDiagnostics.work(FuturesDiagnostics.Stage.DRAFT,()->{
            try{return service.send(new Request<>("eth_chainId",List.of(),service,EthChainId.class),EthChainId.class);}
            catch(IOException x){throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.DEPLOYMENT,x);}
        })).isInstanceOf(Failure.class).hasMessage("FUTURES_RPC_UNAVAILABLE").hasCause(problem);
        verify(transport,times(1)).send(any(Request.class),eq(EthChainId.class));
        assertThat(output()).contains("stage=CHAIN_ID","stage=DEPLOYMENT","stage=DRAFT","TRANSPORT_TIMEOUT","elapsedMs=").doesNotContain("JWT","SECRET","rawTransaction","userSignature","provider-body");
        var ids=new HashSet<String>();for(var e:events.list){var m=Pattern.compile("trace=([0-9a-f-]{36})").matcher(e.getFormattedMessage());assertThat(m.find()).isTrue();ids.add(m.group(1));assertThat(e.getThrowableProxy()).isNull();}assertThat(ids).hasSize(1);
    }
    @Test void nullExceptionMessageIsSafeAndThreadContextIsCleared(){
        assertThatThrownBy(()->FuturesDiagnostics.work(FuturesDiagnostics.Stage.SUBMIT,()->{throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.EOA,new IOException());})).hasMessage("FUTURES_RPC_UNAVAILABLE");
        String first=output().split("trace=")[1].substring(0,36);events.list.clear();
        FuturesDiagnostics.work(FuturesDiagnostics.Stage.NONCE_READ,()->42);
        assertThat(output()).doesNotContain(first).contains("stage=NONCE_READ","outcome=OK");
    }
    @Test void knownIdentityReasonIsPreservedButArbitraryMessageIsRedacted(){
        FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.DEPLOYMENT,new IOException("FUTURES_CODE_MISMATCH"));
        FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.BROADCAST,new IOException("0x-secret-signed-raw"));
        assertThat(output()).contains("FUTURES_CODE_MISMATCH","RPC_IO_FAILURE").doesNotContain("0x-secret-signed-raw");
    }
    @Test void elapsedAndSuccessResultAreTransparent()throws Exception {
        String value=FuturesDiagnostics.work(FuturesDiagnostics.Stage.DRAFT,()->{
            try{return FuturesDiagnostics.io(FuturesDiagnostics.Stage.USER_NONCE,()->{try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return "same-result";});}catch(IOException e){throw new IllegalStateException(e);}
        });assertThat(value).isEqualTo("same-result");
        var m=Pattern.compile("stage=USER_NONCE.*elapsedMs=(\\d+)").matcher(output());assertThat(m.find()).isTrue();assertThat(Long.parseLong(m.group(1))).isGreaterThanOrEqualTo(15);
    }
    @Test void blockHeightHasDistinctStageAndNestedContextIsRestored()throws Exception {
        var transport=mock(Web3jService.class);var response=new EthBlockNumber();response.setResult("0x1");
        when(transport.send(any(Request.class),eq(EthBlockNumber.class))).thenReturn(response);
        var service=new ObservedFuturesRpc(transport,UserIntentV2CodecTest.C);
        FuturesDiagnostics.work(FuturesDiagnostics.Stage.WORKER_EXECUTION,()->{
            String trace=FuturesDiagnostics.context().trace();
            try{assertThat(service.send(new Request<>("eth_blockNumber",List.of(),service,EthBlockNumber.class),EthBlockNumber.class)).isSameAs(response);}
            catch(IOException x){throw new AssertionError(x);}
            assertThat(FuturesDiagnostics.context().stage()).isEqualTo("WORKER_EXECUTION");assertThat(FuturesDiagnostics.context().trace()).isEqualTo(trace);return null;
        });
        assertThat(output()).contains("stage=BLOCK_HEIGHT","stage=WORKER_EXECUTION");
        assertThat(FuturesDiagnostics.context().trace()).isEqualTo("NONE");assertThat(FuturesDiagnostics.context().stage()).isEqualTo("NONE");
    }
}
