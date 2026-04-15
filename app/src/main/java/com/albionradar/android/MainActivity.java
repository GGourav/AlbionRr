package com.albionradar.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.albionradar.android.network.PacketCaptureService;

/**
 * Main entry point for Albion Radar application.
 * Handles VPN permission request and starts packet capture.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST_CODE = 0x0F;
    private static final int OVERLAY_REQUEST_CODE = 0x10;

    private Button startButton;
    private Button settingsButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startRadarButton);
        settingsButton = findViewById(R.id.settingsButton);
        statusText = findViewById(R.id.subtitleText);

        startButton.setOnClickListener(v -> checkPermissionsAndStart());
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean isRunning = isServiceRunning();
        if (isRunning) {
            startButton.setText("Open Radar");
            statusText.setText("Radar is running");
        } else {
            startButton.setText("Start Radar");
            statusText.setText("Entity Detection for Albion Online");
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (PacketCaptureService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void checkPermissionsAndStart() {
        Log.d(TAG, "checkPermissionsAndStart called");

        // If service is already running, just open radar
        if (isServiceRunning()) {
            openRadar();
            return;
        }

        // Check overlay permission first
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission");
            requestOverlayPermission();
            return;
        }

        // Then request VPN permission
        Log.d(TAG, "Requesting VPN permission");
        requestVpnPermission();
    }

    private void requestOverlayPermission() {
        try {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, OVERLAY_REQUEST_CODE);
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to request overlay permission: " + e.getMessage());
            // Continue without overlay
            requestVpnPermission();
        }
    }

    private void requestVpnPermission() {
        try {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            } else {
                // VPN already prepared
                startRadarService();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare VPN: " + e.getMessage());
            Toast.makeText(this, "VPN preparation failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (Settings.canDrawOverlays(this)) {
                requestVpnPermission();
            } else {
                Toast.makeText(this, "Overlay permission denied. Continuing anyway...", Toast.LENGTH_SHORT).show();
                requestVpnPermission();
            }
        } else if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startRadarService();
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startRadarService() {
        Log.d(TAG, "Starting radar service");
        
        try {
            Intent serviceIntent = new Intent(this, PacketCaptureService.class);
            serviceIntent.setAction(PacketCaptureService.ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // Give service time to start
            new android.os.Handler().postDelayed(this::openRadar, 500);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage());
            Toast.makeText(this, "Failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openRadar() {
        try {
            Intent radarIntent = new Intent(MainActivity.this, RadarActivity.class);
            startActivity(radarIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open radar: " + e.getMessage());
        }
    }
}
