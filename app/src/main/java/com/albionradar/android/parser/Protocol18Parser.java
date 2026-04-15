package com.albionradar.android.parser;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Photon GpBinaryV18 protocol.
 * This is the NEW protocol used by Albion Online since the April 2026 patch.
 *
 * PROTOCOL18 MAJOR CHANGES FROM PROTOCOL16:
 * 1. Type codes are NUMERIC (0-42), NOT ASCII characters
 * 2. Integers use VARINT encoding with zigzag for signed values
 * 3. Primitives use LITTLE-ENDIAN (not big-endian)
 * 4. Zero-value type codes for common defaults (no payload needed)
 * 5. Slim custom types (0x80+) omit the customId byte
 * 6. Bit-packed boolean arrays
 *
 * Reference: https://github.com/Nouuu/Albion-Online-OpenRadar (fix/photon-protocol18 branch)
 */
public class Protocol18Parser {

    private static final String TAG = "Protocol18Parser";
    private static final boolean DEBUG = false;

    // === PROTOCOL18 TYPE CODES (NUMERIC, NOT ASCII!) ===

    // Null/Unknown
    public static final byte TYPE_UNKNOWN = 0;
    public static final byte TYPE_BOOLEAN = 2;
    public static final byte TYPE_BYTE = 3;
    public static final byte TYPE_SHORT = 4;
    public static final byte TYPE_FLOAT = 5;
    public static final byte TYPE_DOUBLE = 6;
    public static final byte TYPE_STRING = 7;
    public static final byte TYPE_NULL = 8;

    // Compressed integers (NEW in Protocol18!)
    public static final byte TYPE_COMPRESSED_INT = 9;   // Varint-encoded int32
    public static final byte TYPE_COMPRESSED_LONG = 10; // Varint-encoded int64

    // Small integers (NEW in Protocol18!)
    public static final byte TYPE_INT1 = 11;     // 1-byte positive int
    public static final byte TYPE_INT1_NEG = 12; // 1-byte negative int
    public static final byte TYPE_INT2 = 13;     // 2-byte positive int
    public static final byte TYPE_INT2_NEG = 14; // 2-byte negative int
    public static final byte TYPE_LONG1 = 15;    // 1-byte positive long
    public static final byte TYPE_LONG1_NEG = 16; // 1-byte negative long
    public static final byte TYPE_LONG2 = 17;    // 2-byte positive long
    public static final byte TYPE_LONG2_NEG = 18; // 2-byte negative long

    // Complex types
    public static final byte TYPE_CUSTOM = 19;        // ByteArray with customId
    public static final byte TYPE_DICTIONARY = 20;
    public static final byte TYPE_HASHTABLE = 21;
    public static final byte TYPE_OBJECT_ARRAY = 23;
    public static final byte TYPE_OPERATION_REQUEST = 24;
    public static final byte TYPE_OPERATION_RESPONSE = 25;
    public static final byte TYPE_EVENT_DATA = 26;

    // Zero-value types (NEW in Protocol18! - no payload needed)
    public static final byte TYPE_BOOL_FALSE = 27;
    public static final byte TYPE_BOOL_TRUE = 28;
    public static final byte TYPE_SHORT_ZERO = 29;
    public static final byte TYPE_INT_ZERO = 30;
    public static final byte TYPE_LONG_ZERO = 31;
    public static final byte TYPE_FLOAT_ZERO = 32;
    public static final byte TYPE_DOUBLE_ZERO = 33;
    public static final byte TYPE_BYTE_ZERO = 34;

    // Array marker
    public static final byte TYPE_ARRAY = 0x40;       // 64 - array type prefix
    public static final byte CUSTOM_TYPE_SLIM_BASE = (byte) 0x80; // 128+ slim customs

    // Constants
    private static final int MAX_ARRAY_SIZE = 65536;

    // Command types
    private static final int CMD_DISCONNECT = 4;
    private static final int CMD_RELIABLE = 6;
    private static final int CMD_UNRELIABLE = 7;
    private static final int CMD_FRAGMENT = 8;

    // Message types
    private static final int MSG_REQUEST = 2;
    private static final int MSG_RESPONSE = 3;
    private static final int MSG_EVENT = 4;
    private static final int MSG_RESPONSE_ALT = 7;
    private static final int MSG_ENCRYPTED = (byte) 131;

    // Event codes (dispatch byte)
    private static final int EVENT_MOVE = 3;  // Hot path for position updates

    // Real event codes (from params[252])
    private static final int REAL_EVENT_LEAVE = 1;
    private static final int REAL_EVENT_NEW_CHARACTER = 29;
    private static final int REAL_EVENT_NEW_HARVESTABLE_LIST = 39;
    private static final int REAL_EVENT_NEW_MOB = 40;

    // Fragment reassembly
    private static final int MAX_PENDING_SEGMENTS = 64;
    private Map<Integer, SegmentedPackage> pendingSegments = new HashMap<>();

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
            this.position = 0;

            // Parse Photon header (12 bytes)
            // [peerId:2][flags:1][commandCount:1][timestamp:4][challenge:4]
            int peerId = readUint16BE();
            int flags = readUint8();
            int commandCount = readUint8();
            long timestamp = readUint32BE();
            int challenge = readUint32BE();

            if (DEBUG) Log.d(TAG, String.format("Packet: peer=%d flags=%d cmds=%d", peerId, flags, commandCount));

            // Check for encrypted traffic
            if (flags == 1) {
                if (DEBUG) Log.w(TAG, "Encrypted packet detected, skipping");
                return entities;
            }

            // Parse commands
            for (int i = 0; i < commandCount && position < data.length; i++) {
                parseCommand(entities);
            }

        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }

        return entities;
    }

    private void parseCommand(List<EntityInfo> entities) {
        if (position + 12 > data.length) return;

        int startPos = position;

        // Command header (12 bytes):
        // [cmdType:1][channelId:1][cmdFlags:1][reserved:1][cmdLength:4][seqNumber:4]
        int cmdType = readUint8();
        int channelId = readUint8();
        int cmdFlags = readUint8();
        position++; // reserved
        int cmdLength = readUint32BE();
        int seqNumber = readUint32BE();

        int payloadLen = cmdLength - 12;
        if (payloadLen <= 0 || position + payloadLen > data.length) {
            return;
        }

        int payloadStart = position;

        switch (cmdType) {
            case CMD_DISCONNECT:
                break;

            case CMD_UNRELIABLE:
                // Unreliable has 4 extra bytes before payload
                position += 4;
                payloadLen -= 4;
                parseReliableCommand(payloadLen, entities);
                break;

            case CMD_RELIABLE:
                parseReliableCommand(payloadLen, entities);
                break;

            case CMD_FRAGMENT:
                parseFragment(payloadLen, entities);
                break;

            default:
                // Unknown command type, skip
                break;
        }

        // Ensure we're at the correct position
        position = payloadStart + payloadLen;
    }

    private void parseReliableCommand(int payloadLen, List<EntityInfo> entities) {
        if (payloadLen < 2) return;

        // [signalByte:1][msgType:1][payload...]
        position++; // skip signal byte
        int msgType = readUint8();
        payloadLen -= 2;

        if (payloadLen <= 0) return;

        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, position, payload, 0, payloadLen);
        position += payloadLen;

        if (msgType == MSG_ENCRYPTED) {
            if (DEBUG) Log.w(TAG, "Encrypted message detected");
            return;
        }

        switch (msgType) {
            case MSG_EVENT:
                parseEvent(payload, entities);
                break;
            case MSG_REQUEST:
                // Handle request if needed
                break;
            case MSG_RESPONSE:
            case MSG_RESPONSE_ALT:
                // Handle response if needed
                break;
        }
    }

    private void parseFragment(int payloadLen, List<EntityInfo> entities) {
        // Fragment header (20 bytes):
        // [startSeq:4][fragCount:4][fragNum:4][totalLen:4][fragOffset:4]
        if (payloadLen < 20) return;

        int startSeq = readUint32BE();
        position += 4; // fragCount
        position += 4; // fragNum
        int totalLen = readUint32BE();
        int fragOffset = readUint32BE();

        int fragDataLen = payloadLen - 20;
        if (fragDataLen <= 0 || totalLen > MAX_ARRAY_SIZE * 16) return;

        // Get or create segment
        SegmentedPackage seg = pendingSegments.get(startSeq);
        if (seg == null) {
            if (pendingSegments.size() >= MAX_PENDING_SEGMENTS) {
                // Evict oldest
                evictOldestSegment();
            }
            seg = new SegmentedPackage(totalLen);
            pendingSegments.put(startSeq, seg);
        }

        // Copy fragment data
        if (fragOffset >= 0 && fragOffset + fragDataLen <= seg.payload.length) {
            System.arraycopy(data, position, seg.payload, fragOffset, fragDataLen);
            seg.bytesWritten += fragDataLen;
        }

        position += fragDataLen;

        // Check if complete
        if (seg.bytesWritten >= seg.totalLength) {
            pendingSegments.remove(startSeq);
            // Process reassembled packet
            byte[] reassembled = seg.payload;
            List<EntityInfo> newEntities = parsePacket(reassembled);
            entities.addAll(newEntities);
        }
    }

    private void evictOldestSegment() {
        if (pendingSegments.isEmpty()) return;
        Integer oldestKey = pendingSegments.keySet().iterator().next();
        pendingSegments.remove(oldestKey);
    }

    private void parseEvent(byte[] payload, List<EntityInfo> entities) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

            // Read event code (dispatch byte)
            int eventCode = buf.get() & 0xFF;

            // Read parameter table
            Map<Integer, Object> params = readParameterTable(buf);

            // Post-process: inject params[252] if missing
            if (!params.containsKey(252)) {
                params.put(252, eventCode);
            }

            // Event 3 (Move) - extract positions
            if (eventCode == EVENT_MOVE) {
                extractMovePositions(params);
                parseMove(params, entities);
                return;
            }

            // For other events, use real code from params[252]
            int realCode = params.containsKey(252) ?
                ((Number) params.get(252)).intValue() : eventCode;

            if (DEBUG) Log.d(TAG, "Event " + eventCode + " (real=" + realCode + ") params=" + params.size());

            switch (realCode) {
                case REAL_EVENT_NEW_CHARACTER:
                    parseNewCharacter(params, entities);
                    break;
                case REAL_EVENT_LEAVE:
                    parseLeave(params, entities);
                    break;
                case REAL_EVENT_NEW_MOB:
                    parseNewMob(params, entities);
                    break;
                case REAL_EVENT_NEW_HARVESTABLE_LIST:
                    parseHarvestableList(params, entities);
                    break;
            }

        } catch (Exception e) {
            Log.w(TAG, "Event parse error: " + e.getMessage());
        }
    }

    // === EVENT PARSERS ===

    private void parseNewCharacter(Map<Integer, Object> params, List<EntityInfo> entities) {
        EntityInfo entity = new EntityInfo();

        // Parameter 0: Entity ID
        Object idObj = params.get(0);
        if (idObj instanceof Long) {
            entity.setId((Long) idObj);
        } else if (idObj instanceof Integer) {
            entity.setId(((Integer) idObj).longValue());
        } else if (idObj instanceof Short) {
            entity.setId(((Short) idObj).longValue());
        } else if (idObj instanceof Byte) {
            entity.setId(((Byte) idObj).longValue());
        }

        // Parameter 1: Name
        Object nameObj = params.get(1);
        if (nameObj instanceof String) {
            entity.setName((String) nameObj);
        }

        // Parameter 8: Position (float array or separate floats)
        Object posObj = params.get(8);
        if (posObj instanceof float[]) {
            float[] pos = (float[]) posObj;
            if (pos.length >= 2) {
                entity.setX(pos[0]);
                entity.setY(pos[1]);
            }
        } else if (posObj instanceof Object[]) {
            Object[] pos = (Object[]) posObj;
            if (pos.length >= 2) {
                entity.setX(toFloat(pos[0]));
                entity.setY(toFloat(pos[1]));
            }
        }

        // Alternative position: params[4] and params[5] (injected by move position extraction)
        if (params.containsKey(4)) {
            entity.setX(toFloat(params.get(4)));
        }
        if (params.containsKey(5)) {
            entity.setY(toFloat(params.get(5)));
        }

        // Detect player vs other entities
        if (nameObj instanceof String && entity.getId() != 0) {
            entity.setType(EntityInfo.EntityType.PLAYER);
            entities.add(entity);
            if (DEBUG) Log.d(TAG, "NewCharacter: " + entity);
        }
    }

    private void parseMove(Map<Integer, Object> params, List<EntityInfo> entities) {
        EntityInfo entity = new EntityInfo();

        // Parameter 0: Entity ID
        Object idObj = params.get(0);
        if (idObj instanceof Long) {
            entity.setId((Long) idObj);
        } else if (idObj instanceof Integer) {
            entity.setId(((Integer) idObj).longValue());
        } else if (idObj instanceof Short) {
            entity.setId(((Short) idObj).longValue());
        } else if (idObj instanceof Byte) {
            entity.setId(((Byte) idObj).longValue());
        }

        // Positions are extracted in extractMovePositions
        if (params.containsKey(4)) {
            entity.setX(toFloat(params.get(4)));
        }
        if (params.containsKey(5)) {
            entity.setY(toFloat(params.get(5)));
        }

        if (entity.getId() != 0 && (entity.getX() != 0 || entity.getY() != 0)) {
            entities.add(entity);
        }
    }

    private void parseLeave(Map<Integer, Object> params, List<EntityInfo> entities) {
        Object idObj = params.get(0);
        if (idObj instanceof Long || idObj instanceof Integer ||
            idObj instanceof Short || idObj instanceof Byte) {
            EntityInfo entity = new EntityInfo();
            entity.setId(toLong(idObj));
            entity.setValid(false);
            entities.add(entity);
        }
    }

    private void parseNewMob(Map<Integer, Object> params, List<EntityInfo> entities) {
        EntityInfo entity = new EntityInfo();

        // Parameter 0: Mob ID
        Object idObj = params.get(0);
        if (idObj instanceof Long || idObj instanceof Integer ||
            idObj instanceof Short || idObj instanceof Byte) {
            entity.setId(toLong(idObj));
        }

        // Parameter 1: Type ID string
        Object typeIdObj = params.get(1);
        if (typeIdObj instanceof String) {
            entity.setTypeId((String) typeIdObj);
            parseTierFromTypeId(entity);
        } else if (typeIdObj instanceof Integer || typeIdObj instanceof Short) {
            entity.setTypeId(String.valueOf(toInt(typeIdObj)));
        }

        // Parameter 8: Position (may be float array)
        Object posObj = params.get(8);
        if (posObj instanceof float[]) {
            float[] pos = (float[]) posObj;
            if (pos.length >= 2) {
                entity.setX(pos[0]);
                entity.setY(pos[1]);
            }
        } else if (posObj instanceof Object[]) {
            Object[] pos = (Object[]) posObj;
            if (pos.length >= 2) {
                entity.setX(toFloat(pos[0]));
                entity.setY(toFloat(pos[1]));
            }
        }

        // Parameter 9: Rotation (optional)

        entity.setType(EntityInfo.EntityType.MOB);

        if (entity.getId() != 0) {
            entities.add(entity);
            if (DEBUG) Log.d(TAG, "NewMob: " + entity);
        }
    }

    private void parseHarvestableList(Map<Integer, Object> params, List<EntityInfo> entities) {
        // Parameter 0: IDs (short array)
        // Parameter 3: Positions (float array - packed X/Y pairs)

        Object idsObj = params.get(0);
        Object posObj = params.get(3);

        if (idsObj instanceof short[]) {
            short[] ids = (short[]) idsObj;

            float[] positions = null;
            if (posObj instanceof float[]) {
                positions = (float[]) posObj;
            }

            for (int i = 0; i < ids.length; i++) {
                EntityInfo entity = new EntityInfo();
                entity.setId(ids[i]);

                if (positions != null && i * 2 + 1 < positions.length) {
                    entity.setX(positions[i * 2]);
                    entity.setY(positions[i * 2 + 1]);
                }

                entity.setType(EntityInfo.EntityType.HARVESTABLE);
                entities.add(entity);
            }
        }
    }

    /**
     * Extract move positions from ByteArray at params[1].
     * Protocol18 move event has positions at offsets 9 and 13 (Little-Endian float32).
     */
    private void extractMovePositions(Map<Integer, Object> params) {
        Object rawObj = params.get(1);
        if (!(rawObj instanceof byte[])) return;

        byte[] raw = (byte[]) rawObj;
        if (raw.length < 17) return;

        // Positions at offset 9 and 13 (Little-Endian float32)
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        params.put(4, buf.getFloat(9));
        params.put(5, buf.getFloat(13));
    }

    // === DESERIALIZATION ===

    private Map<Integer, Object> readParameterTable(ByteBuffer buf) {
        Map<Integer, Object> result = new HashMap<>();

        int count = readCompressedUint32(buf);
        if (count < 0 || count > MAX_ARRAY_SIZE) return result;

        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            int key = buf.get() & 0xFF;
            byte typeCode = buf.get();
            Object value = deserialize(buf, typeCode);
            result.put(key, value);
        }

        return result;
    }

    private Object deserialize(ByteBuffer buf, int typeCode) {
        // Slim custom types (0x80+)
        if (typeCode >= CUSTOM_TYPE_SLIM_BASE) {
            return deserializeCustom(buf, typeCode);
        }

        switch (typeCode) {
            case TYPE_UNKNOWN:
            case TYPE_NULL:
                return null;

            case TYPE_BOOLEAN:
                return (buf.get() != 0);

            case TYPE_BYTE:
                return buf.get() & 0xFF;

            case TYPE_SHORT:
                return buf.getShort();

            case TYPE_FLOAT:
                return buf.getFloat();

            case TYPE_DOUBLE:
                return buf.getDouble();

            case TYPE_STRING:
                return readString(buf);

            case TYPE_COMPRESSED_INT:
                return readCompressedInt32(buf);

            case TYPE_COMPRESSED_LONG:
                return readCompressedInt64(buf);

            case TYPE_INT1:
                return (int) (buf.get() & 0xFF);

            case TYPE_INT1_NEG:
                return -(int) (buf.get() & 0xFF);

            case TYPE_INT2:
                return (int) (buf.getShort() & 0xFFFF);

            case TYPE_INT2_NEG:
                return -(int) (buf.getShort() & 0xFFFF);

            case TYPE_LONG1:
                return (long) (buf.get() & 0xFF);

            case TYPE_LONG1_NEG:
                return -(long) (buf.get() & 0xFF);

            case TYPE_LONG2:
                return (long) (buf.getShort() & 0xFFFF);

            case TYPE_LONG2_NEG:
                return -(long) (buf.getShort() & 0xFFFF);

            case TYPE_CUSTOM:
                return deserializeCustom(buf, 0);

            case TYPE_DICTIONARY:
            case TYPE_HASHTABLE:
                return deserializeDictionary(buf);

            case TYPE_OBJECT_ARRAY:
                return deserializeObjectArray(buf);

            case TYPE_OPERATION_REQUEST:
                return deserializeOperationRequest(buf);

            case TYPE_OPERATION_RESPONSE:
                return deserializeOperationResponse(buf);

            case TYPE_EVENT_DATA:
                return deserializeEventData(buf);

            // Zero-value types (no payload)
            case TYPE_BOOL_FALSE:
                return false;
            case TYPE_BOOL_TRUE:
                return true;
            case TYPE_SHORT_ZERO:
                return (short) 0;
            case TYPE_INT_ZERO:
                return 0;
            case TYPE_LONG_ZERO:
                return 0L;
            case TYPE_FLOAT_ZERO:
                return 0.0f;
            case TYPE_DOUBLE_ZERO:
                return 0.0;
            case TYPE_BYTE_ZERO:
                return (byte) 0;

            default:
                // Check for typed array (typeCode = TYPE_ARRAY | elemType)
                if ((typeCode & TYPE_ARRAY) == TYPE_ARRAY) {
                    int elemType = typeCode & ~TYPE_ARRAY;
                    return deserializeTypedArray(buf, elemType);
                }
                // Unknown type
                if (DEBUG) Log.w(TAG, "Unknown type code: " + typeCode);
                return null;
        }
    }

    // === VARINT READERS (Protocol18 specific) ===

    /**
     * Read compressed unsigned varint (up to 32 bits).
     * Uses 7 bits per byte, high bit = continuation.
     */
    private int readCompressedUint32(ByteBuffer buf) {
        int value = 0;
        int shift = 0;

        while (buf.hasRemaining()) {
            byte b = buf.get();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 35) {
                return 0; // Overflow
            }
        }
        return 0;
    }

    /**
     * Read compressed unsigned varint (up to 64 bits).
     */
    private long readCompressedUint64(ByteBuffer buf) {
        long value = 0;
        int shift = 0;

        while (buf.hasRemaining()) {
            byte b = buf.get();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 70) {
                return 0; // Overflow
            }
        }
        return 0;
    }

    /**
     * Read compressed signed int32 with zigzag encoding.
     * Zigzag: (n >> 1) ^ -(n & 1)
     */
    private int readCompressedInt32(ByteBuffer buf) {
        int v = readCompressedUint32(buf);
        return (v >>> 1) ^ -(v & 1);
    }

    /**
     * Read compressed signed int64 with zigzag encoding.
     */
    private long readCompressedInt64(ByteBuffer buf) {
        long v = readCompressedUint64(buf);
        return (v >>> 1) ^ -(v & 1);
    }

    // === COMPLEX TYPE DESERIALIZERS ===

    private String readString(ByteBuffer buf) {
        int length = readCompressedUint32(buf);
        if (length <= 0 || length > buf.remaining()) {
            return "";
        }
        byte[] bytes = new byte[length];
        buf.get(bytes);
        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] deserializeCustom(ByteBuffer buf, int gpType) {
        // Regular custom: skip customId byte
        if (gpType < CUSTOM_TYPE_SLIM_BASE) {
            if (!buf.hasRemaining()) return new byte[0];
            buf.get(); // skip customId
        }

        int size = readCompressedUint32(buf);
        if (size < 0 || size > buf.remaining() || size > MAX_ARRAY_SIZE) {
            return new byte[0];
        }

        byte[] data = new byte[size];
        buf.get(data);
        return data;
    }

    private Map<Object, Object> deserializeDictionary(ByteBuffer buf) {
        byte keyTC = buf.get();
        byte valTC = buf.get();
        int count = readCompressedUint32(buf);

        if (count < 0 || count > MAX_ARRAY_SIZE) {
            return new HashMap<>();
        }

        Map<Object, Object> result = new HashMap<>();
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            int kt = keyTC;
            if (kt == 0) kt = buf.get() & 0xFF;
            int vt = valTC;
            if (vt == 0) vt = buf.get() & 0xFF;

            Object key = deserialize(buf, kt);
            Object value = deserialize(buf, vt);

            if (key != null && isComparable(key)) {
                result.put(key, value);
            }
        }
        return result;
    }

    private Object[] deserializeObjectArray(ByteBuffer buf) {
        int size = readCompressedUint32(buf);
        if (size < 0 || size > MAX_ARRAY_SIZE) {
            return new Object[0];
        }

        Object[] result = new Object[size];
        for (int i = 0; i < size && buf.hasRemaining(); i++) {
            int typeCode = buf.get() & 0xFF;
            result[i] = deserialize(buf, typeCode);
        }
        return result;
    }

    private Object deserializeTypedArray(ByteBuffer buf, int elemType) {
        int size = readCompressedUint32(buf);
        if (size < 0 || size > MAX_ARRAY_SIZE) {
            return null;
        }

        switch (elemType) {
            case TYPE_BOOLEAN: {
                // Bit-packed booleans!
                int packedBytes = (size + 7) / 8;
                byte[] packed = new byte[packedBytes];
                buf.get(packed);
                boolean[] result = new boolean[size];
                for (int i = 0; i < size; i++) {
                    result[i] = (packed[i / 8] & (1 << (i % 8))) != 0;
                }
                return result;
            }
            case TYPE_BYTE: {
                byte[] result = new byte[size];
                buf.get(result);
                return result;
            }
            case TYPE_SHORT: {
                short[] result = new short[size];
                for (int i = 0; i < size; i++) {
                    result[i] = buf.getShort();
                }
                return result;
            }
            case TYPE_FLOAT: {
                float[] result = new float[size];
                for (int i = 0; i < size; i++) {
                    result[i] = buf.getFloat();
                }
                return result;
            }
            case TYPE_DOUBLE: {
                double[] result = new double[size];
                for (int i = 0; i < size; i++) {
                    result[i] = buf.getDouble();
                }
                return result;
            }
            case TYPE_STRING: {
                String[] result = new String[size];
                for (int i = 0; i < size; i++) {
                    result[i] = readString(buf);
                }
                return result;
            }
            case TYPE_COMPRESSED_INT: {
                int[] result = new int[size];
                for (int i = 0; i < size; i++) {
                    result[i] = readCompressedInt32(buf);
                }
                return result;
            }
            case TYPE_COMPRESSED_LONG: {
                long[] result = new long[size];
                for (int i = 0; i < size; i++) {
                    result[i] = readCompressedInt64(buf);
                }
                return result;
            }
            case TYPE_DICTIONARY:
            case TYPE_HASHTABLE: {
                Object[] result = new Object[size];
                for (int i = 0; i < size; i++) {
                    result[i] = deserializeDictionary(buf);
                }
                return result;
            }
            case TYPE_CUSTOM: {
                // Custom array: shared customId
                buf.get(); // skip shared customId
                Object[] result = new Object[size];
                for (int i = 0; i < size; i++) {
                    int elemSize = readCompressedUint32(buf);
                    if (elemSize < 0 || elemSize > buf.remaining()) break;
                    byte[] data = new byte[elemSize];
                    buf.get(data);
                    result[i] = data;
                }
                return result;
            }
            default: {
                Object[] result = new Object[size];
                for (int i = 0; i < size; i++) {
                    result[i] = deserialize(buf, elemType);
                }
                return result;
            }
        }
    }

    private Object deserializeOperationRequest(ByteBuffer buf) {
        int opCode = buf.get() & 0xFF;
        Map<Integer, Object> params = readParameterTable(buf);
        Map<String, Object> result = new HashMap<>();
        result.put("operationCode", opCode);
        result.put("parameters", params);
        return result;
    }

    private Object deserializeOperationResponse(ByteBuffer buf) {
        int opCode = buf.get() & 0xFF;
        short returnCode = buf.getShort();
        String debugMessage = "";

        if (buf.hasRemaining()) {
            int tc = buf.get() & 0xFF;
            Object val = deserialize(buf, tc);
            if (val instanceof String) {
                debugMessage = (String) val;
            } else if (val instanceof String[]) {
                // Market order special case
            }
        }

        Map<Integer, Object> params = readParameterTable(buf);
        Map<String, Object> result = new HashMap<>();
        result.put("operationCode", opCode);
        result.put("returnCode", returnCode);
        result.put("debugMessage", debugMessage);
        result.put("parameters", params);
        return result;
    }

    private Object deserializeEventData(ByteBuffer buf) {
        int code = buf.get() & 0xFF;
        Map<Integer, Object> params = readParameterTable(buf);
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("parameters", params);
        return result;
    }

    // === LOW-LEVEL READERS ===

    private int readUint8() {
        if (position >= data.length) return 0;
        return data[position++] & 0xFF;
    }

    private int readUint16BE() {
        if (position + 2 > data.length) return 0;
        int result = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return result;
    }

    private long readUint32BE() {
        if (position + 4 > data.length) return 0;
        long result = ((long) (data[position] & 0xFF) << 24) |
                     ((data[position + 1] & 0xFF) << 16) |
                     ((data[position + 2] & 0xFF) << 8) |
                     (data[position + 3] & 0xFF);
        position += 4;
        return result;
    }

    // === HELPERS ===

    private float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return 0f;
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private boolean isComparable(Object value) {
        return value == null ||
               value instanceof Boolean ||
               value instanceof Number ||
               value instanceof String;
    }

    private void parseTierFromTypeId(EntityInfo entity) {
        String typeId = entity.getTypeId();
        if (typeId == null) return;

        // Format: T{tier}_{type} or T{tier}_{type}_@
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
                // Ignore
            }
        }
    }

    // Fragment reassembly helper class
    private static class SegmentedPackage {
        int totalLength;
        int bytesWritten;
        byte[] payload;

        SegmentedPackage(int totalLength) {
            this.totalLength = totalLength;
            this.payload = new byte[totalLength];
            this.bytesWritten = 0;
        }
    }
}
