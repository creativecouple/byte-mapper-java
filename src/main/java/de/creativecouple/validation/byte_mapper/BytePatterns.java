package de.creativecouple.validation.byte_mapper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static de.creativecouple.validation.byte_mapper.BytePatternEventType.CONSTANT_BYTES;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.GROUP_BEGIN;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.GROUP_END;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.PLACEHOLDER;
import static de.creativecouple.validation.byte_mapper.LinearSizeValue.ONE;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class BytePatterns {

    private static final Map<Class<?>, BytePatternEvent[]> bytePatternCache = new ConcurrentHashMap<>();

    static BytePatternEvent[] getBytePattern(Class<?> clazz) {
        return bytePatternCache.computeIfAbsent(clazz, type -> parse(getByteFormatString(type)));
    }

    private static String getByteFormatString(Class<?> type) {
        ByteFormat byteFormat = type.getAnnotation(ByteFormat.class);
        if (byteFormat == null) {
            throw new IllegalArgumentException("missing @ByteFormat annotation on type " + type);
        }
        return byteFormat.value();
    }

    private static final Pattern hexPattern = Pattern.compile("(?<newLine>\\n)" + "|(?<constantByte>[0-9A-F]{2})"
            + "|(?<placeholder>hi|lo|[*][*])(?:[{](?:(?<placeholderConstantLength>\\d+)|(?<placeholderFlexibleLength>[^\\s,:+*/{}\\[\\]()-]+)(?:[*](?<placeholderMultiplier>\\d+))?)[}])?"
            + "|:(?<parameter>[^\\s,:+*/{}\\[\\]()-]*)" + "|//[^\\n]*" + // comment
            "|\\s" + // whitespace
            "|(?<groupBegin>\\[)"
            + "|(?<groupEnd>])[{](?:(?<groupEndConstantLength>\\d+)|(?<groupEndFlexibleLength>[^\\s,:+*/{}\\[\\]()-]+)(?:[*](?<groupEndMultiplier>\\d+))?)[}]"
            + "|(?<illegalCharacter>.)", CASE_INSENSITIVE);

    static BytePatternEvent[] parse(String hexString) {
        return (new BytePatternParser(hexString)).parse();
    }

    private static class BytePatternParser {
        private final String hexString;
        private final Matcher matcher;
        private final LinkedList<BytePatternEvent> result = new LinkedList<>();
        private final Set<String> knownParameterNames = new HashSet<>();
        private Integer unknownSizeEventIndex = null;
        private int lineNo = 1;
        private int lineStartIndex = 0;
        private int linePos = 0;
        private Integer groupStartEventIndex = null;

        public BytePatternParser(String hexString) {
            this.hexString = hexString;
            this.matcher = hexPattern.matcher(hexString);
        }

        private BytePatternEvent last(BytePatternEventType type) {
            if (!result.isEmpty()) {
                BytePatternEvent last = result.getLast();
                if (last.event() == type) {
                    return last;
                }
            }
            return null;
        }

        public BytePatternEvent[] parse() {
            while (matcher.find()) {
                linePos = matcher.start() - lineStartIndex;
                if (matcher.group("newLine") != null) {
                    handleNewLine();
                } else if (matcher.group("constantByte") != null) {
                    handleConstantByte();
                } else if (matcher.group("placeholder") != null) {
                    handlePlaceholder();
                } else if (matcher.group("parameter") != null) {
                    handleParameter();
                } else if (matcher.group("groupBegin") != null) {
                    handleGroupBegin();
                } else if (matcher.group("groupEnd") != null) {
                    handleGroupEnd();
                } else if (matcher.group("illegalCharacter") != null) {
                    handleUnknownCharacter();
                }
            }
            return result.toArray(new BytePatternEvent[0]);
        }

        private void handleNewLine() {
            lineNo++;
            lineStartIndex = matcher.end();
        }

        private void handleConstantByte() {
            int byteValue = Integer.parseInt(matcher.group("constantByte"), 16);
            BytePatternEvent lastEvent = last(CONSTANT_BYTES);
            result.add(lastEvent != null && lastEvent.size().constantValue() < 8
                    ? addConstantByte(result.removeLast(), byteValue) : newConstantByte(byteValue));
            reduceUnknownSizedPlaceholder(1);
        }

        private void reduceUnknownSizedPlaceholder(int value) {
            if (unknownSizeEventIndex != null) {
                var variableSizeEvent = result.get(unknownSizeEventIndex);
                result.set(unknownSizeEventIndex, variableSizeEvent.withSize(variableSizeEvent.size().add(-value)));
            }
        }

        private void handlePlaceholder() {
            String placeholderExpr = matcher.group("placeholder");
            Boolean littleEndian = placeholderExpr.equals("**") ? null : placeholderExpr.equalsIgnoreCase("lo");
            String constantLength = matcher.group("placeholderConstantLength");
            String variableName = matcher.group("placeholderFlexibleLength");
            String multiplier = matcher.group("placeholderMultiplier");

            BytePatternEvent placeholderEvent = newPlaceholder(littleEndian, constantLength, variableName, multiplier);
            checkUnknownSizeEventIndex(placeholderEvent);
            BytePatternEvent lastEvent = last(PLACEHOLDER);
            result.add(lastEvent != null && lastEvent.value() == null
                    ? addPlaceholder(result.removeLast(), placeholderEvent) : placeholderEvent);
        }

        private void checkUnknownSizeEventIndex(BytePatternEvent placeholderEvent) {
            LinearSizeValue size = placeholderEvent.size();
            if (size.isVariable()) {
                if (unknownSizeEventIndex != null) {
                    error("there is already a variable-sized parameter " + result.get(unknownSizeEventIndex)
                            + ". A second one cannot exit at the same time in one pattern,\nConflicting");
                }
                if (!knownParameterNames.contains(size.sizeVariable())) {
                    unknownSizeEventIndex = result.size();
                }
            } else {
                reduceUnknownSizedPlaceholder(size.constantValue());
            }
        }

        private void handleParameter() {
            String parameterName = matcher.group("parameter");
            if (!knownParameterNames.add(parameterName)) {
                error("parameter '" + parameterName + "' occurred twice");
            }
            BytePatternEvent lastEvent = last(PLACEHOLDER);
            if (lastEvent != null && lastEvent.value() == null) {
                result.add(addParameter(result.removeLast(), parameterName));
            }
        }

        private void handleGroupBegin() {
            setGroupStartEventIndex();
            result.add(groupBegin());
        }

        private void setGroupStartEventIndex() {
            if (groupStartEventIndex != null) {
                error("only one level of group expression supported");
            }
            groupStartEventIndex = result.size();
        }

        private void handleGroupEnd() {
            int groupStartIndex = unsetGroupStartEventIndex();
            String groupEndConstantLength = matcher.group("groupEndConstantLength");
            String groupEndVarName = matcher.group("groupEndFlexibleLength");
            String groupEndMultiplier = matcher.group("groupEndMultiplier");

            if (groupEndVarName != null && !knownParameterNames.contains(groupEndVarName)) {
                error("group size depending on unknown size variable '" + groupEndVarName + "'");
            }

            BytePatternEvent groupEndEvent = groupEnd(groupEndConstantLength, groupEndVarName, groupEndMultiplier);
            result.add(groupEndEvent);
            result.set(groupStartIndex, result.get(groupStartIndex).withSize(groupEndEvent.size()));

            unknownSizeEventIndex = null;
        }

        private int unsetGroupStartEventIndex() {
            if (groupStartEventIndex == null) {
                error("group end expression misses group start");
            }
            int index = groupStartEventIndex;
            groupStartEventIndex = null;
            return index;
        }

        private void handleUnknownCharacter() {
            error("format error: unexpected token '" + matcher.group("illegalCharacter") + "'");
        }

        private void error(String problem) {
            throw new IllegalArgumentException(
                    problem + " at line " + lineNo + " pos " + linePos + " in pattern\n" + hexString);
        }

        private BytePatternEvent addConstantByte(BytePatternEvent lastEvent, int byteValue) {
            BytePatternSource source = source();
            return lastEvent.withSize(lastEvent.size().add(1))
                    .withValue((long) lastEvent.value() | ((long) byteValue) << (lastEvent.size().constantValue() * 8))
                    .withSource(lastEvent.source().append(source.pattern()));
        }

        private BytePatternEvent newConstantByte(int byteValue) {
            return new BytePatternEvent(CONSTANT_BYTES, ONE, true, (long) byteValue, source());
        }

        private BytePatternEvent groupBegin() {
            return new BytePatternEvent(GROUP_BEGIN, null, null, null, source());
        }

        private BytePatternEvent groupEnd(String constantSize, String varName, String varMultiplier) {
            LinearSizeValue size = varName == null ? new LinearSizeValue(parse(constantSize, 1), 0, null)
                    : new LinearSizeValue(0, parse(varMultiplier, 1), varName);
            return new BytePatternEvent(GROUP_END, size, null, null, source());
        }

        private BytePatternEvent newPlaceholder(Boolean littleEndian, String constantSize, String varName,
                String varMultiplier) {
            LinearSizeValue size = varName == null ? new LinearSizeValue(parse(constantSize, 1), 0, null)
                    : new LinearSizeValue(0, parse(varMultiplier, 1), varName);
            return new BytePatternEvent(PLACEHOLDER, size, littleEndian, null, source());
        }

        private BytePatternEvent addPlaceholder(BytePatternEvent lastEvent, BytePatternEvent newEvent) {
            return lastEvent.withSize(lastEvent.size().add(newEvent.size()))
                    .withSource(lastEvent.source().append(newEvent.source().pattern()));
        }

        private BytePatternEvent addParameter(BytePatternEvent lastEvent, String paramName) {
            BytePatternSource source = source();
            return lastEvent.withValue(paramName).withSource(lastEvent.source().append(source.pattern()));
        }

        private BytePatternSource source() {
            return new BytePatternSource(hexString, lineNo, linePos, matcher.group());
        }

        private static int parse(String expr, int fallback) {
            return expr == null ? fallback : Integer.parseInt(expr);
        }

    }
}
