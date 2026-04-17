/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import static android.content.pm.PermissionInfo.NO_TARGET_SDK_VERSION;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.permission.flags.Flags;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public class ComponentParseUtils {

    public static boolean isImplicitlyExposedIntent(ParsedIntentInfo intentInfo) {
        IntentFilter intentFilter = intentInfo.getIntentFilter();
        return intentFilter.hasCategory(Intent.CATEGORY_BROWSABLE)
                || intentFilter.hasAction(Intent.ACTION_SEND)
                || intentFilter.hasAction(Intent.ACTION_SENDTO)
                || intentFilter.hasAction(Intent.ACTION_SEND_MULTIPLE);
    }

    static <Component extends ParsedComponentImpl> ParseResult<Component> parseAllMetaData(
            ParsingPackage pkg, Resources res, XmlResourceParser parser, String tag,
            Component component, ParseInput input) throws XmlPullParserException, IOException {
        // Beginning in Android 17, permissions may specify valid usage purposes. Currently, valid
        // purposes are only processed for enforcement if the permission is defined within the
        // Android platform manifest. This limitation might be lifted in future versions.
        final boolean isParsedPermissionComponent =
                component instanceof ParsedPermissionImpl
                && "android".equals(pkg.getPackageName());
        final boolean shouldParseAllValidPurposes =
                Flags.ppdManifestEnabled()
                        && isParsedPermissionComponent;
        // TODO(b/443057927): rename to shouldParseValidPurposes
        final boolean shouldParseValidPurposes =
                (Flags.ppdPurposeEnabled() || Flags.ppdInstallTimeEnabled())
                        && isParsedPermissionComponent;
        final List<ParsedValidPurpose> validPurposes = new ArrayList<>();
        final List<ParsedValidGeneralPurpose> validGeneralPurposes = new ArrayList<>();
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            if (AconfigFlags.getInstance().skipCurrentElement(pkg, parser)) {
                XmlUtils.skipCurrentTag(parser);
                continue;
            }

            final ParseResult<?> result;
            if ("meta-data".equals(parser.getName())) {
                result = ParsedComponentUtils.addMetaData(component, pkg, res, parser, input);
            } else if (shouldParseValidPurposes && "valid-purpose".equals(parser.getName())) {
                final ParseResult<ParsedValidPurpose> validPurposeResult =
                        parseValidPurpose(res, parser, input);
                result = validPurposeResult;
                if (validPurposeResult.isSuccess()) {
                    validPurposes.add(validPurposeResult.getResult());
                }
            } else if (shouldParseAllValidPurposes
                    && "valid-general-purpose".equals(parser.getName())) {
                final ParseResult<ParsedValidGeneralPurpose> validGeneralPurposeResult =
                        parseValidGeneralPurpose(res, parser, input);
                result = validGeneralPurposeResult;
                if (validGeneralPurposeResult.isSuccess()) {
                    validGeneralPurposes.add(validGeneralPurposeResult.getResult());
                }
            } else {
                result = ParsingUtils.unknownTag(tag, pkg, parser, input);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        if (shouldParseValidPurposes) {
            final ParsedPermissionImpl permission = (ParsedPermissionImpl) component;
            if (permission.getRequiresPurposeTargetSdkVersion() != NO_TARGET_SDK_VERSION
                    && validPurposes.isEmpty()) {
                return input.error(
                        "<permission> requires purpose but no valid purpose defined!");
            } else {
                permission.setValidPurposes(validPurposes);
            }
        }

        if (shouldParseAllValidPurposes) {
            final ParsedPermissionImpl permission = (ParsedPermissionImpl) component;
            if (permission.getRequiresGeneralPurposeTargetSdkVersion() != NO_TARGET_SDK_VERSION
                    && validGeneralPurposes.isEmpty()) {
                return input.error(
                        "<permission> requires general purpose but no valid general purpose"
                                + " defined");
            } else {
                permission.setValidGeneralPurposes(validGeneralPurposes);
            }
        }

        return input.success(component);
    }

    private static ParseResult<ParsedValidPurpose> parseValidPurpose(
            Resources res, XmlResourceParser parser, ParseInput input) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestValidPurpose);
        try {
            final String name = sa.getString(R.styleable.AndroidManifestValidPurpose_name);
            if (TextUtils.isEmpty(name)) {
                return input.error(
                        "The android:name attribute for <valid-purpose> cannot be null or empty!");
            }
            final int maxTargetSdkVersion =
                    ParsingPackageUtils.parseMinOrMaxSdkVersion(
                            sa,
                            R.styleable.AndroidManifestValidPurpose_maxTargetSdkVersion,
                            /* defaultValue= */ Integer.MAX_VALUE);
            return input.success(new ParsedValidPurposeImpl(name, maxTargetSdkVersion));
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsedValidGeneralPurpose> parseValidGeneralPurpose(
            Resources res, XmlResourceParser parser, ParseInput input) {
        TypedArray sa =
                res.obtainAttributes(parser, R.styleable.AndroidManifestValidGeneralPurpose);
        try {
            final String name = sa.getString(R.styleable.AndroidManifestValidGeneralPurpose_name);
            if (TextUtils.isEmpty(name)) {
                return input.error(
                        "The android:name attribute for <valid-general-purpose> cannot be null or empty!");
            }
            final int maxTargetSdkVersion =
                    ParsingPackageUtils.parseMinOrMaxSdkVersion(
                            sa,
                            R.styleable.AndroidManifestValidGeneralPurpose_maxTargetSdkVersion,
                            /* defaultValue= */ Integer.MAX_VALUE);
            return input.success(new ParsedValidGeneralPurposeImpl(name, maxTargetSdkVersion));
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    public static ParseResult<String> buildProcessName(@NonNull String pkg, String defProc,
            CharSequence procSeq, int flags, String[] separateProcesses, ParseInput input) {
        if ((flags & ParsingPackageUtils.PARSE_IGNORE_PROCESSES) != 0 && !"system".contentEquals(
                procSeq)) {
            return input.success(defProc != null ? defProc : pkg);
        }
        if (separateProcesses != null) {
            for (int i = separateProcesses.length - 1; i >= 0; i--) {
                String sp = separateProcesses[i];
                if (sp.equals(pkg) || sp.equals(defProc) || sp.contentEquals(procSeq)) {
                    return input.success(pkg);
                }
            }
        }
        if (procSeq == null || procSeq.length() <= 0) {
            return input.success(defProc);
        }

        ParseResult<String> nameResult = ComponentParseUtils.buildCompoundName(pkg, procSeq,
                "process", input);
        return input.success(TextUtils.safeIntern(nameResult.getResult()));
    }

    @NonNull
    public static ParseResult<String> buildTaskAffinityName(String pkg, String defProc,
            CharSequence procSeq, ParseInput input) {
        if (procSeq == null) {
            return input.success(defProc);
        }
        if (procSeq.length() <= 0) {
            return input.success(null);
        }
        return buildCompoundName(pkg, procSeq, "taskAffinity", input);
    }

    public static ParseResult<String> buildCompoundName(String pkg, CharSequence procSeq,
            String type, ParseInput input) {
        String proc = procSeq.toString();
        char c = proc.charAt(0);
        if (pkg != null && c == ':') {
            if (proc.length() < 2) {
                return input.error("Bad " + type + " name " + proc + " in package " + pkg
                        + ": must be at least two characters");
            }
            String subName = proc.substring(1);
            final ParseResult<?> nameResult = FrameworkParsingPackageUtils.validateName(input,
                    subName, false, false);
            if (nameResult.isError()) {
                return input.error("Invalid " + type + " name " + proc + " in package " + pkg
                        + ": " + nameResult.getErrorMessage());
            }
            return input.success(pkg + proc);
        }
        if (!"system".equals(proc)) {
            final ParseResult<?> nameResult = FrameworkParsingPackageUtils.validateName(input, proc,
                    true, false);
            if (nameResult.isError()) {
                return input.error("Invalid " + type + " name " + proc + " in package " + pkg
                        + ": " + nameResult.getErrorMessage());
            }
        }
        return input.success(proc);
    }

    public static int flag(int flag, @AttrRes int attribute, TypedArray typedArray) {
        return typedArray.getBoolean(attribute, false) ? flag : 0;
    }

    public static int flag(int flag, @AttrRes int attribute, boolean defaultValue,
            TypedArray typedArray) {
        return typedArray.getBoolean(attribute, defaultValue) ? flag : 0;
    }

    /**
     * This is not state aware. Avoid and access through PackageInfoUtils in the system server.
     */
    @Nullable
    public static CharSequence getNonLocalizedLabel(
            ParsedComponent component) {
        return component.getNonLocalizedLabel();
    }

    /**
     * This is not state aware. Avoid and access through PackageInfoUtils in the system server.
     * <p>
     * This is a method of the utility class to discourage use.
     */
    public static int getIcon(ParsedComponent component) {
        return component.getIcon();
    }
}
