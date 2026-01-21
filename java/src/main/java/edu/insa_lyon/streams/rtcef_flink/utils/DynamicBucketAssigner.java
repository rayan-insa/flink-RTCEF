package edu.insa_lyon.streams.rtcef_flink.utils;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.SimpleVersionedStringSerializer;

import stream.GenericEvent;

public class DynamicBucketAssigner implements BucketAssigner<Tuple2<String, GenericEvent>, String> {
    @Override
    public String getBucketId(Tuple2<String, GenericEvent> element, Context context) {
        return element.f0;
    }
    @Override
    public org.apache.flink.core.io.SimpleVersionedSerializer<String> getSerializer() {
        return SimpleVersionedStringSerializer.INSTANCE;
    }
}
