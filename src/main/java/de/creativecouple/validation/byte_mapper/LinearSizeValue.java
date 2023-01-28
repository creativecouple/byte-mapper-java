package de.creativecouple.validation.byte_mapper;

record LinearSizeValue(int constantValue, int linearFactor, String sizeVariable) {

    public static LinearSizeValue ONE = new LinearSizeValue(1, 0, null);

    public static LinearSizeValue of(String varName) {
        return new LinearSizeValue(0, 1, varName);
    }

    public LinearSizeValue add(int constant) {
        return new LinearSizeValue(constantValue + constant, linearFactor, sizeVariable);
    }

    public LinearSizeValue add(LinearSizeValue size) {
        if (size.sizeVariable == null || size.linearFactor == 0) {
            return add(size.constantValue);
        }
        if (sizeVariable != null && linearFactor != 0 && !sizeVariable.equals(size.sizeVariable)) {
            throw new IllegalArgumentException(
                    "cannot combine variable-length size '" + sizeVariable + "' with '" + size.sizeVariable + "'");
        }
        int newConstant = constantValue + size.constantValue;
        int newLinearFactor = sizeVariable == null ? size.linearFactor : linearFactor + size.linearFactor;
        return new LinearSizeValue(newConstant, newLinearFactor, size.sizeVariable);
    }

    @Override
    public String toString() {
        return constantValue + "+" + linearFactor + '*' + sizeVariable;
    }

    public boolean isVariable() {
        return sizeVariable != null && linearFactor != 0;
    }
}
