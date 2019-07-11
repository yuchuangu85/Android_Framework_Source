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
package android.databinding.tool.writer;

import android.databinding.tool.Binding;
import android.databinding.tool.BindingTarget;
import android.databinding.tool.LayoutBinder;
import android.databinding.tool.processing.ScopedException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

public class ComponentWriter {
    private static final String INDENT = "    ";
    private final HashMap<String, ArrayList<String>> mBindingAdapters = new HashMap<>();

    public ComponentWriter(List<LayoutBinder> binders) {
        HashMap<Binding, ArrayList<String>> bindings = new HashMap<>();

        for (LayoutBinder layoutBinder : binders) {
            try {
                android.databinding.tool.processing.Scope.enter(layoutBinder);
                for (BindingTarget target : layoutBinder.getBindingTargets()) {
                    try {
                        android.databinding.tool.processing.Scope.enter(target);
                        for (Binding binding : target.getBindings()) {
                            try {
                                android.databinding.tool.processing.Scope.enter(binding);
                                final String bindingAdapter = binding
                                        .getBindingAdapterInstanceClass();
                                if (bindingAdapter != null) {
                                    final String simpleName = simpleName(bindingAdapter);
                                    ArrayList<String> classes = mBindingAdapters.get(simpleName);
                                    if (classes == null) {
                                        classes = new ArrayList<>();
                                        mBindingAdapters.put(simpleName, classes);
                                        classes.add(bindingAdapter);
                                    } else if (!classes.contains(bindingAdapter)) {
                                        classes.add(bindingAdapter);
                                    }
                                    bindings.put(binding, classes);
                                }
                            } catch (ScopedException ex) {
                                android.databinding.tool.processing.Scope.defer(ex);
                            } finally{
                                android.databinding.tool.processing.Scope.exit();
                            }
                        }
                    } finally {
                        android.databinding.tool.processing.Scope.exit();
                    }
                }
            } finally {
                android.databinding.tool.processing.Scope.exit();
            }
        }

        for (Entry<Binding, ArrayList<String>> entry : bindings.entrySet()) {
            final Binding binding = entry.getKey();
            final ArrayList<String> classes = entry.getValue();
            final String call;
            if (classes.size() == 1) {
                call = "get" + simpleName(classes.get(0)) + "()";
            } else {
                int index = classes.indexOf(binding.getBindingAdapterInstanceClass());
                call = "get" + simpleName(classes.get(index)) + (index + 1) + "()";
            }
            binding.setBindingAdapterCall(call);
        }
    }

    public String createComponent() {
        final StringBuilder builder = new StringBuilder();
        builder.append("package android.databinding;\n\n");
        builder.append("public interface DataBindingComponent {\n");
        for (final String simpleName : mBindingAdapters.keySet()) {
            final ArrayList<String> classes = mBindingAdapters.get(simpleName);
            if (classes.size() > 1) {
                int index = 1;
                for (String className : classes) {
                    addGetter(builder, simpleName, className, index++);
                }
            } else {
                addGetter(builder, simpleName, classes.iterator().next(), 0);
            }
        }
        builder.append("}\n");
        return builder.toString();
    }

    private static String simpleName(String className) {
        int dotIndex = className.lastIndexOf('.');
        if (dotIndex < 0) {
            return className;
        } else {
            return className.substring(dotIndex + 1);
        }
    }

    private static void addGetter(StringBuilder builder, String simpleName, String className,
            int index) {
        builder.append(INDENT)
                .append(className)
                .append(" get")
                .append(simpleName);
        if (index > 0) {
            builder.append(index);
        }
        builder.append("();\n");
    }
}
