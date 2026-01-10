package edu.insa_lyon.streams.rtcef_flink.utils;

import java.util.HashMap;
import java.util.Map;

public class Scores {

    /**
     * Estimates classification metrics based on a confusion matrix.
     *
     * @param tp True Positives
     * @param tn True Negatives
     * @param fp False Positives
     * @param fn False Negatives
     * @return A Map containing MCC, Precision, Recall, and F1-Score.
     */
    public static Map<String, Double> getMetrics(long tp, long tn, long fp, long fn) {
        
        // --- 1. Basic Sums ---
        double tpfp = tp + fp; // Total Predicted Positives
        double tpfn = tp + fn; // Total Actual Positives
        double tnfp = tn + fp; // Total Actual Negatives
        double tnfn = tn + fn; // Total Predicted Negatives
        double total = tp + tn + fp + fn;

        // --- 2. Precision, Recall, and F1 ---
        double precision = (tpfp != 0) ? (double) tp / tpfp : -1.0;
        double recall    = (tpfn != 0) ? (double) tp / tpfn : -1.0;
        
        double f1 = -1.0;
        if (precision != -1.0 && recall != -1.0 && (precision + recall) != 0.0) {
            f1 = (2.0 * precision * recall) / (precision + recall);
        }

        // --- 3. Additional Metrics for MCC ---
        double specificity = (tnfp != 0) ? (double) tn / tnfp : -1.0;
        double npv         = (tnfn != 0) ? (double) tn / tnfn : -1.0;
        // Accuracy calculation (optional, kept for parity with your Scala logic)
        double accuracy    = (total != 0) ? (double) (tp + tn) / total : -1.0;

        // --- 4. Matthews Correlation Coefficient (MCC) ---
        // Using the robust formula provided in your Scala code to avoid overflow
        double mcc = 0.0;
        if (tpfp != 0 && tpfn != 0 && tnfp != 0 && tnfn != 0) {
            double fdr  = 1.0 - precision;
            double fnr  = 1.0 - recall;
            double fpr  = 1.0 - specificity;
            double fomr = 1.0 - npv;

            mcc = Math.sqrt(precision * recall * specificity * npv) - 
                  Math.sqrt(fdr * fnr * fpr * fomr);
        }

        // --- 5. Construct Result Map ---
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("mcc", mcc);
        metrics.put("precision", precision);
        metrics.put("recall", recall);
        metrics.put("f1", f1);

        return metrics;
    }
}