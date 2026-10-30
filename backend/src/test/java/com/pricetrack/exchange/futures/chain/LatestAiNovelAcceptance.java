package com.pricetrack.exchange.futures.chain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.retrieval.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** New frozen questions, actual JWT/HTTP plus separately labeled retrieval/component comparisons. */
final class LatestAiNovelAcceptance {
    static void run(LatestAiAcceptanceTest t,String password,String before)throws Exception {
        Path file=Path.of("src/test/resources/ai/novel-30-v2.json");
        var dataset=t.json.readTree(file.toFile());
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertThat(hash).isEqualTo(t.runner.identity().path("datasetSha256").asText());
        Files.writeString(t.run.resolve("frozen-novel30.private.json"),t.json.writeValueAsString(dataset),StandardOpenOption.CREATE_NEW);
        var failures=new ArrayList<String>();var probes=new ArrayList<Object>();var baselines=new ArrayList<Object>();
        try {
            t.runner.prepareIndex(password,true,new AuthenticatedUser(t.admin,null,UserRole.ADMIN));t.indexReady=true;
            t.runner.queryVectorCache(true);
            t.write("index.private.json",Map.of("state","READY","preflight",t.runner.preflight(),"budget",t.runner.summary()));
        }catch(Exception|AssertionError failure){failures.add("INDEX");t.write("index-failure.private.json",Map.of("type",failure.getClass().getSimpleName()));}
        for(var c:dataset.path("cases")) {
            String id=c.path("id").asText();boolean isAdmin=c.path("actor").asText().equals("ADMIN");
            if(t.runner.identity().path("novelFollowup").asBoolean()&&!Set.of("D1","A3","A5").contains(id))continue;
            long actor=isAdmin?t.admin:id.equals("D5")?t.other:t.uid;
            String token=isAdmin?t.adminToken:id.equals("D5")?t.otherToken:t.ownerToken;
            var principal=new AuthenticatedUser(actor,null,isAdmin?UserRole.ADMIN:UserRole.USER);
            var body=new LinkedHashMap<String,Object>();body.put("question",c.path("question").asText());
            switch(c.path("target").asText()) {
                case "OWN_ORDER" -> body.put("target",Map.of("orderId",id.equals("F4")?t.pendingOrder:t.order));
                case "OWN_QUOTE" -> body.put("target",Map.of("quoteId",t.quote));
                case "OWN_POSITION" -> body.put("target",Map.of("positionId",id.equals("C3")?t.unverifiedPid:t.pid));
                case "FOREIGN_POSITION" -> body.put("target",Map.of("positionId",t.pid));
                case "NONE" -> { }
                default -> throw new IllegalStateException("INVALID_FROZEN_TARGET");
            }
            if(!c.path("skill").isNull())body.put("skillId",c.path("skill").asText());
            try {
                if(id.equals("D1")){t.novelReads.setBadQuoteLink(true);assertThat(t.novelReads.badQuoteLinkActive()).isTrue();}
                if(id.equals("B5"))t.adverse.mismatchOrder=t.order;
                t.evaluateCase("novel30-actual-jwt-http",id,actor,token,body,c.path("expectedHttp").asInt(200),failures);
                var row=mutableRow(t.rows.getLast());t.rows.set(t.rows.size()-1,row);
                row.put("category",c.path("category").asText());row.put("frozenDatasetSha256",hash);
                row.put("fixtureScope",id.equals("D1")?"CONTROLLED_LINK_DTO_REAL_OWNER_READ":id.equals("B5")?"CONTROLLED_MISMATCH_DTO_REAL_RECEIPT":id.equals("F4")?"CONTROLLED_PENDING_ROW_REAL_RPC_NOT_FOUND":"REAL_ISOLATED_READS_NO_NEW_FINANCIAL_TRADES");
                if(row.get("retrieval") instanceof AuthorizedKnowledgeRetrieval.Evidence e)row.put("actualRetrievalMetrics",metrics(t.json,dataset,c,e.hits()));
                t.write("questions.private.json",t.rows);
                if(id.equals("D1")&&row.get("answer") instanceof JsonNode d1){
                    var quoteTool=java.util.stream.StreamSupport.stream(d1.path("toolEvidence").spliterator(),false)
                        .filter(tool->tool.path("tool").asText().equals("getQuote")).findFirst().orElseThrow();
                    assertThat(quoteTool.at("/data/orderLink").asText()).isEqualTo("INCONSISTENT_LINK");
                    assertThat(quoteTool.at("/data/captureScope").asText()).isEqualTo("CONTROLLED_LINK_DTO_NOT_DATABASE_CORRUPTION");
                    assertThat(d1.path("toolEvidence").size()).isEqualTo(1);
                }
                if(id.equals("D5")&&row.get("answer") instanceof JsonNode denied) {
                    assertThat(row.get("budgetBefore")).isEqualTo(row.get("budgetAfter"));
                    assertThat(denied.path("metrics").path("modelCalls").asInt()).isZero();
                    var missing=new LinkedHashMap<>(body);missing.put("target",Map.of("positionId","ffffffff-ffff-ffff-ffff-ffffffffffff"));
                    t.evaluateCase("novel30-denial-missing-control","D5-missing",actor,token,missing,404,failures);
                    assertThat(t.rows.getLast().get("budgetBefore")).isEqualTo(t.rows.getLast().get("budgetAfter"));
                    continue;
                }
                if(Files.exists(t.run.resolve("STOP"))||!t.indexReady||!(row.get("answer") instanceof JsonNode))continue;
                // Compare direct read-only APIs, not a claim about real user satisfaction or elapsed task time.
                try {
                var direct=new ArrayList<Object>();
                if(row.get("answer") instanceof JsonNode answer)for(var tool:answer.path("toolEvidence")) {
                    String name=tool.path("tool").asText();Object args=arguments(name,t,id);
                    var r=t.request(token,"/api/ai/tools/"+name,Map.of("arguments",args));
                    direct.add(Map.of("tool",name,"httpStatus",r.status(),"result",r.body()));
                }
                if(id.equals("F1")||id.equals("D3")||id.equals("C5")) {
                    var r=t.request(token,"/api/ai/tools/getPortfolio",Map.of("arguments",Map.of()));
                    direct.add(Map.of("tool","getPortfolio","scope","ADDITIONAL_DIRECT_BASELINE_NOT_AGENT_OBSERVATION","httpStatus",r.status(),"result",r.body()));
                }
                if(id.equals("C4")) {
                    var r=t.request(token,"/api/ai/tools/listAbnormalOrders",Map.of("arguments",Map.of("filter","WAITING_LONG","limit",10)));
                    direct.add(Map.of("tool","listAbnormalOrders","scope","EXPLICIT_WAITING_LONG_DIRECT_BASELINE","httpStatus",r.status(),"result",r.body()));
                }
                baselines.add(Map.of("id",id,"source","ACTUAL_HTTP_READ_ONLY_API_NOT_HUMAN_TIMING_EXPERIMENT","reads",direct));
                t.write("direct-api-baselines.private.json",baselines);
                }catch(Exception failure){failures.add(id+"_DIRECT_BASELINE");baselines.add(Map.of("id",id,"status","FAILED","type",failure.getClass().getSimpleName()));t.write("direct-api-baselines.private.json",baselines);}
                // Original-question search is diagnostic and never substituted for actual Agent retrieval.
                try {
                Set<String> declared=c.path("skill").isNull()?null:t.skillRegistry.require(c.path("skill").asText(),principal.role(),
                    new AgentRequest("",body.containsKey("target")&&c.path("target").asText().equals("OWN_ORDER")?t.order:null,
                        c.path("target").asText().equals("OWN_QUOTE")?t.quote:null,null,
                        c.path("target").asText().equals("OWN_POSITION")?t.pid:null).targetKind()).domains();
                Set<String> domains=effectiveDomains(row,declared);
                long started=System.nanoTime();
                var e=search(t,principal,c.path("question").asText(),domains);
                probes.add(Map.of("id",id,"query",c.path("question").asText(),"scope","ORIGINAL_QUESTION_SAME_ACTUAL_DOMAINS_NOT_AGENT_RESULT",
                    "elapsedMs",(System.nanoTime()-started)/1_000_000,"hits",e.hits(),"metrics",metrics(t.json,dataset,c,e.hits())));
                t.write("novel-retrieval-probes.private.json",probes);
                }catch(Exception failure){failures.add(id+"_RETRIEVAL_PROBE");probes.add(Map.of("id",id,"status","FAILED","type",failure.getClass().getSimpleName(),"stopped",Files.exists(t.run.resolve("STOP"))));t.write("novel-retrieval-probes.private.json",probes);}
            } finally {if(t.novelReads!=null)t.novelReads.setBadQuoteLink(false);t.adverse.mismatchOrder=-1;}
        }
        // At most five extra syntheses; no prompt-specific expected answers or oracle terms enter queries.
        var comparisons=new ArrayList<Object>();
        for(var c:dataset.path("cases"))if(!t.runner.identity().path("novelFollowup").asBoolean()&&c.path("category").asText().equals("B")&&!Files.exists(t.run.resolve("STOP"))) {
            String id=c.path("id").asText();
            try {
            var row=t.rows.stream().filter(r -> id.equals(r.get("questionId"))).findFirst().orElseThrow();
            if(!(row.get("answer") instanceof JsonNode answer)||!Set.of("KNOWLEDGE","MIXED").contains(answer.path("route").asText()))continue;
            ObjectNode original;
            if(row.get("modelEvidence") instanceof ObjectNode observed)original=observed;
            else original=AgentEvidence.modelInput(t.json,List.of(),t.json.convertValue(answer.path("toolEvidence"),
                new com.fasterxml.jackson.core.type.TypeReference<List<AgentResponse.ToolEvidence>>(){}));
            if(row.get("retrieval") instanceof AuthorizedKnowledgeRetrieval.Evidence e&&metrics(t.json,dataset,c,e.hits()).path("allSupportAt5").asBoolean())continue;
            var principal=new AuthenticatedUser(c.path("actor").asText().equals("ADMIN")?t.admin:t.uid,null,UserRole.valueOf(c.path("actor").asText()));
            Set<String> declared=c.path("skill").isNull()?null:t.skillRegistry.require(c.path("skill").asText(),principal.role(),AgentModelProvider.Subject.ORDER).domains();
            Set<String> domains=effectiveDomains(row,declared);
            var subqueries=split(c.path("question").asText());var lists=new ArrayList<List<KnowledgeHit>>();
            for(String q:subqueries)lists.add(search(t,principal,q,domains).hits());
            var mixed=new ArrayList<KnowledgeHit>();
            for(int rank=0;rank<5;rank++)for(var list:lists)if(rank<list.size())mixed.add(list.get(rank));
            var selected=EvidenceSelector.select(mixed,5,2);var input=original.deepCopy();input.set("knowledge",t.json.valueToTree(selected));
            var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("scope","TWO_CLAUSE_REAL_RETRIEVAL_CAPTURED_INPUT_SYNTHESIS_NOT_HTTP_OR_PRODUCT_ADOPTION");
            result.put("queries",subqueries);result.put("hits",selected);result.put("metrics",metrics(t.json,dataset,c,selected));result.put("input",input);
            if(!selected.isEmpty()&&metrics(t.json,dataset,c,selected).path("supportRecallAt5").asDouble()>
                (row.get("actualRetrievalMetrics") instanceof JsonNode m?m.path("supportRecallAt5").asDouble():0)) {
                var route=AgentModelProvider.Route.valueOf(((JsonNode)row.get("answer")).path("route").asText());
                var generated=t.runner.model().synthesize(c.path("question").asText(),route,input,Duration.ofSeconds(30));
                AgentEvidence.validate(generated,route,input);result.put("generated",generated);
            }
            comparisons.add(result);t.write("novel-two-clause-comparison.private.json",comparisons);
            }catch(Exception failure){failures.add(id+"_COMPARISON");comparisons.add(Map.of("id",id,"status","FAILED","type",failure.getClass().getSimpleName()));t.write("novel-two-clause-comparison.private.json",comparisons);}
        }
        if(t.runner.identity().path("novelFollowup").asBoolean()&&!Files.exists(t.run.resolve("STOP")))followupComponents(t,failures);
        assertThat(t.cuts.fingerprint(t.cuts.cut())).isEqualTo(before);
        t.write("completion.private.json",Map.of("status","EXECUTED_QUALITY_AND_GRAPH_DECISION_REQUIRE_EVIDENCE_REVIEW","technicalFailures",failures,
            "baselineRows",t.rows.size(),"comparisonRows",comparisons.size(),"budget",Files.exists(t.run.resolve("STOP"))?t.json.nullNode():t.runner.summary(),"financialUnchanged",true));
        assertThat(failures).as("Record independent outcomes before reporting technical failures").isEmpty();
    }
    static void followupComponents(LatestAiAcceptanceTest t,List<String> failures)throws Exception {
        var extra=t.json.readTree(Path.of("src/test/resources/ai/novel-followup-v1.json").toFile());
        Files.writeString(t.run.resolve("frozen-followup.private.json"),t.json.writeValueAsString(extra),StandardOpenOption.CREATE_NEW);
        var results=new ArrayList<Object>();
        for(var c:extra.path("independentPlanningCases")) {
            var plan=t.runner.model().planWithSkills(c.path("question").asText(),AgentModelProvider.Subject.NONE,
                t.skillRegistry.eligible(UserRole.USER,AgentModelProvider.Subject.NONE),Duration.ofSeconds(15));
            boolean ok=plan.route()==AgentModelProvider.Route.KNOWLEDGE&&plan.subject()==AgentModelProvider.Subject.NONE&&"NONE".equals(plan.skillId());
            results.add(Map.of("id",c.path("id").asText(),"scope","ACTUAL_PLANNING_ONLY_NOT_HTTP_OR_FULL_ANSWER","plan",plan,"correct",ok));
            if(!ok)failures.add(c.path("id").asText());
            t.write("novel-followup-components.private.json",results);
        }
        Path parent=Path.of(t.runner.identity().at("/verificationLineage/parent").asText());
        var prior=t.json.readTree(parent.resolve("questions.private.json").toFile());
        var a5=java.util.stream.StreamSupport.stream(prior.spliterator(),false).filter(r->r.path("questionId").asText().equals("A5")).findFirst().orElseThrow();
        var input=(ObjectNode)a5.path("modelEvidence").deepCopy();
        var generated=t.runner.model().synthesize(a5.at("/request/question").asText(),AgentModelProvider.Route.KNOWLEDGE,input,Duration.ofSeconds(30));
        AgentEvidence.validate(generated,AgentModelProvider.Route.KNOWLEDGE,input);
        results.add(Map.of("id","A5-captured","scope","SAME_CAPTURED_INPUT_SYNTHESIS_NOT_HTTP","input",input,"generated",generated));
        t.write("novel-followup-components.private.json",results);
    }
    static LinkedHashMap<String,Object> mutableRow(Map<String,Object> row){return new LinkedHashMap<>(row);}
    static Set<String> effectiveDomains(Map<String,Object> row,Set<String> declared){
        if(row.get("actualRetrievalDomains") instanceof Set<?> actual&&!actual.isEmpty()) {
            var result=new HashSet<String>();for(Object value:actual) {
                if(!(value instanceof String s))throw new IllegalArgumentException("INVALID_CAPTURED_DOMAINS");result.add(s);
            }
            return Set.copyOf(result);
        }
        return declared==null?null:Set.copyOf(declared);
    }
    static Object arguments(String name,LatestAiAcceptanceTest t,String id) {
        if(name.startsWith("getFutures"))return Map.of("positionId",id.equals("C3")?t.unverifiedPid:t.pid);
        if(name.equals("getQuote"))return Map.of("quoteId",t.quote);
        if(Set.of("getOrder","getBlockchainTransaction","getReceiptSummary").contains(name))return Map.of("orderId",id.equals("F4")?t.pendingOrder:t.order);
        return Map.of();
    }
    static AuthorizedKnowledgeRetrieval.Evidence search(LatestAiAcceptanceTest t,AuthenticatedUser actor,String q,Set<String> domains) {
        return domains==null?t.retrieval.search(actor,q,Duration.ofSeconds(15)):t.retrieval.searchScoped(actor,q,Duration.ofSeconds(15),domains);
    }
    static List<String> split(String q) {
        var parts=Arrays.stream(q.split("(?<=[.?!])\\s+")).filter(s->!s.isBlank()).toList();
        if(parts.size()<2)return List.of(q);
        int middle=parts.size()/2;
        return List.of(String.join(" ",parts.subList(0,middle)),String.join(" ",parts.subList(middle,parts.size())));
    }
    static ObjectNode metrics(ObjectMapper json,JsonNode dataset,JsonNode c,List<KnowledgeHit> hits) {
        int supported=0,first=0,unrelated=0;
        for(var key:c.path("atoms")) {
            var a=dataset.path("atoms").path(key.asText());
            for(int i=0;i<hits.size();i++)if(matches(hits.get(i),a)){supported++;if(first==0||i+1<first)first=i+1;break;}
        }
        for(var hit:hits){boolean match=false;for(var key:c.path("atoms"))if(matches(hit,dataset.path("atoms").path(key.asText())))match=true;if(!match)unrelated++;}
        return json.createObjectNode().put("hitAt5",first==0?0:1).put("mrrAt5",first==0?0:1.0/first)
            .put("supportRecallAt5",supported/(double)c.path("atoms").size()).put("allSupportAt5",supported==c.path("atoms").size())
            .put("nonMatchingCandidateCount",unrelated).put("nonMatchingIsNotHallucination",true);
    }
    static boolean matches(KnowledgeHit h,JsonNode atom){return h.path().equals(atom.path("path").asText())&&h.content().contains(atom.path("needle").asText());}
}
