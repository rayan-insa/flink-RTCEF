package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import ui.WayebBridge;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * CoProcessFunction that handles both Commands and Datasets streams.
 * 
 * Stream 1 (processElement1): Commands from Controller (TRAIN, OPTIMIZE, etc.)
 * Stream 2 (processElement2): Datasets from Collector (bucket notifications)
 * 
 * The Factory maintains the current assembled dataset path in state
 * and uses it when processing training commands.
 */
public class ModelFactoryEngine extends CoProcessFunction<String, String, String> {

    // State: Path to the current assembled dataset (assembled_dataset_version_<v>.csv)
    private transient ValueState<String> currentAssembledDatasetState;
    
    // Cached path to extracted pattern file
    private transient String extractedPatternPath;
    
    // JSON Mapper (transient for serialization)
    private transient ObjectMapper mapper;

    private ObjectMapper getMapper() {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
        return mapper;
    }

    /**
     * Extract a resource from the JAR to a temporary file.
     */
    private String extractResource(String resourcePath) throws IOException {
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found in JAR: " + resourcePath);
        }
        
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        File tempFile = File.createTempFile("wayeb_", "_" + fileName);
        tempFile.deleteOnExit();
        
        Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        is.close();
        
        System.out.println("Extracted resource " + resourcePath + " to " + tempFile.getAbsolutePath());
        return tempFile.getAbsolutePath();
    }

    /**
     * Concatenate all bucket files into a single assembled CSV file.
     * 
     * Matches the `assemble_dataset` logic from original `factory.py`.
     * 
     * @param pathPrefix The path prefix (e.g., "/opt/flink/data/saved_datasets/dataset_")
     * @param bucketsRange List of bucket IDs (timestamps)
     * @param version The dataset version
     * @return Path to the assembled file
     */
    private String assembleDataset(String pathPrefix, List<Long> bucketsRange, long version) throws IOException {
        // We use a specific directory for assembled datasets to manage them better
        String baseDir = "/opt/flink/data/assembled_datasets";
        Files.createDirectories(Paths.get(baseDir));
        
        File assembledFile = new File(baseDir, "assembled_dataset_version_" + version + ".csv");
        
        System.out.println("[ModelFactoryEngine] Assembling dataset v" + version + " from " + bucketsRange.size() + " buckets...");
        
        try (FileWriter writer = new FileWriter(assembledFile)) {
            for (Long bucketId : bucketsRange) {
                String bucketDir = pathPrefix + bucketId;
                java.nio.file.Path bucketPath = Paths.get(bucketDir);
                
                if (!Files.exists(bucketPath)) {
                    System.out.println("  WARNING: Bucket directory does not exist: " + bucketDir);
                    continue;
                }
                
                // List all part-* files in the bucket directory
                Files.list(bucketPath)
                    .filter(p -> p.getFileName().toString().startsWith("part-"))
                    .sorted()
                    .forEach(partFile -> {
                        try {
                            List<String> lines = Files.readAllLines(partFile);
                            for (String line : lines) {
                                writer.write(line);
                                writer.write(System.lineSeparator());
                            }
                        } catch (IOException e) {
                            System.err.println("  ERROR reading " + partFile + ": " + e.getMessage());
                        }
                    });
            }
        }
        
        System.out.println("[ModelFactoryEngine] Assembled: " + assembledFile.getAbsolutePath());
        return assembledFile.getAbsolutePath();
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        ValueStateDescriptor<String> datasetDescriptor = new ValueStateDescriptor<>(
            "currentAssembledDataset",
            TypeInformation.of(String.class)
        );
        currentAssembledDatasetState = getRuntimeContext().getState(datasetDescriptor);
        
        extractedPatternPath = extractResource("/patterns/enteringArea/pattern.sre");
    }

    /**
     * Process commands from Controller (TRAIN, OPTIMIZE, etc.)
     */
    @Override
    public void processElement1(String jsonCommand, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonCommand);
            
            // Align with factory.py: use "type" instead of "command"
            String cmdType = rootNode.path("type").asText();
            if (cmdType == null || cmdType.isEmpty()) {
                cmdType = rootNode.path("command").asText(); // fallback
            }

            // Get current assembled dataset path from state
            String datasetPath = currentAssembledDatasetState.value();
            
            if (datasetPath == null) {
                System.out.println("[ModelFactoryEngine] WARNING: No dataset available yet.");
                var errorReport = m.createObjectNode();
                errorReport.put("reply_id", rootNode.path("id").asText());
                errorReport.put("status", "error");
                errorReport.put("error", "No dataset available");
                out.collect(errorReport.toString());
                return;
            }

            if ("TRAIN".equalsIgnoreCase(cmdType)) {
                String cmdId = rootNode.path("id").asText();
                String modelId = rootNode.path("model_id").asText();
                
                // Extract hyperparameters from command
                double pMin = rootNode.path("pMin").asDouble(0.001);
                double gamma = rootNode.path("gamma").asDouble(0.001);
                double alpha = 0.001;
                double r = 1.05;

                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + modelId + ".spst";

                System.out.println("[ModelFactoryEngine] Starting training for command " + cmdId);
                System.out.println("  Assembled Dataset: " + datasetPath);
                System.out.println("  Pattern: " + extractedPatternPath);
                System.out.println("  pMin=" + pMin + ", gamma=" + gamma);
                
                WayebBridge.train(
                    datasetPath,
                    modelPath,
                    extractedPatternPath,
                    pMin,
                    gamma,
                    alpha,
                    r
                );
                
                System.out.println("[ModelFactoryEngine] Training complete. Model saved to " + modelPath);

                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", modelId);
                report.put("model_path", modelPath);
                report.put("status", "success");
                report.put("pMin", pMin);
                report.put("gamma", gamma);

                out.collect(report.toString());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            ObjectMapper m = getMapper();
            var errorReport = m.createObjectNode();
            errorReport.put("status", "error");
            errorReport.put("error", e.getMessage());
            out.collect(errorReport.toString());
        }
    }

    /**
     * Process datasets from Collector - update state and clean old files.
     * 
     * Matches `clean_old_datasets` logic from original `factory.py`.
     */
    @Override
    public void processElement2(String jsonDataset, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonDataset);
            
            String pathPrefix = rootNode.path("path_prefix").asText();
            JsonNode rangeNode = rootNode.path("buckets_range");
            long version = rootNode.path("version").asLong();
            
            if (pathPrefix == null || pathPrefix.isEmpty() || rangeNode == null || rangeNode.size() == 0) {
                System.out.println("[ModelFactoryEngine] ERROR: Invalid dataset notification format");
                return;
            }
            
            List<Long> bucketsRange = new ArrayList<>();
            for (JsonNode id : rangeNode) {
                bucketsRange.add(id.asLong());
            }
            
            // 1. Assemble the new dataset immediately
            String newAssembledPath = assembleDataset(pathPrefix, bucketsRange, version);
            
            // 2. Clean up the old assembled dataset file
            String oldAssembledPath = currentAssembledDatasetState.value();
            if (oldAssembledPath != null && !oldAssembledPath.equals(newAssembledPath)) {
                try {
                    Files.deleteIfExists(Paths.get(oldAssembledPath));
                    System.out.println("[ModelFactoryEngine] Deleted old assembled dataset: " + oldAssembledPath);
                } catch (IOException e) {
                    System.err.println("[ModelFactoryEngine] Failed to delete old dataset: " + oldAssembledPath + " - " + e.getMessage());
                }
            }
            
            // 3. Update state with the new path
            currentAssembledDatasetState.update(newAssembledPath);
            
            System.out.println("[ModelFactoryEngine] Dataset updated to version " + version);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
