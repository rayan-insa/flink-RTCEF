package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import stream.GenericEvent;
import java.util.Iterator;
import scala.Tuple2;
import scala.collection.immutable.Map$;
import scala.collection.mutable.Builder;

/**
 * Parser for maritime AIS data in JSONL format.
 * * Converts JSON objects into Wayeb GenericEvent objects.
 * Handles dynamic typing to ensure Integers remain Integers (crucial for FSM guards).
 */
public class MaritimeParser extends RichFlatMapFunction<String, GenericEvent> {

    private transient ObjectMapper mapper;
    private int counter;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        this.mapper = new ObjectMapper();
        this.counter = 1;
    }

    @Override
    public void flatMap(String line, Collector<GenericEvent> out) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        try {
            JsonNode root = mapper.readTree(line);

            // 1. Extract Mandatory Metadata
            if (!root.has("timestamp")) return;
            long timestamp = root.get("timestamp").asLong();
            
            // "mmsi" is usually the ID, defaulting to "unknown" if missing
            String mmsi = root.has("mmsi") ? root.get("mmsi").asText() : "unknown";

            // 2. Build Scala Map dynamically
            // We use a specific Scala builder to construct the immutable map required by GenericEvent
            @SuppressWarnings("unchecked")
            Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>> builder = 
                (Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>>) (Builder<?, ?>) Map$.MODULE$.newBuilder();

            // 3. Iterate over all JSON fields
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode node = root.get(fieldName);
                Object value = null;

                // CRITICAL LOGIC FIX: Preserve Numeric Types
                if (node.isNumber()) {
                    if (node.isIntegralNumber()) {
                        // If it's an integer (like change_in_heading: 1), keep it as Int
                        // Wayeb patterns often check equality (x == 1), which fails if x is 1.0
                        if (node.canConvertToInt()) {
                            value = node.asInt();
                        } else {
                            value = node.asLong();
                        }
                    } else {
                        // Decimals (speed, lat, lon) remain Double
                        value = node.asDouble();
                    }
                } else if (node.isBoolean()) {
                    value = node.asBoolean();
                } else if (node.isTextual()) {
                    value = node.asText();
                }

                // Add to builder if value is valid
                if (value != null) {
                    builder.$plus$eq(new Tuple2<>(fieldName, value));
                }
            }

            // 4. Finalize Map
            scala.collection.immutable.Map<String, Object> scalaArgs = builder.result();

            // 5. Emit Event
            // "SampledCritical" is the event type used in your previous CSV parser
            GenericEvent event = new GenericEvent(counter++, "SampledCritical", timestamp, scalaArgs);
            out.collect(event);

        } catch (Exception e) {
            // Log parsing errors if necessary, or skip bad lines
            // System.err.println("Failed to parse line: " + line);
        }
    }
}