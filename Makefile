# ==============================================================================
# RTCEF: Real-Time Complex Event Forecasting
# Cross-Platform Makefile (Linux, macOS, WSL)
# ==============================================================================

# --- Project Configuration ---
PROJECT_NAME   := flink-rtcef
VERSION        := 1.0-SNAPSHOT

# --- Directories (Relative) ---
WAYEB_DIR      := Wayeb
JAVA_DIR       := java
DATA_DIR       := data

# --- Artifact Paths ---
# The Fat Jar used for Training (SBT Assembly)
WAYEB_JAR      := $(WAYEB_DIR)/cef/target/scala-2.12/wayeb-0.6.0-SNAPSHOT.jar
# The Flink Job Jar (Maven Package)
FLINK_JOB_JAR  := $(JAVA_DIR)/target/$(PROJECT_NAME)-$(VERSION).jar
# Class to run in Flink
JOB_CLASS      := edu.insa_lyon.streams.rtcef_flink.InferenceJob
FACTORY_CLASS  := edu.insa_lyon.streams.rtcef_flink.ModelFactoryJob

# --- Flink Configuration ---
FLINK_JM_URL   := localhost:8081

# --- Dataset Configuration ---
# Default dataset is 'maritime'. Override with 'make run DATASET=finance'
DATASET ?= maritime

ifeq ($(DATASET),finance)
    INPUT_PATH      := $(DATA_DIR)/finance-eval.jsonl
    ID_FIELD        := pan
    TIMESTAMP_FIELD := timestamp
    DOMAIN_TYPE     := json
else
    # Default to maritime
    INPUT_PATH      := $(DATA_DIR)/maritime-eval.jsonl
    ID_FIELD        := mmsi
    TIMESTAMP_FIELD := timestamp
    DOMAIN_TYPE     := json
endif

# --- Kafka Topics ---
INPUT_TOPIC := $(DATASET)_input

# --- Training Configuration ---
TRAIN_PATTERNS := $(WAYEB_DIR)/patterns/$(DATASET)/fraud/pattern.sre
TRAIN_DECLS    := $(WAYEB_DIR)/patterns/$(DATASET)/fraud/declarations.sre
ifeq ($(DATASET),maritime)
    TRAIN_PATTERNS := $(WAYEB_DIR)/patterns/maritime/port/pattern.sre
    TRAIN_DECLS    := $(WAYEB_DIR)/patterns/maritime/port/declarationsDistance1.sre
endif
MODEL_OUTPUT   := $(DATA_DIR)/saved_models/tmp.spst

# --- Flink Job Parameters ---
MODEL_PATH         ?= /opt/flink/data/saved_models/tmp.spst
HORIZON            ?= 600
THRESHOLD          ?= 0.3
MAX_SPREAD         ?= 5
REPORTING_DISTANCE := 6000

# --- Collector & Dataset Configuration ---
# Bucket Size in Seconds (86400 = 24h). 
# Adapted for 5-day test dataset to produce ~5 buckets.
# Original Prod: 604800 (7 days).
BUCKET_SIZE_SEC := 86400
# Usage History Keep (Last K Datasets).
# Original Prod: 4. Adjusted to 7 for faster rotation testing.
HISTORY_K       := 7
NAMING_PREFIX   := dt_bucket_

# Java Detection: Use JAVA_HOME if set, otherwise try 'java' from PATH
JAVA_BIN := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)

# ==============================================================================
# MAIN TARGETS
# ==============================================================================

.PHONY: help all start stop build run clean logs status check-deps

# Default target: Print help
help:
	@echo "================================================================"
	@echo " RTCEF Automation Tool"
	@echo "================================================================"
	@echo "Usage:"
	@echo "  make start       -> Start the Docker Flink Cluster"
	@echo "  make stop        -> Stop the Docker Flink Cluster"
	@echo "  make build       -> Build Wayeb (Scala) and Flink Job (Java)"
	@echo "  make train       -> Train the probabilistic model"
	@echo "  make run                -> Full Cycle: Build -> Train -> Submit ALL jobs"
	@echo "  make submit-inference   -> Submit InferenceJob only"
	@echo "  make submit-factory     -> Submit ModelFactoryJob only"
	@echo "  make submit-controller  -> Submit Python Controller only"
	@echo "  make run-feeder         -> Start Async Data Feeder (Infinite Loop)"
	@echo "  make init-kafka         -> Create Kafka topics"
	@echo "  make clean       -> Remove all build artifacts and temp files"
	@echo "  make logs        -> View TaskManager logs"
	@echo "  make status      -> Show running jobs"
	@echo "================================================================"

# Alias for building everything
all: build

# 1. Start Docker
start:
	@echo ">>> Starting Flink Cluster..."
	docker compose up -d
	@echo ">>> Waiting for JobManager at $(FLINK_JM_URL)..."
	@until curl -s $(FLINK_JM_URL)/overview > /dev/null; do sleep 2; echo "   Waiting..."; done
	@echo ">>> Cluster is ready!"

# 2. Stop Docker
stop:
	@echo ">>> Stopping Flink Cluster..."
	docker compose down
	@echo ">>> Cleaning runtime artifacts (First-time scenario enforcement)..."
	rm -rf $(DATA_DIR)/saved_models/*
	rm -rf $(DATA_DIR)/buckets/*
	rm -rf $(DATA_DIR)/datasets/*
	rm -rf $(DATA_DIR)/saved_datasets/*
	rm -f $(DATA_DIR)/infer.csv
	rm -f $(DATA_DIR)/train.csv
	rm -f $(DATA_DIR)/infer.jsonl
	rm -f $(DATA_DIR)/train.jsonl

# 3. Build Libraries & Job
build: check-deps build-wayeb build-flink

# 4. Full Cycle (The "Magic Button")
# 4. Full Cycle (The "Magic Button")
run: build train run-all-jobs
# This runs the complete pipeline:
# 1. build: Compiles Wayeb (Scala) and Flink Jobs (Java)
# 2. train: Bootstraps the VMM model using the training slice of the JSONL dataset
# 3. run-all-jobs: Inits Kafka, splits data, and submits all processing jobs

run-all-jobs: init-kafka prepare-data
	@echo ">>> Submitting ALL jobs to Flink..."
	@$(MAKE) submit-inference
	@sleep 2
	@$(MAKE) submit-factory
	@sleep 2
	@$(MAKE) submit-controller
	@echo ">>> Waiting 5s before starting Data Feeder..."
	@sleep 5
	@$(MAKE) run-feeder
	@echo "\n>>> All jobs submitted! Use 'make status' to verify."

# ==============================================================================
# SUB-TASKS
# ==============================================================================

# --- Check Dependencies ---
check-deps:
	@echo ">>> Checking Prerequisites..."
	@which docker > /dev/null || (echo "Error: Docker is not installed."; exit 1)
	@which mvn > /dev/null    || (echo "Error: Maven is not installed."; exit 1)
	@which sbt > /dev/null    || (echo "Error: SBT is not installed."; exit 1)
	@echo ">>> Checking Java Version (Needs Java 11)..."
	@$(JAVA_BIN) -version 2>&1 | grep "version"
	@echo "Dependencies look good."

# --- Build Wayeb (Scala) ---
build-wayeb:
	@echo ">>> [1/2] Building Wayeb Library..."
	# We perform two steps:
	# 1. 'publishM2': Puts the library in ~/.m2 so the Flink/Maven project can find it to compile code.
	# 2. 'assembly': Creates the Fat Jar executable used to run the Training command.
	cd $(WAYEB_DIR) && sbt "project cef" clean publishM2 assembly

# --- Build Flink Job (Java) ---
build-flink:
	@echo ">>> [2/2] Building Flink Job..."
	cd $(JAVA_DIR) && mvn clean package -DskipTests

# --- Prepare Data (Split) ---
prepare-data:
	@echo ">>> Splitting Dataset..."
	@python3 python/split_dataset.py --file $(INPUT_PATH) --train-pct 0.20 --output-dir $(DATA_DIR)

# --- Train Model ---
# We bootstrap the initial model (tmp.spst) using the training slice.
# This allows the InferenceJob to start with a non-empty knowledge base.
train: prepare-data
	@echo ">>> Training Model (VMM) using $(DOMAIN_TYPE) on $(DATASET) dataset..."
	@if [ ! -f "$(WAYEB_JAR)" ]; then echo "Error: Wayeb Jar missing. Run 'make build' first."; exit 1; fi
	@mkdir -p $(dir $(MODEL_OUTPUT))
	
	@echo "   -> Running learnSPST (Structure + VMM Probabilities)..."
	$(JAVA_BIN) -jar $(WAYEB_JAR) learnSPST \
		--fsmModel:dsfa \
		--patterns:$(TRAIN_PATTERNS) \
		--declarations:$(TRAIN_DECLS) \
		--stream:$(DATA_DIR)/train.jsonl \
		--domainSpecificStream:$(DOMAIN_TYPE) \
		--outputSpst:$(MODEL_OUTPUT)
	
	@echo "Model saved to: $(MODEL_OUTPUT)"

# --- Submit to Flink ---
submit-inference:
	@echo ">>> Submitting InferenceJob to Flink..."
	@if [ ! -f "$(FLINK_JOB_JAR)" ]; then echo "Error: Job Jar missing. Run 'make build' first."; exit 1; fi
	
	@# Upload Jar
	@RESPONSE=$$(curl -s -X POST -H "Expect:" -F "jarfile=@$(FLINK_JOB_JAR)" http://$(FLINK_JM_URL)/jars/upload); \
	JAR_ID=$$(echo $$RESPONSE | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | xargs basename); \
	echo "   -> Uploaded Jar ID: $$JAR_ID"; \
	echo "   -> Starting InferenceJob..."; \
	curl -X POST "http://$(FLINK_JM_URL)/jars/$$JAR_ID/run?entry-class=$(JOB_CLASS)&program-args=--modelPath+$(MODEL_PATH)+--inputTopic+$(INPUT_TOPIC)+--instructions-topic+observer_instructions+--model-reports-topic+model_reports+--datasets-topic+dataset_versions+--assembly-topic+assembly_reports+--horizon+$(HORIZON)+--threshold+$(THRESHOLD)+--maxSpread+$(MAX_SPREAD)+--inputSource+kafka+--observer-optimize-diff+0.001+--observer-grace+0+--reportingDistance+$(REPORTING_DISTANCE)+--bucketSize+$(BUCKET_SIZE_SEC)+--lastK+$(HISTORY_K)+--naming+$(NAMING_PREFIX)+--idField+$(ID_FIELD)+--timestampField+$(TIMESTAMP_FIELD)"
	
	@echo "\nInferenceJob Submitted!"

submit-factory:
	@echo ">>> Submitting ModelFactoryJob to Flink..."
	@if [ ! -f "$(FLINK_JOB_JAR)" ]; then echo "Error: Job Jar missing. Run 'make build' first."; exit 1; fi
	
	@RESPONSE=$$(curl -s -X POST -H "Expect:" -F "jarfile=@$(FLINK_JOB_JAR)" http://$(FLINK_JM_URL)/jars/upload); \
	JAR_ID=$$(echo $$RESPONSE | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | xargs basename); \
	echo "   -> Uploaded Jar ID: $$JAR_ID"; \
	echo "   -> Starting InferenceJob..."; \
	curl -X POST "http://$(FLINK_JM_URL)/jars/$$JAR_ID/run?entry-class=$(FACTORY_CLASS)&program-args=--kafka-servers+kafka:29092+--commands-topic+factory_commands+--datasets-topic+dataset_versions+--output-topic+model_reports+--assembly-topic+assembly_reports"
	
	@echo "\nModelFactoryJob Submitted!"

submit-controller:
	@echo ">>> Starting Python Controller..."
	@docker compose exec -d \
		-e INSTRUCTION_TOPIC=observer_instructions \
		-e REPORT_TOPIC=model_reports \
		-e COMMAND_TOPIC=factory_commands \
		-e SYNC_TOPIC=enginesync \
		jobmanager flink run -Dpipeline.jars="file:///opt/flink/lib/flink-connector-kafka-1.17.2.jar;file:///opt/flink/lib/kafka-clients-3.2.3.jar" -py /opt/flink/python_code/controller_job/main.py
	@echo "Controller Job Submitted!"

run-feeder:
	@echo ">>> Starting Async Data Feeder..."
	@docker compose exec -d jobmanager python3 /opt/flink/python_code/data_feeder.py --file /opt/flink/data/infer.jsonl --topic $(INPUT_TOPIC) --delay 0.1
	@echo "Data Feeder running in background!"

# --- Initialize Kafka Topics ---
init-kafka:
	@echo ">>> Creating Kafka Topics..."
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic observer_instructions --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic factory_commands --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic model_reports --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic enginesync --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic dataset_versions --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic assembly_reports --partitions 1 --replication-factor 1 || true
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --create --if-not-exists --topic $(INPUT_TOPIC) --partitions 1 --replication-factor 1 || true
	@echo ">>> Kafka Topics Ready!"
	@docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --list
	@echo "Waiting 5s for Kafka propagation..."
	@sleep 5


logs:
	docker compose logs -f taskmanager

logs-all:
	docker compose logs -f taskmanager jobmanager

watch-topics:
	@echo ">>> Watching Kafka topics (press Ctrl+C to stop)..."
	@docker compose exec kafka bash -c '\
		echo "=== Instructions (Observer -> Controller) ===" && \
		timeout 2 kafka-console-consumer --bootstrap-server kafka:29092 --topic observer_instructions --from-beginning 2>/dev/null || true; \
		echo "\n=== Factory Commands (Controller -> Factory) ===" && \
		timeout 2 kafka-console-consumer --bootstrap-server kafka:29092 --topic factory_commands --from-beginning 2>/dev/null || true; \
		echo "\n=== Helper: Model Reports ===" && \
		timeout 2 kafka-console-consumer --bootstrap-server kafka:29092 --topic model_reports --from-beginning 2>/dev/null || true; \
		echo "\n=== Done!"'

status:
	@curl -s http://$(FLINK_JM_URL)/jobs/overview | python3 -m json.tool

cancel-all:
	@echo ">>> Cancelling all running jobs..."
	@ids=$$(curl -s http://$(FLINK_JM_URL)/jobs/overview | grep -o '"jid":"[^"]*"' | cut -d'"' -f4); \
	for id in $$ids; do \
		echo "   Cancelling $$id"; \
		curl -X PATCH "http://$(FLINK_JM_URL)/jobs/$$id?mode=cancel"; \
	done

clean:
	@echo ">>> Cleaning all artifacts..."
	rm -rf $(DATA_DIR)/saved_models/*	
	rm -rf $(DATA_DIR)/buckets/*
	rm -rf $(DATA_DIR)/datasets/*	
	cd $(WAYEB_DIR) && sbt clean
	cd $(JAVA_DIR) && mvn clean