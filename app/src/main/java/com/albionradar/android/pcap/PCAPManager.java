package com.albionradar.android.pcap;

import android.content.Context;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manager for creating and exporting PCAP files.
 * Used for debugging and protocol analysis.
 */
public class PCAPManager {

    private static final String TAG = "PCAPManager";

    // PCAP file header constants
    private static final int PCAP_MAGIC_NUMBER = 0xa1b2c3d4;
    private static final short PCAP_VERSION_MAJOR = 2;
    private static final short PCAP_VERSION_MINOR = 4;
    private static final int PCAP_THISZONE = 0;
    private static final int PCAP_SIGFIGS = 0;
    private static final int PCAP_SNAPLEN = 65535;
    private static final int PCAP_NETWORK = 1; // Ethernet

    private Context context;
    private FileOutputStream fileOutput;
    private DataOutputStream dataOutput;
    private File currentFile;
    private boolean isRecording = false;
    private long startTime;
    private int packetCount;

    public PCAPManager(Context context) {
        this.context = context;
    }

    /**
     * Start recording packets to a new PCAP file.
     */
    public boolean startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }

        try {
            // Create file with timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "albion_capture_" + sdf.format(new Date()) + ".pcap";

            File capturesDir = new File(context.getExternalFilesDir(null), "captures");
            if (!capturesDir.exists()) {
                capturesDir.mkdirs();
            }

            currentFile = new File(capturesDir, filename);
            fileOutput = new FileOutputStream(currentFile);
            dataOutput = new DataOutputStream(fileOutput);

            // Write PCAP global header
            writeGlobalHeader();

            isRecording = true;
            startTime = System.currentTimeMillis();
            packetCount = 0;

            Log.i(TAG, "Started recording to: " + currentFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop recording and close the file.
     */
    public void stopRecording() {
        if (!isRecording) return;

        try {
            if (dataOutput != null) {
                dataOutput.flush();
                dataOutput.close();
            }
            if (fileOutput != null) {
                fileOutput.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing PCAP file: " + e.getMessage());
        }

        isRecording = false;
        Log.i(TAG, "Stopped recording. Total packets: " + packetCount);
    }

    /**
     * Write a UDP packet to the PCAP file.
     *
     * @param payload    UDP payload data
     * @param srcIp      Source IP address (as bytes)
     * @param dstIp      Destination IP address (as bytes)
     * @param srcPort    Source port
     * @param dstPort    Destination port
     */
    public void writeUdpPacket(byte[] payload, byte[] srcIp, byte[] dstIp,
                               int srcPort, int dstPort) {
        if (!isRecording || dataOutput == null) return;

        try {
            // Build IP header (20 bytes)
            byte[] ipHeader = new byte[20];
            ipHeader[0] = 0x45; // Version 4, IHL 5
            ipHeader[1] = 0x00; // DSCP/ECN

            int totalLength = 20 + 8 + payload.length; // IP + UDP + payload
            ipHeader[2] = (byte) ((totalLength >> 8) & 0xFF);
            ipHeader[3] = (byte) (totalLength & 0xFF);

            // Identification, flags, fragment offset (simplified)
            ipHeader[4] = 0x00;
            ipHeader[5] = 0x00;
            ipHeader[6] = 0x40; // Don't fragment
            ipHeader[7] = 0x00;

            ipHeader[8] = 0x40; // TTL
            ipHeader[9] = 0x11; // Protocol: UDP

            // Checksum (set to 0 for simplicity - can be calculated)
            ipHeader[10] = 0x00;
            ipHeader[11] = 0x00;

            // Source and destination IP
            System.arraycopy(srcIp, 0, ipHeader, 12, 4);
            System.arraycopy(dstIp, 0, ipHeader, 16, 4);

            // Build UDP header (8 bytes)
            byte[] udpHeader = new byte[8];
            udpHeader[0] = (byte) ((srcPort >> 8) & 0xFF);
            udpHeader[1] = (byte) (srcPort & 0xFF);
            udpHeader[2] = (byte) ((dstPort >> 8) & 0xFF);
            udpHeader[3] = (byte) (dstPort & 0xFF);

            int udpLength = 8 + payload.length;
            udpHeader[4] = (byte) ((udpLength >> 8) & 0xFF);
            udpHeader[5] = (byte) (udpLength & 0xFF);

            // UDP checksum (optional for IPv4)
            udpHeader[6] = 0x00;
            udpHeader[7] = 0x00;

            // Build Ethernet frame
            byte[] ethHeader = new byte[14];
            // Destination MAC (broadcast for simplicity)
            ethHeader[0] = (byte) 0xFF;
            ethHeader[1] = (byte) 0xFF;
            ethHeader[2] = (byte) 0xFF;
            ethHeader[3] = (byte) 0xFF;
            ethHeader[4] = (byte) 0xFF;
            ethHeader[5] = (byte) 0xFF;
            // Source MAC (arbitrary)
            ethHeader[6] = 0x00;
            ethHeader[7] = 0x00;
            ethHeader[8] = 0x00;
            ethHeader[9] = 0x00;
            ethHeader[10] = 0x00;
            ethHeader[11] = 0x01;
            // EtherType: IPv4
            ethHeader[12] = 0x08;
            ethHeader[13] = 0x00;

            // Calculate total frame size
            int frameSize = ethHeader.length + ipHeader.length + udpHeader.length + payload.length;

            // Write packet header
            long timestamp = System.currentTimeMillis() - startTime;
            int tsSeconds = (int) (timestamp / 1000);
            int tsMicros = (int) ((timestamp % 1000) * 1000);

            dataOutput.writeInt(tsSeconds);
            dataOutput.writeInt(tsMicros);
            dataOutput.writeInt(frameSize);
            dataOutput.writeInt(frameSize);

            // Write packet data
            dataOutput.write(ethHeader);
            dataOutput.write(ipHeader);
            dataOutput.write(udpHeader);
            dataOutput.write(payload);

            packetCount++;

            // Flush periodically
            if (packetCount % 100 == 0) {
                dataOutput.flush();
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing packet: " + e.getMessage());
        }
    }

    private void writeGlobalHeader() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(PCAP_MAGIC_NUMBER);
        buffer.putShort(PCAP_VERSION_MAJOR);
        buffer.putShort(PCAP_VERSION_MINOR);
        buffer.putInt(PCAP_THISZONE);
        buffer.putInt(PCAP_SIGFIGS);
        buffer.putInt(PCAP_SNAPLEN);
        buffer.putInt(PCAP_NETWORK);

        dataOutput.write(buffer.array());
    }

    public boolean isRecording() {
        return isRecording;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public int getPacketCount() {
        return packetCount;
    }

    /**
     * Get the PCAP file path for sharing.
     */
    public String getFilePath() {
        return currentFile != null ? currentFile.getAbsolutePath() : null;
    }
}
