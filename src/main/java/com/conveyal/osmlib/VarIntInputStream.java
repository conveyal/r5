package com.conveyal.osmlib;

// Conveyal variable-width integer utilities based on Google Protocol Buffers.
// This file is an adapted subset of CodedInputStream.java and therefore includes the original license below.
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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class VarIntInputStream {

    private InputStream inputStream;

    public VarIntInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public byte readRawByte() throws IOException {
        return (byte)(inputStream.read());
    }

    public String readString() throws IOException {
        int size = this.readUInt32();
        return new String(this.readBytes(size), "UTF-8");
    }

    /** Implements the same read loop found in DataInputStream.readFully(). */
    public byte[] readBytes(int len) throws IOException {
        if (len > 1024) {
            throw new RuntimeException(String.format("Attempted to read %d bytes at once, file is probably corrupted.", len));
        }
        byte[] buf = new byte[len];
        int n = 0;
        while (n < len) {
            int count = inputStream.read(buf, n, len - n);
            if (count < 0) {
                throw new EOFException();
            }
            n += count;
        }
        return buf;
    }

    public static int decodeZigZag32(int n) {
        return n >>> 1 ^ -(n & 1);
    }

    public static long decodeZigZag64(long n) {
        return n >>> 1 ^ -(n & 1L);
    }

    public int readSInt32() throws IOException {
        return decodeZigZag32(this.readUInt32());
    }

    public long readSInt64() throws IOException {
        return decodeZigZag64(this.readUInt64());
    }

    public int readUInt32() throws IOException {
        byte tmp = this.readRawByte();
        if(tmp >= 0) {
            return tmp;
        } else {
            int result = tmp & 127;
            if((tmp = this.readRawByte()) >= 0) {
                result |= tmp << 7;
            } else {
                result |= (tmp & 127) << 7;
                if((tmp = this.readRawByte()) >= 0) {
                    result |= tmp << 14;
                } else {
                    result |= (tmp & 127) << 14;
                    if((tmp = this.readRawByte()) >= 0) {
                        result |= tmp << 21;
                    } else {
                        result |= (tmp & 127) << 21;
                        result |= (tmp = this.readRawByte()) << 28;
                        if(tmp < 0) {
                            for(int i = 0; i < 5; ++i) {
                                if(this.readRawByte() >= 0) {
                                    return result;
                                }
                            }
                            throw new NumberFormatException();
                        }
                    }
                }
            }
            return result;
        }
    }

    public long readUInt64() throws IOException {
        int shift = 0;
        for(long result = 0L; shift < 64; shift += 7) {
            byte b = readRawByte();
            result |= (long)(b & 127) << shift;
            if((b & 128) == 0) {
                return result;
            }
        }
        throw new NumberFormatException();
    }

}
