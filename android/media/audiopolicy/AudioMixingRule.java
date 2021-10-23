/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.audiopolicy;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;


/**
 * @hide
 *
 * Here's an example of creating a mixing rule for all media playback:
 * <pre>
 * AudioAttributes mediaAttr = new AudioAttributes.Builder()
 *         .setUsage(AudioAttributes.USAGE_MEDIA)
 *         .build();
 * AudioMixingRule mediaRule = new AudioMixingRule.Builder()
 *         .addRule(mediaAttr, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
 *         .build();
 * </pre>
 */
@SystemApi
public class AudioMixingRule {

    private AudioMixingRule(int mixType, ArrayList<AudioMixMatchCriterion> criteria,
                            boolean allowPrivilegedMediaPlaybackCapture,
                            boolean voiceCommunicationCaptureAllowed) {
        mCriteria = criteria;
        mTargetMixType = mixType;
        mAllowPrivilegedPlaybackCapture = allowPrivilegedMediaPlaybackCapture;
        mVoiceCommunicationCaptureAllowed = voiceCommunicationCaptureAllowed;
    }

    /**
     * A rule requiring the usage information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    /**
     * A rule requiring the capture preset information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    public static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x1 << 1;
    /**
     * A rule requiring the UID of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where the Object
     * parameter is an instance of {@link java.lang.Integer}.
     */
    public static final int RULE_MATCH_UID = 0x1 << 2;
    /**
     * A rule requiring the userId of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where the Object
     * parameter is an instance of {@link java.lang.Integer}.
     */
    public static final int RULE_MATCH_USERID = 0x1 << 3;

    private final static int RULE_EXCLUSION_MASK = 0x8000;
    /**
     * @hide
     * A rule requiring the usage information of the {@link AudioAttributes} to differ.
     */
    public static final int RULE_EXCLUDE_ATTRIBUTE_USAGE =
            RULE_EXCLUSION_MASK | RULE_MATCH_ATTRIBUTE_USAGE;
    /**
     * @hide
     * A rule requiring the capture preset information of the {@link AudioAttributes} to differ.
     */
    public static final int RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET =
            RULE_EXCLUSION_MASK | RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET;
    /**
     * @hide
     * A rule requiring the UID information to differ.
     */
    public static final int RULE_EXCLUDE_UID =
            RULE_EXCLUSION_MASK | RULE_MATCH_UID;

    /**
     * @hide
     * A rule requiring the userId information to differ.
     */
    public static final int RULE_EXCLUDE_USERID =
            RULE_EXCLUSION_MASK | RULE_MATCH_USERID;

    /** @hide */
    public static final class AudioMixMatchCriterion {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final AudioAttributes mAttr;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final int mIntProp;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final int mRule;

        /** input parameters must be valid */
        AudioMixMatchCriterion(AudioAttributes attributes, int rule) {
            mAttr = attributes;
            mIntProp = Integer.MIN_VALUE;
            mRule = rule;
        }
        /** input parameters must be valid */
        AudioMixMatchCriterion(Integer intProp, int rule) {
            mAttr = null;
            mIntProp = intProp.intValue();
            mRule = rule;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAttr, mIntProp, mRule);
        }

        void writeToParcel(Parcel dest) {
            dest.writeInt(mRule);
            final int match_rule = mRule & ~RULE_EXCLUSION_MASK;
            switch (match_rule) {
                case RULE_MATCH_ATTRIBUTE_USAGE:
                    dest.writeInt(mAttr.getSystemUsage());
                    break;
                case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                    dest.writeInt(mAttr.getCapturePreset());
                    break;
                case RULE_MATCH_UID:
                case RULE_MATCH_USERID:
                    dest.writeInt(mIntProp);
                    break;
                default:
                    Log.e("AudioMixMatchCriterion", "Unknown match rule" + match_rule
                            + " when writing to Parcel");
                    dest.writeInt(-1);
            }
        }

        public AudioAttributes getAudioAttributes() { return mAttr; }
        public int getIntProp() { return mIntProp; }
        public int getRule() { return mRule; }
    }

    boolean isAffectingUsage(int usage) {
        for (AudioMixMatchCriterion criterion : mCriteria) {
            if ((criterion.mRule & RULE_MATCH_ATTRIBUTE_USAGE) != 0
                    && criterion.mAttr != null
                    && criterion.mAttr.getSystemUsage() == usage) {
                return true;
            }
        }
        return false;
    }

    /**
      * Returns {@code true} if this rule contains a RULE_MATCH_ATTRIBUTE_USAGE criterion for
      * the given usage
      *
      * @hide
      */
    boolean containsMatchAttributeRuleForUsage(int usage) {
        for (AudioMixMatchCriterion criterion : mCriteria) {
            if (criterion.mRule == RULE_MATCH_ATTRIBUTE_USAGE
                    && criterion.mAttr != null
                    && criterion.mAttr.getSystemUsage() == usage) {
                return true;
            }
        }
        return false;
    }

    private static boolean areCriteriaEquivalent(ArrayList<AudioMixMatchCriterion> cr1,
            ArrayList<AudioMixMatchCriterion> cr2) {
        if (cr1 == null || cr2 == null) return false;
        if (cr1 == cr2) return true;
        if (cr1.size() != cr2.size()) return false;
        //TODO iterate over rules to check they contain the same criterion
        return (cr1.hashCode() == cr2.hashCode());
    }

    private final int mTargetMixType;
    int getTargetMixType() { return mTargetMixType; }
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<AudioMixMatchCriterion> mCriteria;
    /** @hide */
    public ArrayList<AudioMixMatchCriterion> getCriteria() { return mCriteria; }
    /** Indicates that this rule is intended to capture media or game playback by a system component
      * with permission CAPTURE_MEDIA_OUTPUT or CAPTURE_AUDIO_OUTPUT.
      */
    //TODO b/177061175: rename to mAllowPrivilegedMediaPlaybackCapture
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean mAllowPrivilegedPlaybackCapture = false;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean mVoiceCommunicationCaptureAllowed = false;

    /** @hide */
    public boolean allowPrivilegedMediaPlaybackCapture() {
        return mAllowPrivilegedPlaybackCapture;
    }

    /** @hide */
    public boolean voiceCommunicationCaptureAllowed() {
        return mVoiceCommunicationCaptureAllowed;
    }

    /** @hide */
    public void setVoiceCommunicationCaptureAllowed(boolean allowed) {
        mVoiceCommunicationCaptureAllowed = allowed;
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final AudioMixingRule that = (AudioMixingRule) o;
        return (this.mTargetMixType == that.mTargetMixType)
                && (areCriteriaEquivalent(this.mCriteria, that.mCriteria)
                && this.mAllowPrivilegedPlaybackCapture == that.mAllowPrivilegedPlaybackCapture
                && this.mVoiceCommunicationCaptureAllowed
                    == that.mVoiceCommunicationCaptureAllowed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            mTargetMixType,
            mCriteria,
            mAllowPrivilegedPlaybackCapture,
            mVoiceCommunicationCaptureAllowed);
    }

    private static boolean isValidSystemApiRule(int rule) {
        // API rules only expose the RULE_MATCH_* rules
        switch (rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
            case RULE_MATCH_USERID:
                return true;
            default:
                return false;
        }
    }
    private static boolean isValidAttributesSystemApiRule(int rule) {
        // API rules only expose the RULE_MATCH_* rules
        switch (rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
            case RULE_MATCH_USERID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlayerRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_USERID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isRecorderRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isAudioAttributeRule(int match_rule) {
        switch(match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    /**
     * Builder class for {@link AudioMixingRule} objects
     */
    public static class Builder {
        private ArrayList<AudioMixMatchCriterion> mCriteria;
        private int mTargetMixType = AudioMix.MIX_TYPE_INVALID;
        private boolean mAllowPrivilegedMediaPlaybackCapture = false;
        // This value should be set internally according to a permission check
        private boolean mVoiceCommunicationCaptureAllowed = false;

        /**
         * Constructs a new Builder with no rules.
         */
        public Builder() {
            mCriteria = new ArrayList<AudioMixMatchCriterion>();
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE} or
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #excludeRule(AudioAttributes, int)
         */
        public Builder addRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidAttributesSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule, attrToMatch);
        }

        /**
         * Add a rule by exclusion for the selection of which streams are mixed together.
         * <br>For instance the following code
         * <br><pre>
         * AudioAttributes mediaAttr = new AudioAttributes.Builder()
         *         .setUsage(AudioAttributes.USAGE_MEDIA)
         *         .build();
         * AudioMixingRule noMediaRule = new AudioMixingRule.Builder()
         *         .excludeRule(mediaAttr, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
         *         .build();
         * </pre>
         * <br>will create a rule which maps to any usage value, except USAGE_MEDIA.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE} or
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #addRule(AudioAttributes, int)
         */
        public Builder excludeRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidAttributesSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule | RULE_EXCLUSION_MASK, attrToMatch);
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * The rule defines what the matching will be made on. It also determines the type of the
         * property to match against.
         * @param rule one of {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_MATCH_UID} or
         *     {@link AudioMixingRule#RULE_MATCH_USERID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #excludeMixRule(int, Object)
         */
        public Builder addMixRule(int rule, Object property) throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule, property);
        }

        /**
         * Add a rule by exclusion for the selection of which streams are mixed together.
         * <br>For instance the following code
         * <br><pre>
         * AudioAttributes mediaAttr = new AudioAttributes.Builder()
         *         .setUsage(AudioAttributes.USAGE_MEDIA)
         *         .build();
         * AudioMixingRule noMediaRule = new AudioMixingRule.Builder()
         *         .addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, mediaAttr)
         *         .excludeMixRule(AudioMixingRule.RULE_MATCH_UID, new Integer(uidToExclude)
         *         .build();
         * </pre>
         * <br>will create a rule which maps to usage USAGE_MEDIA, but excludes any stream
         * coming from the specified UID.
         * @param rule one of {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_MATCH_UID} or
         *     {@link AudioMixingRule#RULE_MATCH_USERID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder excludeMixRule(int rule, Object property) throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule | RULE_EXCLUSION_MASK, property);
        }

        /**
         * Set if the audio of app that opted out of audio playback capture should be captured.
         *
         * Caller of this method with <code>true</code>, MUST abide to the restriction listed in
         * {@link ALLOW_CAPTURE_BY_SYSTEM}, including but not limited to the captured audio
         * can not leave the capturing app, and the quality is limited to 16k mono.
         *
         * The permission {@link CAPTURE_AUDIO_OUTPUT} or {@link CAPTURE_MEDIA_OUTPUT} is needed
         * to ignore the opt-out.
         *
         * Only affects LOOPBACK|RENDER mix.
         *
         * @return the same Builder instance.
         */
        public @NonNull Builder allowPrivilegedPlaybackCapture(boolean allow) {
            mAllowPrivilegedMediaPlaybackCapture = allow;
            return this;
        }

        /**
         * Set if the caller of the rule is able to capture voice communication output.
         * A system app can capture voice communication output only if it is granted with the.
         * CAPTURE_VOICE_COMMUNICATION_OUTPUT permission.
         *
         * Note that this method is for internal use only and should not be called by the app that
         * creates the rule.
         *
         * @return the same Builder instance.
         *
         * @hide
         */
        public @NonNull Builder voiceCommunicationCaptureAllowed(boolean allowed) {
            mVoiceCommunicationCaptureAllowed = allowed;
            return this;
        }

        /**
         * Set target mix type of the mixing rule.
         *
         * <p>Note: If the mix type was not specified, it will be decided automatically by mixing
         * rule. For {@link #RULE_MATCH_UID}, the default type is {@link AudioMix#MIX_TYPE_PLAYERS}.
         *
         * @param mixType {@link AudioMix#MIX_TYPE_PLAYERS} or {@link AudioMix#MIX_TYPE_RECORDERS}
         * @return the same Builder instance.
         *
         * @hide
         */
        public @NonNull Builder setTargetMixType(int mixType) {
            mTargetMixType = mixType;
            Log.i("AudioMixingRule", "Builder setTargetMixType " + mixType);
            return this;
        }

        /**
         * Add or exclude a rule for the selection of which streams are mixed together.
         * Does error checking on the parameters.
         * @param rule
         * @param property
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        private Builder checkAddRuleObjInternal(int rule, Object property)
                throws IllegalArgumentException {
            if (property == null) {
                throw new IllegalArgumentException("Illegal null argument for mixing rule");
            }
            if (!isValidRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            final int match_rule = rule & ~RULE_EXCLUSION_MASK;
            if (isAudioAttributeRule(match_rule)) {
                if (!(property instanceof AudioAttributes)) {
                    throw new IllegalArgumentException("Invalid AudioAttributes argument");
                }
                return addRuleInternal((AudioAttributes) property, null, rule);
            } else {
                // implies integer match rule
                if (!(property instanceof Integer)) {
                    throw new IllegalArgumentException("Invalid Integer argument");
                }
                return addRuleInternal(null, (Integer) property, rule);
            }
        }

        /**
         * Add or exclude a rule on AudioAttributes or integer property for the selection of which
         * streams are mixed together.
         * No rule-to-parameter type check, all done in {@link #checkAddRuleObjInternal(int, Object)}.
         * Exceptions are thrown only when incompatible rules are added.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet, null if not used.
         * @param intProp an integer property to match or exclude, null if not used.
         * @param rule one of {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET},
         *     {@link AudioMixingRule#RULE_MATCH_UID}, {@link AudioMixingRule#RULE_EXCLUDE_UID}.
         *     {@link AudioMixingRule#RULE_MATCH_USERID},
         *     {@link AudioMixingRule#RULE_EXCLUDE_USERID}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        private Builder addRuleInternal(AudioAttributes attrToMatch, Integer intProp, int rule)
                throws IllegalArgumentException {
            // as rules are added to the Builder, we verify they are consistent with the type
            // of mix being built. When adding the first rule, the mix type is MIX_TYPE_INVALID.
            if (mTargetMixType == AudioMix.MIX_TYPE_INVALID) {
                if (isPlayerRule(rule)) {
                    mTargetMixType = AudioMix.MIX_TYPE_PLAYERS;
                } else if (isRecorderRule(rule)) {
                    mTargetMixType = AudioMix.MIX_TYPE_RECORDERS;
                } else {
                    // For rules which are not player or recorder specific (e.g. RULE_MATCH_UID),
                    // the default mix type is MIX_TYPE_PLAYERS.
                    mTargetMixType = AudioMix.MIX_TYPE_PLAYERS;
                }
            } else if ((isPlayerRule(rule) && (mTargetMixType != AudioMix.MIX_TYPE_PLAYERS))
                    || (isRecorderRule(rule)) && (mTargetMixType != AudioMix.MIX_TYPE_RECORDERS))
            {
                throw new IllegalArgumentException("Incompatible rule for mix");
            }
            synchronized (mCriteria) {
                Iterator<AudioMixMatchCriterion> crIterator = mCriteria.iterator();
                final int match_rule = rule & ~RULE_EXCLUSION_MASK;
                while (crIterator.hasNext()) {
                    final AudioMixMatchCriterion criterion = crIterator.next();

                    if ((criterion.mRule & ~RULE_EXCLUSION_MASK) != match_rule) {
                        continue; // The two rules are not of the same type
                    }
                    switch (match_rule) {
                        case RULE_MATCH_ATTRIBUTE_USAGE:
                            // "usage"-based rule
                            if (criterion.mAttr.getSystemUsage() == attrToMatch.getSystemUsage()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for " + attrToMatch);
                                }
                            }
                            break;
                        case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                            // "capture preset"-base rule
                            if (criterion.mAttr.getCapturePreset() == attrToMatch.getCapturePreset()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for " + attrToMatch);
                                }
                            }
                            break;
                        case RULE_MATCH_UID:
                            // "usage"-based rule
                            if (criterion.mIntProp == intProp.intValue()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for UID " + intProp);
                                }
                            }
                            break;
                        case RULE_MATCH_USERID:
                            // "userid"-based rule
                            if (criterion.mIntProp == intProp.intValue()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for userId " + intProp);
                                }
                            }
                            break;
                    }
                }
                // rule didn't exist, add it
                switch (match_rule) {
                    case RULE_MATCH_ATTRIBUTE_USAGE:
                    case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                        mCriteria.add(new AudioMixMatchCriterion(attrToMatch, rule));
                        break;
                    case RULE_MATCH_UID:
                    case RULE_MATCH_USERID:
                        mCriteria.add(new AudioMixMatchCriterion(intProp, rule));
                        break;
                    default:
                        throw new IllegalStateException("Unreachable code in addRuleInternal()");
                }
            }
            return this;
        }

        Builder addRuleFromParcel(Parcel in) throws IllegalArgumentException {
            final int rule = in.readInt();
            final int match_rule = rule & ~RULE_EXCLUSION_MASK;
            AudioAttributes attr = null;
            Integer intProp = null;
            switch (match_rule) {
                case RULE_MATCH_ATTRIBUTE_USAGE:
                    int usage = in.readInt();
                    if (AudioAttributes.isSystemUsage(usage)) {
                        attr = new AudioAttributes.Builder()
                                .setSystemUsage(usage).build();
                    } else {
                        attr = new AudioAttributes.Builder()
                                .setUsage(usage).build();
                    }
                    break;
                case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                    int preset = in.readInt();
                    attr = new AudioAttributes.Builder()
                            .setInternalCapturePreset(preset).build();
                    break;
                case RULE_MATCH_UID:
                case RULE_MATCH_USERID:
                    intProp = new Integer(in.readInt());
                    break;
                default:
                    // assume there was in int value to read as for now they come in pair
                    in.readInt();
                    throw new IllegalArgumentException("Illegal rule value " + rule + " in parcel");
            }
            return addRuleInternal(attr, intProp, rule);
        }

        /**
         * Combines all of the matching and exclusion rules that have been set and return a new
         * {@link AudioMixingRule} object.
         * @return a new {@link AudioMixingRule} object
         */
        public AudioMixingRule build() {
            return new AudioMixingRule(mTargetMixType, mCriteria,
                mAllowPrivilegedMediaPlaybackCapture, mVoiceCommunicationCaptureAllowed);
        }
    }
}
