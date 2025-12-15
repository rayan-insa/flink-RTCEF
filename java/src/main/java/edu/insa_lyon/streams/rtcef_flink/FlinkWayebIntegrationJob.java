package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FlinkWayebIntegrationJob {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: FlinkWayebIntegrationJob <Java8Path> <WayebJarPath> <FsmPath> <InputStreamPath> <StatsPath>");
            return;
        }

        String java8Path = args[0];
        String wayebJarPath = args[1];
        String fsmPath = args[2];
        String inputStreamPath = args[3];
        String statsPath = args[4];

        System.out.println("--- [DEBUG] Starting FlinkWayebIntegrationJob ---");
        System.out.println("Java 8 Path    : " + java8Path);
        System.out.println("Wayeb JAR Path : " + wayebJarPath);

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Trigger the process execution via a dummy source
        DataStream<String> result = env.fromElements("TRIGGER")
            .map(new WayebprocessRunner(java8Path, wayebJarPath, fsmPath, inputStreamPath, statsPath));

        result.print();

        env.execute("Flink Wayeb Integration Smoke Test");
    }

    public static class WayebprocessRunner implements MapFunction<String, String> {
        private final String java8Path;
        private final String wayebJarPath;
        private final String fsmPath;
        private final String inputStreamPath;
        private final String statsPath;

        public WayebprocessRunner(String java8Path, String wayebJarPath, String fsmPath, String inputStreamPath, String statsPath) {
            this.java8Path = java8Path;
            this.wayebJarPath = wayebJarPath;
            this.fsmPath = fsmPath;
            this.inputStreamPath = inputStreamPath;
            this.statsPath = statsPath;
        }

        @Override
        public String map(String value) throws Exception {
            System.out.println("--- [DEBUG] Triggering Wayeb Subprocess ---");

            List<String> command = new ArrayList<>();
            command.add(java8Path + "/bin/java"); // Assuming path is to Home, as typically provided
            command.add("-cp");
            command.add(wayebJarPath);
            command.add("ui.WayebCLI");
            command.add("recognition");
            command.add("--fsm");
            command.add(fsmPath);
            command.add("--stream");
            command.add(inputStreamPath);
            command.add("--fsmModel");
            command.add("dsfa");
            command.add("--statsFile");
            command.add(statsPath);

            System.out.println("Command: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[WAYEB-STDOUT] " + line);
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            System.out.println("--- [DEBUG] Wayeb Subprocess finished with exit code: " + exitCode + " ---");

            if (exitCode != 0) {
                return "FAILURE: Wayeb exited with code " + exitCode;
            }
            return "SUCCESS: Wayeb executed. Output length: " + output.length();
        }
    }
}
