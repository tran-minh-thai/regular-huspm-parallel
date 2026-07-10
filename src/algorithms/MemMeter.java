package algorithms;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Per-run peak heap measurement that is reliable in a long-lived shared JVM.
 *
 * WHY NOT Runtime.totalMemory()-freeMemory(): in a JVM that runs multiple algorithms
 * sequentially, that value = (committed heap) - (free) and therefore INCLUDES garbage not
 * yet collected from previous runs. With large -Xms/-Xmx, GC fires rarely, so "used" only
 * climbs and plateaus near the same level regardless of the algorithm, and shared structures
 * (e.g. a loaded dataset) dominate the reading -- all algorithms appear to have the same
 * peak, hiding real differences.
 *
 * CORRECT APPROACH -- MemoryPoolMXBean peak with per-run reset:
 *   1. reset(): call System.gc() a few times to drop prior garbage, then resetPeakUsage()
 *      on every HEAP MemoryPoolMXBean. This re-bases the peak tracker to current live
 *      usage, isolating the upcoming run from historical heap state.
 *   2. peakBytes(): sum getPeakUsage().getUsed() over all HEAP pools -- gives the true
 *      peak for THIS run (live objects + temporaries allocated during the run).
 *
 * Call reset() just before starting the timed run (after loading shared data if you want
 * to exclude it from the measurement, or before loading if you want to include it), and
 * peakBytes() after the run completes. No sampler thread is needed -- the JVM updates
 * pool peaks continuously.
 */
public final class MemMeter {
    private MemMeter() {}

    /** GC prior garbage, then re-base the peak counter of every heap pool to current usage. */
    public static void reset() {
        gc();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                try { p.resetPeakUsage(); } catch (UnsupportedOperationException ignored) {}
            }
        }
    }

    /** Peak heap in bytes since the last reset(): sum of peak usage across all heap pools. */
    public static long peakBytes() {
        long sum = 0;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean p : pools) {
            if (p.getType() == MemoryType.HEAP) {
                MemoryUsage u = p.getPeakUsage();
                if (u != null) sum += u.getUsed();
            }
        }
        return sum;
    }

    public static double peakMB() { return peakBytes() / (1024.0 * 1024.0); }

    private static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
