/*
 * Copyright (c) 2003, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.annotation;

/**
 * A program element type.  The constants of this enumerated type
 * provide a simple classification of the declared elements in a
 * Java program.
 *
 * <p>These constants are used with the {@link Target} meta-annotation type
 * to specify where it is legal to use an annotation type.
 *
 * 表示该注解可以用在什么地方
 *
 * @author  Joshua Bloch
 * @since 1.5
 */
public enum ElementType {
    /** Class, interface (including annotation type), or enum declaration */
    // 类，接口（包含注解类型），或者枚举声明
    TYPE,

    /** Field declaration (includes enum constants) */
    // 域声明（包含枚举实例）
    FIELD,

    /** Method declaration */
    // 方法声明
    METHOD,

    /** Formal parameter declaration */
    // 参数声明
    PARAMETER,

    /** Constructor declaration */
    // 构造函数声明
    CONSTRUCTOR,

    /** Local variable declaration */
    // 局部变量声明
    LOCAL_VARIABLE,

    /** Annotation type declaration */
    // 注解类型声明
    ANNOTATION_TYPE,

    /** Package declaration */
    // 包声明
    PACKAGE,

    /**
     * Type parameter declaration
     *
     * 参数类型声明
     *
     * @since 1.8
     * @hide 1.8
     */
    TYPE_PARAMETER,

    /**
     * Use of a type
     *
     * 使用类型声明
     *
     * @since 1.8
     * @hide 1.8
     */
    TYPE_USE
}
