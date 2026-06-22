import java.io.*;
import java.util.*;

/**
 * =====================================================================================
 * SPMF_Converter — CHUYỂN ĐỔI DATASET TỪ ĐỊNH DẠNG SPMF SANG QSDB
 * =====================================================================================
 * CHỨC NĂNG:
 *   Chuyển đổi file chuỗi tuần tự từ định dạng SPMF (chỉ có item ID)
 *   sang định dạng QSDB (có quantity + external utility), sẵn sàng cho thực nghiệm.
 *
 * ĐỊNH DẠNG ĐẦU VÀO (SPMF):
 *   Mỗi dòng: "item1 item2 -1 item3 -1 -2"
 *   - Số nguyên >= 0: item ID
 *   - "-1": phân tách itemset
 *   - "-2": kết thúc chuỗi
 *
 * ĐỊNH DẠNG ĐẦU RA:
 *   1. File _seq.txt (QSDB): "item1[q1] item2[q2] -1 item3[q3] -1 -2"
 *      - quantity được gán ngẫu nhiên Uniform[1, 10]
 *   2. File _eui.txt (External Utility): "itemID:profit"
 *      - profit được gán ngẫu nhiên Gaussian(mu=50, sigma=20), clamp [1, 100]
 *
 * [FIX v12.0] So với bản gốc:
 *   [FIX-1] Cố định random seed = 42 → KẾT QUẢ TÁI TẠO ĐƯỢC (reproducible)
 *       → v11.1 dùng Random() không seed → mỗi lần chạy cho dữ liệu khác
 *   [FIX-2] Thống nhất dấu phân cách EUI: dùng ":" thay "," → khớp với example_eui.txt
 *       → v11.1 dùng "," nhưng example file dùng ":" → parser phải xử lý cả hai
 *
 * GHI CHÚ:
 *   - Random seed cố định đảm bảo cùng dataset SPMF → cùng file QSDB mỗi lần chạy
 *   - Phân bố Gaussian cho profit mô phỏng thực tế: đa số item lãi trung bình,
 *     ít item lãi rất cao hoặc rất thấp
 * =====================================================================================
 */
public class SPMF_Converter {

    // [FIX-1] Cố định seed = 42 để kết quả tái tạo được
    // Mỗi lần chạy SPMF_Converter sẽ tạo ra CÙNG MỘT bộ dữ liệu QSDB
    private static final Random random = new Random(42);

    /** Thư mục đầu ra cho các file đã chuyển đổi */
    private static final String OUTPUT_DIR = "datasets";

    /** Thư mục chứa file SPMF gốc */
    private static final String SOURCE_DIR = "datasets";

    public static void main(String[] args) {
        // Danh sách file SPMF cần chuyển đổi
        String[] inputFiles = {
                "BMS1_SPMF.txt",
                "KOSARAK.txt",
                "FIFA.txt",
                "LEVIATHAN.txt",
                "SIGN.txt",
                "C8T1S5I8N5K.txt"
        };

        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();

        System.out.println("========== BẮT ĐẦU CHUYỂN ĐỔI ĐỒNG LOẠT ==========");
        System.out.println("[*] Random seed = 42 (kết quả tái tạo được)");

        for (String fileName : inputFiles) {
            String fullInputPath = SOURCE_DIR.isEmpty()
                    ? fileName
                    : SOURCE_DIR + File.separator + fileName;
            processSingleFile(fullInputPath, fileName);
        }

        System.out.println("==================================================");
    }

    /**
     * Chuyển đổi một file SPMF thành 2 file QSDB (_seq.txt + _eui.txt).
     *
     * @param fullPath     Đường dẫn đầy đủ tới file SPMF
     * @param originalName Tên file gốc (dùng để đặt tên file đầu ra)
     */
    private static void processSingleFile(String fullPath, String originalName) {
        File inputFile = new File(fullPath);
        if (!inputFile.exists()) {
            System.err.println("[!] LỖI: Không tìm thấy file tại: "
                    + inputFile.getAbsolutePath());
            return;
        }

        String baseName = originalName.replace(".txt", "");
        String seqOut = OUTPUT_DIR + File.separator + baseName + "_seq.txt";
        String euiOut = OUTPUT_DIR + File.separator + baseName + "_eui.txt";

        // TreeSet để thu thập item ID duy nhất (đã sắp xếp)
        Set<Integer> itemRegistry = new TreeSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(inputFile));
             BufferedWriter bw = new BufferedWriter(new FileWriter(seqOut))) {

            String line;
            while ((line = br.readLine()) != null) {
                // Bỏ qua dòng trống và comment
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) continue;

                String[] tokens = line.trim().split("\\s+");
                for (String t : tokens) {
                    try {
                        int id = Integer.parseInt(t);
                        if (id >= 0) {
                            // Item thực: gán quantity ngẫu nhiên Uniform[1, 10]
                            // 70% cơ hội mua 1-2 cái, 20% mua 3-5 cái, 10% mua 6-10 cái
                            int r = random.nextInt(100);
                            int q;
                            if (r < 70) q = random.nextInt(2) + 1;       // 1-2
                            else if (r < 90) q = random.nextInt(3) + 3;  // 3-5
                            else q = random.nextInt(5) + 6;              // 6-10

                            bw.write(id + "[" + q + "] ");
                            itemRegistry.add(id); // Đăng ký item
                        } else {
                            // Ký tự đặc biệt: -1 (phân tách itemset) hoặc -2 (kết thúc chuỗi)
                            bw.write(id + " ");
                        }
                    } catch (NumberFormatException e) {
                        // Bỏ qua token không phải số (nếu có)
                    }
                }
                bw.write("\n");
            }

            // Sinh file External Utility (lợi nhuận biên)
            generateEUI(euiOut, itemRegistry);
            System.out.println("[OK] Đã chuyển đổi: " + originalName
                    + " (" + itemRegistry.size() + " items)");

        } catch (IOException e) {
            System.err.println("[Lỗi] " + originalName + ": " + e.getMessage());
        }
    }

    /**
     * Sinh file External Utility Information (EUI) cho tất cả item.
     *
     * PHÂN BỐ LỢI NHUẬN:
     *   - Gaussian: mu=50, sigma=20 → đa số item lãi ~30-70
     *   - Clamp [1, 100] → không có lãi âm hoặc quá cao
     *   - Kiểu long (số nguyên) cho hiệu năng tính toán
     *
     * [FIX-2] Dùng dấu ":" thay "," → khớp với example_eui.txt
     *
     * @param outputPath Đường dẫn file EUI đầu ra
     * @param items      Tập item ID duy nhất (đã sắp xếp)
     */
    private static void generateEUI(String outputPath, Set<Integer> items) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("# ItemID:Profit (Log-Normal Distribution seed=42)\n");
            for (int id : items) {
                // Sinh phân bố Log-Normal: Đa số dao động 5-30, một số ít vọt lên 500-1000
                double logNormal = Math.exp(random.nextGaussian() * 1.0 + 2.5);

                // Cắt trần ở 1000 và đáy ở 1 để tránh lỗi số học
                long profit = Math.round(Math.max(1, Math.min(1000, logNormal)));
                writer.write(id + ":" + profit + "\n");
            }
        }
    }
}