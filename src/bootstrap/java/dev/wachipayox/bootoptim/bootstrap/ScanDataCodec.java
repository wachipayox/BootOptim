package dev.wachipayox.bootoptim.bootstrap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.objectweb.asm.Type;

/** Compact, versioned codec for the immutable part of FML's class scan output. */
final class ScanDataCodec {
    private static final int MAGIC = 0x424F5343; // BOSC
    private static final int MAX_ENTRIES = 2_000_000;
    private static final int MAX_NESTED_DEPTH = 32;

    private ScanDataCodec() {
    }

    static void write(DataOutput output, int version, long sourceSize, long sourceModified, ModFileScanData data) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(version);
        output.writeLong(sourceSize);
        output.writeLong(sourceModified);

        output.writeInt(data.getClasses().size());
        for (ModFileScanData.ClassData classData : data.getClasses()) {
            writeType(output, classData.clazz());
            writeType(output, classData.parent());
            output.writeInt(classData.interfaces().size());
            for (Type interfaceType : classData.interfaces()) {
                writeType(output, interfaceType);
            }
        }

        output.writeInt(data.getAnnotations().size());
        for (ModFileScanData.AnnotationData annotation : data.getAnnotations()) {
            writeType(output, annotation.annotationType());
            output.writeByte(annotation.targetType().ordinal());
            writeType(output, annotation.clazz());
            writeNullableUtf(output, annotation.memberName());
            writeStringObjectMap(output, annotation.annotationData(), 0);
        }
    }

    static ModFileScanData read(DataInput input, int expectedVersion, long sourceSize, long sourceModified) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new IOException("Unexpected scan cache magic");
        }
        if (input.readInt() != expectedVersion) {
            throw new IOException("Unsupported scan cache version");
        }
        if (input.readLong() != sourceSize || input.readLong() != sourceModified) {
            throw new IOException("Scan cache source metadata changed");
        }

        int classCount = readCount(input, "class");
        Set<ModFileScanData.ClassData> classes = new LinkedHashSet<>(initialCapacity(classCount));
        for (int i = 0; i < classCount; i++) {
            Type clazz = readType(input);
            Type parent = readType(input);
            int interfaceCount = readCount(input, "interface");
            Set<Type> interfaces = new LinkedHashSet<>(initialCapacity(interfaceCount));
            for (int j = 0; j < interfaceCount; j++) {
                interfaces.add(readType(input));
            }
            classes.add(new ModFileScanData.ClassData(clazz, parent, interfaces));
        }

        int annotationCount = readCount(input, "annotation");
        Set<ModFileScanData.AnnotationData> annotations = new LinkedHashSet<>(initialCapacity(annotationCount));
        ElementType[] elementTypes = ElementType.values();
        for (int i = 0; i < annotationCount; i++) {
            Type annotationType = readType(input);
            int targetOrdinal = input.readUnsignedByte();
            if (targetOrdinal >= elementTypes.length) {
                throw new IOException("Invalid annotation target ordinal " + targetOrdinal);
            }
            Type clazz = readType(input);
            String memberName = readNullableUtf(input);
            Map<String, Object> values = readStringObjectMap(input, 0);
            annotations.add(new ModFileScanData.AnnotationData(annotationType, elementTypes[targetOrdinal], clazz, memberName, values));
        }

        ModFileScanData result = new ModFileScanData();
        result.getClasses().addAll(classes);
        result.getAnnotations().addAll(annotations);
        return result;
    }

    private static void writeType(DataOutput output, Type type) throws IOException {
        output.writeUTF(type.getDescriptor());
    }

    private static Type readType(DataInput input) throws IOException {
        try {
            return Type.getType(input.readUTF());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid ASM type in scan cache", exception);
        }
    }

    private static void writeNullableUtf(DataOutput output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    private static String readNullableUtf(DataInput input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }

    private static void writeStringObjectMap(DataOutput output, Map<String, Object> values, int depth) throws IOException {
        checkDepth(depth);
        output.writeInt(values.size());
        for (var entry : values.entrySet()) {
            output.writeUTF(entry.getKey());
            writeNested(output, entry.getValue(), depth + 1);
        }
    }

    private static Map<String, Object> readStringObjectMap(DataInput input, int depth) throws IOException {
        checkDepth(depth);
        int size = readCount(input, "map");
        Map<String, Object> values = new HashMap<>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            values.put(input.readUTF(), readNested(input, depth + 1));
        }
        return values;
    }

    private static void writeNested(DataOutput output, Object value, int depth) throws IOException {
        checkDepth(depth);
        if (value == null) {
            output.writeByte(0);
        } else if (value instanceof String v) {
            output.writeByte(1); output.writeUTF(v);
        } else if (value instanceof Byte v) {
            output.writeByte(2); output.writeByte(v);
        } else if (value instanceof Boolean v) {
            output.writeByte(3); output.writeBoolean(v);
        } else if (value instanceof Short v) {
            output.writeByte(4); output.writeShort(v);
        } else if (value instanceof Character v) {
            output.writeByte(5); output.writeChar(v);
        } else if (value instanceof Integer v) {
            output.writeByte(6); output.writeInt(v);
        } else if (value instanceof Long v) {
            output.writeByte(7); output.writeLong(v);
        } else if (value instanceof Float v) {
            output.writeByte(8); output.writeFloat(v);
        } else if (value instanceof Double v) {
            output.writeByte(9); output.writeDouble(v);
        } else if (value instanceof Type v) {
            output.writeByte(10); writeType(output, v);
        } else if (value instanceof ModAnnotation.EnumHolder v) {
            output.writeByte(11); output.writeUTF(v.desc()); output.writeUTF(v.value());
        } else if (value instanceof List<?> list) {
            output.writeByte(12);
            output.writeInt(list.size());
            for (Object item : list) writeNested(output, item, depth + 1);
        } else if (value instanceof Map<?, ?> map) {
            output.writeByte(13);
            output.writeInt(map.size());
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IOException("Non-string annotation map key");
                output.writeUTF(key);
                writeNested(output, entry.getValue(), depth + 1);
            }
        } else if (value instanceof byte[] array) {
            output.writeByte(14); output.writeInt(array.length); output.write(array);
        } else if (value instanceof boolean[] array) {
            output.writeByte(15); output.writeInt(array.length); for (boolean item : array) output.writeBoolean(item);
        } else if (value instanceof short[] array) {
            output.writeByte(16); output.writeInt(array.length); for (short item : array) output.writeShort(item);
        } else if (value instanceof char[] array) {
            output.writeByte(17); output.writeInt(array.length); for (char item : array) output.writeChar(item);
        } else if (value instanceof int[] array) {
            output.writeByte(18); output.writeInt(array.length); for (int item : array) output.writeInt(item);
        } else if (value instanceof long[] array) {
            output.writeByte(19); output.writeInt(array.length); for (long item : array) output.writeLong(item);
        } else if (value instanceof float[] array) {
            output.writeByte(20); output.writeInt(array.length); for (float item : array) output.writeFloat(item);
        } else if (value instanceof double[] array) {
            output.writeByte(21); output.writeInt(array.length); for (double item : array) output.writeDouble(item);
        } else {
            throw new IOException("Unsupported annotation value type: " + value.getClass().getName());
        }
    }

    private static Object readNested(DataInput input, int depth) throws IOException {
        checkDepth(depth);
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> input.readUTF();
            case 2 -> input.readByte();
            case 3 -> input.readBoolean();
            case 4 -> input.readShort();
            case 5 -> input.readChar();
            case 6 -> input.readInt();
            case 7 -> input.readLong();
            case 8 -> input.readFloat();
            case 9 -> input.readDouble();
            case 10 -> readType(input);
            case 11 -> new ModAnnotation.EnumHolder(input.readUTF(), input.readUTF());
            case 12 -> {
                int size = readCount(input, "list");
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(readNested(input, depth + 1));
                yield list;
            }
            case 13 -> {
                int size = readCount(input, "nested map");
                Map<String, Object> map = new HashMap<>(initialCapacity(size));
                for (int i = 0; i < size; i++) map.put(input.readUTF(), readNested(input, depth + 1));
                yield map;
            }
            case 14 -> {
                int size = readCount(input, "byte array");
                byte[] array = new byte[size];
                input.readFully(array);
                yield array;
            }
            case 15 -> {
                int size = readCount(input, "boolean array"); boolean[] array = new boolean[size];
                for (int i = 0; i < size; i++) array[i] = input.readBoolean(); yield array;
            }
            case 16 -> {
                int size = readCount(input, "short array"); short[] array = new short[size];
                for (int i = 0; i < size; i++) array[i] = input.readShort(); yield array;
            }
            case 17 -> {
                int size = readCount(input, "char array"); char[] array = new char[size];
                for (int i = 0; i < size; i++) array[i] = input.readChar(); yield array;
            }
            case 18 -> {
                int size = readCount(input, "int array"); int[] array = new int[size];
                for (int i = 0; i < size; i++) array[i] = input.readInt(); yield array;
            }
            case 19 -> {
                int size = readCount(input, "long array"); long[] array = new long[size];
                for (int i = 0; i < size; i++) array[i] = input.readLong(); yield array;
            }
            case 20 -> {
                int size = readCount(input, "float array"); float[] array = new float[size];
                for (int i = 0; i < size; i++) array[i] = input.readFloat(); yield array;
            }
            case 21 -> {
                int size = readCount(input, "double array"); double[] array = new double[size];
                for (int i = 0; i < size; i++) array[i] = input.readDouble(); yield array;
            }
            default -> throw new IOException("Unknown annotation value tag");
        };
    }

    private static int readCount(DataInput input, String label) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid " + label + " count " + count);
        }
        return count;
    }

    private static int initialCapacity(int size) {
        return size < 3 ? size + 1 : Math.min(Integer.MAX_VALUE - 8, (int) (size / 0.75f) + 1);
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_NESTED_DEPTH) {
            throw new IOException("Annotation nesting exceeds cache limit");
        }
    }
}
