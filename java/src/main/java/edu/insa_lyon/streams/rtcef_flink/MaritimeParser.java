package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.util.Collector;
import stream.GenericEvent; // Ensure this matches your Scala class package
import java.util.HashMap;
import java.util.Map;
import scala.Tuple2;
import scala.collection.immutable.Map$; // Factory for immutable maps
import scala.collection.mutable.Builder;

public class MaritimeParser extends RichFlatMapFunction<String, GenericEvent> {
    
    private int counter;

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        counter = 1;
    }

    @Override
    public void flatMap(String line, Collector<GenericEvent> out) {
        try {
            String[] cols = line.split(","); // Simple CSV split
            
            // Parse fields based on the data structure
            long timestamp = Long.parseLong(cols[0].trim());
            String mmsi = cols[1].trim();
            
            // Create the attribute map required by Wayeb
            Map<String, Object> args = new HashMap<>();
            args.put("mmsi", mmsi);
            args.put("lon", Double.parseDouble(cols[2].trim()));
            args.put("lat", Double.parseDouble(cols[3].trim()));
            args.put("speed", Double.parseDouble(cols[4].trim()));
            args.put("heading", Double.parseDouble(cols[5].trim()));
            args.put("cog", Double.parseDouble(cols[6].trim()));
            args.put("annotation", cols[7].trim());

            Builder<scala.Tuple2<String, Object>, scala.collection.immutable.Map<String, Object>> builder = Map$.MODULE$.newBuilder();
            for (java.util.Map.Entry<String, Object> entry : args.entrySet()) {
                builder.$plus$eq(new Tuple2<>(entry.getKey(), entry.getValue()));
            }
            scala.collection.immutable.Map<String, Object> scalaArgs = builder.result();

            // Create and emit the event
            GenericEvent event = new GenericEvent(counter++, "SampledCritical", timestamp, scalaArgs);
            out.collect(event);

        } catch (Exception e) {
            // Skip bad lines without crashing
            // TODO: Add logging if needed
        }
    }
}
