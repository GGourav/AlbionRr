package com.albionradar.android.parser;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an entity detected in the game world.
 */
public class EntityInfo implements Parcelable {

    public enum EntityType {
        UNKNOWN,
        PLAYER,
        LIVING_HARVESTABLE,
        LIVING_SKINNABLE,
        ENEMY,
        MEDIUM_ENEMY,
        ENCHANTED_ENEMY,
        MINI_BOSS,
        BOSS,
        DRONE,
        MIST_BOSS,
        EVENT,
        MOB,
        RESOURCE,
        TREASURE,
        HARVESTABLE,
        SKINNABLE
    }

    private long id;
    private String name;
    private String typeId;
    private EntityType type;
    private float x;
    private float y;
    private int tier;
    private int enchantment;
    private long lastUpdate;
    private boolean isValid;

    public EntityInfo() {
        this.type = EntityType.UNKNOWN;
        this.tier = 1;
        this.enchantment = 0;
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
    public void setTier(int tier) { this.tier = tier; }

    public int getEnchantment() { return enchantment; }
    public void setEnchantment(int enchantment) { this.enchantment = enchantment; }

    public long getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(long lastUpdate) { this.lastUpdate = lastUpdate; }

    public boolean isValid() { return isValid; }
    public void setValid(boolean valid) { isValid = valid; }

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
