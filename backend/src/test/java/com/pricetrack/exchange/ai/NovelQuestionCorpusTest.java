package com.pricetrack.exchange.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.agent.AgentRequest;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.skill.SkillRegistry;
import com.pricetrack.exchange.ai.tool.ToolRegistry;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/** Actual product loader/chunker, no embedding, provider, DB, JWT or RPC execution. */
class NovelQuestionCorpusTest {
    final ObjectMapper json = new ObjectMapper();
    final Path resources = Path.of("src/test/resources/ai");
    JsonNode dataset() throws Exception { return json.readTree(resources.resolve("novel-30-v2.json").toFile()); }
    Set<String> allowedDomains(JsonNode row) {
        if(row.path("skill").isNull())return Set.of();
        return new SkillRegistry(json).require(row.path("skill").asText(),UserRole.valueOf(row.path("actor").asText()),
            switch(row.path("target").asText()) {
                case "OWN_ORDER" -> com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject.ORDER;
                case "OWN_QUOTE" -> com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject.QUOTE;
                case "OWN_POSITION", "FOREIGN_POSITION" -> com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject.FUTURES;
                default -> com.pricetrack.exchange.ai.agent.AgentModelProvider.Subject.NONE;
            }).domains();
    }
    KnowledgeCorpus corpus() {
        var p = new AiProperties(true,"jdbc:postgresql://127.0.0.1:1/unreachable","none","none",
            "../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","http://127.0.0.1:1/",
            "text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        return new KnowledgeLoader(p,json).load();
    }
    @Test void exactFrozenIdentityAndActualCorpus() throws Exception {
        var lock=json.readTree(resources.resolve("novel-30-v2.lock.json").toFile());
        var bytes=Files.readAllBytes(resources.resolve("novel-30-v2.json"));
        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo(lock.path("datasetSha256").asText());
        assertThat(lock.path("newProviderApproval").asBoolean()).isFalse();
        assertThat(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(resources.resolve("novel-30-v1.json")))))
            .isEqualTo(dataset().path("predecessor").path("sha256").asText());
        var previous=json.readTree(resources.resolve("novel-30-v1.json").toFile()).path("cases");
        for(int i=0;i<previous.size();i++) {
            var old=previous.get(i);var now=dataset().path("cases").get(i);
            assertThat(now.path("question")).isEqualTo(old.path("question"));
            assertThat(now.path("facts")).isEqualTo(old.path("facts"));
            if(!old.path("id").asText().equals("D1"))assertThat(now).isEqualTo(old);
        }
        assertThat(corpus().documents()).hasSize(9);
        assertThat(corpus().chunks()).hasSize(88);
    }
    @TestFactory Stream<DynamicTest> everyRequiredAtomExistsInAnAuthorizedActualChunk() throws Exception {
        var d=dataset();var c=corpus();
        return java.util.stream.StreamSupport.stream(d.path("cases").spliterator(),false).map(row ->
            DynamicTest.dynamicTest(row.path("id").asText(),() -> {
                var domains=allowedDomains(row);
                var found=new HashSet<String>();
                for(var key:row.path("atoms")) {
                    var atom=d.path("atoms").path(key.asText());
                    var matches=c.chunks().stream().filter(k -> k.path().equals(atom.path("path").asText())
                        && k.content().contains(atom.path("needle").asText())
                        && (row.path("actor").asText().equals("ADMIN") || k.role().equals("USER"))
                        && (domains.isEmpty() || domains.contains(k.domain()))).toList();
                    assertThat(matches).as(key.asText()).isNotEmpty();
                    found.add(atom.path("path").asText());
                }
                if(row.path("category").asText().equals("B"))assertThat(found).hasSizeGreaterThanOrEqualTo(2);
            }));
    }
    @Test void requestTargetsSkillsAndToolCeilingsAreCompatible() throws Exception {
        var skills=new SkillRegistry(json);var tools=new HashSet<String>();var usedSkills=new HashSet<String>();
        for(var row:dataset().path("cases")) {
            var request=json.createObjectNode().put("question",row.path("question").asText());
            switch(row.path("target").asText()) {
                case "OWN_ORDER" -> request.putObject("target").put("orderId",1);
                case "OWN_QUOTE" -> request.putObject("target").put("quoteId","0x"+"ab".repeat(32));
                case "OWN_POSITION", "FOREIGN_POSITION" -> request.putObject("target").put("positionId","11111111-1111-1111-1111-111111111111");
                case "NONE" -> { }
                default -> fail("Unknown symbolic fixture target");
            }
            if(!row.path("skill").isNull())request.put("skillId",row.path("skill").asText());
            var parsed=AgentRequest.parse(json,json.writeValueAsBytes(request));
            assertThat(parsed.conflictingTarget()).isFalse();
            assertThat(parsed.mutationRequest()).as(row.path("id").asText()).isFalse();
            if(parsed.skillId()!=null) {
                var definition=skills.require(parsed.skillId(),UserRole.valueOf(row.path("actor").asText()),parsed.targetKind());
                usedSkills.add(parsed.skillId());
                for(var name:row.path("tools"))assertThat(definition.tools()).contains(name.asText());
            }
            for(var name:row.path("tools"))tools.add(name.asText());
        }
        assertThat(tools).isEqualTo(ToolRegistry.NAMES);
        assertThat(usedSkills).containsExactlyInAnyOrderElementsOf(SkillRegistry.ceilings().stream().map(SkillRegistry.Definition::id).toList());
    }
    @Test void optionalEvidenceExportIsNewFileOnlyAndExplicitlyNotRetrieval() throws Exception {
        String dest=System.getenv("TMS_NOVEL30_CORPUS_REPORT");
        var d=dataset();var c=corpus();var rows=new ArrayList<Object>();
        for(var row:d.path("cases")) {
            var domains=allowedDomains(row);
            var support=new ArrayList<Object>();
            for(var key:row.path("atoms")) {
                var atom=d.path("atoms").path(key.asText());
                support.add(Map.of("atom",key.asText(),"path",atom.path("path").asText(),"chunkIds",c.chunks().stream()
                    .filter(k -> k.path().equals(atom.path("path").asText()) && k.content().contains(atom.path("needle").asText())
                        && (row.path("actor").asText().equals("ADMIN") || k.role().equals("USER"))
                        && (domains.isEmpty() || domains.contains(k.domain())))
                    .map(KnowledgeCorpus.Chunk::id).toList()));
            }
            rows.add(Map.of("id",row.path("id").asText(),"availableSupport",support,"rankedRetrieval","NOT_RUN","answerQuality","NOT_RUN"));
        }
        if(dest!=null)Files.writeString(Path.of(dest),json.writerWithDefaultPrettyPrinter().writeValueAsString(
            Map.of("status","ORACLE_CHUNK_AVAILABILITY_NOT_RETRIEVAL_SUCCESS","corpusHash",c.fingerprint(),"chunks",c.chunks().size(),"cases",rows)),StandardOpenOption.CREATE_NEW);
        assertThat(rows).hasSize(30);
    }
    @Test void d1ActualSkillDoesNotAllowSecurityEvenWhenRoleWouldAllowIt()throws Exception {
        JsonNode d=dataset(), row=null;
        for(var candidate:d.path("cases"))if(candidate.path("id").asText().equals("D1"))row=candidate;
        assertThat(row).isNotNull();
        var domains=allowedDomains(row);
        assertThat(domains).contains("trading").doesNotContain("security");
        var legacyAtom=d.path("atoms").path("badLink");
        var matches=corpus().chunks().stream().filter(k -> k.path().equals(legacyAtom.path("path").asText())
            && k.content().contains(legacyAtom.path("needle").asText()) && k.role().equals("USER")).toList();
        assertThat(matches).isNotEmpty();
        assertThat(matches).noneMatch(k -> domains.contains(k.domain()));
    }
}
