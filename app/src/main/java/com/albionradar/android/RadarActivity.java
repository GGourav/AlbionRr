package com.albionradar.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.albionradar.android.network.PacketCaptureService;
import com.albionradar.android.parser.EntityInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Radar display activity.
 * Shows entities on a minimap-style radar overlay.
 */
public class RadarActivity extends AppCompatActivity {

    private static final String TAG = "RadarActivity";
    private static final int RADAR_RANGE = 50;
    private static final float PLAYER_SIZE = 10f;
    private static final float ENTITY_SIZE = 8f;

    // Tier colors (matching OpenRadar)
    private static final int[] TIER_COLORS = {
        Color.parseColor("#1a1a1a"), // T1 - Black
        Color.parseColor("#808080"), // T2 - Grey
        Color.parseColor("#00ff00"), // T3 - Green
        Color.parseColor("#0066ff"), // T4 - Blue
        Color.parseColor("#ff0000"), // T5 - Red
        Color.parseColor("#ff8800"), // T6 - Orange
        Color.parseColor("#ffff00"), // T7 - Yellow
        Color.parseColor("#ffffff")  // T8 - White
    };

    // Enchantment colors (matching OpenRadar)
    private static final int[] ENCHANTMENT_COLORS = {
        Color.TRANSPARENT,        // No enchantment
        Color.parseColor("#006600"), // .1 - Dark Green
        Color.parseColor("#000066"), // .2 - Dark Blue
        Color.parseColor("#660066"), // .3 - Purple
        Color.parseColor("#996600")  // .4 - Gold
    };

    private RadarView radarView;
    private List<EntityInfo> entities = new ArrayList<>();
    private EntityInfo playerEntity;

    private BroadcastReceiver entityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PacketCaptureService.ACTION_ENTITY_UPDATE.equals(intent.getAction())) {
                ArrayList<EntityInfo> updatedEntities = 
                    intent.getParcelableArrayListExtra(PacketCaptureService.EXTRA_ENTITIES);
                if (updatedEntities != null) {
                    entities.clear();
                    entities.addAll(updatedEntities);
                    radarView.invalidate();
                }

                EntityInfo player = intent.getParcelableExtra(PacketCaptureService.EXTRA_PLAYER);
                if (player != null) {
                    playerEntity = player;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        radarView = new RadarView(this);
        setContentView(radarView);
        Log.i(TAG, "RadarActivity created");
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PacketCaptureService.ACTION_ENTITY_UPDATE);
        registerReceiver(entityReceiver, filter);
        Log.i(TAG, "Broadcast receiver registered");
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(entityReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering receiver: " + e.getMessage());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 0, 0, "Settings");
        menu.add(0, 1, 0, "Back");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 0) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == 1) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class RadarView extends View {
        private Paint backgroundPaint, gridPaint, playerPaint, entityPaint, enchantPaint, textPaint, borderPaint;

        public RadarView(Context context) {
            super(context);
            init();
        }

        private void init() {
            backgroundPaint = new Paint();
            backgroundPaint.setColor(Color.parseColor("#cc000000"));
            backgroundPaint.setStyle(Paint.Style.FILL);

            gridPaint = new Paint();
            gridPaint.setColor(Color.parseColor("#333333"));
            gridPaint.setStrokeWidth(1f);
            gridPaint.setStyle(Paint.Style.STROKE);

            playerPaint = new Paint();
            playerPaint.setColor(Color.parseColor("#00FF00"));
            playerPaint.setStyle(Paint.Style.FILL);
            playerPaint.setAntiAlias(true);

            entityPaint = new Paint();
            entityPaint.setStyle(Paint.Style.FILL);
            entityPaint.setAntiAlias(true);

            enchantPaint = new Paint();
            enchantPaint.setStyle(Paint.Style.STROKE);
            enchantPaint.setStrokeWidth(3f);
            enchantPaint.setAntiAlias(true);

            textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(11f);
            textPaint.setAntiAlias(true);

            borderPaint = new Paint();
            borderPaint.setColor(Color.parseColor("#4CAF50"));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            int centerX = width / 2;
            int centerY = height / 2;

            // Draw background
            canvas.drawRect(0, 0, width, height, backgroundPaint);

            // Draw radar border
            int radarRadius = Math.min(width, height) / 2 - 10;
            canvas.drawCircle(centerX, centerY, radarRadius, borderPaint);

            // Draw grid
            drawGrid(canvas, width, height, centerX, centerY);

            // Draw entities
            for (EntityInfo entity : entities) {
                drawEntity(canvas, entity, centerX, centerY, width, height);
            }

            // Draw player at center
            playerPaint.setColor(Color.parseColor("#00FF00"));
            canvas.drawCircle(centerX, centerY, PLAYER_SIZE, playerPaint);
            canvas.drawCircle(centerX, centerY, PLAYER_SIZE + 2, borderPaint);
            canvas.drawText("YOU", centerX - 15, centerY + 4, textPaint);

            // Draw stats
            textPaint.setTextSize(12f);
            canvas.drawText("Entities: " + entities.size(), 10, 25, textPaint);
            if (playerEntity != null) {
                canvas.drawText(String.format("Pos: %.1f, %.1f", playerEntity.getX(), playerEntity.getY()), 10, 45, textPaint);
            }
            canvas.drawText("Protocol18", 10, height - 10, textPaint);
        }

        private void drawGrid(Canvas canvas, int width, int height, int cx, int cy) {
            int step = Math.min(width, height) / 10;
            
            // Draw concentric circles
            for (int i = 1; i <= 4; i++) {
                canvas.drawCircle(cx, cy, step * i, gridPaint);
            }

            // Draw cross lines
            canvas.drawLine(cx, 0, cx, height, gridPaint);
            canvas.drawLine(0, cy, width, cy, gridPaint);
        }

        private void drawEntity(Canvas canvas, EntityInfo entity, int cx, int cy, int width, int height) {
            float screenX, screenY;
            float scale = Math.min(width, height) / (RADAR_RANGE * 2f);

            if (playerEntity != null) {
                // Relative to player
                float relX = entity.getX() - playerEntity.getX();
                float relY = entity.getY() - playerEntity.getY();
                screenX = cx + relX * scale;
                screenY = cy - relY * scale; // Invert Y
            } else {
                // No player reference - use absolute position mod
                screenX = cx + (entity.getX() % 100) * scale;
                screenY = cy - (entity.getY() % 100) * scale;
            }

            // Clamp to radar area
            int radarRadius = Math.min(width, height) / 2 - 20;
            float dx = screenX - cx;
            float dy = screenY - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > radarRadius) {
                screenX = cx + (dx / dist) * radarRadius;
                screenY = cy + (dy / dist) * radarRadius;
            }

            // Get color based on entity type
            int color = entity.getDisplayColor();
            entityPaint.setColor(color);

            // Draw enchantment outline if present
            if (entity.getEnchantment() > 0 && entity.getEnchantment() <= 4) {
                enchantPaint.setColor(entity.getEnchantmentColor());
                canvas.drawCircle(screenX, screenY, ENTITY_SIZE + 3, enchantPaint);
            }

            // Draw entity
            float size = ENTITY_SIZE;
            if (entity.getType() == EntityInfo.EntityType.BOSS || 
                entity.getType() == EntityInfo.EntityType.MINI_BOSS) {
                size = ENTITY_SIZE + 4;
            }
            canvas.drawCircle(screenX, screenY, size, entityPaint);

            // Draw name/tier
            String label = null;
            if (entity.getName() != null && !entity.getName().isEmpty()) {
                label = entity.getName();
            } else if (entity.getTier() > 0) {
                label = "T" + entity.getTier();
                if (entity.getEnchantment() > 0) {
                    label += "." + entity.getEnchantment();
                }
            }

            if (label != null) {
                textPaint.setTextSize(10f);
                float textWidth = textPaint.measureText(label);
                canvas.drawText(label, screenX - textWidth / 2, screenY - size - 5, textPaint);
            }
        }
    }
}
