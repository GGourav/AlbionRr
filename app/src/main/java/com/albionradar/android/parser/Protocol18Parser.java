package com.albionradar.android.parser;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Photon GpBinaryV18 protocol.
 * Handles varint-encoded integers and new type codes.
 *
 * Protocol18 Type Codes:
 * - CompressedInt (varint): 9
 * - CompressedLong (varint): 10
 * - String: 7
 * - Dictionary: 20
 * - Hashtable: 21
 * - EventData: 26
 * - Array: 0x40 bitwise OR with element type
 */
public class Protocol18Parser {

    private static final String TAG = "Protocol18Parser";

    // Protocol18 Type Codes
    public static final byte TYPE_NULL = 0;
    public static final byte TYPE_BOOL_FALSE = 1;
    public static final byte TYPE_BOOL_TRUE = 2;
    public static final byte TYPE_BYTE = 3;
    public static final byte TYPE_SHORT = 4;
    public static final byte TYPE_INT = 5;
    public static final byte TYPE_LONG = 6;
    public static final byte TYPE_STRING = 7;
    public static final byte TYPE_FLOAT = 8;
    public static final byte TYPE_COMPRESSED_INT = 9;  // Varint
    public static final byte TYPE_COMPRESSED_LONG = 10; // Varint
    public static final byte TYPE_BYTE_ARRAY = 11;
    public static final byte TYPE_ARRAY = 12;
    public static final byte TYPE_OBJECT_ARRAY = 13;
    public static final byte TYPE_DICTIONARY = 20;
    public static final byte TYPE_HASHTABLE = 21;
    public static final byte TYPE_OPERATION_REQUEST = 22;
    public static final byte TYPE_OPERATION_RESPONSE = 23;
    public static final byte TYPE_EVENT_DATA = 26;

    // Array type marker (0x40 bitwise OR with element type)
    public static final byte TYPE_ARRAY_MARKER = 0x40;

    // Photon Event Codes
    public static final int EVENT_NEW_CHARACTER = 29;
    public static final int EVENT_MOVE = 3;
    public static final int EVENT_SPAWN = 38;
    public static final int EVENT_LEAVE = 252;

    // Entity Type IDs (Albion specific)
    private static final Map<String, EntityInfo.EntityType> ENTITY_TYPE_MAP = new HashMap<>();
    static {
        // Resources
        ENTITY_TYPE_MAP.put("LIVINGHARVESTABLE", EntityInfo.EntityType.LIVING_HARVESTABLE);
        ENTITY_TYPE_MAP.put("LIVINGSKINNABLE", EntityInfo.EntityType.LIVING_SKINNABLE);
        ENTITY_TYPE_MAP.put("HARVESTABLE", EntityInfo.EntityType.HARVESTABLE);
        ENTITY_TYPE_MAP.put("SKINNABLE", EntityInfo.EntityType.SKINNABLE);

        // Enemies
        ENTITY_TYPE_MAP.put("ENEMY", EntityInfo.EntityType.ENEMY);
        ENTITY_TYPE_MAP.put("MEDIUMENEMY", EntityInfo.EntityType.MEDIUM_ENEMY);
        ENTITY_TYPE_MAP.put("ENCHANTEDENEMY", EntityInfo.EntityType.ENCHANTED_ENEMY);
        ENTITY_TYPE_MAP.put("MINIBOSS", EntityInfo.EntityType.MINI_BOSS);
        ENTITY_TYPE_MAP.put("BOSS", EntityInfo.EntityType.BOSS);
        ENTITY_TYPE_MAP.put("DRONE", EntityInfo.EntityType.DRONE);
        ENTITY_TYPE_MAP.put("MISTBOSS", EntityInfo.EntityType.MIST_BOSS);

        // Other
        ENTITY_TYPE_MAP.put("EVENT", EntityInfo.EntityType.EVENT);
        ENTITY_TYPE_MAP.put("PLAYER", EntityInfo.EntityType.PLAYER);
        ENTITY_TYPE_MAP.put("MOB", EntityInfo.EntityType.MOB);
        ENTITY_TYPE_MAP.put("TREASURE", EntityInfo.EntityType.TREASURE);
    }

    private DataInputStream input;
    private byte[] data;
    private int position;

    public Protocol18Parser() {}

    /**
     * Parse a Photon packet and extract entities.
     */
    public List<EntityInfo> parsePacket(byte[] packetData) {
        List<EntityInfo> entities = new ArrayList<>();

        if (packetData == null || packetData.length < 12) {
            return entities;
        }

        try {
            this.data = packetData;
            this.input = new DataInputStream(new ByteArrayInputStream(packetData));
            this.position = 0;

            // Parse UDP header (first 12 bytes for Photon)
            int peerId = readShort();
            int flags = readByte();
            int commandCount = readByte();
            int timestamp = readInt();
            int challenge = readInt();

            Log.d(TAG, String.format("Photon packet: peerId=%d, flags=%d, commands=%d",
                    peerId, flags, commandCount));

            // Parse commands
            for (int i = 0; i < commandCount && position < data.length; i++) {
                parseCommand(entities);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error parsing packet: " + e.getMessage());
        } finally {
            try {
                if (input != null) input.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        return entities;
    }

    private void parseCommand(List<EntityInfo> entities) throws IOException {
        if (position + 2 > data.length) return;

        int commandType = readByte();
        int channel = readByte();
        int commandLength = readShort();

        Log.d(TAG, String.format("Command: type=%d, channel=%d, length=%d",
                commandType, channel, commandLength));

        int startPos = position;

        switch (commandType) {
            case 4: // Event
                parseEvent(entities);
                break;
            case 2: // OperationRequest
            case 3: // OperationResponse
                skip(commandLength - (position - startPos));
                break;
            default:
                skip(commandLength - (position - startPos));
                break;
        }
    }

    private void parseEvent(List<EntityInfo> entities) throws IOException {
        int start = position;

        // Read reliable sequence number if present
        int sequenceNumber = readInt();

        // Read event code
        int eventCode = readVarint();

        Log.d(TAG, String.format("Event code: %d", eventCode));

        // Parse event data based on event code
        switch (eventCode) {
            case EVENT_NEW_CHARACTER:
                parseNewCharacterEvent(entities);
                break;
            case EVENT_MOVE:
                parseMoveEvent(entities);
                break;
            case EVENT_LEAVE:
                parseLeaveEvent(entities);
                break;
            default:
                Log.d(TAG, "Unhandled event code: " + eventCode);
                break;
        }
    }

    private void parseNewCharacterEvent(List<EntityInfo> entities) throws IOException {
        EntityInfo entity = new EntityInfo();

        // Read parameter count
        int paramCount = readVarint();

        Log.d(TAG, "NewCharacter event with " + paramCount + " parameters");

        for (int i = 0; i < paramCount; i++) {
            int paramKey = readVarint();
            Object value = readValue();

            Log.d(TAG, String.format("Parameter %d: %s", paramKey, value));

            // Parameter 0 is typically the entity ID
            if (paramKey == 0 && value instanceof Long) {
                entity.setId((Long) value);
            }
            // Parameter 1 is typically the position (Vector2)
            else if (paramKey == 1 && value instanceof Map) {
                Map<?, ?> posMap = (Map<?, ?>) value;
                if (posMap.containsKey("x")) {
                    entity.setX(toFloat(posMap.get("x")));
                }
                if (posMap.containsKey("y")) {
                    entity.setY(toFloat(posMap.get("y")));
                }
            }
            // Parameter 10 is typically the type string
            else if (paramKey == 10 && value instanceof String) {
                entity.setTypeId((String) value);
                entity.setType(getEntityType((String) value));
            }
            // Parameter 11 is typically the name
            else if (paramKey == 11 && value instanceof String) {
                entity.setName((String) value);
            }
        }

        // Extract tier from type ID (format: T{tier}_{type})
        if (entity.getTypeId() != null) {
            parseTierFromTypeId(entity);
        }

        if (entity.getId() != 0) {
            entities.add(entity);
            Log.d(TAG, "Detected entity: " + entity);
        }
    }

    private void parseMoveEvent(List<EntityInfo> entities) throws IOException {
        EntityInfo entity = new EntityInfo();

        int paramCount = readVarint();

        for (int i = 0; i < paramCount; i++) {
            int paramKey = readVarint();
            Object value = readValue();

            // Position update
            if (paramKey == 0 && value instanceof Long) {
                entity.setId((Long) value);
            }
            else if (paramKey == 1 && value instanceof Map) {
                Map<?, ?> posMap = (Map<?, ?>) value;
                if (posMap.containsKey("x")) {
                    entity.setX(toFloat(posMap.get("x")));
                }
                if (posMap.containsKey("y")) {
                    entity.setY(toFloat(posMap.get("y")));
                }
            }
        }

        if (entity.getId() != 0 && (entity.getX() != 0 || entity.getY() != 0)) {
            entities.add(entity);
        }
    }

    private void parseLeaveEvent(List<EntityInfo> entities) throws IOException {
        int paramCount = readVarint();

        for (int i = 0; i < paramCount; i++) {
            int paramKey = readVarint();
            Object value = readValue();

            // Create invalid entity to signal removal
            if (paramKey == 0 && value instanceof Long) {
                EntityInfo entity = new EntityInfo();
                entity.setId((Long) value);
                entity.setValid(false);
                entities.add(entity);
            }
        }
    }

    /**
     * Read a Protocol18 value based on type code.
     */
    private Object readValue() throws IOException {
        if (position >= data.length) return null;

        byte typeCode = readByte();

        switch (typeCode) {
            case TYPE_NULL:
                return null;

            case TYPE_BOOL_FALSE:
                return false;

            case TYPE_BOOL_TRUE:
                return true;

            case TYPE_BYTE:
                return readByte() & 0xFF;

            case TYPE_SHORT:
                return readShort();

            case TYPE_INT:
                return readInt();

            case TYPE_LONG:
                return readLong();

            case TYPE_STRING:
                return readString();

            case TYPE_FLOAT:
                return Float.intBitsToFloat(readInt());

            case TYPE_COMPRESSED_INT:
                return readVarint();

            case TYPE_COMPRESSED_LONG:
                return readVarintLong();

            case TYPE_BYTE_ARRAY:
                return readByteArray();

            case TYPE_DICTIONARY:
            case TYPE_HASHTABLE:
                return readDictionary();

            case TYPE_EVENT_DATA:
                return readEventData();

            default:
                // Check for array type (0x40 OR'd with element type)
                if ((typeCode & TYPE_ARRAY_MARKER) != 0) {
                    return readArray((byte)(typeCode & 0x3F));  // FIXED: Added (byte) cast
                }
                Log.w(TAG, "Unknown type code: " + typeCode);
                return null;
        }
    }

    /**
     * Read varint-encoded integer (Protocol18).
     * Each byte uses 7 bits for data, 1 bit as continuation flag.
     */
    private int readVarint() throws IOException {
        int result = 0;
        int shift = 0;

        while (shift < 32) {
            if (position >= data.length) break;
            byte b = data[position++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }

        return result;
    }

    /**
     * Read varint-encoded long (Protocol18).
     */
    private long readVarintLong() throws IOException {
        long result = 0;
        int shift = 0;

        while (shift < 64) {
            if (position >= data.length) break;
            byte b = data[position++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }

        return result;
    }

    private String readString() throws IOException {
        int length = readVarint();
        if (length == 0) return "";

        if (position + length > data.length) {
            length = data.length - position;
        }

        String result = new String(data, position, length, "UTF-8");
        position += length;
        return result;
    }

    private byte[] readByteArray() throws IOException {
        int length = readVarint();
        if (length == 0) return new byte[0];

        if (position + length > data.length) {
            length = data.length - position;
        }

        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        position += length;
        return result;
    }

    private Map<String, Object> readDictionary() throws IOException {
        Map<String, Object> map = new HashMap<>();
        int count = readVarint();

        for (int i = 0; i < count; i++) {
            Object key = readValue();
            Object value = readValue();

            if (key != null) {
                map.put(String.valueOf(key), value);
            }
        }

        return map;
    }

    private Object readEventData() throws IOException {
        int eventCode = readVarint();
        return readDictionary();
    }

    private Object[] readArray(byte elementType) throws IOException {
        int length = readVarint();
        Object[] array = new Object[length];

        for (int i = 0; i < length; i++) {
            array[i] = readValue();
        }

        return array;
    }

    // Low-level read methods
    private byte readByte() throws IOException {
        if (position >= data.length) return 0;
        return data[position++];
    }

    private short readShort() throws IOException {
        if (position + 2 > data.length) return 0;
        short result = (short) ((data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8));
        position += 2;
        return result;
    }

    private int readInt() throws IOException {
        if (position + 4 > data.length) return 0;
        int result = ByteBuffer.wrap(data, position, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
        position += 4;
        return result;
    }

    private long readLong() throws IOException {
        if (position + 8 > data.length) return 0;
        long result = ByteBuffer.wrap(data, position, 8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
        position += 8;
        return result;
    }

    private void skip(int bytes) throws IOException {
        position += bytes;
        if (position > data.length) {
            position = data.length;
        }
    }

    private float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return 0f;
    }

    private EntityInfo.EntityType getEntityType(String typeId) {
        if (typeId == null) return EntityInfo.EntityType.UNKNOWN;

        String upper = typeId.toUpperCase();
        for (Map.Entry<String, EntityInfo.EntityType> entry : ENTITY_TYPE_MAP.entrySet()) {
            if (upper.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return EntityInfo.EntityType.UNKNOWN;
    }

    private void parseTierFromTypeId(EntityInfo entity) {
        String typeId = entity.getTypeId();
        if (typeId == null) return;

        // Format: T{tier}_{type} or T{tier}_{type}_@
        // Enchantment format: T{tier}_{type}_@{enchantLevel}

        if (typeId.startsWith("T") && typeId.length() >= 2) {
            try {
                int underscoreIndex = typeId.indexOf('_');
                if (underscoreIndex > 1) {
                    String tierStr = typeId.substring(1, underscoreIndex);
                    entity.setTier(Integer.parseInt(tierStr));
                }

                // Check for enchantment marker
                int atIndex = typeId.indexOf("_@");
                if (atIndex > 0 && atIndex + 2 < typeId.length()) {
                    char enchantChar = typeId.charAt(atIndex + 2);
                    if (Character.isDigit(enchantChar)) {
                        entity.setEnchantment(enchantChar - '0');
                    }
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse tier from: " + typeId);
            }
        }
    }
}
