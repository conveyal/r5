package com.conveyal.osmlib;

// Conveyal variable-width integer utilities based on Google Protocol Buffers.
// This file is an adapted subset of CodedOutputStream.java and therefore includes the original license below.
//
// Protocol Buffers - Google's data interchange format
// Copyright 2008 Google Inc.  All rights reserved.
// http://code.google.com/p/protobuf/
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//     * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//     * Neither the name of Google Inc. nor the names of its
// contributors may be used to endorse or promote products derived from
// this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

import java.io.IOException;
import java.io.OutputStream;

public class VarIntOutputStream {

    private OutputStream os;

    public VarIntOutputStream(OutputStream os) {
        this.os = os;
    }

    public void writeByte(int value) throws IOException {
        os.write((byte)value);
    }

    public void writeBytes(byte[] bytes) throws IOException {
        os.write(bytes);
    }

    public void writeString(String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        this.writeUInt32(bytes.length);
        this.writeBytes(bytes);
    }

    public static int encodeZigZag32(int n) {
        return n << 1 ^ n >> 31;
    }

    public static long encodeZigZag64(long n) {
        return n << 1 ^ n >> 63;
    }

    public void writeSInt32(int value) throws IOException {
        writeUInt32(encodeZigZag32(value));
    }

    public void writeSInt64(long value) throws IOException {
        writeUInt64(encodeZigZag64(value));
    }

    public void writeUInt64(long value) throws IOException {
        while((value & -128L) != 0L) {
            writeByte((int) value & 127 | 128);
            value >>>= 7;
        }
        writeByte((int) value);
    }

    public void writeUInt32(int value) throws IOException {
        while((value & -128) != 0) {
            writeByte(value & 127 | 128);
            value >>>= 7;
        }
        writeByte(value);
    }

    public void close() throws IOException {
        os.close();
    }

}
