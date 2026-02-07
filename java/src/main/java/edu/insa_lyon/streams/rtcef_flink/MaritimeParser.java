package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import stream.GenericEvent;
import scala.Tuple2;
import scala.collection.immutable.Map$;
import scala.collection.mutable.Builder;

/**
 * Parser for maritime AIS data in JSONL format.
 * Matches logic of MaritimeWAStreamSourceJSON.scala exactly:
 * - Forces Doubles for metrics
 * - Expands critical_bitstring
 * - Renames specific fields (trh -> heading)
 */
public class MaritimeParser extends RichFlatMapFunction<String, GenericEvent> {

    private transient ObjectMapper mapper;
    private int counter;
    private static final Logger LOG = LoggerFactory.getLogger(MaritimeParser.class);

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        this.mapper = new ObjectMapper();
        this.counter = 1;
    }

    @Override
    public void flatMap(String line, Collector<GenericEvent> out) {
        if (line == null || line.trim().isEmpty()) return;

        try {
            JsonNode root = mapper.readTree(line);
            if (!root.has("timestamp")) return;
            
            // 1. Extract Timestamp (Long)
            long timestamp = root.get("timestamp").asLong();

            // 2. Prepare Scala Map Builder
            @SuppressWarnings("unchecked")
            Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>> builder = 
                (Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>>) (Builder<?, ?>) Map$.MODULE$.newBuilder();

            // 3. Explicitly Extract and Map Fields (Matching Scala Logic)
            
            // Identity Strings
            String mmsi = root.path("mmsi").asText("");
            builder.$plus$eq(new Tuple2<>("mmsi", mmsi));

            // Metrics (Force Double)
            builder.$plus$eq(new Tuple2<>("lon", root.path("lon").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("lat", root.path("lat").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("speed", root.path("speed").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("cog", root.path("cog").asDouble(0.0)));
            
            // Renamed Fields (trh -> heading)
            builder.$plus$eq(new Tuple2<>("heading", root.path("trh").asDouble(0.0)));

            // Context/Entry/Exit Fields (Force Double)
            builder.$plus$eq(new Tuple2<>("entryNearcoast", root.path("entry_nearcoast").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("entryNearcoast5k", root.path("entry_nearcoast5k").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("entryFishing", root.path("entry_fishing").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("entryNatura", root.path("entry_natura").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("entryNearports", root.path("entry_nearports").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("entryAnchorage", root.path("entry_anchorage").asDouble(0.0)));

            builder.$plus$eq(new Tuple2<>("exitNearcoast", root.path("exit_nearcoast").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("exitNearcoast5k", root.path("exit_nearcoast5k").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("exitFishing", root.path("exit_fishing").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("exitNatura", root.path("exit_natura").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("exitNearports", root.path("exit_nearports").asDouble(0.0)));
            builder.$plus$eq(new Tuple2<>("exitAnchorage", root.path("exit_anchorage").asDouble(0.0)));

            // Next Timestamp (Long -> nextCETimestamp)
            long nextTs = root.path("next_timestamp").asLong(0L);
            builder.$plus$eq(new Tuple2<>("nextCETimestamp", nextTs));

            // Gap Start Logic
            double gapStart = (timestamp == -1) ? 1.0 : 0.0;
            builder.$plus$eq(new Tuple2<>("gap_start", gapStart));

            // 4. Expand Annotation Bitstring
            String annotation = root.path("critical_bitstring").asText("");
            expandAnnotation(annotation, builder);

            // 5. Build Event
            scala.collection.immutable.Map<String, Object> scalaArgs = builder.result();
            
            // NOTE: Using "SampledCritical" to match your uploaded Scala file.
            GenericEvent event = new GenericEvent(counter++, "SampledCritical", timestamp, scalaArgs);
            
            out.collect(event);

        } catch (Exception e) {
            // LOG.error("Error parsing event", e);
        }
    }

    /**
     * Replicates the annotationExpansion logic from Scala.
     * Maps bitstring positions to specific feature flags.
     */
    private void expandAnnotation(String ann, Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>> builder) {
        if ("-1".equals(ann)) {
            add(builder, "stop_start", -1.0);
            add(builder, "stop_end", -1.0);
            add(builder, "slow_motion_start", -1.0);
            add(builder, "slow_motion_end", -1.0);
            add(builder, "gap_end", -1.0);
            add(builder, "change_in_heading", -1.0);
            add(builder, "change_in_speed_start", -1.0);
            add(builder, "change_in_speed_end", -1.0);
        } else if (ann != null && ann.length() >= 8) {
            // Scala slice(start, end) matches Java substring(start, end)
            // String: "01234567" (indices)
            add(builder, "stop_start", parseDouble(ann, 7, 8));
            add(builder, "stop_end", parseDouble(ann, 6, 7));
            add(builder, "slow_motion_start", parseDouble(ann, 5, 6));
            add(builder, "slow_motion_end", parseDouble(ann, 4, 5));
            add(builder, "gap_end", parseDouble(ann, 3, 4));
            add(builder, "change_in_heading", parseDouble(ann, 2, 3));
            add(builder, "change_in_speed_start", parseDouble(ann, 1, 2));
            add(builder, "change_in_speed_end", parseDouble(ann, 0, 1));
        }
    }

    private void add(Builder<Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>> builder, String key, Double val) {
        builder.$plus$eq(new Tuple2<>(key, val));
    }

    private Double parseDouble(String s, int start, int end) {
        try {
            return Double.parseDouble(s.substring(start, end));
        } catch (Exception e) {
            return 0.0;
        }
    }
}