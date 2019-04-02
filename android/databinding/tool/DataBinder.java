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

import android.databinding.tool.processing.Scope;
import android.databinding.tool.processing.ScopedException;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.util.L;
import android.databinding.tool.util.StringUtils;
import android.databinding.tool.writer.CallbackWrapperWriter;
import android.databinding.tool.writer.ComponentWriter;
import android.databinding.tool.writer.JavaFileWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The main class that handles parsing files and generating classes.
 */
public class DataBinder {
    List<LayoutBinder> mLayoutBinders = new ArrayList<LayoutBinder>();
    private static final String COMPONENT_CLASS = "android.databinding.DataBindingComponent";

    private JavaFileWriter mFileWriter;

    Set<String> mWrittenClasses = new HashSet<String>();

    public DataBinder(ResourceBundle resourceBundle) {
        L.d("reading resource bundle into data binder");
        for (Map.Entry<String, List<ResourceBundle.LayoutFileBundle>> entry :
                resourceBundle.getLayoutBundles().entrySet()) {
            for (ResourceBundle.LayoutFileBundle bundle : entry.getValue()) {
                try {
                    mLayoutBinders.add(new LayoutBinder(bundle));
                } catch (ScopedException ex) {
                    Scope.defer(ex);
                }
            }
        }
    }
    public List<LayoutBinder> getLayoutBinders() {
        return mLayoutBinders;
    }

    public void sealModels() {
        for (LayoutBinder layoutBinder : mLayoutBinders) {
            layoutBinder.sealModel();
        }
    }

    public void writerBaseClasses(boolean isLibrary) {
        for (LayoutBinder layoutBinder : mLayoutBinders) {
            try {
                Scope.enter(layoutBinder);
                if (isLibrary || layoutBinder.hasVariations()) {
                    String className = layoutBinder.getClassName();
                    String canonicalName = layoutBinder.getPackage() + "." + className;
                    if (mWrittenClasses.contains(canonicalName)) {
                        continue;
                    }
                    L.d("writing data binder base %s", canonicalName);
                    mFileWriter.writeToFile(canonicalName,
                            layoutBinder.writeViewBinderBaseClass(isLibrary));
                    mWrittenClasses.add(canonicalName);
                }
            } catch (ScopedException ex){
                Scope.defer(ex);
            } finally {
                Scope.exit();
            }
        }
    }

    public void writeBinders(int minSdk) {
        writeCallbackWrappers(minSdk);
        for (LayoutBinder layoutBinder : mLayoutBinders) {
            try {
                Scope.enter(layoutBinder);
                String className = layoutBinder.getImplementationName();
                String canonicalName = layoutBinder.getPackage() + "." + className;
                L.d("writing data binder %s", canonicalName);
                mWrittenClasses.add(canonicalName);
                mFileWriter.writeToFile(canonicalName, layoutBinder.writeViewBinder(minSdk));
            } catch (ScopedException ex) {
                Scope.defer(ex);
            } finally {
                Scope.exit();
            }
        }
    }

    private void writeCallbackWrappers(int minSdk) {
        Map<String, CallbackWrapper> uniqueWrappers = new HashMap<String, CallbackWrapper>();
        Set<String> classNames = new HashSet<String>();
        int callbackCounter = 0;
        for (LayoutBinder binder : mLayoutBinders) {
            for (Map.Entry<String, CallbackWrapper> entry : binder.getModel().getCallbackWrappers()
                    .entrySet()) {
                final CallbackWrapper existing = uniqueWrappers.get(entry.getKey());
                if (existing == null) {
                    // first time seeing this. register
                    final CallbackWrapper wrapper = entry.getValue();
                    uniqueWrappers.put(entry.getKey(), wrapper);
                    String listenerName = makeUnique(classNames, wrapper.klass.getSimpleName());
                    String methodName = makeUnique(classNames,
                            "_internalCallback" + StringUtils.capitalize(wrapper.method.getName()));
                    wrapper.prepare(listenerName, methodName);
                } else {
                    // fill from previous
                    entry.getValue()
                            .prepare(existing.getClassName(), existing.getListenerMethodName());
                }

            }
        }

        // now write the original wrappers
        for (CallbackWrapper wrapper : uniqueWrappers.values()) {
            final String code = new CallbackWrapperWriter(wrapper).write();
            String className = wrapper.getClassName();
            String canonicalName = wrapper.getPackage() + "." + className;
            mFileWriter.writeToFile(canonicalName, code);
            // these will be deleted for library projects.
            mWrittenClasses.add(canonicalName);
        }

    }

    private String makeUnique(Set<String> existing, String wanted) {
        int cnt = 1;
        while (existing.contains(wanted)) {
            wanted = wanted + cnt;
            cnt++;
        }
        existing.add(wanted);
        return wanted;
    }

    public void writeComponent() {
        ComponentWriter componentWriter = new ComponentWriter();

        mWrittenClasses.add(COMPONENT_CLASS);
        mFileWriter.writeToFile(COMPONENT_CLASS, componentWriter.createComponent());
    }

    public Set<String> getWrittenClassNames() {
        return mWrittenClasses;
    }

    public void setFileWriter(JavaFileWriter fileWriter) {
        mFileWriter = fileWriter;
    }

    public JavaFileWriter getFileWriter() {
        return mFileWriter;
    }
}
