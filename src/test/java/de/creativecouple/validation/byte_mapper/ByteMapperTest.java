package de.creativecouple.validation.byte_mapper;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ByteMapperTest {

    @ByteFormat("")
    public record Empty() {
    }

    public record WithoutAnnotation() {
    }

    @Test
    void readEmptyBytesEmptyRecord() {
        Empty empty = mapper.readBytes(new byte[0], Empty.class);
        assertThat(empty).isEqualTo(new Empty());
    }

    @ByteFormat("""
            00 88 77 aa
            """)
    public record ConstantBytesOnly() {
    }

    @ByteFormat("""
            00 10 20 30 // some comment
            ** ** ** ** :foobar
            """)
    public record SingleVariable(int foobar) {
    }

    @ByteFormat("""
            01 ** :byteDefault
            02 ** ** :shortDefault
            hi lo :shortBigEndian
            lo hi :shortLittleEndian
            03 ** ** ** ** :intDefault
            hi ** ** lo :intBigEndian
            lo ** ** hi :intLittleEndian
            04 ** ** ** ** ** ** ** ** :longDefault
            hi ** ** ** ** ** ** lo :longBigEndian
            lo ** ** ** ** ** ** hi :longLittleEndian
            05 ** ** ** :byteArray
            06
            """)
    public record NumberFormatsExample(byte byteDefault, short shortDefault, short shortBigEndian,
            short shortLittleEndian, int intDefault, int intBigEndian, int intLittleEndian, long longDefault,
            long longBigEndian, long longLittleEndian, byte[] byteArray) {
    }

    @ByteFormat("""
            * // some comment
            """)
    public record WrongFormat() {
    }

    @ByteFormat("** ** ** ** :actual")
    public record WithSameSizeVariant(SameSizeVariant actual) {
    }

    @ByteVariants({ SimpleVariant1.class, SimpleVariant2.class })
    public interface SameSizeVariant {
    }

    @ByteFormat("""
            ** ** :info
            00 AA // identifier for first variant
            """)
    public record SimpleVariant1(int info) implements SameSizeVariant {
    }

    @ByteFormat("""
            ** ** :size
            42 13 // identifier for second variant
            """)
    public record SimpleVariant2(long size) implements SameSizeVariant {
    }

    @ByteVariants(SingleVariant.class)
    public interface SingleVariantInterface {
    }

    @ByteFormat("""
            ** ** :info
            00 AA // identifier for only variant
            """)
    public record SingleVariant(int info) implements SingleVariantInterface {
    }

    @ByteFormat("""
            hi ** :$size
            AA BB // some signature
            **{$size} :body
            """)
    public record VariableParamLength(byte[] body) {
    }

    @ByteFormat("""
            ** ** : // ignore these bytes
            AA BB // some signature
            **{?} :body
            01 02 ** :lastByte
            """)
    public record RestBytesLength(byte[] body, int lastByte) {
    }

    @ByteFormat("""
            44 22 // some signature
            ** ** ** **  ** ** ** **
            ** ** ** **  ** ** ** **
            ** ** ** **  ** ** ** **
            ** ** ** **  ** ** ** ** :values
            """)
    public record ConstantSizeList(List<SingleVariable> values) {
    }

    @ByteFormat("""
            44 22 // some signature
            ** ** ** **  ** ** ** **
            ** ** ** **  :values
            """)
    public record ConstantSizeListWrongSize(List<SingleVariable> values) {
    }

    @ByteFormat("""
            33 11 // some signature
            [
              ** :$longSize
              22
              ** ** :$intSize
              **{$intSize*4} :data
              **{?} :values
            ]{$longSize*8}
            fe ff // suffix
            """)
    public record RestSizeList(List<Integer> data, List<SingleVariable> values) {
    }

    private final ByteMapper mapper = new ByteMapper();

    @Test
    void throwExceptionOnMissingAnnotation() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[0], WithoutAnnotation.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readConstantBytes_toFewBytes() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[0], ConstantBytesOnly.class))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void readConstantBytes_toManyBytes() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[20], ConstantBytesOnly.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readConstantBytes_contentMismatch() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 0, 0, 0, 0 }, ConstantBytesOnly.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("88");
    }

    @Test
    void readConstantBytes_match() {
        ConstantBytesOnly result = mapper.readBytes(new byte[] { 0, -120, 119, -86 }, ConstantBytesOnly.class);
        assertThat(result).isEqualTo(new ConstantBytesOnly());
    }

    @Test
    void writeConstantBytes() {
        byte[] result = mapper.writeBytes(new ConstantBytesOnly());
        assertThat(result).isEqualTo(new byte[] { 0, -120, 119, -86 });
    }

    @Test
    void readWrongFormat() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 0 }, WrongFormat.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("format");
    }

    @Test
    void writeWrongFormat() {
        assertThatThrownBy(() -> mapper.writeBytes(new WrongFormat())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
    }

    @Test
    void singleVariable_exactMatch() {
        SingleVariable result = mapper.readBytes(new byte[] { 0, 16, 32, 48, 10, 26, 42, 58 }, SingleVariable.class);
        assertThat(result).isEqualTo(new SingleVariable(0x0a1a2a3a));
    }

    @Test
    void singleVariable_negative() {
        SingleVariable result = mapper.readBytes(new byte[] { 0, 16, 32, 48, -1, -1, -1, -3 }, SingleVariable.class);
        assertThat(result).isEqualTo(new SingleVariable(-3));
    }

    @Test
    void singleVariable_nonExactMatch() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 0, 0, 0, 0, 10, 26, 42, 58 }, SingleVariable.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void write_singleVariable() {
        byte[] result = mapper.writeBytes(new SingleVariable(0x0a1a2a3a));
        assertThat(result).isEqualTo(new byte[] { 0, 16, 32, 48, 10, 26, 42, 58 });
    }

    @Test
    void write_negativeSingleVariable() {
        byte[] result = mapper.writeBytes(new SingleVariable(-0x80000000));
        assertThat(result).isEqualTo(new byte[] { 0, 16, 32, 48, -128, 0, 0, 0 });
    }

    @Test
    void variousNumberFormatsMatch() {
        NumberFormatsExample result = mapper
                .readBytes(
                        new byte[] { 0x01, 0x77, 0x02, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x03, 0x77, 0x66, 0x55, 0x44,
                                0x33, 0x22, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x04, 0x77, 0x66, 0x55, 0x44, 0x33,
                                0x22, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x77,
                                0x66, 0x55, 0x44, 0x33, 0x22, 0x05, 0x11, 0x22, 0x33, 0x06 },
                        NumberFormatsExample.class);
        assertThat(result).isEqualTo(new NumberFormatsExample((byte) 0x77, (short) 0x7766, (short) 0x5544,
                (short) 0x2233, 0x77665544, 0x33227766, 0x22334455, 0x7766554433227766L, 0x5544332277665544L,
                0x2233445566772233L, result.byteArray()));
        assertThat(result.byteArray()).isEqualTo(new byte[] { 0x11, 0x22, 0x33 });
    }

    @Test
    void sameSizeVariant_read() {
        SameSizeVariant result1 = mapper.readBytes(new byte[] { 0, 11, 0, -86 }, SameSizeVariant.class);
        assertThat(result1).isEqualTo(new SimpleVariant1(11));
        SameSizeVariant result2 = mapper.readBytes(new byte[] { 0, 11, 0x42, 0x13 }, SameSizeVariant.class);
        assertThat(result2).isEqualTo(new SimpleVariant2(11L));
    }

    @Test
    void sameSizeVariant_noMatch() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 1, 2, 3, 4 }, SameSizeVariant.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withSameSizeVariant_read() {
        WithSameSizeVariant result1 = mapper.readBytes(new byte[] { 0, 11, 0, -86 }, WithSameSizeVariant.class);
        assertThat(result1).isEqualTo(new WithSameSizeVariant(new SimpleVariant1(11)));
        WithSameSizeVariant result2 = mapper.readBytes(new byte[] { 0, 11, 0x42, 0x13 }, WithSameSizeVariant.class);
        assertThat(result2).isEqualTo(new WithSameSizeVariant(new SimpleVariant2(11L)));
    }

    @Test
    void withSameSizeVariant_noMatch() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 1, 2, 3, 4 }, WithSameSizeVariant.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleVariant_read() {
        SingleVariantInterface result = mapper.readBytes(new byte[] { 1, 23, 0, -86 }, SingleVariantInterface.class);
        assertThat(result).isEqualTo(new SingleVariant(279));
    }

    @Test
    void singleVariant_noMatch() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 1, 2, 3, 4 }, SingleVariantInterface.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void variableParam_read_zeroLength() {
        VariableParamLength result = mapper.readBytes(new byte[] { 0, 0, (byte) 0xAA, (byte) 0xBB },
                VariableParamLength.class);
        assertThat(result).isEqualTo(new VariableParamLength(null));
    }

    @Test
    void variableParam_write_zeroLength() {
        byte[] result = mapper.writeBytes(new VariableParamLength(null));
        assertThat(result).isEqualTo(new byte[] { 0, 0, (byte) 0xAA, (byte) 0xBB });
    }

    @Test
    void variableParam_read_nonzeroLength() {
        VariableParamLength result = mapper.readBytes(new byte[] { 0, 3, (byte) 0xAA, (byte) 0xBB, 11, 12, 13 },
                VariableParamLength.class);
        assertThat(result).isEqualTo(new VariableParamLength(result.body()));
        assertThat(result.body()).isEqualTo(new byte[] { 11, 12, 13 });
    }

    @Test
    void variableParam_write_nonzeroLength() {
        byte[] result = mapper.writeBytes(new VariableParamLength(new byte[] { 11, 12, 13 }));
        assertThat(result).isEqualTo(new byte[] { 0, 3, (byte) 0xAA, (byte) 0xBB, 11, 12, 13 });
    }

    @Test
    void restBytes_read_zeroLength() {
        RestBytesLength result = mapper.readBytes(new byte[] { 0, 0, (byte) 0xAA, (byte) 0xBB, 1, 2, 3 },
                RestBytesLength.class);
        assertThat(result).isEqualTo(new RestBytesLength(null, 3));
    }

    @Test
    void restBytes_write_zeroLength() {
        byte[] result = mapper.writeBytes(new RestBytesLength(null, 3));
        assertThat(result).isEqualTo(new byte[] { 0, 0, (byte) 0xAA, (byte) 0xBB, 1, 2, 3 });
    }

    @Test
    void restBytes_read_nonzeroLength() {
        RestBytesLength result = mapper.readBytes(new byte[] { 0, 3, (byte) 0xAA, (byte) 0xBB, 11, 12, 13, 1, 2, 3 },
                RestBytesLength.class);
        assertThat(result).isEqualTo(new RestBytesLength(result.body(), 3));
        assertThat(result.body()).isEqualTo(new byte[] { 11, 12, 13 });
    }

    @Test
    void restBytes_write_nonzeroLength() {
        byte[] result = mapper.writeBytes(new RestBytesLength(new byte[] { 11, 12, 13 }, 4));
        assertThat(result).isEqualTo(new byte[] { 0, 0, (byte) 0xAA, (byte) 0xBB, 11, 12, 13, 1, 2, 4 });
    }

    @Test
    void constantSizeList_read_zeroLength() {
        assertThatThrownBy(() -> mapper.readBytes(new byte[] { 0x44, 0x22 }, ConstantSizeList.class))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void constantSizeList_write_zeroLength() {
        assertThatThrownBy(() -> mapper.writeBytes(new ConstantSizeList(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.writeBytes(new ConstantSizeList(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constantSizeList_read_correctLength() {
        ConstantSizeList result = mapper.readBytes(new byte[] { 0x44, 0x22, 0, 16, 32, 48, 0, 0, 0, 3, 0, 16, 32, 48, 0,
                0, 0, 2, 0, 16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0 }, ConstantSizeList.class);
        assertThat(result).isEqualTo(new ConstantSizeList(
                List.of(new SingleVariable(3), new SingleVariable(2), new SingleVariable(1), new SingleVariable(0))));
    }

    @Test
    void constantSizeList_write_correctLength() {
        byte[] result = mapper.writeBytes(new ConstantSizeList(
                List.of(new SingleVariable(3), new SingleVariable(2), new SingleVariable(1), new SingleVariable(0))));
        assertThat(result).isEqualTo(new byte[] { 0x44, 0x22, 0, 16, 32, 48, 0, 0, 0, 3, 0, 16, 32, 48, 0, 0, 0, 2, 0,
                16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0 });
    }

    @Test
    void constantSizeList_read_tooMuch() {
        ConstantSizeList result = mapper.readBytes(
                new byte[] { 0x44, 0x22, 0, 16, 32, 48, 0, 0, 0, 5, 0, 16, 32, 48, 0, 0, 0, 4, 0, 16, 32, 48, 0, 0, 0,
                        3, 0, 16, 32, 48, 0, 0, 0, 2, 0, 16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0 },
                ConstantSizeList.class);
        assertThat(result).isEqualTo(new ConstantSizeList(
                List.of(new SingleVariable(5), new SingleVariable(4), new SingleVariable(3), new SingleVariable(2))));
    }

    @Test
    void constantSizeList_write_tooMuch() {
        assertThatThrownBy(
                () -> mapper.writeBytes(new ConstantSizeList(List.of(new SingleVariable(5), new SingleVariable(4),
                        new SingleVariable(3), new SingleVariable(2), new SingleVariable(1), new SingleVariable(0)))))
                                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restSizeList_read_zeroLength() {
        RestSizeList result = mapper.readBytes(new byte[] { 0x33, 0x11, 1, 0x22, 0, 1, 1, 2, 3, 4, -2, -1 },
                RestSizeList.class);
        assertThat(result).isEqualTo(new RestSizeList(List.of(0x1020304), List.of()));
    }

    @Test
    void restSizeList_write_zeroLength() {
        byte[] result1 = mapper.writeBytes(new RestSizeList(null, null));
        assertThat(result1).isEqualTo(new byte[] { 0x33, 0x11, 0, 0x22, 0, 0, -2, -1 });
        byte[] result2 = mapper.writeBytes(new RestSizeList(List.of(), List.of()));
        assertThat(result2).isEqualTo(new byte[] { 0x33, 0x11, 0, 0x22, 0, 0, -2, -1 });
    }

    @Test
    void restSizeList_read_correctLength() {
        RestSizeList result = mapper
                .readBytes(
                        new byte[] { 0x33, 0x11, 5, 0x22, 0, 1, 4, 4, 4, 4, 0, 16, 32, 48, 0, 0, 0, 3, 0, 16, 32, 48, 0,
                                0, 0, 2, 0, 16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0, -2, -1 },
                        RestSizeList.class);
        assertThat(result).isEqualTo(new RestSizeList(List.of(0x4040404),
                List.of(new SingleVariable(3), new SingleVariable(2), new SingleVariable(1), new SingleVariable(0))));
    }

    @Test
    void restSizeList_write_correctLength() {
        byte[] result = mapper.writeBytes(new RestSizeList(List.of(0x4040404),
                List.of(new SingleVariable(3), new SingleVariable(2), new SingleVariable(1), new SingleVariable(0))));
        assertThat(result).isEqualTo(new byte[] { 0x33, 0x11, 5, 0x22, 0, 1, 4, 4, 4, 4, 0, 16, 32, 48, 0, 0, 0, 3, 0,
                16, 32, 48, 0, 0, 0, 2, 0, 16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0, -2, -1 });
    }

    @Test
    void constantSizeListWrongSize_read_correctLength() {
        assertThatThrownBy(
                () -> mapper.readBytes(new byte[] { 0x33, 0x11, 0, 16, 32, 48, 0, 0, 0, 1, 0, 16, 32, 48, 0, 0, 0, 0 },
                        ConstantSizeListWrongSize.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constantSizeListWrongSize_write_correctLength() {
        assertThatThrownBy(() -> mapper
                .writeBytes(new ConstantSizeListWrongSize(List.of(new SingleVariable(1), new SingleVariable(0)))))
                        .isInstanceOf(IllegalArgumentException.class);
    }

}