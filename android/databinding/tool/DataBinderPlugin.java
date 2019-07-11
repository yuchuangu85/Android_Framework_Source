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

import com.google.common.base.Preconditions;

import com.android.build.gradle.AppExtension;
import com.android.build.gradle.BaseExtension;
import com.android.build.gradle.LibraryExtension;
import com.android.build.gradle.api.ApplicationVariant;
import com.android.build.gradle.api.LibraryVariant;
import com.android.build.gradle.api.TestVariant;
import com.android.build.gradle.internal.api.ApplicationVariantImpl;
import com.android.build.gradle.internal.api.LibraryVariantImpl;
import com.android.build.gradle.internal.api.TestVariantImpl;
import com.android.build.gradle.internal.core.GradleVariantConfiguration;
import com.android.build.gradle.internal.variant.ApplicationVariantData;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.build.gradle.internal.variant.LibraryVariantData;
import com.android.build.gradle.internal.variant.TestVariantData;
import com.android.build.gradle.tasks.ProcessAndroidResources;
import com.android.builder.model.ApiVersion;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.AbstractCompile;

import android.databinding.tool.processing.ScopedException;
import android.databinding.tool.util.L;
import android.databinding.tool.writer.JavaFileWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import javax.tools.Diagnostic;
import javax.xml.bind.JAXBException;

public class DataBinderPlugin implements Plugin<Project> {

    private static final String INVOKED_FROM_IDE_PROPERTY = "android.injected.invoked.from.ide";
    private static final String PRINT_ENCODED_ERRORS_PROPERTY
            = "android.databinding.injected.print.encoded.errors";
    private Logger logger;
    private boolean printEncodedErrors = false;

    class GradleFileWriter extends JavaFileWriter {

        private final String outputBase;

        public GradleFileWriter(String outputBase) {
            this.outputBase = outputBase;
        }

        @Override
        public void writeToFile(String canonicalName, String contents) {
            String asPath = canonicalName.replace('.', '/');
            File f = new File(outputBase + "/" + asPath + ".java");
            logD("Asked to write to " + canonicalName + ". outputting to:" +
                    f.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                IOUtils.write(contents, fos);
            } catch (IOException e) {
                logE(e, "cannot write file " + f.getAbsolutePath());
            } finally {
                IOUtils.closeQuietly(fos);
            }
        }
    }

    private boolean safeGetBooleanProperty(Project project, String property) {
        boolean hasProperty = project.hasProperty(property);
        if (!hasProperty) {
            return false;
        }
        try {
            if (Boolean.parseBoolean(String.valueOf(project.getProperties().get(property)))) {
                return true;
            }
        } catch (Throwable t) {
            L.w("unable to read property %s", project);
        }
        return false;
    }

    private boolean resolvePrintEncodedErrors(Project project) {
        return safeGetBooleanProperty(project, INVOKED_FROM_IDE_PROPERTY) ||
                safeGetBooleanProperty(project, PRINT_ENCODED_ERRORS_PROPERTY);
    }

    @Override
    public void apply(Project project) {
        if (project == null) {
            return;
        }
        setupLogger(project);

        String myVersion = readMyVersion();
        logD("data binding plugin version is %s", myVersion);
        if (StringUtils.isEmpty(myVersion)) {
            throw new IllegalStateException("cannot read version of the plugin :/");
        }
        printEncodedErrors = resolvePrintEncodedErrors(project);
        ScopedException.encodeOutput(printEncodedErrors);
        project.getDependencies().add("compile", "com.android.databinding:library:" + myVersion);
        boolean addAdapters = true;
        if (project.hasProperty("ext")) {
            Object ext = project.getProperties().get("ext");
            if (ext instanceof ExtraPropertiesExtension) {
                ExtraPropertiesExtension propExt = (ExtraPropertiesExtension) ext;
                if (propExt.has("addDataBindingAdapters")) {
                    addAdapters = Boolean.valueOf(
                            String.valueOf(propExt.get("addDataBindingAdapters")));
                }
            }
        }
        if (addAdapters) {
            project.getDependencies()
                    .add("compile", "com.android.databinding:adapters:" + myVersion);
        }
        project.getDependencies().add("provided", "com.android.databinding:compiler:" + myVersion);
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                try {
                    createXmlProcessor(project);
                } catch (Throwable t) {
                    logE(t, "failed to setup data binding");
                }
            }
        });
    }

    private void setupLogger(Project project) {
        logger = project.getLogger();
        L.setClient(new L.Client() {
            @Override
            public void printMessage(Diagnostic.Kind kind, String message) {
                if (kind == Diagnostic.Kind.ERROR) {
                    logE(null, message);
                } else {
                    logD(message);
                }
            }
        });
    }

    String readMyVersion() {
        try {
            InputStream stream = getClass().getResourceAsStream("/data_binding_build_info");
            try {
                return IOUtils.toString(stream, "utf-8").trim();
            } finally {
                IOUtils.closeQuietly(stream);
            }
        } catch (IOException exception) {
            logE(exception, "Cannot read data binding version");
        }
        return null;
    }

    private void createXmlProcessor(Project project)
            throws NoSuchFieldException, IllegalAccessException {
        L.d("creating xml processor for " + project);
        Object androidExt = project.getExtensions().getByName("android");
        if (!(androidExt instanceof BaseExtension)) {
            return;
        }
        if (androidExt instanceof AppExtension) {
            createXmlProcessorForApp(project, (AppExtension) androidExt);
        } else if (androidExt instanceof LibraryExtension) {
            createXmlProcessorForLibrary(project, (LibraryExtension) androidExt);
        } else {
            logE(new UnsupportedOperationException("cannot understand android ext"),
                    "unsupported android extension. What is it? %s", androidExt);
        }
    }

    private void createXmlProcessorForLibrary(Project project, LibraryExtension lib)
            throws NoSuchFieldException, IllegalAccessException {
        File sdkDir = lib.getSdkDirectory();
        L.d("create xml processor for " + lib);
        for (TestVariant variant : lib.getTestVariants()) {
            logD("test variant %s. dir name %s", variant, variant.getDirName());
            BaseVariantData variantData = getVariantData(variant);
            attachXmlProcessor(project, variantData, sdkDir, false);//tests extend apk variant
        }
        for (LibraryVariant variant : lib.getLibraryVariants()) {
            logD("library variant %s. dir name %s", variant, variant.getDirName());
            BaseVariantData variantData = getVariantData(variant);
            attachXmlProcessor(project, variantData, sdkDir, true);
        }
    }

    private void createXmlProcessorForApp(Project project, AppExtension appExt)
            throws NoSuchFieldException, IllegalAccessException {
        L.d("create xml processor for " + appExt);
        File sdkDir = appExt.getSdkDirectory();
        for (TestVariant testVariant : appExt.getTestVariants()) {
            TestVariantData variantData = getVariantData(testVariant);
            attachXmlProcessor(project, variantData, sdkDir, false);
        }
        for (ApplicationVariant appVariant : appExt.getApplicationVariants()) {
            ApplicationVariantData variantData = getVariantData(appVariant);
            attachXmlProcessor(project, variantData, sdkDir, false);
        }
    }

    private LibraryVariantData getVariantData(LibraryVariant variant)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = LibraryVariantImpl.class.getDeclaredField("variantData");
        field.setAccessible(true);
        return (LibraryVariantData) field.get(variant);
    }

    private TestVariantData getVariantData(TestVariant variant)
            throws IllegalAccessException, NoSuchFieldException {
        Field field = TestVariantImpl.class.getDeclaredField("variantData");
        field.setAccessible(true);
        return (TestVariantData) field.get(variant);
    }

    private ApplicationVariantData getVariantData(ApplicationVariant variant)
            throws IllegalAccessException, NoSuchFieldException {
        Field field = ApplicationVariantImpl.class.getDeclaredField("variantData");
        field.setAccessible(true);
        return (ApplicationVariantData) field.get(variant);
    }

    private void attachXmlProcessor(Project project, final BaseVariantData variantData,
            final File sdkDir,
            final Boolean isLibrary) {
        final GradleVariantConfiguration configuration = variantData.getVariantConfiguration();
        final ApiVersion minSdkVersion = configuration.getMinSdkVersion();
        ProcessAndroidResources generateRTask = variantData.generateRClassTask;
        final String packageName = generateRTask.getPackageForR();
        String fullName = configuration.getFullName();
        List<File> resourceFolders = Arrays.asList(variantData.mergeResourcesTask.getOutputDir());

        final File codeGenTargetFolder = new File(project.getBuildDir() + "/data-binding-info/" +
                configuration.getDirName());
        String writerOutBase = codeGenTargetFolder.getAbsolutePath();
        JavaFileWriter fileWriter = new GradleFileWriter(writerOutBase);
        final LayoutXmlProcessor xmlProcessor = new LayoutXmlProcessor(packageName, resourceFolders,
                fileWriter, minSdkVersion.getApiLevel(), isLibrary);
        final ProcessAndroidResources processResTask = generateRTask;
        final File xmlOutDir = new File(project.getBuildDir() + "/layout-info/" +
                configuration.getDirName());
        final File generatedClassListOut = isLibrary ? new File(xmlOutDir, "_generated.txt") : null;
        logD("xml output for %s is %s", variantData, xmlOutDir);
        String layoutTaskName = "dataBindingLayouts" + StringUtils
                .capitalize(processResTask.getName());
        String infoClassTaskName = "dataBindingInfoClass" + StringUtils
                .capitalize(processResTask.getName());

        final DataBindingProcessLayoutsTask[] processLayoutsTasks
                = new DataBindingProcessLayoutsTask[1];
        project.getTasks().create(layoutTaskName,
                DataBindingProcessLayoutsTask.class,
                new Action<DataBindingProcessLayoutsTask>() {
                    @Override
                    public void execute(final DataBindingProcessLayoutsTask task) {
                        processLayoutsTasks[0] = task;
                        task.setXmlProcessor(xmlProcessor);
                        task.setSdkDir(sdkDir);
                        task.setXmlOutFolder(xmlOutDir);
                        task.setMinSdk(minSdkVersion.getApiLevel());

                        logD("TASK adding dependency on %s for %s", task, processResTask);
                        processResTask.dependsOn(task);
                        processResTask.getInputs().dir(xmlOutDir);
                        for (Object dep : processResTask.getDependsOn()) {
                            if (dep == task) {
                                continue;
                            }
                            logD("adding dependency on %s for %s", dep, task);
                            task.dependsOn(dep);
                        }
                        processResTask.doLast(new Action<Task>() {
                            @Override
                            public void execute(Task unused) {
                                try {
                                    task.writeLayoutXmls();
                                } catch (JAXBException e) {
                                    // gradle sometimes fails to resolve JAXBException.
                                    // We get stack trace manually to ensure we have the log
                                    logE(e, "cannot write layout xmls %s",
                                            ExceptionUtils.getStackTrace(e));
                                }
                            }
                        });
                    }
                });
        final DataBindingProcessLayoutsTask processLayoutsTask = processLayoutsTasks[0];
        project.getTasks().create(infoClassTaskName,
                DataBindingExportInfoTask.class,
                new Action<DataBindingExportInfoTask>() {

                    @Override
                    public void execute(DataBindingExportInfoTask task) {
                        task.dependsOn(processLayoutsTask);
                        task.dependsOn(processResTask);
                        task.setXmlProcessor(xmlProcessor);
                        task.setSdkDir(sdkDir);
                        task.setXmlOutFolder(xmlOutDir);
                        task.setExportClassListTo(generatedClassListOut);
                        task.setPrintEncodedErrors(printEncodedErrors);
                        task.setEnableDebugLogs(logger.isEnabled(LogLevel.DEBUG));

                        variantData.registerJavaGeneratingTask(task, codeGenTargetFolder);
                    }
                });
        String packageJarTaskName = "package" + StringUtils.capitalize(fullName) + "Jar";
        final Task packageTask = project.getTasks().findByName(packageJarTaskName);
        if (packageTask instanceof Jar) {
            String removeGeneratedTaskName = "dataBindingExcludeGeneratedFrom" +
                    StringUtils.capitalize(packageTask.getName());
            if (project.getTasks().findByName(removeGeneratedTaskName) == null) {
                final AbstractCompile javaCompileTask = variantData.javacTask;
                Preconditions.checkNotNull(javaCompileTask);

                project.getTasks().create(removeGeneratedTaskName,
                        DataBindingExcludeGeneratedTask.class,
                        new Action<DataBindingExcludeGeneratedTask>() {
                            @Override
                            public void execute(DataBindingExcludeGeneratedTask task) {
                                packageTask.dependsOn(task);
                                task.dependsOn(javaCompileTask);
                                task.setAppPackage(packageName);
                                task.setInfoClassQualifiedName(xmlProcessor.getInfoClassFullName());
                                task.setPackageTask((Jar) packageTask);
                                task.setLibrary(isLibrary);
                                task.setGeneratedClassListFile(generatedClassListOut);
                            }
                        });
            }
        }
    }

    private void logD(String s, Object... args) {
        logger.info(formatLog(s, args));
    }

    private void logE(Throwable t, String s, Object... args) {
        logger.error(formatLog(s, args), t);
    }

    private String formatLog(String s, Object... args) {
        return "[data binding plugin]: " + String.format(s, args);
    }
}
