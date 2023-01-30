package de.creativecouple.validation.byte_mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearSizeValueTest {

    @Test
    void constant() {
        LinearSizeValue value = new LinearSizeValue(42, 0, null);
        assertThat(value.constantValue()).isEqualTo(42);
        assertThat(value.isVariable()).isFalse();
        assertThat(value.linearFactor()).isEqualTo(0);
        assertThat(value.sizeVariable()).isNull();
    }

    @Test
    void variable() {
        LinearSizeValue value = LinearSizeValue.of("test");
        assertThat(value.constantValue()).isEqualTo(0);
        assertThat(value.isVariable()).isTrue();
        assertThat(value.linearFactor()).isEqualTo(1);
        assertThat(value.sizeVariable()).isEqualTo("test");
    }

    @Test
    void add() {
        LinearSizeValue value = LinearSizeValue.of("test").add(10);
        assertThat(value.constantValue()).isEqualTo(10);
        assertThat(value.isVariable()).isTrue();
        assertThat(value.linearFactor()).isEqualTo(1);
        assertThat(value.sizeVariable()).isEqualTo("test");

        assertThatThrownBy(() -> value.add(LinearSizeValue.of("foobar")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot combine variable-length size");

        LinearSizeValue newValue = value.add(LinearSizeValue.of("test").add(2));
        assertThat(newValue.constantValue()).isEqualTo(12);
        assertThat(newValue.isVariable()).isTrue();
        assertThat(newValue.linearFactor()).isEqualTo(2);
        assertThat(newValue.sizeVariable()).isEqualTo("test");
    }
}