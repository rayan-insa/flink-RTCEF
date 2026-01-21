package edu.insa_lyon.streams.rtcef_flink.utils;

import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.java.tuple.Tuple2;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import stream.GenericEvent;

public class GenericEventEncoder extends SimpleStringEncoder<Tuple2<String, GenericEvent>> {
    @Override
    public void encode(Tuple2<String, GenericEvent> element, OutputStream stream) throws java.io.IOException {
        GenericEvent e = element.f1;
        
        // Reconstruct JSON from GenericEvent (Adapt field names as necessary)
        // Note: Use the safe accessor for your specific GenericEvent implementation
        String json = String.format(
            "{\"timestamp\":%d,\"mmsi\":\"%s\",\"lon\":%s,\"lat\":%s,\"speed\":%s,\"heading\":%s,\"cog\":%s}",
            e.timestamp(),
            e.id(),
            e.getValueOf("lon"), // Assuming these keys exist in the GenericEvent payload
            e.getValueOf("lat"),
            e.getValueOf("speed"),
            e.getValueOf("heading"),
            e.getValueOf("cog")
        );
        
        stream.write(json.getBytes(StandardCharsets.UTF_8));
        stream.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
    }
}
