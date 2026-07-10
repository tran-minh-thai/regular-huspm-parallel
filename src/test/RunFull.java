package test;

import java.io.IOException;

/**
 * Official experiment launcher -- evaluates the proposed PARALLEL ARCHITECTURE (paper §4).
 * Does NOT re-evaluate the pruning tiers (those are described in the paper, not re-ablated here).
 * Six scenarios SC1-SC6: sequential vs parallel, thread scalability, parallel-technique ablation,
 * preprocessing scalability, data scalability, comparison with AHUS-P baseline.
 *
 * Recommended JVM flags:
 *   java -Xms8g -Xmx24g -Xss64m -cp out test.RunFull
 *   java ... test.RunFull SC2 SC6     (run selected scenarios only)
 */
public class RunFull {
    public static void main(String[] args) throws IOException {
        ExperimentSuite.Config c = new ExperimentSuite.Config();
        c.tag = System.getenv().getOrDefault("RUN_TAG", "FULL"); // set RUN_TAG to re-run one scenario into a separate file
        c.iterations = 3;
        c.warmup = 1;
        c.timeoutSec = 3600;
        c.maxThreads = Runtime.getRuntime().availableProcessors();
        c.threadList = new int[]{1, 2, 4, 8, 10};

        // (su, reg) thresholds chosen to produce sufficient mining load for parallelism to matter;
        // reg is within the active-constraint range (not too loose).
        c.datasets.put("BIBLE",       new ExperimentSuite.DsCfg(0.010, 0.02));
        c.datasets.put("SIGN",        new ExperimentSuite.DsCfg(0.0020, 0.02));
        c.datasets.put("FIFA",        new ExperimentSuite.DsCfg(0.040, 0.05));
        c.datasets.put("LEVIATHAN",   new ExperimentSuite.DsCfg(0.0015, 0.01));
        c.datasets.put("KOSARAK",     new ExperimentSuite.DsCfg(0.010, 0.005));
        // SYN: consistently heavy threshold across all scenarios -- su alone would not generate
        // enough load; reg is relaxed (su=2e-5, reg=0.1 -> ~4.9M nodes, enough work for parallelism).
        c.datasets.put("C8T1S5I8N5K", new ExperimentSuite.DsCfg(0.00002, 0.1)); // SYN

        // SC1: sequential vs parallel (motivation): dense datasets with many items
        c.sc1Datasets = new String[]{"BIBLE", "FIFA", "LEVIATHAN"};
        // SC2: thread scalability (default 4 datasets; set SC2_DS env var to target a specific one,
        //      e.g. SC2_DS=C8T1S5I8N5K to re-run SYN only). SYN uses the heavy threshold from above.
        c.sc2Datasets = new String[]{"BIBLE", "FIFA", "LEVIATHAN", "C8T1S5I8N5K"};
        String sc2ds = System.getenv("SC2_DS");
        if (sc2ds != null && !sc2ds.isBlank()) c.sc2Datasets = sc2ds.trim().split("\\s*,\\s*");
        // SC3: parallel-technique ablation (where load imbalance is most visible)
        c.sc3Datasets = new String[]{"BIBLE", "FIFA"};
        // SC4: preprocessing scalability (heavy EUCS build)
        c.sc4Datasets = new String[]{"KOSARAK", "BIBLE"};
        // SC5: data scalability (random subsets); set SC5_DS env var to target a specific dataset,
        //      e.g. SC5_DS=C8T1S5I8N5K to re-run SYN only.
        c.sc5Datasets = new String[]{"KOSARAK", "C8T1S5I8N5K"};
        String sc5ds = System.getenv("SC5_DS");
        if (sc5ds != null && !sc5ds.isBlank()) c.sc5Datasets = sc5ds.trim().split("\\s*,\\s*");
        c.sc5Fractions = new double[]{0.2, 0.4, 0.6, 0.8, 1.0};
        // SC6: AHUS-P (HUSP) baseline -- su high enough to keep HUSP manageable
        c.sc6Datasets = new String[]{"BIBLE", "LEVIATHAN", "SIGN"};
        c.sc6SuOverride.put("BIBLE", 0.02);
        c.sc6SuOverride.put("LEVIATHAN", 0.004);
        c.sc6SuOverride.put("SIGN", 0.02);
        c.sc6AhuspTimeoutSec = 600;

        RunTest.applySelection(c, args);
        new ExperimentSuite(c).run();
    }
}
