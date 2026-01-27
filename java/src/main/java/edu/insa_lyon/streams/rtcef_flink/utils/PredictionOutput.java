package edu.insa_lyon.streams.rtcef_flink.utils;

/**
 * POJO representing a forecasting prediction output.
 * 
 * Contains the probability of an event occurrence and the predicted
 * time window relative to the current timestamp.
 */
public class PredictionOutput {
    /** Event occurrence timestamp. */
    public long timestamp;
    /** Unique key (e.g., MMSI) for the entity being monitored. */
    public String key;
    /** Probability of the predicted event (0.0 to 1.0). */
    public double probability;
    /** Predicted start time relative to now (in seconds). */
    public int startIn;
    /** Predicted end time relative to now (in seconds). */
    public int endIn;
    /** Binary classification result (Will it happen?). */
    public boolean isPositive;

    public PredictionOutput() {}

    public PredictionOutput(long timestamp, String key, double probability, int startIn, int endIn, boolean isPositive) {
        this.timestamp = timestamp;
        this.key = key;
        this.probability = probability;
        this.startIn = startIn;
        this.endIn = endIn;
        this.isPositive = isPositive;
    }

    @Override
    public String toString() {
        return String.format("PRED{ts=%d, key='%s', prob=%.2f, window=[+%d, +%d]}", 
            timestamp, key, probability, startIn, endIn);
    }
}
