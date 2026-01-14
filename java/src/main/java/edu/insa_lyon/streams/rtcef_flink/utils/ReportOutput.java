package edu.insa_lyon.streams.rtcef_flink.utils;

public class ReportOutput {
    public long timestamp;
    public String key;
    public MetricGroup runtime;
    public MetricGroup batch;

    public ReportOutput() {}

    public ReportOutput(long timestamp, String key, MetricGroup runtime, MetricGroup batch) {
        this.timestamp = timestamp;
        this.key = key;
        this.runtime = runtime;
        this.batch = batch;
    }

    public static class MetricGroup {
        public long tp, tn, fp, fn;
        public double precision, recall, f1;
        public double mcc;
        public MetricGroup() {}

        public MetricGroup(long tp, long tn, long fp, long fn, double p, double r, double f1, double mcc) {
            this.tp = tp; this.tn = tn; this.fp = fp; this.fn = fn;
            this.precision = p; this.recall = r; this.f1 = f1; this.mcc = mcc;
        }
    }
    
    @Override
    public String toString() {
        return "Report{ts=" + timestamp + ", key='" + key + "', runtime MCC=" + runtime.mcc + "', batch MCC=" + batch.mcc + "}";
    }
}