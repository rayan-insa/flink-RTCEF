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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * CoProcessFunction that handles both Commands and Datasets streams.
 * 
 * Stream 1 (processElement1): Commands from Controller
 * Stream 2 (processElement2): Datasets from Collector
 * 
 * The Factory maintains the current dataset in state and uses it when
 * processing training commands.
 */
public class ModelFactoryEngine extends CoProcessFunction<String, String, String> {

    // State: current dataset info (JSON string)
    private transient ValueState<String> currentDatasetState;
    
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
     * 
     * @param resourcePath Path to resource in JAR (e.g., "/patterns/enteringArea/pattern.sre")
     * @return Absolute path to the extracted temporary file
     */
    private String extractResource(String resourcePath) throws IOException {
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("Resource not found in JAR: " + resourcePath);
        }
        
        // Create temp file with meaningful name
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        File tempFile = File.createTempFile("wayeb_", "_" + fileName);
        tempFile.deleteOnExit(); // Clean up on JVM exit
        
        Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        is.close();
        
        System.out.println("Extracted resource " + resourcePath + " to " + tempFile.getAbsolutePath());
        return tempFile.getAbsolutePath();
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // Initialize state descriptor for current dataset
        ValueStateDescriptor<String> datasetDescriptor = new ValueStateDescriptor<>(
            "currentDataset",
            TypeInformation.of(String.class)
        );
        currentDatasetState = getRuntimeContext().getState(datasetDescriptor);
        
        // Extract pattern file from JAR resources once at startup
        extractedPatternPath = extractResource("/patterns/enteringArea/pattern.sre");
    }

    /**
     * Process commands from Controller (train, optimize, etc.)
     */
    @Override
    public void processElement1(String jsonCommand, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonCommand);
            String cmdType = rootNode.path("type").asText();

            // Get current dataset from state
            String currentDataset = currentDatasetState.value();
            
            if (currentDataset == null) {
                System.out.println("WARNING: No dataset available yet. Queueing command or skipping.");
                // In production: could queue commands or use a timer
                var errorReport = m.createObjectNode();
                errorReport.put("reply_id", rootNode.path("id").asText());
                errorReport.put("status", "error");
                errorReport.put("error", "No dataset available");
                out.collect(errorReport.toString());
                return;
            }

            JsonNode datasetNode = m.readTree(currentDataset);
            String datasetPath = datasetNode.path("path").asText();
            
            if ("TRAIN".equalsIgnoreCase(cmdType)) {
                String cmdId = rootNode.path("id").asText();
                String modelId = rootNode.path("model_id").asText();
                
                // Extract hyperparameters from command
                double pMin = rootNode.path("pMin").asDouble(0.001);
                double gamma = rootNode.path("gamma").asDouble(0.001);
                double alpha = 0.001; // Default
                double r = 1.05;      // Default

                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + modelId + ".spst";

                System.out.println("Starting training for command " + cmdId);
                System.out.println("  Dataset: " + datasetPath);
                System.out.println("  Pattern: " + extractedPatternPath);
                System.out.println("  pMin=" + pMin + ", gamma=" + gamma);
                
                // Call Scala Bridge for training (using extracted pattern file)
                WayebBridge.train(
                    datasetPath,
                    modelPath,
                    extractedPatternPath,
                    pMin,
                    gamma,
                    alpha,
                    r
                );
                
                System.out.println("Training complete. Model saved to " + modelPath);

                // Emit success report
                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", modelId);
                report.put("model_path", modelPath);
                report.put("status", "success");
                report.put("pMin", pMin);
                report.put("gamma", gamma);

                out.collect(report.toString());
            }
            // Handle other command types (opt_initialise, opt_step, opt_finalise) later
            
        } catch (Exception e) {
            e.printStackTrace();
            // Emit error report
            ObjectMapper m = getMapper();
            var errorReport = m.createObjectNode();
            errorReport.put("status", "error");
            errorReport.put("error", e.getMessage());
            out.collect(errorReport.toString());
        }
    }

    /**
     * Process datasets from Collector - update state
     */
    @Override
    public void processElement2(String jsonDataset, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonDataset);
            
            String datasetId = rootNode.path("dataset_id").asText();
            String path = rootNode.path("uri").asText();
            
            // If no explicit path, construct one from dataset_id
            if (path == null || path.isEmpty() || "null".equals(path)) {
                path = "/opt/flink/data/datasets/" + datasetId + ".csv";
            }
            
            // Store dataset info in state
            var datasetInfo = m.createObjectNode();
            datasetInfo.put("dataset_id", datasetId);
            datasetInfo.put("path", path);
            datasetInfo.put("timestamp", rootNode.path("timestamp").asLong());
            datasetInfo.put("event_count", rootNode.path("events").size());
            
            currentDatasetState.update(datasetInfo.toString());
            
            System.out.println("Dataset updated: " + datasetId + " (" + rootNode.path("events").size() + " events)");
            System.out.println("  Path: " + path);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

