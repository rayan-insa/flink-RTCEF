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
JOB_CLASS      := edu.insa_lyon.streams.rtcef_flink.FlinkWayebJob

# --- Flink Configuration ---
FLINK_JM_URL   := localhost:8081

# --- Training Configuration ---
TRAIN_PATTERNS := $(WAYEB_DIR)/patterns/maritime/port/pattern.sre
TRAIN_DECLS    := $(WAYEB_DIR)/patterns/maritime/port/declarationsDistance1.sre
TRAIN_STREAM   := $(WAYEB_DIR)/data/maritime/227592820.csv
MODEL_OUTPUT   := $(DATA_DIR)/saved_models/tmp.spst

# --- Flink Job Parameters ---
# Default parameters (can be overridden from command line like: make submit THRESHOLD=0.5)
MODEL_PATH ?= /opt/flink/data/saved_models/tmp.spst
INPUT_PATH ?= /opt/flink/data/maritime.csv
HORIZON ?= 30
THRESHOLD ?= 0.3
MAX_SPREAD ?= 5

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
	@echo "  make submit      -> Submit the job to Flink"
	@echo "  make run         -> Full Cycle: Build -> Train -> Submit"
	@echo "  make clean       -> Remove all build artifacts and temp files"
	@echo "  make logs        -> View TaskManager logs"
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

# 3. Build Libraries & Job
build: check-deps build-wayeb build-flink

# 4. Full Cycle (The "Magic Button")
run: build train submit

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

# --- Train Model ---
train:
	@echo ">>> Training Model..."
	@if [ ! -f "$(WAYEB_JAR)" ]; then echo "Error: Wayeb Jar missing. Run 'make build' first."; exit 1; fi
	@mkdir -p $(dir $(MODEL_OUTPUT))
	
	@echo "   -> Step A: Compiling Pattern to FSM..."
	$(JAVA_BIN) -jar $(WAYEB_JAR) compile \
		--fsmModel:dsfa \
		--patterns:$(TRAIN_PATTERNS) \
		--declarations:$(TRAIN_DECLS) \
		--outputFsm:$(MODEL_OUTPUT)

	@echo "   -> Step B: Learning Probabilities (MLE)..."
	$(JAVA_BIN) -jar $(WAYEB_JAR) mle \
		--fsm:$(MODEL_OUTPUT) \
		--stream:$(TRAIN_STREAM) \
		--domainSpecificStream:maritime \
		--streamArgs: \
		--outputMc:$(MODEL_OUTPUT).mc
	
	@echo "Model saved to: $(MODEL_OUTPUT).mc"

# --- Submit to Flink ---
submit:
	@echo ">>> Submitting Job to Flink..."
	@if [ ! -f "$(FLINK_JOB_JAR)" ]; then echo "Error: Job Jar missing. Run 'make build' first."; exit 1; fi
	
	@# Upload Jar
	@RESPONSE=$$(curl -s -X POST -H "Expect:" -F "jarfile=@$(FLINK_JOB_JAR)" http://$(FLINK_JM_URL)/jars/upload); \
	JAR_ID=$$(echo $$RESPONSE | grep -o '"filename":"[^"]*"' | cut -d'"' -f4 | xargs basename); \
	echo "   -> Uploaded Jar ID: $$JAR_ID"; \
	echo "   -> Starting Job..."; \
	curl -X POST "http://$(FLINK_JM_URL)/jars/$$JAR_ID/run?entry-class=$(JOB_CLASS)&program-args=--modelPath+$(MODEL_PATH)+--inputPath+$(INPUT_PATH)+--horizon+$(HORIZON)+--threshold+$(THRESHOLD)+--maxSpread+$(MAX_SPREAD)"
	
	@echo "\nJob Submitted Successfully!"

# ==============================================================================
# UTILITIES
# ==============================================================================

logs:
	docker compose logs -f taskmanager

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
	cd $(WAYEB_DIR) && sbt clean
	cd $(JAVA_DIR) && mvn clean