package com.albionradar.android.pcap;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug logger for packet analysis.
 * Writes detailed packet information to log files.
 */
public class PacketLogger {

    private static final String TAG = "PacketLogger";

    private Context context;
    private FileWriter logWriter;
    private File logFile;
    private boolean isEnabled = false;
    private SimpleDateFormat dateFormat;
    private int logCount;

    public PacketLogger(Context context) {
        this.context = context;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    }

    /**
     * Start logging to a new file.
     */
    public boolean startLogging() {
        if (isEnabled) {
            Log.w(TAG, "Already logging");
            return false;
        }

        try {
            File logsDir = new File(context.getExternalFilesDir(null), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "packet_log_" + sdf.format(new Date()) + ".txt";

            logFile = new File(logsDir, filename);
            logWriter = new FileWriter(logFile, true);

            isEnabled = true;
            logCount = 0;

            logHeader();
            Log.i(TAG, "Started logging to: " + logFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to start logging: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop logging and close the file.
     */
    public void stopLogging() {
        if (!isEnabled) return;

        try {
            if (logWriter != null) {
                logFooter();
                logWriter.flush();
                logWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing log file: " + e.getMessage());
        }

        isEnabled = false;
        Log.i(TAG, "Stopped logging. Total entries: " + logCount);
    }

    /**
     * Log a packet event.
     */
    public void logPacket(String direction, byte[] data, int length) {
        if (!isEnabled || logWriter == null) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append(dateFormat.format(new Date())).append(" ");
            sb.append("[").append(direction).append("] ");
            sb.append("Length: ").append(length).append("\n");

            // Hex dump first 64 bytes
            int dumpLength = Math.min(64, length);
            sb.append("Hex: ");
            for (int i = 0; i < dumpLength; i++) {
                sb.append(String.format("%02X ", data[i] & 0xFF));
                if ((i + 1) % 16 == 0) {
                    sb.append("\n      ");
                }
            }
            sb.append("\n\n");

            logWriter.write(sb.toString());
            logCount++;

            if (logCount % 50 == 0) {
                logWriter.flush();
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing log: " + e.getMessage());
        }
    }

    /**
     * Log a parsed entity event.
     */
    public void logEntity(String action, long entityId, String type,
                          float x, float y, int tier, int enchantment) {
        if (!isEnabled || logWriter == null) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append(dateFormat.format(new Date())).append(" ");
            sb.append("[ENTITY] ").append(action).append(" ");
            sb.append("ID=").append(entityId).append(" ");
            sb.append("Type=").append(type).append(" ");
            sb.append("Pos=(").append(x).append(", ").append(y).append(") ");
            sb.append("T").append(tier);
            if (enchantment > 0) {
                sb.append(".").append(enchantment);
            }
            sb.append("\n");

            logWriter.write(sb.toString());
            logCount++;

        } catch (IOException e) {
            Log.e(TAG, "Error writing entity log: " + e.getMessage());
        }
    }

    /**
     * Log a Photon event.
     */
    public void logPhotonEvent(int eventCode, String description) {
        if (!isEnabled || logWriter == null) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append(dateFormat.format(new Date())).append(" ");
            sb.append("[PHOTON] Event ").append(eventCode);
            if (description != null && !description.isEmpty()) {
                sb.append(": ").append(description);
            }
            sb.append("\n");

            logWriter.write(sb.toString());

        } catch (IOException e) {
            Log.e(TAG, "Error writing event log: " + e.getMessage());
        }
    }

    private void logHeader() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("=" .repeat(60)).append("\n");
        sb.append("Albion Radar - Packet Debug Log\n");
        sb.append("Started: ").append(dateFormat.format(new Date())).append("\n");
        sb.append("=" .repeat(60)).append("\n\n");
        logWriter.write(sb.toString());
    }

    private void logFooter() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=" .repeat(60)).append("\n");
        sb.append("Log ended: ").append(dateFormat.format(new Date())).append("\n");
        sb.append("Total entries: ").append(logCount).append("\n");
        sb.append("=" .repeat(60)).append("\n");
        logWriter.write(sb.toString());
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public File getLogFile() {
        return logFile;
    }

    public int getLogCount() {
        return logCount;
    }
}
