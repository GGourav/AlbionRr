package com.albionradar.android.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.albionradar.android.MainActivity;
import com.albionradar.android.R;
import com.albionradar.android.parser.EntityInfo;
import com.albionradar.android.parser.Protocol18Parser;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VPN-based packet capture service for non-rooted devices.
 * Captures UDP packets to/from Albion Online servers (port 5056).
 *
 * IMPORTANT: Albion Online now uses Protocol18 (GpBinaryV18) since April 2026 patch!
 * Protocol16 is deprecated and will NOT work.
 *
 * Key changes in Protocol18:
 * - Type codes are NUMERIC (0-42), not ASCII
 * - Varint encoding for integers and lengths
 * - Little-Endian for primitives
 * - Zero-value type codes (no payload needed)
 * - Bit-packed boolean arrays
 */
public class PacketCaptureService extends VpnService {

    private static final String TAG = "PacketCaptureService";
    private static final String CHANNEL_ID = "albion_radar_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.albionradar.android.START";
    public static final String ACTION_STOP = "com.albionradar.android.STOP";
    public static final String ACTION_ENTITY_UPDATE = "com.albionradar.android.ENTITY_UPDATE";
    public static final String EXTRA_ENTITIES = "entities";
    public static final String EXTRA_PLAYER = "player";

    private static final int ALBION_PORT = 5056;
    private static final long ENTITY_TIMEOUT = 30000; // 30 seconds

    private ParcelFileDescriptor vpnInterface;
    private Thread captureThread;
    private volatile boolean running = false;

    private Protocol18Parser parser;  // Using Protocol18!
    private ConcurrentHashMap<Long, EntityInfo> entityMap;
    private EntityInfo playerEntity;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate - Using Protocol18 (GpBinaryV18)");

        // Use Protocol18 parser (REQUIRED after April 2026 patch!)
        parser = new Protocol18Parser();
        entityMap = new ConcurrentHashMap<>();

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: " + (intent != null ? intent.getAction() : "null intent"));

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startCapture();
        } else if (ACTION_STOP.equals(action)) {
            stopCapture();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        stopCapture();
        super.onDestroy();
    }

    private void startCapture() {
        Log.i(TAG, "startCapture called, running=" + running);

        if (running) {
            Log.w(TAG, "Already running");
            return;
        }

        startForeground(NOTIFICATION_ID, createNotification());

        running = true;

        // Build VPN interface - capture all UDP traffic
        Builder builder = new Builder()
                .setSession("Albion Radar")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0);

        // Don't restrict to specific app - causes issues on some devices
        // Let the VPN capture all traffic and filter by port

        try {
            vpnInterface = builder.establish();
            Log.i(TAG, "VPN interface established: " + (vpnInterface != null));
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN: " + e.getMessage());
            stopCapture();
            return;
        }

        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null");
            stopCapture();
            return;
        }

        captureThread = new Thread(this::capturePackets);
        captureThread.start();

        Log.i(TAG, "Packet capture started successfully - Protocol18 active");
    }

    private void stopCapture() {
        Log.i(TAG, "stopCapture called");

        running = false;

        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            captureThread = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN: " + e.getMessage());
            }
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();
    }

    private void capturePackets() {
        Log.i(TAG, "Capture thread started - Protocol18 parsing");

        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        byte[] packet = new byte[32767];
        int packetCount = 0;
        int albionPacketCount = 0;
        int parsedCount = 0;
        int entityCount = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int length = in.read(packet);
                if (length <= 0) {
                    Thread.sleep(1);
                    continue;
                }

                packetCount++;

                // Basic IP packet validation
                if (length < 20) {
                    out.write(packet, 0, length);
                    continue;
                }

                byte version = (byte) ((packet[0] >> 4) & 0x0F);
                if (version != 4) {
                    out.write(packet, 0, length);
                    continue;
                }

                int protocol = packet[9] & 0xFF;
                if (protocol != 17) { // UDP
                    out.write(packet, 0, length);
                    continue;
                }

                int ipHeaderLength = (packet[0] & 0x0F) * 4;
                if (ipHeaderLength + 8 > length) {
                    out.write(packet, 0, length);
                    continue;
                }

                int srcPort = ((packet[ipHeaderLength] & 0xFF) << 8) | (packet[ipHeaderLength + 1] & 0xFF);
                int dstPort = ((packet[ipHeaderLength + 2] & 0xFF) << 8) | (packet[ipHeaderLength + 3] & 0xFF);

                // Check for Albion port
                if (srcPort == ALBION_PORT || dstPort == ALBION_PORT) {
                    albionPacketCount++;

                    int payloadStart = ipHeaderLength + 8;
                    int payloadLength = length - payloadStart;

                    if (payloadLength > 0 && payloadLength < 1500) {
                        byte[] payload = new byte[payloadLength];
                        System.arraycopy(packet, payloadStart, payload, 0, payloadLength);

                        // Only parse server responses (srcPort == 5056)
                        if (srcPort == ALBION_PORT) {
                            try {
                                int entitiesBefore = entityMap.size();
                                parsePhotonPacket(payload);
                                int entitiesAfter = entityMap.size();

                                if (entitiesAfter > entitiesBefore) {
                                    parsedCount++;
                                    entityCount += (entitiesAfter - entitiesBefore);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Parse error: " + e.getMessage());
                            }
                        }
                    }
                }

                // Forward packet (passthrough)
                out.write(packet, 0, length);

            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "IO error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage());
            }
        }

        try {
            in.close();
            out.close();
        } catch (IOException e) {
            // Ignore
        }

        Log.i(TAG, "Capture thread ended. Total packets: " + packetCount +
              ", Albion packets: " + albionPacketCount +
              ", Parsed: " + parsedCount +
              ", Entities found: " + entityCount);
    }

    private void parsePhotonPacket(byte[] payload) {
        // Use Protocol18 parser
        List<EntityInfo> entities = parser.parsePacket(payload);

        if (entities.isEmpty()) return;

        for (EntityInfo entity : entities) {
            if (!entity.isValid()) {
                // Entity left - remove from map
                entityMap.remove(entity.getId());
                Log.d(TAG, "Entity left: " + entity.getId());
            } else {
                // Update or add entity
                entityMap.put(entity.getId(), entity);

                // Track player entity
                if (entity.getType() == EntityInfo.EntityType.PLAYER) {
                    playerEntity = entity;
                    Log.d(TAG, "Player at: " + entity.getX() + ", " + entity.getY());
                }
            }
        }

        // Clean up stale entities
        long now = System.currentTimeMillis();
        entityMap.entrySet().removeIf(entry ->
                now - entry.getValue().getLastUpdate() > ENTITY_TIMEOUT);

        // Broadcast updates
        broadcastUpdate();
    }

    private void broadcastUpdate() {
        ArrayList<EntityInfo> entityList = new ArrayList<>(entityMap.values());

        Intent intent = new Intent(ACTION_ENTITY_UPDATE);
        intent.putParcelableArrayListExtra(EXTRA_ENTITIES, entityList);
        if (playerEntity != null) {
            intent.putExtra(EXTRA_PLAYER, playerEntity);
        }

        sendBroadcast(intent);

        Log.d(TAG, "Broadcasting " + entityList.size() + " entities");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Albion Radar",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Radar is running - Protocol18 (GpBinaryV18)");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, PacketCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Albion Radar")
                .setContentText("Monitoring Albion traffic (Protocol18)")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build();
    }
}
