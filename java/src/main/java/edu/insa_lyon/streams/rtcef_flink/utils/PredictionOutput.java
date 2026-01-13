package edu.insa_lyon.streams.rtcef_flink.utils;

public class PredictionOutput {
    public long timestamp;
    public String key;
    public double probability;
    public int startIn;  // "Happens in X seconds" (start)
    public int endIn;    // "Happens in Y seconds" (end)
    public boolean isPositive; // For classification (Will it happen? Yes/No)

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
