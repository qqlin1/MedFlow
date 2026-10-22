package com.qqlin.medflow.billing.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

public class StrictLongDeserializer extends StdDeserializer<Long> {
    public StrictLongDeserializer() { super(Long.class); }
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return (Long) context.handleUnexpectedToken(Long.class, parser);
        return parser.getLongValue();
    }
}
