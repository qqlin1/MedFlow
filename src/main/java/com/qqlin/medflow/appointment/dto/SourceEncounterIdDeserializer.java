package com.qqlin.medflow.appointment.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/** Do not let Jackson truncate a fractional source ID to a different completed encounter. */
public class SourceEncounterIdDeserializer extends StdDeserializer<Long> {
    public SourceEncounterIdDeserializer() { super(Long.class); }

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}
