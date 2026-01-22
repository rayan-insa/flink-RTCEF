package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.functions.ReduceFunction;
import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.Scores;
import java.util.Map;

/**
 * Aggregates "Partial" ReportOutputs from parallel WayebEngine instances into a "Global" ReportOutput.
 * 
 * Logic:
 * 1. Sums the Raw Counts (TP, TN, FP, FN) for both Runtime and Batch metrics.
 * 2. Recalculates the derived scores (F1, MCC, Precision, Recall) based on the new sums.
 * 3. Preserves the latest timestamp.
 */
public class MetricsAggregator implements ReduceFunction<ReportOutput> {

    @Override
    public ReportOutput reduce(ReportOutput r1, ReportOutput r2) throws Exception {
        ReportOutput aggregated = new ReportOutput();
        
        // 1. Merge Metadata
        aggregated.timestamp = Math.max(r1.timestamp, r2.timestamp);
        aggregated.key = "GLOBAL"; // Merged report is global

        // 2. Aggregate Runtime Metrics (Cumulative since start)
        long rtTP = r1.runtime.tp + r2.runtime.tp;
        long rtTN = r1.runtime.tn + r2.runtime.tn;
        long rtFP = r1.runtime.fp + r2.runtime.fp;
        long rtFN = r1.runtime.fn + r2.runtime.fn;
        
        Map<String, Double> rtScores = Scores.getMetrics(rtTP, rtTN, rtFP, rtFN);
        
        aggregated.runtime = new ReportOutput.MetricGroup(
            rtTP, rtTN, rtFP, rtFN,
            rtScores.get("precision"),
            rtScores.get("recall"),
            rtScores.get("f1"),
            rtScores.get("mcc")
        );

        // 3. Aggregate Batch Metrics (Last Window)
        long bTP = r1.batch.tp + r2.batch.tp;
        long bTN = r1.batch.tn + r2.batch.tn;
        long bFP = r1.batch.fp + r2.batch.fp;
        long bFN = r1.batch.fn + r2.batch.fn;

        Map<String, Double> bScores = Scores.getMetrics(bTP, bTN, bFP, bFN);

        aggregated.batch = new ReportOutput.MetricGroup(
            bTP, bTN, bFP, bFN,
            bScores.get("precision"),
            bScores.get("recall"),
            bScores.get("f1"),
            bScores.get("mcc")
        );

        return aggregated;
    }
}
