package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.PredictionOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.Scores;

// Flink JSON (Shaded Jackson) for clean output
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
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
 * 
 * FlinkEngine acts as a "Bridge" or "Wrapper".
 * 
 * * Flink manages the distributed stream and fault tolerance.
 * 
 * Wayeb manages the complex event logic (Detection & Forecasting).
 * 
 * * This class runs on the Worker Nodes (TaskManagers).
 * 
 */
public class FlinkEngine extends KeyedProcessFunction<String, GenericEvent, ReportOutput> {
        // =========================================================================
        // 1. Configuration (The Blueprint)
        // =========================================================================
        // These fields are simple Primitives/Strings. Flink serializes them on the
        // Master Node (JobManager) and sends copies to all Worker Nodes.
        private final String modelPath; // Path to the .spst model file on disk
        private final int horizon; // How far into the future we look (e.g. 30 steps)
        private final double runConfidenceThreshold; // Probability required to trigger an alert (0.0 to 1.0)
        private final int maxSpread; // Allowed variance in prediction
        private final boolean finalsEnabled = false;
        private final long statsReportingDistance = 600000;
        public static final OutputTag<String> MATCH_TAG = new OutputTag<String>("detections"){};
        public static final OutputTag<PredictionOutput> PRED_TAG = new OutputTag<PredictionOutput>("predictions"){};

        // =========================================================================
        // 2. Flink Persistent State (The Memory)
        // =========================================================================
        // Flink uses "State Handles" to save data to RocksDB or Memory.
        // If the machine crashes, Flink reloads these values to restore the job.
        private ValueState<fsm.symbolic.sra.Configuration> confState; // The current state of the automaton (e.g. State
                                                                      // 3)
        private ValueState<Boolean> startedState; // Has the engine started processing?
        private ValueState<CyclicBuffer> bufferState; // Internal event buffer
        private ValueState<Match> matchState; // Partial matches found so far
        private ValueState<Long> counterState; // Event counter (logic clock)
        private ValueState<long[]> statsOffsetState;
        private ValueState<long[]> statsHistoryState; // Previous cumulative metrics (TP, TN, FP, FN)
        private ValueState<Long> nextReportTimeState; // When to report stats

        // =========================================================================
        // 3. Transient Objects (The Engines)
        // =========================================================================
        // "transient" means: DO NOT SERIALIZE.
        // These complex objects (Wayeb Engines) cannot be sent over the network.
        // Instead, we build them from scratch on every Worker using the open() method.
        private transient FSMInterface fsm; // The static definition of the Pattern (The Map)
        private transient ForecasterProvider predProvider; // Factory for creating forecasters
        private transient Run runEngine; // The Runtime Detector (Moves through the Map)
        private transient ForecasterRun forecasterEngine;// The Runtime Predictor (Calculates Probabilities)
        private transient WtProfiler profiler;
        private transient ObjectMapper jsonMapper;

        // Constructor: Only stores configuration strings. No heavy lifting here.
        public FlinkEngine(
                        String modelPath,
                        int horizon,
                        double runConfidenceThreshold,
                        int maxSpread) {
                this.modelPath = modelPath;
                this.horizon = horizon;
                this.runConfidenceThreshold = runConfidenceThreshold;
                this.maxSpread = maxSpread;
        }

        /**
         * 
         * open() is the "Boot Up" sequence for the Worker.
         * 
         * It runs once when the TaskManager starts.
         * 
         */
        @Override
        public void open(Configuration parameters) throws Exception {
                super.open(parameters);
                // --- A. Lazy Loading (Solving the Serialization Problem) ---
                // Since we couldn't send the FSM object over the network, we load it
                // from the file system right here on the worker.
                // 1. Load the FSM (The pattern definition) from the .spst file
                SDFASourceSerialized sdfaSource = SDFASourceSerialized.apply(modelPath);
                SDFAProvider sdfaProvider = SDFAProvider.apply(sdfaSource);
                FSMProvider fsmProvider = FSMProvider.apply(sdfaProvider);

                // Convert Scala List -> Java List to access the first element
                List<FSMInterface> fsmList = JavaConverters.seqAsJavaList(fsmProvider.provide());
                this.fsm = fsmList.get(0);

                // 2. Load the Probabilities (Markov Chain) from the .mc file
                // This provides the math needed for forecasting.
                MarkovChainProvider markovChainProvider = MarkovChainProvider
                                .apply(MCSourceSerialized.apply(modelPath + ".mc"));
                // Build the Waiting Time Provider (predicts WHEN something happens)
                WtProvider wtp = WtProvider
                                .apply(WtSourceMatrix.apply(fsmProvider, markovChainProvider, horizon, finalsEnabled));

                                
                // 3. Build the Forecaster Provider (Factory)
                Enumeration.Value methodEnum = ForecastMethod.CLASSIFY_NEXTK();
                this.predProvider = ForecasterProvider.apply(
                                ForecasterSourceBuild.apply(
                                                fsmProvider,
                                                wtp,
                                                horizon,
                                                runConfidenceThreshold,
                                                maxSpread,
                                                methodEnum));

                this.profiler = new WtProfiler();
                this.jsonMapper = new ObjectMapper();

                // --- B. Initialize State Handles ---
                // We tell Flink: "I need you to manage these specific variables for me."
                statsHistoryState = getRuntimeContext().getState(
                        new ValueStateDescriptor<>("statsHistory", long[].class));
                statsOffsetState = getRuntimeContext().getState(
                        new ValueStateDescriptor<>("statsOffset", long[].class));
                confState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("conf", fsm.symbolic.sra.Configuration.class));
                startedState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("started", Boolean.class));
                bufferState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("buffer", CyclicBuffer.class));
                matchState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("match", Match.class));
                counterState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("counter", Long.class));
                statsHistoryState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("statsHistory", long[].class));
                nextReportTimeState = getRuntimeContext().getState(
                                new ValueStateDescriptor<>("nextReportTime", Long.class));
        }

        /**
         * 
         * processElement() is the Main Loop.
         * 
         * Flink calls this for EVERY single event in the stream.
         * 
         */
        @Override
        public void processElement(GenericEvent event, Context ctx, Collector<ReportOutput> out) throws Exception {
                // 1. Engine Check: If the engines (Detector/Forecaster) don't exist yet, create
                // them.
                if (runEngine == null) {
                        initializeEngine(ctx);
                }

                if (statsOffsetState.value() == null) {
                        long[] history = statsHistoryState.value();
                        if (history != null) {
                                // recovering from crash: set offset to the saved history
                                statsOffsetState.update(history);
                        } else {
                                // fresh start for this key: set offset to 0
                                statsOffsetState.update(new long[]{0L, 0L, 0L, 0L});
                        }
                }

                // 2. Run Detection Logic
                // We feed the event into Wayeb. It updates its internal state
                // (e.g., moves from State A -> State B).
                runEngine.processEventDet(event);

                // Note: Forecasting happens automatically via the Listener we set up in
                // initializeEngine()
                // 3. Checkpointing (Fault Tolerance)
                // We pull the state OUT of Wayeb and push it INTO Flink.
                // If the machine crashes after this line, Flink can restore Wayeb exactly as it
                // was.
                Tuple5<fsm.symbolic.sra.Configuration, Object, CyclicBuffer, Match, Object> snapshot = runEngine
                                .snapshotState();
                confState.update(snapshot._1());
                startedState.update((Boolean) snapshot._2());
                bufferState.update(snapshot._3());
                matchState.update(snapshot._4());
                counterState.update((Long) snapshot._5());

                long currentTime = event.timestamp();

                // Retrieve the current "currentNextReportTime" from Flink State
                Long currentNextReportTime = nextReportTimeState.value();

                // If it's the first event for this key, initialize the timer
                if (currentNextReportTime == null) {
                        currentNextReportTime = currentTime + statsReportingDistance;
                        nextReportTimeState.update(currentNextReportTime);
                }

                // Check if it's time to report stats
                if (currentTime >= currentNextReportTime) {
                        // 1. Wrap the single collector in a Java List
                        List<ForecastCollector> singleCollectorList = Collections
                                        .singletonList(forecasterEngine.getCollector());

                        // 2. Convert to Scala Buffer (List)
                        scala.collection.immutable.List<ForecastCollector> scalaList = JavaConverters
                                        .asScalaBuffer(singleCollectorList).toList();

                        // 3. Create the Map (ID -> Collectors)
                        // Force the key to be Integer 0, so it matches profiler.getStatFor(..., 0)
                        Map<Object, scala.collection.immutable.List<ForecastCollector>> javaMap = Collections
                                        .singletonMap(0,
                                                        scalaList);
                        scala.collection.immutable.Map<Object, scala.collection.immutable.List<ForecastCollector>> scalaMap = JavaConverters
                                        .mapAsScalaMap(javaMap).toMap(scala.Predef$.MODULE$.conforms());

                        // B. Run Profiler Calculation
                        // Note: We pass 0s for streamSize/execTime as they are less relevant per-event
                        // in Flink
                        profiler.setGlobal(0, 0L, 0L, 0L, 0, 0, 0, null);
                        profiler.createEstimators(scalaMap);
                        profiler.estimateStats();

                        // C. Extract TP/TN/FP/FN from Profiler
                        // getTPTNFPFN returns a Scala Seq. We access index 0 because we have 1 pattern.
                        long tp = Long.parseLong(profiler.getStatFor("tp", 0));
                        long tn = Long.parseLong(profiler.getStatFor("tn", 0));
                        long fp = Long.parseLong(profiler.getStatFor("fp", 0));
                        long fn = Long.parseLong(profiler.getStatFor("fn", 0));

                        // D. Calculate Batch Metrics (Delta from last report)
                        long[] prevStats = statsHistoryState.value();

                        if (prevStats == null) {
                                prevStats = new long[] { 0L, 0L, 0L, 0L };
                        }

                        long batchTP = tp - prevStats[0];
                        long batchTN = tn - prevStats[1];
                        long batchFP = fp - prevStats[2];
                        long batchFN = fn - prevStats[3];

                        // Update State with new cumulative totals
                        statsHistoryState.update(new long[] { tp, tn, fp, fn });

                        // E. Compute Scores (Precision, Recall, F1)
                        // We use the Scala Utils provided by Wayeb
                        Map<String, Double> runtimeScores = Scores.getMetrics(tp, tn, fp, fn);
                        Map<String, Double> batchScores = Scores.getMetrics(batchTP, batchTN, batchFP, batchFN);

                        // F. Construct Reports
                        ReportOutput.MetricGroup runtimeGroup = new ReportOutput.MetricGroup(
                                tp, tn, fp, fn, runtimeScores.get("precision"), runtimeScores.get("recall"), runtimeScores.get("f1"), 
                                runtimeScores.get("mcc")
                        );
                        ReportOutput.MetricGroup batchGroup = new ReportOutput.MetricGroup(
                                batchTP, batchTN, batchFP, batchFN, batchScores.get("precision"), batchScores.get("recall"), batchScores.get("f1"),
                                batchScores.get("mcc") // Use defaults if null
                        );

                        // G. Emit to Output Stream
                        // The Observer will look for lines starting with "STATS:" or parse the JSON
                        out.collect(new ReportOutput(currentTime, ctx.getCurrentKey(), runtimeGroup, batchGroup));

                        currentNextReportTime = currentTime + statsReportingDistance;
                        nextReportTimeState.update(currentNextReportTime);
                }
        }
        /**
         * 
         * Wiring the components together.
         * 
         */
        private void initializeEngine(Context ctx) throws Exception {
                // 1. Create the Detector (Run)
                // This tracks where we are in the pattern.
                runEngine = Run$.MODULE$.apply(1, this.fsm);

                // 2. Create the Predictor (Forecaster)
                // This calculates probabilities based on where the Detector is.
                scala.collection.immutable.List predList = this.predProvider.provide();

                ForecasterRunFactory predFactory = ForecasterRunFactory.apply(predList, true, finalsEnabled);

                forecasterEngine = predFactory.getNewForecasterRun(this.fsm.getId(), 1);

                // 3. Connect them via a Listener (The "Nervous System")
                // Whenever the Detector processes an event, it notifies this listener.
                runEngine.register(new RunListener() {
                        @Override
                        public void newEventProcessed(RunMessage rm) {
                                // A. Pass the update to the Forecaster
                                forecasterEngine.newEventProcessed(rm);

                                RelativeForecast pred = forecasterEngine.getCurrentPrediction();

                                if (pred.isValid()) {
                                        PredictionOutput output = new PredictionOutput(
                                                rm.timestamp(),
                                                ctx.getCurrentKey(),
                                                pred.prob(),
                                                pred.startRelativeToNow(), // How far in the future starts
                                                pred.endRelativeToNow(),   // How far in the future ends
                                                pred.isPositive()          // Classification result
                                        );
                                        
                                        // Emit to Side Output
                                        ctx.output(PRED_TAG, output);
                                }

                                // B. Check if the pattern is complete (Detection)
                                if (rm.fmDetected()) {
                                        String msg = "Detected Pattern at " + rm.timestamp();
                                        ctx.output(MATCH_TAG, msg);
                                }
                        }

                        @Override
                        public void shutdown() {
                        }
                });

                // 4. Restore State (Recovery Mode)
                // If Flink has saved state (from a previous crash), inject it back into Wayeb.
                if (confState.value() != null) {
                        runEngine.restoreState(
                                        confState.value(),
                                        startedState.value(),
                                        bufferState.value(),
                                        matchState.value(),
                                        counterState.value());
                }
        }
}