package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import stream.GenericEvent;
import ui.WayebAdapter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Generic Parser for JSONL data.
 * 
 * Replaces domain-specific parsers (like MaritimeParser) with a configurable JSON parser.
 * Converts raw JSON strings into Wayeb {@link GenericEvent} objects.
 * 
 * Key Features:
 * - Configurable ID field (e.g., "mmsi", "symbol")
 * - Configurable Timestamp field (e.g., "timestamp")
 * - Dynamic attribute mapping (all JSON fields become event attributes)
 */
public class JsonEventParser extends RichFlatMapFunction<String, GenericEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(JsonEventParser.class);

    private transient ObjectMapper mapper;
    
    // Configuration
    private final String idField;
    private final String timestampField;
    private final String eventType;

    public JsonEventParser(String idField, String timestampField, String eventType) {
        this.idField = idField;
        this.timestampField = timestampField;
        this.eventType = eventType;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        this.mapper = new ObjectMapper();
    }

    @Override
    public void flatMap(String line, Collector<GenericEvent> out) throws Exception {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        try {
            JsonNode root = mapper.readTree(line);
            
            // Extract Timestamp
            if (!root.has(timestampField)) {
                // LOG.warn("Skipping event without timestamp field '{}': {}", timestampField, line);
                return;
            }
            long timestamp = root.get(timestampField).asLong();
            
            // Extract ID
            String id = "unknown";
            if (root.has(idField)) {
                id = root.get(idField).asText();
            }
            
            // Build Attributes Map
            Map<String, Object> attributes = new HashMap<>();
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode node = root.get(fieldName);
                
                if (node.isNumber()) {
                    if (node.isIntegralNumber()) {
                        // Wayeb usually prefers Double for compatibility
                        // Let's stick to Double for consistency with previous CSV parser for numbers
                        attributes.put(fieldName, node.asDouble());
                    } else {
                        attributes.put(fieldName, node.asDouble());
                    }
                } else if (node.isTextual()) {
                    attributes.put(fieldName, node.asText());
                } else if (node.isBoolean()) {
                    attributes.put(fieldName, node.asBoolean());
                }
                // Skip arrays/objects for flat attributes map for now
            }
            
            // Ensure ID is in attributes map if needed by patterns
            attributes.put(idField, id);
            
            out.collect(WayebAdapter.createEvent(timestamp, eventType, attributes));

        } catch (Exception e) {
            // LOG.warn("Failed to parse JSON line: {}", line);
        }
    }
}
