# ==============================================================================
# RTCEF Makefile
# ==============================================================================
# Managing the dual-stack: Java 8 (Wayeb/Scala) / Java 17 (Flink)
# ==============================================================================

# Variables
WAYEB_DIR := Wayeb
JAVA_DIR := java
WORK_DIR := .work
WAYEB_JAR := $(WAYEB_DIR)/cef/target/scala-2.12/wayeb-0.6.0-SNAPSHOT.jar

# Explicit JDK Paths detected on system
# Users can override these by setting JAVA8_HOME or JAVA17_HOME in their shell
JAVA8_HOME ?= $(shell /usr/libexec/java_home -v 1.8 2>/dev/null)
JAVA17_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null)

# Wrapper to run commands with a specific JAVA_HOME
# If detection fails, we rely on the user having set the variable or the tool being in PATH
RUN_WITH_JAVA8 := $(if $(JAVA8_HOME),export JAVA_HOME="$(JAVA8_HOME)" && export PATH="$(JAVA8_HOME)/bin:$$PATH",@echo "Error: Java 8 not found. Set JAVA8_HOME." && exit 1)
RUN_WITH_JAVA17 := $(if $(JAVA17_HOME),export JAVA_HOME="$(JAVA17_HOME)" && export PATH="$(JAVA17_HOME)/bin:$$PATH",@echo "Error: Java 17 not found. Set JAVA17_HOME." && exit 1)

.PHONY: all build clean check-env smoke wayeb-jar flink-jar

all: build

# ------------------------------------------------------------------------------
# Environment & Setup
# ------------------------------------------------------------------------------
check-env:
	@echo "--- Checking Environment ---"
	@echo "Looking for Java 8 at: $(JAVA8_HOME)"
	@if [ -d "$(JAVA8_HOME)" ]; then echo "  [OK] Java 8 found"; else echo "  [ERR] Java 8 NOT found"; fi
	@echo "Looking for Java 17 at: $(JAVA17_HOME)"
	@if [ -d "$(JAVA17_HOME)" ]; then echo "  [OK] Java 17 found"; else echo "  [ERR] Java 17 NOT found"; fi

$(WORK_DIR):
	mkdir -p $(WORK_DIR)

# ------------------------------------------------------------------------------
# Building
# ------------------------------------------------------------------------------
build: wayeb-jar flink-jar

wayeb-jar:
	@echo "--- Building Wayeb (Scala/SBT) with Java 8 ---"
	@$(RUN_WITH_JAVA8) && java -version
	cd $(WAYEB_DIR) && $(RUN_WITH_JAVA8) && sbt clean assembly

flink-jar:
	@echo "--- Building Flink App (Maven) with Java 17 ---"
	@$(RUN_WITH_JAVA17) && java -version
	cd $(JAVA_DIR) && $(RUN_WITH_JAVA17) && mvn clean package -DskipTests

# ------------------------------------------------------------------------------
# Verification / Smoke Tests
# ------------------------------------------------------------------------------
# ------------------------------------------------------------------------------
# Verification / Smoke Tests
# ------------------------------------------------------------------------------
smoke: smoke-wayeb smoke-flink-hello smoke-flink-integration

# 1. smoke-wayeb: Validates Wayeb (standalone)
smoke-wayeb: $(WORK_DIR)
	@echo "\n=== [SMOKE] 1. Wayeb Standalone (Java 8) ==="
	@if [ ! -f $(WAYEB_JAR) ]; then echo "Error: Wayeb JAR not found. Run 'make wayeb-jar' first."; exit 1; fi
	# Prepare inputs
	echo "A,1" > $(WORK_DIR)/stream.csv
	echo "X,2" >> $(WORK_DIR)/stream.csv
	echo "B,3" >> $(WORK_DIR)/stream.csv
	echo "A,4" >> $(WORK_DIR)/stream.csv
	echo "B,5" >> $(WORK_DIR)/stream.csv
	# Compile Pattern
	@echo "-> Compiling Pattern..."
	$(RUN_WITH_JAVA8) && java -cp $(WAYEB_JAR) ui.WayebCLI compile \
		--patterns $(WAYEB_DIR)/patterns/demo/a_seq_b.sre \
		--outputFsm $(WORK_DIR)/pattern.fsm \
		--fsmModel dsfa \
		--countPolicy overlap > $(WORK_DIR)/compile.log 2>&1
	# Recognition
	@echo "-> Running Recognition..."
	$(RUN_WITH_JAVA8) && java -cp $(WAYEB_JAR) ui.WayebCLI recognition \
		--fsm $(WORK_DIR)/pattern.fsm \
		--stream $(WORK_DIR)/stream.csv \
		--fsmModel dsfa \
		--statsFile $(WORK_DIR)/stats > $(WORK_DIR)/run.log 2>&1
	# Check Output
	@echo "-> Wayeb Stats Output:"
	@cat $(WORK_DIR)/stats.csv
	@echo "   (Expected roughly: 5,16128918,1,1,0)"

# 2. smoke-flink-hello: Validates Flink App (Java 17) execution
smoke-flink-hello:
	@echo "\n=== [SMOKE] 2. Flink Hello World (Java 17) ==="
	$(RUN_WITH_JAVA17) && java -cp $(JAVA_DIR)/target/java-1.0-SNAPSHOT.jar edu.insa_lyon.streams.rtcef_flink.FlinkHelloWorldJob

# 3. smoke-flink-integration: Validates Flink calling Wayeb
smoke-flink-integration:
	@echo "\n=== [SMOKE] 3. Flink <-> Wayeb Integration (Java 17 calling Java 8) ==="
	@# We pass the paths: <Java8Home> <WayebJar> <Fsm> <Stream> <StatsPrefix>
	$(RUN_WITH_JAVA17) && java -cp $(JAVA_DIR)/target/java-1.0-SNAPSHOT.jar edu.insa_lyon.streams.rtcef_flink.FlinkWayebIntegrationJob \
		"$(JAVA8_HOME)" \
		"$(PWD)/$(WAYEB_JAR)" \
		"$(PWD)/$(WORK_DIR)/pattern.fsm" \
		"$(PWD)/$(WORK_DIR)/stream.csv" \
		"$(PWD)/$(WORK_DIR)/stats_integrated"

# ------------------------------------------------------------------------------
# Cleanup
# ------------------------------------------------------------------------------
clean:
	@echo "--- Cleaning ---"
	rm -rf $(WORK_DIR)
	cd $(WAYEB_DIR) && $(RUN_WITH_JAVA8) && sbt clean
	cd $(JAVA_DIR) && $(RUN_WITH_JAVA17) && mvn clean
