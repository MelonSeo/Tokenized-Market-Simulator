package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.PgKnowledgeStore;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Controlled synthesis comparison on frozen fresh-run inputs, NOT a retry or new HTTP acceptance. */
@EnabledIfEnvironmentVariable(named="AI_LATEST_COMPARISON_TESTS",matches="true")
class LatestAiComparisonTest {
    final ObjectMapper json=EvaluationJson.mapper();Path run;GuardedAiEvaluationRunner runner;
    final List<Map<String,Object>> rows=new ArrayList<>();
    void write(String name,Object value)throws Exception {Files.writeString(run.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
    void synth(String profile,String id,String question,AgentModelProvider.Route route,ObjectNode input,String origin)throws Exception {
        if(Files.exists(run.resolve("STOP")))throw new IllegalStateException("STOP_NO_RETRY");
        var row=new LinkedHashMap<String,Object>();row.put("profile",profile);row.put("questionId",id);row.put("question",question);
        row.put("origin",origin);row.put("input",input);row.put("budgetBefore",runner.summary());long start=System.nanoTime();
        try{
            var generated=runner.model().synthesize(question,route,input,Duration.ofSeconds(20));row.put("generated",generated);
            AgentEvidence.validate(generated,route,input);row.put("validation","PASS");
        }catch(Exception error){row.put("validation","FAIL");row.put("diagnostic",EvaluationFailureDiagnostic.safe(error));}
        row.put("elapsedMs",(System.nanoTime()-start)/1_000_000);row.put("withinProductSynthesis10s",(System.nanoTime()-start)<=10_000_000_000L);
        if(!Files.exists(run.resolve("STOP")))row.put("budgetAfter",runner.summary());
        rows.add(row);write("comparison.private.json",rows);
        if(Files.exists(run.resolve("STOP")))throw new IllegalStateException("UNKNOWN_OR_BUDGET_STOP_NO_RETRY");
    }
    @Test void frozenInputTwoPromptComparisonAndSeparateQuestions()throws Exception {
        run=Path.of(System.getenv("AI_LATEST_STUDY_RUN")).toRealPath();
        Files.writeString(run.resolve("COMPARISON_STARTED"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
        var meta=json.readTree(run.resolve("run.json").toFile());var parent=Path.of(meta.at("/continuation/parent").asText());
        var baseline=json.readTree(parent.resolve("questions.private.json").toFile());Map<String,JsonNode> byId=new HashMap<>();
        baseline.forEach(q->byId.put(q.path("questionId").asText(),q));
        var prompts=json.readTree(Path.of("src/test/resources/ai/latest-quality-prompts.json").toFile());
        var db=new Properties();try(var in=Files.newInputStream(run.resolve("db.env"))){db.load(in);}
        var parentMeta=json.readTree(parent.resolve("run.json").toFile());
        // Parent AI DB is the fresh test-only index, never a historical/user database.
        ((ObjectNode)parentMeta).put("dbPort",meta.path("dbPort").asInt());
        write("database-ready.private.json",EvaluationDatabasePreflight.ready(parentMeta,db.getProperty("POSTGRES_PASSWORD")));
        try(var r=new GuardedAiEvaluationRunner(Path.of("..").toAbsolutePath().normalize(),run,Path.of(System.getenv("AI_EVAL_TEST_PYTHON")),
                GuardedAiEvaluationRunner.Mode.LIVE,run.resolve("approval.json"),json)){
            runner=r;
            for(String profile:List.of("experiment1","experiment2")){
                write("profile.json",Map.of("id",profile,"instructionsSuffix",prompts.path(profile).asText()));
                // Q14 is intentionally excluded: the timed-out request is not automatically resubmitted.
                for(int number:List.of(5,6,7,9,10,11,12,13)){
                    var q=byId.get("Q"+number);assertThat(q.path("generated").isObject()).isTrue();
                    synth(profile,"Q"+number,q.at("/request/question").asText(),AgentModelProvider.Route.MIXED,
                        (ObjectNode)q.path("modelEvidence").deepCopy(),"FROZEN_ACTUAL_HTTP_RUN_INPUT_NOT_NEW_RPC_OR_HTTP");
                }
            }
            write("comparison-ready.json",Map.of("budget",r.summary(),"questionsPerCandidate",8));
            long end=System.nanoTime()+Duration.ofMinutes(20).toNanos();Path selection=run.resolve("selection-command.json");
            while(!Files.exists(selection)&&System.nanoTime()<end){if(Files.exists(run.resolve("STOP")))throw new IllegalStateException("STOPPED");Thread.sleep(200);}
            assertThat(Files.exists(selection)).isTrue();String selected=json.readTree(selection.toFile()).path("selectedProfile").asText();
            assertThat(selected).isIn("baseline","experiment1","experiment2");
            write("profile.json",Map.of("id",selected,"instructionsSuffix",selected.equals("baseline")?"":prompts.path(selected).asText()));
            write("selection.private.json",json.readTree(selection.toFile()));
            var p=r.properties();var ai=new AiProperties(true,p.jdbcUrl(),p.dbUser(),db.getProperty("POSTGRES_PASSWORD"),p.knowledgeRoot(),p.manifest(),
                "","guarded:stdio/",p.embeddingModel(),p.chatModel(),1200,60,5,.25,20);
            try(var store=new PgKnowledgeStore(ai)){
                var retrieval=new AuthorizedKnowledgeRetrieval(ai,new KnowledgeLoader(ai,json),store,r.provider());
                var extra=json.readTree(Path.of("src/test/resources/ai/latest-acceptance-supplement.json").toFile());
                var searches=new ArrayList<>();
                for(var h:extra.path("cases")){
                    String id=h.path("id").asText();if(Set.of("H07","H08").contains(id))continue; // authorization is not replaced with a generation prompt
                    String question=h.path("question").asText();var principal=new AuthenticatedUser(910001L,null,id.equals("H06")?UserRole.ADMIN:UserRole.USER);
                    var hits=retrieval.search(principal,question,Duration.ofSeconds(20));searches.add(Map.of("questionId",id,"retrieval",hits));write("holdout-retrieval.private.json",searches);
                    ObjectNode input=json.createObjectNode();input.set("knowledge",json.valueToTree(hits.hits()));input.putArray("tools");
                    String origin="ACTUAL_NEW_RAG_WITH_CAPTURED_FRESH_RUN_TOOL_INPUT_NOT_NEW_HTTP";
                    if(id.equals("H02"))input.set("tools",byId.get("Q7").at("/modelEvidence/tools").deepCopy());
                    if(id.equals("H03"))input.set("tools",byId.get("Q12").at("/modelEvidence/tools").deepCopy());
                    if(id.equals("H04"))input.set("tools",byId.get("Q11").at("/modelEvidence/tools").deepCopy());
                    if(id.equals("H05")||id.equals("H06")){
                        var tool=input.withArray("tools").addObject().put("evidenceId","tool-1").put("tool","getReceiptSummary").put("source","CONTROLLED_NEGATIVE_NOT_ACTUAL_RECEIPT");
                        tool.putObject("data").put("databaseStatus",id.equals("H05")?"SUBMITTED":"REVIEW_REQUIRED")
                            .put("receiptStatus",id.equals("H05")?"NOT_FOUND":"FOUND").put("executionStatus",id.equals("H05")?"UNKNOWN":"FAILED");
                        origin="ACTUAL_LLM_RAG_CONTROLLED_NEGATIVE_TOOL_NOT_NEW_PG_ANVIL_RECEIPT";
                    }
                    synth(selected,id,question,id.equals("H01")?AgentModelProvider.Route.KNOWLEDGE:AgentModelProvider.Route.MIXED,input,origin);
                }
            }
            write("completion.private.json",Map.of("status","COMPARISON_EXECUTED_NOT_HTTP_ACCEPTANCE_PASS","budget",r.summary(),"rows",rows.size(),
                "failedQ14Retried",false,"financialWrites",false,"limitations",List.of("HTTP Q15/Q16/H07/H08 NOT_RUN","H05/H06 controlled negative inputs","20s benchmark does not change product 10s deadline")));
        }
    }
}
