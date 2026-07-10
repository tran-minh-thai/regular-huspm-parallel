package test;

import java.util.StringJoiner;
import java.time.LocalDateTime;

public class ExperimentMetrics {
    public String runId = "";
    public String scenario = "";     // SC1..SC6
    public String algorithm = "";
    public String dataset = "";
    public int threads = 1;          // thread count (1 = sequential-equivalent)
    public double su = -1;           // minimum utility threshold ratio
    public double reg = -1;          // regularity threshold ratio
    public int iteration = 1;
    public String status = "SUCCESS";

    public long runtimeMs = 0;      // wall-clock time
    public long cpuTimeMs = -1;     // CPU time of the mining thread (unaffected by idle waits)
    public long preprocessMs = 0;   // Phase 1 time (SWU scan + EUCS build), included in runtimeMs
    public long peakHeapMb = 0;
    public long totalPatternsFound = 0;
    public long candidateCount = 0;
    public long exploredNodes = 0;

    // --- EXPLICIT PRUNING BREAKDOWN ---
    public long prunedEucs = 0;        // Tier 1 (EUCS co-occurrence filter)
    public long prunedPeu = 0;         // Tier 2 (standard PEU bound)
    public long prunedLaPeu = 0;       // Tier 2 (Look-Ahead PEU bound)
    public long prunedNodeCond = 0;    // Node condition (u + ru < minUtility)
    public long prunedRegularity = 0;  // Tier 3 (regularity constraint)

    public static String getHeader() {
        return "run_id,scenario,timestamp,algorithm,dataset,threads,su,reg,iteration,status," +
                "runtime_ms,cpu_time_ms,preprocess_ms,peak_heap_mb,total_patterns_found," +
                "candidate_count,explored_nodes," +
                "pruned_tier1_eucs,pruned_tier2_peu,pruned_tier2_la_peu,pruned_node_cond,pruned_tier3_reg";
    }

    public String toCsvRow() {
        StringJoiner sj = new StringJoiner(",");
        sj.add(runId).add(scenario).add(LocalDateTime.now().toString()).add(algorithm)
                .add(dataset).add(String.valueOf(threads))
                .add(String.valueOf(su)).add(String.valueOf(reg))
                .add(String.valueOf(iteration)).add(status)
                .add(String.valueOf(runtimeMs)).add(String.valueOf(cpuTimeMs))
                .add(String.valueOf(preprocessMs)).add(String.valueOf(peakHeapMb))
                .add(String.valueOf(totalPatternsFound)).add(String.valueOf(candidateCount))
                .add(String.valueOf(exploredNodes))
                .add(String.valueOf(prunedEucs))
                .add(String.valueOf(prunedPeu))
                .add(String.valueOf(prunedLaPeu))
                .add(String.valueOf(prunedNodeCond))
                .add(String.valueOf(prunedRegularity));
        return sj.toString();
    }
}
