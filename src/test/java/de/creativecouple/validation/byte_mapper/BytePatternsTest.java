package de.creativecouple.validation.byte_mapper;

import org.junit.jupiter.api.Test;

import static de.creativecouple.validation.byte_mapper.BytePatternEventType.CONSTANT_BYTES;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.GROUP_BEGIN;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.GROUP_END;
import static de.creativecouple.validation.byte_mapper.BytePatternEventType.PLACEHOLDER;
import static org.assertj.core.api.Assertions.assertThat;

public class BytePatternsTest {

    @Test
    void parseEmptyFormat() {
        assertThat(BytePatterns.parse("")).isEmpty();
    }

    @Test
    void parseConstantBytes() {
        String hexString = """
                00 11 22 //
                33 44 55 // some constant
                """;
        assertThat(BytePatterns.parse(hexString))
                .containsExactly(event(CONSTANT_BYTES, true, 0x554433221100L).withSize(new LinearSizeValue(6, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "00 11 22 33 44 55")));
    }

    @Test
    void parseManyConstantBytes() {
        String hexString = """
                00 11 22 33 // signature
                44 55 66 77
                  88 99 aa bb
                cc""";
        assertThat(BytePatterns.parse(hexString)).containsExactly(
                event(CONSTANT_BYTES, true, 0x7766554433221100L).withSize(new LinearSizeValue(8, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "00 11 22 33 44 55 66 77")),
                event(CONSTANT_BYTES, true, 0xccbbaa9988L).withSize(new LinearSizeValue(5, 0, null))
                        .withSource(new BytePatternSource(hexString, 3, 2, "88 99 aa bb cc")));
    }

    @Test
    void parseAnonymousPlaceholder() {
        String hexString = """
                **  ** \t ** // normalize whitespaces in pattern
                """;
        assertThat(BytePatterns.parse(hexString))
                .containsExactly(event(PLACEHOLDER, null, null).withSize(new LinearSizeValue(3, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "** ** **")));
    }

    @Test
    void parseLittleBigEndianPlaceholder() {
        String hexString = """
                hi lo : // ignore parameter
                lo **{2} hi :foobar
                """;
        assertThat(BytePatterns.parse(hexString)).containsExactly(
                event(PLACEHOLDER, false, "").withSize(new LinearSizeValue(2, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "hi lo :")),
                event(PLACEHOLDER, true, "foobar").withSize(new LinearSizeValue(4, 0, null))
                        .withSource(new BytePatternSource(hexString, 2, 0, "lo **{2} hi :foobar")));
    }

    @Test
    void parseVariableSizedPlaceholder() {
        String hexString = """
                hi lo :$size
                ** ** **{$size*4} :foobar
                **{foobar} :data
                **{?} ** ** :rest
                **{6} :suffix
                """;
        assertThat(BytePatterns.parse(hexString)).containsExactly(
                event(PLACEHOLDER, false, "$size").withSize(new LinearSizeValue(2, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "hi lo :$size")),
                event(PLACEHOLDER, null, "foobar").withSize(new LinearSizeValue(2, 4, "$size"))
                        .withSource(new BytePatternSource(hexString, 2, 0, "** ** **{$size*4} :foobar")),
                event(PLACEHOLDER, null, "data").withSize(new LinearSizeValue(0, 1, "foobar"))
                        .withSource(new BytePatternSource(hexString, 3, 0, "**{foobar} :data")),
                event(PLACEHOLDER, null, "rest").withSize(new LinearSizeValue(-6, 1, "?"))
                        .withSource(new BytePatternSource(hexString, 4, 0, "**{?} ** ** :rest")),
                event(PLACEHOLDER, null, "suffix").withSize(new LinearSizeValue(6, 0, null))
                        .withSource(new BytePatternSource(hexString, 5, 0, "**{6} :suffix")));
    }

    @Test
    void parseSimpleGroup() {
        String hexString = """
                ** ** :$size
                [11
                hi{?} :test]{$size}
                """;
        assertThat(BytePatterns.parse(hexString)).containsExactly(
                event(PLACEHOLDER, null, "$size").withSize(new LinearSizeValue(2, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "** ** :$size")),
                event(GROUP_BEGIN, null, null).withSize(new LinearSizeValue(0, 1, "$size"))
                        .withSource(new BytePatternSource(hexString, 2, 0, "[")),
                event(CONSTANT_BYTES, true, (long) 0x11).withSize(new LinearSizeValue(1, 0, null))
                        .withSource(new BytePatternSource(hexString, 2, 1, "11")),
                event(PLACEHOLDER, false, "test").withSize(new LinearSizeValue(0, 1, "?"))
                        .withSource(new BytePatternSource(hexString, 3, 0, "hi{?} :test")),
                event(GROUP_END, null, null).withSize(new LinearSizeValue(0, 1, "$size"))
                        .withSource(new BytePatternSource(hexString, 3, 11, "]{$size}")));
    }

    @Test
    void parseGroupSuffix() {
        String hexString = """
                ** ** :$size
                [11
                hi{?} :test]{$size}
                aa bb cc // suffix
                """;
        assertThat(BytePatterns.parse(hexString)).containsExactly(
                event(PLACEHOLDER, null, "$size").withSize(new LinearSizeValue(2, 0, null))
                        .withSource(new BytePatternSource(hexString, 1, 0, "** ** :$size")),
                event(GROUP_BEGIN, null, null).withSize(new LinearSizeValue(0, 1, "$size"))
                        .withSource(new BytePatternSource(hexString, 2, 0, "[")),
                event(CONSTANT_BYTES, true, (long) 0x11).withSize(new LinearSizeValue(1, 0, null))
                        .withSource(new BytePatternSource(hexString, 2, 1, "11")),
                event(PLACEHOLDER, false, "test").withSize(new LinearSizeValue(0, 1, "?"))
                        .withSource(new BytePatternSource(hexString, 3, 0, "hi{?} :test")),
                event(GROUP_END, null, null).withSize(new LinearSizeValue(0, 1, "$size"))
                        .withSource(new BytePatternSource(hexString, 3, 11, "]{$size}")),
                event(CONSTANT_BYTES, true, (long) 0xccbbaa).withSize(new LinearSizeValue(3, 0, null))
                        .withSource(new BytePatternSource(hexString, 4, 0, "aa bb cc")));
    }

    private BytePatternEvent event(BytePatternEventType type, Boolean littleEndian, Object value) {
        return new BytePatternEvent(type, null, littleEndian, value, null);
    }
}