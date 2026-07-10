package algorithms;

import java.io.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Sequential RHUSP (Regular High Utility Sequential Pattern) mining engine with
 * independently togglable pruning tiers sharing a single data structure layout.
 *
 * Pruning tiers:
 *   Tier 0  (always)       : SWU breadth filter + optional regularity filter on items.
 *   Tier 1  (useEUCS)      : EUCS co-occurrence matrix to prune item pairs early.
 *   Tier 2  (boundMode)    : item-level upper bound -- NONE / PEU / LA-PEU (look-ahead).
 *   Tier 3  (useRegPruning): regularity-constraint pruning (anti-monotone) -- item
 *                            filtering at Tier 0, early exit during scan, subtree pruning.
 *
 * Two conditions are always applied regardless of flags (so every configuration returns
 * exactly the same RHUSP set, differing only in speed):
 *   - RHusp node condition:  utility(P) + remainingUtility(P) >= minUtility.
 *   - Final regularity check: maxPeriod(P) <= maxRegularity when outputting a pattern.
 *
 * Baseline RHusp (Ishita et al., Appl Intell 2021) = {useEUCS=false, boundMode=PEU,
 * useRegPruning=true}. Reported runtime includes preprocessing (Tier 0 + EUCS build);
 * preprocessing time is stored separately in preprocessMs for breakdown reporting.
 *
 * Data structure:
 *   - Flat CSR arrays: items/utils/remUtils/posEvent + per-sequence and per-event offsets.
 *   - Projected database: three parallel primitive arrays (sid, last-item pos, prefix util).
 *   - EUCS: open-addressing primitive hash map for item pairs (memory proportional to the
 *     number of co-occurring pairs, not O(n^2) dense matrix), then converted to a
 *     descending-utility adjacency list for early break pruning.
 *   - All utility values use long to avoid overflow on long sequences.
 */
public class AlgoRHUSPMiner {

    public static final int BOUND_NONE = 0;
    public static final int BOUND_PEU = 1;
    public static final int BOUND_LA_PEU = 2;

    // ----- Configuration -----
    public boolean useEUCS = true;
    public int boundMode = BOUND_LA_PEU;
    public boolean useRegPruning = true;
    public String label = "RHUSPM";

    // ----- Statistics -----
    public long startTimestamp, endTimestamp, preprocessMs, maxMemory;
    public long cpuStartNanos, cpuEndNanos;
    public long exploredNodes, candidateCount;
    public long prunedEucs, prunedBound, prunedNodeCond, prunedRegularity;
    public Map<String, PatternResult> finalPatterns = new HashMap<>();

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private volatile boolean samplerRunning = false;
    private Thread memorySampler;

    public static final class PatternResult {
        public final long utility;
        public final int periodicity;
        PatternResult(long u, int p) { utility = u; periodicity = p; }
    }

    // ----- CSR-layout flat arrays (shared, immutable after load) -----
    private int numSeq;
    private int[] seqPosStart;   // numSeq+1: start position offset for each sequence
    private int[] items;         // item id at each global position
    private long[] utils;        // utility at each global position
    private long[] remUtils;     // remaining utility immediately after each position (within seq)
    private int[] posEvent;      // local event index for each position
    private int[] seqEvtStart;   // numSeq+1: start event offset for each sequence
    private int[] evtPosStart;   // totalEvents+1: start position of each event
    private long[] seqUtility;   // total utility of each sequence
    private int maxItemId, maxEventsPerSeq;
    private long totalDBUtility;

    private long minUtility;
    private int maxRegularity;

    // Optional absolute thresholds (for subset-scaling experiments: fix the same absolute
    // threshold across all subsets so pattern counts are monotone in |D|). Negative means
    // compute from ratio as usual.
    public long forcedMinUtil = -1;
    public int forcedMaxReg = -1;

    // ----- Tier 0 / EUCS structures -----
    private int[] itemToDense;   // -1 if item is not in the promising set
    private int[] denseToItem;
    private int numValid;
    private int[] adjIStart, adjSStart;     // numValid+1: adjacency list offsets
    private int[] adjIItem, adjSItem;       // raw item ids of EUCS neighbours
    private long[] adjIUtil, adjSUtil;      // EUCS accumulated utility (descending within slice)

    // ----- Reusable per-call buffers (avoid allocation in the hot recursion) -----
    private long[] seqBoundI, seqBoundS;    // per-sequence candidate bounds
    private int[] seqVisitI, seqVisitS;     // stamp == sid; reset to -1 on flush
    private int[] seqActI, seqActS;
    private int seqNumI, seqNumS;
    private long[] gAccI, gAccS;            // global accumulated bounds (sum over sequences)
    private int[] gMarkI, gMarkS;           // -1 = not yet accumulated this call
    private int[] gActI, gActS;
    private int gNumI, gNumS;
    private long[] evtMaxUtil;              // best utility of candidate per event
    private int[] evtMaxPos;
    private int[] candBufI, candBufS;
    private int[] laStampArr;               // marks items valid for LA-PEU look-ahead
    private int laStampCounter;
    private static final int ROOT_STAMP = 1;
    private int[] patBuf = new int[64];     // current pattern; -1 separates events
    private int patLen = 0;

    public void runAlgorithm(String dataPath, String utilPath, String outputFolder,
                             String datasetName, double minRatio, double maxRegRatio) throws IOException {
        resetState();
        loadData(dataPath, utilPath, minRatio, maxRegRatio);
        allocateBuffers();
        MemMeter.reset(); // GC + reset peak marker for this run

        startTimestamp = System.currentTimeMillis();
        cpuStartNanos = THREAD_MX.isCurrentThreadCpuTimeSupported()
                ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
        checkMemory();

        long t0 = System.currentTimeMillis();
        boolean needTier0 = useEUCS || boundMode == BOUND_LA_PEU;
        if (needTier0) {
            tier0BuildValidItems();
            if (boundMode == BOUND_LA_PEU) {
                laStampCounter = ROOT_STAMP;
                for (int k = 0; k < numValid; k++) laStampArr[denseToItem[k]] = ROOT_STAMP;
            }
            if (useEUCS) buildEUCS();
        }
        preprocessMs = System.currentTimeMillis() - t0;
        checkMemory();

        try {
            InstanceList init = new InstanceList(numSeq);
            for (int s = 0; s < numSeq; s++) init.add(s, -1, 0L);
            expand(init, -1);
        } finally {
            endTimestamp = System.currentTimeMillis();
            cpuEndNanos = THREAD_MX.isCurrentThreadCpuTimeSupported()
                    ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
            maxMemory = MemMeter.peakBytes();
        }

        File folder = new File(outputFolder);
        if (!folder.exists()) folder.mkdirs();
        String fileName = String.format("%s_%s_su%s_reg%s_patterns.txt",
                label, datasetName, trimNum(minRatio), trimNum(maxRegRatio));
        savePatternsToFile(outputFolder + File.separator + fileName);
    }

    private void resetState() {
        stopMemorySampler();
        finalPatterns.clear();
        maxMemory = 0; exploredNodes = 0; candidateCount = 0;
        cpuStartNanos = cpuEndNanos = -1L;
        prunedEucs = 0; prunedBound = 0; prunedNodeCond = 0; prunedRegularity = 0;
        preprocessMs = 0; maxItemId = 0; maxEventsPerSeq = 0; totalDBUtility = 0;
        numValid = 0; patLen = 0; laStampCounter = ROOT_STAMP;
        adjIStart = adjSStart = null; adjIItem = adjSItem = null; adjIUtil = adjSUtil = null;
        itemToDense = null; denseToItem = null;
    }

    // ============================ TIER 0 ============================

    /** Single-pass scan: TWU + per-item max period. Regularity filter applied only when useRegPruning. */
    private void tier0BuildValidItems() {
        long[] twu = new long[maxItemId + 1];
        int[] lastSid = new int[maxItemId + 1]; Arrays.fill(lastSid, -1);
        int[] maxPer = new int[maxItemId + 1];
        int[] seen = new int[maxItemId + 1]; Arrays.fill(seen, -1);

        for (int s = 0; s < numSeq; s++) {
            long su = seqUtility[s];
            for (int p = seqPosStart[s]; p < seqPosStart[s + 1]; p++) {
                int it = items[p];
                if (seen[it] != s) {
                    seen[it] = s;
                    twu[it] += su;
                    int per = (lastSid[it] == -1) ? (s + 1) : (s - lastSid[it]);
                    if (per > maxPer[it]) maxPer[it] = per;
                    lastSid[it] = s;
                }
            }
            if ((s & 1023) == 0) checkMemory();
        }

        itemToDense = new int[maxItemId + 1]; Arrays.fill(itemToDense, -1);
        int[] tmp = new int[maxItemId + 1];
        int cnt = 0;
        for (int it = 0; it <= maxItemId; it++) {
            if (lastSid[it] == -1 || twu[it] < minUtility) continue;
            if (useRegPruning) {
                int mp = Math.max(maxPer[it], numSeq - lastSid[it]);
                if (mp > maxRegularity) continue;
            }
            itemToDense[it] = cnt; tmp[cnt++] = it;
        }
        denseToItem = Arrays.copyOf(tmp, cnt);
        numValid = cnt;
    }

    // ============================ EUCS (TIER 1) ============================

    /**
     * Open-addressing primitive hash map for item pairs. Memory is proportional to
     * the number of actually co-occurring pairs rather than O(numValid^2), which avoids
     * OOM on high-cardinality datasets. Deduplication per sequence via a lastSid stamp.
     */
    private static final class PairAccMap {
        long[] keys; long[] acc; int[] last; int size;
        PairAccMap(int cap) {
            int c = Integer.highestOneBit(Math.max(cap, 16) * 2 - 1);
            keys = new long[c]; Arrays.fill(keys, -1L);
            acc = new long[c]; last = new int[c];
        }
        void add(long key, int sid, long su) {
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (true) {
                long k = keys[i];
                if (k == key) { if (last[i] != sid) { last[i] = sid; acc[i] += su; } return; }
                if (k == -1L) {
                    keys[i] = key; last[i] = sid; acc[i] = su; size++;
                    if (size * 5L > keys.length * 3L) grow();
                    return;
                }
                i = (i + 1) & mask;
            }
        }
        private static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
        private void grow() {
            long[] ok = keys; long[] oa = acc; int[] ol = last;
            int c = keys.length << 1; int mask = c - 1;
            keys = new long[c]; Arrays.fill(keys, -1L);
            acc = new long[c]; last = new int[c];
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] == -1L) continue;
                int i = hash(ok[j]) & mask;
                while (keys[i] != -1L) i = (i + 1) & mask;
                keys[i] = ok[j]; acc[i] = oa[j]; last[i] = ol[j];
            }
        }
    }

    private static long pack(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    private void buildEUCS() {
        PairAccMap mapI = new PairAccMap(1 << 16);
        PairAccMap mapS = new PairAccMap(1 << 16);
        int[] seenBefore = new int[numValid];
        int[] seenStamp = new int[Math.max(numValid, 1)]; Arrays.fill(seenStamp, -1);
        int[] evtBuf = new int[maxItemId + 1];

        for (int s = 0; s < numSeq; s++) {
            long su = seqUtility[s];
            int nb = 0;
            for (int e = seqEvtStart[s]; e < seqEvtStart[s + 1]; e++) {
                int m = 0;
                for (int p = evtPosStart[e]; p < evtPosStart[e + 1]; p++) {
                    int d = itemToDense[items[p]];
                    if (d >= 0) evtBuf[m++] = d;
                }
                // I-pairs: same event (unordered; canonical key a < b)
                for (int x = 0; x < m; x++)
                    for (int y = x + 1; y < m; y++) {
                        int a = evtBuf[x], b = evtBuf[y];
                        if (a == b) continue;
                        mapI.add(a < b ? pack(a, b) : pack(b, a), s, su);
                    }
                // S-pairs: item u in earlier event -> item v in current event (ordered)
                for (int y = 0; y < m; y++) {
                    int v = evtBuf[y];
                    for (int k = 0; k < nb; k++) mapS.add(pack(seenBefore[k], v), s, su);
                }
                // Update seenBefore AFTER processing S-pairs (items in the same event do not form S-pairs)
                for (int y = 0; y < m; y++) {
                    int v = evtBuf[y];
                    if (seenStamp[v] != s) { seenStamp[v] = s; seenBefore[nb++] = v; }
                }
            }
            if ((s & 255) == 0) checkMemory();
        }

        // Convert hash maps to adjacency lists: I is symmetric (stored in both directions),
        // S is directed. Lists are sorted descending by utility for early-break pruning.
        int[] cntI = new int[numValid + 1];
        int[] cntS = new int[numValid + 1];
        for (int j = 0; j < mapI.keys.length; j++)
            if (mapI.keys[j] != -1L) { cntI[(int) (mapI.keys[j] >>> 32)]++; cntI[(int) mapI.keys[j]]++; }
        for (int j = 0; j < mapS.keys.length; j++)
            if (mapS.keys[j] != -1L) cntS[(int) (mapS.keys[j] >>> 32)]++;

        adjIStart = prefixSum(cntI); adjSStart = prefixSum(cntS);
        adjIItem = new int[adjIStart[numValid]]; adjIUtil = new long[adjIStart[numValid]];
        adjSItem = new int[adjSStart[numValid]]; adjSUtil = new long[adjSStart[numValid]];
        int[] curI = Arrays.copyOf(adjIStart, numValid);
        int[] curS = Arrays.copyOf(adjSStart, numValid);
        for (int j = 0; j < mapI.keys.length; j++) {
            if (mapI.keys[j] == -1L) continue;
            int a = (int) (mapI.keys[j] >>> 32), b = (int) mapI.keys[j];
            long u = mapI.acc[j];
            adjIItem[curI[a]] = denseToItem[b]; adjIUtil[curI[a]++] = u;
            adjIItem[curI[b]] = denseToItem[a]; adjIUtil[curI[b]++] = u;
        }
        for (int j = 0; j < mapS.keys.length; j++) {
            if (mapS.keys[j] == -1L) continue;
            int a = (int) (mapS.keys[j] >>> 32), b = (int) mapS.keys[j];
            adjSItem[curS[a]] = denseToItem[b]; adjSUtil[curS[a]++] = mapS.acc[j];
        }
        for (int d = 0; d < numValid; d++) {
            sortDescByUtil(adjIItem, adjIUtil, adjIStart[d], adjIStart[d + 1] - 1);
            sortDescByUtil(adjSItem, adjSUtil, adjSStart[d], adjSStart[d + 1] - 1);
        }
        checkMemory();
    }

    private static int[] prefixSum(int[] cnt) {
        int[] out = new int[cnt.length];
        int acc = 0;
        for (int i = 0; i < cnt.length; i++) { out[i] = acc; acc += cnt[i]; }
        return out;
    }

    /** In-place descending sort by utility on parallel item/utility arrays. */
    private static void sortDescByUtil(int[] it, long[] ut, int lo, int hi) {
        while (lo < hi) {
            long pivot = ut[lo + (hi - lo) / 2];
            int i = lo, j = hi;
            while (i <= j) {
                while (ut[i] > pivot) i++;
                while (ut[j] < pivot) j--;
                if (i <= j) {
                    long tu = ut[i]; ut[i] = ut[j]; ut[j] = tu;
                    int ti = it[i]; it[i] = it[j]; it[j] = ti;
                    i++; j--;
                }
            }
            if (j - lo < hi - i) { sortDescByUtil(it, ut, lo, j); lo = i; }
            else { sortDescByUtil(it, ut, i, hi); hi = j; }
        }
    }

    // ============================ MINING ============================

    /** Projected database: parallel primitive arrays, doubled on overflow. */
    private static final class InstanceList {
        int[] sid; int[] pos; long[] pref; int size;
        InstanceList(int cap) {
            cap = Math.max(cap, 64);
            sid = new int[cap]; pos = new int[cap]; pref = new long[cap];
        }
        void add(int s, int p, long u) {
            if (size == sid.length) {
                int n = size << 1;
                sid = Arrays.copyOf(sid, n); pos = Arrays.copyOf(pos, n); pref = Arrays.copyOf(pref, n);
            }
            sid[size] = s; pos[size] = p; pref[size] = u; size++;
        }
    }

    /**
     * Extend the current pattern with one candidate item, compute utility and period,
     * record the pattern if it qualifies, and recurse.
     */
    private void processExtension(InstanceList parent, int candidate, boolean isIExt) {
        exploredNodes++;
        checkMemory();
        InstanceList next = new InstanceList(256);
        long totalUtil = 0, totalRU = 0;
        int lastSid = -1, maxPeriod = 0;

        int i = 0;
        while (i < parent.size) {
            int s = parent.sid[i];
            if (useRegPruning) {
                // Anti-monotone early exit: gap to current sequence already exceeds maxRegularity
                int gap = (lastSid == -1) ? (s + 1) : (s - lastSid);
                if (gap > maxRegularity) { prunedRegularity++; return; }
            }
            int start = i;
            while (i < parent.size && parent.sid[i] == s) i++;
            int end = i;

            int evBase = seqEvtStart[s];
            int nEvents = seqEvtStart[s + 1] - evBase;
            for (int e = 0; e < nEvents; e++) evtMaxUtil[e] = -1L;
            boolean found = false;
            long maxUtilSeq = 0, ruFirst = 0;
            boolean first = true;

            for (int k = start; k < end; k++) {
                int p = parent.pos[k];
                long pref = parent.pref[k];
                int from, to;
                if (isIExt) {
                    if (p < 0) continue;
                    from = p + 1; to = evtPosStart[evBase + posEvent[p] + 1];
                } else {
                    from = (p < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[p] + 1];
                    to = seqPosStart[s + 1];
                }
                for (int q = from; q < to; q++) {
                    if (items[q] == candidate) {
                        long nu = pref + utils[q];
                        int e = posEvent[q];
                        if (nu > evtMaxUtil[e]) {
                            evtMaxUtil[e] = nu; evtMaxPos[e] = q; found = true;
                            if (nu > maxUtilSeq) maxUtilSeq = nu;
                            // Earliest match gives largest remUtils -- valid upper bound
                            if (first) { ruFirst = remUtils[q]; first = false; }
                        }
                        if (isIExt) break;
                        q = evtPosStart[evBase + e + 1] - 1;
                    }
                }
            }
            if (found) {
                for (int e = 0; e < nEvents; e++)
                    if (evtMaxUtil[e] >= 0) next.add(s, evtMaxPos[e], evtMaxUtil[e]);
                totalUtil += maxUtilSeq; totalRU += ruFirst;
                int per = (lastSid == -1) ? (s + 1) : (s - lastSid);
                if (per > maxPeriod) maxPeriod = per;
                lastSid = s;
            }
        }

        if (lastSid == -1) return;
        int finalPer = numSeq - lastSid;
        if (useRegPruning && finalPer > maxRegularity) { prunedRegularity++; return; }
        if (finalPer > maxPeriod) maxPeriod = finalPer;
        boolean isRegular = maxPeriod <= maxRegularity;

        pushPattern(candidate, isIExt);
        try {
            // Regularity check at pattern output is always applied (not gated by Tier 3 flag)
            if (isRegular && totalUtil >= minUtility)
                finalPatterns.put(formatCurrentPattern(), new PatternResult(totalUtil, maxPeriod));
            // RHusp node condition is always applied (baseline requirement)
            if (totalUtil + totalRU < minUtility) { prunedNodeCond++; return; }
            // Subtree pruning by regularity anti-monotone property (Tier 3)
            if (useRegPruning && !isRegular) { prunedRegularity++; return; }
            expand(next, candidate);
        } finally {
            popPattern(isIExt);
        }
    }

    /** Generate and filter candidates for the next level, then recurse. lastItem == -1 at root. */
    private void expand(InstanceList projected, int lastItem) {
        if (projected.size == 0) return;

        int laStamp = 0;
        if (boundMode == BOUND_LA_PEU) {
            if (useEUCS && lastItem >= 0) {
                // Promising set for look-ahead = EUCS neighbours of lastItem above minUtility
                laStamp = ++laStampCounter;
                int d = itemToDense[lastItem];
                if (d >= 0) {
                    for (int k = adjIStart[d]; k < adjIStart[d + 1] && adjIUtil[k] >= minUtility; k++)
                        laStampArr[adjIItem[k]] = laStamp;
                    for (int k = adjSStart[d]; k < adjSStart[d + 1] && adjSUtil[k] >= minUtility; k++)
                        laStampArr[adjSItem[k]] = laStamp;
                }
            } else {
                laStamp = ROOT_STAMP; // fall back to full Tier 0 promising set
            }
        }

        if (boundMode == BOUND_LA_PEU) computeBoundsLA(projected, laStamp);
        else computeBoundsPEU(projected);

        int nI = collectCandidates(lastItem, true);
        int nS = collectCandidates(lastItem, false);
        resetGlobalAccumulators();

        // Copy candidate arrays before recursing because candBuf is shared across levels
        int[] cI = Arrays.copyOf(candBufI, nI);
        int[] cS = Arrays.copyOf(candBufS, nS);
        for (int it : cI) processExtension(projected, it, true);
        for (int it : cS) processExtension(projected, it, false);
    }

    // ============================ UPPER BOUNDS (TIER 2) ============================

    /**
     * Standard PEU bound: bound(x) = maxPrefixUtil(seq) + u(x at pos) + remUtils(pos).
     * For the S-extension region we scan only from the earliest instance's next event
     * (it covers all positions reachable by later instances), avoiding repeated scans.
     */
    private void computeBoundsPEU(InstanceList plist) {
        int i = 0;
        while (i < plist.size) {
            int s = plist.sid[i];
            int start = i;
            long maxPref = plist.pref[i];
            while (i < plist.size && plist.sid[i] == s) {
                if (plist.pref[i] > maxPref) maxPref = plist.pref[i];
                i++;
            }
            int end = i;
            int evBase = seqEvtStart[s];
            int seqEnd = seqPosStart[s + 1];

            // I-extension region: remainder of the event containing each instance
            for (int k = start; k < end; k++) {
                int p = plist.pos[k];
                if (p < 0) continue;
                int evEnd = evtPosStart[evBase + posEvent[p] + 1];
                for (int q = p + 1; q < evEnd; q++) {
                    int it = items[q];
                    long b = maxPref + utils[q] + remUtils[q];
                    if (seqVisitI[it] != s) { seqVisitI[it] = s; seqActI[seqNumI++] = it; seqBoundI[it] = b; }
                    else if (b > seqBoundI[it]) seqBoundI[it] = b;
                }
            }
            // S-extension region: from first instance's next event to end of sequence
            int pMin = plist.pos[start];
            int from = (pMin < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[pMin] + 1];
            for (int q = from; q < seqEnd; q++) {
                int it = items[q];
                long b = maxPref + utils[q] + remUtils[q];
                if (seqVisitS[it] != s) { seqVisitS[it] = s; seqActS[seqNumS++] = it; seqBoundS[it] = b; }
                else if (b > seqBoundS[it]) seqBoundS[it] = b;
            }
            flushSeqBuffers(s);
        }
    }

    /**
     * LA-PEU bound: scan backward from the sequence end, accumulating LAR (look-ahead
     * remaining utility) = sum of utilities of promising items (stamped by laStamp) seen
     * so far. bound(x) = prefixUtil(instance) + u(x) + LAR. Tighter than PEU because
     * items certain not to contribute to any extension above minUtility are excluded.
     */
    private void computeBoundsLA(InstanceList plist, int laStamp) {
        int i = 0;
        while (i < plist.size) {
            int s = plist.sid[i];
            int start = i;
            while (i < plist.size && plist.sid[i] == s) i++;
            int end = i;
            int evBase = seqEvtStart[s];
            int seqEnd = seqPosStart[s + 1];

            for (int k = start; k < end; k++) {
                int p = plist.pos[k];
                long pref = plist.pref[k];
                long lar = 0;
                int sFrom = (p < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[p] + 1];
                for (int q = seqEnd - 1; q >= sFrom; q--) {
                    int it = items[q];
                    long b = pref + utils[q] + lar;
                    if (seqVisitS[it] != s) { seqVisitS[it] = s; seqActS[seqNumS++] = it; seqBoundS[it] = b; }
                    else if (b > seqBoundS[it]) seqBoundS[it] = b;
                    if (laStampArr[it] == laStamp) lar += utils[q];
                }
                if (p >= 0) {
                    for (int q = sFrom - 1; q > p; q--) {
                        int it = items[q];
                        long b = pref + utils[q] + lar;
                        if (seqVisitI[it] != s) { seqVisitI[it] = s; seqActI[seqNumI++] = it; seqBoundI[it] = b; }
                        else if (b > seqBoundI[it]) seqBoundI[it] = b;
                        if (laStampArr[it] == laStamp) lar += utils[q];
                    }
                }
            }
            flushSeqBuffers(s);
        }
    }

    /** Merge per-sequence accumulators into global totals; reset per-sequence stamps. */
    private void flushSeqBuffers(int s) {
        for (int k = 0; k < seqNumI; k++) {
            int it = seqActI[k];
            if (gMarkI[it] == -1) { gMarkI[it] = 1; gActI[gNumI++] = it; }
            gAccI[it] += seqBoundI[it];
            seqVisitI[it] = -1;
        }
        seqNumI = 0;
        for (int k = 0; k < seqNumS; k++) {
            int it = seqActS[k];
            if (gMarkS[it] == -1) { gMarkS[it] = 1; gActS[gNumS++] = it; }
            gAccS[it] += seqBoundS[it];
            seqVisitS[it] = -1;
        }
        seqNumS = 0;
    }

    private void resetGlobalAccumulators() {
        for (int k = 0; k < gNumI; k++) { int it = gActI[k]; gAccI[it] = 0; gMarkI[it] = -1; }
        gNumI = 0;
        for (int k = 0; k < gNumS; k++) { int it = gActS[k]; gAccS[it] = 0; gMarkS[it] = -1; }
        gNumS = 0;
    }

    /** Filter candidates through Tier 1 (EUCS) then Tier 2 (bound). Returns count in candBuf. */
    private int collectCandidates(int lastItem, boolean iExt) {
        int[] out = iExt ? candBufI : candBufS;
        long[] acc = iExt ? gAccI : gAccS;
        int[] mark = iExt ? gMarkI : gMarkS;
        int n = 0;

        if (useEUCS && lastItem >= 0) {
            int d = itemToDense[lastItem];
            if (d < 0) return 0;
            int[] adjStart = iExt ? adjIStart : adjSStart;
            int[] adjItem = iExt ? adjIItem : adjSItem;
            long[] adjUtil = iExt ? adjIUtil : adjSUtil;
            for (int k = adjStart[d]; k < adjStart[d + 1]; k++) {
                candidateCount++;
                if (adjUtil[k] < minUtility) {
                    // Adjacency list is descending: all remaining entries are also pruned
                    int remaining = adjStart[d + 1] - k;
                    prunedEucs += remaining;
                    candidateCount += remaining - 1;
                    break;
                }
                int it = adjItem[k];
                long bound = (mark[it] != -1) ? acc[it] : 0;
                if (boundMode != BOUND_NONE && bound < minUtility) prunedBound++;
                else out[n++] = it;
            }
        } else {
            int[] act = iExt ? gActI : gActS;
            int cnt = iExt ? gNumI : gNumS;
            for (int k = 0; k < cnt; k++) {
                int it = act[k];
                candidateCount++;
                if (boundMode != BOUND_NONE && acc[it] < minUtility) prunedBound++;
                else out[n++] = it;
            }
            Arrays.sort(out, 0, n); // deterministic exploration order
        }
        return n;
    }

    // ============================ PATTERN BUFFER ============================

    private void pushPattern(int item, boolean iExt) {
        if (patLen + 2 > patBuf.length) patBuf = Arrays.copyOf(patBuf, patBuf.length * 2);
        if (!iExt && patLen > 0) patBuf[patLen++] = -1;
        patBuf[patLen++] = item;
    }

    private void popPattern(boolean iExt) {
        patLen--;
        if (!iExt && patLen > 0) patLen--; // remove event separator
    }

    private String formatCurrentPattern() {
        StringBuilder sb = new StringBuilder("<(");
        for (int k = 0; k < patLen; k++) {
            if (patBuf[k] == -1) sb.append(")(");
            else {
                if (k > 0 && patBuf[k - 1] != -1) sb.append(' ');
                sb.append(patBuf[k]);
            }
        }
        return sb.append(")>").toString();
    }

    // ============================ DATA LOADING ============================

    private void loadData(String dataPath, String utilPath, double minRatio, double maxRegRatio) throws IOException {
        Map<Integer, Integer> externalUtility = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(utilPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":");
                if (parts.length >= 2)
                    externalUtility.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            }
        }

        List<int[]> seqItemsList = new ArrayList<>();
        List<long[]> seqUtilsList = new ArrayList<>();
        List<int[]> seqEvtLenList = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(dataPath))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                if ((++lineNo & 4095) == 0) checkMemory();
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] eventStrs = line.split("-1");
                int[] tmpItems = new int[64]; long[] tmpUtils = new long[64];
                int[] tmpEvtLen = new int[16];
                int nPos = 0, nEvt = 0;
                for (String eventStr : eventStrs) {
                    eventStr = eventStr.replace("-2", "").trim();
                    if (eventStr.isEmpty()) continue;
                    String[] tokens = eventStr.split("\\s+");
                    int evStart = nPos;
                    for (String tk : tokens) {
                        int open = tk.indexOf('['), close = tk.indexOf(']');
                        if (open == -1 || close == -1) continue;
                        try {
                            int itemId = Integer.parseInt(tk.substring(0, open).trim());
                            int qty = Integer.parseInt(tk.substring(open + 1, close).trim());
                            long u = (long) qty * externalUtility.getOrDefault(itemId, 0);
                            if (itemId > maxItemId) maxItemId = itemId;
                            if (nPos == tmpItems.length) {
                                tmpItems = Arrays.copyOf(tmpItems, nPos * 2);
                                tmpUtils = Arrays.copyOf(tmpUtils, nPos * 2);
                            }
                            tmpItems[nPos] = itemId; tmpUtils[nPos] = u; nPos++;
                        } catch (NumberFormatException ignored) {}
                    }
                    int evLen = nPos - evStart;
                    if (evLen > 0) {
                        // Sort items within each event ascending for canonical I-extension order
                        insertionSortPaired(tmpItems, tmpUtils, evStart, nPos - 1);
                        if (nEvt == tmpEvtLen.length) tmpEvtLen = Arrays.copyOf(tmpEvtLen, nEvt * 2);
                        tmpEvtLen[nEvt++] = evLen;
                    }
                }
                if (nPos > 0) {
                    seqItemsList.add(Arrays.copyOf(tmpItems, nPos));
                    seqUtilsList.add(Arrays.copyOf(tmpUtils, nPos));
                    seqEvtLenList.add(Arrays.copyOf(tmpEvtLen, nEvt));
                }
            }
        }

        // Flatten to CSR arrays
        numSeq = seqItemsList.size();
        int totalPos = 0, totalEvt = 0;
        for (int s = 0; s < numSeq; s++) {
            totalPos += seqItemsList.get(s).length;
            totalEvt += seqEvtLenList.get(s).length;
            maxEventsPerSeq = Math.max(maxEventsPerSeq, seqEvtLenList.get(s).length);
        }
        seqPosStart = new int[numSeq + 1];
        seqEvtStart = new int[numSeq + 1];
        evtPosStart = new int[totalEvt + 1];
        items = new int[totalPos];
        utils = new long[totalPos];
        remUtils = new long[totalPos];
        posEvent = new int[totalPos];
        seqUtility = new long[numSeq];

        int pCur = 0, eCur = 0;
        for (int s = 0; s < numSeq; s++) {
            seqPosStart[s] = pCur; seqEvtStart[s] = eCur;
            int[] its = seqItemsList.get(s);
            long[] uts = seqUtilsList.get(s);
            int[] evl = seqEvtLenList.get(s);
            long su = 0;
            int local = 0, e = 0, inEvt = 0;
            for (int j = 0; j < its.length; j++) {
                if (inEvt == 0) evtPosStart[eCur + e] = pCur + j;
                items[pCur + j] = its[j]; utils[pCur + j] = uts[j];
                posEvent[pCur + j] = e;
                su += uts[j];
                inEvt++;
                if (inEvt == evl[e]) { e++; inEvt = 0; }
                local++;
            }
            // remUtils[j] = total utility of positions j+1 .. end of sequence
            long acc = 0;
            for (int j = its.length - 1; j >= 0; j--) {
                remUtils[pCur + j] = acc;
                acc += uts[j];
            }
            seqUtility[s] = su;
            totalDBUtility += su;
            pCur += local; eCur += evl.length;
        }
        seqPosStart[numSeq] = pCur;
        seqEvtStart[numSeq] = eCur;
        evtPosStart[totalEvt] = pCur;

        minUtility = (forcedMinUtil >= 0) ? forcedMinUtil : (long) Math.ceil(totalDBUtility * minRatio);
        maxRegularity = (forcedMaxReg >= 0) ? forcedMaxReg : (int) (numSeq * maxRegRatio);
    }

    private static void insertionSortPaired(int[] keys, long[] vals, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
            int k = keys[i]; long v = vals[i];
            int j = i - 1;
            while (j >= lo && keys[j] > k) { keys[j + 1] = keys[j]; vals[j + 1] = vals[j]; j--; }
            keys[j + 1] = k; vals[j + 1] = v;
        }
    }

    private void allocateBuffers() {
        int n = maxItemId + 1;
        seqBoundI = new long[n]; seqBoundS = new long[n];
        seqVisitI = new int[n]; Arrays.fill(seqVisitI, -1);
        seqVisitS = new int[n]; Arrays.fill(seqVisitS, -1);
        seqActI = new int[n]; seqActS = new int[n];
        gAccI = new long[n]; gAccS = new long[n];
        gMarkI = new int[n]; Arrays.fill(gMarkI, -1);
        gMarkS = new int[n]; Arrays.fill(gMarkS, -1);
        gActI = new int[n]; gActS = new int[n];
        candBufI = new int[n]; candBufS = new int[n];
        laStampArr = new int[n];
        evtMaxUtil = new long[Math.max(maxEventsPerSeq, 1)];
        evtMaxPos = new int[Math.max(maxEventsPerSeq, 1)];
        seqNumI = seqNumS = gNumI = gNumS = 0;
    }

    // ============================ UTILITIES ============================

    private void savePatternsToFile(String path) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            for (Map.Entry<String, PatternResult> e : finalPatterns.entrySet())
                w.write(e.getKey() + " #UTIL: " + e.getValue().utility + " #PER: " + e.getValue().periodicity + "\n");
        }
    }

    private static String trimNum(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Check for thread interruption or timeout; memory tracking is handled by MemMeter. */
    private void checkMemory() {
        if (Thread.currentThread().isInterrupted()) throw new RuntimeException("TIMEOUT_INTERRUPT");
    }

    private void startMemorySampler() {
        stopMemorySampler();
        samplerRunning = true;
        memorySampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (samplerRunning) {
                long cur = rt.totalMemory() - rt.freeMemory();
                if (cur > maxMemory) maxMemory = cur;
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }, "rhuspm-mem-sampler");
        memorySampler.setDaemon(true);
        memorySampler.start();
    }

    private void stopMemorySampler() {
        samplerRunning = false;
        if (memorySampler != null) {
            memorySampler.interrupt();
            try { memorySampler.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            memorySampler = null;
        }
    }

    private void forceGC() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public double getPeakMemoryMB() { return maxMemory / (1024.0 * 1024.0); }
    public int getPatternCount() { return finalPatterns.size(); }
    public long getRuntimeMs() { return endTimestamp - startTimestamp; }
    public long getMinUtility() { return minUtility; }
    public int getMaxRegularity() { return maxRegularity; }
    /** CPU time (ms) of the mining thread; isolates wall-clock noise from sleep/suspend. -1 if unsupported. */
    public long getCpuTimeMs() {
        return (cpuStartNanos < 0 || cpuEndNanos < 0) ? -1L : (cpuEndNanos - cpuStartNanos) / 1_000_000L;
    }

    public String configDescription() {
        return String.format("EUCS=%s, bound=%s, regPruning=%s",
                useEUCS, boundMode == BOUND_NONE ? "NONE" : boundMode == BOUND_PEU ? "PEU" : "LA_PEU", useRegPruning);
    }

    public void printStatistics() {
        System.out.println("\n========== " + label + " (" + configDescription() + ") ==========");
        System.out.println("Patterns found              : " + finalPatterns.size());
        System.out.println("Runtime (ms)                : " + getRuntimeMs() + " (preprocessing: " + preprocessMs + ")");
        System.out.println("Peak heap memory            : " + String.format("%.2f MB", getPeakMemoryMB()));
        System.out.println("Candidates generated        : " + candidateCount);
        System.out.println("Nodes explored              : " + exploredNodes);
        System.out.println("Pruned by EUCS (Tier 1)     : " + prunedEucs);
        System.out.println("Pruned by upper bound (T2)  : " + prunedBound);
        System.out.println("Pruned by node condition    : " + prunedNodeCond);
        System.out.println("Pruned by regularity (T3)   : " + prunedRegularity);
        System.out.println("===========================================================");
    }
}
