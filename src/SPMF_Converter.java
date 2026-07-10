import java.io.*;
import java.util.*;

/**
 * =====================================================================================
 * SPMF_Converter -- converts sequential datasets from SPMF format to QSDB format.
 * =====================================================================================
 * PURPOSE:
 *   Reads a sequential database in the SPMF format (item IDs only) and writes it in
 *   the QSDB format (item ID with quantity bracket + external utility file), ready for
 *   experiment runs.
 *
 * INPUT FORMAT (SPMF):
 *   Each line: "item1 item2 -1 item3 -1 -2"
 *   - Non-negative integers: item IDs
 *   - "-1": itemset separator
 *   - "-2": sequence terminator
 *
 * OUTPUT FORMAT:
 *   1. _seq.txt (QSDB sequence file): "item1[q1] item2[q2] -1 item3[q3] -1 -2"
 *      - quantity assigned randomly: Uniform-weighted mix (70%: 1-2, 20%: 3-5, 10%: 6-10)
 *   2. _eui.txt (External Utility file): "itemID:profit"
 *      - profit drawn from a Log-Normal distribution (mu=2.5, sigma=1.0), clamped [1, 1000]
 *
 * NOTES:
 *   - Generation is seeded, so the same SPMF input always produces the same QSDB output.
 *     Every experiment in the paper reads the files produced by this class.
 *   - A Log-Normal profit distribution reflects realistic skewness: most items have modest
 *     profit, while a few have very high profit.
 * =====================================================================================
 */
public class SPMF_Converter {

    // Fixed seed: every run of SPMF_Converter produces the same QSDB dataset.
    private static final Random random = new Random(42);

    /** Output directory for converted files. */
    private static final String OUTPUT_DIR = "datasets";

    /** Source directory containing the original SPMF files. */
    private static final String SOURCE_DIR = "datasets";

    public static void main(String[] args) {
        // List of SPMF files to convert
        String[] inputFiles = {
                "BIBLE.txt",
                "BMS1.txt",
                "C8T1S5I8N5K.txt",
                "FIFA.txt",
                "KOSARAK.txt",
                "LEVIATHAN.txt",
                "SIGN.txt"
        };

        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();

        System.out.println("========== SPMF -> QSDB batch conversion ==========");
        System.out.println("[*] Random seed = 42 (reproducible output)");

        for (String fileName : inputFiles) {
            String fullInputPath = SOURCE_DIR.isEmpty()
                    ? fileName
                    : SOURCE_DIR + File.separator + fileName;
            processSingleFile(fullInputPath, fileName);
        }

        System.out.println("==================================================");
    }

    /**
     * Converts a single SPMF file into two QSDB files (_seq.txt + _eui.txt).
     *
     * @param fullPath     full path to the SPMF input file
     * @param originalName original file name (used to derive output file names)
     */
    private static void processSingleFile(String fullPath, String originalName) {
        File inputFile = new File(fullPath);
        if (!inputFile.exists()) {
            System.err.println("[!] ERROR: file not found at: " + inputFile.getAbsolutePath());
            return;
        }

        String baseName = originalName.replace(".txt", "");
        String seqOut = OUTPUT_DIR + File.separator + baseName + "_seq.txt";
        String euiOut = OUTPUT_DIR + File.separator + baseName + "_eui.txt";

        // TreeSet to collect unique item IDs in sorted order
        Set<Integer> itemRegistry = new TreeSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(inputFile));
             BufferedWriter bw = new BufferedWriter(new FileWriter(seqOut))) {

            String line;
            while ((line = br.readLine()) != null) {
                // Skip blank lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) continue;

                String[] tokens = line.trim().split("\\s+");
                for (String t : tokens) {
                    try {
                        int id = Integer.parseInt(t);
                        if (id >= 0) {
                            // Real item: assign random quantity (Uniform-weighted)
                            // 70% chance: 1-2 units; 20%: 3-5 units; 10%: 6-10 units
                            int r = random.nextInt(100);
                            int q;
                            if (r < 70) q = random.nextInt(2) + 1;       // 1-2
                            else if (r < 90) q = random.nextInt(3) + 3;  // 3-5
                            else q = random.nextInt(5) + 6;              // 6-10

                            bw.write(id + "[" + q + "] ");
                            itemRegistry.add(id);
                        } else {
                            // Special token: -1 (itemset separator) or -2 (sequence end)
                            bw.write(id + " ");
                        }
                    } catch (NumberFormatException e) {
                        // Skip non-numeric tokens
                    }
                }
                bw.write("\n");
            }

            generateEUI(euiOut, itemRegistry);
            System.out.println("[OK] Converted: " + originalName + " (" + itemRegistry.size() + " items)");

        } catch (IOException e) {
            System.err.println("[ERROR] " + originalName + ": " + e.getMessage());
        }
    }

    /**
     * Generates the External Utility Information (EUI) file for all items.
     *
     * PROFIT DISTRIBUTION:
     *   - Log-Normal: mu=2.5, sigma=1.0 -- most items in the ~5-30 range, a few spike to 500-1000
     *   - Clamped to [1, 1000] -- no negative or astronomically large profits
     *   - Values are stored as long integers for efficient arithmetic
     *
     * The delimiter is ":", matching the external-utility files read by the miners.
     *
     * @param outputPath path to the EUI output file
     * @param items      sorted set of unique item IDs
     */
    private static void generateEUI(String outputPath, Set<Integer> items) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("# ItemID:Profit (Log-Normal Distribution seed=42)\n");
            for (int id : items) {
                // Log-Normal draw: most values fall in 5-30, rare tail spikes to 500-1000
                double logNormal = Math.exp(random.nextGaussian() * 1.0 + 2.5);

                // Clamp to [1, 1000] to avoid arithmetic edge cases
                long profit = Math.round(Math.max(1, Math.min(1000, logNormal)));
                writer.write(id + ":" + profit + "\n");
            }
        }
    }
}
