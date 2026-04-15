package com.albionradar.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
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

    private Button startButton;
    private Button settingsButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate");
        
        try {
            setContentView(R.layout.activity_main);

            startButton = findViewById(R.id.startRadarButton);
            settingsButton = findViewById(R.id.settingsButton);
            statusText = findViewById(R.id.subtitleText);

            if (startButton != null) {
                startButton.setOnClickListener(v -> checkPermissionsAndStart());
            }
            if (settingsButton != null) {
                settingsButton.setOnClickListener(v -> {
                    Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                    startActivity(intent);
                });
            }

            updateStatus();
            Log.i(TAG, "MainActivity created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
            Toast.makeText(this, "Error initializing: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean isRunning = isServiceRunning();
        if (startButton != null) {
            if (isRunning) {
                startButton.setText("Open Radar");
            } else {
                startButton.setText("Start Radar");
            }
        }
        if (statusText != null) {
            if (isRunning) {
                statusText.setText("Radar is running");
            } else {
                statusText.setText("Entity Detection for Albion Online");
            }
        }
    }

    private boolean isServiceRunning() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (PacketCaptureService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking service: " + e.getMessage());
        }
        return false;
    }

    private void checkPermissionsAndStart() {
        Log.i(TAG, "checkPermissionsAndStart called");

        // If service is already running, just open radar
        if (isServiceRunning()) {
            openRadar();
            return;
        }

        // Request VPN permission
        Log.i(TAG, "Requesting VPN permission");
        requestVpnPermission();
    }

    private void requestVpnPermission() {
        try {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
                Log.i(TAG, "VPN permission dialog started");
            } else {
                // VPN already prepared
                Log.i(TAG, "VPN already prepared, starting service");
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
        Log.i(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "VPN permission granted");
                startRadarService();
            } else {
                Log.w(TAG, "VPN permission denied");
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startRadarService() {
        Log.i(TAG, "Starting radar service");
        
        try {
            Intent serviceIntent = new Intent(this, PacketCaptureService.class);
            serviceIntent.setAction(PacketCaptureService.ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "Service start requested");

            // Give service time to start
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                Log.i(TAG, "Opening radar activity");
                openRadar();
            }, 1000);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage());
            Toast.makeText(this, "Failed to start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openRadar() {
        try {
            Intent radarIntent = new Intent(MainActivity.this, RadarActivity.class);
            startActivity(radarIntent);
            Log.i(TAG, "RadarActivity started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open radar: " + e.getMessage());
            Toast.makeText(this, "Failed to open radar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
