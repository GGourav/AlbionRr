package com.albionradar.android.pcap;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * Integration with PCAPdroid app for packet capture.
 * Provides functionality to export and share PCAP files.
 */
public class PCAPdroidIntegration {

    private static final String TAG = "PCAPdroidIntegration";
    private static final String PCAPDROID_PACKAGE = "com.emanuelef.remote_capture";

    private Context context;
    private PCAPManager pcapManager;

    public PCAPdroidIntegration(Context context) {
        this.context = context;
        this.pcapManager = new PCAPManager(context);
    }

    /**
     * Check if PCAPdroid is installed on the device.
     */
    public boolean isPCAPdroidInstalled() {
        try {
            context.getPackageManager().getPackageInfo(PCAPDROID_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Open PCAPdroid in the app store.
     */
    public void openPCAPdroidInStore() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("market://details?id=" + PCAPDROID_PACKAGE));
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback to browser
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + PCAPDROID_PACKAGE));
            context.startActivity(intent);
        }
    }

    /**
     * Start PCAP recording.
     */
    public boolean startRecording() {
        return pcapManager.startRecording();
    }

    /**
     * Stop PCAP recording.
     */
    public void stopRecording() {
        pcapManager.stopRecording();
    }

    /**
     * Share the current PCAP file.
     */
    public void sharePCAPFile() {
        File pcapFile = pcapManager.getCurrentFile();
        if (pcapFile == null || !pcapFile.exists()) {
            Log.w(TAG, "No PCAP file to share");
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/vnd.tcpdump.pcap");
        shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(pcapFile));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooserIntent = Intent.createChooser(shareIntent, "Share PCAP File");
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooserIntent);
    }

    /**
     * Get the PCAP file path.
     */
    public String getPCAPFilePath() {
        return pcapManager.getFilePath();
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return pcapManager.isRecording();
    }

    /**
     * Get packet count.
     */
    public int getPacketCount() {
        return pcapManager.getPacketCount();
    }

    /**
     * Write a UDP packet to the PCAP file.
     */
    public void writeUdpPacket(byte[] payload, byte[] srcIp, byte[] dstIp,
                               int srcPort, int dstPort) {
        pcapManager.writeUdpPacket(payload, srcIp, dstIp, srcPort, dstPort);
    }
}
