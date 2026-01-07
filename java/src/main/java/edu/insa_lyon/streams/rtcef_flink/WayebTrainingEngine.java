package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import ui.WayebBridge;

public class WayebTrainingEngine extends KeyedProcessFunction<String, String, String> {

    // JSON Mapper
    // Use transient layout or recreate per instance if needed, but Flink functions are serialized.
    // Making it transient lazy-loaded is preferred or creating in open().
    private transient ObjectMapper mapper;

    private ObjectMapper getMapper() {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
        return mapper;
    }

    @Override
    public void processElement(String jsonCommand, Context ctx, Collector<String> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode rootNode = m.readTree(jsonCommand);
            String cmdType = rootNode.path("type").asText();

            if ("train".equals(cmdType)) {
                int cmdId = rootNode.path("id").asInt();
                String datasetPath = rootNode.path("dataset_path").asText();
                String paramsJsonStr = rootNode.path("params").asText();

                // Parse nested params
                JsonNode paramsNode = m.readTree(paramsJsonStr);
                // Expected payload: {"params": [confidence, order, pmin, gamma]}
                JsonNode paramsArray = paramsNode.path("params");
                
                double pMin = paramsArray.get(2).asDouble();
                double gamma = paramsArray.get(3).asDouble();
                
                // Defaults (could be extracted from config or params if available)
                double alpha = 0.001; // Default
                double r = 1.05;      // Default

                String modelPath = "/opt/flink/data/saved_models/wayeb_model_" + cmdId + ".spst";
                String patternFile = "/opt/flink/libraries/models/wrappers/patterns/enteringArea/pattern.sre";

                System.out.println("Starting training for ID " + cmdId + " with pMin=" + pMin + ", gamma=" + gamma);
                
                // Call Scala Bridge
                WayebBridge.train(
                    datasetPath,
                    modelPath,
                    patternFile,
                    pMin,
                    gamma,
                    alpha,
                    r
                );
                
                System.out.println("Training complete. Model saved to " + modelPath);

                // Emit Report
                // Schema: {"reply_id": 123, "model_id": 123, "model_path": "...", "status": "success"}
                var report = m.createObjectNode();
                report.put("reply_id", cmdId);
                report.put("model_id", cmdId);
                report.put("model_path", modelPath);
                report.put("status", "success");

                out.collect(report.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Optionally emit error report
        }
    }
}
