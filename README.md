# Flink-RTCEF

[![Apache Flink](https://img.shields.io/badge/Apache%20Flink-2.1+-E6526F?style=flat&logo=apache-flink&logoColor=white)](https://flink.apache.org/)
[![Python](https://img.shields.io/badge/Python-3.9+-3776AB?style=flat&logo=python&logoColor=white)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **Flink-based adaptation of the RTCEF (Run-Time Adaptation for Complex Event Forecasting) framework**

This project integrates the [RTCEF framework](https://github.com/manospits/rtcef) into [Apache Flink](https://flink.apache.org/), enabling scalable, distributed Complex Event Forecasting (CEF) with run-time model adaptation capabilities.

---

## 📖 Overview

### What is Complex Event Forecasting (CEF)?

Complex Event Forecasting (CEF) is the process of predicting complex events of interest over a stream of simple events. Unlike Complex Event Recognition (CER) which detects events reactively, CEF enables **proactive measures** by anticipating future occurrences with a degree of certainty.

**Real-world applications include:**

- 🚢 **Maritime Situational Awareness**: Forecasting vessel arrivals at ports for better resource management
- 💳 **Financial Fraud Detection**: Anticipating fraudulent transaction patterns before they complete
- 🏭 **Industrial IoT**: Predicting equipment failures or process anomalies

### The Challenge

CEF systems rely on probabilistic models trained on historical data. However, our world is constantly evolving:

- Maritime vessels adapt routes based on weather conditions
- Fraudsters evolve their tactics to avoid detection
- Operational patterns shift due to seasonal or external factors

This renders CEF systems **inherently susceptible to data evolutions** that can invalidate their underlying models.

### RTCEF: The Solution

**RTCEF** (Run-Time Adaptation for Complex Event Forecasting) is a novel framework that addresses these challenges through:

- ⚡ **Run-time model updates** with minimal downtime
- 🔄 **Lossless adaptation** — no forecasts are lost during model transitions
- 🎯 **Intelligent decision-making** — distinguishes between when to retrain vs. when to re-optimize hyperparameters
- 📊 **Bayesian optimization** for efficient hyperparameter tuning

> 📄 **Reference Paper**: [Run-Time Adaptation of Complex Event Forecasting](https://dl.acm.org/doi/10.1145/3701717.3730539)  
> *Pitsikalis M., Alevizos E., Giatrakos N., Artikis A. — DEBS '25 (19th ACM International Conference on Distributed and Event-based Systems)*

---

## 🎯 Project Goals

This project aims to **integrate RTCEF into Apache Flink** to leverage:

| Flink Capabilities | Benefits for RTCEF |
|-------------------|-------------------|
| **Distributed Processing** | Scale CEF across clusters for high-throughput streams |
| **Exactly-once Semantics** | Guarantee forecast reliability and consistency |
| **Event Time Processing** | Handle out-of-order events with watermarks |
| **State Management** | Efficiently manage PST models and automaton states |
| **Checkpointing** | Enable fault-tolerant CEF with recovery |
| **Native Kafka Integration** | Seamless integration with existing RTCEF Kafka topics |

### Why Flink?

The original RTCEF framework uses native Python with Kafka for service communication. By porting to Flink, we enable:

1. **Production-ready scalability** — Process millions of events per second
2. **Unified stream processing** — Single framework for ingestion, processing, and output
3. **Ecosystem integration** — Easy integration with other Flink-based data pipelines
4. **Operational maturity** — Battle-tested in large-scale production environments

---

## 🏗️ Architecture

### Original RTCEF Architecture

The RTCEF framework consists of five synergistic services communicating over Kafka:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         INPUT STREAM                                │
└─────────────────────────────────────────────────────────────────────┘
                    │                           │
                    ▼                           ▼
            ┌───────────────┐           ┌───────────────┐
            │    ENGINE     │           │   COLLECTOR   │
            │   (Wayeb)     │           │               │
            │               │           │  Sliding      │
            │  CEF + CER    │           │  Window Data  │
            └───────┬───────┘           └───────┬───────┘
                    │                           │
                    │ Scores                    │ Datasets
                    ▼                           ▼
            ┌───────────────┐           ┌───────────────┐
            │   OBSERVER    │           │    FACTORY    │
            │               │◄─────────►│               │
            │ Trend-based   │           │  PST Training │
            │ Monitoring    │           │  & Testing    │
            └───────┬───────┘           └───────┬───────┘
                    │                           │
                    │ Instructions              │
                    ▼                           │
            ┌───────────────┐                   │
            │  CONTROLLER   │◄──────────────────┘
            │               │     Reports
            │   Bayesian    │
            │  Optimizer    │
            └───────────────┘
```

### Flink-RTCEF Target Architecture

...

---

## 🚀 Getting Started

### Prerequisites

- **Java 8 (JDK 1.8)**: Required for building/running Wayeb (Scala component).
- **Java 17 (JDK 17)**: Required for the Flink application.
- **Maven**: For building the Flink Java application.
- **SBT**: For building Wayeb.
- **Make**: To run the automated workflow.

> **Note**: On macOS/Linux, the `Makefile` attempts to auto-detect JDK paths. You can override them if detection fails or for custom setups:
>
> ```bash
> make build JAVA8_HOME="/usr/lib/jvm/java-8" JAVA17_HOME="/usr/lib/jvm/java-17"
> ```

### Installation

1. Clone the repository:

    ```bash
    git clone https://github.com/your-username/flink-RTCEF.git
    cd flink-RTCEF
    ```

2. Check your environment:

    ```bash
    make check-env
    ```

    *Ensure both Java 8 and 17 are detected.*

### Quick Start

To build everything and run the end-to-end smoke test:

```bash
make build smoke
```

This command will:

1. Compile Wayeb (using Java 8).
2. Compile the Flink App (using Java 17).
3. Run a recognition test on a sample stream.

If successful, you should see `--- Smoke Test Passed ---`.

---

## 📚 Key Concepts

### Prediction Suffix Trees (PST)

RTCEF uses [Wayeb](https://github.com/ElAlev/Wayeb), a CEF engine that employs **Prediction Suffix Trees** — a form of Variable-order Markov Models — to learn probabilistic patterns from data streams.

### Hyperparameters

| Parameter | Description |
|-----------|-------------|
| `m` | Maximum order of the PST (longer dependencies) |
| `θfc` | Confidence threshold for emitting forecasts |
| `pMin` | Symbol retaining probability threshold |
| `γ` | Symbol distribution smoothing parameter |

### Performance Metric: MCC

RTCEF uses **Matthew's Correlation Coefficient (MCC)** to evaluate forecasting performance, as it accounts for both positive and negative predictions:

$$MCC = \sqrt{Precision \times Recall \times Specificity \times NPV} - \sqrt{FDR \times FNR \times FPR \times FOMR}$$

---

## 🗂️ Project Structure

...

---

## 📖 References

- **Original RTCEF Repository**: [github.com/manospits/rtcef](https://github.com/manospits/rtcef)
- **Research Paper**: [Run-Time Adaptation of Complex Event Forecasting](https://dl.acm.org/doi/10.1145/3701717.3730539) — DEBS '25
- **Wayeb CEF Engine**: [github.com/ElAlev/Wayeb](https://github.com/ElAlev/Wayeb)
- **Apache Flink Documentation**: [flink.apache.org](https://flink.apache.org/)

---

## 👥 Authors

This project is developed as part of the **Data System Research** module, a final year Computer Science course at **INSA Lyon**, supervised by **Riccardo Tommasini**.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- The original RTCEF framework authors: Manolis Pitsikalis, Elias Alevizos, Nikos Giatrakos, and Alexander Artikis
- The CREXDATA project (European Union's Horizon Europe Programme, grant agreement No 101092749)
- INSA Lyon and the Data System Research teaching team
