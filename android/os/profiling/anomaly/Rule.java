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

package android.os.profiling.anomaly;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.profiling.anomaly.flags.Flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Objects;

/**
 * Defines a rule for detecting system anomalies.
 *
 * <p>Each rule consists of a condition and a set of actions to be taken if the condition is met.
 * These rules are set through {@link
 * android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules}.
 *
 * <p>Use the {@link Builder} to construct {@link Rule} instances.
 *
 * @hide
 */
@RequiresApi(37)
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class Rule extends RuleInternal {
    /**
     * Defines the types of actions to be executed by the {@code
     * com.android.os.profiling.anomaly.AnomalyDetectorService} when an anomaly is detected based on
     * the {@link Rule}.
     *
     * @hide
     */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ACTION_TYPE_LOG,
    })
    // TODO(b/416804300): Add default and other action once finalized.
    public @interface AnomalyActionType {}

    /**
     * Defines the possible types of conditions a {@link Rule} can represent.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        CONDITION_TYPE_BINDER_SPAM,
    })
    public @interface ConditionType {}

    /**
     * Action to write a detailed report of the anomaly to the system log.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final int ACTION_TYPE_LOG = RuleInternal.ACTION_TYPE_LOG;

    /**
     * Condition type for monitoring excessive Binder Inter-Process Calls (IPCs), also known as
     * 'Binder Spam'.
     *
     * <p>Binder Spam is characterized by an application making an unusually high number of IPCs to
     * a specific Binder interface and method within a defined time window.
     *
     * <p>When using this condition type, the associated {@link #getRuleCondition()} bundle must
     * only contain the following keys {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME},
     * {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT}, and {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String CONDITION_TYPE_BINDER_SPAM = RuleInternal.CONDITION_TYPE_BINDER_SPAM;

    /**
     * {@link Bundle} key for the fully qualified name of the AIDL interface to monitor.
     *
     * <p>Example: {@code "android.app.IActivityManager"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME =
            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME;

    /**
     * {@link Bundle} key for the name of the method within the AIDL interface to monitor.
     *
     * <p>Example: {@code "startService"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME =
            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME;

    /**
     * {@link Bundle} key for the maximum number of allowed calls to the specified interface and
     * method within the defined interval. This value is an integer.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT =
            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT;

    /**
     * {@link Bundle} key for the duration of the sliding time window in milliseconds used to count
     * Binder calls. This value is a long.
     *
     * <p>Exceeding the {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT} within this interval
     * triggers the associated anomaly actions.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS =
            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS;

    // Private constructor used by the Builder.
    private Rule(Builder builder) {
        super(builder);
    }

    /**
     * Returns the name of the rule.
     *
     * @return The name of the rule.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @Override
    public String getName() {
        return super.getName();
    }

    /**
     * Returns the list of actions to be executed by the anomaly detection service when the {@code
     * mRuleCondition} defined by this rule is met.
     *
     * <p>Each integer in the list corresponds to a constant defined in {@link AnomalyActionType},
     * representing a specific action.
     *
     * @return A non-null list of {@link AnomalyActionType} integers.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @Override
    public List<@AnomalyActionType Integer> getAnomalyActions() {
        return super.getAnomalyActions();
    }

    /**
     * Returns a string indicating the type of condition this rule monitors.
     *
     * <p>The condition type determines how the parameters in the {@link #getRuleCondition()} Bundle
     * are interpreted and what system behavior is being observed.
     *
     * @return One of the string constants defined in {@link ConditionType}, for example, {@link
     *     #CONDITION_TYPE_BINDER_SPAM}.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @Override
    public @ConditionType String getConditionType() {
        return super.getConditionType();
    }

    /**
     * Returns the {@link Bundle} containing the specific parameters that define the condition for
     * this rule.
     *
     * <p>The expected keys and value types within this Bundle are strictly dependent on the {@link
     * ConditionType} returned by {@link #getConditionType()}. For instance, if the type is {@link
     * #CONDITION_TYPE_BINDER_SPAM}, the Bundle should contain the following keys: {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT}, and {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS}.
     *
     * @return A non-null {@link Bundle} instance. The Bundle may be empty if the condition type
     *     requires no parameters.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @Override
    public Bundle getRuleCondition() {
        return super.getRuleCondition();
    }

    /**
     * Builder class for creating {@link Rule} instances.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final class Builder extends RuleInternal.Builder {
        /**
         * Sets the name of the rule.
         *
         * <p>Rule names are displayed in logs and system dumps for debugging purposes and are
         * intended only for human consumption.
         *
         * <p>The name must be a non-empty string. There are no other restrictions on the format. If
         * a name is not explicitly set using this method, it will default to an empty string.
         *
         * @param name The name of the rule.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        @Override
        public Builder setName(@NonNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            if (name.trim().isEmpty()) {
                throw new IllegalArgumentException("name cannot be empty or blank");
            }
            super.setName(name);
            return this;
        }

        /**
         * Adds a action to be taken when the rule's condition is met. Duplicate actions will be
         * ignored.
         *
         * @param anomalyAction An integer representing one of the constants defined in {@link
         *     AnomalyActionType}.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        @Override
        public Builder addAnomalyAction(@AnomalyActionType int anomalyAction) {
            super.addAnomalyAction(anomalyAction);
            return this;
        }

        /**
         * Sets the type of condition this rule monitors.
         *
         * @param conditionType One of the string constants defined in {@link ConditionType}.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        @Override
        public Builder setConditionType(@NonNull @ConditionType String conditionType) {
            super.setConditionType(conditionType);
            return this;
        }

        /**
         * Sets the {@link Bundle} containing the specific parameters for this rule's condition.
         *
         * <p>The keys and values expected in the Bundle depend on the {@link
         * #setConditionType(String) ConditionType}.
         *
         * @param ruleCondition A Bundle containing the condition parameters.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        @Override
        public Builder setRuleCondition(@NonNull Bundle ruleCondition) {
            super.setRuleCondition(ruleCondition);
            return this;
        }

        /**
         * Builds the {@link Rule} instance.
         *
         * @return The configured {@link Rule} object.
         * @throws IllegalStateException if required fields (ConditionType or RuleCondition) are not
         *     set.
         * @throws IllegalArgumentException if any values are invalid (e.g., unknown action type,
         *     unknown condition type, or invalid Bundle keys for the given condition type).
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        @Override
        public Rule build() {
            super.validate();
            return new Rule(this);
        }
    }
}
