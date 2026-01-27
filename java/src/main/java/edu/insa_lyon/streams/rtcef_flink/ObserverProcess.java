package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Brain of the Loop.
 * 
 * Monitors the GLOBAL performance of the model (aggregated from all ships).
 * Decides when to trigger RETRAIN or OPTIMIZE instructions.
 * 
 * Logic adapted from original 'observer.py'.
 */
public class ObserverProcess extends KeyedProcessFunction<String, ReportOutput, String> {

    private static final Logger LOG = LoggerFactory.getLogger(ObserverProcess.class);

    // Thresholds (could be parameterized)
    private final double trainDiffThreshold; // traind
    private final double optDiffThreshold;   // hoptd
    private final double lowScoreThreshold;
    private final int k = 2; // For difference/trend
    private final int gracePeriodInit;

    // State
    private transient ListState<Double> scoreHistoryState;
    private transient ValueState<Integer> gracePeriodState;
    private transient ValueState<Integer> modelIdState; // To track unique instruction IDs

    // Utils
    private transient ObjectMapper mapper;

    public ObserverProcess(double trainDiffThreshold, double optDiffThreshold, double lowScoreThreshold, int gracePeriod) {
        this.trainDiffThreshold = trainDiffThreshold;
        this.optDiffThreshold = optDiffThreshold;
        this.lowScoreThreshold = lowScoreThreshold;
        this.gracePeriodInit = gracePeriod;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        scoreHistoryState = getRuntimeContext().getListState(new ListStateDescriptor<>("scoreHistory", Double.class));
        gracePeriodState = getRuntimeContext().getState(new ValueStateDescriptor<>("gracePeriod", Integer.class));
        modelIdState = getRuntimeContext().getState(new ValueStateDescriptor<>("modelIdCounter", Integer.class));
        mapper = new ObjectMapper();
    }

    @Override
    public void processElement(ReportOutput report, Context ctx, Collector<String> out) throws Exception {
        // New: Activity Discrimination
        // If no relevant events in this window (TP+FP+FN == 0), skip assessment to avoid false alarms.
        if (report.batch.tp + report.batch.fp + report.batch.fn == 0) {
            LOG.debug("Silent window (Key={}). Ignoring MCC for decision.", report.key);
            Integer grace = gracePeriodState.value();
            if (grace != null && grace > 0) {
                gracePeriodState.update(grace - 1);
            }
            return; 
        }

        // 1. Get Metric (Using MCC from BATCH/Window as it is the most reactive)
        double currentScore = report.batch.mcc; 
        
        // 2. Manage History
        List<Double> scores = new ArrayList<>();
        scoreHistoryState.get().iterator().forEachRemaining(scores::add);
        scores.add(currentScore);
        
        // Keep only last K
        while (scores.size() > k) {
            scores.remove(0);
        }
        scoreHistoryState.update(scores);
        
        // 3. Grace Period Check
        Integer grace = gracePeriodState.value();
        if (grace == null) grace = 0;

        // Log heartbeat every report for high visibility during development
        LOG.info("Observer Heartbeat: Key={} Current Score (MCC)={}. (Grace Period Slots: {})", report.key, currentScore, grace);

        if (grace > 0) {
            LOG.debug("In Grace Period ({} remaining). Ignoring score {}", grace, currentScore);
            gracePeriodState.update(grace - 1);
            return;
        }

        // 4. Assessment Logic
        String decision = "noop";
        
        // A. Low Score Safety Net
        if (currentScore < lowScoreThreshold) {
            decision = "optimize";
            LOG.warn("Low Score Alert! {} < {}", currentScore, lowScoreThreshold);
        }
        // B. Difference Method (Drop Detection)
        else if (scores.size() >= 2) {
            double prev = scores.get(scores.size() - 2);
            double curr = scores.get(scores.size() - 1);
            double diff = prev - curr; 
            
            // Positive diff means score dropped
            if (diff > optDiffThreshold) {
                decision = "optimize";
                LOG.info("Major Drop detected (diff={}). Decision: OPTIMIZE", diff);
            } else if (diff > trainDiffThreshold) {
                decision = "retrain"; 
                LOG.info("Minor Drop detected (diff={}). Decision: RETRAIN", diff);
            }
        }

        // 5. Emit Instruction
        if (!decision.equals("noop")) {
            Integer id = modelIdState.value();
            if (id == null) id = 0;
            
            ObjectNode json = mapper.createObjectNode();
            json.put("id", id);
            json.put("timestamp", report.timestamp);
            json.put("instruction_type", decision); // Matches controller python expectations
            json.put("model_id", "dynamic_v" + id); // Simple logical ID
            json.put("instruction", decision); // Legacy alias
            
            // Metrics object for closed-loop optimization (see skopt_wrapper.py line 214)
            // f_val is the objective to MINIMIZE. We use negative MCC since higher MCC = better.
            ObjectNode metricsNode = mapper.createObjectNode();
            metricsNode.put("mcc", currentScore);
            metricsNode.put("f_val", -currentScore); // Negate: minimize(-mcc) = maximize(mcc)
            metricsNode.put("precision", report.batch.precision);
            metricsNode.put("recall", report.batch.recall);
            metricsNode.put("f1", report.batch.f1);
            json.set("metrics", metricsNode);

            out.collect(json.toString());
            
            // Update State
            modelIdState.update(id + 1);
            gracePeriodState.update(gracePeriodInit);
        }
    }
}
