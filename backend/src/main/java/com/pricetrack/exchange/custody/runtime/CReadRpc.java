package com.pricetrack.exchange.custody.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** C keyless read transport. No signing, general HTTP proxy or transaction methods. */
@FunctionalInterface
public interface CReadRpc {
    JsonNode request(String method,List<Object> params,long timeoutMillis);
}
