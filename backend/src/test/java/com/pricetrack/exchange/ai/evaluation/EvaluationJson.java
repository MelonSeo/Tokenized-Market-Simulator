package com.pricetrack.exchange.ai.evaluation;

import com.fasterxml.jackson.databind.*;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Match Boot's mapper defaults without starting the application or providers. */
final class EvaluationJson {
    static ObjectMapper mapper() {
        return Jackson2ObjectMapperBuilder.json().featuresToDisable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS).build();
    }
}
