package de.creativecouple.validation.byte_mapper;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.With;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static de.creativecouple.validation.byte_mapper.BytePatterns.getBytePattern;
import static java.lang.Integer.toUnsignedLong;

public class ByteMapper {

    public <T> T readBytes(byte[] bytes, Class<T> type) {
        return readBytes(bytes, 0, bytes.length, type);
    }

    public <T> T readBytes(byte[] bytes, int offset, int length, Class<T> type) {
        return readBytes(new ByteArrayInputStream(bytes, offset, length), length, type);
    }

    public <T> T readBytes(InputStream input, Class<T> type) {
        return readBytes(input, -1, type);
    }

    @SneakyThrows
    public <T> T readBytes(InputStream input, int size, Class<T> type) {
        type = findVariantType(type, input);
        @SuppressWarnings("unchecked")
        Constructor<T> constructor = (Constructor<T>) type.getConstructors()[0];
        Parameter[] parameters = constructor.getParameters();
        Object[] params = new InputStreamParser(input, size).parse(getBytePattern(type), parameters);
        return constructor.newInstance(params);
    }

    public byte[] writeBytes(Object value) {
        ResettableByteArrayOutputStream stream = new ResettableByteArrayOutputStream();
        writeBytes(stream, value);
        return stream.toByteArray();
    }

    public void writeBytes(OutputStream stream, Object value) throws IOException {
        stream.write(writeBytes(value));
    }

    private void writeBytes(ResettableByteArrayOutputStream stream, Object value) {
        writeBytes(stream, value, -1);
    }

    @SneakyThrows
    private int writeBytes(ResettableByteArrayOutputStream output, Object value, int size) {
        if (value == null) {
            return 0;
        }
        return new OutputStreamProducer(output, size).produce(value);
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    private <T> Class<T> findVariantType(Class<T> baseType, InputStream input) {
        ByteVariants variants = baseType.getAnnotation(ByteVariants.class);
        if (variants == null) {
            return baseType;
        }
        for (Class<?> type : variants.value()) {
            if (firstBytesMatch(input, getBytePattern(type))) {
                return (Class<T>) type;
            }
        }
        throw newNoMatchFound(baseType, input);
    }

    @SneakyThrows
    private static <T> Exception newNoMatchFound(Class<T> baseType, InputStream input) {
        input.mark(8);
        byte[] actual = new byte[8];
        int len = input.read(actual);
        return new IllegalArgumentException("no variant pattern matched for type " + baseType + " given the " + len
                + " bytes: " + ByteArrays.toString(actual));
    }

    private static final int nrTestBytes = 8;

    @SneakyThrows
    private static boolean firstBytesMatch(InputStream input, BytePatternEvent[] pattern) {
        input.mark(nrTestBytes);
        try {
            int bytesMatched = 0;
            for (BytePatternEvent event : pattern) {
                switch (event.event()) {
                case CONSTANT_BYTES -> {
                    for (long l = (long) event.value(),
                            i = event.size().constantValue(); i > 0; i--, l = l >> 8, bytesMatched++) {
                        int b = input.read();
                        if (b < 0) {
                            return true;
                        }
                        if (((int) l & 0xFF) != b) {
                            return false;
                        }
                    }
                }
                case PLACEHOLDER -> {
                    if (event.size().isVariable()) {
                        return true;
                    }
                    long skipped = input.skip(event.size().constantValue());
                    if (skipped < event.size().constantValue()) {
                        return true;
                    }
                    bytesMatched += event.size().constantValue();
                }
                }
                if (bytesMatched >= nrTestBytes) {
                    break;
                }
            }
            return true;
        } finally {
            input.reset();
        }
    }

    private class InputStreamParser {

        private final InputStream input;
        private int maxLength;
        private int maxLengthOutsideGroup = -1;
        private int bytesParsedOutsideGroup = -1;
        private int bytesParsed = 0;
        private BytePatternEvent currentGroupStartEvent = null;
        private final Map<String, Integer> variables = new HashMap<>();

        InputStreamParser(InputStream input, int maxLength) {
            this.input = input;
            this.maxLength = maxLength;
        }

        public Object[] parse(BytePatternEvent[] pattern, Parameter[] parameters) {
            Object[] result = new Object[parameters.length];

            for (BytePatternEvent event : pattern) {
                switch (event.event()) {
                case CONSTANT_BYTES -> handleConstantByte(event);
                case PLACEHOLDER -> handlePlaceholder(event, parameters, result);
                case GROUP_BEGIN -> handleGroupStart(event);
                case GROUP_END -> handleGroupEnd(event);
                }
            }

            return result;
        }

        @SneakyThrows
        private void handleConstantByte(BytePatternEvent event) {
            LinearSizeValue size = event.size();
            for (long l = (long) event.value(), i = size.constantValue(); i > 0; i--, l = l >> 8) {
                int b = readByte();
                if (((int) l & 0xFF) != b) {
                    throw new NumberFormatException("byte 0x" + Integer.toHexString(b) + " at stream index "
                            + bytesParsed + " does not match pattern '" + event.source().pattern() + "'");
                }
            }
            incBytesParsed(size.constantValue());
        }

        private int readByte() throws IOException {
            int b = input.read();
            if (b < 0) {
                throw new EOFException("byte stream ends unexpectedly");
            }
            return b;
        }

        @SneakyThrows
        private void incBytesParsed(int count) {
            if (maxLength >= 0 && count >= 0) {
                bytesParsed += count;
                if (bytesParsed > maxLength) {
                    throw new EOFException("reading more than " + maxLength + " bytes");
                }
            }
        }

        private void handlePlaceholder(BytePatternEvent event, Parameter[] parameters, Object[] results) {
            LinearSizeValue size = event.size();
            final int placeholderLength;
            if (size.isVariable()) {
                if (size.sizeVariable().equals("?") && size.linearFactor() == 1) { // all the rest bytes
                    if (maxLength >= 0) {
                        placeholderLength = maxLength - bytesParsed + size.constantValue();
                    } else {
                        placeholderLength = -1;
                    }
                } else if (!variables.containsKey(size.sizeVariable())) {
                    throw new IllegalArgumentException(
                            "size name '" + size.sizeVariable() + "' was not defined before");
                } else {
                    placeholderLength = size.constantValue() + size.linearFactor() * variables.get(size.sizeVariable());
                }
            } else {
                placeholderLength = size.constantValue();
            }
            addParameterValue((String) event.value(), event.littleEndian(), placeholderLength, parameters, results);
            incBytesParsed(placeholderLength);
        }

        private void handleGroupStart(BytePatternEvent event) {
            currentGroupStartEvent = event;
            maxLengthOutsideGroup = maxLength;
            bytesParsedOutsideGroup = bytesParsed;

            LinearSizeValue size = event.size();
            if (size.isVariable()) {
                if (variables.containsKey(size.sizeVariable())) {
                    maxLength = size.constantValue() + size.linearFactor() * variables.get(size.sizeVariable());
                } else {
                    maxLength -= bytesParsed;
                }
            } else {
                maxLength = size.constantValue();
            }
            bytesParsed = 0;
        }

        private void handleGroupEnd(BytePatternEvent event) {
            if (maxLength >= 0 && bytesParsed != maxLength) {
                throw new IllegalArgumentException("parsed " + bytesParsed + " bytes instead of expected " + maxLength);
            }
            currentGroupStartEvent = null;
            maxLength = maxLengthOutsideGroup;
            bytesParsed = bytesParsedOutsideGroup;
        }

        @SneakyThrows
        private void addParameterValue(String variableName, Boolean littleEndian, int placeholderLength,
                Parameter[] parameters, Object[] result) {
            if (variableName == null || variableName.isEmpty()) {
                long skipped = input.skip(placeholderLength);
                if (skipped < placeholderLength) {
                    throw new EOFException("byte stream ends unexpectedly");
                }
            } else if (variableName.startsWith("$")) {
                Object value = parseValue(placeholderLength, littleEndian, Integer.TYPE);
                if (value == null) {
                    throw new IllegalArgumentException(
                            "size variable '" + variableName + "' must have non-zero length itself.");
                }
                variables.put(variableName, (Integer) value);
                if (currentGroupStartEvent != null) {
                    LinearSizeValue groupSize = currentGroupStartEvent.size();
                    String groupSizeVarName = groupSize.sizeVariable();
                    if (variableName.equals(groupSizeVarName)) {
                        maxLength = groupSize.constantValue() + groupSize.linearFactor() * (Integer) value;
                    }
                }
            } else {
                for (int i = 0; i < parameters.length; i++) {
                    if (variableName.equals(parameters[i].getName())) {
                        result[i] = parseValue(placeholderLength, littleEndian, parameters[i].getParameterizedType());
                        return;
                    }
                }
                throw new NumberFormatException("no parameter with name '" + variableName + "' visible in constructor");
            }
        }

        private Object parseValue(int size, Boolean littleEndian, Type genericType) throws Exception {
            if (genericType instanceof ParameterizedType parameterizedType) {
                if (parameterizedType.getRawType() instanceof Class<?> clazz
                        && clazz.isAssignableFrom(ArrayList.class)) {
                    Type[] genericArguments = parameterizedType.getActualTypeArguments();
                    if (genericArguments.length == 1) {
                        if (size == 0) {
                            return List.of();
                        }
                        List<Object> list = new ArrayList<>();
                        Type elementType = genericArguments[0];
                        int stopAt = size >= 0 ? input.available() - size : 0;
                        while (input.available() > stopAt) {
                            list.add(parseValue(-1, littleEndian, elementType));
                        }
                        if (input.available() < stopAt) {
                            throw new IllegalArgumentException("expected to read only " + size + " bytes, but read "
                                    + (stopAt - input.available()) + " bytes too much.");
                        }
                        return list;
                    }
                }
            }
            if (size == 0) {
                return null;
            }
            if (genericType instanceof Class<?> type) {
                type = findVariantType(type, input);
                if (type.isAssignableFrom(byte[].class)) {
                    return toByteArray(size);
                }
                if (Boolean.TYPE.equals(type) || Boolean.class.equals(type)) {
                    return toBoolean(size);
                }
                if (Byte.TYPE.equals(type) || Byte.class.equals(type)) {
                    return toByte(size, littleEndian);
                }
                if (Short.TYPE.equals(type) || Short.class.equals(type)) {
                    return toShort(size, littleEndian);
                }
                if (Character.TYPE.equals(type) || Character.class.equals(type)) {
                    return (char) toShort(size, littleEndian);
                }
                if (Integer.TYPE.equals(type) || Integer.class.equals(type)) {
                    return toInteger(size, littleEndian);
                }
                if (Long.TYPE.equals(type) || Long.class.equals(type)) {
                    return toLong(size, littleEndian);
                }
                if (type.getAnnotation(ByteFormat.class) != null) {
                    return readBytes(input, size, type);
                }
                for (Constructor<?> constructor : type.getConstructors()) {
                    Parameter[] parameters = constructor.getParameters();
                    if (parameters.length == 1) {
                        Object param = parseValue(size, littleEndian, parameters[0].getParameterizedType());
                        return constructor.newInstance(param);
                    }
                    if (parameters.length == 0) {
                        return constructor.newInstance();
                    }
                }
            }
            throw new IllegalArgumentException("type " + genericType
                    + " is not primitive, has no simple constructor, and has no @ByteFormat annotation");
        }

        private long toLong(int length, Boolean littleEndian) throws IOException {
            long a, b, c, d, e, f, g;
            switch (length) {
            case 1 -> {
                return toUnsignedLong(readByte());
            }
            case 2 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 : a << 8 | b;
            }
            case 3 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                c = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 : a << 16 | b << 8 | c;
            }
            case 4 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                c = toUnsignedLong(readByte());
                d = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 | d << 24
                        : a << 24 | b << 16 | c << 8 | d;
            }
            case 5 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                c = toUnsignedLong(readByte());
                d = toUnsignedLong(readByte());
                e = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 | d << 24 | e << 32
                        : a << 32 | b << 24 | c << 16 | d << 8 | e;
            }
            case 6 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                c = toUnsignedLong(readByte());
                d = toUnsignedLong(readByte());
                e = toUnsignedLong(readByte());
                f = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 | d << 24 | e << 32 | f << 40
                        : a << 40 | b << 32 | c << 24 | d << 16 | e << 8 | f;
            }
            case 7 -> {
                a = toUnsignedLong(readByte());
                b = toUnsignedLong(readByte());
                c = toUnsignedLong(readByte());
                d = toUnsignedLong(readByte());
                e = toUnsignedLong(readByte());
                f = toUnsignedLong(readByte());
                g = toUnsignedLong(readByte());
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 | d << 24 | e << 32 | f << 40 | g << 48
                        : a << 48 | b << 40 | c << 32 | d << 24 | e << 16 | f << 8 | g;
            }
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                long l = toUnsignedLong(readByte()) | toUnsignedLong(readByte()) << 8 | toUnsignedLong(readByte()) << 16
                        | toUnsignedLong(readByte()) << 24 | toUnsignedLong(readByte()) << 32
                        | toUnsignedLong(readByte()) << 40 | toUnsignedLong(readByte()) << 48
                        | toUnsignedLong(readByte()) << 56;
                input.skipNBytes(length - 8);
                return l;
            }
            input.skipNBytes(length - 8);
            return toUnsignedLong(readByte()) << 56 | toUnsignedLong(readByte()) << 48
                    | toUnsignedLong(readByte()) << 40 | toUnsignedLong(readByte()) << 32
                    | toUnsignedLong(readByte()) << 24 | toUnsignedLong(readByte()) << 16
                    | toUnsignedLong(readByte()) << 8 | toUnsignedLong(readByte());
        }

        private int toInteger(int length, Boolean littleEndian) throws IOException {
            int a, b, c;
            switch (length) {
            case 1 -> {
                return readByte();
            }
            case 2 -> {
                a = readByte();
                b = readByte();
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 : a << 8 | b;
            }
            case 3 -> {
                a = readByte();
                b = readByte();
                c = readByte();
                return Boolean.TRUE.equals(littleEndian) ? a | b << 8 | c << 16 : a << 16 | b << 8 | c;
            }
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                int i = readByte() | readByte() << 8 | readByte() << 16 | readByte() << 24;
                input.skipNBytes(length - 4);
                return i;
            }
            input.skipNBytes(length - 4);
            return readByte() << 24 | readByte() << 16 | readByte() << 8 | readByte();
        }

        private short toShort(int length, Boolean littleEndian) throws IOException {
            if (length == 1) {
                return (short) readByte();
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                int s = readByte() | (readByte() << 8);
                input.skipNBytes(length - 2);
                return (short) s;
            }
            input.skipNBytes(length - 2);
            return (short) (readByte() << 8 | readByte());
        }

        private byte toByte(int length, Boolean littleEndian) throws IOException {
            if (length == 1) {
                return (byte) readByte();
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                int b = readByte();
                input.skipNBytes(length - 1);
                return (byte) b;
            }
            input.skipNBytes(length - 1);
            return (byte) readByte();
        }

        private boolean toBoolean(int length) throws IOException {
            for (int i = 0; i < length; i++) {
                if (readByte() != 0) {
                    return true;
                }
            }
            return false;
        }

        private byte[] toByteArray(int length) throws IOException {
            return input.readNBytes(length);
        }

    }

    @RequiredArgsConstructor
    private class OutputStreamProducer {

        @With
        private record VariablePosition(int pos, int nrBytes, Boolean littleEndian, Integer content) {
        }

        private final ResettableByteArrayOutputStream output;
        private final int expectedSize;
        private int bytesWritten = 0;
        private final Map<String, VariablePosition> sizeVariables = new HashMap<>();

        public int produce(Object object) {
            int groupStart = 0;
            if (object != null) {
                for (BytePatternEvent event : getBytePattern(object.getClass())) {
                    switch (event.event()) {
                    case CONSTANT_BYTES -> bytesWritten += handleConstantByte(event);
                    case PLACEHOLDER -> bytesWritten += handlePlaceholder(event, object);
                    case GROUP_BEGIN -> groupStart = bytesWritten;
                    case GROUP_END -> handleGroupEnd(event, bytesWritten - groupStart);
                    }
                }
            }
            if (expectedSize >= 0 && bytesWritten != expectedSize) {
                throw new IllegalArgumentException(
                        "expected to produce " + expectedSize + " bytes, but instead wrote " + bytesWritten + ".");
            }
            return bytesWritten;
        }

        private int handleConstantByte(BytePatternEvent event) {
            LinearSizeValue size = event.size();
            for (long l = (long) event.value(), i = size.constantValue(); i > 0; i--, l = l >> 8) {
                output.write((int) l);
            }
            return size.constantValue();
        }

        @SneakyThrows
        private int handlePlaceholder(BytePatternEvent event, Object object) {
            LinearSizeValue size = event.size();
            final int placeholderLength = getPlaceholderLength(size);
            String parameterName = (String) event.value();

            if (parameterName == null || parameterName.isEmpty()) {
                return writeValue(placeholderLength, event.littleEndian(), 0);
            }

            if (parameterName.startsWith("$")) {
                int pos = output.count();
                int writtenBytes = writeValue(placeholderLength, event.littleEndian(), 0);
                sizeVariables.put(parameterName, new VariablePosition(pos, writtenBytes, event.littleEndian(), null));
                return writtenBytes;
            }

            Method method = object.getClass().getMethod(parameterName);
            int writtenBytes = writeValue(placeholderLength, event.littleEndian(), method.invoke(object));
            if (placeholderLength >= 0 && writtenBytes != placeholderLength) {
                throw new IllegalArgumentException("expected to produce " + placeholderLength + " bytes for parameter '"
                        + parameterName + "', but instead wrote " + writtenBytes + ".");
            }
            if (size.isVariable()) {
                storeVariable(size.sizeVariable(), (writtenBytes - size.constantValue()) / size.linearFactor());
            }
            return writtenBytes;
        }

        @SneakyThrows
        private void handleGroupEnd(BytePatternEvent event, int writtenBytes) {
            LinearSizeValue size = event.size();
            final int placeholderLength = getPlaceholderLength(size);
            if (placeholderLength >= 0 && writtenBytes != placeholderLength) {
                throw new IllegalArgumentException("expected to produce " + placeholderLength
                        + " bytes for group, but instead wrote " + writtenBytes + ".");
            }
            if (size.isVariable()) {
                storeVariable(size.sizeVariable(), (writtenBytes - size.constantValue()) / size.linearFactor());
            }
        }

        private void storeVariable(String variable, int content) throws IOException {
            VariablePosition sizePos = sizeVariables.get(variable);
            if (sizePos != null) {
                output.reset(sizePos.pos());
                writeValue(sizePos.nrBytes(), sizePos.littleEndian(), content);
                sizeVariables.put(variable, sizePos.withContent(content));
                output.unreset();
            }
        }

        private int getPlaceholderLength(LinearSizeValue size) {
            if (!size.isVariable()) {
                return size.constantValue();
            }
            if ("?".equals(size.sizeVariable())) {
                return expectedSize < 0 ? -1 : expectedSize - bytesWritten + size.constantValue();
            }
            if (!sizeVariables.containsKey(size.sizeVariable())) {
                throw new IllegalArgumentException("size expression '" + size.sizeVariable()
                        + "' does not match previously defined size variables " + sizeVariables.keySet() + ".");
            }
            Integer varContent = sizeVariables.get(size.sizeVariable()).content();
            return varContent != null ? varContent : -1;
        }

        private int writeValue(int length, Boolean littleEndian, Object value) throws IOException {
            if (value == null) {
                return 0;
            }
            if (value instanceof byte[] bytes) {
                return writeByteArray(bytes, length);
            }
            if (value instanceof Boolean bool) {
                return writeBoolean(bool, length);
            }
            if (value instanceof Byte byteValue) {
                return writeByte(byteValue, length, littleEndian);
            }
            if (value instanceof Short shortValue) {
                return writeShort(shortValue, length, littleEndian);
            }
            if (value instanceof Character charValue) {
                return writeShort((short) (char) charValue, length, littleEndian);
            }
            if (value instanceof Integer intValue) {
                return writeInteger(intValue, length, littleEndian);
            }
            if (value instanceof Long longValue) {
                return writeLong(longValue, length, littleEndian);
            }
            if (value instanceof Collection<?> collection) {
                int sum = 0;
                for (Object o : collection) {
                    sum += writeValue(-1, littleEndian, o);
                }
                return sum;
            }
            return writeBytes(output, value, length);
        }

        private int writeLong(long value, int length, Boolean littleEndian) {
            if (length < 0) {
                length = 8;
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                int end = Math.min(length, 8);
                for (int i = 0; i < end; i++) {
                    output.write((int) value);
                    value = value >> 8;
                }
                for (int i = end; i < length; i++) {
                    output.write(0);
                }
            } else {
                int start = Math.max(0, length - 8);
                for (int i = 0; i < start; i++) {
                    output.write(0);
                }
                for (int i = length - start - 1; i >= 0; i--) {
                    output.write((int) (value >> (i << 3)));
                }
            }
            return length;
        }

        private int writeInteger(int value, int length, Boolean littleEndian) {
            if (length < 0) {
                length = 4;
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                int end = Math.min(length, 4);
                for (int i = 0; i < end; i++) {
                    output.write(value);
                    value = value >> 8;
                }
                for (int i = end; i < length; i++) {
                    output.write(0);
                }
            } else {
                int start = Math.max(0, length - 4);
                for (int i = 0; i < start; i++) {
                    output.write(0);
                }
                for (int i = length - start - 1; i >= 0; i--) {
                    output.write(value >> (i << 3));
                }
            }
            return length;
        }

        private int writeShort(short value, int length, Boolean littleEndian) {
            if (length < 0) {
                length = 2;
            }
            if (length == 1) {
                output.write(value);
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                output.write(value);
                output.write(value >> 8);
                for (int i = 2; i < length; i++) {
                    output.write(0);
                }
            } else {
                for (int i = 2; i < length; i++) {
                    output.write(0);
                }
                output.write(value >> 8);
                output.write(value);
            }
            return length;
        }

        private int writeByte(byte value, int length, Boolean littleEndian) {
            if (length < 0) {
                length = 1;
            }
            if (Boolean.TRUE.equals(littleEndian)) {
                output.write(value);
                for (int i = 1; i < length; i++) {
                    output.write(0);
                }
            } else {
                for (int i = 1; i < length; i++) {
                    output.write(0);
                }
                output.write(value);
            }
            return length;
        }

        private int writeBoolean(boolean value, int length) {
            if (length < 0) {
                length = 1;
            }
            for (int i = 0; i < length; i++) {
                output.write(value ? 1 : 0);
            }
            return length;
        }

        private int writeByteArray(byte[] value, int length) throws IOException {
            if (length < 0) {
                length = value.length;
            } else if (value.length != length) {
                throw new IllegalArgumentException(
                        "byte array length " + value.length + " does not match format length " + length);
            }
            output.write(value);
            return length;
        }

    }

}
