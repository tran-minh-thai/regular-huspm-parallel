package algorithms;

import java.io.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * AHUS-P -- parallel baseline for High Utility Sequential Pattern (HUSP) mining
 * without a regularity constraint (adapted from Le et al., 2018, cited as
 * \cite{Le2018} in the paper). This class serves two roles in the experiment suite:
 *   (1) Quantify the overhead of the regularity constraint: compare pattern counts
 *       and runtimes of AHUS-P (full HUSP) vs. the proposed engine (regular HUSP only).
 *   (2) Compare parallel scalability between HUSP and regular-HUSP mining.
 *
 * Differences from AlgoRHUSPMinerParallel:
 *   - No regularity constraint (no maxRegularity, no early-exit or subtree pruning on period).
 *   - No EUCS co-occurrence filter; no LA-PEU look-ahead bound.
 *   - Standard HUSP pruning on flat arrays: (a) SWU breadth filter at Tier 0;
 *     (b) node-level upper bound u(alpha,S) + remaining utility (PEU/RSU condition);
 *     (c) node recursion guard u + ru >= minUtility.
 *   - Immutable shared CSR database; per-task context pool (ConcurrentLinkedQueue<Ctx>)
 *     to handle ForkJoinPool join-helping safely; dynamic load balancing (RDLB) via
 *     conditional forking.
 *
 * Output: the set HUSP (each pattern with #UTIL). At the same (dataset, minUtility),
 * RHUSP produced by AlgoRHUSPMinerParallel is a strict subset of HUSP:
 * RHUSP = { alpha in HUSP | maxPeriod(alpha) <= maxRegularity }.
 */
public class AlgoAHUSP {

    // ----- Configuration -----
    public int numThreads = Runtime.getRuntime().availableProcessors();
    public int forkSurplusThreshold = 3;
    public int minForkInstances = 64;   // only fork when projected database is large enough
    public String label = "AHUS-P";
    public long forcedMinUtil = -1;
    public long timeoutMs = -1;

    // ----- Statistics (thread-safe via LongAdder) -----
    public long startTimestamp, endTimestamp, preprocessMs, maxMemory;
    public long cpuStartNanos, cpuEndNanos;
    private final LongAdder exploredNodesA = new LongAdder();
    private final LongAdder candidateCountA = new LongAdder();
    private final LongAdder prunedBoundA = new LongAdder();
    private final LongAdder prunedNodeCondA = new LongAdder();
    public long exploredNodes, candidateCount, prunedBound, prunedNodeCond;

    public final Map<String, Long> finalPatterns = new ConcurrentHashMap<>(); // pattern -> utility

    private volatile long deadlineMillis = Long.MAX_VALUE;
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private volatile boolean samplerRunning = false;
    private Thread memorySampler;

    // ----- CSR-layout flat arrays (immutable, shared across all threads) -----
    private int numSeq;
    private int[] seqPosStart;
    private int[] items;
    private long[] utils;
    private long[] remUtils;
    private int[] posEvent;
    private int[] seqEvtStart;
    private int[] evtPosStart;
    private long[] seqUtility;
    private int maxItemId, maxEventsPerSeq;
    private long totalDBUtility;
    private long minUtility;

    // ----- Tier 0 (SWU filter only -- no regularity) -----
    private int[] itemToDense;
    private int[] denseToItem;
    private int numValid;

    private ForkJoinPool pool;

    // ============================ PER-TASK CONTEXT ============================

    /**
     * All hot buffers allocated per task. Using a ConcurrentLinkedQueue pool rather than
     * ThreadLocal: ForkJoinPool join-helping can execute a stolen task on the joining thread,
     * which would overwrite ThreadLocal buffers still in use by the outer frame (patLen,
     * gAcc, candBuf, ...), corrupting state. Each task borrows a Ctx at compute() entry and
     * returns it on exit; inline recursion within the same task reuses the borrowed Ctx.
     * Invariant: Ctx is clean (seqVisit=-1, gMark=-1, counts=0, push/pop balanced) after
     * each mine()/processOne() call, so returned Ctx is always ready for reuse.
     */
    private final class Ctx {
        final long[] seqBoundI, seqBoundS;
        final int[] seqVisitI, seqVisitS;
        final int[] seqActI, seqActS;
        int seqNumI, seqNumS;
        final long[] gAccI, gAccS;
        final int[] gMarkI, gMarkS;
        final int[] gActI, gActS;
        int gNumI, gNumS;
        final long[] evtMaxUtil;
        final int[] evtMaxPos;
        final int[] candBufI, candBufS;
        int[] patBuf = new int[64];
        int patLen = 0;

        Ctx() {
            int n = Math.max(numValid, 1); // indexed by dense id (numValid << maxItemId)
            seqBoundI = new long[n]; seqBoundS = new long[n];
            seqVisitI = new int[n]; Arrays.fill(seqVisitI, -1);
            seqVisitS = new int[n]; Arrays.fill(seqVisitS, -1);
            seqActI = new int[n]; seqActS = new int[n];
            gAccI = new long[n]; gAccS = new long[n];
            gMarkI = new int[n]; Arrays.fill(gMarkI, -1);
            gMarkS = new int[n]; Arrays.fill(gMarkS, -1);
            gActI = new int[n]; gActS = new int[n];
            candBufI = new int[n]; candBufS = new int[n];
            evtMaxUtil = new long[Math.max(maxEventsPerSeq, 1)];
            evtMaxPos = new int[Math.max(maxEventsPerSeq, 1)];
        }
    }

    private final ConcurrentLinkedQueue<Ctx> ctxPool = new ConcurrentLinkedQueue<>();
    private Ctx acquireCtx() { Ctx c = ctxPool.poll(); return (c != null) ? c : new Ctx(); }
    private void releaseCtx(Ctx c) { c.patLen = 0; ctxPool.offer(c); }

    // ============================ ENTRY POINT ============================

    public void runAlgorithm(String dataPath, String utilPath, String outputFolder,
                             String datasetName, double minRatio) throws IOException {
        resetState();
        loadData(dataPath, utilPath, minRatio);
        MemMeter.reset(); // GC + reset peak marker for this run

        startTimestamp = System.currentTimeMillis();
        deadlineMillis = (timeoutMs > 0) ? startTimestamp + timeoutMs : Long.MAX_VALUE;
        cpuStartNanos = THREAD_MX.isCurrentThreadCpuTimeSupported() ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
        checkMemory();

        pool = new ForkJoinPool(Math.max(1, numThreads));
        try {
            long t0 = System.currentTimeMillis();
            tier0BuildValidItemsParallel();
            preprocessMs = System.currentTimeMillis() - t0;
            checkMemory();

            InstanceList init = new InstanceList(numSeq);
            for (int s = 0; s < numSeq; s++) init.add(s, -1, 0L);
            pool.invoke(new RootTask(init));
        } finally {
            shutdownPool(pool);
            endTimestamp = System.currentTimeMillis();
            cpuEndNanos = THREAD_MX.isCurrentThreadCpuTimeSupported() ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
            maxMemory = MemMeter.peakBytes();
        }

        exploredNodes = exploredNodesA.sum();
        candidateCount = candidateCountA.sum();
        prunedBound = prunedBoundA.sum();
        prunedNodeCond = prunedNodeCondA.sum();

        File folder = new File(outputFolder);
        if (!folder.exists()) folder.mkdirs();
        String fileName = String.format("%s_%s_su%s_patterns.txt", label, datasetName, trimNum(minRatio));
        savePatternsToFile(outputFolder + File.separator + fileName);
    }

    private void resetState() {
        stopMemorySampler();
        finalPatterns.clear();
        maxMemory = 0;
        exploredNodesA.reset(); candidateCountA.reset(); prunedBoundA.reset(); prunedNodeCondA.reset();
        exploredNodes = candidateCount = prunedBound = prunedNodeCond = 0;
        cpuStartNanos = cpuEndNanos = -1L;
        preprocessMs = 0; maxItemId = 0; maxEventsPerSeq = 0; totalDBUtility = 0; numValid = 0;
        itemToDense = null; denseToItem = null;
        ctxPool.clear();
    }

    // ============================ TIER 0 (SWU only) ============================

    private void tier0BuildValidItemsParallel() {
        final int n = maxItemId + 1;
        final int T = Math.max(1, numThreads);
        final int chunk = (numSeq + T - 1) / T;
        final long[][] twuL = new long[T][];
        final boolean[][] seenAnyL = new boolean[T][];

        ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[T];
        for (int t = 0; t < T; t++) {
            final int tid = t;
            final int lo = Math.min(numSeq, tid * chunk);
            final int hi = Math.min(numSeq, lo + chunk);
            tasks[t] = pool.submit(() -> {
                long[] twu = new long[n];
                boolean[] seenAny = new boolean[n];
                int[] seen = new int[n]; Arrays.fill(seen, -1);
                for (int s = lo; s < hi; s++) {
                    long su = seqUtility[s];
                    for (int p = seqPosStart[s]; p < seqPosStart[s + 1]; p++) {
                        int it = items[p];
                        if (seen[it] != s) { seen[it] = s; twu[it] += su; seenAny[it] = true; }
                    }
                }
                twuL[tid] = twu; seenAnyL[tid] = seenAny;
            });
        }
        for (ForkJoinTask<?> tk : tasks) tk.join();
        checkMemory();

        long[] twu = new long[n];
        boolean[] seenAny = new boolean[n];
        for (int t = 0; t < T; t++) {
            if (twuL[t] == null) continue;
            for (int it = 0; it < n; it++) { twu[it] += twuL[t][it]; seenAny[it] |= seenAnyL[t][it]; }
        }

        itemToDense = new int[n]; Arrays.fill(itemToDense, -1);
        int[] tmp = new int[n];
        int cnt = 0;
        for (int it = 0; it < n; it++) {
            if (!seenAny[it] || twu[it] < minUtility) continue;
            itemToDense[it] = cnt; tmp[cnt++] = it;
        }
        denseToItem = Arrays.copyOf(tmp, cnt);
        numValid = cnt;
    }

    // ============================ MINING ============================

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

    private final class RootTask extends RecursiveAction {
        final InstanceList init;
        RootTask(InstanceList init) { this.init = init; }
        @Override protected void compute() {
            Ctx ctx = acquireCtx();
            try { ctx.patLen = 0; mine(ctx, init); }
            finally { releaseCtx(ctx); }
        }
    }

    private final class MineTask extends RecursiveAction {
        final InstanceList parent;
        final int candidate;
        final boolean isIExt;
        final int[] prefix;
        final int prefixLen;
        MineTask(InstanceList parent, int candidate, boolean isIExt, int[] prefix, int prefixLen) {
            this.parent = parent; this.candidate = candidate; this.isIExt = isIExt;
            this.prefix = prefix; this.prefixLen = prefixLen;
        }
        @Override protected void compute() {
            Ctx ctx = acquireCtx();
            try {
                if (ctx.patBuf.length < prefixLen + 2) ctx.patBuf = Arrays.copyOf(ctx.patBuf, Math.max(ctx.patBuf.length * 2, prefixLen + 2));
                System.arraycopy(prefix, 0, ctx.patBuf, 0, prefixLen);
                ctx.patLen = prefixLen;
                processOne(ctx, parent, candidate, isIExt);
            } finally { releaseCtx(ctx); }
        }
    }

    /** Fork only when the pool has spare capacity (RDLB adaptive strategy). */
    private boolean shouldFork() { return ForkJoinTask.getSurplusQueuedTaskCount() <= forkSurplusThreshold; }

    private void processOne(Ctx ctx, InstanceList parent, int candidate, boolean isIExt) {
        exploredNodesA.increment();
        checkMemory();
        InstanceList next = new InstanceList(256);
        long totalUtil = 0, totalRU = 0;
        long[] evtMaxUtil = ctx.evtMaxUtil; int[] evtMaxPos = ctx.evtMaxPos;

        int i = 0;
        while (i < parent.size) {
            int s = parent.sid[i];
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
            }
        }

        if (next.size == 0 && totalUtil == 0) return;

        pushPattern(ctx, candidate, isIExt);
        try {
            if (totalUtil >= minUtility) finalPatterns.put(formatCurrentPattern(ctx), totalUtil);
            if (totalUtil + totalRU < minUtility) { prunedNodeCondA.increment(); return; }
            mine(ctx, next);
        } finally {
            popPattern(ctx, isIExt);
        }
    }

    private void mine(Ctx ctx, InstanceList projected) {
        if (projected.size == 0) return;
        computeBoundsPEU(ctx, projected);
        int nI = collectCandidates(ctx, true);
        int nS = collectCandidates(ctx, false);
        resetGlobalAccumulators(ctx);

        int[] cI = Arrays.copyOf(ctx.candBufI, nI);
        int[] cS = Arrays.copyOf(ctx.candBufS, nS);

        boolean canFork = projected.size >= minForkInstances;
        List<MineTask> forked = null;
        for (int idx = 0; idx < cI.length; idx++) {
            if (canFork && shouldFork()) {
                MineTask tk = new MineTask(projected, cI[idx], true, Arrays.copyOf(ctx.patBuf, ctx.patLen), ctx.patLen);
                tk.fork(); if (forked == null) forked = new ArrayList<>(); forked.add(tk);
            } else processOne(ctx, projected, cI[idx], true);
        }
        for (int idx = 0; idx < cS.length; idx++) {
            if (canFork && shouldFork()) {
                MineTask tk = new MineTask(projected, cS[idx], false, Arrays.copyOf(ctx.patBuf, ctx.patLen), ctx.patLen);
                tk.fork(); if (forked == null) forked = new ArrayList<>(); forked.add(tk);
            } else processOne(ctx, projected, cS[idx], false);
        }
        if (forked != null) for (int k = forked.size() - 1; k >= 0; k--) forked.get(k).join();
    }

    // ----- Standard PEU bound: u(prefix) + u(item at pos) + remaining utility -----
    private void computeBoundsPEU(Ctx ctx, InstanceList plist) {
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

            for (int k = start; k < end; k++) {
                int p = plist.pos[k];
                if (p < 0) continue;
                int evEnd = evtPosStart[evBase + posEvent[p] + 1];
                for (int q = p + 1; q < evEnd; q++) {
                    int d = itemToDense[items[q]];
                    if (d < 0) continue; // item outside Tier 0 cannot be a candidate
                    long b = maxPref + utils[q] + remUtils[q];
                    if (ctx.seqVisitI[d] != s) { ctx.seqVisitI[d] = s; ctx.seqActI[ctx.seqNumI++] = d; ctx.seqBoundI[d] = b; }
                    else if (b > ctx.seqBoundI[d]) ctx.seqBoundI[d] = b;
                }
            }
            int pMin = plist.pos[start];
            int from = (pMin < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[pMin] + 1];
            for (int q = from; q < seqEnd; q++) {
                int d = itemToDense[items[q]];
                if (d < 0) continue;
                long b = maxPref + utils[q] + remUtils[q];
                if (ctx.seqVisitS[d] != s) { ctx.seqVisitS[d] = s; ctx.seqActS[ctx.seqNumS++] = d; ctx.seqBoundS[d] = b; }
                else if (b > ctx.seqBoundS[d]) ctx.seqBoundS[d] = b;
            }
            flushSeqBuffers(ctx, s);
        }
    }

    private void flushSeqBuffers(Ctx ctx, int s) {
        for (int k = 0; k < ctx.seqNumI; k++) {
            int it = ctx.seqActI[k];
            if (ctx.gMarkI[it] == -1) { ctx.gMarkI[it] = 1; ctx.gActI[ctx.gNumI++] = it; }
            ctx.gAccI[it] += ctx.seqBoundI[it];
            ctx.seqVisitI[it] = -1;
        }
        ctx.seqNumI = 0;
        for (int k = 0; k < ctx.seqNumS; k++) {
            int it = ctx.seqActS[k];
            if (ctx.gMarkS[it] == -1) { ctx.gMarkS[it] = 1; ctx.gActS[ctx.gNumS++] = it; }
            ctx.gAccS[it] += ctx.seqBoundS[it];
            ctx.seqVisitS[it] = -1;
        }
        ctx.seqNumS = 0;
    }

    private void resetGlobalAccumulators(Ctx ctx) {
        for (int k = 0; k < ctx.gNumI; k++) { int it = ctx.gActI[k]; ctx.gAccI[it] = 0; ctx.gMarkI[it] = -1; }
        ctx.gNumI = 0;
        for (int k = 0; k < ctx.gNumS; k++) { int it = ctx.gActS[k]; ctx.gAccS[it] = 0; ctx.gMarkS[it] = -1; }
        ctx.gNumS = 0;
    }

    /** Filter candidates by PEU bound (>= minUtility); only consider items in the Tier 0 promising set. */
    private int collectCandidates(Ctx ctx, boolean iExt) {
        int[] out = iExt ? ctx.candBufI : ctx.candBufS;
        long[] acc = iExt ? ctx.gAccI : ctx.gAccS;
        int[] act = iExt ? ctx.gActI : ctx.gActS;
        int cnt = iExt ? ctx.gNumI : ctx.gNumS;
        int n = 0;
        for (int k = 0; k < cnt; k++) {
            int dd = act[k];                       // dense id (already in SWU-filtered set)
            candidateCountA.increment();
            if (acc[dd] < minUtility) prunedBoundA.increment();
            else out[n++] = denseToItem[dd];       // emit raw item id for processOne
        }
        Arrays.sort(out, 0, n); // deterministic exploration order
        return n;
    }

    // ----- Pattern buffer -----
    private void pushPattern(Ctx ctx, int item, boolean iExt) {
        if (ctx.patLen + 2 > ctx.patBuf.length) ctx.patBuf = Arrays.copyOf(ctx.patBuf, ctx.patBuf.length * 2);
        if (!iExt && ctx.patLen > 0) ctx.patBuf[ctx.patLen++] = -1;
        ctx.patBuf[ctx.patLen++] = item;
    }
    private void popPattern(Ctx ctx, boolean iExt) {
        ctx.patLen--;
        if (!iExt && ctx.patLen > 0) ctx.patLen--;
    }
    private String formatCurrentPattern(Ctx ctx) {
        StringBuilder sb = new StringBuilder("<(");
        for (int k = 0; k < ctx.patLen; k++) {
            if (ctx.patBuf[k] == -1) sb.append(")(");
            else { if (k > 0 && ctx.patBuf[k - 1] != -1) sb.append(' '); sb.append(ctx.patBuf[k]); }
        }
        return sb.append(")>").toString();
    }

    // ============================ DATA LOADING ============================

    private void loadData(String dataPath, String utilPath, double minRatio) throws IOException {
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
            while ((line = br.readLine()) != null) {
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
            long acc = 0;
            for (int j = its.length - 1; j >= 0; j--) { remUtils[pCur + j] = acc; acc += uts[j]; }
            seqUtility[s] = su;
            totalDBUtility += su;
            pCur += local; eCur += evl.length;
        }
        seqPosStart[numSeq] = pCur;
        seqEvtStart[numSeq] = eCur;
        evtPosStart[totalEvt] = pCur;

        minUtility = (forcedMinUtil >= 0) ? forcedMinUtil : (long) Math.ceil(totalDBUtility * minRatio);
    }

    private static void insertionSortPaired(int[] keys, long[] vals, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
            int k = keys[i]; long v = vals[i];
            int j = i - 1;
            while (j >= lo && keys[j] > k) { keys[j + 1] = keys[j]; vals[j + 1] = vals[j]; j--; }
            keys[j + 1] = k; vals[j + 1] = v;
        }
    }

    // ============================ UTILITIES ============================

    private void savePatternsToFile(String path) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            for (Map.Entry<String, Long> e : finalPatterns.entrySet())
                w.write(e.getKey() + " #UTIL: " + e.getValue() + "\n");
        }
    }

    private static String trimNum(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Check for thread interruption or deadline; memory tracking is handled by MemMeter. */
    private void checkMemory() {
        if (Thread.currentThread().isInterrupted()) throw new RuntimeException("TIMEOUT_INTERRUPT");
        if (System.currentTimeMillis() > deadlineMillis) throw new RuntimeException("TIMEOUT_INTERRUPT");
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
        }, "ahusp-mem-sampler");
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

    /** Graceful pool shutdown: wait briefly then force-interrupt workers to avoid contaminating the next run. */
    private static void shutdownPool(ForkJoinPool p) {
        if (p == null) return;
        p.shutdown();
        try {
            if (!p.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.shutdownNow();
                p.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public double getPeakMemoryMB() { return maxMemory / (1024.0 * 1024.0); }
    public int getPatternCount() { return finalPatterns.size(); }
    public long getRuntimeMs() { return endTimestamp - startTimestamp; }
    public long getMinUtility() { return minUtility; }
    public long getCpuTimeMs() {
        return (cpuStartNanos < 0 || cpuEndNanos < 0) ? -1L : (cpuEndNanos - cpuStartNanos) / 1_000_000L;
    }

    public void printStatistics() {
        System.out.println("\n========== " + label + " (threads=" + numThreads + ") ==========");
        System.out.println("HUSP patterns found         : " + finalPatterns.size());
        System.out.println("Runtime (ms)                : " + getRuntimeMs() + " (preprocessing: " + preprocessMs + ")");
        System.out.println("Peak heap memory            : " + String.format("%.2f MB", getPeakMemoryMB()));
        System.out.println("Candidates generated        : " + candidateCount);
        System.out.println("Nodes explored              : " + exploredNodes);
        System.out.println("Pruned by PEU bound         : " + prunedBound);
        System.out.println("Pruned by node condition    : " + prunedNodeCond);
        System.out.println("===========================================================");
    }
}
