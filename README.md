# Parallel Regular High-Utility Sequential Pattern Mining with Recursive Dynamic Load Balancing

Reference implementation and experiment harness for the paper of the same name.

A regular high-utility sequential pattern (RHUSP) is a pattern whose aggregate utility reaches a
minimum threshold and whose largest gap between consecutive occurrences never exceeds a maximum
period. This repository contains a parallel miner for shared-memory multi-core machines. The
database is held in an immutable flat CSR array shared by all threads, the per-thread scratch
buffers are indexed over a compressed item space, and the search tree is scheduled by a
work-stealing pool extended with recursive dynamic load balancing (RDLB).

The multi-level pruning kernel (SWU and regularity item filters, an EUCS co-occurrence filter and
the LA-PEU upper bound) comes from an earlier sequential study and is reused unchanged; the
contribution evaluated here is the parallelization.

## Requirements

* JDK 21 or newer. The results in the paper were produced on JDK 26.
* No build system. The sources are compiled directly with `javac`.
* Python 3 with `pandas` and `matplotlib`, only if you want to regenerate the aggregate tables and
  the figures from the raw CSV files.

## Repository layout

```
run_full.sh                        Compiles the sources and runs the experiment suite
src/
  algorithms/
    AlgoRHUSPMinerParallel.java    Proposed parallel miner (CSR layout, work stealing, RDLB)
    AlgoRHUSPMiner.java            Sequential miner with individually togglable pruning tiers
    AlgoAHUSP.java                 AHUS-P parallel baseline, no regularity constraint
    MemMeter.java                  Per-run peak-heap measurement
  test/
    RunFull.java                   Official suite, scenarios SC1 to SC6
    RunTauSensitivity.java         Sweep of the granularity threshold tau (scenario SC7)
    RunTest.java                   Quick sanity suite with small parameters
    ExperimentSuite.java           Scenario implementations and measurement protocol
    ExperimentMetrics.java         One row of a result CSV
    ResultCollector.java           CSV writer
    StrategyVerificationTest.java  Ablation variants must return the sequential result set
    AHUSPVerificationTest.java     Cross-checks the AHUS-P baseline against the proposed miner
datasets/                          Raw SPMF files and the generated quantitative files
results/                           Result CSVs, run summary, logs, aggregated tables, figures
analysis/colab_analyze.py          Builds the aggregate tables and the figures of the paper
```

## Data format

Every dataset is stored as two files.

`<name>_seq.txt` holds one quantitative sequence per line. Non-negative integers are item
identifiers, each followed by its internal utility (the quantity) in brackets. `-1` closes an
event and `-2` closes the sequence:

```
1[2] 3[1] -1 4[2] -1 -2
```

`<name>_eui.txt` holds the external utility (unit profit) of every item, one per line:

```
# ItemID:Profit
1:2
3:3
```

Only the small datasets are committed: `SIGN`, `LEVIATHAN`, `BMS1` and the eight-sequence running
example `paper`. Together they occupy about four megabytes, and they are enough to run the quick
suite and both correctness checks straight after a clone. The four large ones, `BIBLE`, `FIFA`,
`KOSARAK` and `C8T1S5I8N5K`, are **not** redistributed here, and neither are the raw SPMF files any
of them derive from, because everything is freely available from the SPMF site.

To obtain the missing datasets, take them from the pinned
[huspm-datasets](https://github.com/tran-minh-thai/huspm-datasets) release, which ships the
quantitative databases together with a checksum manifest. To build them from the raw SPMF files
instead, download those under the names `BIBLE.txt`, `FIFA.txt`, `KOSARAK.txt` and
`C8T1S5I8N5K.txt` and convert them with
[convert-spmf-to-quantitative](https://github.com/tran-minh-thai/convert-spmf-to-quantitative):

```
git clone https://github.com/tran-minh-thai/convert-spmf-to-quantitative
javac -encoding UTF-8 -d out src/SPMF_Converter.java
java -cp out SPMF_Converter          # raw files in datasets/
```

It skips any name it does not find, so a partial download is fine. The SPMF files contain sequences
only; the converter adds the quantitative part. Internal utilities are drawn from a weighted mixture
(70 % in 1-2, 20 % in 3-5, 10 % in 6-10) and external utilities from a log-normal distribution
(mu = 2.5, sigma = 1.0) clamped to [1, 1000]. The generator is seeded with 42, so it reproduces the
exact files used for the reported results, byte for byte.

The datasets used in the paper, with the thresholds applied to them. `delta` is the utility
threshold as a fraction of the total database utility, `rho` the regularity threshold as a
fraction of the number of sequences.

| Dataset   | File prefix   | Sequences | Distinct items | Avg. length | delta (%) | rho (%) | Shipped   |
| --------- | ------------- | --------: | -------------: | ----------: | --------: | ------: | --------- |
| SIGN      | `SIGN`        |       730 |            267 |       52.00 |     2.000 |     2.0 | yes       |
| LEVIATHAN | `LEVIATHAN`   |     5,834 |          9,025 |       33.81 |     0.150 |     1.0 | yes       |
| FIFA      | `FIFA`        |    20,450 |          2,990 |       36.24 |     4.000 |     5.0 | regenerate |
| BIBLE     | `BIBLE`       |    36,369 |         13,905 |       21.64 |     1.000 |     2.0 | regenerate |
| SYN       | `C8T1S5I8N5K` |    47,133 |         68,240 |       18.83 |     0.002 |    10.0 | regenerate |
| KOSARAK   | `KOSARAK`     |   990,002 |         41,270 |        8.10 |     1.000 |     0.5 | regenerate |

SYN is stored under its original SPMF name `C8T1S5I8N5K`. Scenario SC6 raises `delta` on BIBLE,
LEVIATHAN and SIGN, because the HUSP result set of the AHUS-P baseline explodes at the thresholds
above. Two further datasets are present but are not part of the paper: `BMS1` is used by the quick
sanity suite, and `paper` is the eight-sequence running example of the paper, used by
`AHUSPVerificationTest`.

## Building

```
./run_full.sh compile
```

or, equivalently,

```
find src -name '*.java' -print0 | xargs -0 javac -d out
```

## Running the experiments

```
./run_full.sh              # compile, then run scenarios SC1 to SC6
./run_full.sh SC2 SC6      # run only the named scenarios
./run_full.sh test         # quick suite, small parameters
```

On a filesystem that does not preserve the executable bit, such as a synchronised cloud folder,
invoke the script as `bash run_full.sh` instead.

The quick suite and the two correctness checks run on the datasets that ship with the repository.
The full suite additionally needs `BIBLE`, `FIFA`, `KOSARAK` and `C8T1S5I8N5K`, so regenerate those
first as described above; a scenario whose dataset is missing is skipped with a message on stderr.

The sweep of the granularity threshold is a separate launcher:

```
java -Xms8g -Xmx24g -Xss64m -cp out test.RunTauSensitivity              # all four datasets
java -Xms8g -Xmx24g -Xss64m -cp out test.RunTauSensitivity BIBLE FIFA   # a subset
SC7_ITERS=11 java ... test.RunTauSensitivity LEVIATHAN                  # more runs, tighter spread
```

`SC7_ITERS` sets the number of measured runs and defaults to three. Datasets whose runtime is only
a few tens of milliseconds sit at the resolution of the timer, so raise it for those. `SC7_DS_WARMUP`
sets how many discarded runs precede each dataset (default two); they absorb the one-off cost of
switching datasets, which the JIT and the garbage collector otherwise charge to the first measured
configuration of the new dataset.

The JVM options default to `-Xms8g -Xmx24g -Xss64m` and can be overridden. On a machine with less
memory, lower `-Xmx`; FIFA and SYN need roughly 5 GB and 8 GB of heap respectively.

```
JVM_OPTS="-Xms2g -Xmx8g -Xss64m" ./run_full.sh SC2
```

Three more environment variables are honoured by the suite: `RUN_TAG` renames the result files, and
`SC2_DS` and `SC5_DS` restrict those two scenarios to a single dataset.

Every configuration is warmed up once and then measured three times; the median is reported.
Loading the data is excluded from the measured runtime. The peak heap is read through `MemMeter`,
which resets the peak marker around each run so that one algorithm cannot inherit the garbage of
the previous one. Long runs write a timestamped log under `results/logs/`.

## Reproducing the tables and figures of the paper

| Paper artefact                              | Scenario | Result file                        |
| ------------------------------------------- | -------- | ---------------------------------- |
| Table 6, runtime and peak memory            | SC1      | `FULL_SC1_SeqVsParallel.csv`       |
| Figure 1 (a), (b), runtime and peak memory  | SC1      | `FULL_SC1_SeqVsParallel.csv`       |
| Figure 1 (c), technique ablation            | SC3      | `FULL_SC3_ParallelAblation.csv`    |
| Figure 2 (a), (b), speedup and efficiency   | SC2      | `FULL_SC2_ThreadScaling.csv`       |
| Figure 2 (c), preprocessing speedup         | SC4      | `FULL_SC4_Preprocess.csv`          |
| Figure 3 (a), (b), data scaling             | SC5      | `FULL_SC5_DataScaling.csv`         |
| Figure 3 (c), comparison with AHUS-P        | SC6      | `FULL_SC6_vsAHUSP.csv`             |
| Table 7, sensitivity to tau                 | SC7      | `FULL_SC7_TauSensitivity.csv`      |

All files are written to `results/`. To rebuild the aggregate tables and the three figures from
them, run `analysis/colab_analyze.py`; it writes `results/tables/` and `results/figures/`. The
script was written for Google Colab but runs anywhere `pandas` and `matplotlib` are installed.

## Result files

The scenario CSVs share one row per measured iteration:

```
run_id, scenario, timestamp, algorithm, dataset, threads, su, reg, iteration, status,
runtime_ms, cpu_time_ms, preprocess_ms, peak_heap_mb, total_patterns_found,
candidate_count, explored_nodes, pruned_tier1_eucs, pruned_tier2_peu,
pruned_tier2_la_peu, pruned_node_cond, pruned_tier3_reg
```

`status` is `SUCCESS`, `TIMEOUT`, `OOM` or `ERROR`; only `SUCCESS` rows should be aggregated.
`su` and `reg` are the ratios `delta` and `rho`, not absolute thresholds. One row per iteration is
kept on purpose, so the run-to-run dispersion can be recovered from these files rather than being
lost inside a median.

The tau sweep writes two files. `FULL_SC7_TauSensitivity.csv` aggregates, one row per value of tau:

```
dataset, su, reg, threads, tau, median_ms, min_ms, max_ms, spread_pct, patterns, peak_MB
```

`spread_pct` is `(max - min) / median`, expressed as a percentage. It is decided by a single
outlier, so `FULL_SC7_TauRaw.csv` keeps every measured iteration as well, from which a robust
statistic such as the interquartile range can be computed afterwards:

```
dataset, su, reg, threads, tau, iteration, runtime_ms, patterns, peak_MB
```

`FULL_summary.txt` repeats the medians, speedups and efficiencies printed during the run.

## Configuring the miner

`AlgoRHUSPMinerParallel` is configured through public fields set before `runAlgorithm`:

| Field                  | Default                  | Meaning                                                        |
| ---------------------- | ------------------------ | -------------------------------------------------------------- |
| `numThreads`           | `availableProcessors()`  | size of the ForkJoin pool                                       |
| `parallelStrategy`     | `STRAT_RDLB`             | `STRAT_STATIC`, `STRAT_STEAL` or `STRAT_RDLB`                   |
| `denseBuffers`         | `true`                   | index the per-thread buffers over the compressed item space     |
| `minForkInstances`     | `64`                     | tau: fork only if the projected database holds this many entries |
| `forkSurplusThreshold` | `3`                      | fork only while the pool has at most this many surplus tasks     |
| `useEUCS`              | `true`                   | EUCS co-occurrence filter                                       |
| `boundMode`            | `BOUND_LA_PEU`           | `BOUND_NONE`, `BOUND_PEU` or `BOUND_LA_PEU`                     |
| `useRegPruning`        | `true`                   | anti-monotone regularity pruning                                |
| `timeoutMs`            | `-1`                     | abort the run after this many milliseconds                      |

The RHusp baseline of the paper is the sequential engine `AlgoRHUSPMiner` with `useEUCS = false`,
`boundMode = BOUND_PEU` and `useRegPruning = true`.

Both miners are invoked the same way. `minUtilRatio` is `delta`, so the absolute threshold is
`delta` times the total database utility; `maxRegRatio` is `rho`, so the absolute period bound is
`rho` times the number of sequences.

```java
AlgoRHUSPMinerParallel miner = new AlgoRHUSPMinerParallel();
miner.numThreads = 10;
miner.runAlgorithm("datasets/BIBLE_seq.txt", "datasets/BIBLE_eui.txt",
                   "results", "BIBLE", 0.01, 0.02);
System.out.println(miner.getPatternCount() + " patterns in "
                   + (miner.endTimestamp - miner.startTimestamp) + " ms");
```

## Correctness checks

```
java -Xss64m -Xmx6g -cp out test.StrategyVerificationTest SIGN 0.002 0.02
java -Xss64m -Xmx6g -cp out test.AHUSPVerificationTest paper
```

The first check runs every combination of scheduling strategy, buffer layout and thread count and
asserts that each returns exactly the pattern set of the sequential engine, so the ablation
variants differ in speed only. The second check disables the regularity constraint (`rho = 1`) and
compares the proposed miner against AHUS-P, which mines high-utility sequences without regularity;
the two result sets, utilities included, must coincide.

## Environment used for the reported numbers

Apple M5 with four performance cores and six efficiency cores (ten logical cores), 32 GB of unified
memory, JDK 26, JVM started with `-Xms8g -Xmx24g -Xss64m`. Absolute runtimes depend on the machine;
the speedups and the relative comparisons do not.

## Data sources

The five real datasets come from the SPMF library, <https://philippe-fournier-viger.com/spmf/>.
The synthetic dataset SYN (`C8T1S5I8N5K`) is distributed by the same project. Neither the raw
sequence files nor the four large generated ones are redistributed here, since both are reachable
from the link above and the generator is deterministic. Utility values are not part of the original
datasets; they are generated by
[convert-spmf-to-quantitative](https://github.com/tran-minh-thai/convert-spmf-to-quantitative),
whose generator is seeded per input file so that a raw file always converts to the same bytes,
regardless of what else was converted alongside it.
