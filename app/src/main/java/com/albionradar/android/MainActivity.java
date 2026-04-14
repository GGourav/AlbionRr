package com.albionradar.android;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.albionradar.android.network.PacketCaptureService;
import com.albionradar.android.R;

/**
 * Main entry point for Albion Radar application.
 * Handles VPN permission request and starts packet capture.
 */
public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 0x0F;
    private Button startButton;
    private Button settingsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.startRadarButton);
        settingsButton = findViewById(R.id.settingsButton);

        startButton.setOnClickListener(v -> startVpn());
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    private void startVpn() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startRadarService();
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startRadarService() {
        Intent serviceIntent = new Intent(this, PacketCaptureService.class);
        serviceIntent.setAction(PacketCaptureService.ACTION_START);
        startService(serviceIntent);

        Intent radarIntent = new Intent(this, RadarActivity.class);
        startActivity(radarIntent);
    }
}
