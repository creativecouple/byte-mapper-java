package de.creativecouple.validation.byte_mapper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ByteArrays {

    public static String toString(byte[] array) {
        if (array == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder().append('[');
        for (int i = 0; i < array.length && i < 5; i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(Integer.toHexString(array[i] & 0xFF | 0x100).substring(1));
        }
        if (array.length == 6) {
            result.append(' ').append(Integer.toHexString(array[5] & 0xFF | 0x100).substring(1));
        } else if (array.length > 6) {
            result.append(" ..");
        }
        result.append(']').append('{').append(array.length).append('}');
        return result.toString();
    }

}
