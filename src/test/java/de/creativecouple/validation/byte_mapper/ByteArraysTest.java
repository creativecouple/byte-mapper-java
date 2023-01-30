package de.creativecouple.validation.byte_mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ByteArraysTest {

    @Test
    void empty() {
        assertThat(ByteArrays.toString(null)).isEqualTo("null");
        assertThat(ByteArrays.toString(new byte[0])).isEqualTo("[]{0}");
    }

    @Test
    void small() {
        assertThat(ByteArrays.toString(new byte[]{-1})).isEqualTo("[ff]{1}");
        assertThat(ByteArrays.toString(new byte[]{0, 1, 2, 3, 4, 5}))
                .isEqualTo("[00 01 02 03 04 05]{6}");
    }

    @Test
    void longer() {
        assertThat(ByteArrays.toString(new byte[]{0, -1, 2, -3, 4, -5, 6, -7, 8, -9, 10, -11, 12, -13, 14, -15, 16, -17, 18, -19, 20}))
                .isEqualTo("[00 ff 02 fd 04 ..]{21}");
    }
}