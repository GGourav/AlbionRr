package com.albionradar.android.parser;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an entity detected in the game world.
 * Based on Albion Online OpenRadar implementation.
 */
public class EntityInfo implements Parcelable {

    public enum EntityType {
        UNKNOWN(0, "#4169E1"),           // Royal Blue
        LIVING_HARVESTABLE(1, "#FFD700"), // Gold
        LIVING_SKINNABLE(2, "#FFD700"),   // Gold
        HARVESTABLE(3, "#FFD700"),        // Gold
        SKINNABLE(4, "#FFD700"),          // Gold
        PLAYER(5, "#00FF00"),             // Green (self)
        ENEMY(6, "#00FF00"),              // Green
        MEDIUM_ENEMY(7, "#FFFF00"),       // Yellow
        ENCHANTED_ENEMY(8, "#9370DB"),    // Purple
        MINI_BOSS(9, "#FF8C00"),          // Orange
        BOSS(10, "#FF0000"),              // Red
        DRONE(11, "#00FFFF"),             // Cyan
        MIST_BOSS(12, "#FF1493"),         // Pink
        EVENT(13, "#FFFFFF"),             // White
        MOB(14, "#00FF00");               // Green

        private final int value;
        private final String color;

        EntityType(int value, String color) {
            this.value = value;
            this.color = color;
        }

        public int getValue() { return value; }
        public String getColor() { return color; }
    }

    private long id;
    private String name;
    private String typeId;
    private EntityType type;
    private float x;
    private float y;
    private int tier;
    private int enchantment;
    private int health;          // Current HP (0-255 normalized)
    private int maxHealth;       // Max HP
    private long lastUpdate;
    private boolean isValid;

    public EntityInfo() {
        this.type = EntityType.UNKNOWN;
        this.tier = 1;
        this.enchantment = 0;
        this.health = 255;
        this.maxHealth = 0;
        this.isValid = true;
        this.lastUpdate = System.currentTimeMillis();
    }

    protected EntityInfo(Parcel in) {
        id = in.readLong();
        name = in.readString();
        typeId = in.readString();
        type = EntityType.valueOf(in.readString());
        x = in.readFloat();
        y = in.readFloat();
        tier = in.readInt();
        enchantment = in.readInt();
        health = in.readInt();
        maxHealth = in.readInt();
        lastUpdate = in.readLong();
        isValid = in.readByte() != 0;
    }

    public static final Creator<EntityInfo> CREATOR = new Creator<EntityInfo>() {
        @Override
        public EntityInfo createFromParcel(Parcel in) {
            return new EntityInfo(in);
        }

        @Override
        public EntityInfo[] newArray(int size) {
            return new EntityInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeString(typeId);
        dest.writeString(type.name());
        dest.writeFloat(x);
        dest.writeFloat(y);
        dest.writeInt(tier);
        dest.writeInt(enchantment);
        dest.writeInt(health);
        dest.writeInt(maxHealth);
        dest.writeLong(lastUpdate);
        dest.writeByte((byte) (isValid ? 1 : 0));
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTypeId() { return typeId; }
    public void setTypeId(String typeId) { this.typeId = typeId; }

    public EntityType getType() { return type; }
    public void setType(EntityType type) { this.type = type; }

    public float getX() { return x; }
    public void setX(float x) { this.x = x; }

    public float getY() { return y; }
    public void setY(float y) { this.y = y; }

    public int getTier() { return tier; }
    public void setTier(int tier) { this.tier = Math.max(1, Math.min(8, tier)); }

    public int getEnchantment() { return enchantment; }
    public void setEnchantment(int enchantment) { this.enchantment = Math.max(0, Math.min(4, enchantment)); }

    public int getHealth() { return health; }
    public void setHealth(int health) { this.health = health; }

    public int getMaxHealth() { return maxHealth; }
    public void setMaxHealth(int maxHealth) { this.maxHealth = maxHealth; }

    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }

    public boolean isValid() { return isValid; }
    public void setValid(boolean valid) { isValid = valid; }

    /**
     * Get display color based on entity type.
     */
    public int getDisplayColor() {
        return android.graphics.Color.parseColor(type.getColor());
    }

    /**
     * Get tier color (for resources).
     */
    public int getTierColor() {
        String[] tierColors = {
            "#1a1a1a", // T1 - Black/Dark Grey
            "#808080", // T2 - Grey
            "#00ff00", // T3 - Green
            "#0066ff", // T4 - Blue
            "#ff0000", // T5 - Red
            "#ff8800", // T6 - Orange
            "#ffff00", // T7 - Yellow
            "#ffffff"  // T8 - White
        };
        int tierIndex = Math.max(0, Math.min(7, tier - 1));
        return android.graphics.Color.parseColor(tierColors[tierIndex]);
    }

    /**
     * Get enchantment outline color.
     */
    public int getEnchantmentColor() {
        String[] enchantColors = {
            "#00000000", // No enchantment (transparent)
            "#006600",   // .1 - Dark Green
            "#000066",   // .2 - Dark Blue
            "#660066",   // .3 - Purple
            "#996600"    // .4 - Gold
        };
        int index = Math.max(0, Math.min(4, enchantment));
        return android.graphics.Color.parseColor(enchantColors[index]);
    }

    /**
     * Get HP percentage (0-100).
     */
    public int getHealthPercent() {
        return Math.round((health / 255f) * 100);
    }

    @Override
    public String toString() {
        return "EntityInfo{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", x=" + x +
                ", y=" + y +
                ", tier=" + tier +
                ", enchantment=" + enchantment +
                '}';
    }
}
