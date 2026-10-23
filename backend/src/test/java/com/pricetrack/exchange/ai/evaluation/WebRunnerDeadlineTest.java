package com.pricetrack.exchange.ai.evaluation;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class WebRunnerDeadlineTest {
    @TempDir Path temp;
    @Test void independentAbortDoesNotWaitForInflightSynchronizedPost()throws Exception {
        Path repo=Path.of("..").toRealPath(),run=temp.resolve("run");
        var p=new ProcessBuilder("python","-B","-S",repo.resolve("deployment/ai_evaluation_broker.py").toString(),"--prepare","--run-dir",run.toString(),"--db-port","60091","--fixture","slow").redirectErrorStream(true).start();
        assertThat(p.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        try(var runner=new GuardedAiEvaluationRunner(repo,run,Path.of("python"),GuardedAiEvaluationRunner.Mode.OFFLINE,null,new ObjectMapper())){
            var pool=Executors.newSingleThreadExecutor();
            try{
                var request=pool.submit(()->runner.provider().embed(List.of("offline pending")));
                Thread.sleep(200);long start=System.nanoTime();runner.abortWeb();
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(1000);
                assertThat(Files.exists(run.resolve("STOP"))).isTrue();
                assertThatThrownBy(()->request.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            }finally{pool.shutdownNow();}
        }
    }
}
