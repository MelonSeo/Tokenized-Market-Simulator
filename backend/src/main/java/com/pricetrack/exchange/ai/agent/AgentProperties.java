package com.pricetrack.exchange.ai.agent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("app.ai.agent")
public record AgentProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("15") int planningSeconds,@DefaultValue("30") int synthesisSeconds,
        @DefaultValue("15") int retrievalSeconds,@DefaultValue("90") int totalSeconds,
        @DefaultValue("true") boolean deterministicState) {
    public AgentProperties(boolean enabled){this(enabled,15,30,15,90,true);}
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AgentProperties {
        if(planningSeconds<1||planningSeconds>30||synthesisSeconds<1||synthesisSeconds>60||retrievalSeconds<1||retrievalSeconds>30
            ||totalSeconds<Math.max(planningSeconds,Math.max(synthesisSeconds,retrievalSeconds))||totalSeconds>180)
            throw new IllegalArgumentException("Invalid bounded Agent deadlines");
    }
}
