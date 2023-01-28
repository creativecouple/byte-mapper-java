package de.creativecouple.validation.byte_mapper;

import lombok.With;

@With
record BytePatternEvent(BytePatternEventType event, LinearSizeValue size, Boolean littleEndian, Object value,
        BytePatternSource source) {
}
