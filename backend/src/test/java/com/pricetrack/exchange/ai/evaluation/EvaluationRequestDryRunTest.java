package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.OpenAiProvider;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Actual Java provider serializes every request; transport only returns local fixtures.
 * Largest eligible chunks bound context without claiming actual query similarity.
 */
class EvaluationRequestDryRunTest {
    final ObjectMapper json=EvaluationJson.mapper();
    @Test void exactQ2to16SerializedBodiesWithoutHttpOrKey()throws Exception {
        var p=new AiProperties(true,"jdbc:postgresql://127.0.0.1:59999/exchange_ai_eval_dryrun","dryrun","",
            "../docs/ai-knowledge","../docs/ai/ingest-manifest.json","","guarded:stdio/","text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
        var corpus=new KnowledgeLoader(p,json).load();var plans=new ArrayList<Map<String,Object>>();
        String source=sourceHash();
        String snapshot=System.getenv("AI_DRY_RUN_EVIDENCE");
        for(boolean maximum:List.of(false,true)){
            var fixture=snapshot==null?EvaluationToolFixture.offline(json):EvaluationToolFixture.preserved(json,json.readTree(Path.of(snapshot).toFile()));
            var question=new AtomicInteger();var requests=new ArrayList<Map<String,Object>>();
            var provider=OpenAiProvider.forGuardedEvaluation(p,json,(path,raw,timeout)->{
                try{
                var b=json.readTree(raw);var recorded=new LinkedHashMap<String,Object>();recorded.put("question",question.get()+1);recorded.put("path",path);recorded.put("bodyBase64",Base64.getEncoder().encodeToString(raw));requests.add(recorded);
                if(path.equals("embeddings")){
                    var reply=json.createObjectNode();reply.putObject("usage").put("prompt_tokens",0);var v=reply.putArray("data").addObject();v.put("index",0);var values=v.putArray("embedding");values.add(1);for(int j=1;j<1536;j++)values.add(0);return reply;
                }
                JsonNode input=json.readTree(b.path("input").asText());String name=b.at("/text/format/name").asText();Object answer;
                if(name.equals("agent_plan")){
                    int i=question.get();String subject=input.path("targetKind").asText();if(subject.equals("NONE"))subject=i==2?"PORTFOLIO":i==7?"ABNORMAL":"PRICE";
                    answer=Map.of("route",!maximum&&Set.of(1,2,3,7).contains(i)?"STATE":"MIXED","subject",subject,"skillId","NONE");
                }else{
                    var e=input.path("evidence");var first=e.path("tools").get(0);var data=first.path("data");
                    String pointer=data.has("position")?"/position/status":data.has("status")?"/status":data.has("storedStatus")?"/storedStatus":data.has("totalValue")?"/totalValue":data.has("filter")?"/filter":"/marketStatus";
                    answer=Map.of("status","ANSWERED","interpretation","제공된 정책과 조회 사실을 설명합니다.","citationIds",List.of(e.path("knowledge").get(0).path("id").asText(),first.path("evidenceId").asText()),
                        "facts",List.of(Map.of("evidenceId",first.path("evidenceId").asText(),"pointer",pointer,"value",data.at(pointer).asText())),"uncertainties",List.of(),"recommendedNextCheck",List.of());
                }
                return json.valueToTree(Map.of("status","completed","service_tier","default","usage",Map.of("input_tokens",0,"output_tokens",0),"output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",json.writeValueAsString(answer)))))));
                }catch(Exception failure){throw new IllegalStateException("LOCAL_DRY_RUN_SERIALIZATION_FAILED");}
            });
            var retrieval=mock(AuthorizedKnowledgeRetrieval.class);
            org.mockito.stubbing.Answer<AuthorizedKnowledgeRetrieval.Evidence> search=c->{
                var user=(com.pricetrack.exchange.auth.AuthenticatedUser)c.getArgument(0);String query=c.getArgument(1);
                provider.embed(List.of(query),Duration.ofSeconds(2));
                Set<String> domains=c.getArguments().length>3?c.getArgument(3):null;var counts=new HashMap<String,Integer>();
                var hits=corpus.chunks().stream().filter(ch->user.role()==com.pricetrack.exchange.user.UserRole.ADMIN||ch.role().equals("USER"))
                    .filter(ch->domains==null||domains.contains(ch.domain())).sorted(Comparator.comparingInt((KnowledgeCorpus.Chunk ch)->ch.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).reversed())
                    .filter(ch->counts.merge(ch.path(),1,Integer::sum)<=2).limit(5)
                    .map(ch->new KnowledgeHit(ch.id(),ch.path(),ch.title(),ch.heading(),ch.version(),ch.role(),ch.content(),.8)).toList();
                return new AuthorizedKnowledgeRetrieval.Evidence(corpus.fingerprint(),hits);
            };
            when(retrieval.search(any(),any(),any())).thenAnswer(search);when(retrieval.searchScoped(any(),any(),any(),any())).thenAnswer(search);
            try(var tools=fixture.dispatcher();var agent=new AgentService(new AgentProperties(true),()->new OpenAiAgentProvider(provider,json),()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))){
                for(int i=1;i<16;i++){
                    question.set(i);if(i==15)fixture.unverified.set(true);
                    var r=agent.answer(i==7||i==8?fixture.admin:fixture.user,body(i,fixture));
                    assertThat(r.httpStatus()).as("Q%d error=%s uncertainties=%s",i+1,r.error(),r.uncertainties()).isEqualTo(200);assertThat(r.uncertainties()).as("Q%d",i+1).doesNotContain("SYNTHESIS_UNAVAILABLE","RAG_UNAVAILABLE","AGENT_CONTEXT_LIMIT");
                    assertThat(r.status()).isIn("ANSWERED","PARTIAL");
                }
            }
            plans.add(Map.of("profile",maximum?"MAXIMUM_MIXED":"EXPECTED_STATE","requests",requests));
        }
        assertThat(sourceHash()).isEqualTo(source);
        String out=System.getenv("AI_DRY_RUN_OUTPUT");if(out!=null)Files.writeString(Path.of(out),json.writeValueAsString(Map.of("providerCalls",0,"source","ACTUAL_SERIALIZATION_LOCAL_TRANSPORT_MAX_ELIGIBLE_CHUNKS","sourceHash",source,"processedQuestions",java.util.stream.IntStream.rangeClosed(2,16).boxed().toList(),"preservedFuturesEvidence",snapshot!=null,"profiles",plans)),StandardOpenOption.CREATE_NEW);
        assertThat(plans).hasSize(2);
    }
    String sourceHash()throws Exception {
        var p=new ProcessBuilder(System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python"),"-B","-S","-c","import sys; sys.path.insert(0,'../deployment'); from ai_evaluation_broker import source_hash; print(source_hash())").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();return new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();
    }
    byte[] body(int i,EvaluationToolFixture f)throws Exception {
        var b=new LinkedHashMap<String,Object>();b.put("question",ApprovedAiModelEvaluationTest.fixedQuestions().get(i));
        if(Set.of(3,5,6,8).contains(i))b.put("target",Map.of("orderId",1));if(Set.of(4,9).contains(i))b.put("target",Map.of("quoteId",f.quote));if(i>=11)b.put("target",Map.of("positionId",f.pid));
        String skill=switch(i){case 8->"settlement-debugging";case 9->"signed-quote-diagnosis";case 10->"market-availability-diagnosis";case 11,15->"futures-liquidation-diagnosis";default->null;};if(skill!=null)b.put("skillId",skill);
        return json.writeValueAsBytes(b);
    }
}
