package com.pricetrack.exchange.futures.chain;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Command/path validation only. Does not load a wallet key, launch a signer or call RPC. */
class AuthorizedV2ExternalSignerCommandTest {
    @TempDir Path temp;
    Path fixture()throws Exception{
        Path run=temp.resolve("phase-a-20261022000000-1234abcd/fixtures/futures-api-abcd1234");Files.createDirectories(run.resolve("secrets"));Files.writeString(run.resolve("secrets/user-normal"),"NOT_A_KEY_VALIDATION_FIXTURE");return run;
    }
    @Test void defaultWindowsLauncherIsPreservedAndLinuxSelectsJavaExecutable(){
        var windows=AuthorizedV2PostgresAnvilE2ETest.signerCommand(temp,temp,true,null,"normal","address","");
        assertThat(windows.get(0)).endsWith("java.exe");assertThat(windows.get(1)).startsWith("@").endsWith("java-classpath.args");assertThat(windows.get(2)).endsWith("Bv2ExternalSigner.java");
        var linux=AuthorizedV2PostgresAnvilE2ETest.signerCommand(temp,temp,false,null,"normal","address","");assertThat(linux.get(0)).endsWith("bin"+java.io.File.separator+"java");
    }
    @Test void compiledSignerUsesOnlyFreshFixtureClasspathAndKeyFile()throws Exception{
        Path run=fixture(),classes=Files.createDirectories(run.resolve("harness/classes")),jar=Files.write(run.resolve("harness/dependency.jar"),new byte[0]);Files.write(classes.resolve("Bv2ExternalSigner.class"),new byte[]{0});
        var command=AuthorizedV2PostgresAnvilE2ETest.signerCommand(run,temp,false,classes+java.io.File.pathSeparator+jar,"normal","digest","0x"+"00".repeat(32));
        assertThat(command).containsSubsequence("-cp",classes.toRealPath()+java.io.File.pathSeparator+jar.toRealPath(),"Bv2ExternalSigner",run.resolve("secrets/user-normal").toRealPath().toString(),"digest");assertThat(command).noneMatch(a->a.startsWith("@"));
    }
    @Test void oldRuntimeClasspathMissingSignerAndTraversalFailClosed()throws Exception{
        Path run=fixture(),classes=Files.createDirectories(run.resolve("harness/classes")),outside=Files.createDirectory(temp.resolve("old-runtime"));Files.write(classes.resolve("Bv2ExternalSigner.class"),new byte[]{0});
        for(String cp:List.of(outside.toString(),classes+java.io.File.pathSeparator+outside,classes+java.io.File.pathSeparator,run.toString()+java.io.File.separator+"harness"+java.io.File.separator+"*"," "))
            assertThatThrownBy(()->AuthorizedV2PostgresAnvilE2ETest.signerCommand(run,temp,false,cp,"normal","address","")).hasMessage("DAPP_EXTERNAL_SIGNER_CONFIGURATION_INVALID");
        assertThatThrownBy(()->AuthorizedV2PostgresAnvilE2ETest.signerCommand(run,temp,false,classes.toString(),"../normal","address","")).hasMessage("DAPP_EXTERNAL_SIGNER_CONFIGURATION_INVALID");
        Files.delete(classes.resolve("Bv2ExternalSigner.class"));assertThatThrownBy(()->AuthorizedV2PostgresAnvilE2ETest.signerCommand(run,temp,false,classes.toString(),"normal","address","")).hasMessage("DAPP_EXTERNAL_SIGNER_CONFIGURATION_INVALID");
    }
}
