package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.ai.agent.*;
import com.pricetrack.exchange.ai.agent.AgentModelProvider.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeLoader;
import com.pricetrack.exchange.ai.observability.AiObservability;
import com.pricetrack.exchange.ai.skill.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.*;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Paid opt-in evaluation. Actual model/pgvector; trading reads are explicit
 * controlled fixtures, Futures facts are preserved canonical E2E snapshots,
 * NOT fresh RPC or a new trade/Chrome acceptance run. No financial writes.
 */
@EnabledIfEnvironmentVariable(named="AI_APPROVED_MODEL_EVALUATION",matches="true")
class ApprovedAiModelEvaluationTest {
    static List<String> fixedQuestions(){return List.of("quoteId는 왜 한 번만 사용할 수 있나요?","현재 기준가격과 시장 상태를 조회해 주세요.","내 가용 잔고와 포트폴리오를 조회해 주세요.","내 주문의 현재 상태만 조회해 주세요.","내 견적의 상태와 만료 정책을 함께 설명해 주세요.","이 주문의 체인 전송 상황과 SIGNED, SUBMITTED의 의미를 함께 설명해주세요.","이 주문이 체인에 확정됐는지 현재 근거와 확정 정책을 함께 설명해주세요.","REVIEW_REQUIRED 이상 주문 목록을 조회해 주세요.","이 주문의 정산 지연을 진단해주세요.","이 견적의 상태와 만료를 진단해주세요.","시장 거래 가용성을 진단해주세요.","내 Long 포지션은 왜 강제청산됐나요? 기록된 근거로 진단해주세요.","이 포지션의 마진콜 가격과 유지증거금 5퍼센트 판단 근거를 설명해주세요.","이 청산은 실제 체인에 확정됐는지 보존된 Receipt와 이벤트 근거 및 한계를 설명해주세요.","청산 후 손익과 DB journal 기록을 근거로 정합성과 한계를 설명해주세요.","이 포지션의 가격과 Receipt 근거가 불확실할 때 무엇을 확인할 수 있는지 진단해주세요.");}
    final ObjectMapper json=EvaluationJson.mapper();
    final AuthenticatedUser user=new AuthenticatedUser(910001L,null,UserRole.USER),admin=new AuthenticatedUser(910002L,null,UserRole.ADMIN);
    final String quote="0x"+"aa".repeat(32);
    Path run;final List<Map<String,Object>> rows=new ArrayList<>();final AtomicBoolean uncertain=new AtomicBoolean(),unverified=new AtomicBoolean();
    ObjectNode node(Object... fields){var n=json.createObjectNode();for(int i=0;i<fields.length;i+=2)n.set(fields[i].toString(),json.valueToTree(fields[i+1]));return n;}
    void write(String name,Object value)throws Exception {
        Path p=run.resolve(name);byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try(var f=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())f.write(buffer);f.force(true);}
    }
    void own(ToolContext ctx){ctx.requireAuthenticated();if(!ctx.admin()&&!Objects.equals(ctx.userId(),user.userId()))throw new ToolFailure("RESOURCE_NOT_FOUND");}

    @Test void approvedIndexFixedRetrievalAndSixteenAgentQuestions()throws Exception {
        run=Path.of(Objects.requireNonNull(System.getenv("AI_APPROVED_EVALUATION_RUN"))).toRealPath();
        // Evaluation itself is not replayed after a crash just to obtain a PASS.
        Files.writeString(run.resolve("EVALUATION_STARTED"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
        Path repo=Path.of("..").toAbsolutePath().normalize();
        var meta=json.readTree(run.resolve("run.json").toFile());boolean child=meta.has("lineage");
        if(child)assertThat(meta.path("questions")).isEqualTo(json.valueToTree(java.util.stream.IntStream.rangeClosed(2,16).boxed().toList()));
        var captured=json.readTree(Path.of(Objects.requireNonNull(System.getenv("AI_APPROVED_EVIDENCE_SNAPSHOT"))).toFile());
        var fixture=EvaluationToolFixture.preserved(json,captured);String pid=fixture.pid;
        var props=new Properties();try(var in=Files.newInputStream(run.resolve("db.env"))){props.load(in);}
        try {
            write("database-preflight.json",child?EvaluationDatabasePreflight.ready(meta,props.getProperty("POSTGRES_PASSWORD")):EvaluationDatabasePreflight.prepare(meta,props.getProperty("POSTGRES_PASSWORD")));
        }catch(Exception failure) {
            write("stopped.json",Map.of("status","BLOCKED","reason","FREE_DATABASE_PREFLIGHT_FAILED","completedQuestions",0,"diagnostic",EvaluationFailureDiagnostic.safe(failure)));
            Files.writeString(run.resolve("STOP"),"STOPPED",StandardOpenOption.CREATE_NEW);
            throw new IllegalStateException("FREE_DATABASE_PREFLIGHT_FAILED_SAFE");
        }
        try(var runner=new GuardedAiEvaluationRunner(repo,run,Path.of(System.getenv().getOrDefault("AI_EVAL_TEST_PYTHON","python")),GuardedAiEvaluationRunner.Mode.LIVE,run.resolve("approval.json"),json);
            var tools=fixture.dispatcher()){
            var usageOrdinal=new AtomicInteger();
            runner.provider().observe(new AiObservability(){@Override public void record(String stage,String name,String id,long start,boolean success,String code,long docs,Long input,Long output){
                if(!Set.of("LLM","EMBEDDING").contains(stage))return;
                try{var row=new LinkedHashMap<String,Object>();row.put("ordinal",usageOrdinal.incrementAndGet());row.put("stage",stage);row.put("success",success);row.put("inputTokens",input);row.put("outputTokens",output);row.put("code",success?"OK":"PROVIDER_FAILURE");row.put("reserved",runner.summary());
                    write(String.format("usage-%03d.json",usageOrdinal.get()),row);
                    if(input==null||output==null){uncertain.set(true);runner.stop();}
                }catch(Exception failure){uncertain.set(true);runner.stop();}
            }});
            try {
                write("preflight.json",runner.preflight());
                runner.prepareIndex(props.getProperty("POSTGRES_PASSWORD"),false,admin);
                write("index.json",Map.of("status","READY","index",new KnowledgeLoader(runner.properties(),json).load().fingerprint(),"usage",runner.summary()));
                var retrieval=runner.retrieval();var searchRows=new ArrayList<Map<String,Object>>();
                var golden=json.readTree(Path.of("src/test/resources/ai/retrieval-golden.json").toFile());
                var supplement=json.readTree(Path.of("src/test/resources/ai/retrieval-supplement.json").toFile());
                for(String group:child?List.<String>of():List.of("golden","negative","identifiers")){
                    JsonNode questions=group.equals("golden")?golden:supplement.path(group);
                    for(var q:questions){
                        String question=q.isTextual()?q.asText():q.path("question").asText();
                        var hits=retrieval.search(admin,question,Duration.ofSeconds(25)).hits();
                        var expected=new HashSet<String>();q.path("expected").forEach(e->expected.add(e.asText()));
                        int rank=0;for(int i=0;i<hits.size();i++)if(expected.contains(hits.get(i).path())){rank=i+1;break;}
                        searchRows.add(Map.of("group",group,"question",question,"rank",rank,"reciprocalRank",rank==0?0d:1d/rank,"hits",hits));
                        write("retrieval.json",searchRows);if(uncertain.get())throw new IllegalStateException("UNCERTAIN_STOP");
                    }
                }
                String[] questions=fixedQuestions().toArray(String[]::new);
                var model=runner.model();final AgentModelProvider.Generated[] generated={null};
                var capturing=new OpenAiAgentProvider(runner.provider(),json){@Override public Generated synthesize(String q,Route route,JsonNode e,Duration timeout){generated[0]=super.synthesize(q,route,e,timeout);return generated[0];}};
                try(var agent=new AgentService(new AgentProperties(true),()->capturing,()->retrieval,tools,json,Duration.ofSeconds(40),new SkillProperties(true),new SkillRegistry(json))){
                    // Free authorization boundary checks happen before any model request.
                    var foreign=new AuthenticatedUser(910003L,null,UserRole.USER);
                    var foreignResult=agent.answer(foreign,json.writeValueAsBytes(Map.of("question","포지션 조회","target",Map.of("positionId",pid))));
                    assertThat(foreignResult.httpStatus()).isEqualTo(404);assertThat(foreignResult.metrics().modelCalls()).isZero();
                    assertThat(tools.invoke(user,"listAbnormalOrders",json.writeValueAsBytes(Map.of("arguments",Map.of()))).error()).isEqualTo("TOOL_FORBIDDEN");
                    write("authorization.json",Map.of("foreignUser404",true,"userAdminToolForbidden",true,"dataSource","CONTROLLED_FIXTURE_OWNERSHIP_CHECK_NOT_NEW_JPA_E2E"));
                    for(int i=child?1:0;i<questions.length;i++){
                        if(uncertain.get())throw new IllegalStateException("UNCERTAIN_STOP");
                        var body=new LinkedHashMap<String,Object>();body.put("question",questions[i]);
                        if(Set.of(3,5,6,8).contains(i))body.put("target",Map.of("orderId",1));
                        if(Set.of(4,9).contains(i))body.put("target",Map.of("quoteId",quote));
                        if(i>=11)body.put("target",Map.of("positionId",pid));
                        String skill=switch(i){case 8->"settlement-debugging";case 9->"signed-quote-diagnosis";case 10->"market-availability-diagnosis";case 11,15->"futures-liquidation-diagnosis";default->null;};
                        if(skill!=null)body.put("skillId",skill);
                        if(i==15)fixture.unverified.set(true);
                        generated[0]=null;var result=agent.answer(i==7||i==8?admin:user,json.writeValueAsBytes(body));
                        var row=new LinkedHashMap<String,Object>();row.put("number",i+1);row.put("question",questions[i]);row.put("result",result);row.put("generated",generated[0]);
                        row.put("providerUsage",runner.summary());row.put("verdict",EvaluationVerdict.classify(result,true,false));row.put("source","ACTUAL_LLM_PGVECTOR_WITH_CONTROLLED_TRADING_READS_AND_PRESERVED_FUTURES_SNAPSHOT");
                        rows.add(row);write("questions.json",rows);
                        if(uncertain.get())throw new IllegalStateException("UNCERTAIN_STOP");
                    }
                }
                write("completion.json",Map.of("status","EVALUATION_EXECUTED_NOT_AUTOMATIC_PASS","questions",rows.size(),"usage",runner.summary()));
            }catch(Exception failure){
                try{var stopped=new LinkedHashMap<String,Object>();stopped.put("status","BLOCKED");stopped.put("reason","EVALUATION_STOPPED_NO_RETRY");stopped.put("completedQuestions",rows.size());
                    stopped.put("diagnostic",EvaluationFailureDiagnostic.safe(failure));
                    try{stopped.put("usage",runner.summary());}catch(Exception unavailable){stopped.put("summaryUnavailable",true);}
                    write("stopped.json",stopped);}catch(Exception ignored){}
                runner.stop();throw new IllegalStateException("APPROVED_AI_EVALUATION_STOPPED_NO_RETRY");
            }
        }
    }
}
