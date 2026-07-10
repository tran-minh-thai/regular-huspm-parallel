package test;

import algorithms.AlgoRHUSPMiner;
import algorithms.AlgoRHUSPMinerParallel;

import java.util.*;

/**
 * Verifies that every combination of parallel-technique ablation switches
 * (parallelStrategy x denseBuffers x thread count) produces the same RHUSP set
 * as the sequential engine -- ensuring SC3 ablation variants do not alter correctness.
 *
 * Run:  java -Xss64m -Xmx6g -cp out test.StrategyVerificationTest [dataset] [su] [reg]
 */
public class StrategyVerificationTest {
    public static void main(String[] args) throws Exception {
        String ds = args.length > 0 ? args[0] : "SIGN";
        double su = args.length > 1 ? Double.parseDouble(args[1]) : 0.0025;
        double reg = args.length > 2 ? Double.parseDouble(args[2]) : 0.02;
        String seq = "datasets/" + ds + "_seq.txt", eui = "datasets/" + ds + "_eui.txt";

        AlgoRHUSPMiner oracle = new AlgoRHUSPMiner();
        oracle.useEUCS = true; oracle.boundMode = AlgoRHUSPMiner.BOUND_LA_PEU; oracle.useRegPruning = true;
        oracle.runAlgorithm(seq, eui, "results", ds, su, reg);
        Map<String, Long> ref = new HashMap<>();
        for (Map.Entry<String, AlgoRHUSPMiner.PatternResult> e : oracle.finalPatterns.entrySet())
            ref.put(e.getKey(), e.getValue().utility);
        System.out.printf("== Parallel ablation on %s (su=%s reg=%s) -- sequential oracle: %d patterns ==%n%n", ds, su, reg, ref.size());

        int[] strats = {AlgoRHUSPMinerParallel.STRAT_STATIC, AlgoRHUSPMinerParallel.STRAT_STEAL, AlgoRHUSPMinerParallel.STRAT_RDLB};
        String[] sname = {"STATIC", "STEAL", "RDLB"};
        int[] threads = {1, 4, 10};
        boolean all = true;
        for (boolean dense : new boolean[]{true, false}) {
            for (int si = 0; si < strats.length; si++) {
                for (int T : threads) {
                    AlgoRHUSPMinerParallel a = new AlgoRHUSPMinerParallel();
                    a.useEUCS = true; a.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU; a.useRegPruning = true;
                    a.numThreads = T; a.parallelStrategy = strats[si]; a.denseBuffers = dense;
                    a.runAlgorithm(seq, eui, "results", ds, su, reg);
                    Map<String, Long> cur = new HashMap<>();
                    for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> e : a.finalPatterns.entrySet())
                        cur.put(e.getKey(), e.getValue().utility);
                    boolean ok = ref.equals(cur);
                    all &= ok;
                    System.out.printf("  dense=%-5s %-7s T=%-2d : %6d patterns | %6d ms | nodes=%-8d | %s%n",
                            dense, sname[si], T, a.getPatternCount(), a.getRuntimeMs(), a.exploredNodes, ok ? "PASS" : "*** FAIL ***");
                }
            }
        }
        System.out.println();
        System.out.println(all ? "PASS: all strategy variants produce the same pattern set."
                               : "FAIL: at least one variant diverged!");
        if (!all) System.exit(1);
    }
}
