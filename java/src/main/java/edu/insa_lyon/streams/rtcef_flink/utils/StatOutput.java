package edu.insa_lyon.streams.rtcef_flink.utils;

public class StatOutput {
    public long timestamp;
    public String key;
    public MetricGroup runtime;
    public MetricGroup batch;

    public StatOutput() {}

    public StatOutput(long timestamp, String key, MetricGroup runtime, MetricGroup batch) {
        this.timestamp = timestamp;
        this.key = key;
        this.runtime = runtime;
        this.batch = batch;
    }

    public static class MetricGroup {
        public long tp, tn, fp, fn;
        public double precision, recall, f1;

        public MetricGroup() {}

        public MetricGroup(long tp, long tn, long fp, long fn, double p, double r, double f1) {
            this.tp = tp; this.tn = tn; this.fp = fp; this.fn = fn;
            this.precision = p; this.recall = r; this.f1 = f1;
        }
    }
    
    @Override
    public String toString() {
        return "Report{ts=" + timestamp + ", key='" + key + "', runtime F1=" + runtime.f1 + "', batch F1=" + batch.f1 + "}";
    }
}