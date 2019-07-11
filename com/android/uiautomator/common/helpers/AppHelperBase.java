/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.uiautomator.common.helpers;

import java.io.BufferedReader;
import java.io.IOException;

/**
 * Base app helper class intended for all app helper to extend.
 * This class provides common APIs that are expected to be present across
 * all app helpers.
 */
public abstract class AppHelperBase {

    /*
     * App helpers should provide methods for accessing various UI widgets.
     * Assume the app has an Action Bar, the helper should provide something similar to
     * SomeAppHelper.ActionBar.getRefreshButton(). Methods like this help the tests check the
     * state of the targeted widget as well as clicking it if need be. These types of methods are
     * referred to as object getters. If there are different components, consider creating internal
     * name spaces as in the .ActionBar example for better context.
     *
     * Adding basic units of functionality APIs is also very helpful to test.
     * Consider the Alarm clock application as an example. It would be helpful if its helper
     * provided basic functionality such as, setAlarm(Date) and deleteAlarm(Date). Such basic
     * and key functionality helper methods, will abstract the tests from the UI implementation and
     * make tests more reliable.
     */

    /**
     * Launches the application.
     *
     * This is typically performed by executing a shell command to launch the application
     * via Intent. It is possible to launch the application by automating the Launcher
     * views and finding the target app icon to launch, however, this is prone to failure if
     * the Launcher UI implementation differ from one platform to another.
     */
    abstract public void open();

    /**
     * Checks if the application is in foreground.
     *
     * This is typically performed by verifying the current package name of the foreground
     * application. See UiDevice.getCurrentPackageName()
     * @return true if open, else false.
     */
    abstract public boolean isOpen();


    /**
     * Helper to execute a shell command.
     * @param command
     */
    protected void runShellCommand(String command) {
        Process p = null;
        BufferedReader resultReader = null;
        try {
            p = Runtime.getRuntime().exec(command);
            int status = p.waitFor();
            if (status != 0) {
                System.err.println(String.format("Run shell command: %s, status: %s", command,
                        status));
            }
        } catch (IOException e) {
            System.err.println("// Exception from command " + command + ":");
            System.err.println(e.toString());
        } catch (InterruptedException e) {
            System.err.println("// Interrupted while waiting for the command to finish. ");
            System.err.println(e.toString());
        } finally {
            try {
                if (resultReader != null) {
                    resultReader.close();
                }
                if (p != null) {
                    p.destroy();
                }
            } catch (IOException e) {
                System.err.println(e.toString());
            }
        }
    }
}
