package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the failure-cleanup mechanism against ONLY a newly spawned Node process and its newly spawned child. */
class FuturesBrowserCleanupTest {
    @TempDir Path evidence;
    @Test void cleanupTerminatesOnlyTrackedNewDescendantsWithMatchingStartTime()throws Exception {
        var harness=new FuturesBrowserPostgresAnvilE2ETest();
        var process=new ProcessBuilder("node","-e","require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore',windowsHide:true});setInterval(()=>{},1000)").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var owned=new HashMap<Long,FuturesBrowserPostgresAnvilE2ETest.Owned>();
        try {
            long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(owned.isEmpty()&&System.nanoTime()<stop){harness.capture(process,owned);Thread.sleep(50);}
            assertThat(owned).isNotEmpty();
        }finally{harness.cleanup(process,owned,evidence);}
        assertThat(process.isAlive()).isFalse();assertThat(owned.values()).noneMatch(FuturesBrowserPostgresAnvilE2ETest.Owned::sameAlive);
        assertThat(Files.readString(evidence.resolve("owned-process-cleanup.evidence.json"))).contains("\"aliveAfterCleanup\":0","ONLY_NEW_NODE_DESCENDANTS_WITH_MATCHING_START_TIME");
    }
}
