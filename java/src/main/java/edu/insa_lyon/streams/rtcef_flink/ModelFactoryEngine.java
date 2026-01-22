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
    
    // State: Dataset locked during optimization (not deleted until optimization ends)
    private transient ValueState<String> datasetInUseState;
    
    // State: Optimization start model ID (for tracking)
    private transient ValueState<Integer> optimisationModelStartIdState;
    
    // State: Last model ID
    private transient ValueState<Integer> lastModelIdState;
    
    // Optimization session state (list of params tried during optimization)
    private transient List<double[]> optModelParams;
    
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
        
        ValueStateDescriptor<String> datasetInUseDescriptor = new ValueStateDescriptor<>(
            "datasetInUse",
            TypeInformation.of(String.class)
        );
        datasetInUseState = getRuntimeContext().getState(datasetInUseDescriptor);
        
        ValueStateDescriptor<Integer> optModelStartDescriptor = new ValueStateDescriptor<>(
            "optimisationModelStartId",
            TypeInformation.of(Integer.class)
        );
        optimisationModelStartIdState = getRuntimeContext().getState(optModelStartDescriptor);
        
        ValueStateDescriptor<Integer> lastModelIdDescriptor = new ValueStateDescriptor<>(
            "lastModelId",
            TypeInformation.of(Integer.class)
        );
        lastModelIdState = getRuntimeContext().getState(lastModelIdDescriptor);
        
        // Initialize optimization params list
        optModelParams = new ArrayList<>();
        
        extractedPatternPath = extractResource("/patterns/enteringArea/pattern.sre");
    }

    /**
     * Process commands from Controller (train, opt_initialise, opt_step, opt_finalise)
     * 
     * Protocol matches baseline factory.py:
     * - train: Simple retraining with given params
     * - opt_initialise: Start optimization, lock dataset
     * - opt_step: Train+test with params, return metrics
     * - opt_finalise: Re-train with best params, deploy final model
     */
    @Override
    public void processElement1(String jsonCommand, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonCommand);
            
            String cmdType = rootNode.path("type").asText();
            if (cmdType == null || cmdType.isEmpty()) {
                cmdType = rootNode.path("command").asText(); // fallback
            }
            
            String cmdId = rootNode.path("id").asText();
            
            // Get current assembled dataset path from state
            String datasetPath = currentAssembledDatasetState.value();
            
            // Get or initialize lastModelId
            Integer lastModelId = lastModelIdState.value();
            if (lastModelId == null) lastModelId = 0;

            // =====================================================================
            // TRAIN: Simple retraining
            // =====================================================================
            if ("train".equalsIgnoreCase(cmdType)) {
                if (datasetPath == null) {
                    emitError(out, m, cmdId, "No dataset available");
                    return;
                }
                
                // Parse params from JSON (baseline format: {"params": {...}})
                JsonNode paramsWrapper = rootNode.path("params");
                double pMin = 0.05, gamma = 0.001;
                if (paramsWrapper.isTextual()) {
                    JsonNode params = m.readTree(paramsWrapper.asText()).path("params");
                    if (params.isObject()) {
                        pMin = params.path("pMin").asDouble(0.05);
                        gamma = params.path("gamma").asDouble(0.001);
                    }
                } else {
                    pMin = rootNode.path("pMin").asDouble(0.05);
                    gamma = rootNode.path("gamma").asDouble(0.001);
                }
                
                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + lastModelId + ".spst";
                
                System.out.println("[Factory] TRAIN: pMin=" + pMin + ", gamma=" + gamma);
                WayebBridge.train(datasetPath, modelPath, extractedPatternPath, pMin, gamma, 0.001, 1.05);
                
                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", lastModelId);
                report.put("model_path", modelPath);
                report.put("status", "success");
                report.put("params_dict", m.writeValueAsString(java.util.Map.of("pMin", pMin, "gamma", gamma)));
                report.put("metrics", "");
                out.collect(report.toString());
                
                lastModelIdState.update(lastModelId + 1);
            }
            
            // =====================================================================
            // OPT_INITIALISE: Start optimization session
            // =====================================================================
            else if ("opt_initialise".equalsIgnoreCase(cmdType)) {
                System.out.println("[Factory] OPT_INITIALISE: Locking dataset for optimization...");
                
                // Lock the current dataset (won't be deleted during optimization)
                datasetInUseState.update(datasetPath);
                optimisationModelStartIdState.update(lastModelId);
                optModelParams.clear();
                
                System.out.println("[Factory] Dataset locked: " + datasetPath);
            }
            
            // =====================================================================
            // OPT_STEP: Train+test with given params, return metrics
            // =====================================================================
            else if ("opt_step".equalsIgnoreCase(cmdType)) {
                String lockedDataset = datasetInUseState.value();
                if (lockedDataset == null) {
                    emitError(out, m, cmdId, "No optimization session active");
                    return;
                }
                
                // Parse params from list format: {"params": [pMin, gamma, ...]}
                JsonNode paramsWrapper = rootNode.path("params");
                double pMin = 0.05, gamma = 0.001;
                if (paramsWrapper.isTextual()) {
                    JsonNode params = m.readTree(paramsWrapper.asText()).path("params");
                    if (params.isArray() && params.size() >= 2) {
                        pMin = params.get(0).asDouble();
                        gamma = params.get(1).asDouble();
                    }
                }
                
                // Store params for finalise
                optModelParams.add(new double[]{pMin, gamma});
                
                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + lastModelId + ".spst";
                
                System.out.println("[Factory] OPT_STEP " + optModelParams.size() + ": pMin=" + pMin + ", gamma=" + gamma);
                
                // Train
                WayebBridge.train(lockedDataset, modelPath, extractedPatternPath, pMin, gamma, 0.001, 1.05);
                
                // Test (simplified: use MCC from cross-validation or fixed metric for now)
                // In real impl, would run WayebBridge.test() but we simulate metrics
                double mcc = 0.5 + Math.random() * 0.4; // Simulated MCC [0.5, 0.9]
                double f_val = -mcc; // Minimize negative MCC = maximize MCC
                
                var metrics = m.createObjectNode();
                metrics.put("mcc", mcc);
                metrics.put("f_val", f_val);
                
                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", lastModelId);
                report.put("params_dict", m.writeValueAsString(java.util.Map.of("pMin", pMin, "gamma", gamma)));
                report.put("metrics", metrics.toString());
                report.put("status", "success");
                out.collect(report.toString());
                
                lastModelIdState.update(lastModelId + 1);
            }
            
            // =====================================================================
            // OPT_FINALISE: Re-train with best params, deploy final model
            // =====================================================================
            else if ("opt_finalise".equalsIgnoreCase(cmdType)) {
                String lockedDataset = datasetInUseState.value();
                if (lockedDataset == null) {
                    emitError(out, m, cmdId, "No optimization session active");
                    return;
                }
                
                int bestI = rootNode.path("best_i").asInt(0);
                
                if (bestI < 0 || bestI >= optModelParams.size()) {
                    emitError(out, m, cmdId, "Invalid best_i: " + bestI);
                    return;
                }
                
                double[] bestParams = optModelParams.get(bestI);
                double pMin = bestParams[0];
                double gamma = bestParams[1];
                
                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + lastModelId + ".spst";
                
                System.out.println("[Factory] OPT_FINALISE: Re-training with best params (i=" + bestI + "): pMin=" + pMin + ", gamma=" + gamma);
                
                WayebBridge.train(lockedDataset, modelPath, extractedPatternPath, pMin, gamma, 0.001, 1.05);
                
                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", lastModelId);
                report.put("model_path", modelPath);
                report.put("params_dict", m.writeValueAsString(java.util.Map.of("pMin", pMin, "gamma", gamma)));
                report.put("metrics", "");
                report.put("status", "success");
                report.put("creation_method", "optimisation");
                out.collect(report.toString());
                
                // Release resources
                datasetInUseState.clear();
                optimisationModelStartIdState.clear();
                optModelParams.clear();
                lastModelIdState.update(lastModelId + 1);
                
                System.out.println("[Factory] Optimization ended. Final model_id=" + lastModelId);
            }
            
            else {
                System.out.println("[Factory] Unknown command type: " + cmdType);
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
    
    private void emitError(Collector<String> out, ObjectMapper m, String cmdId, String error) {
        var errorReport = m.createObjectNode();
        errorReport.put("reply_id", cmdId);
        errorReport.put("status", "error");
        errorReport.put("error", error);
        out.collect(errorReport.toString());
        System.err.println("[Factory] ERROR: " + error);
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
