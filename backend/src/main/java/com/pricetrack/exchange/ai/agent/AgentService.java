package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.ai.skill.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.*;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

/** Bounded stateless orchestration. Never opens a trading transaction or accesses domain repositories. */
public final class AgentService implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(AgentService.class);
    private final AgentProperties properties;
    private final Supplier<AgentModelProvider> model;
    private final Supplier<AuthorizedKnowledgeRetrieval> retrieval;
    private final ToolDispatcher tools;
    private final ObjectMapper json;
    private final Duration totalBudget;
    private final ThreadPoolExecutor workers;
    private final SkillProperties skills;
    private final SkillRegistry registry;
    private com.pricetrack.exchange.ai.observability.AiObservability observation;
    public AgentService observe(com.pricetrack.exchange.ai.observability.AiObservability value){observation=value;return this;}
    // Retained until the actual worker exits, including a timed-out non-cooperative call.
    private final java.util.concurrent.atomic.AtomicBoolean automaticRunning=new java.util.concurrent.atomic.AtomicBoolean();
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json){this(p,model,retrieval,tools,json,Duration.ofSeconds(p.totalSeconds()));}
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json,Duration budget){
        this(p,model,retrieval,tools,json,budget,new SkillProperties(false),new SkillRegistry(json));
    }
    public AgentService(AgentProperties p,Supplier<AgentModelProvider> model,Supplier<AuthorizedKnowledgeRetrieval> retrieval,
                        ToolDispatcher tools,ObjectMapper json,Duration budget,SkillProperties skills,SkillRegistry registry){
        this.skills=skills;this.registry=registry;
        this.properties=p;this.model=model;this.retrieval=retrieval;this.tools=tools;this.totalBudget=budget;
        this.json=json.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        workers=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r -> {
            Thread t=new Thread(r,"read-agent");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public AgentResponse answer(AuthenticatedUser principal,byte[] input){
        return answer(principal,input,false);
    }
    /** Preserve automatic diagnosis's original 40s budget below its existing 90s DB claim lease. */
    long requestBudgetNanos(boolean automatic){return automatic?Math.min(totalBudget.toNanos(),TimeUnit.SECONDS.toNanos(40)):totalBudget.toNanos();}
    /** Server-only fixed procedure. HTTP cannot supply an origin or an automatic execution plan. */
    public AgentResponse answerAutomatic(AuthenticatedUser principal,long orderId){
        if(principal==null || principal.role()!=com.pricetrack.exchange.user.UserRole.ADMIN || orderId<=0)
            return AgentResponse.failure(UUID.randomUUID().toString(),"TOOL_FORBIDDEN",403);
        byte[] input=("{\"question\":\"이 주문의 검토 필요 상태와 정산 근거를 조사해줘\",\"target\":{\"orderId\":"+orderId+"},\"skillId\":\"settlement-debugging\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return answer(principal,input,true);
    }
    private AgentResponse answer(AuthenticatedUser principal,byte[] input,boolean automatic){
        String runId=UUID.randomUUID().toString();long start=System.nanoTime();Future<AgentResponse> work=null;AgentResponse result;Run run=null;
        try{
            if(principal==null || principal.userId()==null || principal.userId()<=0 || principal.role()==null)throw new AgentFailure("AUTHENTICATION_REQUIRED",401);
            if(!properties.enabled())throw new AgentFailure("AGENT_DISABLED",503);
            AgentRequest request=AgentRequest.parse(json,input);
            var provider=model.get();
            if(provider==null)throw new AgentFailure("AGENT_CONFIGURATION_UNAVAILABLE",503);
            run=new Run(runId,principal,request,provider,start,start+requestBudgetNanos(automatic));
            if(automatic){
                if(!automaticRunning.compareAndSet(false,true))throw new RejectedExecutionException();
                final Run captured=run;
                FutureTask<AgentResponse> task=new FutureTask<>(()->observedRun(runId,captured)){
                    @Override public void run(){try{super.run();}finally{automaticRunning.set(false);}}
                };
                try{workers.execute(task);work=task;}catch(RejectedExecutionException e){automaticRunning.set(false);throw e;}
            }else {final Run captured=run;work=workers.submit(()->observedRun(runId,captured));}
            result=work.get(Math.max(1,run.deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(json.writeValueAsBytes(result).length>65_536)throw new AgentFailure("AGENT_OUTPUT_LIMIT",503);
        }catch(AgentFailure e){result=failure(runId,e.code(),e.http(),run);}
        catch(RejectedExecutionException e){result=failure(runId,"AGENT_BUSY",503,run);}
        catch(TimeoutException e){result=failure(runId,"AGENT_TIMEOUT",504,run);}
        catch(InterruptedException e){Thread.currentThread().interrupt();result=failure(runId,"AGENT_TIMEOUT",504,run);}
        catch(Exception e){result=failure(runId,"AGENT_UNAVAILABLE",503,run);}
        finally{if(work!=null && !work.isDone())work.cancel(true);}
        var m=result.metrics();
        if(observation!=null){
            String dependency=result.uncertainties().stream().filter(c->Set.of("SYNTHESIS_UNAVAILABLE","PLAN_UNAVAILABLE","RAG_UNAVAILABLE","KNOWLEDGE_APPROVAL_UNVERIFIED","AGENT_CONTEXT_LIMIT").contains(c)).findFirst().orElse(null);
            observation.record("AGENT",result.route(),runId,start,result.httpStatus()<400 && dependency==null,
                result.error()!=null?result.error():dependency!=null?dependency:result.status(),0,null,null);
        }
        if(automatic)log.info("AutomaticDiagnosis runId={} actorId={} origin=AUTO_DIAGNOSIS status={} code={}",runId,principal.userId(),result.status(),result.error());
        log.info("ReadAgent runId={} role={} route={} status={} code={} toolCalls={} retrievalCalls={} modelCalls={} inputTokens={} outputTokens={} latencyMs={}",
                runId,principal==null?null:principal.role(),result.route(),result.status(),result.error(),m.toolCalls(),m.retrievalCalls(),m.modelCalls(),
                m.inputTokens(),m.outputTokens(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        if(result.skill()!=null)log.info("ReadSkill runId={} skillId={} version={} hash={} classification={} trace={}",
                runId,result.skill().id(),result.skill().version(),result.skill().definitionHash(),result.skill().diagnosis().classification(),
                result.skill().trace().stream().map(t -> t.stepId()+":"+t.status()+":"+t.resultCode()).toList());
        return result;
    }
    private AgentResponse observedRun(String id,Run run){return com.pricetrack.exchange.ai.provider.ProviderAccess.call(run.user.userId(),id,()->observation==null?run.execute():observation.correlated(id,run::execute));}
    private AgentResponse failure(String id,String code,int http,Run run){
        if(run==null)return AgentResponse.failure(id,code,http);
        return new AgentResponse(id,"ERROR",run.route,"요청을 처리할 수 없습니다.",null,List.of(),List.of(),List.of(),
            List.of(code),List.of(),run.metrics(),code,http);
    }
    private static boolean budgetFailure(String code){return code.startsWith("AI_BUDGET_")||code.equals("AI_ACCOUNT_RATE_LIMIT");}
    private final class Run {
        final String id;final AuthenticatedUser user;final AgentRequest request;final AgentModelProvider provider;final long start,deadline;
        final LinkedHashMap<String,AgentResponse.ToolEvidence> evidence=new LinkedHashMap<>();
        final List<String> uncertainties=new ArrayList<>();List<KnowledgeHit> knowledge=List.of();String index;
        List<String> modelUncertainties=List.of();
        SkillRegistry.Definition skill;SkillRunner skillRunner;
        // One worker writes; timeout/audit thread reads observed counts without waiting for cancellation.
        volatile int toolCalls,retrievalCalls,modelCalls;volatile long inputTokens,outputTokens;volatile String route="NONE";
        Run(String id,AuthenticatedUser user,AgentRequest request,AgentModelProvider provider,long start,long deadline){
            this.id=id;this.user=user;this.request=request;this.provider=provider;this.start=start;this.deadline=deadline;
        }
        Duration remaining(int maxSeconds){long n=deadline-System.nanoTime();if(n<=0 || Thread.currentThread().isInterrupted())throw new AgentFailure("AGENT_TIMEOUT",504);return Duration.ofNanos(Math.min(n,Duration.ofSeconds(maxSeconds).toNanos()));}
        AgentResponse execute(){
            try{
                if(request.conflictingTarget())return response("NEEDS_CLARIFICATION","질문과 target을 일치시켜 다시 요청해 주세요.",List.of(),List.of());
                if(request.mutationRequest())return response("UNSUPPORTED","조회와 근거 설명만 지원합니다.",List.of(),List.of());
                if(request.skillId()!=null){
                    if(!skills.enabled())throw new AgentFailure("SKILLS_DISABLED",503);
                    skill=registry.require(request.skillId(),user.role(),request.targetKind());
                }
                if(request.orderId()!=null)preflight(call("getOrder",Map.of("orderId",request.orderId())));
                if(request.quoteId()!=null)preflight(call("getQuote",Map.of("quoteId",request.quoteId())));
                if(request.positionId()!=null)preflight(call("getFuturesPosition",Map.of("positionId",request.positionId())));
                if(request.cPositionId()!=null)preflight(call("getCPosition",Map.of("positionId",request.cPositionId())));
                if(skill!=null && skill.target()==Subject.NONE)preflight(call("getCurrentReferencePrice",Map.of()));
                Plan plan;
                try{
                    if(skill!=null)plan=new Plan(Route.MIXED,skill.target()==Subject.NONE?Subject.PRICE:skill.target(),Usage.none(),skill.id());
                    else if((plan=RequiredReadRoute.select(request))!=null) { /* current facts cannot be replaced by policy-only planning */ }
                    else if(properties.deterministicState()&&(plan=DeterministicStateRoute.select(request))!=null) { /* same ownership preflight and subject/role checks below */ }
                    else {modelCalls++;plan=skills.enabled()?provider.planWithSkills(request.modelQuestion(),request.targetKind(),registry.eligible(user.role(),request.targetKind()),remaining(properties.planningSeconds()))
                            :provider.plan(request.modelQuestion(),request.targetKind(),remaining(properties.planningSeconds()));addUsage(plan==null?null:plan.usage());}
                }
                catch(Exception e){if(e instanceof com.pricetrack.exchange.ai.AiFailure f&&budgetFailure(f.code()))return failure(id,f.code(),f.code().equals("AI_ACCOUNT_RATE_LIMIT")?429:503,this);return degraded("PLAN_UNAVAILABLE");}
                if(plan==null || plan.route()==null || plan.subject()==null)throw new AgentFailure("INVALID_AGENT_PLAN",503);
                route=plan.route().name();
                if(skill==null && plan.skillId()!=null && !plan.skillId().equals("NONE")){
                    if(!skills.enabled() || plan.route()!=Route.MIXED || !registry.eligible(user.role(),request.targetKind()).contains(plan.skillId()))
                        throw new AgentFailure("INVALID_AGENT_PLAN",503);
                    skill=registry.require(plan.skillId(),user.role(),request.targetKind());
                    if(plan.subject()!=(skill.target()==Subject.NONE?Subject.PRICE:skill.target()))throw new AgentFailure("INVALID_AGENT_PLAN",503);
                    if(skill.target()==Subject.NONE)preflight(call("getCurrentReferencePrice",Map.of()));
                }
                if(plan.route()==Route.CLARIFY)return response("NEEDS_CLARIFICATION","조회 대상을 명시해 주세요.",List.of(),List.of());
                if(plan.route()==Route.UNSUPPORTED)return response("UNSUPPORTED","조회와 프로젝트 규칙 설명만 지원합니다.",List.of(),List.of());
                if(plan.route()==Route.KNOWLEDGE && (plan.subject()!=Subject.NONE || request.targetKind()!=Subject.NONE))
                    return response("NEEDS_CLARIFICATION","정책 질문과 조회 대상을 구분해 주세요.",List.of(),List.of());
                if(plan.route()!=Route.KNOWLEDGE && (plan.subject()==Subject.NONE
                        || (plan.subject()==Subject.ORDER || plan.subject()==Subject.QUOTE || plan.subject()==Subject.FUTURES || plan.subject()==Subject.C_POSITION) && plan.subject()!=request.targetKind()
                        || request.targetKind()!=Subject.NONE && plan.subject()!=request.targetKind()))
                    return response("NEEDS_CLARIFICATION","주문/견적 target과 질문 대상을 일치시켜 주세요.",List.of(),List.of());
                if(plan.subject()==Subject.ABNORMAL && user.role()!=UserRole.ADMIN)throw new AgentFailure("TOOL_FORBIDDEN",403);
                if(skill!=null){
                    skillRunner=new SkillRunner(skill,new SkillRunner.RunContext(){
                        public AgentResponse.ToolEvidence call(String name,Map<String,Object> args){return Run.this.call(name,args);}
                        public void checkpoint(){remaining(Integer.MAX_VALUE);}
                        public void uncertainty(String code){uncertainties.add(code);}
                    });
                    skillRunner.execute(request);
                }else if(plan.route()!=Route.KNOWLEDGE)executeTools(plan);
                if((plan.subject()==Subject.C_ACCOUNT||plan.subject()==Subject.C_POSITION)&&RequiredReadRoute.select(request)==null)throw new AgentFailure("INVALID_AGENT_PLAN",503);
                if((plan.subject()==Subject.C_ACCOUNT||plan.subject()==Subject.C_POSITION)&&plan.route()==Route.STATE){
                    if(!hasFacts())return degraded("TOOL_UNAVAILABLE");
                    return response("ANSWERED",StateExplanation.explain(List.copyOf(evidence.values()))+"\n고정 블록의 현재 조회입니다. 청산 원인·개인 Claims 전체 진단 또는 전체 감사 결과는 아닙니다.",List.of(),List.of());
                }
                checkConsistency();
                if(plan.route()!=Route.STATE){
                    retrievalCalls++;
                    long retrievalStart=System.nanoTime();
                    try{
                        var retriever=retrieval.get();if(retriever==null)throw new AgentFailure("RAG_UNAVAILABLE",503);
                        var found=plan.subject()==Subject.C_ACCOUNT||plan.subject()==Subject.C_POSITION?retriever.searchScoped(user,retrievalQuestion(plan),remaining(properties.retrievalSeconds()),Set.of("custody")):skill==null?retriever.search(user,retrievalQuestion(plan),remaining(properties.retrievalSeconds()))
                                :retriever.searchScoped(user,retrievalQuestion(plan),remaining(properties.retrievalSeconds()),skill.domains());knowledge=found.hits();index=found.indexVersion();
                        if(skillRunner!=null)skillRunner.trace("RETRIEVE_POLICY","EXECUTED","RAG",knowledge.stream().map(KnowledgeHit::id).toList(),knowledge.isEmpty()?"NO_EVIDENCE":"SUCCESS",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-retrievalStart));
                    }catch(Exception e){uncertainties.add(e instanceof com.pricetrack.exchange.ai.AiFailure f&&budgetFailure(f.code())?f.code():"RAG_UNAVAILABLE");traceOnce("RETRIEVE_POLICY","UNAVAILABLE","RAG",List.of(),"RAG_UNAVAILABLE",retrievalStart);}
                }
                if(plan.route()==Route.STATE){
                    if(!hasFacts())return degraded("TOOL_UNAVAILABLE");
                    return response(uncertainties.isEmpty()?"ANSWERED":"PARTIAL",StateExplanation.explain(List.copyOf(evidence.values())),List.of(),List.of());
                }
                if(knowledge.isEmpty()){
                    uncertainties.add("KNOWLEDGE_EVIDENCE_MISSING");
                    return hasFacts()?response("PARTIAL","현재 사실은 조회했지만 관련 규칙의 근거를 확보하지 못했습니다.",List.of(),List.of())
                            :response("INSUFFICIENT_EVIDENCE","승인된 문서에서 충분한 근거를 확인하지 못했습니다.",List.of(),List.of());
                }
                if(plan.route()==Route.MIXED && !hasFacts())return response("PARTIAL","현재 상태는 확인하지 못했습니다. 조회된 정책 출처만 제공합니다.",List.of(),List.of());
                if(plan.subject()==Subject.FUTURES&&(uncertainties.contains("FUTURES_CUT_CHANGED")||evidence.values().stream().anyMatch(e->e.tool().equals("getFuturesLiquidationEvidence")
                        &&(!"SUCCESS".equals(e.status())||!"VERIFIED".equals(e.data().path("verification").asText())))))
                    return response("PARTIAL","선물 조회 사실과 정책 근거만 제공합니다. 미확정 또는 불충분한 체인·장부 근거로 청산 완료나 원인을 확정할 수 없습니다.",List.of(),List.of());
                var modelInput=AgentEvidence.modelInput(json,knowledge,List.copyOf(evidence.values()));
                if(skillRunner!=null)modelInput.set("diagnosticObservations",json.valueToTree(skillRunner.result().diagnosis()));
                if(json.writeValueAsBytes(modelInput).length>24_576)return degraded("AGENT_CONTEXT_LIMIT");
                long synthesisStart=System.nanoTime();
                try{
                    modelCalls++;var generated=provider.synthesize(request.modelQuestion(),plan.route(),modelInput,remaining(properties.synthesisSeconds()));
                    addUsage(generated==null?null:generated.usage());
                    long validationStart=System.nanoTime();boolean valid=false;
                    try{AgentEvidence.validate(generated,plan.route(),modelInput);valid=true;}
                    finally{if(observation!=null)observation.record("VALIDATION","evidence",id,validationStart,valid,valid?"OK":"INVALID_AGENT_EVIDENCE",0,null,null);}
                    traceOnce("SUMMARIZE","EXECUTED","MODEL","ANSWERED".equals(generated.status())?generated.citationIds():List.of(),"ANSWERED".equals(generated.status())?"VALIDATED":"NO_EVIDENCE",synthesisStart);
                    if(!"ANSWERED".equals(generated.status())){
                        if(hasFacts()){
                            uncertainties.add("MODEL_EVIDENCE_INSUFFICIENT");
                            return response("PARTIAL",StateExplanation.explain(List.copyOf(evidence.values()))+"\n질문한 결론은 확보된 근거만으로 확정할 수 없습니다.",List.of(),List.of());
                        }
                        return response("INSUFFICIENT_EVIDENCE","확보된 근거로 답변을 확정하지 못했습니다.",List.of(),List.of());
                    }
                    modelUncertainties=List.copyOf(generated.uncertainties());
                    return response(uncertainties.isEmpty() && modelUncertainties.isEmpty()?"ANSWERED":"PARTIAL",generated.interpretation(),generated.citationIds(),generated.recommendedNextCheck());
                }catch(Exception e){traceOnce("SUMMARIZE","UNAVAILABLE","MODEL",List.of(),"SYNTHESIS_UNAVAILABLE",synthesisStart);return degraded(e instanceof com.pricetrack.exchange.ai.AiFailure f&&budgetFailure(f.code())?f.code():"SYNTHESIS_UNAVAILABLE");}
            }catch(AgentFailure e){return failure(id,e.code(),e.http(),this);}
            catch(Exception e){return degraded("AGENT_UNAVAILABLE");}
        }
        String retrievalQuestion(Plan plan){
            if(plan.route()==Route.KNOWLEDGE)return request.modelQuestion();
            String context=switch(plan.subject()){
                case ORDER -> "주문 대기 PENDING_ONCHAIN REVIEW_REQUIRED 상태와 온체인 receipt DB 정산 확정 조건";
                case QUOTE -> "서명 견적 만료 validUntil observedAt 30초 일회용 소비 CONSUMED 정책";
                case MARKET,PRICE -> "시장 CLOSED LIVE STALE 가격 신선도 신규 거래 차단 정책";
                case PORTFOLIO -> "포트폴리오 잔고 잠금 available 내부 원장 기준";
                case ABNORMAL -> "REVIEW_REQUIRED SIGNED SUBMITTED 장애 읽기 진단 정책";
                case FUTURES -> "선물 격리증거금 손익 유지증거금 5퍼센트 마진콜 60초 유예 강제청산 FuturesEngine Receipt journal DB 수탁";
                case C_ACCOUNT,C_POSITION -> "C MarginVault cMKRW free locked house Claims 사용자 직접 승인 예치 인출 단일 RPC 고정 블록 조회 범위";
                default -> request.modelQuestion();
            };
            String question=request.modelQuestion();return question.substring(0,Math.min(700,question.length()))+"\n"+context;
        }
        void traceOnce(String step,String status,String action,List<String> refs,String code,long start){
            if(skillRunner!=null && skillRunner.result().trace().stream().noneMatch(t -> t.stepId().equals(step)))
                skillRunner.trace(step,status,action,refs,code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
        void executeTools(Plan plan){
            switch(plan.subject()){
                case ORDER -> {
                    var order=call("getOrder",Map.of("orderId",request.orderId()));
                    if(plan.route()==Route.MIXED){
                        var tx=call("getBlockchainTransaction",Map.of("orderId",request.orderId()));
                        if("SUCCESS".equals(tx.status()) && "LINKED".equals(tx.data().path("linkStatus").asText()))
                            call("getReceiptSummary",Map.of("orderId",request.orderId()));
                        if("SUCCESS".equals(order.status()) && order.data().hasNonNull("quoteId"))
                            call("getQuote",Map.of("quoteId",order.data().path("quoteId").asText()));
                    }
                }
                case QUOTE -> call("getQuote",Map.of("quoteId",request.quoteId()));
                case MARKET -> call("getMarketStatus",Map.of());
                case PRICE -> call("getCurrentReferencePrice",Map.of());
                case PORTFOLIO -> call("getPortfolio",Map.of());
                case ABNORMAL -> call("listAbnormalOrders",RequiredReadRoute.abnormal(request));
                case C_ACCOUNT -> preflight(call("getCAccount",Map.of()));
                case C_POSITION -> preflight(call("getCPosition",Map.of("positionId",request.cPositionId())));
                case FUTURES -> {call("getFuturesPosition",Map.of("positionId",request.positionId()));if(plan.route()==Route.MIXED)call("getFuturesLiquidationEvidence",Map.of("positionId",request.positionId()));}
                default -> throw new AgentFailure("INVALID_AGENT_PLAN",503);
            }
        }
        AgentResponse.ToolEvidence call(String name,Map<String,Object> args){
            String key=name+args;
            if(evidence.containsKey(key))return evidence.get(key);
            if(++toolCalls>4)throw new AgentFailure("AGENT_TOOL_BUDGET",503);
            try{
                var result=tools.invokeForAgent(user,name,json.writeValueAsBytes(Map.of("arguments",args)),id,remaining(5));
                var item=AgentResponse.ToolEvidence.from("tool-"+toolCalls,result);evidence.put(key,item);
                if(!"SUCCESS".equals(result.status()))uncertainties.add(name+":"+result.error());
                return item;
            }catch(AgentFailure e){throw e;}catch(Exception e){throw new AgentFailure("TOOL_UNAVAILABLE",503);}
        }
        void preflight(AgentResponse.ToolEvidence result){if(!"SUCCESS".equals(result.status())){
            int http=switch(result.error()){case "RESOURCE_NOT_FOUND" -> 404;case "TOOL_FORBIDDEN" -> 403;case "TOOL_TIMEOUT" -> 504;default -> 503;};
            throw new AgentFailure(result.error(),http);
        }}
        boolean hasFacts(){return evidence.values().stream().anyMatch(e -> "SUCCESS".equals(e.status()));}
        void checkConsistency(){
            JsonNode order=null,quote=null,futuresPosition=null,futuresEvidence=null;
            for(var item:evidence.values())if("SUCCESS".equals(item.status())){
                JsonNode data=item.data();
                checkFreshness(data);
                if(data.hasNonNull("reference"))checkFreshness(data.path("reference"));
                if(item.tool().equals("getOrder"))order=data;
                if(item.tool().equals("getQuote"))quote=data;
                if(item.tool().equals("getFuturesLiquidationEvidence")&&!data.path("verification").asText().equals("VERIFIED"))uncertainties.add("FUTURES_EVIDENCE_UNVERIFIED");
                if(item.tool().equals("getFuturesLiquidationEvidence"))futuresEvidence=data;
                if(item.tool().equals("getFuturesPosition")){
                    var p=data.path("position");
                    futuresPosition=p;
                    if(p.hasNonNull("pendingOperation")||p.path("status").asText().equals("REVIEW_REQUIRED"))uncertainties.add("FUTURES_STATE_UNCONFIRMED");
                    if(p.path("status").asText().equals("OPEN")&&!p.path("priceAvailable").asBoolean())uncertainties.add("FUTURES_PRICE_UNAVAILABLE");
                }
                if(data.path("orderLink").asText().equals("INCONSISTENT_LINK"))uncertainties.add("INCONSISTENT_LINK");
                if(data.has("amountRelation")&&!data.path("amountRelation").path("verification").asText().equals("QUOTED_ARITHMETIC_ONLY_NOT_EXECUTION"))uncertainties.add("AMOUNT_RELATION_UNVERIFIED");
                if(item.tool().equals("getReceiptSummary")){
                    if(data.path("receiptStatus").asText().equals("NOT_FOUND"))uncertainties.add("RECEIPT_NOT_FOUND");
                    if(data.path("eventValidation").asText().equals("MISMATCH"))uncertainties.add("RECEIPT_EVENT_MISMATCH");
                    if(data.has("confirmations") && data.path("confirmations").asLong()<data.path("requiredConfirmations").asLong())
                        uncertainties.add("CONFIRMATIONS_INSUFFICIENT");
                    if(data.path("executionStatus").asText().equals("SUCCESS") && !data.path("databaseStatus").asText().equals("CONFIRMED"))
                        uncertainties.add("CHAIN_SUCCESS_DB_NOT_CONFIRMED");
                }
            }
            if(futuresPosition!=null&&futuresEvidence!=null&&!FuturesEvidenceConsistency.samePosition(futuresPosition,futuresEvidence))uncertainties.add("FUTURES_CUT_CHANGED");
            if(order!=null && quote!=null)for(String field:List.of("side","inputAmount","inputSymbol","outputSymbol"))
                if(order.hasNonNull(field) && quote.hasNonNull(field) && !sameFact(field,order.get(field).asText(),quote.get(field).asText()))
                    uncertainties.add("ORDER_QUOTE_MISMATCH:"+field);
        }
        boolean sameFact(String field,String first,String second){
            if(field.equals("inputAmount"))try{return new java.math.BigDecimal(first).compareTo(new java.math.BigDecimal(second))==0;}
                catch(NumberFormatException e){return false;}
            return first.equals(second);
        }
        void checkFreshness(JsonNode data){
            String freshness=data.path("priceStatus").asText();
            if(Set.of("STALE","DEGRADED","INITIALIZING").contains(freshness))uncertainties.add("PRICE_"+freshness);
            if(data.has("priceStatus") && (!data.hasNonNull("observedAt") || invalidObservation(data.path("observedAt").asText())))
                uncertainties.add("PRICE_OBSERVATION_UNVERIFIED");
        }
        boolean invalidObservation(String timestamp){
            try{java.time.Instant.parse(timestamp);return false;}
            catch(java.time.format.DateTimeParseException e){return true;}
        }
        void addUsage(Usage usage){if(usage!=null){inputTokens+=Math.max(0,usage.inputTokens());outputTokens+=Math.max(0,usage.outputTokens());}}
        AgentResponse degraded(String code){
            uncertainties.add(code);
            if(hasFacts() || !knowledge.isEmpty())return response("PARTIAL",(hasFacts()?StateExplanation.explain(List.copyOf(evidence.values()))+"\n":"")+"확보된 근거만 제공합니다. 추가 해석은 확인하지 못했습니다.",List.of(),List.of());
            return failure(id,code,code.equals("AGENT_TIMEOUT")?504:503,this);
        }
        AgentResponse.Metrics metrics(){return new AgentResponse.Metrics(toolCalls,retrievalCalls,modelCalls,inputTokens,outputTokens,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}
        AgentResponse response(String status,String answer,List<String> citations,List<String> next){
            remaining(Integer.MAX_VALUE);
            if(skill!=null)registry.verify(skill);
            if(skillRunner!=null && skillRunner.result().trace().stream().noneMatch(t -> t.stepId().equals("SUMMARIZE")))
                skillRunner.trace("SUMMARIZE","SKIPPED","MODEL",List.of(),"EVIDENCE_UNAVAILABLE",0);
            // Every output path, including provider-failure PARTIAL, revalidates approval before exposing documents.
            long approvalStart=System.nanoTime();boolean approved=false;
            if(!knowledge.isEmpty())try{
                var retriever=retrieval.get();if(retriever==null)throw new AgentFailure("RAG_UNAVAILABLE",503);
                retriever.verify(index);approved=true;
            }catch(Exception e){
                knowledge=List.of();index=null;modelUncertainties=List.of();uncertainties.add("KNOWLEDGE_APPROVAL_UNVERIFIED");
                if(skillRunner!=null)skillRunner.discardPolicy();
                if(!hasFacts())return failure(id,"KNOWLEDGE_APPROVAL_UNVERIFIED",503,this);
                status="PARTIAL";answer="현재 조회 사실만 제공합니다. 문서 승인 상태를 확인하지 못해 정책 근거는 제외했습니다.";
                citations=List.of();next=List.of();
            }finally{if(observation!=null)observation.record("VALIDATION","approval",id,approvalStart,approved,approved?"OK":"KNOWLEDGE_APPROVAL_UNVERIFIED",0,null,null);}
            remaining(Integer.MAX_VALUE);
            var safeUncertainties=new LinkedHashSet<>(uncertainties);safeUncertainties.addAll(modelUncertainties);
            return new AgentResponse(id,status,route,answer,index,knowledge,List.copyOf(evidence.values()),List.copyOf(citations),
                    List.copyOf(safeUncertainties),List.copyOf(next),
                    metrics(),null,200,skillRunner==null?null:skillRunner.result());
        }
    }
    @Override public void close(){workers.shutdownNow();}
}
