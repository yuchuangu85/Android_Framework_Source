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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.StringDef;
import android.os.Bundle;
import android.util.ArraySet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Base class for rules for detecting system anomalies.
 *
 * @hide
 */
public class RuleInternal {
    private static final String RULE_KEY_PREFIX = "android.os.profiling.anomaly.Rule.";

    /**
     * The integer values for @IntDef definitions
     *
     * <p>The integer value 0 - 1000 is reserved for Android Platform use.
     */
    /** Action to write a detailed report of the anomaly to the system log. */
    public static final int ACTION_TYPE_LOG = 1;

    // TODO: b/482942778 - Also add this to the Rule class
    /** Action to collect a profile of the package the anomaly originated from. */
    public static final int ACTION_TYPE_COLLECT_PROFILE = 2;

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
     */
    public static final String CONDITION_TYPE_BINDER_SPAM = RULE_KEY_PREFIX + "binder_spam";

    /**
     * {@link Bundle} key for the fully qualified name of the AIDL interface to monitor.
     *
     * <p>Example: {@code "android.app.IActivityManager"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     */
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME =
            RULE_KEY_PREFIX + "binder_interface_name";

    /**
     * {@link Bundle} key for the name of the method within the AIDL interface to monitor.
     *
     * <p>Example: {@code "startService"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     */
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME =
            RULE_KEY_PREFIX + "binder_method_name";

    /**
     * {@link Bundle} key for the maximum number of allowed calls to the specified interface and
     * method within the defined interval. This value is an integer.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     */
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT =
            RULE_KEY_PREFIX + "binder_call_limit";

    /**
     * {@link Bundle} key for the duration of the sliding time window in milliseconds used to count
     * Binder calls. This value is a long.
     *
     * <p>Exceeding the {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT} within this interval
     * triggers the associated anomaly actions.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     */
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS =
            RULE_KEY_PREFIX + "binder_call_interval_millis";

    private static final Set<String> BINDER_SPAM_CONDITION_KEYS =
            Set.of(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);

    /** The name of the rule. */
    private final String mName;

    /**
     * The list of actions to execute when the rule's condition (see {@link #getConditionType()}) is
     * met. Each element must be a value from {@link AnomalyActionTypeInternal}.
     */
    private final List<@AnomalyActionTypeInternal Integer> mAnomalyActions;

    /**
     * The type of condition this rule monitors. Must be a value from {@link ConditionTypeInternal}.
     */
    private final @ConditionTypeInternal String mConditionType;

    /**
     * A {@link Bundle} containing the specific parameters for the rule's condition.
     *
     * <p>The system enforces this condition. Non-compliance triggers the execution of the actions
     * specified in {@link #getAnomalyActions()}.
     */
    private final Bundle mRuleCondition;

    // constructor used by the Builder.
    protected RuleInternal(Builder builder) {
        mName = builder.mName;
        mAnomalyActions = new ArrayList<>(builder.mAnomalyActions);
        mConditionType = builder.mConditionType;
        mRuleCondition = builder.mRuleCondition;
    }

    /**
     * Returns the name of the rule.
     *
     * @return The name of the rule.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the list of actions to be executed by the anomaly detection service when the {@code
     * mRuleCondition} defined by this rule is met.
     *
     * <p>Each integer in the list corresponds to a constant defined in {@link
     * AnomalyActionTypeInternal}, representing a specific action.
     *
     * @return A non-null list of {@link AnomalyActionTypeInternal} integers.
     */
    @NonNull
    public List<@AnomalyActionTypeInternal Integer> getAnomalyActions() {
        return new ArrayList<>(mAnomalyActions);
    }

    /**
     * Returns a string indicating the type of condition this rule monitors.
     *
     * <p>The condition type determines how the parameters in the {@link #getRuleCondition()} Bundle
     * are interpreted and what system behavior is being observed.
     *
     * @return One of the string constants defined in {@link ConditionTypeInternal}, for example,
     *     {@link #CONDITION_TYPE_BINDER_SPAM}.
     */
    @NonNull
    public @ConditionTypeInternal String getConditionType() {
        return mConditionType;
    }

    /**
     * Returns the {@link Bundle} containing the specific parameters that define the condition for
     * this rule.
     *
     * <p>The expected keys and value types within this Bundle are strictly dependent on the {@link
     * ConditionTypeInternal} returned by {@link #getConditionType()}. For instance, if the type is
     * {@link #CONDITION_TYPE_BINDER_SPAM}, the Bundle should contain the following keys: {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT}, and {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS}.
     *
     * @return A non-null {@link Bundle} instance. The Bundle may be empty if the condition type
     *     requires no parameters.
     */
    @NonNull
    public Bundle getRuleCondition() {
        return new Bundle(mRuleCondition);
    }

    /*
     * "deprecation" suppression: Bundle.get(String) is deprecated, but used here for generic
     * key-value comparison across different types.
     * "EqualsGetClass" suppression: The API class Rule (which extends RuleInternal) is final and
     * adds no state, but semantically, we want to maintain a strict type equality to distinguish
     * internal objects from API objects.
     */
    @Override
    @SuppressWarnings({"deprecation", "EqualsGetClass"})
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleInternal rule = (RuleInternal) o;

        if (!mName.equals(rule.mName)) {
            return false;
        }

        if (!mConditionType.equals(rule.mConditionType)) {
            return false;
        }

        // Compare mAnomalyActions. Order doesn't matter.
        if (mAnomalyActions.size() != rule.mAnomalyActions.size()
                || !new ArraySet<>(mAnomalyActions).equals(new ArraySet<>(rule.mAnomalyActions))) {
            return false;
        }

        // Compare mRuleCondition
        if (mRuleCondition.size() != rule.mRuleCondition.size()) {
            return false;
        }
        for (String key : mRuleCondition.keySet()) {
            if (!rule.mRuleCondition.containsKey(key)) {
                return false;
            }
            Object value1 = mRuleCondition.get(key);
            Object value2 = rule.mRuleCondition.get(key);
            if (!Objects.equals(value1, value2)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mName, mConditionType);

        // Hash for mAnomalyActions, order-independent
        result = 31 * result + new ArraySet<>(mAnomalyActions).hashCode();

        // Hash for mRuleCondition, order-independent for keys
        int bundleHash = 0;
        for (String key : mRuleCondition.keySet()) {
            Object value = mRuleCondition.get(key);
            bundleHash += Objects.hash(key, value);
        }
        result = 31 * result + bundleHash;

        return result;
    }

    /**
     * Defines the types of actions to be executed by the {@code
     * com.android.os.profiling.anomaly.AnomalyDetectorService} when an anomaly is detected based on
     * the {@link Rule}.
     *
     * @hide
     */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    // LINT.IfChange(supported_actions)
    @IntDef({
        ACTION_TYPE_LOG,
        ACTION_TYPE_COLLECT_PROFILE,
    })
    // LINT.ThenChange(/anomaly-detector/tests/scripts/generate_anomaly_rules.py:supported_actions)
    // TODO(b/416804300): Add default and other action once finalized.
    public @interface AnomalyActionTypeInternal {}

    /**
     * Defines the possible types of conditions a {@link Rule} can represent.
     *
     * @hide
     */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    // LINT.IfChange(supported_condition_types)
    @StringDef({
        CONDITION_TYPE_BINDER_SPAM,
    })
    // LINT.ThenChange(/anomaly-detector/tests/scripts/generate_anomaly_rules.py:supported_condition_types)
    public @interface ConditionTypeInternal {}

    /**
     * Builder class for creating {@link RuleInternal} instances.
     *
     * @hide
     */
    public static class Builder {
        private String mName = "";
        private final Set<@AnomalyActionTypeInternal Integer> mAnomalyActions = new ArraySet<>();
        private @ConditionTypeInternal String mConditionType;
        private Bundle mRuleCondition;

        /**
         * Sets the name of the rule.
         *
         * @param name The name of the rule.
         * @return This Builder instance for chaining.
         */
        @NonNull
        public Builder setName(@NonNull String name) {
            Objects.requireNonNull(name, "name cannot be null");
            mName = name;
            return this;
        }

        /**
         * Adds a action to be taken when the rule's condition is met. Duplicate actions will be
         * ignored.
         *
         * @param anomalyAction An integer representing one of the constants defined in {@link
         *     AnomalyActionTypeInternal}.
         * @return This Builder instance for chaining.
         */
        @NonNull
        public Builder addAnomalyAction(@AnomalyActionTypeInternal int anomalyAction) {
            mAnomalyActions.add(anomalyAction);
            return this;
        }

        /**
         * Sets the type of condition this rule monitors.
         *
         * @param conditionType One of the string constants defined in {@link
         *     ConditionTypeInternal}.
         * @return This Builder instance for chaining.
         */
        @NonNull
        public Builder setConditionType(@NonNull @ConditionTypeInternal String conditionType) {
            Objects.requireNonNull(conditionType, "conditionType cannot be null");
            mConditionType = conditionType;
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
         */
        @NonNull
        public Builder setRuleCondition(@NonNull Bundle ruleCondition) {
            Objects.requireNonNull(ruleCondition, "ruleCondition cannot be null");
            mRuleCondition = ruleCondition;
            return this;
        }

        /**
         * Builds the {@link RuleInternal} instance.
         *
         * @return The configured {@link RuleInternal} object.
         * @throws IllegalStateException if required fields (ConditionType or RuleCondition) are not
         *     set.
         * @throws IllegalArgumentException if any values are invalid (e.g., unknown action type,
         *     unknown condition type, or invalid Bundle keys for the given condition type).
         */
        @NonNull
        public RuleInternal build() {
            validate();
            return new RuleInternal(this);
        }

        /**
         * Validates the state of the builder before building the rule.
         *
         * @throws IllegalStateException if required fields are not set.
         * @throws IllegalArgumentException if the rule condition bundle is invalid.
         */
        protected void validate() {
            if (mConditionType == null) {
                throw new IllegalStateException("ConditionType must be set.");
            }
            if (mRuleCondition == null) {
                throw new IllegalStateException("RuleCondition Bundle must be set.");
            }
            if (mAnomalyActions.isEmpty()) {
                throw new IllegalStateException("AnomalyActions must be set.");
            }

            validateRuleConditionBundle();
        }

        /**
         * Validates the contents of {@link #mRuleCondition} based on the {@link #mConditionType}.
         *
         * <p>This method ensures that all required keys for the specified condition type are
         * present and that their corresponding values are of the correct type.
         *
         * @throws IllegalArgumentException if the bundle is missing required keys or contains
         *     values of incorrect types for the given condition.
         */
        private void validateRuleConditionBundle() {
            Set<String> requiredKeys = new ArraySet<>();
            Set<String> providedKeys = mRuleCondition.keySet();

            switch (mConditionType) {
                case CONDITION_TYPE_BINDER_SPAM -> {
                    requiredKeys = new ArraySet<>(BINDER_SPAM_CONDITION_KEYS);
                }
            }

            if (!providedKeys.containsAll(requiredKeys)) {
                Set<String> missingKeys = new ArraySet<>(requiredKeys);
                missingKeys.removeAll(providedKeys);

                throw new IllegalArgumentException(
                        "mRuleCondition is missing keys. Missing keys: " + missingKeys);
            }

            // if all the keys are present, we validate value types
            switch (mConditionType) {
                case CONDITION_TYPE_BINDER_SPAM -> validateBinderSpamBundleValuesType();
                    // add validation for other types.
            }
        }

        /*
         * "deprecation" suppression: Bundle.get(String) is deprecated, but used here for strict
         * runtime type checking.
         */
        @SuppressWarnings("deprecation")
        private void validateBinderSpamBundleValueType(String key, Class<?> expectedType) {
            Object value = mRuleCondition.get(key);

            if (!expectedType.isInstance(value)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid value type for key: %s. Expected: %s, Actual: %s",
                                key,
                                expectedType.getSimpleName(),
                                (value == null ? "null" : value.getClass().getSimpleName())));
            }
        }

        // TODO(b/440140585): Validate the format of the interface name and method.
        private void validateBinderSpamBundleValuesType() {
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, String.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, String.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, Integer.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, Long.class);
        }
    }
}
