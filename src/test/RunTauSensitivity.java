package test;

import algorithms.AlgoRHUSPMinerParallel;

import java.io.*;
import java.util.*;

/**
 * SC7 -- sensitivity of the fork granularity threshold tau (minForkInstances).
 *
 * Sweeps tau over {16, 32, 64, 128, 256} at fixed T = availableProcessors, with the same
 * measurement protocol as SC2 (warmup once, then median of three), to substantiate the
 * paper's claim that the runtime is insensitive to tau over [32,128]. Everything else
 * (RDLB, dense buffers, EUCS + LA-PEU, regularity pruning) is held identical to the
 * Proposed-par configuration; only tau varies.
 *
 * Standalone launcher: it does NOT touch ExperimentSuite / SC1..SC6. It is compiled by
 * run_full.sh (which globs src for *.java); run it with:
 *     java -Xms8g -Xmx24g -Xss64m -cp out test.RunTauSensitivity
 *     java ... test.RunTauSensitivity BIBLE FIFA     (subset of datasets)
 *
 * Thresholds (su, reg) match SC2 so the numbers are comparable to the thread-scaling table.
 */
public class RunTauSensitivity {

    static final int[] TAUS = {16, 32, 64, 128, 256};

    public static void main(String[] args) throws Exception {
        int T = Runtime.getRuntime().availableProcessors();
        int warmup = 1, iters = 3;
        String resultsDir = "results";
        new File(resultsDir).mkdirs();

        // dataset -> {su, reg}, matching the SC2 thread-scaling thresholds
        LinkedHashMap<String, double[]> all = new LinkedHashMap<>();
        all.put("BIBLE",       new double[]{0.010,   0.02});
        all.put("FIFA",        new double[]{0.040,   0.05});
        all.put("LEVIATHAN",   new double[]{0.0015,  0.01});
        all.put("C8T1S5I8N5K", new double[]{0.00002, 0.10}); // SYN

        // optional dataset filter via args
        List<String> targets = new ArrayList<>();
        if (args.length > 0) {
            for (String a : args) { String k = a.trim(); if (all.containsKey(k)) targets.add(k); }
        }
        if (targets.isEmpty()) targets.addAll(all.keySet());

        PrintWriter csv = new PrintWriter(new FileWriter(resultsDir + "/FULL_SC7_TauSensitivity.csv"), true);
        PrintWriter sum = new PrintWriter(new FileWriter(resultsDir + "/FULL_SC7_TauSensitivity.txt"), true);
        csv.println("dataset,su,reg,threads,tau,median_ms,patterns,peak_MB");

        System.out.printf("[SC7] tau sensitivity | T=%d | warmup=%d measure=%d (median) | taus=%s%n",
                T, warmup, iters, Arrays.toString(TAUS));
        sum.printf("== [FULL] SC7 tau sensitivity (T=%d, warmup=%d, median of %d) ==%n", T, warmup, iters);

        for (String ds : targets) {
            String seq = "datasets/" + ds + "_seq.txt";
            String eui = "datasets/" + ds + "_eui.txt";
            if (!new File(seq).exists() || !new File(eui).exists()) {
                System.err.println("SC7: skipping " + ds + " (dataset files not found)");
                continue;
            }
            double su = all.get(ds)[0], reg = all.get(ds)[1];
            System.out.printf("%n>>> %s (su=%s reg=%s)%n", ds, su, reg);
            sum.printf("%n%s (su=%s reg=%s)%n", ds, su, reg);
            sum.printf("%-6s %-12s %-10s %-8s%n", "tau", "median_ms", "patterns", "peak_MB");

            Map<Integer, Double> med = new LinkedHashMap<>();
            double pat = -1;
            for (int tau : TAUS) {
                for (int w = 0; w < warmup; w++) runOnce(seq, eui, resultsDir, ds, su, reg, T, tau);
                long[] times = new long[iters];
                double mem = -1;
                for (int it = 0; it < iters; it++) {
                    double[] r = runOnce(seq, eui, resultsDir, ds, su, reg, T, tau);
                    times[it] = (long) r[0]; pat = r[1]; mem = r[2];
                }
                Arrays.sort(times);
                double m = times[iters / 2];
                med.put(tau, m);
                csv.printf("%s,%s,%s,%d,%d,%.0f,%.0f,%.0f%n", ds, su, reg, T, tau, m, pat, mem);
                sum.printf("%-6d %-12.0f %-10.0f %-8.0f%n", tau, m, pat, mem);
                System.out.printf("   tau=%-4d | median=%8.0f ms | patterns=%.0f | peak=%.0f MB%n", tau, m, pat, mem);
            }

            // deviation vs tau=64 across the claimed band [32,128] and the full sweep
            double base = med.getOrDefault(64, Double.NaN);
            double devBand = 0, devAll = 0;
            for (Map.Entry<Integer, Double> e : med.entrySet()) {
                double dev = Math.abs(e.getValue() - base) / base * 100.0;
                devAll = Math.max(devAll, dev);
                if (e.getKey() >= 32 && e.getKey() <= 128) devBand = Math.max(devBand, dev);
            }
            sum.printf("max deviation vs tau=64: %.1f%% over [32,128], %.1f%% over [16,256]%n", devBand, devAll);
            System.out.printf("   >> max dev vs tau=64: %.1f%% over [32,128] | %.1f%% over [16,256]%n", devBand, devAll);
        }
        csv.close();
        sum.close();
        System.out.println("\n[SC7 DONE] -> results/FULL_SC7_TauSensitivity.{csv,txt}");
    }

    /** One measured run of Proposed-par with a given tau; returns {runtime_ms, patterns, peak_MB}. */
    static double[] runOnce(String seq, String eui, String outDir, String label,
                            double su, double reg, int T, int tau) throws Exception {
        AlgoRHUSPMinerParallel a = new AlgoRHUSPMinerParallel();
        a.useEUCS = true;
        a.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        a.useRegPruning = true;
        a.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
        a.denseBuffers = true;
        a.numThreads = T;
        a.minForkInstances = tau;      // <-- the swept parameter
        a.timeoutMs = 3600_000L;
        a.label = label + "_tau" + tau;
        a.runAlgorithm(seq, eui, outDir, label, su, reg);
        double rt = (a.endTimestamp > 0 ? a.endTimestamp : System.currentTimeMillis()) - a.startTimestamp;
        System.gc();
        Thread.sleep(150);
        return new double[]{rt, a.getPatternCount(), a.getPeakMemoryMB()};
    }
}
