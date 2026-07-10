package test;

import algorithms.AlgoAHUSP;
import algorithms.AlgoRHUSPMinerParallel;

import java.util.*;

/**
 * Verifies the correctness of the AHUS-P baseline through its relationship with the
 * proposed engine:
 *
 *   (1) AHUS-P produces the complete HUSP set at threshold su. The proposed engine run with
 *       rho=1.0 (maxReg = N, regularity inactive) also produces exactly HUSP. Both sets
 *       (including utility values) must match -- confirms AHUS-P is correct.
 *
 *   (2) With a small rho (active regularity), the RHUSP set produced by the proposed engine
 *       must be a strict subset of AHUS-P's HUSP, and per-pattern utilities must agree --
 *       confirms the relationship RHUSP = { alpha in HUSP | maxPeriod(alpha) <= maxReg }.
 *
 *   (3) AHUS-P is invariant to thread count (T=1,2,4 all produce the same HUSP set).
 *
 * Run:  java -Xss64m -cp out test.AHUSPVerificationTest [dataset] [su]
 * Default: paper 0.1
 */
public class AHUSPVerificationTest {

    public static void main(String[] args) throws Exception {
        String ds = args.length > 0 ? args[0] : "paper";
        double su = args.length > 1 ? Double.parseDouble(args[1]) : 0.1;
        double regSmall = args.length > 2 ? Double.parseDouble(args[2]) : 0.05;
        String dataPath = "datasets/" + ds + "_seq.txt";
        String utilPath = "datasets/" + ds + "_eui.txt";

        System.out.printf("== AHUS-P verification on %s (su=%s) ==%n%n", ds, su);

        // (1) AHUS-P (HUSP) vs Proposed rho=1.0 (HUSP)
        AlgoAHUSP ahusp = new AlgoAHUSP();
        ahusp.numThreads = 4;
        ahusp.runAlgorithm(dataPath, utilPath, "results", ds, su);
        Map<String, Long> husp = new HashMap<>(ahusp.finalPatterns);
        System.out.printf("%-26s : %6d HUSP patterns | %6d ms | nodes=%d%n",
                "AHUS-P (T=4)", ahusp.getPatternCount(), ahusp.getRuntimeMs(), ahusp.exploredNodes);

        AlgoRHUSPMinerParallel prop = new AlgoRHUSPMinerParallel();
        prop.useEUCS = true; prop.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU; prop.useRegPruning = true;
        prop.numThreads = 4; prop.label = "Prop_reg1";
        prop.runAlgorithm(dataPath, utilPath, "results", ds, su, 1.0); // rho=1.0 => regularity inactive
        Map<String, Long> propAll = utilMap(prop.finalPatterns);
        System.out.printf("%-26s : %6d HUSP patterns | %6d ms | nodes=%d%n",
                "Proposed rho=1.0 (HUSP)", prop.getPatternCount(), prop.getRuntimeMs(), prop.exploredNodes);

        boolean eq1 = husp.equals(propAll);
        if (!eq1) printDiff(propAll, husp);
        System.out.println("  [1] AHUS-P == Proposed(rho=1.0): " + (eq1 ? "PASS" : "*** FAIL ***"));

        // (2) RHUSP (small rho) is a subset of HUSP, with matching utility values
        AlgoRHUSPMinerParallel rhusp = new AlgoRHUSPMinerParallel();
        rhusp.useEUCS = true; rhusp.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU; rhusp.useRegPruning = true;
        rhusp.numThreads = 4; rhusp.label = "Prop_regSmall";
        rhusp.runAlgorithm(dataPath, utilPath, "results", ds, su, regSmall);
        Map<String, Long> rh = utilMap(rhusp.finalPatterns);
        boolean subset = true;
        for (Map.Entry<String, Long> e : rh.entrySet())
            if (!Objects.equals(husp.get(e.getKey()), e.getValue())) { subset = false; break; }
        System.out.printf("%-26s : %6d RHUSP patterns (rho=%s) -- subset of HUSP & utilities match: %s%n",
                "Proposed small rho (RHUSP)", rhusp.getPatternCount(), regSmall, subset ? "PASS" : "*** FAIL ***");

        // (3) AHUS-P is invariant to thread count
        boolean stable = true;
        for (int T : new int[]{1, 2, 4}) {
            AlgoAHUSP a = new AlgoAHUSP();
            a.numThreads = T;
            a.runAlgorithm(dataPath, utilPath, "results", ds, su);
            boolean ok = new HashMap<>(a.finalPatterns).equals(husp);
            stable &= ok;
            System.out.printf("    AHUS-P T=%-2d : %6d patterns | %s%n", T, a.getPatternCount(), ok ? "match" : "MISMATCH");
        }

        boolean all = eq1 && subset && stable;
        System.out.println();
        System.out.println(all ? "PASS: AHUS-P produces complete HUSP, RHUSP is a subset, result is thread-count invariant."
                               : "FAIL: at least one check failed -- review output above.");
        if (!all) System.exit(1);
    }

    private static Map<String, Long> utilMap(Map<String, AlgoRHUSPMinerParallel.PatternResult> m) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> e : m.entrySet())
            out.put(e.getKey(), e.getValue().utility);
        return out;
    }

    private static void printDiff(Map<String, Long> ref, Map<String, Long> cur) {
        Set<String> missing = new TreeSet<>(ref.keySet()); missing.removeAll(cur.keySet());
        Set<String> extra = new TreeSet<>(cur.keySet()); extra.removeAll(ref.keySet());
        int sh = 0;
        for (String p : missing) { System.out.println("   MISSING in AHUS-P : " + p + " (util=" + ref.get(p) + ")"); if (++sh >= 5) break; }
        sh = 0;
        for (String p : extra) { System.out.println("   EXTRA in AHUS-P   : " + p + " (util=" + cur.get(p) + ")"); if (++sh >= 5) break; }
        sh = 0;
        for (String p : ref.keySet())
            if (cur.containsKey(p) && !ref.get(p).equals(cur.get(p))) {
                System.out.println("   UTIL MISMATCH: " + p + " prop=" + ref.get(p) + " ahusp=" + cur.get(p));
                if (++sh >= 10) break;
            }
    }
}
