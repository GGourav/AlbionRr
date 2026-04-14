package com.albionradar.android;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings activity for configuring radar display options.
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText serverIpEdit;
    private EditText serverPortEdit;
    private SeekBar rangeSeekBar;
    private TextView rangeValueText;
    private CheckBox showResourcesCheck;
    private CheckBox showMobsCheck;
    private CheckBox showPlayersCheck;
    private CheckBox showEventsCheck;
    private CheckBox recordPcapCheck;
    private CheckBox debugModeCheck;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("albion_radar_prefs", MODE_PRIVATE);

        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        serverIpEdit = findViewById(R.id.serverIpEdit);
        serverPortEdit = findViewById(R.id.serverPortEdit);
        rangeSeekBar = findViewById(R.id.rangeSeekBar);
        rangeValueText = findViewById(R.id.rangeValueText);
        showResourcesCheck = findViewById(R.id.showResourcesCheck);
        showMobsCheck = findViewById(R.id.showMobsCheck);
        showPlayersCheck = findViewById(R.id.showPlayersCheck);
        showEventsCheck = findViewById(R.id.showEventsCheck);
        recordPcapCheck = findViewById(R.id.recordPcapCheck);
        debugModeCheck = findViewById(R.id.debugModeCheck);
    }

    private void loadSettings() {
        serverIpEdit.setText(prefs.getString("server_ip", ""));
        serverPortEdit.setText(String.valueOf(prefs.getInt("server_port", 5056)));
        rangeSeekBar.setProgress(prefs.getInt("radar_range", 50));
        showResourcesCheck.setChecked(prefs.getBoolean("show_resources", true));
        showMobsCheck.setChecked(prefs.getBoolean("show_mobs", true));
        showPlayersCheck.setChecked(prefs.getBoolean("show_players", true));
        showEventsCheck.setChecked(prefs.getBoolean("show_events", true));
        recordPcapCheck.setChecked(prefs.getBoolean("record_pcap", false));
        debugModeCheck.setChecked(prefs.getBoolean("debug_mode", false));
    }

    private void setupListeners() {
        rangeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rangeValueText.setText(progress + " units");
                saveInt("radar_range", progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        serverIpEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveString("server_ip", serverIpEdit.getText().toString());
            }
        });

        serverPortEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    int port = Integer.parseInt(serverPortEdit.getText().toString());
                    saveInt("server_port", port);
                } catch (NumberFormatException e) {
                    serverPortEdit.setText("5056");
                }
            }
        });

        showResourcesCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("show_resources", checked));
        showMobsCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("show_mobs", checked));
        showPlayersCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("show_players", checked));
        showEventsCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("show_events", checked));
        recordPcapCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("record_pcap", checked));
        debugModeCheck.setOnCheckedChangeListener((v, checked) -> saveBoolean("debug_mode", checked));
    }

    private void saveString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    private void saveInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    private void saveBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }
}
