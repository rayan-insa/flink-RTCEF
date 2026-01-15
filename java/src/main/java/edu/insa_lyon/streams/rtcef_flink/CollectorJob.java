package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.filesystem.BucketAssigner;
import org.apache.flink.streaming.api.functions.sink.filesystem.bucketassigners.SimpleVersionedStringSerializer;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.util.Collector;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * CollectorJob - Streaming implementation of the RTCEF Collector.
 * * Logic:
 * 1. Reads Maritime CSV data.
 * 2. Groups data into "Buckets" based on time duration (e.g., 1 hour chunks).
 * - Uses custom logic: New bucket starts when (current_ts - start_ts) > bucket_size.
 * 3. Writes data to: data/saved_datasets/dataset_N/part-...
 */
public class CollectorJob {

    public static void main(String[] args) throws Exception {

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        ParameterTool params = ParameterTool.fromArgs(args);

        // Parameters
        String inputPath = params.get("inputPath", "data/maritime.csv");
        String outputPath = "data/saved_datasets";
        final long bucketSizeSec = params.getLong("bucketSize", 86400); // 1 day default
        final String namingPrefix = params.get("naming", "dataset_");

        System.out.println("=== Collector Job (Streaming) ===");
        System.out.println("Bucket Size: " + bucketSizeSec + " seconds");
        System.out.println("Output: " + outputPath + "/" + namingPrefix + "X");

        // 1. Source
        FileSource<String> fileSource = FileSource
                .forRecordStreamFormat(new TextLineInputFormat(), new Path(inputPath))
                .build();

        // 2. Watermarks (Required for Event Time processing)
        WatermarkStrategy<MaritimeEvent> watermarkStrategy = WatermarkStrategy
                .<MaritimeEvent>forBoundedOutOfOrderness(Duration.ofSeconds(60))
                .withTimestampAssigner((event, timestamp) -> event.getTimestamp() * 1000L);

        // 3. Process Stream
        DataStream<Tuple2<String, MaritimeEvent>> bucketedStream = env
            .fromSource(fileSource, WatermarkStrategy.noWatermarks(), "Maritime Source")
            .flatMap((String line, Collector<MaritimeEvent> out) -> {
                MaritimeEvent event = MaritimeParser.parseLine(line);
                if (event != null) out.collect(event);
            })
            .returns(MaritimeEvent.class)
            .assignTimestampsAndWatermarks(watermarkStrategy)
            .map(event -> {
                long bucketStart = event.getTimestamp() - (event.getTimestamp() % bucketSizeSec);
                String bucketName = "dataset_" + bucketStart; 
                return Tuple2.of(bucketName, event);
            })
            .returns(Types.TUPLE(Types.STRING, Types.POJO(MaritimeEvent.class))); 

        // 4. Sink with Rolling Policy
        FileSink<Tuple2<String, MaritimeEvent>> sink = FileSink
                .<Tuple2<String, MaritimeEvent>>forRowFormat(new Path(outputPath), new EventOnlyEncoder())
                .withBucketAssigner(new DynamicBucketAssigner())
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withInactivityInterval(Duration.ofMinutes(60)) // Close if stream pauses
                                .withMaxPartSize(1024 * 1024 * 128) // Close if file hits 128MB
                                .build())
                .build();

        bucketedStream.sinkTo(sink);

        env.execute("RTCEF Collector Job");
    }

    /**
     * Uses the Tuple's f0 string (e.g., "dataset_0") as the directory name.
     */
    public static class DynamicBucketAssigner implements BucketAssigner<Tuple2<String, MaritimeEvent>, String> {
        @Override
        public String getBucketId(Tuple2<String, MaritimeEvent> element, Context context) {
            return element.f0;
        }
        @Override
        public org.apache.flink.core.io.SimpleVersionedSerializer<String> getSerializer() {
            return SimpleVersionedStringSerializer.INSTANCE;
        }
    }

    /**
     * Writes only the Event JSON to the file, discarding the bucket name wrapper.
     */
    public static class EventOnlyEncoder extends SimpleStringEncoder<Tuple2<String, MaritimeEvent>> {
        @Override
        public void encode(Tuple2<String, MaritimeEvent> element, OutputStream stream) throws java.io.IOException {
            stream.write(element.f1.toJson().getBytes(StandardCharsets.UTF_8));
            stream.write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
        }
    }
}