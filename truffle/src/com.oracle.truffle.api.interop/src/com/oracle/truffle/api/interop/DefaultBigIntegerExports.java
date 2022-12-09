/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.interop;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.SourceSection;

@ExportLibrary(value = InteropLibrary.class, receiverType = BigInteger.class)
@SuppressWarnings("unused")
public class DefaultBigIntegerExports {

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInByte(BigInteger receiver) {
        return receiver.bitLength() < Byte.SIZE;
    }

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInShort(BigInteger receiver) {
        return receiver.bitLength() < Short.SIZE;
    }

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInInt(BigInteger receiver) {
        return receiver.bitLength() < Integer.SIZE;
    }

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInLong(BigInteger receiver) {
        return receiver.bitLength() < Long.SIZE;
    }

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInFloat(BigInteger receiver) {
        if (receiver.bitLength() <= 24) { // 24 = size of float mantissa + 1
            return true;
        } else {
            float floatValue = receiver.floatValue();
            if (!Float.isFinite(floatValue)) {
                return false;
            }
            return new BigDecimal(floatValue).toBigIntegerExact().equals(receiver);
        }
    }

    @ExportMessage
    @TruffleBoundary
    static boolean fitsInDouble(BigInteger receiver) {
        if (receiver.bitLength() <= 53) { // 53 = size of double mantissa + 1
            return true;
        } else {
            double doubleValue = receiver.doubleValue();
            if (!Double.isFinite(doubleValue)) {
                return false;
            }
            return new BigDecimal(doubleValue).toBigIntegerExact().equals(receiver);
        }
    }

    @ExportMessage
    @TruffleBoundary
    static byte asByte(BigInteger receiver) throws UnsupportedMessageException {
        try {
            return receiver.byteValueExact();
        } catch (ArithmeticException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    static short asShort(BigInteger receiver) throws UnsupportedMessageException {
        try {
            return receiver.shortValueExact();
        } catch (ArithmeticException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    static int asInt(BigInteger receiver) throws UnsupportedMessageException {
        try {
            return receiver.intValueExact();
        } catch (ArithmeticException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    static long asLong(BigInteger receiver) throws UnsupportedMessageException {
        try {
            return receiver.longValueExact();
        } catch (ArithmeticException e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    static float asFloat(BigInteger receiver) throws UnsupportedMessageException {
        if (fitsInFloat(receiver)) {
            return receiver.floatValue();
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    @TruffleBoundary
    static double asDouble(BigInteger receiver) throws UnsupportedMessageException {
        if (fitsInDouble(receiver)) {
            return receiver.doubleValue();
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    static boolean isNumber(BigInteger receiver) {
        return true;
    }

    @ExportMessage
    static boolean fitsInBigInteger(BigInteger receiver) {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    static BigInteger asBigInteger(BigInteger receiver) {
        return new BigInteger(receiver.toByteArray());
    }

    /*
     * We export these messages explicitly because the legacy default is very costly. Remove with
     * the complicated legacy implementation in InteropLibrary.
     */
    @ExportMessage
    static boolean hasLanguage(BigInteger receiver) {
        return false;
    }

    @ExportMessage
    static Class<? extends TruffleLanguage<?>> getLanguage(BigInteger receiver) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    static boolean hasSourceLocation(BigInteger receiver) {
        return false;
    }

    @ExportMessage
    static SourceSection getSourceLocation(BigInteger receiver) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    static boolean hasMetaObject(BigInteger receiver) {
        return false;
    }

    @ExportMessage
    static Object getMetaObject(BigInteger receiver) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @TruffleBoundary
    static Object toDisplayString(BigInteger receiver, boolean allowSideEffects) {
        return receiver.toString();
    }

}
