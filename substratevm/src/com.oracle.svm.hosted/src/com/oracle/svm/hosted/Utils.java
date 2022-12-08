/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted;

import com.oracle.svm.core.util.VMError;
import com.oracle.svm.util.ReflectionUtil;

import java.lang.reflect.Field;

public class Utils {
    private static final double MILLIS_TO_SECONDS = 1000d;
    private static final double NANOS_TO_SECONDS = 1000d * 1000d * 1000d;
    private static final double BYTES_TO_KiB = 1024d;
    private static final double BYTES_TO_MiB = 1024d * 1024d;
    private static final double BYTES_TO_GiB = 1024d * 1024d * 1024d;

    private static final Field STRING_VALUE = ReflectionUtil.lookupField(String.class, "value");

    public static String bytesToHuman(long bytes) {
        return bytesToHuman("%4.2f", bytes);
    }

    public static String bytesToHuman(String format, long bytes) {
        if (bytes < BYTES_TO_KiB) {
            return String.format(format, (double) bytes) + "B";
        } else if (bytes < BYTES_TO_MiB) {
            return String.format(format, bytesToKiB(bytes)) + "KB";
        } else if (bytes < BYTES_TO_GiB) {
            return String.format(format, bytesToMiB(bytes)) + "MB";
        } else {
            return String.format(format, bytesToGiB(bytes)) + "GB";
        }
    }

    private static double bytesToKiB(long bytes) {
        return bytes / BYTES_TO_KiB;
    }

    static double bytesToGiB(long bytes) {
        return bytes / BYTES_TO_GiB;
    }

    private static double bytesToMiB(long bytes) {
        return bytes / BYTES_TO_MiB;
    }

    public static double millisToSeconds(double millis) {
        return millis / MILLIS_TO_SECONDS;
    }

    public static double nanosToSeconds(double nanos) {
        return nanos / NANOS_TO_SECONDS;
    }

    public static int getInternalByteArrayLength(String string) {
        try {
            return ((byte[]) STRING_VALUE.get(string)).length;
        } catch (ReflectiveOperationException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    static double getUsedMemory() {
        return bytesToGiB(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    }

    static String stringFilledWith(int size, String fill) {
        return new String(new char[size]).replace("\0", fill);
    }

    static String truncateClassOrPackageName(String classOrPackageName, int maxLength) {
        int classNameLength = classOrPackageName.length();
        if (classNameLength <= maxLength) {
            return classOrPackageName;
        }
        StringBuilder sb = new StringBuilder();
        int currentDot = -1;
        while (true) {
            int nextDot = classOrPackageName.indexOf('.', currentDot + 1);
            if (nextDot < 0) { // Not more dots, handle the rest and return.
                String rest = classOrPackageName.substring(currentDot + 1);
                int sbLength = sb.length();
                int restLength = rest.length();
                if (sbLength + restLength <= maxLength) {
                    sb.append(rest);
                } else {
                    int remainingSpaceDivBy2 = (maxLength - sbLength) / 2;
                    sb.append(rest.substring(0, remainingSpaceDivBy2 - 1) + "~" + rest.substring(restLength - remainingSpaceDivBy2, restLength));
                }
                break;
            }
            sb.append(classOrPackageName.charAt(currentDot + 1)).append('.');
            if (sb.length() + (classNameLength - nextDot) <= maxLength) {
                // Rest fits maxLength, append and return.
                sb.append(classOrPackageName.substring(nextDot + 1));
                break;
            }
            currentDot = nextDot;
        }
        return sb.toString();
    }
}
