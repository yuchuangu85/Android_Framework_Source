/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ike.ikev2;

import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;

/** Package private factory for making ChildSessionStateMachine. */
//TODO: Make it a inner Creator class of ChildSessionStateMachine
final class ChildSessionStateMachineFactory {

    private static IChildSessionFactoryHelper sChildSessionHelper = new ChildSessionFactoryHelper();

    /** Package private. */
    static ChildSessionStateMachine makeChildSessionStateMachine(
            String name, Looper looper, ChildSessionOptions sessionOptions) {
        return sChildSessionHelper.makeChildSessionStateMachine(name, looper, sessionOptions);
    }

    @VisibleForTesting
    static void setChildSessionFactoryHelper(IChildSessionFactoryHelper helper) {
        sChildSessionHelper = helper;
    }

    /**
     * IChildSessionFactoryHelper provides a package private interface for constructing
     * ChildSessionStateMachine.
     *
     * <p>IChildSessionFactoryHelper exists so that the interface is injectable for testing.
     */
    interface IChildSessionFactoryHelper {
        ChildSessionStateMachine makeChildSessionStateMachine(
                String name, Looper looper, ChildSessionOptions sessionOptions);
    }

    /**
     * ChildSessionFactoryHelper implements a method for constructing ChildSessionStateMachine.
     *
     * <p>Package private.
     */
    static class ChildSessionFactoryHelper implements IChildSessionFactoryHelper {
        public ChildSessionStateMachine makeChildSessionStateMachine(
                String name, Looper looper, ChildSessionOptions sessionOptions) {
            ChildSessionStateMachine childSession =
                    new ChildSessionStateMachine(name, looper, sessionOptions);
            childSession.start();
            return childSession;
        }
    }
}
