package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class FlinkHelloWorldJob {
    public static void main(String[] args) throws Exception {
        System.out.println("--- [DEBUG] Starting FlinkHelloWorldJob ---");

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<String> stream = env.fromElements("Flink", "says", "Hello", "World", "!");

        stream
            .map(s -> "Processed: " + s)
            .print();

        env.execute("Flink Hello World Smoke Test");
        
        System.out.println("--- [DEBUG] FlinkHelloWorldJob Finished ---");
    }
}
