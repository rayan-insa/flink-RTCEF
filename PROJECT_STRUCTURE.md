# Project Structure

```text
.
├── .gitignore
├── LICENSE
├── PROJECT_STRUCTURE.md
├── README.md
├── Wayeb
│   ├── .gitignore
│   ├── LICENSE.md
│   ├── README.md
│   ├── build.sbt
│   ├── cef
│   │   └── src
│   │       ├── main
│   │       │   ├── resources
│   │       │   │   ├── application.conf
│   │       │   │   └── logback.xml
│   │       │   └── scala
│   │       │       ├── db
│   │       │       │   ├── DBConnector.scala
│   │       │       │   ├── DetectionsTable.scala
│   │       │       │   └── ForecastsTable.scala
│   │       │       ├── engine
│   │       │       │   ├── ERFEngine.scala
│   │       │       │   └── ERFOptEngine.scala
│   │       │       ├── estimator
│   │       │       │   ├── HMMEstimator
│   │       │       │   │   ├── FSMStateEstimator.scala
│   │       │       │   │   ├── FSMStateRun.scala
│   │       │       │   │   └── IsoHMM.scala
│   │       │       │   ├── MatrixEstimator
│   │       │       │   │   ├── MLEEstimator.scala
│   │       │       │   │   └── MLERun.scala
│   │       │       │   ├── OrderEstimator
│   │       │       │   │   └── CrossValEstimator.scala
│   │       │       │   ├── RemainingTimeEstimator
│   │       │       │   │   ├── MeanEstimator.scala
│   │       │       │   │   ├── MeanRun.scala
│   │       │       │   │   └── RemainingTimes.scala
│   │       │       │   ├── RunEstimator.scala
│   │       │       │   └── RunEstimatorEngine.scala
│   │       │       ├── fsm
│   │       │       │   ├── CountPolicy.scala
│   │       │       │   ├── DFAInterface.scala
│   │       │       │   ├── DSRAInterface.scala
│   │       │       │   ├── FSMInterface.scala
│   │       │       │   ├── FSMModel.scala
│   │       │       │   ├── NSRAInterface.scala
│   │       │       │   ├── SDFAInterface.scala
│   │       │       │   ├── SNFAInterface.scala
│   │       │       │   ├── SPSAInterface.scala
│   │       │       │   ├── SPSTInterface.scala
│   │       │       │   ├── SPSTmInterface.scala
│   │       │       │   ├── WindowType.scala
│   │       │       │   ├── classical
│   │       │       │   │   ├── FATransition.scala
│   │       │       │   │   ├── fa
│   │       │       │   │   │   ├── dfa
│   │       │       │   │   │   │   ├── DFA.scala
│   │       │       │   │   │   │   ├── DFAFactory.scala
│   │       │       │   │   │   │   ├── DFAState.scala
│   │       │       │   │   │   │   ├── DFAUtils.scala
│   │       │       │   │   │   │   └── Disambiguator.scala
│   │       │       │   │   │   └── nfa
│   │       │       │   │   │       ├── Eliminator.scala
│   │       │       │   │   │       ├── NFA.scala
│   │       │       │   │   │       ├── NFAFactory.scala
│   │       │       │   │   │       ├── NFAState.scala
│   │       │       │   │   │       └── NFAUtils.scala
│   │       │       │   │   └── pattern
│   │       │       │   │       ├── archived
│   │       │       │   │       │   └── Reader.scala
│   │       │       │   │       └── regexp
│   │       │       │   │           ├── NodeType.scala
│   │       │       │   │           ├── OperatorNode.scala
│   │       │       │   │           ├── OperatorType.scala
│   │       │       │   │           ├── RegExpReader.scala
│   │       │       │   │           ├── RegExpTree.scala
│   │       │       │   │           ├── RegExpUtils.scala
│   │       │       │   │           ├── SymbolNode.scala
│   │       │       │   │           └── archived
│   │       │       │   │               ├── Node.scala
│   │       │       │   │               └── REReader.scala
│   │       │       │   ├── runtime
│   │       │       │   │   ├── Match.scala
│   │       │       │   │   ├── MatchDump.scala
│   │       │       │   │   ├── MatchList.scala
│   │       │       │   │   ├── MatchPool.scala
│   │       │       │   │   ├── MonoRunNSRA.scala
│   │       │       │   │   ├── MonoRunSNFA.scala
│   │       │       │   │   ├── Run.scala
│   │       │       │   │   ├── RunListener.scala
│   │       │       │   │   ├── RunMessage.scala
│   │       │       │   │   ├── RunPool.scala
│   │       │       │   │   ├── RunPrototype.scala
│   │       │       │   │   ├── RunRegistry.scala
│   │       │       │   │   └── RunSubPool.scala
│   │       │       │   └── symbolic
│   │       │       │       ├── Automaton.scala
│   │       │       │       ├── AutomatonState.scala
│   │       │       │       ├── Constants.scala
│   │       │       │       ├── Eliminator.scala
│   │       │       │       ├── Guard.scala
│   │       │       │       ├── StateMapper.scala
│   │       │       │       ├── Transition.scala
│   │       │       │       ├── TransitionOutput.scala
│   │       │       │       ├── Valuation.scala
│   │       │       │       ├── logic
│   │       │       │       │   ├── Assignment.scala
│   │       │       │       │   ├── AssignmentProducer.scala
│   │       │       │       │   ├── AtomicSentence.scala
│   │       │       │       │   ├── BooleanPermutator.scala
│   │       │       │       │   ├── ComplexSentence.scala
│   │       │       │       │   ├── EpsilonSentence.scala
│   │       │       │       │   ├── IsEventTypeSentence.scala
│   │       │       │       │   ├── LogicUtils.scala
│   │       │       │       │   ├── Predicate.scala
│   │       │       │       │   ├── PredicateConstructor.scala
│   │       │       │       │   ├── Sentence.scala
│   │       │       │       │   ├── SentenceConstructor.scala
│   │       │       │       │   ├── TrueSentence.scala
│   │       │       │       │   ├── TruthTable.scala
│   │       │       │       │   └── predicates
│   │       │       │       │       ├── BT.scala
│   │       │       │       │       ├── DistanceBetweenPredicate.scala
│   │       │       │       │       ├── EQ.scala
│   │       │       │       │       ├── EQAttr.scala
│   │       │       │       │       ├── EQAttrStr.scala
│   │       │       │       │       ├── EQStr.scala
│   │       │       │       │       ├── EpsilonPredicate.scala
│   │       │       │       │       ├── GT.scala
│   │       │       │       │       ├── GTAttr.scala
│   │       │       │       │       ├── GTE.scala
│   │       │       │       │       ├── HeadingTowardsPredicate.scala
│   │       │       │       │       ├── IsEventTypePredicate.scala
│   │       │       │       │       ├── LT.scala
│   │       │       │       │       ├── LTAttr.scala
│   │       │       │       │       ├── LTE.scala
│   │       │       │       │       ├── OutsideCirclePredicate.scala
│   │       │       │       │       ├── TruePredicate.scala
│   │       │       │       │       └── WithinCirclePredicate.scala
│   │       │       │       ├── sfa
│   │       │       │       │   ├── Constants.scala
│   │       │       │       │   ├── Determinizer.scala
│   │       │       │       │   ├── DeterminizerIncr.scala
│   │       │       │       │   ├── IdGenerator.scala
│   │       │       │       │   ├── SFA.scala
│   │       │       │       │   ├── SFAGuard.scala
│   │       │       │       │   ├── SFAState.scala
│   │       │       │       │   ├── SFATransition.scala
│   │       │       │       │   ├── SFAUtils.scala
│   │       │       │       │   ├── sdfa
│   │       │       │       │   │   ├── Disambiguator.scala
│   │       │       │       │   │   ├── DisambiguatorMutant.scala
│   │       │       │       │   │   ├── SDFA.scala
│   │       │       │       │   │   ├── SDFAMutant.scala
│   │       │       │       │   │   ├── SDFAMutantGraph.scala
│   │       │       │       │   │   ├── SDFAState.scala
│   │       │       │       │   │   ├── SDFAStateMutant.scala
│   │       │       │       │   │   └── SDFAUtils.scala
│   │       │       │       │   └── snfa
│   │       │       │       │       ├── Eliminator.scala
│   │       │       │       │       ├── SNFA.scala
│   │       │       │       │       ├── SNFAMutantGraph.scala
│   │       │       │       │       ├── SNFAState.scala
│   │       │       │       │       ├── SNFAStateMutant.scala
│   │       │       │       │       └── SNFAUtils.scala
│   │       │       │       ├── sra
│   │       │       │       │   ├── Configuration.scala
│   │       │       │       │   ├── SRA.scala
│   │       │       │       │   ├── SRAGuard.scala
│   │       │       │       │   ├── SRAState.scala
│   │       │       │       │   ├── SRATransition.scala
│   │       │       │       │   ├── SRAUtils.scala
│   │       │       │       │   ├── dsra
│   │       │       │       │   │   ├── DSRA.scala
│   │       │       │       │   │   ├── DSRAState.scala
│   │       │       │       │   │   ├── DSRAStreaming.scala
│   │       │       │       │   │   ├── DSRASymbolized.scala
│   │       │       │       │   │   └── DSRAUtils.scala
│   │       │       │       │   └── nsra
│   │       │       │       │       ├── NSRA.scala
│   │       │       │       │       ├── NSRAState.scala
│   │       │       │       │       ├── NSRAUtils.scala
│   │       │       │       │       └── Tracker.scala
│   │       │       │       └── sre
│   │       │       │           ├── BooleanOperator.scala
│   │       │       │           ├── Declaration.scala
│   │       │       │           ├── DeclarationsParser.scala
│   │       │       │           ├── LogicSentence.scala
│   │       │       │           ├── RegularOperator.scala
│   │       │       │           ├── SREFormula.scala
│   │       │       │           ├── SREParser.scala
│   │       │       │           ├── SREUtils.scala
│   │       │       │           ├── SelectionStrategy.scala
│   │       │       │           └── SelectionUtils.scala
│   │       │       ├── model
│   │       │       │   ├── ProbModel.scala
│   │       │       │   ├── forecaster
│   │       │       │   │   ├── ForecasterInterface.scala
│   │       │       │   │   ├── ForecasterType.scala
│   │       │       │   │   ├── HMMInterface.scala
│   │       │       │   │   ├── NextInterface.scala
│   │       │       │   │   ├── RandomInterface.scala
│   │       │       │   │   ├── WtInterface.scala
│   │       │       │   │   ├── next
│   │       │       │   │   │   ├── NextForecaster.scala
│   │       │       │   │   │   └── NextForecasterBuilder.scala
│   │       │       │   │   ├── random
│   │       │       │   │   │   └── RandomForecaster.scala
│   │       │       │   │   ├── runtime
│   │       │       │   │   │   ├── Forecast.scala
│   │       │       │   │   │   ├── ForecasterPrototype.scala
│   │       │       │   │   │   ├── ForecasterRegistry.scala
│   │       │       │   │   │   ├── ForecasterRun.scala
│   │       │       │   │   │   ├── ForecasterRunFactory.scala
│   │       │       │   │   │   └── RelativeForecast.scala
│   │       │       │   │   └── wt
│   │       │       │   │       ├── WtForecaster.scala
│   │       │       │   │       └── WtForecasterBuilder.scala
│   │       │       │   ├── markov
│   │       │       │   │   ├── MarkovChain.scala
│   │       │       │   │   ├── MarkovChainFactory.scala
│   │       │       │   │   └── TransitionProbs.scala
│   │       │       │   ├── vmm
│   │       │       │   │   ├── Symbol.scala
│   │       │       │   │   ├── SymbolWord.scala
│   │       │       │   │   ├── VMMUtils.scala
│   │       │       │   │   ├── mapper
│   │       │       │   │   │   ├── Isomorphism.scala
│   │       │       │   │   │   ├── SymExBank.scala
│   │       │       │   │   │   ├── SymbolExtractorFromDSRA.scala
│   │       │       │   │   │   └── SymbolMapper.scala
│   │       │       │   │   └── pst
│   │       │       │   │       ├── BufferBank.scala
│   │       │       │   │       ├── CSTLearner.scala
│   │       │       │   │       ├── CounterSuffixTree.scala
│   │       │       │   │       ├── CyclicBuffer.scala
│   │       │       │   │       ├── PSTLearner.scala
│   │       │       │   │       ├── PredictionSuffixTree.scala
│   │       │       │   │       ├── SymbolDistribution.scala
│   │       │       │   │       ├── psa
│   │       │       │   │       │   ├── PSAMatrix.scala
│   │       │       │   │       │   ├── PSAState.scala
│   │       │       │   │       │   ├── PSATransition.scala
│   │       │       │   │       │   ├── PSAUtils.scala
│   │       │       │   │       │   └── ProbSuffixAutomaton.scala
│   │       │       │   │       └── spsa
│   │       │       │   │           ├── SPSAState.scala
│   │       │       │   │           ├── SPSATransition.scala
│   │       │       │   │           ├── SPSAUtils.scala
│   │       │       │   │           └── SymbolicPSA.scala
│   │       │       │   └── waitingTime
│   │       │       │       ├── ForecastMethod.scala
│   │       │       │       └── WtDistribution.scala
│   │       │       ├── profiler
│   │       │       │   ├── ForecastCollector.scala
│   │       │       │   ├── LogLossProfiler.scala
│   │       │       │   ├── NextProfiler.scala
│   │       │       │   ├── ProfilerInterface.scala
│   │       │       │   ├── StatsEstimator.scala
│   │       │       │   ├── WtProfiler.scala
│   │       │       │   ├── classification
│   │       │       │   │   ├── ClassificationForecastCollector.scala
│   │       │       │   │   └── ClassificationStatsEstimator.scala
│   │       │       │   └── regression
│   │       │       │       ├── RegressionForecastCollector.scala
│   │       │       │       └── RegressionStatsEstimator.scala
│   │       │       ├── stream
│   │       │       │   ├── GenericEvent.scala
│   │       │       │   ├── ResetEvent.scala
│   │       │       │   ├── StreamFactory.scala
│   │       │       │   ├── array
│   │       │       │   │   ├── EventStream.scala
│   │       │       │   │   ├── EventStreamI.scala
│   │       │       │   │   ├── ListStream.scala
│   │       │       │   │   ├── PSAStream.scala
│   │       │       │   │   ├── ProbMapStream.scala
│   │       │       │   │   ├── TransProbStream.scala
│   │       │       │   │   ├── XMLParser.scala
│   │       │       │   │   ├── XMLStream.scala
│   │       │       │   │   └── archived
│   │       │       │   │       ├── CSVStream.scala
│   │       │       │   │       └── Generator.scala
│   │       │       │   ├── domain
│   │       │       │   │   ├── homes
│   │       │       │   │   │   └── HomesLineParser.scala
│   │       │       │   │   ├── maritime
│   │       │       │   │   │   └── MaritimeLineParser.scala
│   │       │       │   │   ├── stock
│   │       │       │   │   │   └── StockLineParser.scala
│   │       │       │   │   └── taxi
│   │       │       │   │       └── TaxiLineParser.scala
│   │       │       │   └── source
│   │       │       │       ├── ArrayStreamSource.scala
│   │       │       │       ├── CSVStreamSource.scala
│   │       │       │       ├── EmitMode.scala
│   │       │       │       ├── EndOfStreamEvent.scala
│   │       │       │       ├── GenericCSVLineParser.scala
│   │       │       │       ├── JsonFileStreamSource.scala
│   │       │       │       ├── JsonLineParser.scala
│   │       │       │       ├── KafkaStreamSource.scala
│   │       │       │       ├── LineParser.scala
│   │       │       │       ├── StreamListener.scala
│   │       │       │       └── StreamSource.scala
│   │       │       ├── ui
│   │       │       │   ├── BeepBeep.scala
│   │       │       │   ├── ConfigUtils.scala
│   │       │       │   ├── WayebCLI.scala
│   │       │       │   └── demo
│   │       │       │       └── RunSrc.scala
│   │       │       ├── utils
│   │       │       │   ├── MathUtils.scala
│   │       │       │   ├── MiscUtils.scala
│   │       │       │   ├── Progressor.scala
│   │       │       │   ├── SerializationUtils.scala
│   │       │       │   ├── SetUtils.scala
│   │       │       │   ├── Shutdownable.scala
│   │       │       │   ├── SpatialUtils.scala
│   │       │       │   ├── StringUtils.scala
│   │       │       │   ├── structures
│   │       │       │   │   └── CyclicBuffer.scala
│   │       │       │   └── testing
│   │       │       │       ├── PatternGenerator.scala
│   │       │       │       └── SymbolWordGenerator.scala
│   │       │       └── workflow
│   │       │           ├── condition
│   │       │           │   ├── Condition.scala
│   │       │           │   └── FileExistsCondition.scala
│   │       │           ├── provider
│   │       │           │   ├── AbstractProvider.scala
│   │       │           │   ├── DFAProvider.scala
│   │       │           │   ├── DSRAProvider.scala
│   │       │           │   ├── FSMProvider.scala
│   │       │           │   ├── ForecasterProvider.scala
│   │       │           │   ├── HMMProvider.scala
│   │       │           │   ├── MarkovChainProvider.scala
│   │       │           │   ├── NSRAProvider.scala
│   │       │           │   ├── OrderProvider.scala
│   │       │           │   ├── PSAProvider.scala
│   │       │           │   ├── PSTProvider.scala
│   │       │           │   ├── RemainingTimesProvider.scala
│   │       │           │   ├── SDFAProvider.scala
│   │       │           │   ├── SNFAProvider.scala
│   │       │           │   ├── SPSAProvider.scala
│   │       │           │   ├── SPSTProvider.scala
│   │       │           │   ├── SPSTmProvider.scala
│   │       │           │   ├── WtProvider.scala
│   │       │           │   └── source
│   │       │           │       ├── dfa
│   │       │           │       │   ├── DFASource.scala
│   │       │           │       │   ├── DFASourceDirect.scala
│   │       │           │       │   ├── DFASourceFromSDFA.scala
│   │       │           │       │   ├── DFASourceFromXML.scala
│   │       │           │       │   ├── DFASourceRegExp.scala
│   │       │           │       │   └── DFASourceSerialized.scala
│   │       │           │       ├── dsra
│   │       │           │       │   ├── DSRASource.scala
│   │       │           │       │   ├── DSRASourceDirect.scala
│   │       │           │       │   ├── DSRASourceDirectI.scala
│   │       │           │       │   ├── DSRASourceFromSREM.scala
│   │       │           │       │   ├── DSRASourceRegExp.scala
│   │       │           │       │   └── DSRASourceSerialized.scala
│   │       │           │       ├── forecaster
│   │       │           │       │   ├── ForecasterHMMSourceBuild.scala
│   │       │           │       │   ├── ForecasterNextSourceBuild.scala
│   │       │           │       │   ├── ForecasterSource.scala
│   │       │           │       │   ├── ForecasterSourceBuild.scala
│   │       │           │       │   ├── ForecasterSourceDirect.scala
│   │       │           │       │   ├── ForecasterSourceRandom.scala
│   │       │           │       │   └── ForecasterSourceSerialized.scala
│   │       │           │       ├── hmm
│   │       │           │       │   ├── HMMSource.scala
│   │       │           │       │   ├── HMMSourceDirect.scala
│   │       │           │       │   └── HMMSourceEstimator.scala
│   │       │           │       ├── matrix
│   │       │           │       │   ├── MCSourceDirect.scala
│   │       │           │       │   ├── MCSourceMLE.scala
│   │       │           │       │   ├── MCSourceProbs.scala
│   │       │           │       │   ├── MCSourceSPSA.scala
│   │       │           │       │   ├── MCSourceSerialized.scala
│   │       │           │       │   └── MatrixSource.scala
│   │       │           │       ├── nsra
│   │       │           │       │   ├── NSRASource.scala
│   │       │           │       │   ├── NSRASourceFromSREM.scala
│   │       │           │       │   ├── NSRASourceRegExp.scala
│   │       │           │       │   └── NSRASourceSerialized.scala
│   │       │           │       ├── order
│   │       │           │       │   ├── OrderSource.scala
│   │       │           │       │   ├── OrderSourceCrossVal.scala
│   │       │           │       │   └── OrderSourceDirect.scala
│   │       │           │       ├── psa
│   │       │           │       │   ├── PSASource.scala
│   │       │           │       │   ├── PSASourceDirect.scala
│   │       │           │       │   ├── PSASourceLearner.scala
│   │       │           │       │   └── PSASourceSerialized.scala
│   │       │           │       ├── pst
│   │       │           │       │   ├── PSTSource.scala
│   │       │           │       │   ├── PSTSourceCST.scala
│   │       │           │       │   ├── PSTSourceDirect.scala
│   │       │           │       │   ├── PSTSourceLearnerFromDSRA.scala
│   │       │           │       │   └── PSTSourceLearnerFromSDFA.scala
│   │       │           │       ├── rt
│   │       │           │       │   ├── RTSource.scala
│   │       │           │       │   ├── RTSourceDirect.scala
│   │       │           │       │   └── RTSourceEstimator.scala
│   │       │           │       ├── sdfa
│   │       │           │       │   ├── SDFASource.scala
│   │       │           │       │   ├── SDFASourceDFA.scala
│   │       │           │       │   ├── SDFASourceDirect.scala
│   │       │           │       │   ├── SDFASourceDirectI.scala
│   │       │           │       │   ├── SDFASourceFormula.scala
│   │       │           │       │   ├── SDFASourceFromSRE.scala
│   │       │           │       │   ├── SDFASourceLLDFA.scala
│   │       │           │       │   ├── SDFASourceRegExp.scala
│   │       │           │       │   └── SDFASourceSerialized.scala
│   │       │           │       ├── snfa
│   │       │           │       │   ├── SNFASource.scala
│   │       │           │       │   ├── SNFASourceFromSRE.scala
│   │       │           │       │   ├── SNFASourceRegExp.scala
│   │       │           │       │   └── SNFASourceSerialized.scala
│   │       │           │       ├── spsa
│   │       │           │       │   ├── SPSASource.scala
│   │       │           │       │   ├── SPSASourceDirect.scala
│   │       │           │       │   ├── SPSASourceDirectI.scala
│   │       │           │       │   ├── SPSASourceFromSRE.scala
│   │       │           │       │   ├── SPSASourcePSASerialized.scala
│   │       │           │       │   └── SPSASourceSerialized.scala
│   │       │           │       ├── spst
│   │       │           │       │   ├── SPSTSource.scala
│   │       │           │       │   ├── SPSTSourceDirectI.scala
│   │       │           │       │   ├── SPSTSourceFromSDFA.scala
│   │       │           │       │   ├── SPSTSourceFromSRE.scala
│   │       │           │       │   └── SPSTSourceSerialized.scala
│   │       │           │       ├── spstm
│   │       │           │       │   ├── SPSTmSource.scala
│   │       │           │       │   ├── SPSTmSourceDirectI.scala
│   │       │           │       │   ├── SPSTmSourceFromDSRA.scala
│   │       │           │       │   ├── SPSTmSourceFromSREM.scala
│   │       │           │       │   └── SPSTmSourceSerialized.scala
│   │       │           │       └── wt
│   │       │           │           ├── WtSource.scala
│   │       │           │           ├── WtSourceDirect.scala
│   │       │           │           ├── WtSourceMatrix.scala
│   │       │           │           ├── WtSourceRT.scala
│   │       │           │           ├── WtSourceSPST.scala
│   │       │           │           └── WtSourceSPSTm.scala
│   │       │           └── task
│   │       │               ├── Task.scala
│   │       │               ├── engineTask
│   │       │               │   ├── ERFOptTask.scala
│   │       │               │   └── ERFTask.scala
│   │       │               ├── estimatorTask
│   │       │               │   ├── HMMTask.scala
│   │       │               │   ├── MatrixMLETask.scala
│   │       │               │   ├── MeanTask.scala
│   │       │               │   └── OrderCrossValTask.scala
│   │       │               ├── fsmTask
│   │       │               │   ├── DFATask.scala
│   │       │               │   ├── DSRATask.scala
│   │       │               │   ├── EmbedPSAinSDFATask.scala
│   │       │               │   ├── NSRATask.scala
│   │       │               │   ├── SDFATask.scala
│   │       │               │   ├── SNFATask.scala
│   │       │               │   ├── SPSATask.scala
│   │       │               │   ├── SPSTTask.scala
│   │       │               │   └── SPSTmTask.scala
│   │       │               └── predictorTask
│   │       │                   ├── HMMPredictorTask.scala
│   │       │                   ├── PredictorNextTask.scala
│   │       │                   ├── PredictorRandomTask.scala
│   │       │                   └── WtPredictorTask.scala
│   │       └── test
│   │           └── scala
│   │               └── Specs
│   │                   ├── classical
│   │                   │   ├── dfa
│   │                   │   │   └── DisSpec.scala
│   │                   │   └── nfa
│   │                   │       └── NFA2DFA.scala
│   │                   ├── engine
│   │                   │   ├── EngineSpec.scala
│   │                   │   └── SPSTvsSPSTm.scala
│   │                   ├── estimators
│   │                   │   └── Means.scala
│   │                   ├── misc
│   │                   │   └── PermutationsSpec.scala
│   │                   ├── model
│   │                   │   └── waitingTime
│   │                   │       └── WaitingTimeSpec.scala
│   │                   ├── selection
│   │                   │   ├── SkipTillAny.scala
│   │                   │   ├── SkipTillNext.scala
│   │                   │   └── TransformToStrict.scala
│   │                   ├── srem
│   │                   │   ├── NSRAeqDSRA.scala
│   │                   │   ├── NSRAeqElimNSRA.scala
│   │                   │   ├── NSRAeqSREM.scala
│   │                   │   ├── NSRAeqUnrolledNSRA.scala
│   │                   │   └── NSRAeqWDSRA.scala
│   │                   ├── symbolic
│   │                   │   ├── MinTerms.scala
│   │                   │   ├── engine
│   │                   │   │   ├── DFAeqSDFA.scala
│   │                   │   │   └── SNFAeqSDFA.scala
│   │                   │   ├── sdfa
│   │                   │   │   ├── SDFADis.scala
│   │                   │   │   ├── SDFADistances.scala
│   │                   │   │   ├── SDFAIncrDet.scala
│   │                   │   │   ├── SDFAMutant.scala
│   │                   │   │   └── SDFAMutantDis.scala
│   │                   │   └── snfa
│   │                   │       ├── NFAEqSNFA.scala
│   │                   │       ├── SNFAEqElimSNFA.scala
│   │                   │       ├── SNFAeqNEGSNFA.scala
│   │                   │       ├── SNFAeqSDFA.scala
│   │                   │       └── SNFAeqSRE.scala
│   │                   └── vmm
│   │                       ├── Buffer.scala
│   │                       ├── CST.scala
│   │                       ├── CompleteProperSuffixSet.scala
│   │                       ├── PSAGenerator.scala
│   │                       ├── PST.scala
│   │                       ├── PST2PSA.scala
│   │                       ├── SPSA.scala
│   │                       └── SPST.scala
│   ├── data
│   │   ├── demo
│   │   │   └── data.csv
│   │   └── maritime
│   │       └── 227592820.csv
│   ├── docs
│   │   ├── building.md
│   │   ├── ceffmm.md
│   │   ├── cefvmm.md
│   │   ├── cep.md
│   │   ├── example.md
│   │   ├── lang.md
│   │   ├── lib.md
│   │   ├── overview.md
│   │   ├── papers
│   │   │   ├── Wayeb-DEBS17.pdf
│   │   │   ├── Wayeb-LPAR18.pdf
│   │   │   ├── Wayeb_SREMO.pdf
│   │   │   ├── Wayeb_VLDBJ22.pdf
│   │   │   ├── Wayeb_VLDBJ22_extended.pdf
│   │   │   └── alevizos_thesis_final.pdf
│   │   ├── readme.md
│   │   ├── references.md
│   │   └── streams.md
│   ├── kafkaConfigs
│   │   └── kafkaEarliest.properties
│   ├── misc
│   │   └── wayeb_glyph.png
│   ├── patterns
│   │   ├── demo
│   │   │   ├── a_seq_b.sre
│   │   │   ├── a_seq_b_or_c.sre
│   │   │   └── declarations.sre
│   │   ├── homes
│   │   │   └── reg1.sre
│   │   ├── maritime
│   │   │   └── port
│   │   │       ├── declarationsDistance1.sre
│   │   │       ├── pattern.sre
│   │   │       └── patternRel.sre
│   │   ├── stock
│   │   │   └── reg1.sre
│   │   ├── taxi
│   │   │   └── reg1.sre
│   │   └── validation
│   │       ├── pattern2.sre
│   │       ├── pattern3.sre
│   │       └── pattern4.sre
│   ├── project
│   │   ├── Build.scala
│   │   ├── Dependencies.scala
│   │   ├── build.properties
│   │   ├── plugins.sbt
│   │   └── project
│   ├── results
│   │   └── .keep
│   ├── scripts
│   │   └── wayeb_smoke.sh
│   ├── sim
│   │   └── src
│   │       └── main
│   │           └── scala
│   │               └── StreamSimulator.scala
│   ├── version.sbt
│   └── wayeb.log
├── data
│   └── input.txt
├── docs
│   └── original-paper
│       ├── RTCEF-paper.pdf
│       └── links.md
├── java
│   ├── pom.xml
│   └── src
│       ├── main
│       │   └── java
│       │       └── edu
│       │           └── insa_lyon
│       │               └── streams
│       │                   └── rtcef_flink
│       │                       ├── App.java
│       │                       └── FilePrintJob.java
│       └── test
│           └── java
│               └── edu
│                   └── insa_lyon
│                       └── streams
│                           └── rtcef_flink
│                               └── AppTest.java
└── python
    ├── hello_world.py
    └── requirements.txt
```
