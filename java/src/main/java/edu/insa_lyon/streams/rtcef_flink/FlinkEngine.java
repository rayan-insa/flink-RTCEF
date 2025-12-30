package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

// Standard Java Imports
import java.util.List;

// Scala & Wayeb Imports
// Wayeb is written in Scala, so we need converters to use its collections in Java.
import scala.collection.JavaConverters;
import scala.Tuple5;
import scala.Enumeration;
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
import model.waitingTime.ForecastMethod; 

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
 * FlinkEngine acts as a "Bridge" or "Wrapper".
 * * Flink manages the distributed stream and fault tolerance.
 * Wayeb manages the complex event logic (Detection & Forecasting).
 * * This class runs on the Worker Nodes (TaskManagers).
 */
public class FlinkEngine extends KeyedProcessFunction<String, GenericEvent, String> {

    // =========================================================================
    // 1. Configuration (The Blueprint)
    // =========================================================================
    // These fields are simple Primitives/Strings. Flink serializes them on the 
    // Master Node (JobManager) and sends copies to all Worker Nodes.
    private final String modelPath;              // Path to the .spst model file on disk
    private final int horizon;                   // How far into the future we look (e.g. 30 steps)
    private final double runConfidenceThreshold; // Probability required to trigger an alert (0.0 to 1.0)
    private final int maxSpread;                 // Allowed variance in prediction
    private final boolean finalsEnabled = false;

    // =========================================================================
    // 2. Flink Persistent State (The Memory)
    // =========================================================================
    // Flink uses "State Handles" to save data to RocksDB or Memory.
    // If the machine crashes, Flink reloads these values to restore the job.
    private ValueState<fsm.symbolic.sra.Configuration> confState; // The current state of the automaton (e.g. State 3)
    private ValueState<Boolean> startedState;                     // Has the engine started processing?
    private ValueState<CyclicBuffer> bufferState;                 // Internal event buffer
    private ValueState<Match> matchState;                         // Partial matches found so far
    private ValueState<Long> counterState;                        // Event counter (logic clock)

    // =========================================================================
    // 3. Transient Objects (The Engines)
    // =========================================================================
    // "transient" means: DO NOT SERIALIZE. 
    // These complex objects (Wayeb Engines) cannot be sent over the network.
    // Instead, we build them from scratch on every Worker using the open() method.
    private transient FSMInterface fsm;              // The static definition of the Pattern (The Map)
    private transient ForecasterProvider predProvider; // Factory for creating forecasters
    private transient Run runEngine;                 // The Runtime Detector (Moves through the Map)
    private transient ForecasterRun forecasterEngine;// The Runtime Predictor (Calculates Probabilities)

    // Constructor: Only stores configuration strings. No heavy lifting here.
    public FlinkEngine(
        String modelPath, 
        int horizon, 
        double runConfidenceThreshold, 
        int maxSpread
    ) {
        this.modelPath = modelPath;
        this.horizon = horizon;
        this.runConfidenceThreshold = runConfidenceThreshold;
        this.maxSpread = maxSpread;
    }

    /**
     * open() is the "Boot Up" sequence for the Worker.
     * It runs once when the TaskManager starts.
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
        MarkovChainProvider markovChainProvider = MarkovChainProvider.apply(MCSourceSerialized.apply(modelPath + ".mc"));

        // Build the Waiting Time Provider (predicts WHEN something happens)
        WtProvider wtp = WtProvider.apply(WtSourceMatrix.apply(fsmProvider, markovChainProvider, horizon, finalsEnabled));

        // 3. Build the Forecaster Provider (Factory)
        Enumeration.Value methodEnum = ForecastMethod.CLASSIFY_NEXTK();

        this.predProvider = ForecasterProvider.apply(
            ForecasterSourceBuild.apply(
                fsmProvider,
                wtp,
                horizon,
                runConfidenceThreshold,
                maxSpread,
                methodEnum
            )
        );

        // --- B. Initialize State Handles ---
        // We tell Flink: "I need you to manage these specific variables for me."
        confState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("conf", fsm.symbolic.sra.Configuration.class)
        );
        startedState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("started", Boolean.class)
        );
        bufferState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("buffer", CyclicBuffer.class)
        );
        matchState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("match", Match.class)
        );
        counterState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("counter", Long.class)
        );
    }

    /**
     * processElement() is the Main Loop.
     * Flink calls this for EVERY single event in the stream.
     */
    @Override
    public void processElement(GenericEvent event, Context ctx, Collector<String> out) throws Exception {
        
        // 1. Engine Check: If the engines (Detector/Forecaster) don't exist yet, create them.
        if (runEngine == null) {
            initializeEngine(out);
        }
        
        // 2. Run Detection Logic
        // We feed the event into Wayeb. It updates its internal state 
        // (e.g., moves from State A -> State B).
        runEngine.processEventDet(event);

        // Note: Forecasting happens automatically via the Listener we set up in initializeEngine()

        // 3. Checkpointing (Fault Tolerance)
        // We pull the state OUT of Wayeb and push it INTO Flink.
        // If the machine crashes after this line, Flink can restore Wayeb exactly as it was.
        Tuple5<fsm.symbolic.sra.Configuration, Object, CyclicBuffer, Match, Object> snapshot = runEngine.snapshotState();

        confState.update(snapshot._1());
        startedState.update((Boolean) snapshot._2());
        bufferState.update(snapshot._3());
        matchState.update(snapshot._4());
        counterState.update((Long) snapshot._5());
    }

    /**
     * Wiring the components together.
     */
    private void initializeEngine(Collector<String> out) throws Exception {
        // 1. Create the Detector (Run)
        // This tracks where we are in the pattern.
        runEngine = Run$.MODULE$.apply(1, this.fsm);

        // 2. Create the Predictor (Forecaster)
        // This calculates probabilities based on where the Detector is.
        scala.collection.immutable.List predList = this.predProvider.provide();
        ForecasterRunFactory predFactory = ForecasterRunFactory.apply(predList, false, finalsEnabled);
        forecasterEngine = predFactory.getNewForecasterRun(this.fsm.getId(), 1);

        // 3. Connect them via a Listener (The "Nervous System")
        // Whenever the Detector processes an event, it notifies this listener.
        runEngine.register(new RunListener() {
            @Override
            public void newEventProcessed(RunMessage rm) {
                // A. Pass the update to the Forecaster
                forecasterEngine.newEventProcessed(rm);
                
                // B. Check if the pattern is complete (Detection)
                if (rm.fmDetected()) {
                    out.collect("Detected Pattern at " + rm.timestamp());
                }
            }
            @Override
            public void shutdown() {}
        });

        // 4. Restore State (Recovery Mode)
        // If Flink has saved state (from a previous crash), inject it back into Wayeb.
        if (confState.value() != null) {
            runEngine.restoreState(
                confState.value(),
                startedState.value(),
                bufferState.value(),
                matchState.value(),
                counterState.value()
            );
        }
    }
}