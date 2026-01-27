package ui

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import model.waitingTime.ForecastMethod
import stream.GenericEvent
import stream.array.EventStream
import stream.source.ArrayStreamSource
import workflow.task.fsmTask.SPSTTask
import workflow.provider.{SDFAProvider, FSMProvider, WtProvider, ForecasterProvider, SPSTProvider}
import workflow.provider.source.wt._
import workflow.provider.source.forecaster.ForecasterSourceBuild
import workflow.provider.source.spst.SPSTSourceSerialized
import fsm.{CountPolicy, FSMInterface}
import fsm.runtime.{Run, RunListener, RunMessage}
import model.forecaster.runtime.{ForecasterRun, ForecasterRunFactory}
import profiler.WtProfiler
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable.ArrayBuffer

object WayebAdapter extends LazyLogging {

  def createEvent(ts: Long, eventType: String, attributes: java.util.Map[String, Object]): GenericEvent = {
    // Convert Java Map to Scala Immutable Map[String, Any] or String
    // GenericEvent expects Map[String, Any]
    val scalaAttrs = attributes.asScala.toMap
    // Constructor: id, type, timestamp, attrs
    new GenericEvent(0, eventType, ts, scalaAttrs)
  }

  /**
   * TRAIN (In-Memory)
   * Converts Java List[GenericEvent] to Scala ArrayStreamSource
   * Executes SPSTTask
   * Returns Serialized Model (byte[])
   */
  def trainInMemory(
      javaEvents: java.util.List[GenericEvent], 
      patternPath: String, 
      declarationsPath: String,
      pMin: Double, 
      gamma: Double, 
      alpha: Double, 
      r: Double
  ): Array[Byte] = {
    
    // 1. Convert Java List to Scala ArrayBuffer (Required by EventStream)
    val scalaEvents = ArrayBuffer(javaEvents.asScala: _*)
    
    // 2. Wrap in EventStream for Wayeb
    val eventStream = EventStream(scalaEvents)
    val streamSource = new ArrayStreamSource(eventStream)
    
    // 3. Configure SPSTTask
    // Using default CountPolicy.OVERLAP matching standard behavior
    val task = SPSTTask(
      patternPath, 
      declarationsPath, 
      CountPolicy.OVERLAP, 
      streamSource, 
      pMin, 
      alpha, 
      gamma, 
      r
    )
    
    // 4. Execute
    val spstModel = task.execute()
    
    // 5. Serialize
    val bao = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bao)
    oos.writeObject(spstModel)
    oos.close()
    
    bao.toByteArray
  }

  /**
   * TEST (In-Memory)
   * Mimics WayebEngine.java logic verbatim:
   * 1. Loads Providers (FSM, MC, Wt, Forecaster)
   * 2. Sets up Run and ForecasterRun
   * 3. Loops through events
   * 4. Returns MCC
   */
  def testInMemory(
      javaEvents: java.util.List[GenericEvent],
      modelPath: String,
      pMin: Double, 
      gamma: Double,
      alpha: Double,
      r: Double,
      horizon: Int,
      runConfidenceThreshold: Double,
      maxSpread: Int
  ): Double = {

    logger.info(s"WayebAdapter.testInMemory: Loading model from $modelPath (H=$horizon, T=$runConfidenceThreshold, S=$maxSpread)")

    // --- 1. Load Providers (Exactly as new WayebEngine.loadWayebModels) ---
    val spstSource = SPSTSourceSerialized(modelPath)
    val spstProvider = SPSTProvider(spstSource)
    val fsmProvider = FSMProvider(spstProvider)

    // Load FSM to get ID
    val fsmList = fsmProvider.provide()
    val fsmInterface = fsmList.head 

    // Load Waiting Time Provider
    val finalsEnabled = true
    val distance = (0.0, 1.0)
    val wtp = WtProvider(
      WtSourceSPST(
        spstProvider, 
        horizon, 
        0.001, // approximate cutoff
        distance
      )
    )

    // Load Forecaster Provider (CLASSIFY_NEXTK)
    val methodEnum = ForecastMethod.CLASSIFY_NEXTK

    val predProvider = ForecasterProvider(
      ForecasterSourceBuild(
        fsmProvider,
        wtp,
        horizon,
        runConfidenceThreshold,
        maxSpread,
        methodEnum
      )
    )

    // --- 2. Initialize Engines (Exactly as WayebEngine.initializeEngineInternal) ---
    val runEngine = Run(1, fsmInterface)
    
    val predList = predProvider.provide()
    val predFactory = ForecasterRunFactory(predList, true, finalsEnabled)
    val forecasterEngine = predFactory.getNewForecasterRun(fsmInterface.getId, 1)

    // Profiler to capture stats
    val profiler = new WtProfiler()

    // --- 3. Wire Listener (Exactly as WayebEngine wiring) ---
    runEngine.register(new RunListener {
      override def newEventProcessed(rm: RunMessage): Unit = {
        // Forward to Forecaster
        forecasterEngine.newEventProcessed(rm)
        
        // Note: In WayebEngine, we also emit to Flink Output here.
        // For Optimization, we just want stats, so we skip side-effects.
      }
      override def shutdown(): Unit = {}
    })

    // --- 4. Processing Loop (Exactly as WayebEngine.processElement) ---
    val scalaEvents = javaEvents.asScala
    scalaEvents.foreach { event =>
      runEngine.processEventDet(event)
    }

    // --- 5. Profile & Calculate MCC ---
    // In WayebEngine, profiler stats are updated via checkAndReportStats logic which accesses forecasterEngine.getCollector()
    // We replicate that manually here:
    
    val collector = forecasterEngine.getCollector
    // Profiler expects a map of collectors (Map[Int, List[ForecastCollector]])
    // Key 0 is used in single-pattern setups
    val collectorsMap = Map(0 -> List(collector))
    
    profiler.setGlobal(0, 0L, 0L, 0L, 0, 0, 0, null)
    profiler.createEstimators(collectorsMap)
    profiler.estimateStats()

    val mcc = profiler.getStatFor("mcc", 0).toDouble
    
    logger.info(s"WayebAdapter Test Results: MCC=$mcc")
    
    mcc
  }
}
