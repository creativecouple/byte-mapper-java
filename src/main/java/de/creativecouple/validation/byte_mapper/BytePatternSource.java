package de.creativecouple.validation.byte_mapper;

record BytePatternSource(String format, int lineNo, int linePos, String pattern) {
    public BytePatternSource append(String pattern) {
        return new BytePatternSource(format, lineNo, linePos, this.pattern + ' ' + pattern);
    }

    @Override
    public String toString() {
        return "source{line=" + lineNo + ",pos=" + linePos + ",pattern=" + pattern + "}";
    }
}
