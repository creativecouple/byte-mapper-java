package de.creativecouple.validation.byte_mapper;

import java.io.ByteArrayOutputStream;

class ResettableByteArrayOutputStream extends ByteArrayOutputStream {
    int unresetPos = -1;

    public int count() {
        return count;
    }

    public synchronized void reset(int pos) {
        this.unresetPos = this.count;
        this.count = pos;
    }

    public synchronized void unreset() {
        this.count = this.unresetPos;
        this.unresetPos = -1;
    }
}
