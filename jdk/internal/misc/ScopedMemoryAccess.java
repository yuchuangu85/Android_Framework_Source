/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.misc;

import java.util.function.Supplier;

import jdk.internal.foreign.MemorySessionImpl;


/**
 * This class defines low-level methods to access on-heap and off-heap memory. The methods in this class
 * can be thought of as thin wrappers around methods provided in the {@link Unsafe} class. All the methods in this
 * class accept one or more {@link MemorySessionImpl} parameter, which is used to validate as to whether access to memory
 * can be performed in a safe fashion - more specifically, to ensure that the memory being accessed has not
 * already been released (which would result in a hard VM crash).
 * <p>
 * Accessing and releasing memory from a single thread is not problematic - after all, a given thread cannot,
 * at the same time, access a memory region <em>and</em> free it. But ensuring correctness of memory access
 * when multiple threads are involved is much trickier, as there can be cases where a thread is accessing
 * a memory region while another thread is releasing it.
 * <p>
 * This class provides tools to manage races when multiple threads are accessing and/or releasing the same memory
 * session concurrently. More specifically, when a thread wants to release a memory session, it should call the
 * {@link ScopedMemoryAccess#closeScope(MemorySessionImpl)} method. This method initiates thread-local handshakes with all the other VM threads,
 * which are then stopped one by one. If any thread is found accessing a resource associated to the very memory session
 * being closed, the handshake fails, and the session will not be closed.
 * <p>
 * This synchronization strategy relies on the idea that accessing memory is atomic with respect to checking the
 * validity of the session associated with that memory region - that is, a thread that wants to perform memory access will be
 * suspended either <em>before</em> a liveness check or <em>after</em> the memory access. To ensure this atomicity,
 * all methods in this class are marked with the special {@link Scoped} annotation, which is recognized by the VM,
 * and used during the thread-local handshake to detect (and stop) threads performing potentially problematic memory access
 * operations. Additionally, to make sure that the session object(s) of the memory being accessed is always
 * reachable during an access operation, all the methods in this class add reachability fences around the underlying
 * unsafe access.
 * <p>
 * This form of synchronization allows APIs to use plain memory access without any other form of synchronization
 * which might be deemed to expensive; in other words, this approach prioritizes the performance of memory access over
 * that of releasing a shared memory resource.
 */
// Android-note : this file is from X-ScopedMemoryAccess.java.template.
// We don't import the rest of the file because that is used in upstream implementation for
// varhandle.
// At the time we only require it for MemorySessionImpl.
public class ScopedMemoryAccess {

    private ScopedMemoryAccess() {}

    private static final ScopedMemoryAccess theScopedMemoryAccess = new ScopedMemoryAccess();

    public static final class ScopedAccessError extends Error {

        @SuppressWarnings("serial")
        private final Supplier<RuntimeException> runtimeExceptionSupplier;

        public ScopedAccessError(Supplier<RuntimeException> runtimeExceptionSupplier) {
            super("Invalid memory access", null, false, false);
            this.runtimeExceptionSupplier = runtimeExceptionSupplier;
        }

        static final long serialVersionUID = 1L;

        public final RuntimeException newRuntimeException() {
            return runtimeExceptionSupplier.get();
        }
    }

}
