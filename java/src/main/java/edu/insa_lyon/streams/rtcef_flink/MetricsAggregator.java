package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.Scores;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates reports into a GLOBAL view with state persistence.
 * * FEATURES:
 * 1. Memory State: Remembers latest stats of silent ships to prevent Runtime drops.
 * 2. Activity Filter: Only emits reports if the window contains actual activity 
 * (TP, FP, or FN). Silent windows (TN only) are suppressed.
 */
public class MetricsAggregator extends ProcessAllWindowFunction<ReportOutput, ReportOutput, TimeWindow> {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsAggregator.class);
    
    // MEMORY STATE: Stores the latest cumulative stats for each ship Key
    private final Map<String, ReportOutput.MetricGroup> shipHistory = new HashMap<>();

    @Override
    public void process(Context context, Iterable<ReportOutput> elements, Collector<ReportOutput> out) {
        
        long maxTs = 0;
        
        // 1. Batch Stats (Local to this window - No Memory)
        long bTP = 0, bTN = 0, bFP = 0, bFN = 0;
        int count = 0;
        
        // 2. Update Memory with new reports
        for (ReportOutput r : elements) {
            count++;
            maxTs = Math.max(maxTs, r.timestamp);
            
            // Accumulate Batch (Window) stats
            bTP += r.batch.tp; bTN += r.batch.tn;
            bFP += r.batch.fp; bFN += r.batch.fn;
            
            // Update History for this ship (Runtime is cumulative)
            shipHistory.put(r.key, r.runtime);
        }

        // 3. Calculate Global Runtime Stats (Sum of ALL ships in history)
        long gRtTP = 0, gRtTN = 0, gRtFP = 0, gRtFN = 0;
        for (ReportOutput.MetricGroup m : shipHistory.values()) {
             gRtTP += m.tp; gRtTN += m.tn; 
             gRtFP += m.fp; gRtFN += m.fn;
        }

        // 4. Calculate Scores
        Map<String, Double> rtScores = Scores.getMetrics(gRtTP, gRtTN, gRtFP, gRtFN);
        Map<String, Double> bScores = Scores.getMetrics(bTP, bTN, bFP, bFN);

        // 5. Emit Merged Report ONLY if there is relevant activity
        // We filter if (TP + FP + FN == 0) because pure TN windows are "silent".
        // If we filtered strictly on TP > 0, we would hide errors (FN/FP) from the Observer.
        if (bTP + bFP + bFN > 0) {
            
            if (bTP > 0) {
                 LOG.info("WINDOW: Emitting Report (Count={} | Batch TP={})", count, bTP);
            }

            ReportOutput aggregated = new ReportOutput();
            aggregated.timestamp = maxTs > 0 ? maxTs : context.window().getEnd();
            aggregated.key = "GLOBAL";

            aggregated.runtime = new ReportOutput.MetricGroup(
                gRtTP, gRtTN, gRtFP, gRtFN,
                rtScores.get("precision"), rtScores.get("recall"), rtScores.get("f1"), rtScores.get("mcc")
            );

            aggregated.batch = new ReportOutput.MetricGroup(
                bTP, bTN, bFP, bFN,
                bScores.get("precision"), bScores.get("recall"), bScores.get("f1"), bScores.get("mcc")
            );

            out.collect(aggregated);
        }
    }
}