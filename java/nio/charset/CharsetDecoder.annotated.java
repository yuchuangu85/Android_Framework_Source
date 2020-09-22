/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
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

// -- This file was mechanically generated: Do not edit! -- //


package java.nio.charset;

import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.charset.CoderMalfunctionError;

@SuppressWarnings({"unchecked", "deprecation", "all"})
public abstract class CharsetDecoder {

@libcore.api.IntraCoreApi
protected CharsetDecoder(java.nio.charset.Charset cs, float averageCharsPerByte, float maxCharsPerByte) { throw new RuntimeException("Stub!"); }

public final java.nio.charset.Charset charset() { throw new RuntimeException("Stub!"); }

public final java.lang.String replacement() { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CharsetDecoder replaceWith(java.lang.String newReplacement) { throw new RuntimeException("Stub!"); }

protected void implReplaceWith(java.lang.String newReplacement) { throw new RuntimeException("Stub!"); }

public java.nio.charset.CodingErrorAction malformedInputAction() { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CharsetDecoder onMalformedInput(java.nio.charset.CodingErrorAction newAction) { throw new RuntimeException("Stub!"); }

protected void implOnMalformedInput(java.nio.charset.CodingErrorAction newAction) { throw new RuntimeException("Stub!"); }

public java.nio.charset.CodingErrorAction unmappableCharacterAction() { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CharsetDecoder onUnmappableCharacter(java.nio.charset.CodingErrorAction newAction) { throw new RuntimeException("Stub!"); }

protected void implOnUnmappableCharacter(java.nio.charset.CodingErrorAction newAction) { throw new RuntimeException("Stub!"); }

public final float averageCharsPerByte() { throw new RuntimeException("Stub!"); }

public final float maxCharsPerByte() { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CoderResult decode(java.nio.ByteBuffer in, java.nio.CharBuffer out, boolean endOfInput) { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CoderResult flush(java.nio.CharBuffer out) { throw new RuntimeException("Stub!"); }

protected java.nio.charset.CoderResult implFlush(java.nio.CharBuffer out) { throw new RuntimeException("Stub!"); }

public final java.nio.charset.CharsetDecoder reset() { throw new RuntimeException("Stub!"); }

protected void implReset() { throw new RuntimeException("Stub!"); }

protected abstract java.nio.charset.CoderResult decodeLoop(java.nio.ByteBuffer in, java.nio.CharBuffer out);

public final java.nio.CharBuffer decode(java.nio.ByteBuffer in) throws java.nio.charset.CharacterCodingException { throw new RuntimeException("Stub!"); }

public boolean isAutoDetecting() { throw new RuntimeException("Stub!"); }

public boolean isCharsetDetected() { throw new RuntimeException("Stub!"); }

public java.nio.charset.Charset detectedCharset() { throw new RuntimeException("Stub!"); }
}

