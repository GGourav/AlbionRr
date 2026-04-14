package com.albionradar.android.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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
 * Captures UDP packets to/from Albion Online servers.
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

    private Protocol18Parser parser;
    private ConcurrentHashMap<Long, EntityInfo> entityMap;
    private EntityInfo playerEntity;
    private SharedPreferences prefs;
    private boolean debugMode;

    private BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                stopCapture();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        parser = new Protocol18Parser();
        entityMap = new ConcurrentHashMap<>();
        prefs = getSharedPreferences("albion_radar_prefs", MODE_PRIVATE);
        debugMode = prefs.getBoolean("debug_mode", false);

        createNotificationChannel();
        
        IntentFilter filter = new IntentFilter(ACTION_STOP);
        registerReceiver(stopReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startCapture();
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCapture();
        try {
            unregisterReceiver(stopReceiver);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void startCapture() {
        if (running) return;

        running = true;
        startForeground(NOTIFICATION_ID, createNotification());

        // Build VPN interface
        Builder builder = new Builder()
                .setSession("Albion Radar")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0);

        // Try to restrict to Albion app only (optional)
        try {
            builder.addAllowedApplication("com.albiononline.AlbionOnline");
        } catch (Exception e) {
            Log.w(TAG, "Could not restrict VPN to Albion app: " + e.getMessage());
            // Continue without app restriction - VPN will capture all traffic
        }

        try {
            vpnInterface = builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN: " + e.getMessage());
            stopCapture();
            return;
        }

        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null - permission may be revoked");
            stopCapture();
            return;
        }

        captureThread = new Thread(this::capturePackets);
        captureThread.start();

        Log.i(TAG, "Packet capture started");
    }

    private void stopCapture() {
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
                Log.e(TAG, "Error closing VPN interface: " + e.getMessage());
            }
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();

        Log.i(TAG, "Packet capture stopped");
    }

    private void capturePackets() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        byte[] packet = new byte[32767];

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Read packet
                int length = in.read(packet);
                if (length <= 0) {
                    // Small delay to avoid busy loop
                    Thread.sleep(1);
                    continue;
                }

                // Parse IP packet
                if (length < 20) continue; // Minimum IP header size

                byte version = (byte) ((packet[0] >> 4) & 0x0F);
                if (version != 4) continue; // Only IPv4

                // Get protocol (UDP = 17)
                int protocol = packet[9] & 0xFF;
                if (protocol != 17) {
                    // Forward non-UDP traffic
                    out.write(packet, 0, length);
                    continue;
                }

                // Get source and destination ports
                int ipHeaderLength = (packet[0] & 0x0F) * 4;
                int srcPort = ((packet[ipHeaderLength] & 0xFF) << 8) | (packet[ipHeaderLength + 1] & 0xFF);
                int dstPort = ((packet[ipHeaderLength + 2] & 0xFF) << 8) | (packet[ipHeaderLength + 3] & 0xFF);

                // Check if this is Albion traffic (port 5056)
                boolean isAlbionPacket = (srcPort == ALBION_PORT || dstPort == ALBION_PORT);

                if (isAlbionPacket) {
                    // Extract UDP payload
                    int udpHeaderLength = 8;
                    int payloadStart = ipHeaderLength + udpHeaderLength;
                    int payloadLength = length - payloadStart;

                    if (payloadLength > 0) {
                        byte[] payload = new byte[payloadLength];
                        System.arraycopy(packet, payloadStart, payload, 0, payloadLength);

                        // Parse Photon packet (only server responses)
                        if (srcPort == ALBION_PORT) {
                            parsePhotonPacket(payload);
                        }
                    }
                }

                // Forward packet (passthrough)
                out.write(packet, 0, length);

            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Error reading packet: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                // Thread interrupted, exit
                break;
            }
        }

        try {
            in.close();
            out.close();
        } catch (IOException e) {
            // Ignore
        }
    }

    private void parsePhotonPacket(byte[] payload) {
        try {
            List<EntityInfo> entities = parser.parsePacket(payload);

            if (entities.isEmpty()) return;

            for (EntityInfo entity : entities) {
                if (!entity.isValid()) {
                    // Entity left - remove from map
                    entityMap.remove(entity.getId());
                } else {
                    // Update or add entity
                    entityMap.put(entity.getId(), entity);

                    // Track player entity
                    if (entity.getType() == EntityInfo.EntityType.PLAYER &&
                        entity.getX() != 999999 && entity.getY() != 999999) {
                        playerEntity = entity;
                    }
                }
            }

            // Broadcast updates
            broadcastEntityUpdate();

        } catch (Exception e) {
            Log.e(TAG, "Error parsing Photon packet: " + e.getMessage());
        }
    }

    private void broadcastEntityUpdate() {
        // Clean up stale entities
        long now = System.currentTimeMillis();
        entityMap.entrySet().removeIf(entry ->
                now - entry.getValue().getLastUpdate() > ENTITY_TIMEOUT);

        ArrayList<EntityInfo> entityList = new ArrayList<>(entityMap.values());

        Intent intent = new Intent(ACTION_ENTITY_UPDATE);
        intent.putParcelableArrayListExtra(EXTRA_ENTITIES, entityList);
        if (playerEntity != null) {
            intent.putExtra(EXTRA_PLAYER, playerEntity);
        }

        try {
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting entity update: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Albion Radar",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Radar is running");

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

        Intent stopIntent = new Intent(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Albion Radar")
                .setContentText("Monitoring game traffic...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop", stopPendingIntent)
                .build();
    }
}
