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
package android.databinding.tool;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

import android.databinding.tool.processing.Scope;

import java.io.File;

/**
 * Task to pass environment info to javac
 */
public class DataBindingExportInfoTask extends DefaultTask {
    private LayoutXmlProcessor xmlProcessor;
    private File sdkDir;
    private File xmlOutFolder;
    private File exportClassListTo;
    private boolean printEncodedErrors;
    private boolean enableDebugLogs = false;
    @TaskAction
    public void exportInfo() {
        xmlProcessor.writeInfoClass(sdkDir, xmlOutFolder, exportClassListTo, enableDebugLogs,
                printEncodedErrors);
        Scope.assertNoError();
    }

    public LayoutXmlProcessor getXmlProcessor() {
        return xmlProcessor;
    }

    public void setXmlProcessor(LayoutXmlProcessor xmlProcessor) {
        this.xmlProcessor = xmlProcessor;
    }

    public File getSdkDir() {
        return sdkDir;
    }

    public void setSdkDir(File sdkDir) {
        this.sdkDir = sdkDir;
    }

    public File getXmlOutFolder() {
        return xmlOutFolder;
    }

    public void setXmlOutFolder(File xmlOutFolder) {
        this.xmlOutFolder = xmlOutFolder;
    }

    public File getExportClassListTo() {
        return exportClassListTo;
    }

    public void setExportClassListTo(File exportClassListTo) {
        this.exportClassListTo = exportClassListTo;
    }

    public boolean isPrintEncodedErrors() {
        return printEncodedErrors;
    }

    public void setPrintEncodedErrors(boolean printEncodedErrors) {
        this.printEncodedErrors = printEncodedErrors;
    }

    public boolean isEnableDebugLogs() {
        return enableDebugLogs;
    }

    public void setEnableDebugLogs(boolean enableDebugLogs) {
        this.enableDebugLogs = enableDebugLogs;
    }
}