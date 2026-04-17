/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.content.pm.PackageManager;
import android.content.pm.SignedPackage;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;

import com.android.internal.R;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.internal.util.HexDump;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** @hide */
public class ParsedAllowComponentAccessPolicyUtils {

    private static final String TAG_PACKAGE = "package";

    /** Parses the allow-component-access tag from the manifest. */
    public static ParseResult<ParsingPackage> parseAllowComponentAccessPolicy(
            ParseInput input, ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {

        final List<SignedPackage> allowedPackages = new ArrayList<>();
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

            final String tagName = parser.getName();
            if (TAG_PACKAGE.equals(tagName)) {
                final TypedArray sa =
                        res.obtainAttributes(
                                parser, R.styleable.AndroidManifestAllowComponentAccessPackage);
                try {
                    String packageName =
                            sa.getNonConfigurationString(
                                    R.styleable.AndroidManifestAllowComponentAccessPackage_name, 0);

                    if (TextUtils.isEmpty(packageName)) {
                        return input.error(
                                "Package name is missing from package tag in"
                                        + " allow-component-access.");
                    }

                    String certDigestStr =
                            sa.getNonConfigurationString(
                                    R.styleable
                                            .AndroidManifestAllowComponentAccessPackage_certDigest,
                                    0);

                    byte[] certDigest = null;
                    if (certDigestStr != null) {
                        // We allow ":" delimiters in the SHA declaration as this is the format
                        // emitted by the certtool making it easy for developers to copy/paste.
                        certDigestStr = certDigestStr.replace(":", "").toLowerCase();
                        try {
                            certDigest = HexDump.hexStringToByteArray(certDigestStr);
                        } catch (IllegalArgumentException e) {
                            return input.error(
                                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                    "Invalid certificate digest in <package>: " + certDigestStr,
                                    e);
                        }
                    }

                    SignedPackage signedPackage = new SignedPackage(packageName, certDigest);
                    allowedPackages.add(signedPackage);
                    ParseResult<String[]> additionalCertsResult = ParsingPackageUtils
                            .parseAdditionalCertificates(input, pkg, res, parser);
                    if (additionalCertsResult.isError()) {
                        return input.error(additionalCertsResult);
                    }

                    String[] additionalCerts = additionalCertsResult.getResult();
                    for (String certStr : additionalCerts) {
                        try {
                            byte[] digest = HexDump.hexStringToByteArray(certStr);
                            allowedPackages.add(new SignedPackage(packageName, digest));
                        } catch (IllegalArgumentException e) {
                            return input.error(
                                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                    "Invalid additional certificate digest: " + certStr, e);
                        }
                    }
                } finally {
                    sa.recycle();
                }
            } else {
                final ParseResult result =
                        ParsingUtils.unknownTag("<allow-component-access>", pkg, parser, input);
                if (result.isError()) {
                    return input.error(result);
                }
            }
        }

        ParsedAllowComponentAccessPolicyImpl policy =
                new ParsedAllowComponentAccessPolicyImpl(allowedPackages);

        pkg.setParsedAllowComponentAccessPolicy(policy);
        return input.success(pkg);
    }
}
