/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
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
package java.util.stream;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

// Android-added: http://b/329219364 Moved this from Gatherer.java due to syntax error in Metalava.
record GathererImpl<T, A, R>(
        @Override Supplier<A> initializer,
        @Override Integrator<A, T, R> integrator,
        @Override BinaryOperator<A> combiner,
        @Override BiConsumer<A, Downstream<? super R>> finisher) implements Gatherer<T, A, R> {

    static <T, A, R> GathererImpl<T, A, R> of(
            Supplier<A> initializer,
            Integrator<A, T, R> integrator,
            BinaryOperator<A> combiner,
            BiConsumer<A, Downstream<? super R>> finisher) {
        return new GathererImpl<>(
                Objects.requireNonNull(initializer,"initializer"),
                Objects.requireNonNull(integrator, "integrator"),
                Objects.requireNonNull(combiner, "combiner"),
                Objects.requireNonNull(finisher, "finisher")
        );
    }
}
