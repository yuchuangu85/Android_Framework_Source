/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.text.TextUtils;

import com.android.server.wifi.hotspot2.Utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility class for matching domain names.
 */
public class DomainMatcher {
    public static final int MATCH_NONE = 0;
    public static final int MATCH_PRIMARY = 1;
    public static final int MATCH_SECONDARY = 2;

    /**
     * The root of the Label tree.
     */
    private final Label mRoot;

    /**
     * Label tree representation for the domain name.  Labels are delimited by "." in the domain
     * name.
     *
     * For example, the tree representation of "android.google.com" as a primary domain:
     * [com, None] -> [google, None] -> [android, Primary]
     *
     */
    private static class Label {
        private final Map<String, Label> mSubDomains;
        private int mMatch;

        Label(int match) {
            mMatch = match;
            mSubDomains = new HashMap<String, Label>();
        }

        /**
         * Add sub-domains to this label.
         *
         * @param labels The iterator of domain label strings
         * @param match The match status of the domain
         */
        public void addDomain(Iterator<String> labels, int match) {
            String labelName = labels.next();
            // Create the Label object if it doesn't exist yet.
            Label subLabel = mSubDomains.get(labelName);
            if (subLabel == null) {
                subLabel = new Label(MATCH_NONE);
                mSubDomains.put(labelName, subLabel);
            }

            if (labels.hasNext()) {
                // Adding sub-domain.
                subLabel.addDomain(labels, match);
            } else {
                // End of the domain, update the match status.
                subLabel.mMatch = match;
            }
        }

        /**
         * Return the Label for the give label string.
         * @param labelString The label string to look for
         * @return {@link Label}
         */
        public Label getSubLabel(String labelString) {
            return mSubDomains.get(labelString);
        }

        /**
         * Return the match status
         *
         * @return The match status
         */
        public int getMatch() {
            return mMatch;
        }

        private void toString(StringBuilder sb) {
            if (mSubDomains != null) {
                sb.append(".{");
                for (Map.Entry<String, Label> entry : mSubDomains.entrySet()) {
                    sb.append(entry.getKey());
                    entry.getValue().toString(sb);
                }
                sb.append('}');
            } else {
                sb.append('=').append(mMatch);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }
    }

    public DomainMatcher(String primaryDomain, List<String> secondaryDomains) {
        // Create the root label.
        mRoot = new Label(MATCH_NONE);

        // Add secondary domains.
        if (secondaryDomains != null) {
            for (String domain : secondaryDomains) {
                if (!TextUtils.isEmpty(domain)) {
                    List<String> secondaryLabel = Utils.splitDomain(domain);
                    mRoot.addDomain(secondaryLabel.iterator(), MATCH_SECONDARY);
                }
            }
        }

        // Add primary domain, primary overwrites secondary.
        if (!TextUtils.isEmpty(primaryDomain)) {
            List<String> primaryLabel = Utils.splitDomain(primaryDomain);
            mRoot.addDomain(primaryLabel.iterator(), MATCH_PRIMARY);
        }
    }

    /**
     * Check if domain is either the same or a sub-domain of any of the domains in the
     * domain tree in this matcher, i.e. all or a sub-set of the labels in domain matches
     * a path in the tree.
     *
     * This will have precedence for matching primary domain over secondary domain if both
     * are found.
     *
     * For example, with primary domain set to "test.google.com" and secondary domain set to
     * "google.com":
     * "test2.test.google.com" -> Match.Primary
     * "test1.google.com" -> Match.Secondary
     *
     * @param domainName Domain name to be checked.
     * @return The match status
     */
    public int isSubDomain(String domainName) {
        if (TextUtils.isEmpty(domainName)) {
            return MATCH_NONE;
        }
        List<String> domainLabels = Utils.splitDomain(domainName);

        Label label = mRoot;
        int match = MATCH_NONE;
        for (String labelString : domainLabels) {
            label = label.getSubLabel(labelString);
            if (label == null) {
                break;
            } else if (label.getMatch() != MATCH_NONE) {
                match = label.getMatch();
                if (match == MATCH_PRIMARY) {
                    break;
                }
            }
        }
        return match;
    }

    /**
     * Check if domain2 is a sub-domain of domain1.
     *
     * @param domain1 The string of the first domain
     * @param domain2 The string of the second domain
     * @return true if the second domain is the sub-domain of the first
     */
    public static boolean arg2SubdomainOfArg1(String domain1, String domain2) {
        if (TextUtils.isEmpty(domain1) || TextUtils.isEmpty(domain2)) {
            return false;
        }

        List<String> labels1 = Utils.splitDomain(domain1);
        List<String> labels2 = Utils.splitDomain(domain2);

        // domain2 must be the same or longer than domain1 in order to be a sub-domain.
        if (labels2.size() < labels1.size()) {
            return false;
        }

        Iterator<String> l1 = labels1.iterator();
        Iterator<String> l2 = labels2.iterator();

        while(l1.hasNext()) {
            if (!TextUtils.equals(l1.next(), l2.next())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Domain matcher " + mRoot;
    }
}
