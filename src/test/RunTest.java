package test;

import java.io.IOException;

/**
 * Lightweight sanity-check runner -- small parameters, fast execution, covers
 * all six scenarios SC1-SC6 before running the full official suite. Uses small
 * datasets, moderate thresholds, few threads, and a single measurement iteration.
 *
 * Run:  java -Xss64m -Xmx4g -cp out test.RunTest
 *       java ... test.RunTest SC2 SC6        (run selected scenarios only)
 */
public class RunTest {
    public static void main(String[] args) throws IOException {
        ExperimentSuite.Config c = new ExperimentSuite.Config();
        c.tag = "TEST";
        c.iterations = 1;
        c.warmup = 1;
        c.timeoutSec = 180;
        c.maxThreads = 4;
        c.threadList = new int[]{1, 2, 4};

        c.datasets.put("SIGN", new ExperimentSuite.DsCfg(0.002, 0.02));
        c.datasets.put("BMS1", new ExperimentSuite.DsCfg(0.002, 0.05));

        c.sc1Datasets = new String[]{"SIGN"};            // sequential vs parallel
        c.sc2Datasets = new String[]{"SIGN"};            // thread scalability
        c.sc3Datasets = new String[]{"SIGN"};            // parallel-technique ablation
        c.sc4Datasets = new String[]{"BMS1"};            // preprocessing scalability
        c.sc5Datasets = new String[]{"BMS1"};            // data scalability
        c.sc5Fractions = new double[]{0.5, 1.0};
        c.sc6Datasets = new String[]{"SIGN"};            // vs AHUS-P baseline
        c.sc6SuOverride.put("SIGN", 0.02);
        c.sc6AhuspTimeoutSec = 120;

        applySelection(c, args);
        new ExperimentSuite(c).run();
    }

    static void applySelection(ExperimentSuite.Config c, String[] args) {
        if (args.length == 0) return;
        java.util.Set<String> sel = new java.util.HashSet<>();
        for (String a : args) sel.add(a.trim().toUpperCase());
        c.runSC1 = sel.contains("SC1");
        c.runSC2 = sel.contains("SC2");
        c.runSC3 = sel.contains("SC3");
        c.runSC4 = sel.contains("SC4");
        c.runSC5 = sel.contains("SC5");
        c.runSC6 = sel.contains("SC6");
    }
}
