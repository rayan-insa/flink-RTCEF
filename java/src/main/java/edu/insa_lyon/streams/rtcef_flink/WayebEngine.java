package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.PredictionOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.Scores;

// Flink JSON (Shaded Jackson) for clean output
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
// Standard Java Imports
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

// Scala & Wayeb Imports
// Wayeb is written in Scala, so we need converters to use its collections in Java.
import scala.collection.JavaConverters;
import scala.Tuple5;
import scala.Enumeration;
import scala.Option;
import stream.GenericEvent;
import fsm.FSMInterface;
import fsm.runtime.Run;
import fsm.runtime.Run$;
import fsm.runtime.RunMessage;
import fsm.runtime.RunListener;
import fsm.runtime.Match;
import model.vmm.pst.CyclicBuffer;
import model.forecaster.runtime.ForecasterRun;
import model.forecaster.runtime.ForecasterRunFactory;
import model.forecaster.runtime.RelativeForecast;
import model.waitingTime.ForecastMethod;
import profiler.WtProfiler;
import profiler.ForecastCollector;
import scala.Tuple3;

// Provider Imports
// These classes help load the Models (FSMs) from files.
import workflow.provider.FSMProvider;
import workflow.provider.WtProvider;
import workflow.provider.ForecasterProvider;
import workflow.provider.source.wt.WtSourceMatrix;
import workflow.provider.source.forecaster.ForecasterSourceBuild;
import workflow.provider.source.matrix.MCSourceSerialized;
import workflow.provider.source.sdfa.SDFASourceSerialized;
import workflow.provider.SDFAProvider;
import workflow.provider.MarkovChainProvider;

/**
 * WayebEngine acts as a "Bridge" or "Wrapper".
 * 
 * Flink manages the distributed stream and fault tolerance.
 * Wayeb manages the complex event logic (Detection & Forecasting).
 * 
 * This class implements Dynamic Model Loading:
 * - processElement: Maritime events for inference.
 * - processBroadcastElement: Model update notifications from ModelFactory.
 */
public class WayebEngine extends KeyedBroadcastProcessFunction<String, GenericEvent, String, ReportOutput> {
    // =========================================================================
    // 1. Configuration (The Blueprint)
    // =========================================================================
    private final String initialModelPath;
    private final int horizon;
    private final double runConfidenceThreshold;
    private final int maxSpread;
    private final boolean finalsEnabled = false;
    private final long statsReportingDistance;
    public static final OutputTag<String> MATCH_TAG = new OutputTag<String>("detections"){};
    public static final OutputTag<PredictionOutput> PRED_TAG = new OutputTag<PredictionOutput>("predictions"){};

    // =========================================================================
    // 2. Flink Persistent State (The Memory)
    // =========================================================================
    // =========================================================================
    // 2. Flink Persistent State (The Memory)
    // =========================================================================
    private ValueState<fsm.symbolic.sra.Configuration> confState;
    private ValueState<Boolean> startedState;
    private ValueState<CyclicBuffer> bufferState;
    private ValueState<Match> matchState;
    private ValueState<Long> counterState;
    private ValueState<long[]> statsOffsetState;
    private ValueState<long[]> statsHistoryState;
    private ValueState<Long> nextReportTimeState;

    // Dynamic Model State (Keyed)
    private ValueState<String> currentModelPathState; 
    private ValueState<String> scheduledModelPathState; // Track which update was already scheduled per key
    private ValueState<String> pendingModelPathState; 
    private ValueState<Long> syncTimestampState;
    
    // Engine Sync State (pause during optimization)
    private ValueState<Boolean> pausedState;     

    // =========================================================================
    // 3. Transient Objects (The Engines)
    // =========================================================================
    private transient FSMInterface fsm;
    private transient ForecasterProvider predProvider;
    private transient Run runEngine;
    private transient ForecasterRun forecasterEngine;
    private transient WtProfiler profiler;
    private transient ObjectMapper jsonMapper;

    // Last seen event time (per operator instance, not keyed state)
    // Used mainly for calculating synchronization point in processBroadcastElement if possible
    private transient long lastEventTime = 0;

    public WayebEngine(
            String initialModelPath,
            int horizon,
            double runConfidenceThreshold,
            int maxSpread,
            long statsReportingDistance) {
        this.initialModelPath = initialModelPath;
        this.horizon = horizon;
        this.runConfidenceThreshold = runConfidenceThreshold;
        this.maxSpread = maxSpread;
        this.statsReportingDistance = statsReportingDistance;
    }

    private void loadWayebModels(String path) throws Exception {
        System.out.println("[WayebEngine] Loading model from: " + path);
        
        SDFASourceSerialized sdfaSource = SDFASourceSerialized.apply(path);
        SDFAProvider sdfaProvider = SDFAProvider.apply(sdfaSource);
        FSMProvider fsmProvider = FSMProvider.apply(sdfaProvider);

        List<FSMInterface> fsmList = JavaConverters.seqAsJavaList(fsmProvider.provide());
        this.fsm = fsmList.get(0);

        MarkovChainProvider markovChainProvider = MarkovChainProvider
                .apply(MCSourceSerialized.apply(path + ".mc"));
        
        WtProvider wtp = WtProvider
                .apply(WtSourceMatrix.apply(fsmProvider, markovChainProvider, horizon, finalsEnabled));

        Enumeration.Value methodEnum = ForecastMethod.CLASSIFY_NEXTK();
        this.predProvider = ForecasterProvider.apply(
                ForecasterSourceBuild.apply(
                        fsmProvider,
                        wtp,
                        horizon,
                        runConfidenceThreshold,
                        maxSpread,
                        methodEnum));
        
        if (runEngine != null) {
            System.out.println("[WayebEngine] Swapping running engines to new model...");
            Tuple5<fsm.symbolic.sra.Configuration, Object, CyclicBuffer, Match, Object> snapshot = runEngine.snapshotState();
            initializeEngineInternal(null); 
            runEngine.restoreState(snapshot._1(), (Boolean)snapshot._2(), snapshot._3(), snapshot._4(), (Long)snapshot._5());
            System.out.println("[WayebEngine] Engine swapped successfully.");
        }
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        this.profiler = new WtProfiler();
        this.jsonMapper = new ObjectMapper();

        statsHistoryState = getRuntimeContext().getState(new ValueStateDescriptor<>("statsHistory", long[].class));
        statsOffsetState = getRuntimeContext().getState(new ValueStateDescriptor<>("statsOffset", long[].class));
        confState = getRuntimeContext().getState(new ValueStateDescriptor<>("conf", fsm.symbolic.sra.Configuration.class));
        startedState = getRuntimeContext().getState(new ValueStateDescriptor<>("started", Boolean.class));
        bufferState = getRuntimeContext().getState(new ValueStateDescriptor<>("buffer", CyclicBuffer.class));
        matchState = getRuntimeContext().getState(new ValueStateDescriptor<>("match", Match.class));
        counterState = getRuntimeContext().getState(new ValueStateDescriptor<>("counter", Long.class));
        nextReportTimeState = getRuntimeContext().getState(new ValueStateDescriptor<>("nextReportTime", Long.class));
        
        currentModelPathState = getRuntimeContext().getState(new ValueStateDescriptor<>("currentModelPath", String.class));
        scheduledModelPathState = getRuntimeContext().getState(new ValueStateDescriptor<>("scheduledModelPath", String.class));
        pendingModelPathState = getRuntimeContext().getState(new ValueStateDescriptor<>("pendingModelPath", String.class));
        syncTimestampState = getRuntimeContext().getState(new ValueStateDescriptor<>("syncTimestamp", Long.class));
        pausedState = getRuntimeContext().getState(new ValueStateDescriptor<>("paused", Boolean.class));
    }

    /**
     * Process Maritime Events (Inference)
     */
    @Override
    public void processElement(GenericEvent event, ReadOnlyContext ctx, Collector<ReportOutput> out) throws Exception {
        this.lastEventTime = event.timestamp();
        
        // 0. Check if engine is PAUSED (during optimization)
        Boolean paused = pausedState.value();
        if (paused != null && paused) {
            // Engine is paused - skip processing (events are dropped during optimization)
            // A more sophisticated impl could buffer events
            return;
        }
        
        // 0.5. Check for SYNC commands (pause/play) in broadcast state
        ReadOnlyBroadcastState<String, String> bState = ctx.getBroadcastState(InferenceJob.MODEL_UPDATE_DESCRIPTOR);
        String syncJson = bState.get("SYNC");
        if (syncJson != null) {
            ObjectMapper m = getMapper();
            JsonNode syncRoot = m.readTree(syncJson);
            String syncType = syncRoot.path("type").asText();
            
            if ("pause".equalsIgnoreCase(syncType)) {
                if (paused == null || !paused) {
                    System.out.println("[WayebEngine] Key=" + ctx.getCurrentKey() + " PAUSING due to optimization");
                    pausedState.update(true);
                    return; // Skip processing
                }
            } else if ("play".equalsIgnoreCase(syncType)) {
                if (paused != null && paused) {
                    System.out.println("[WayebEngine] Key=" + ctx.getCurrentKey() + " RESUMING after optimization");
                    pausedState.update(false);
                    // Continue processing - don't return
                }
            }
        }
        
        // 1. Check for NEW BROADCAST MODELS that haven't been scheduled for this key yet
        String latestUpdateJson = bState.get("LATEST");
        
        if (latestUpdateJson != null) {
            ObjectMapper m = getMapper();
            JsonNode root = m.readTree(latestUpdateJson);
            String newPath = root.path("model_path").asText();
            
            // If this update is different from the one currently active or already scheduled
            String alreadyScheduled = scheduledModelPathState.value();
            if (!newPath.equals(currentModelPathState.value()) && !newPath.equals(alreadyScheduled)) {
                long productionTime = root.path("pt").asLong(0);
                long syncTime = event.timestamp() + (productionTime / 1000); 
                
                System.out.println("[WayebEngine] Key=" + ctx.getCurrentKey() + " Scheduling model swap to " + newPath + " at T=" + syncTime);
                pendingModelPathState.update(newPath);
                syncTimestampState.update(syncTime);
                scheduledModelPathState.update(newPath);
            }
        }

        // 2. Initial Load Check
        String currentPath = currentModelPathState.value();
        if (currentPath == null) {
            loadWayebModels(initialModelPath);
            currentModelPathState.update(initialModelPath);
        } else if (fsm == null) {
            loadWayebModels(currentPath);
        }
        
        // 3. Check Synchronized Swap
        Long syncTime = syncTimestampState.value();
        if (syncTime != null && event.timestamp() >= syncTime) {
            String pendingPath = pendingModelPathState.value();
            System.out.println("[WayebEngine] Key=" + ctx.getCurrentKey() + " Sync reached (" + event.timestamp() + " >= " + syncTime + "). Swapping.");
            loadWayebModels(pendingPath);
            currentModelPathState.update(pendingPath);
            syncTimestampState.clear();
            pendingModelPathState.clear();
        }

        // 4. Engine Check
        if (runEngine == null) {
            initializeEngineInternal(ctx);
        }

        if (statsOffsetState.value() == null) {
            long[] history = statsHistoryState.value();
            statsOffsetState.update(history != null ? history : new long[]{0L, 0L, 0L, 0L});
        }

        // 5. Run Detection
        runEngine.processEventDet(event);

        // 6. Checkpointing
        Tuple5<fsm.symbolic.sra.Configuration, Object, CyclicBuffer, Match, Object> snapshot = runEngine.snapshotState();
        confState.update(snapshot._1());
        startedState.update((Boolean) snapshot._2());
        bufferState.update(snapshot._3());
        matchState.update(snapshot._4());
        counterState.update((Long) snapshot._5());

        checkAndReportStats(event.timestamp(), ctx.getCurrentKey(), out);
    }

    /**
     * Process Model Update Notifications: Store in Broadcast State
     */
    @Override
    public void processBroadcastElement(String jsonReport, Context ctx, Collector<ReportOutput> out) throws Exception {
        try {
            ObjectMapper m = getMapper();
            JsonNode root = m.readTree(jsonReport);
            
            // Handle SYNC commands (pause/play) from Controller
            String syncType = root.path("type").asText();
            if ("pause".equalsIgnoreCase(syncType)) {
                System.out.println("[WayebEngine] Broadcast: PAUSE received - pausing inference");
                // Store in broadcast state for all keyed instances to see
                BroadcastState<String, String> bState = ctx.getBroadcastState(InferenceJob.MODEL_UPDATE_DESCRIPTOR);
                bState.put("SYNC", jsonReport);
                return;
            } else if ("play".equalsIgnoreCase(syncType)) {
                String modelPath = root.path("model_path").asText();
                int modelId = root.path("model_id").asInt(-1);
                System.out.println("[WayebEngine] Broadcast: PLAY received - resuming with model_id=" + modelId);
                BroadcastState<String, String> bState = ctx.getBroadcastState(InferenceJob.MODEL_UPDATE_DESCRIPTOR);
                bState.put("SYNC", jsonReport);
                // If a new model path is provided, also store it as LATEST for loading
                if (modelPath != null && !modelPath.isEmpty()) {
                    bState.put("LATEST", jsonReport);
                }
                return;
            }
            
            // Handle Model Update notifications (existing logic)
            if ("success".equalsIgnoreCase(root.path("status").asText())) {
                String newPath = root.path("model_path").asText();
                System.out.println("[WayebEngine] Broadcast: New model available at " + newPath);
                
                // Put it in broadcast state so all keys can see it in processElement
                BroadcastState<String, String> bState = ctx.getBroadcastState(InferenceJob.MODEL_UPDATE_DESCRIPTOR);
                bState.put("LATEST", jsonReport);
            }
        } catch (Exception e) {
            System.err.println("[WayebEngine] Error in broadcast processing: " + e.getMessage());
        }
    }

    private ObjectMapper getMapper() {
        if (jsonMapper == null) jsonMapper = new ObjectMapper();
        return jsonMapper;
    }

    private void checkAndReportStats(long currentTime, String key, Collector<ReportOutput> out) throws Exception {
        Long currentNextReportTime = nextReportTimeState.value();
        if (currentNextReportTime == null) {
            currentNextReportTime = currentTime + statsReportingDistance;
            nextReportTimeState.update(currentNextReportTime);
        }

        if (currentTime >= currentNextReportTime) {
            List<ForecastCollector> singleCollectorList = Collections.singletonList(forecasterEngine.getCollector());
            scala.collection.immutable.List<ForecastCollector> scalaList = JavaConverters.asScalaBuffer(singleCollectorList).toList();

            Map<Object, scala.collection.immutable.List<ForecastCollector>> javaMap = Collections.singletonMap(0, scalaList);
            scala.collection.immutable.Map<Object, scala.collection.immutable.List<ForecastCollector>> scalaMap = JavaConverters
                    .mapAsScalaMap(javaMap).toMap(scala.Predef$.MODULE$.conforms());

            profiler.setGlobal(0, 0L, 0L, 0L, 0, 0, 0, null);
            profiler.createEstimators(scalaMap);
            profiler.estimateStats();

            long tp = Long.parseLong(profiler.getStatFor("tp", 0));
            long tn = Long.parseLong(profiler.getStatFor("tn", 0));
            long fp = Long.parseLong(profiler.getStatFor("fp", 0));
            long fn = Long.parseLong(profiler.getStatFor("fn", 0));

            long[] prevStats = statsHistoryState.value();
            if (prevStats == null) prevStats = new long[] { 0L, 0L, 0L, 0L };

            long batchTP = tp - prevStats[0];
            long batchTN = tn - prevStats[1];
            long batchFP = fp - prevStats[2];
            long batchFN = fn - prevStats[3];

            statsHistoryState.update(new long[] { tp, tn, fp, fn });

            Map<String, Double> runtimeScores = Scores.getMetrics(tp, tn, fp, fn);
            Map<String, Double> batchScores = Scores.getMetrics(batchTP, batchTN, batchFP, batchFN);

            ReportOutput.MetricGroup runtimeGroup = new ReportOutput.MetricGroup(
                tp, tn, fp, fn, runtimeScores.get("precision"), runtimeScores.get("recall"), runtimeScores.get("f1"), runtimeScores.get("mcc")
            );
            ReportOutput.MetricGroup batchGroup = new ReportOutput.MetricGroup(
                batchTP, batchTN, batchFP, batchFN, batchScores.get("precision"), batchScores.get("recall"), batchScores.get("f1"), batchScores.get("mcc")
            );

            out.collect(new ReportOutput(currentTime, key, runtimeGroup, batchGroup));
            nextReportTimeState.update(currentTime + statsReportingDistance);
        }
    }

    private void initializeEngineInternal(ReadOnlyContext ctx) throws Exception {
        runEngine = Run$.MODULE$.apply(1, this.fsm);

        scala.collection.immutable.List predList = this.predProvider.provide();
        ForecasterRunFactory predFactory = ForecasterRunFactory.apply(predList, true, finalsEnabled);
        forecasterEngine = predFactory.getNewForecasterRun(this.fsm.getId(), 1);

        runEngine.register(new RunListener() {
            @Override
            public void newEventProcessed(RunMessage rm) {
                forecasterEngine.newEventProcessed(rm);
                RelativeForecast pred = forecasterEngine.getCurrentPrediction();

                if (pred.isValid()) {
                    PredictionOutput output = new PredictionOutput(
                        rm.timestamp(),
                        (ctx != null) ? ctx.getCurrentKey() : "unknown", 
                        pred.prob(),
                        pred.startRelativeToNow(),
                        pred.endRelativeToNow(),
                        pred.isPositive()
                    );
                    if (ctx != null) ctx.output(PRED_TAG, output);
                }

                if (rm.fmDetected()) {
                    if (ctx != null) ctx.output(MATCH_TAG, "Detected Pattern at " + rm.timestamp());
                }
            }
            @Override
            public void shutdown() {}
        });

        if (confState.value() != null) {
            runEngine.restoreState(confState.value(), startedState.value(), bufferState.value(), matchState.value(), counterState.value());
        }
    }
}