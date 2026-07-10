package test;

import algorithms.AlgoAHUSP;
import algorithms.AlgoRHUSPMiner;
import algorithms.AlgoRHUSPMinerParallel;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * Experiment harness for the RHUSP parallelization study.
 *
 * SCOPE: the multi-tier pruning kernel (EUCS + LA-PEU bound + regularity constraint) is
 * inherited from the sequential work (paper). This study proposes and evaluates only the
 * PARALLELIZATION: load-balancing strategies and accompanying optimizations. Therefore the
 * experiments do NOT re-evaluate the pruning tiers -- those are described in the paper but
 * not re-ablated here. Baseline algorithms:
 *   - RHusp (Ishita et al.): classic SEQUENTIAL baseline (PEU bound + regularity, no EUCS,
 *     no LA-PEU) -- motivates the need for parallelism (time and memory).
 *   - Proposed-seq (T=1): proposed kernel running single-threaded -- base for speedup.
 *   - Proposed-par (T threads): the proposed parallel system.
 *   - AHUS-P: PARALLEL baseline for HUSP mining (no regularity constraint).
 *
 * Scenarios:
 *   SC1  Sequential vs parallel (motivation): RHusp-seq, Proposed-seq, Proposed-par --
 *        wall-clock time and PEAK MEMORY, establishing the need and gain of parallelism.
 *   SC2  Thread scalability: T=1..10; S_p=T_1/T_p, E_p=S_p/p; compared against Amdahl.
 *   SC3  PARALLEL-TECHNIQUE ablation: STATIC -> STEAL -> RDLB and dense-buffer on/off.
 *   SC4  PREPROCESSING speedup (SWU scan + EUCS build) vs thread count.
 *   SC5  DATA scalability: RHusp-seq vs Proposed-par across subset sizes |D| (widening gap).
 *   SC6  AHUS-P (HUSP) baseline comparison -- cost of the regularity constraint.
 */
public class ExperimentSuite {

    public static final class DsCfg {
        public final double su, reg;
        public DsCfg(double su, double reg) { this.su = su; this.reg = reg; }
    }

    public static final class Config {
        public String tag = "RUN";
        public String resultsDir = "results";
        public int iterations = 3;
        public int warmup = 1;
        public long timeoutSec = 3600;
        public int maxThreads = Runtime.getRuntime().availableProcessors();
        public int[] threadList = {1, 2, 4, 8, 10};

        public boolean runSC1 = true, runSC2 = true, runSC3 = true, runSC4 = true, runSC5 = true, runSC6 = true;

        public Map<String, DsCfg> datasets = new LinkedHashMap<>(); // ds -> (su, reg)
        public String[] sc1Datasets = {};
        public String[] sc2Datasets = {};
        public String[] sc3Datasets = {};
        public String[] sc4Datasets = {};
        public String[] sc5Datasets = {};
        public double[] sc5Fractions = {};
        public String[] sc6Datasets = {};

        // SC6: AHUS-P (HUSP) can explode in pattern count -- use a separate higher su and
        // a shorter timeout to avoid burning hours on unproductive runs.
        public Map<String, Double> sc6SuOverride = new LinkedHashMap<>();
        public long sc6AhuspTimeoutSec = 600;
    }

    private final Config cfg;
    private PrintWriter summary;
    private String lastStatus = "SUCCESS";
    private String curRunId = "";

    public ExperimentSuite(Config cfg) { this.cfg = cfg; }

    public void run() throws IOException {
        new File(cfg.resultsDir).mkdirs();
        summary = new PrintWriter(new FileWriter(cfg.resultsDir + "/" + cfg.tag + "_summary.txt", false), true);
        System.out.printf("[SUITE %s] warmup=%d, measure=%d (median), maxThreads=%d%n",
                cfg.tag, cfg.warmup, cfg.iterations, cfg.maxThreads);
        warmup();

        if (cfg.runSC1) sc1_seqVsParallel();
        if (cfg.runSC2) sc2_threadScalability();
        if (cfg.runSC3) sc3_parallelAblation();
        if (cfg.runSC4) sc4_preprocScalability();
        if (cfg.runSC5) sc5_dataScalability();
        if (cfg.runSC6) sc6_vsAHUSP();

        summary.close();
        System.out.println("\n[DONE] Suite " + cfg.tag + " complete. Results in " + cfg.resultsDir + "/");
    }

    private void warmup() {
        if (cfg.warmup <= 0 || cfg.datasets.isEmpty()) return;
        System.out.println(">>> Warming up JVM ...");
        Map.Entry<String, DsCfg> e = cfg.datasets.entrySet().iterator().next();
        for (int w = 0; w < cfg.warmup; w++) {
            runProposed("WARM", null, e.getKey(), e.getValue(), AlgoRHUSPMinerParallel.STRAT_RDLB, true, cfg.maxThreads, -1, null);
            runRHusp("WARM", e.getKey(), e.getValue(), -1, null);
        }
        System.gc(); sleep(300);
    }

    // ==================== SC1: SEQUENTIAL vs PARALLEL ====================
    private void sc1_seqVsParallel() throws IOException {
        if (cfg.sc1Datasets.length == 0) return;
        System.out.println("\n>>> SC1: Sequential vs Parallel (time + memory) ...");
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC1_SeqVsParallel.csv")) {
            curRunId = cfg.tag + "_SC1_" + System.currentTimeMillis();
            summary.printf("== [%s] SC1 Sequential vs Parallel (T_par=%d) ==%n", cfg.tag, cfg.maxThreads);
            summary.printf("%-14s %-16s %-10s %-10s %-8s%n", "dataset", "algo", "time_ms", "peak_MB", "patterns");
            for (String ds : cfg.sc1Datasets) {
                DsCfg dc = cfg.datasets.get(ds);
                if (dc == null || !exists(ds)) { System.err.println("SC1: skipping " + ds); continue; }
                System.out.printf("   %s (su=%s reg=%s)%n", ds, dc.su, dc.reg);
                double[] rh = repeatRHusp("SC1", ds, dc, col);
                double[] ps = repeatProposed("SC1", "Prop_seq", ds, dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, 1, col);
                double[] pp = repeatProposed("SC1", "Prop_par", ds, dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, cfg.maxThreads, col);
                System.out.printf("      RHusp-seq   : %8.0f ms | %7.0f MB | patterns=%.0f%n", rh[0], rh[2], rh[1]);
                System.out.printf("      Proposed-seq: %8.0f ms | %7.0f MB | patterns=%.0f%n", ps[0], ps[2], ps[1]);
                System.out.printf("      Proposed-par: %8.0f ms | %7.0f MB | patterns=%.0f (vs RHusp %.1fx, vs Prop-seq %.1fx)%n",
                        pp[0], pp[2], pp[1], safeDiv(rh[0], pp[0]), safeDiv(ps[0], pp[0]));
                summary.printf("%-14s %-16s %-10.0f %-10.0f %-8.0f%n", ds, "RHusp_seq", rh[0], rh[2], rh[1]);
                summary.printf("%-14s %-16s %-10.0f %-10.0f %-8.0f%n", ds, "Proposed_seq", ps[0], ps[2], ps[1]);
                summary.printf("%-14s %-16s %-10.0f %-10.0f %-8.0f%n", ds, "Proposed_par", pp[0], pp[2], pp[1]);
            }
            summary.println();
        }
    }

    // ==================== SC2: THREAD SCALABILITY ====================
    private void sc2_threadScalability() throws IOException {
        if (cfg.sc2Datasets.length == 0) return;
        System.out.println("\n>>> SC2: Thread scalability (S_p, E_p) ...");
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC2_ThreadScaling.csv")) {
            curRunId = cfg.tag + "_SC2_" + System.currentTimeMillis();
            for (String ds : cfg.sc2Datasets) {
                DsCfg dc = cfg.datasets.get(ds);
                if (dc == null || !exists(ds)) { System.err.println("SC2: skipping " + ds); continue; }
                System.out.printf("   %s (su=%s reg=%s)%n", ds, dc.su, dc.reg);
                double t1 = -1; Map<Integer, Double> med = new LinkedHashMap<>();
                for (int T : cfg.threadList) {
                    double[] r = repeatProposed("SC2", null, ds, dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, T, col);
                    med.put(T, r[0]); if (T == 1) t1 = r[0];
                    double sp = t1 > 0 ? t1 / r[0] : 1.0;
                    System.out.printf("      T=%-2d | %8.0f ms | patterns=%.0f | S_p=%.2f | E_p=%.2f%n", T, r[0], r[1], sp, sp / T);
                }
                writeSpeedup("SC2 " + ds + " (su=" + dc.su + " reg=" + dc.reg + ")", t1, med);
            }
        }
    }

    // ==================== SC3: PARALLEL-TECHNIQUE ABLATION ====================
    private void sc3_parallelAblation() throws IOException {
        if (cfg.sc3Datasets.length == 0) return;
        System.out.println("\n>>> SC3: Parallel-technique ablation ...");
        int T = cfg.maxThreads;
        Object[][] variants = {
                {"Seq_T1",      AlgoRHUSPMinerParallel.STRAT_RDLB,   Boolean.TRUE,  1},
                {"Static",      AlgoRHUSPMinerParallel.STRAT_STATIC, Boolean.TRUE,  T},
                {"Steal",       AlgoRHUSPMinerParallel.STRAT_STEAL,  Boolean.TRUE,  T},
                {"RDLB_dense",  AlgoRHUSPMinerParallel.STRAT_RDLB,   Boolean.TRUE,  T},
                {"RDLB_rawbuf", AlgoRHUSPMinerParallel.STRAT_RDLB,   Boolean.FALSE, T},
        };
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC3_ParallelAblation.csv")) {
            curRunId = cfg.tag + "_SC3_" + System.currentTimeMillis();
            for (String ds : cfg.sc3Datasets) {
                DsCfg dc = cfg.datasets.get(ds);
                if (dc == null || !exists(ds)) { System.err.println("SC3: skipping " + ds); continue; }
                System.out.printf("   %s (su=%s reg=%s), T=%d%n", ds, dc.su, dc.reg, T);
                double base = -1;
                for (Object[] v : variants) {
                    String name = (String) v[0]; int strat = (Integer) v[1]; boolean dense = (Boolean) v[2]; int th = (Integer) v[3];
                    double[] r = repeatProposed("SC3", name, ds, dc, strat, dense, th, col);
                    if ("Seq_T1".equals(name)) base = r[0];
                    double sp = base > 0 ? base / r[0] : 1.0;
                    System.out.printf("      %-12s | %8.0f ms | %7.0f MB | patterns=%.0f | S_p(vs Seq)=%.2f%n", name, r[0], r[2], r[1], sp);
                }
            }
        }
    }

    // ==================== SC4: PREPROCESSING SCALABILITY ====================
    private void sc4_preprocScalability() throws IOException {
        if (cfg.sc4Datasets.length == 0) return;
        System.out.println("\n>>> SC4: Preprocessing phase speedup (SWU scan + EUCS build) vs thread count ...");
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC4_Preprocess.csv")) {
            curRunId = cfg.tag + "_SC4_" + System.currentTimeMillis();
            for (String ds : cfg.sc4Datasets) {
                DsCfg dc = cfg.datasets.get(ds);
                if (dc == null || !exists(ds)) { System.err.println("SC4: skipping " + ds); continue; }
                System.out.printf("   %s (su=%s reg=%s)%n", ds, dc.su, dc.reg);
                double p1 = -1; Map<Integer, Double> med = new LinkedHashMap<>();
                for (int T : cfg.threadList) {
                    long[] preps = new long[cfg.iterations];
                    for (int w = 0; w < cfg.warmup; w++) runProposedAlgo(ds, seqPath(ds), euiPath(ds), dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, T);
                    for (int it = 1; it <= cfg.iterations; it++) {
                        AlgoRHUSPMinerParallel a = runProposedAlgo(ds, seqPath(ds), euiPath(ds), dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, T);
                        preps[it - 1] = a.preprocessMs;
                        ExperimentMetrics m = baseMetrics(curRunId, "SC4", "Prop_RDLB", ds, T, dc, it, lastStatus);
                        fillProposed(m, a); col.log(m);
                    }
                    Arrays.sort(preps);
                    double pm = preps[preps.length / 2];
                    med.put(T, pm); if (T == 1) p1 = pm;
                    double sp = p1 > 0 ? p1 / Math.max(pm, 1) : 1.0;
                    System.out.printf("      T=%-2d | preprocess %7.0f ms | S_p=%.2f%n", T, pm, sp);
                }
                writeSpeedup("SC4 preprocess " + ds, p1, med);
            }
        }
    }

    // ==================== SC5: DATA SCALABILITY ====================
    private void sc5_dataScalability() throws IOException {
        if (cfg.sc5Datasets.length == 0 || cfg.sc5Fractions.length == 0) return;
        System.out.println("\n>>> SC5: Data scalability (RHusp-seq vs Proposed-par, T=" + cfg.maxThreads + ") ...");
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC5_DataScaling.csv")) {
            curRunId = cfg.tag + "_SC5_" + System.currentTimeMillis();
            for (String ds : cfg.sc5Datasets) {
                DsCfg dc = cfg.datasets.get(ds);
                if (dc == null || !exists(ds)) { System.err.println("SC5: skipping " + ds); continue; }
                String tmp = cfg.resultsDir + "/temp_scale_" + ds + ".txt";
                boolean rhDead = false;
                for (double frac : cfg.sc5Fractions) {
                    createSubset(seqPath(ds), tmp, frac);
                    String label = ds + "_" + (int) (frac * 100) + "pct";
                    double[] pp = repeatProposedPath("SC5", "Prop_par", label, tmp, euiPath(ds), dc,
                            AlgoRHUSPMinerParallel.STRAT_RDLB, true, cfg.maxThreads, col);
                    double[] rh = rhDead ? new double[]{-1, -1, -1}
                            : repeatRHuspPath("SC5", label, tmp, euiPath(ds), dc, col);
                    if (rh[0] < 0) rhDead = true;
                    System.out.printf("   %-16s | RHusp-seq %9s ms | Proposed-par %8.0f ms (%.0f MB, patterns=%.0f) | gap=%s%n",
                            label, rh[0] < 0 ? "DNF" : String.format("%.0f", rh[0]), pp[0], pp[2], pp[1],
                            rh[0] < 0 ? "-" : String.format("%.1fx", safeDiv(rh[0], pp[0])));
                }
                new File(tmp).delete();
            }
        }
    }

    // ==================== SC6: COMPARISON WITH AHUS-P ====================
    private void sc6_vsAHUSP() throws IOException {
        if (cfg.sc6Datasets.length == 0) return;
        System.out.println("\n>>> SC6: Comparison with AHUS-P ...");
        try (ResultCollector col = new ResultCollector(cfg.resultsDir + "/" + cfg.tag + "_SC6_vsAHUSP.csv")) {
            curRunId = cfg.tag + "_SC6_" + System.currentTimeMillis();
            for (String ds : cfg.sc6Datasets) {
                DsCfg dc0 = cfg.datasets.get(ds);
                if (dc0 == null || !exists(ds)) { System.err.println("SC6: skipping " + ds); continue; }
                double su6 = cfg.sc6SuOverride.getOrDefault(ds, dc0.su);
                DsCfg dc = new DsCfg(su6, dc0.reg);
                System.out.printf("   %s (su=%s reg=%s)%n", ds, su6, dc.reg);
                double pT1 = -1, aT1 = -1; boolean ahuspDead = false;
                Map<Integer, Double> pMed = new LinkedHashMap<>(), aMed = new LinkedHashMap<>();
                for (int T : cfg.threadList) {
                    double[] rp = repeatProposed("SC6", null, ds, dc, AlgoRHUSPMinerParallel.STRAT_RDLB, true, T, col);
                    pMed.put(T, rp[0]); if (T == 1) pT1 = rp[0];
                    double[] ra = ahuspDead ? new double[]{-1, -1, -1} : repeatAHUSP("SC6", ds, dc, T, col);
                    if (ra[0] < 0) ahuspDead = true;
                    aMed.put(T, ra[0]); if (T == 1) aT1 = ra[0];
                    double psp = pT1 > 0 ? pT1 / rp[0] : 1.0;
                    double asp = (aT1 > 0 && ra[0] > 0) ? aT1 / ra[0] : Double.NaN;
                    System.out.printf("      T=%-2d | Proposed %7.0fms (RHUSP=%.0f, S_p=%.2f) | AHUS-P %8s ms (HUSP=%.0f, S_p=%.2f)%n",
                            T, rp[0], rp[1], psp, ra[0] < 0 ? "DNF" : String.format("%.0f", ra[0]), ra[1], asp);
                }
                writeSpeedup("SC6 Proposed " + ds, pT1, pMed);
                if (aT1 > 0) writeSpeedup("SC6 AHUS-P " + ds, aT1, aMed);
            }
        }
    }

    // ==================== CORE: Sequential RHusp ====================
    private double[] repeatRHusp(String scn, String ds, DsCfg dc, ResultCollector col) {
        return repeatRHuspPath(scn, ds, seqPath(ds), euiPath(ds), dc, col);
    }
    private double[] repeatRHuspPath(String scn, String label, String seq, String eui, DsCfg dc, ResultCollector col) {
        for (int w = 0; w < cfg.warmup; w++) runRHuspPath(scn, label, seq, eui, dc, -1, null);
        long[] times = new long[cfg.iterations]; double pat = -1, mem = -1; boolean ok = false;
        for (int it = 1; it <= cfg.iterations; it++) {
            double[] r = runRHuspPath(scn, label, seq, eui, dc, it, col);
            times[it - 1] = (long) r[0]; pat = r[1]; mem = r[2];
            if ("SUCCESS".equals(lastStatus)) ok = true; else break;
        }
        Arrays.sort(times);
        return new double[]{ok ? times[times.length / 2] : -1, pat, mem};
    }
    private double[] runRHusp(String scn, String ds, DsCfg dc, int iter, ResultCollector col) {
        return runRHuspPath(scn, ds, seqPath(ds), euiPath(ds), dc, iter, col);
    }
    private double[] runRHuspPath(String scn, String label, String seq, String eui, DsCfg dc, int iter, ResultCollector col) {
        AlgoRHUSPMiner a = new AlgoRHUSPMiner();
        a.useEUCS = false; a.boundMode = AlgoRHUSPMiner.BOUND_PEU; a.useRegPruning = true; // original RHusp configuration
        a.label = "RHusp_seq";
        lastStatus = runGuarded(cfg.timeoutSec, () -> { try { a.runAlgorithm(seq, eui, cfg.resultsDir, label, dc.su, dc.reg); } catch (IOException e) { throw new RuntimeException(e); } });
        double rt = (a.endTimestamp > 0 ? a.endTimestamp : System.currentTimeMillis()) - a.startTimestamp;
        if (col != null && iter >= 0) {
            ExperimentMetrics m = baseMetrics(curRunId, scn, "RHusp_seq", label, 1, dc, iter, lastStatus);
            m.runtimeMs = (long) rt; m.cpuTimeMs = a.getCpuTimeMs(); m.preprocessMs = a.preprocessMs;
            m.peakHeapMb = (long) a.getPeakMemoryMB(); m.candidateCount = a.candidateCount;
            m.exploredNodes = a.exploredNodes; m.totalPatternsFound = a.getPatternCount();
            m.prunedPeu = a.prunedBound; m.prunedNodeCond = a.prunedNodeCond; m.prunedRegularity = a.prunedRegularity;
            col.log(m);
        }
        return new double[]{rt, a.getPatternCount(), a.getPeakMemoryMB()};
    }

    // ==================== CORE: Proposed (parallel) ====================
    private double[] repeatProposed(String scn, String name, String ds, DsCfg dc, int strat, boolean dense, int T, ResultCollector col) {
        return repeatProposedPath(scn, name, ds, seqPath(ds), euiPath(ds), dc, strat, dense, T, col);
    }
    private double[] repeatProposedPath(String scn, String name, String label, String seq, String eui,
                                        DsCfg dc, int strat, boolean dense, int T, ResultCollector col) {
        for (int w = 0; w < cfg.warmup; w++) runProposedRow(scn, name, label, seq, eui, dc, strat, dense, T, -1, null);
        long[] times = new long[cfg.iterations]; double pat = -1, mem = -1;
        for (int it = 1; it <= cfg.iterations; it++) {
            double[] r = runProposedRow(scn, name, label, seq, eui, dc, strat, dense, T, it, col);
            times[it - 1] = (long) r[0]; pat = r[1]; mem = r[2];
        }
        Arrays.sort(times);
        return new double[]{times[times.length / 2], pat, mem};
    }
    private double[] runProposed(String scn, String name, String ds, DsCfg dc, int strat, boolean dense, int T, int iter, ResultCollector col) {
        return runProposedRow(scn, name, ds, seqPath(ds), euiPath(ds), dc, strat, dense, T, iter, col);
    }
    private double[] runProposedRow(String scn, String name, String label, String seq, String eui,
                                    DsCfg dc, int strat, boolean dense, int T, int iter, ResultCollector col) {
        AlgoRHUSPMinerParallel a = runProposedAlgo(label, seq, eui, dc, strat, dense, T);
        String algoName = (name != null) ? name : ("Prop_" + stratName(strat) + (dense ? "_dense" : "_raw"));
        double rt = (a.endTimestamp > 0 ? a.endTimestamp : System.currentTimeMillis()) - a.startTimestamp;
        if (col != null && iter >= 0) {
            ExperimentMetrics m = baseMetrics(curRunId, scn, algoName, label, T, dc, iter, lastStatus);
            fillProposed(m, a); col.log(m);
        }
        return new double[]{rt, a.getPatternCount(), a.getPeakMemoryMB()};
    }
    private AlgoRHUSPMinerParallel runProposedAlgo(String label, String seq, String eui, DsCfg dc, int strat, boolean dense, int T) {
        AlgoRHUSPMinerParallel a = new AlgoRHUSPMinerParallel();
        a.useEUCS = true; a.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU; a.useRegPruning = true;
        a.parallelStrategy = strat; a.denseBuffers = dense; a.numThreads = T; a.timeoutMs = cfg.timeoutSec * 1000L;
        a.label = "Prop_" + stratName(strat) + (dense ? "_dense" : "_raw") + "_T" + T;
        lastStatus = runGuarded(cfg.timeoutSec, () -> { try { a.runAlgorithm(seq, eui, cfg.resultsDir, label, dc.su, dc.reg); } catch (IOException e) { throw new RuntimeException(e); } });
        return a;
    }

    // ==================== CORE: AHUS-P ====================
    private double[] repeatAHUSP(String scn, String ds, DsCfg dc, int T, ResultCollector col) {
        for (int w = 0; w < cfg.warmup; w++) runAHUSPOnce(scn, ds, dc, T, -1, null);
        long[] times = new long[cfg.iterations]; double pat = -1; boolean ok = false;
        for (int it = 1; it <= cfg.iterations; it++) {
            double[] r = runAHUSPOnce(scn, ds, dc, T, it, col);
            times[it - 1] = (long) r[0]; pat = r[1];
            if (r[3] == 0) ok = true; else break;
        }
        Arrays.sort(times);
        return new double[]{ok ? times[times.length / 2] : -1, pat, -1};
    }
    private double[] runAHUSPOnce(String scn, String ds, DsCfg dc, int T, int iter, ResultCollector col) {
        AlgoAHUSP a = new AlgoAHUSP();
        a.numThreads = T; a.timeoutMs = cfg.sc6AhuspTimeoutSec * 1000L; a.label = "AHUS-P";
        String status = runGuarded(cfg.sc6AhuspTimeoutSec, () -> { try { a.runAlgorithm(seqPath(ds), euiPath(ds), cfg.resultsDir, ds, dc.su); } catch (IOException e) { throw new RuntimeException(e); } });
        double rt = (a.endTimestamp > 0 ? a.endTimestamp : System.currentTimeMillis()) - a.startTimestamp;
        if (col != null && iter >= 0) {
            ExperimentMetrics m = baseMetrics(curRunId, scn, "AHUS-P", ds, T, new DsCfg(dc.su, -1), iter, status);
            m.runtimeMs = (long) rt; m.cpuTimeMs = a.getCpuTimeMs(); m.preprocessMs = a.preprocessMs;
            m.peakHeapMb = (long) a.getPeakMemoryMB(); m.candidateCount = a.candidateCount;
            m.exploredNodes = a.exploredNodes; m.totalPatternsFound = a.getPatternCount();
            m.prunedPeu = a.prunedBound; m.prunedNodeCond = a.prunedNodeCond;
            col.log(m);
        }
        return new double[]{"SUCCESS".equals(status) ? rt : -1, a.getPatternCount(), -1, "SUCCESS".equals(status) ? 0 : 1};
    }

    // ==================== UTILITIES ====================
    private String runGuarded(long timeoutSec, Runnable task) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> f = ex.submit(task);
        String status = "SUCCESS";
        try { f.get(timeoutSec + 30, TimeUnit.SECONDS); }
        catch (TimeoutException e) { f.cancel(true); status = "TIMEOUT"; }
        catch (ExecutionException e) {
            Throwable c = e.getCause();
            String m1 = c != null ? c.getMessage() : null, m2 = (c != null && c.getCause() != null) ? c.getCause().getMessage() : null;
            if (c instanceof OutOfMemoryError) status = "OOM";
            else if ("TIMEOUT_INTERRUPT".equals(m1) || "TIMEOUT_INTERRUPT".equals(m2)) status = "TIMEOUT";
            else { status = "ERROR"; System.err.println("   [!] ERROR: " + c); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); status = "INTERRUPTED"; }
        finally { ex.shutdownNow(); System.gc(); sleep(200); }
        return status;
    }

    private ExperimentMetrics baseMetrics(String runId, String scn, String algo, String ds, int T, DsCfg dc, int iter, String status) {
        ExperimentMetrics m = new ExperimentMetrics();
        m.runId = runId; m.scenario = scn; m.algorithm = algo; m.dataset = ds;
        m.threads = T; m.su = dc.su; m.reg = dc.reg; m.iteration = iter; m.status = status;
        return m;
    }
    private void fillProposed(ExperimentMetrics m, AlgoRHUSPMinerParallel a) {
        m.runtimeMs = (a.endTimestamp > 0 ? a.endTimestamp : System.currentTimeMillis()) - a.startTimestamp;
        m.cpuTimeMs = a.getCpuTimeMs(); m.preprocessMs = a.preprocessMs;
        m.peakHeapMb = (long) a.getPeakMemoryMB(); m.candidateCount = a.candidateCount;
        m.exploredNodes = a.exploredNodes; m.totalPatternsFound = a.getPatternCount();
        m.prunedEucs = a.prunedEucs; m.prunedLaPeu = a.prunedBound;
        m.prunedNodeCond = a.prunedNodeCond; m.prunedRegularity = a.prunedRegularity;
    }
    private void writeSpeedup(String title, double t1, Map<Integer, Double> med) {
        summary.printf("== [%s] %s ==%n", cfg.tag, title);
        summary.printf("%-6s %-12s %-8s %-8s%n", "T", "median_ms", "S_p", "E_p");
        for (Map.Entry<Integer, Double> e : med.entrySet()) {
            int T = e.getKey(); double m = e.getValue();
            double sp = (t1 > 0 && m > 0) ? t1 / m : Double.NaN;
            summary.printf("%-6d %-12.0f %-8.3f %-8.3f%n", T, m, sp, sp / T);
        }
        summary.println();
    }

    private static double safeDiv(double a, double b) { return (a > 0 && b > 0) ? a / b : Double.NaN; }
    private static String stratName(int s) {
        return s == AlgoRHUSPMinerParallel.STRAT_STATIC ? "STATIC" : s == AlgoRHUSPMinerParallel.STRAT_STEAL ? "STEAL" : "RDLB";
    }
    private boolean exists(String ds) { return new File(seqPath(ds)).exists() && new File(euiPath(ds)).exists(); }
    private static String seqPath(String ds) { return "datasets/" + ds + "_seq.txt"; }
    private static String euiPath(String ds) { return "datasets/" + ds + "_eui.txt"; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static void createSubset(String src, String dst, double fraction) throws IOException {
        File f = new File(src);
        if (!f.exists()) return;
        List<String> lines = new ArrayList<>();
        for (String ln : Files.readAllLines(f.toPath())) {
            String t = ln.trim();
            if (!t.isEmpty() && !t.startsWith("#")) lines.add(ln);
        }
        Collections.shuffle(lines, new Random(42));
        int target = (int) (lines.size() * fraction);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(dst))) {
            for (int i = 0; i < target; i++) { bw.write(lines.get(i)); bw.newLine(); }
        }
    }
}
