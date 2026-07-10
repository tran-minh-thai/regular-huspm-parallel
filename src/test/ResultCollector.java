package test;

import java.io.*;

/**
 * Collects experiment results and writes them to a CSV file automatically.
 */
public class ResultCollector implements Closeable {
    private final BufferedWriter writer;

    public ResultCollector(String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();

        boolean exists = file.exists() && file.length() > 0;

        // Open in append mode so runs accumulate in a single file
        this.writer = new BufferedWriter(new FileWriter(file, true));

        // Write the CSV header only when creating a new file
        if (!exists) {
            writer.write(ExperimentMetrics.getHeader());
            writer.newLine();
            writer.flush();
        }
    }

    public synchronized void log(ExperimentMetrics metrics) {
        if (metrics == null) return;

        try {
            writer.write(metrics.toCsvRow());
            writer.newLine();
            writer.flush(); // flush immediately to preserve data on crash
        } catch (Throwable e) {
            try {
                System.err.println("Failed to write experiment log: " + e.getClass().getSimpleName());
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {}
    }
}
