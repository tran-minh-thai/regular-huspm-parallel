# Parallel Mining of Regular High-Utility Sequential Patterns on Shared-Memory Multi-Core Architectures with Recursive Dynamic Load Balancing

This repository contains the official Java implementation of the high-performance parallel framework for **Regular High-Utility Sequential Pattern Mining (RHUSPM)** on shared-memory multi-core architectures.

---

## 📌 About the Paper

Discovering regular high-utility sequential patterns is an essential yet computationally expensive task in knowledge discovery. Traditional sequential algorithms suffer from combinatorial search space growth and excessive dynamic-allocation overhead. This framework addresses these bottlenecks through hardware-aware optimizations and dynamic load balancing.

### Key Contributions
* **Hardware-Friendly Data Layout:** The entire database is flattened into an immutable shared **Compressed Sparse Row (CSR)** primitive array. This design maximizes CPU cache locality and eliminates object-allocation overhead during mining.
* **Per-Thread Compressed Buffers:** Hot buffers are allocated separately per execution unit and indexed over a compressed item space rather than raw item identifiers. This completely prevents **false sharing** and minimizes memory overhead.
* **Recursive Dynamic Load Balancing (RDLB):** Combines standard thread-pool work stealing (LIFO queues for locality) with deep branch decomposition to balance highly asymmetric work graphs across asymmetric CPU cores.
* **Multi-Level Pruning Integration:** Seamlessly parallelizes an inherited core framework consisting of Sequence-Weighted Utility (SWU), Estimated Utility Co-occurrence Structure (EUCS), and Look-Ahead Pattern Extension Utility (LA-PEU) upper bounds.

---

## 📊 Datasets & Quantitative Data Conversion

The experimental evaluation utilizes six benchmark datasets spanning various scales, densities, and item spaces.

### Dataset Statistics
The repository supports processing the following benchmark profiles:

| Dataset | Sequences | Distinct Items | Avg. Length | Characteristics |
| :--- | :---: | :---: | :---: | :--- |
| **SIGN** | 730 | 267 | 52.00 | Small scale, dense |
| **LEVIATHAN** | 5,834 | 9,025 | 33.81 | Medium scale, sparse |
| **FIFA** | 20,450 | 2,990 | 36.24 | Medium scale |
| **BIBLE** | 36,369 | 13,905 | 21.64 | Medium scale, sparse |
| **SYN** | 47,133 | 68,240 | 18.83 | Large item space, synthetic |
| **KOSARAK** | 990,002 | 41,270 | 8.10 | Extremely large scale, very sparse |

### SPMF Utility Conversion
Standard datasets from the **SPMF Open-Source Data Mining Library** provide only pure sequential transaction structures without quantitative weights or utility values. To evaluate high-utility criteria, this repository includes a dedicated preprocessing/generation module that:
1. Assigns a synthetic **external utility** (unit profit) to each distinct item using a standard procedure matching literature standards.
2. Generates **internal utilities** (quantities purchased) dynamically per event instance.
3. Uses a **fixed pseudorandom seed** to ensure reproducible data generation across all tests and comparison baselines.

---

## 🛠️ Project Structure

```text
├── src/
│   ├── algorithm/        # Main RHUSPM-Par coordinator execution
│   ├── structure/        # Flat CSR array layouts and immutable adjacency lists
│   ├── parallel/         # Work-stealing thread pools and RDLB implementation
│   ├── pruning/          # LA-PEU upper bound calculations and EUCS filters
│   └── data/             # SPMF sequence parser and quantitative utility generator
├── datasets/             # Directory for data storage and generated .txt outputs
└── pom.xml               # Maven dependencies and project configuration