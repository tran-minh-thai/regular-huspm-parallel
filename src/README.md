# Parallel Mining of Regular High-Utility Sequential Patterns on Shared-Memory Multi-Core Architectures with Recursive Dynamic Load Balancing

[cite_start]This repository contains the official Java implementation of the high-performance parallel framework for **Regular High-Utility Sequential Pattern Mining (RHUSPM)** on shared-memory multi-core architectures[cite: 1, 7, 11].

---

## 📌 About the Paper

[cite_start]Discovering regular high-utility sequential patterns is an essential yet computationally expensive task in knowledge discovery[cite: 5, 14]. [cite_start]Traditional sequential algorithms suffer from combinatorial search space growth and excessive dynamic-allocation overhead[cite: 6, 22]. [cite_start]This framework addresses these bottlenecks through hardware-aware optimizations and dynamic load balancing[cite: 7, 8, 9].

### Key Contributions
* [cite_start]**Hardware-Friendly Data Layout:** The entire database is flattened into an immutable shared **Compressed Sparse Row (CSR)** primitive array[cite: 8, 29]. [cite_start]This design maximizes CPU cache locality and eliminates object-allocation overhead during mining[cite: 8, 31].
* [cite_start]**Per-Thread Compressed Buffers:** Hot buffers are allocated separately per execution unit and indexed over a compressed item space ($|P_{swu}|$) rather than raw item identifiers[cite: 8, 31, 136]. [cite_start]This completely prevents **false sharing** and minimizes memory overhead[cite: 8, 31, 136, 137].
* [cite_start]**Recursive Dynamic Load Balancing (RDLB):** Combines standard thread-pool work stealing (LIFO queues for locality) with deep branch decomposition to balance highly asymmetric work graphs across asymmetric CPU cores[cite: 9, 32, 191, 192, 194].
* [cite_start]**Multi-Level Pruning Integration:** Seamlessly parallelizes an inherited core framework consisting of Sequence-Weighted Utility (SWU), Estimated Utility Co-occurrence Structure (EUCS), and Look-Ahead Pattern Extension Utility (LA-PEU) upper bounds[cite: 7, 21, 124, 128].

---

## 📊 Datasets & Quantitative Data Conversion

[cite_start]The experimental evaluation utilizes six benchmark datasets spanning various scales, densities, and item spaces[cite: 10, 226, 227].

### Dataset Statistics
[cite_start]The repository supports processing the following benchmark profiles[cite: 226, 235]:

| Dataset | Sequences ($D$) | Distinct Items ($I$) | Avg. Length | Characteristics |
| :--- | :---: | :---: | :---: | :--- |
| **SIGN** | 730 | 267 | 52.00 | [cite_start]Small scale, dense [cite: 226, 236] |
| **LEVIATHAN** | 5,834 | 9,025 | 33.81 | [cite_start]Medium scale, sparse [cite: 226, 236] |
| **FIFA** | 20,450 | 2,990 | 36.24 | [cite_start]Medium scale [cite: 226, 236] |
| **BIBLE** | 36,369 | 13,905 | 21.64 | [cite_start]Medium scale, sparse [cite: 226, 236] |
| **SYN** | 47,133 | 68,240 | 18.83 | [cite_start]Large item space, synthetic [cite: 226, 236] |
| **KOSARAK** | 990,002 | 41,270 | 8.10 | [cite_start]Extremely large scale, very sparse [cite: 226, 236] |

### SPMF Utility Conversion
[cite_start]Standard datasets from the **SPMF Open-Source Data Mining Library** provide only pure sequential transaction structures without quantitative weights or utility values[cite: 226, 229]. To evaluate high-utility criteria, this repository includes a dedicated preprocessing/generation module that:
1. [cite_start]Assigns a synthetic **external utility** (unit profit) to each distinct item using a standard procedure matching literature standards[cite: 229].
2. [cite_start]Generates **internal utilities** (quantities purchased) dynamically per event instance[cite: 67, 229].
3. [cite_start]Uses a **fixed pseudorandom seed** to ensure reproducible data generation across all tests and comparison baselines[cite: 229].

---

## 🛠️ Project Structure

```text
├── src/
│   ├── algorithm/        # Main RHUSPM-Par coordinator execution [Algorithm 1]
│   ├── structure/        # Flat CSR array layouts and immutable adjacency lists
│   ├── parallel/         # Work-stealing thread pools and RDLB implementation
│   ├── pruning/          # LA-PEU upper bound calculations and EUCS filters
│   └── data/             # SPMF sequence parser and quantitative utility generator
├── datasets/             # Directory for data storage and generated .txt outputs
└── pom.xml               # Maven dependencies and project configuration