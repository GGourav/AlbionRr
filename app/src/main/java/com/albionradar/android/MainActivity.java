package com.albionradar.android;

import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.albionradar.android.network.PacketCaptureService;

/**
 * Main entry point for Albion Radar application.
 * Handles VPN and overlay permission requests.
 */
public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 0x0F;
    private static final int OVERLAY_REQUEST_CODE = 0x10;
    
    private Button startButton;
    private Button settingsButton;
    private boolean vpnPermissionGranted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startRadarButton);
        settingsButton = findViewById(R.id.settingsButton);

        startButton.setOnClickListener(v -> checkPermissionsAndStart());
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    private void checkPermissionsAndStart() {
        // First check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        
        // Then request VPN permission
        requestVpnPermission();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName())
        );
        startActivityForResult(intent, OVERLAY_REQUEST_CODE);
        Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show();
    }

    private void requestVpnPermission() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            vpnPermissionGranted = true;
            startRadarService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (Settings.canDrawOverlays(this)) {
                // Overlay granted, now request VPN
                requestVpnPermission();
            } else {
                Toast.makeText(this, "Overlay permission denied. Radar may not display properly.", 
                    Toast.LENGTH_LONG).show();
                // Try to continue anyway
                requestVpnPermission();
            }
        } else if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                vpnPermissionGranted = true;
                startRadarService();
            } else {
                Toast.makeText(this, "VPN permission denied. Cannot capture packets.", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startRadarService() {
        try {
            Intent serviceIntent = new Intent(this, PacketCaptureService.class);
            serviceIntent.setAction(PacketCaptureService.ACTION_START);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Intent radarIntent = new Intent(this, RadarActivity.class);
            startActivity(radarIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        }
    }
}
