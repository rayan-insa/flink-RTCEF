package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Core engine for model training and optimization in the Model Factory.
 * 
 * This class handles:
 * 1. Assembling datasets from time-bucketed CSV files.
 * 2. Executing VMM (Variable Memory Markov) training using WayebAdapter.
 * 3. Implementing the "Ask-Tell" Baysezian optimization protocol.
 * 4. Managing dataset persistence and cleanup.
 */
public class ModelFactoryEngine extends CoProcessFunction<String, String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(ModelFactoryEngine.class);

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
    private transient String extractedDeclarationsPath;
    
    // JSON Mapper (transient for serialization)
    private transient ObjectMapper mapper;

    // Config Params (aligned with InferenceJob)
    private final int horizon;
    private final double runConfidenceThreshold;
    private final int maxSpread;
    private final OutputTag<String> assemblyTag;
    
    // Minimum Data threshold for training (Phase 2)
    private static final int MIN_DATA_THRESHOLD = 50;

    public ModelFactoryEngine(OutputTag<String> assemblyTag, int horizon, double runConfidenceThreshold, int maxSpread) {
        this.assemblyTag = assemblyTag;
        this.horizon = horizon;
        this.runConfidenceThreshold = runConfidenceThreshold;
        this.maxSpread = maxSpread;
    }

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
        java.io.File file = new java.io.File(resourcePath);
        if (file.exists() && file.isFile()) {
            LOG.info("Resource found on filesystem: {}", resourcePath);
            return resourcePath; // Return the absolute path directly, no extraction needed
        }

        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            if (resourcePath.startsWith("/")) {
                 is = getClass().getResourceAsStream(resourcePath.substring(1));
            }
        }
        
        if (is == null) {
            throw new IOException("Resource not found in JAR: " + resourcePath);
        }
        
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        File tempFile = File.createTempFile("wayeb_", "_" + fileName);
        tempFile.deleteOnExit();
        
        Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        is.close();
        
        LOG.debug("Extracted resource {} to {}", resourcePath, tempFile.getAbsolutePath());
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
        String baseDir = "/opt/flink/data/datasets";
        Files.createDirectories(Paths.get(baseDir));
        
        File assembledFile = new File(baseDir, "dataset_version_" + version + ".csv");
        
        // LOG.info("Assembling dataset v{} from {} buckets...", version, bucketsRange.size());
        
        try (FileWriter writer = new FileWriter(assembledFile)) {
            for (Long bucketId : bucketsRange) {
                String bucketDir = pathPrefix + bucketId;
                java.nio.file.Path bucketPath = Paths.get(bucketDir);
                
                if (!Files.exists(bucketPath)) {
                    LOG.warn("Bucket does not exist: {}", bucketDir);
                    continue;
                }

                // Handle both flat files (New Collector) and Directories (Legacy/Parallel)
                if (Files.isDirectory(bucketPath)) {
                    // Directory: List and concatenate children
                    Files.list(bucketPath)
                        .sorted()
                        .forEach(p -> {
                             try {
                                 List<String> lines = Files.readAllLines(p);
                                 for (String line : lines) {
                                     writer.write(line);
                                     writer.write(System.lineSeparator());
                                 }
                             } catch (IOException e) {
                                 LOG.error("Error reading part file: " + e.getMessage());
                             }
                        });
                } else {
                    // Flat File: Append directly
                    try {
                         List<String> lines = Files.readAllLines(bucketPath);
                         for (String line : lines) {
                             writer.write(line);
                             writer.write(System.lineSeparator());
                         }
                     } catch (IOException e) {
                         LOG.error("Error reading bucket file: " + e.getMessage());
                     }
                }
            }
        }
        
        // LOG.info("Assembled: {}", assembledFile.getAbsolutePath());
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
        
        extractedPatternPath = extractResource("/opt/flink/data/pattern.sre");
        extractedDeclarationsPath = extractResource("/opt/flink/data/declarations.sre");
    }

    /**
     * Processes commands from the Python Controller (TRAIN, OPT_INITIALISE, OPT_STEP, OPT_FINALISE).
     * 
     * This method implements the heavy computational part of the Bayesian optimization
     * loop, including in-memory training and validation of candidate models.
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
            LOG.info("FACTORY RECEIVED COMMAND: type={}", cmdType);
            
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
                LOG.info("TRAIN: In-Memory VMM Training pMin={}, gamma={}", pMin, gamma);
                
                // 1. Load Data
                List<stream.GenericEvent> events = loadEvents(datasetPath);

                // Minimum Data Guard (Phase 2)
                if (events.size() < MIN_DATA_THRESHOLD) {
                    LOG.warn("TRAIN SKIPPED: Insufficient data density ({} < {}). Noisy model risk.", events.size(), MIN_DATA_THRESHOLD);
                    emitError(out, m, cmdId, "Insufficient data density for retraining");
                    return;
                }
                
                // 2. Train In-Memory
                byte[] modelBytes = ui.WayebAdapter.trainInMemory(
                    events, 
                    extractedPatternPath, 
                    extractedDeclarationsPath, 
                    pMin, gamma, 
                    0.001, 1.05
                );
                
                // 3. Write to File
                Files.write(Paths.get(modelPath), modelBytes);
                
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
                if (datasetPath == null) {
                    emitError(out, m, cmdId, "No dataset available to lock");
                    return;
                }
                LOG.info("OPT_INITIALISE: Locking dataset for optimization...");
                
                // Lock the current dataset (won't be deleted during optimization)
                datasetInUseState.update(datasetPath);
                optimisationModelStartIdState.update(lastModelId);
                optModelParams.clear();
                
                LOG.info("Dataset locked: {}", datasetPath);
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
                
                LOG.info("OPT_STEP {}: In-Memory VMM Training pMin={}, gamma={}", optModelParams.size(), pMin, gamma);
                
                // 1. Load Data
                List<stream.GenericEvent> events = loadEvents(lockedDataset);

                // Minimum Data Guard (Phase 2)
                if (events.size() < MIN_DATA_THRESHOLD) {
                    LOG.warn("OPT_STEP SKIPPED: Insufficient data density ({} < {}).", events.size(), MIN_DATA_THRESHOLD);
                    emitError(out, m, cmdId, "Insufficient data density for optimization step");
                    return;
                }

                // 2. Train In-Memory
                byte[] modelBytes = ui.WayebAdapter.trainInMemory(
                    events, extractedPatternPath, extractedDeclarationsPath, pMin, gamma, 0.001, 1.05
                );
                
                // 3. Write to File
                Files.write(Paths.get(modelPath), modelBytes);
                
                // Test: Call in-memory test method (WayebEngine Exact Replica) to get actual MCC
                // ALIGNMENT: Pass Job configuration to the test method
                double mcc = ui.WayebAdapter.testInMemory(
                    events, 
                    modelPath, 
                    pMin, 
                    gamma, 
                    0.001, 
                    1.05, 
                    horizon, 
                    runConfidenceThreshold, 
                    maxSpread
                );
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
                
                LOG.info("OPT_FINALISE: In-Memory Re-training with best params (i={}): pMin={}, gamma={}", bestI, pMin, gamma);
                
                // 1. Load Data
                List<stream.GenericEvent> events = loadEvents(lockedDataset);

                // 2. Train In-Memory
                byte[] modelBytes = ui.WayebAdapter.trainInMemory(
                    events, extractedPatternPath, extractedDeclarationsPath, pMin, gamma, 0.001, 1.05
                );
                
                // 3. Write to File
                Files.write(Paths.get(modelPath), modelBytes);
                
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
                // Clean up locked dataset if it is no longer the current assembled dataset (stale)
                String currentDataset = currentAssembledDatasetState.value();
                if (lockedDataset != null && !lockedDataset.equals(currentDataset)) {
                     try {
                        Files.deleteIfExists(Paths.get(lockedDataset));
                        LOG.info("Deleted stale locked dataset after optimization: {}", lockedDataset);
                    } catch (IOException e) {
                        LOG.warn("Failed to delete stale locked dataset: {}", e.getMessage());
                    }
                }

                // Release resources
                datasetInUseState.clear();
                
                // CLEANUP: Delete intermediate models generated during this optimization session
                Integer startId = optimisationModelStartIdState.value();
                if (startId != null) {
                    LOG.info("Cleaning up intermediate models from ID {} to {}", startId, lastModelId - 1);
                    for (int id = startId; id < lastModelId; id++) {
                        String tempModelPath = "/opt/flink/data/saved_models/wayeb_model_" + id + ".spst";
                        try {
                            if (Files.deleteIfExists(Paths.get(tempModelPath))) {
                                LOG.debug("Deleted intermediate model: {}", tempModelPath);
                            }
                        } catch (IOException e) {
                            LOG.warn("Failed to delete intermediate model {}: {}", tempModelPath, e.getMessage());
                        }
                    }
                }
                
                optimisationModelStartIdState.clear();
                optModelParams.clear();
                lastModelIdState.update(lastModelId + 1);
                
                LOG.info("Optimization ended. Final model_id={}", lastModelId);
            }
            
            else {
                LOG.warn("Unknown command type: {}", cmdType);
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
     * Processes dataset notifications from the Collector.
     * 
     * Triggers the immediate assembly of new buckets into a searchable dataset
     * and performs cleanup of stale dataset files that are not currently locked.
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
                LOG.error("Invalid dataset notification format");
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
            String lockedPath = datasetInUseState.value();
            
            if (oldAssembledPath != null && !oldAssembledPath.equals(newAssembledPath)) {
                // Check if this dataset is currently locked for optimization
                if (lockedPath != null && lockedPath.equals(oldAssembledPath)) {
                     LOG.info("Skipping deletion of locked dataset: {}", oldAssembledPath);
                } else {
                    try {
                        Files.deleteIfExists(Paths.get(oldAssembledPath));
                        LOG.debug("Deleted old assembled dataset: {}", oldAssembledPath);
                    } catch (IOException e) {
                        System.err.println("[ModelFactoryEngine] Failed to delete old dataset: " + oldAssembledPath + " - " + e.getMessage());
                    }
                }
            }
            
            // 3. Update state with the new path
            currentAssembledDatasetState.update(newAssembledPath);
            
            LOG.info("Dataset updated to version {}", version);

            // 4. Emit Assembly Report (ACK) for Collector
            // Structure matches Python's expected "assembly_reports"
            ObjectNode ack = m.createObjectNode();
            ack.put("version", version);
            ack.set("buckets_range", rangeNode); // Pass through the range
            // ack.put("path", newAssembledPath); // Optional
            
            // We use the MAIN output collector but with a specific wrapper or side output?
            // Python Factory emits to a different topic.
            // ModelFactoryJob connects main output to 'factory_reports'.
            // We need to differentiate.
            // Solution: Emit a JSON with "type": "assembly_report" to the main output,
            // AND the Job will route it? Or simple emit purely for Flink internal loop?
            // The User requested alignment with Python. Python emits to 'assembleddatasets'.
            // Let's assume ModelFactoryJob will have a side output sink or we mix it in main topic.
            // Mixing in main topic 'factory_reports' might confuse Controller unless filtered.
            // Best approach: Use a SideOutput defined in ModelFactoryEngine.
            
            ctx.output(assemblyTag, ack.toString());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Reads a dataset file (line-by-line CSV) into a list of GenericEvent objects.
     * Expects CSV from Collector: timestamp,mmsi,lon,lat,speed,heading,cog,annotation
     */
    private List<stream.GenericEvent> loadEvents(String datasetPath) throws IOException {
        List<stream.GenericEvent> events = new ArrayList<>();
        ObjectMapper m = getMapper();
        
        List<String> lines = Files.readAllLines(Paths.get(datasetPath));
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            try {
                // Parse JSONL line
                JsonNode root = m.readTree(line);
                
                // Extract Standard Fields (Data Agnostic)
                // We assume the stored JSONL follows the structure emitted by Collector
                long ts = root.path("timestamp").asLong();
                
                // Construct attributes map
                java.util.Map<String, Object> attributes = new java.util.HashMap<>();
                Iterator<String> fieldNames = root.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    JsonNode node = root.get(fieldName);
                    
                    if (node.isNumber()) {
                        if (node.isIntegralNumber()) {
                            // attributes.put(fieldName, node.asLong()); 
                            // Wayeb usually prefers Double for compatibility
                             attributes.put(fieldName, node.asDouble());
                        } else {
                            attributes.put(fieldName, node.asDouble());
                        }
                    } else if (node.isTextual()) {
                        attributes.put(fieldName, node.asText());
                    } else if (node.isBoolean()) {
                        attributes.put(fieldName, node.asBoolean());
                    }
                }
                
                String type = "SampledCritical"; // Default type or extract from JSON if available?
                // For now, keep "SampledCritical" or make it generic "EVENT"
                if (root.has("type")) type = root.get("type").asText();

                // Use Bridge to create Scala Case Class
                events.add(ui.WayebAdapter.createEvent(ts, type, attributes));
            } catch (Exception e) {
                 // LOG.warn("Skipping malformed JSON line: {}", line);
            }
        }
        return events;
    }
}
