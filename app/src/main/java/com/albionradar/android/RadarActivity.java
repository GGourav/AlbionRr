package com.albionradar.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.albionradar.android.network.PacketCaptureService;
import com.albionradar.android.parser.EntityInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Main radar display activity.
 * Shows entities on a minimap-style radar overlay.
 */
public class RadarActivity extends AppCompatActivity {

    private static final String TAG = "RadarActivity";
    
    // Radar settings
    private static final int RADAR_RANGE = 50; // In-game coordinate units
    private static final float PLAYER_SIZE = 8f;
    private static final float ENTITY_SIZE = 6f;
    
    // Tier colors
    private static final int[] TIER_COLORS = {
        Color.parseColor("#1a1a1a"), // T1 - Black/Dark Grey
        Color.parseColor("#808080"), // T2 - Grey
        Color.parseColor("#00ff00"), // T3 - Green
        Color.parseColor("#0066ff"), // T4 - Blue
        Color.parseColor("#ff0000"), // T5 - Red
        Color.parseColor("#ff8800"), // T6 - Orange
        Color.parseColor("#ffff00"), // T7 - Yellow
        Color.parseColor("#ffffff")  // T8 - White
    };
    
    // Enchantment outline colors
    private static final int[] ENCHANTMENT_COLORS = {
        Color.TRANSPARENT,      // No enchantment
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
                    if (radarView != null) {
                        radarView.invalidate();
                    }
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
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PacketCaptureService.ACTION_ENTITY_UPDATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(entityReceiver, filter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(entityReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 0, 0, "Settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 0) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Custom view for rendering the radar display.
     */
    private class RadarView extends View {
        private Paint backgroundPaint;
        private Paint gridPaint;
        private Paint playerPaint;
        private Paint entityPaint;
        private Paint enchantPaint;
        private Paint textPaint;

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
            playerPaint.setColor(Color.parseColor("#00ff00"));
            playerPaint.setStyle(Paint.Style.FILL);

            entityPaint = new Paint();
            entityPaint.setStyle(Paint.Style.FILL);

            enchantPaint = new Paint();
            enchantPaint.setStyle(Paint.Style.STROKE);
            enchantPaint.setStrokeWidth(2f);

            textPaint = new Paint();
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(12f);
            textPaint.setAntiAlias(true);
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

            // Draw grid
            drawGrid(canvas, width, height);

            // Draw entities
            synchronized (entities) {
                for (EntityInfo entity : entities) {
                    drawEntity(canvas, entity, centerX, centerY, width, height);
                }
            }

            // Draw player at center
            canvas.drawCircle(centerX, centerY, PLAYER_SIZE, playerPaint);
            canvas.drawText("YOU", centerX - 15, centerY + 4, textPaint);
        }

        private void drawGrid(Canvas canvas, int width, int height) {
            int step = Math.min(width, height) / 10;
            for (int x = 0; x < width; x += step) {
                canvas.drawLine(x, 0, x, height, gridPaint);
            }
            for (int y = 0; y < height; y += step) {
                canvas.drawLine(0, y, width, y, gridPaint);
            }
        }

        private void drawEntity(Canvas canvas, EntityInfo entity, 
                                int centerX, int centerY, int width, int height) {
            if (playerEntity == null) return;

            // Calculate relative position
            float relX = entity.getX() - playerEntity.getX();
            float relY = entity.getY() - playerEntity.getY();

            // Scale to screen coordinates
            float scale = Math.min(width, height) / (RADAR_RANGE * 2f);
            float screenX = centerX + relX * scale;
            float screenY = centerY - relY * scale;

            // Clamp to visible area
            screenX = Math.max(ENTITY_SIZE, Math.min(width - ENTITY_SIZE, screenX));
            screenY = Math.max(ENTITY_SIZE, Math.min(height - ENTITY_SIZE, screenY));

            // Set tier color
            int tier = Math.max(0, Math.min(7, entity.getTier() - 1));
            entityPaint.setColor(TIER_COLORS[tier]);

            // Draw enchantment outline if present
            if (entity.getEnchantment() > 0 && entity.getEnchantment() <= 4) {
                enchantPaint.setColor(ENCHANTMENT_COLORS[entity.getEnchantment()]);
                canvas.drawCircle(screenX, screenY, ENTITY_SIZE + 2, enchantPaint);
            }

            // Draw entity circle
            canvas.drawCircle(screenX, screenY, ENTITY_SIZE, entityPaint);

            // Draw name if available
            if (entity.getName() != null && !entity.getName().isEmpty()) {
                canvas.drawText(entity.getName(), screenX - 20, screenY - ENTITY_SIZE - 5, textPaint);
            }
        }
    }
}
