/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.util;

import org.apache.commons.lang3.exception.ExceptionUtils;

import android.databinding.tool.processing.ScopedException;

import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;

public class L {
    private static boolean sEnableDebug = false;
    private static final Client sSystemClient = new Client() {
        @Override
        public void printMessage(Kind kind, String message) {
            if (kind == Kind.ERROR) {
                System.err.println(message);
            } else {
                System.out.println(message);
            }
        }
    };

    private static Client sClient = sSystemClient;

    public static void setClient(Client systemClient) {
        L.sClient = systemClient;
    }

    public static void setDebugLog(boolean enabled) {
        sEnableDebug = enabled;
    }

    public static void d(String msg, Object... args) {
        if (sEnableDebug) {
            printMessage(Diagnostic.Kind.NOTE, String.format(msg, args));
        }
    }

    public static void d(Throwable t, String msg, Object... args) {
        if (sEnableDebug) {
            printMessage(Diagnostic.Kind.NOTE,
                    String.format(msg, args) + " " + ExceptionUtils.getStackTrace(t));
        }
    }

    public static void w(String msg, Object... args) {
        printMessage(Kind.WARNING, String.format(msg, args));
    }

    public static void w(Throwable t, String msg, Object... args) {
        printMessage(Kind.WARNING,
                String.format(msg, args) + " " + ExceptionUtils.getStackTrace(t));
    }

    private static void tryToThrowScoped(Throwable t, String fullMessage) {
        if (t instanceof ScopedException) {
            ScopedException ex = (ScopedException) t;
            if (ex.isValid()) {
                throw ex;
            }
        }
        ScopedException ex = new ScopedException(fullMessage);
        if (ex.isValid()) {
            throw ex;
        }
    }

    public static void e(String msg, Object... args) {
        String fullMsg = String.format(msg, args);
        tryToThrowScoped(null, fullMsg);
        printMessage(Diagnostic.Kind.ERROR, fullMsg);
    }

    public static void e(Throwable t, String msg, Object... args) {
        String fullMsg = String.format(msg, args);
        tryToThrowScoped(t, fullMsg);
        printMessage(Diagnostic.Kind.ERROR,
                fullMsg + " " + ExceptionUtils.getStackTrace(t));
    }

    private static void printMessage(Diagnostic.Kind kind, String message) {
        sClient.printMessage(kind, message);
        if (kind == Diagnostic.Kind.ERROR) {
            throw new RuntimeException("failure, see logs for details.\n" + message);
        }
    }

    public static boolean isDebugEnabled() {
        return sEnableDebug;
    }

    public static interface Client {
        public void printMessage(Diagnostic.Kind kind, String message);
    }
}
