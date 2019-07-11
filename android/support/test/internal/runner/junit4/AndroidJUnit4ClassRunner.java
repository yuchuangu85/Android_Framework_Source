/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.support.test.internal.runner.junit4;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.support.test.InjectBundle;
import android.support.test.InjectContext;
import android.support.test.InjectInstrumentation;
import android.util.Log;


import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkField;
import org.junit.runners.model.InitializationError;

import java.lang.reflect.Field;
import java.util.List;

/**
 * A specialized {@link BlockJUnit4ClassRunner} that can handle {@link InjectContext} and
 * {@link InjectInstrumentation}.
 */
class AndroidJUnit4ClassRunner extends BlockJUnit4ClassRunner {

    private static final String LOG_TAG = "AndroidJUnit4ClassRunner";
    private final Instrumentation mInstr;
    private final Bundle mBundle;

    @SuppressWarnings("serial")
    private static class InvalidInjectException extends Exception {
        InvalidInjectException(String message) {
            super(message);
        }
    }

    public AndroidJUnit4ClassRunner(Class<?> klass, Instrumentation instr, Bundle bundle)
            throws InitializationError {
        super(klass);
        mInstr = instr;
        mBundle = bundle;
    }

    @Override
    protected Object createTest() throws Exception {
        Object test = super.createTest();
        inject(test);
        return test;
    }

    @Override
    protected void collectInitializationErrors(List<Throwable> errors) {
        super.collectInitializationErrors(errors);

        validateInjectFields(errors);
    }

    private void validateInjectFields(List<Throwable> errors) {
        List<FrameworkField> instrFields = getTestClass().getAnnotatedFields(
                InjectInstrumentation.class);
        for (FrameworkField instrField : instrFields) {
            validateInjectField(errors, instrField, Instrumentation.class);
        }
        List<FrameworkField> contextFields = getTestClass().getAnnotatedFields(
                InjectContext.class);
        for (FrameworkField contextField : contextFields) {
            validateInjectField(errors, contextField, Context.class);
        }
        List<FrameworkField> bundleFields = getTestClass().getAnnotatedFields(
                InjectBundle.class);
        for (FrameworkField bundleField : bundleFields) {
            validateInjectField(errors, bundleField, Bundle.class);
        }
    }

    private void validateInjectField(List<Throwable> errors, FrameworkField instrField,
            Class<?> expectedType) {
        if (!instrField.isPublic()) {
            errors.add(new InvalidInjectException(String.format(
                    "field %s in class %s has an InjectInstrumentation annotation," +
                    " but is not public", instrField.getName(), getTestClass().getName())));
        }
        if (!expectedType.isAssignableFrom(instrField.getType())) {
            errors.add(new InvalidInjectException(String.format(
                    "field %s in class %s has an InjectInstrumentation annotation," +
                    " but its not of %s type", instrField.getName(),
                    getTestClass().getName(), expectedType.getName())));
        }
    }

    private void inject(Object test) {
        List<FrameworkField> instrFields = getTestClass().getAnnotatedFields(
                InjectInstrumentation.class);
        for (FrameworkField instrField : instrFields) {
            setFieldValue(test, instrField.getField(), mInstr);
        }
        List<FrameworkField> contextFields = getTestClass().getAnnotatedFields(
                InjectContext.class);
        for (FrameworkField contextField : contextFields) {
            setFieldValue(test, contextField.getField(), mInstr.getTargetContext());
        }
        List<FrameworkField> bundleFields = getTestClass().getAnnotatedFields(
                InjectBundle.class);
        for (FrameworkField bundleField : bundleFields) {
            setFieldValue(test, bundleField.getField(), mBundle);
        }
    }

    private void setFieldValue(Object test, Field field, Object value) {
        try {
            field.set(test, value);
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, String.format(
                    "Failed to inject value for field %s in class %s", field.getName(),
                    test.getClass().getName()), e);
        } catch (IllegalAccessException e) {
            Log.e(LOG_TAG, String.format(
                    "Failed to inject value for field %s in class %s", field.getName(),
                    test.getClass().getName()), e);
        }
    }
}
