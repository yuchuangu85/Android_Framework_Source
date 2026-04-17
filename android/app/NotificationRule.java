/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app;

import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.modes.ContextualMode;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Representation of a rule that modifies the behavior of certain notifications at certain times.
 * Contains a set of filters to determine what notifications are affected, a set of conditions to
 * determine when a rule is active, and an action to apply when a notification matches a given
 * filter and when the user's context matches a given condition.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_NM_CONTEXTUAL_DISPLAY_LAUNCH)
public final class NotificationRule implements Parcelable {
    private static final String TAG = "NotificationRule";

    // Rule ids 100-200 are reserved for user owned rules.
    /**
     * Reserved rule id for OS owned rule that highlights promoted notifications
     */
    public static final int RESERVED_ID_PROMOTED = 201;
    /**
     * Reserved rule id for the OS owned rule that highlights priority conversations
     */
    public static final int RESERVED_ID_PRIORITY_CONVERSATIONS = 202;
    /**
     * Reserved rule id for the NAS owned rule that bundles lower urgency notifications
     */
    public static final int RESERVED_ID_STATIC_BUNDLES = 203;
    /**
     * Reserved rule id for the notification assistant owned rule that highlights urgent
     * notifications
\     */
    public static final int RESERVED_ID_IMPORTANT_NOTIFICATIONS = 204;

    // tags and attributes for persistence
    private static final String DELIMITER = ",";
    /**
     * @hide
     */
    public static final String RULE_TAG = "rule";
    /**
     * @hide
     */
    public static final String USER_ATTR = "user";
    private static final String ID_ATTR = "id";
    private static final String ENABLED_ATTR = "enabled";
    private static final String NAME_ATTR = "name";
    private static final String EDIT_INTENT_ACTION_ATTR = "editIntentAction";
    private static final String CAN_BE_DISABLED_ATTR = "canBeDisabled";

    private static final String ACTION_TAG = "action";
    private static final String PRIMARY_ACTION_ATTR = "primaryAction";
    private static final String SOUND_ATTR = "sound";
    private static final String LIGHT_COLOR_ATTR = "lightColor";
    private static final String MODE_BREAKTHROUGH_ATTR = "modeBreakthroughs";
    private static final String DYNAMIC_BUNDLE_NAME_ATTR = "dynamicBundleName";
    private static final String DYNAMIC_BUNDLE_EMOJI_ICON_ATTR = "dynamicBundleEmojiIcon";

    private static final String CONDITIONS_TAG = "conditions";
    private static final String CONDITION_TAG = "condition";
    private static final String LOCATION_TAG = "location";
    private static final String TIME_TAG = "time";
    private static final String CONDITION_TYPE_ATTR = "conditionType";
    private static final String LATITUDE_ATTR = "latitude";
    private static final String LONGITUDE_ATTR = "longitude";
    private static final String RADIUS_ATTR = "radius";
    private static final String DAY_TAG = "day";
    private static final String START_HOUR_ATTR = "startHour";
    private static final String START_MINUTE_ATTR = "startMinute";
    private static final String END_HOUR_ATTR = "endHour";
    private static final String END_MINUTE_ATTR = "endMinute";

    private static final String FILTERS_TAG = "filters";
    private static final String FILTER_TAG = "filter";
    private static final String INCLUDED_PACKAGES_TAG = "includedPackages";
    private static final String EXCLUDED_PACKAGES_TAG = "excludedPackages";
    private static final String CONTACT_LEVEL_ATTR = "contactLevel";
    private static final String CONVERSATION_LEVEL_ATTR = "conversationLevel";
    private static final String CONTACTS_TAG = "contacts";
    private static final String CONTACT_TAG = "contact";
    private static final String VALUE_ATTR = "value";
    private static final String SHORTCUT_ID_TAG = "shortcutId";
    private static final String KEYWORD_TAG = "keyword";
    private static final String USER_TAG = "user";
    private static final String CATEGORY_TAG = "category";
    private static final String STATIC_BUNDLE_TYPE_TAG = "staticBundleType";
    private static final String FLAGS_TAG = "flags";


    private final List<Filter> mFilters = new ArrayList<>();
    private boolean mEnabled = true;
    private int mId;
    private @NonNull final String mName;
    private @Nullable final String mEditIntentAction;
    private @Nullable final Action mAction;
    private boolean mCanBeDisabled = true;
    private final List<Condition> mConditions = new ArrayList<>();

    /**
     * @hide
     */
    public static boolean isSystemRule(int ruleId) {
        return ruleId == RESERVED_ID_PROMOTED
                || ruleId == RESERVED_ID_PRIORITY_CONVERSATIONS
                || ruleId == RESERVED_ID_IMPORTANT_NOTIFICATIONS
                || ruleId == RESERVED_ID_STATIC_BUNDLES;
    }

    /**
     * Returns the filters for this rule.
     * <p>
     * A notification matches this rule if it matches <em>any</em> of the provided filters.
     * Within a single {@link Filter}, all conditions must be met for the notification to match
     * (logical AND). Across multiple filters, the relationship is logical OR. If the list is empty,
     * all notifications match this rule.
     */
    public @NonNull List<Filter> getFilters() {
        return mFilters;
    }

    /**
     * Returns the action that should be applied to notifications that match this rule.
     */
    public @NonNull Action getAction() {
        return mAction;
    }

    /**
     * Returns whether this rule is enabled.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * @hide
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /**
     * Returns the id of this rule. These must be unique within a {@link UserHandle}.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the name of this rule.
     */
    public @NonNull String getName() {
        return mName;
    }

    /**
     * Returns whether a 'turn off rule' affordance should be displayed in the rule edit UIs.
     * @hide
     */
    @TestApi
    public boolean canBeDisabled() {
        return mCanBeDisabled;
    }

    /**
     * Returns the {@link Intent#getAction()} for the activity where this rule can be edited.
     * If null, this rule cannot be edited.
     * @hide
     */
    @TestApi
    public @Nullable String getEditIntentAction() {
        return mEditIntentAction;
    }

    /**
     * Returns the list of conditions that define when this rule is active. The rule should be
     * active if any of these conditions are met. If the list is empty the rule is always active.
     */
    public @NonNull List<Condition> getConditions() {
        return mConditions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NotificationRule)) return false;
        NotificationRule that = (NotificationRule) o;
        return mEnabled == that.mEnabled && mId == that.mId && mCanBeDisabled == that.mCanBeDisabled
                && Objects.equals(mFilters, that.mFilters) && Objects.equals(mName,
                that.mName) && Objects.equals(mEditIntentAction, that.mEditIntentAction)
                && Objects.equals(mAction, that.mAction) && Objects.equals(
                mConditions, that.mConditions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFilters, mEnabled, mId, mName, mEditIntentAction, mAction,
                mCanBeDisabled,
                mConditions);
    }

    @Override
    public String toString() {
        return "NotificationRule{" +
                "mFilters=" + mFilters +
                ", mEnabled=" + mEnabled +
                ", mId=" + mId +
                ", mName='" + mName + '\'' +
                ", mEditIntentAction='" + mEditIntentAction + '\'' +
                ", mAction=" + mAction +
                ", mCanBeDisabled=" + mCanBeDisabled +
                ", mConditions=" + mConditions +
                '}';
    }

    /**
     * @hide
     */
    public static NotificationRule readXml(TypedXmlPullParser parser, boolean forRestore,
            @Nullable Context context) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        String tag = parser.getName();
        if (type != XmlPullParser.START_TAG || !RULE_TAG.equals(tag)) return null;

        int id = parser.getAttributeInt(null, ID_ATTR, 0);
        String name = parser.getAttributeValue(null, NAME_ATTR);
        NotificationRule.Builder builder = new NotificationRule.Builder(id, name);
        builder.setEnabled(parser.getAttributeBoolean(null, ENABLED_ATTR));
        builder.setEditIntentAction(parser.getAttributeValue(null, EDIT_INTENT_ACTION_ATTR));
        builder.setCanBeDisabled(parser.getAttributeBoolean(null, CAN_BE_DISABLED_ATTR));
        type = parser.next();
        tag = parser.getName();
        while (type != XmlPullParser.END_DOCUMENT
                && !(RULE_TAG.equals(tag) && type == XmlPullParser.END_TAG)) {
            if (type == XmlPullParser.START_TAG) {
                if (ACTION_TAG.equals(tag)) {
                    builder.setAction(Action.readXml(parser, forRestore, context));
                }
                if (CONDITION_TAG.equals(tag)) {
                    builder.addCondition(Condition.readXml(parser));
                }
                if (FILTER_TAG.equals(tag)) {
                    Filter filter = Filter.readXml(parser);
                    builder.addFilter(filter);
                }
            }
            type = parser.next();
            tag = parser.getName();
        }
        return builder.build();
    }

    /**
     * @hide
     */
    public void writeXml(TypedXmlSerializer out, boolean forBackup, int userId,
            @Nullable Context context) throws IOException {
        out.startTag(null, RULE_TAG);
        if (!forBackup) {
            out.attribute(null, USER_ATTR, String.valueOf(userId));
        }
        out.attribute(null, ID_ATTR, String.valueOf(getId()));
        out.attribute(null, ENABLED_ATTR, String.valueOf(isEnabled()));
        out.attribute(null, NAME_ATTR, getName());
        if (mEditIntentAction != null) {
            out.attribute(null, EDIT_INTENT_ACTION_ATTR, getEditIntentAction());
        }
        out.attribute(null, CAN_BE_DISABLED_ATTR, String.valueOf(canBeDisabled()));

        if (getAction() != null) {
            getAction().writeXml(out, forBackup, context);
        }

        if (!getConditions().isEmpty()) {
            out.startTag(null, CONDITIONS_TAG);
            for (Condition condition : getConditions()) {
                condition.writeXml(out);
            }
            out.endTag(null, CONDITIONS_TAG);
        }

        if (!getFilters().isEmpty()) {
            out.startTag(null, FILTERS_TAG);
            for (Filter filter : getFilters()) {
                filter.writeXml(out);
            }
            out.endTag(null, FILTERS_TAG);
        }
        out.endTag(null, RULE_TAG);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mFilters);
        dest.writeBoolean(mEnabled);
        dest.writeInt(mId);
        dest.writeString8(mName);
        dest.writeString8(mEditIntentAction);
        dest.writeParcelable(mAction, flags);
        dest.writeBoolean(mCanBeDisabled);
        dest.writeTypedList(mConditions);
    }

    private NotificationRule(Parcel in) {
        in.readTypedList(mFilters, Filter.CREATOR);
        mEnabled = in.readBoolean();
        mId = in.readInt();
        mName = in.readString8();
        mEditIntentAction = in.readString8();
        mAction = in.readParcelable(Action.class.getClassLoader(), Action.class);
        mCanBeDisabled = in.readBoolean();
        in.readTypedList(mConditions, Condition.CREATOR);
    }

    private NotificationRule(@NonNull List<Filter> filters,
            boolean enabled, int id, @Nullable String name, @Nullable String editIntentAction,
            @Nullable Action action, boolean canBeDisabled,
            @NonNull List<Condition> conditions) {
        mFilters.addAll(filters);
        mEnabled = enabled;
        mId = id;
        mName = name;
        mEditIntentAction = editIntentAction;
        mAction = action;
        mCanBeDisabled = canBeDisabled;
        mConditions.addAll(conditions);
    }

    @NonNull
    public static final Creator<NotificationRule> CREATOR =
            new Creator<>() {
                @Override
                public NotificationRule createFromParcel(Parcel in) {
                    return new NotificationRule(in);
                }

                @Override
                public NotificationRule[] newArray(int size) {
                    return new NotificationRule[size];
                }
            };

    public static final class Builder {
        private final List<Filter> mFilters = new ArrayList<>();
        private boolean mEnabled = true;
        private int mId;
        private @NonNull String mName;
        private @Nullable String mEditIntentAction;
        private @Nullable Action mAction;
        private boolean mCanBeDisabled = true;
        private final List<Condition> mConditions = new ArrayList<>();

        public Builder(@NonNull NotificationRule rule) {
            mFilters.addAll(rule.getFilters());
            mEnabled = rule.isEnabled();
            mId = rule.getId();
            mName = rule.getName();
            mEditIntentAction = rule.getEditIntentAction();
            mAction = rule.getAction();
            mCanBeDisabled = rule.canBeDisabled();
            mConditions.addAll(rule.getConditions());
        }

        public Builder(@IntRange(from=100, to=125) int mId, @NonNull String name) {
            this.mId = mId;
            this.mName = name;
        }

        /**
         * Sets the filters for this rule.
         */
        @NonNull
        public Builder setFilters(@Nullable List<Filter> filters) {
            mFilters.clear();
            if (filters != null) {
                mFilters.addAll(filters);
            }
            return this;
        }

        /**
         * Adds a filter for this rule.
         * @hide
         */
        @NonNull
        public Builder addFilter(@NonNull NotificationRule.Filter filter) {
            mFilters.add(filter);
            return this;
        }

        /**
         * Sets whether this rule is enabled.
         */
        @NonNull
        public Builder setEnabled(boolean enabled) {
            mEnabled = enabled;
            return this;
        }

        /**
         * Sets the name of this rule.
         */
        @NonNull
        public Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the {@link Intent#getAction()} for the screen where this rule can be edited.
         * @hide
         */
        @TestApi
        @NonNull
        public Builder setEditIntentAction(@Nullable String editIntentAction) {
            mEditIntentAction = editIntentAction;
            return this;
        }

        /**
         * Sets the action that should be applied to notifications that match this rule.
         */
        @NonNull
        public Builder setAction(@NonNull Action action) {
            mAction = action;
            return this;
        }

        /**
         * Sets whether a 'turn off rule' affordance should be displayed in the rule edit UIs.
         * @hide
         */
        @TestApi
        @NonNull
        public Builder setCanBeDisabled(boolean canBeDisabled) {
            mCanBeDisabled = canBeDisabled;
            return this;
        }

        /**
         * Sets the conditions that define when this rule is active.
         */
        @NonNull
        public Builder setConditions(@Nullable List<Condition> conditions) {
            mConditions.clear();
            if (conditions != null) {
                mConditions.addAll(conditions);
            }
            return this;
        }

        /**
         * Adds a conditions that define when this rule is active.
         * @hide
         */
        @NonNull
        public Builder addCondition(@NonNull Condition condition) {
            mConditions.add(condition);
            return this;
        }

        @NonNull
        public NotificationRule build() {
            return new NotificationRule(mFilters, mEnabled, mId, mName, mEditIntentAction,
                    mAction, mCanBeDisabled, mConditions);
        }
    }

    /**
     * Defines when a given rule is active based on the user's context.
     */
    public static final class Condition implements Parcelable {
        /** @hide */
        @IntDef(prefix = {"CONDITION_TYPE_"}, value = {
                CONDITION_TYPE_UNKNOWN, CONDITION_TYPE_LOCATION, CONDITION_TYPE_TIME
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ConditionType {}

        /**
         * The condition type is unknown. This should be treated as 'always active'.
         */
        public static final int CONDITION_TYPE_UNKNOWN = 0;
        /**
         * The rule should be active when the device is at a specific location.
         */
        public static final int CONDITION_TYPE_LOCATION = 1;
        /**
         * The rule should be active on specific days at a specific time.
         */
        public static final int CONDITION_TYPE_TIME = 2;

        private @ConditionType int mConditionType = CONDITION_TYPE_UNKNOWN;

        // for location
        private double mLatitude;
        private double mLongitude;
        private float mRadiusMeters;

        // for time
        private final List<Integer> mDays = new ArrayList<>();
        private int mStartHour;
        private int mStartMinute;
        private int mEndHour;
        private int mEndMinute;

        private Condition(@NonNull List<Integer> days, int startHour, int startMinute,
                int endHour, int endMinute) {
            mConditionType = CONDITION_TYPE_TIME;
            mDays.addAll(days);
            mStartHour = startHour;
            mStartMinute = startMinute;
            mEndHour = endHour;
            mEndMinute = endMinute;
        }

        private Condition(double latitude, double longitude, float radiusMeters) {
            mConditionType = CONDITION_TYPE_LOCATION;
            mLatitude = latitude;
            mLongitude = longitude;
            mRadiusMeters = radiusMeters;
        }

        public static @NonNull Condition createTimeCondition(@NonNull List<Integer> days,
                @IntRange(from = 0, to = 23) int startHour,
                @IntRange(from = 0 , to = 59) int startMinute,
                @IntRange(from = 0, to = 23) int endHour,
                @IntRange(from = 0, to = 59) int endMinute) {
            return new Condition(days, startHour, startMinute, endHour, endMinute);
        }

        public static @NonNull Condition createLocationCondition(double latitude, double longitude,
                float radiusMeters) {
            return new Condition(latitude, longitude, radiusMeters);
        }

        /**
         * @hide
         */
        public static NotificationRule.Condition readXml(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int type = parser.getEventType();
            String tag = parser.getName();
            if (type != XmlPullParser.START_TAG || !CONDITION_TAG.equals(tag)) return null;

            int startHours = 0;
            int startMinutes = 0;
            int endHours = 0;
            int endMinutes = 0;
            List<Integer> days = new ArrayList<>();

            int conditionType = parser.getAttributeInt(
                    null, CONDITION_TYPE_ATTR, CONDITION_TYPE_UNKNOWN);
            type = parser.next();
            tag = parser.getName();
            while (type != XmlPullParser.END_DOCUMENT
                    && !(CONDITION_TAG.equals(tag) && type == XmlPullParser.END_TAG)) {
                tag = parser.getName();
                if (type == XmlPullParser.START_TAG) {
                    if (LOCATION_TAG.equals(tag) && conditionType == CONDITION_TYPE_LOCATION) {
                        return Condition.createLocationCondition(
                                parser.getAttributeDouble(null, LATITUDE_ATTR),
                                parser.getAttributeDouble(null, LONGITUDE_ATTR),
                                parser.getAttributeFloat(null, RADIUS_ATTR));
                    } else if (TIME_TAG.equals(tag) && conditionType == CONDITION_TYPE_TIME) {
                        startHours = parser.getAttributeInt(null, START_HOUR_ATTR, 0);
                        startMinutes = parser.getAttributeInt(null, START_MINUTE_ATTR, 0);
                        endHours = parser.getAttributeInt(null, END_HOUR_ATTR, 0);
                        endMinutes = parser.getAttributeInt(null, END_MINUTE_ATTR, 0);
                    } else if (DAY_TAG.equals(tag) && conditionType == CONDITION_TYPE_TIME) {
                        days.add(parser.getAttributeInt(null, VALUE_ATTR, 0));
                    }
                }
                type = parser.next();
                tag = parser.getName();
            }
            if (conditionType == CONDITION_TYPE_TIME) {
                return Condition.createTimeCondition(
                        days, startHours, startMinutes, endHours, endMinutes);
            }
            return null;
        }

        /**
         * @hide
         */
        public void writeXml(TypedXmlSerializer out) throws IOException {
            out.startTag(null, CONDITION_TAG);
            out.attributeInt(null, CONDITION_TYPE_ATTR, getConditionType());
            switch (getConditionType()) {
                case Condition.CONDITION_TYPE_LOCATION:
                    out.startTag(null, LOCATION_TAG);
                    out.attributeDouble(null, LATITUDE_ATTR, getLatitude());
                    out.attributeDouble(null, LONGITUDE_ATTR, getLongitude());
                    out.attributeFloat(null, RADIUS_ATTR, getRadiusMeters());
                    out.endTag(null, LOCATION_TAG);
                    break;
                case Condition.CONDITION_TYPE_TIME:
                    out.startTag(null, TIME_TAG);
                    out.attributeInt(null, START_HOUR_ATTR, getStartHour());
                    out.attributeInt(null, START_MINUTE_ATTR, getStartMinute());
                    out.attributeInt(null, END_HOUR_ATTR, getEndHour());
                    out.attributeInt(null, END_MINUTE_ATTR, getEndMinute());
                    for (int day : getDays()) {
                        out.startTag(null, DAY_TAG);
                        out.attributeInt(null, VALUE_ATTR, day);
                        out.endTag(null, DAY_TAG);
                    }
                    out.endTag(null, TIME_TAG);
                    break;
                default:
                    break;
            }
            out.endTag(null, CONDITION_TAG);
        }

        /**
         * Returns the type of this condition.
         */
        public @ConditionType int getConditionType() {
            return mConditionType;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_LOCATION} condition, returns the latitude of the
         * center of the geofence circle where this rule applies.
         */
        public double getLatitude() {
            return mLatitude;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_LOCATION} condition, returns the longitude of the
         * center of the geofence circle where this rule applies.
         */
        public double getLongitude() {
            return mLongitude;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_LOCATION} condition, returns the radius of the
         * geofence circle where this rule applies.
         */
        public float getRadiusMeters() {
            return mRadiusMeters;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_TIME} condition, returns the days on which this rule
         * should be active. See {@link java.util.Calendar#DAY_OF_WEEK}.
         */
        public @NonNull List<Integer> getDays() {
            return mDays;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_TIME} condition, returns the start hour from which
         * this rule should be active. See {@link java.util.Calendar#HOUR_OF_DAY}.
         */
        public int getStartHour() {
            return mStartHour;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_TIME} condition, returns the start minute from which
         * this rule should be active. See {@link java.util.Calendar#MINUTE}.
         */
        public int getStartMinute() {
            return mStartMinute;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_TIME} condition, returns the end hour after which
         * this rule should be inactive. See {@link java.util.Calendar#HOUR_OF_DAY}.
         */
        public int getEndHour() {
            return mEndHour;
        }

        /**
         * If this is a {@link #CONDITION_TYPE_TIME} condition, returns the end minute after which
         * this rule should be inactive. See {@link java.util.Calendar#MINUTE}.
         */
        public int getEndMinute() {
            return mEndMinute;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Condition)) return false;
            Condition condition = (Condition) o;
            return mConditionType == condition.mConditionType && Double.compare(mLatitude,
                    condition.mLatitude) == 0 && Double.compare(mLongitude, condition.mLongitude)
                    == 0 && Float.compare(mRadiusMeters, condition.mRadiusMeters) == 0
                    && mStartHour == condition.mStartHour && mStartMinute == condition.mStartMinute
                    && mEndHour == condition.mEndHour && mEndMinute == condition.mEndMinute
                    && Objects.equals(mDays, condition.mDays);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mConditionType, mLatitude, mLongitude, mRadiusMeters, mDays,
                    mStartHour, mStartMinute, mEndHour, mEndMinute);
        }

        @Override
        public String toString() {
            return "Condition{" +
                    "mConditionType=" + mConditionType +
                    ", mLatitude=" + mLatitude +
                    ", mLongitude=" + mLongitude +
                    ", mRadiusMeters=" + mRadiusMeters +
                    ", mDays=" + mDays +
                    ", mStartHour=" + mStartHour +
                    ", mStartMinute=" + mStartMinute +
                    ", mEndHour=" + mEndHour +
                    ", mEndMinute=" + mEndMinute +
                    '}';
        }

        private Condition(Parcel in) {
            mConditionType = in.readInt();
            mLatitude = in.readDouble();
            mLongitude = in.readDouble();
            mRadiusMeters = in.readFloat();
            mDays.addAll(in.readArrayList(Integer.class.getClassLoader(), Integer.class));
            mStartHour = in.readInt();
            mStartMinute = in.readInt();
            mEndHour = in.readInt();
            mEndMinute = in.readInt();
        }

        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mConditionType);
            dest.writeDouble(mLatitude);
            dest.writeDouble(mLongitude);
            dest.writeFloat(mRadiusMeters);
            dest.writeList(mDays);
            dest.writeInt(mStartHour);
            dest.writeInt(mStartMinute);
            dest.writeInt(mEndHour);
            dest.writeInt(mEndMinute);
        }

        @NonNull
        public static final Creator<Condition> CREATOR =
                new Creator<>() {
                    @Override
                    public Condition createFromParcel(Parcel in) {
                        return new Condition(in);
                    }

                    @Override
                    public Condition[] newArray(int size) {
                        return new Condition[size];
                    }
                };
    }

    public static final class Action implements Parcelable {
        /** @hide */
        @IntDef(prefix = {"PRIMARY_ACTION_"}, value = {
                PRIMARY_ACTION_NONE, PRIMARY_ACTION_HIGHLIGHT, PRIMARY_ACTION_DEFAULT,
                PRIMARY_ACTION_LOW, PRIMARY_ACTION_BUNDLE, PRIMARY_ACTION_BLOCK
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface PrimaryAction {}

        /**
         * No primary action should be applied to notifications that match this rule's filter.
         * Specific behavioral overrides may still apply.
         */
        public static final int PRIMARY_ACTION_NONE = 0;
        /**
         * Notifications that match this filter should be highlighted. This means they will have
         * elevated audible and visual alerts.
         */
        public static final int PRIMARY_ACTION_HIGHLIGHT = 1;
        /**
         * Notifications that match this filter should have their importance set to
         * {@link NotificationManager#IMPORTANCE_DEFAULT}.
         */
        public static final int PRIMARY_ACTION_DEFAULT = 2;
        /**
         * Notifications that match this filter should have their importance set to
         * {@link NotificationManager#IMPORTANCE_LOW}.
         */
        public static final int PRIMARY_ACTION_LOW = 3;
        /**
         * Notifications that match this filter should be classified into a bundle.
         */
        public static final int PRIMARY_ACTION_BUNDLE = 4;
        /**
         * Notifications that match this filter should be blocked (not posted).
         */
        public static final int PRIMARY_ACTION_BLOCK = 5;

        private @PrimaryAction int mPrimaryAction = PRIMARY_ACTION_NONE;
        private final @Nullable Uri mSoundHapticOverride;
        private @ColorInt int mLightColor = 0;
        private final List<String> mModeBreakthroughs = new ArrayList<>();
        private final @Nullable String mBundleName;
        private final @Nullable String mEmojiIcon;

        /**
         * Returns the primary action for this rule.
         */
        public @PrimaryAction int getPrimaryAction() {
            return mPrimaryAction;
        }

        /**
         * If non-null, notifications that match this rule's filtering conditions should use this
         * sound and haptics.
         */
        public @Nullable Uri getSoundHapticOverride() {
            return mSoundHapticOverride;
        }

        /**
         * If non-negative, notifications that match this rule's filtering conditions should trigger
         * the notification light with this color.
         */
        public @ColorInt int getLightColorOverride() {
            return mLightColor;
        }

        /**
         * If non-null and non-empty, notifications that match this rule's filtering conditions
         * should bypass the modes in this list.
         */
        public @NonNull List<String> getModeBreakthroughIds() {
            return mModeBreakthroughs;
        }

        /**
         * If this Action's primary action is {@link #PRIMARY_ACTION_BUNDLE}, notifications that
         * match this rule's filtering conditions should be placed in a bundle with this name.
         */
        public @Nullable String getDynamicBundleName() {
            return mBundleName;
        }

        /**
         * If this Action's primary action is {@link #PRIMARY_ACTION_BUNDLE}, notifications that
         * match this rule's filtering conditions should be placed in a bundle with this icon.
         */
        public @Nullable String getDynamicBundleEmojiIcon() {
            return mEmojiIcon;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private Action(int primaryAction, @Nullable Uri soundHapticOverride, int lightColor,
                @NonNull List<String> modeBreakthroughs, @Nullable String bundleName,
                @Nullable String emojiIcon) {
            mPrimaryAction = primaryAction;
            mSoundHapticOverride = soundHapticOverride;
            mLightColor = lightColor;
            mModeBreakthroughs.addAll(modeBreakthroughs);
            mBundleName = bundleName;
            mEmojiIcon = emojiIcon;
        }

        private Action(Parcel in) {
            mPrimaryAction = in.readInt();
            if (in.readByte() != 0) {
                mSoundHapticOverride = Uri.CREATOR.createFromParcel(in);
            } else {
                mSoundHapticOverride = null;
            }
            mLightColor = in.readInt();
            if (in.readByte() != 0) {
                in.readStringList(mModeBreakthroughs);
            }
            if (in.readByte() != 0) {
                mBundleName = in.readString8();
            } else {
                mBundleName = null;
            }
            if (in.readByte() != 0) {
                mEmojiIcon = in.readString8();
            } else {
                mEmojiIcon = null;
            }
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mPrimaryAction);
            if (mSoundHapticOverride != null) {
                dest.writeByte((byte) 1);
                mSoundHapticOverride.writeToParcel(dest, flags);
            } else {
                dest.writeByte((byte) 0);
            }
            dest.writeInt(mLightColor);
            if (!mModeBreakthroughs.isEmpty()) {
                dest.writeByte((byte) 1);
                dest.writeStringList(mModeBreakthroughs);
            } else {
                dest.writeByte((byte) 0);
            }
            if (mBundleName != null) {
                dest.writeByte((byte) 1);
                dest.writeString8(mBundleName);
            } else {
                dest.writeByte((byte) 0);
            }
            if (mEmojiIcon != null) {
                dest.writeByte((byte) 1);
                dest.writeString8(mEmojiIcon);
            } else {
                dest.writeByte((byte) 0);
            }
        }

        @NonNull
        public static final Creator<Action> CREATOR =
                new Creator<>() {
                    @Override
                    public Action createFromParcel(Parcel in) {
                        return new Action(in);
                    }

                    @Override
                    public Action[] newArray(int size) {
                        return new Action[size];
                    }
                };

        /**
         * @hide
         */
        public static NotificationRule.Action readXml(TypedXmlPullParser parser, boolean forRestore,
                @Nullable Context context) throws XmlPullParserException, IOException {
            int type = parser.getEventType();
            if (type != XmlPullParser.START_TAG) return null;
            String tag = parser.getName();
            if (!ACTION_TAG.equals(tag)) return null;

            int primaryAction = parser.getAttributeInt(
                    null, PRIMARY_ACTION_ATTR, PRIMARY_ACTION_NONE);
            NotificationRule.Action.Builder builder = new Builder(primaryAction);
            builder.setLightColorOverride(parser.getAttributeInt(null, LIGHT_COLOR_ATTR, 0));
            String soundOverride = parser.getAttributeValue(null, SOUND_ATTR);
            if (!TextUtils.isEmpty(soundOverride)) {
                if (forRestore) {
                    Uri sound = NotificationSoundCanonicalizer.restoreSoundUri(
                            context, Uri.parse(soundOverride), true,
                            AudioAttributes.USAGE_NOTIFICATION, false).first;
                    builder.setSoundHapticOverride(sound);
                } else {
                    builder.setSoundHapticOverride(Uri.parse(soundOverride));
                }
            }
            builder.setDynamicBundleEmojiIcon(parser.getAttributeValue(
                    null, DYNAMIC_BUNDLE_EMOJI_ICON_ATTR));
            builder.setDynamicBundleName(parser.getAttributeValue(null, DYNAMIC_BUNDLE_NAME_ATTR));
            String modesList = parser.getAttributeValue(null, MODE_BREAKTHROUGH_ATTR);
            if (!TextUtils.isEmpty(modesList)) {
                String[] modes = modesList.split(DELIMITER);
                builder.setModeBreakthroughIds(Arrays.asList(modes));
            }
            return builder.build();
        }

        /**
         * @hide
         */
        public void writeXml(TypedXmlSerializer out, boolean forBackup,
                @Nullable Context context) throws IOException {
            out.startTag(null, ACTION_TAG);
            out.attribute(null, PRIMARY_ACTION_ATTR, String.valueOf(getPrimaryAction()));
            Uri actionSoundHapticsUri = getSoundHapticOverride();
            if (actionSoundHapticsUri != null && forBackup) {
                actionSoundHapticsUri = NotificationSoundCanonicalizer.getSoundForBackup(
                        context, getSoundHapticOverride());
            }
            if (actionSoundHapticsUri != null) {
                out.attribute(null, SOUND_ATTR, actionSoundHapticsUri.toString());
            }
            out.attribute(null, LIGHT_COLOR_ATTR, String.valueOf(getLightColorOverride()));
            out.attribute(null, MODE_BREAKTHROUGH_ATTR,
                    String.join(DELIMITER, getModeBreakthroughIds()));
            if (getDynamicBundleName() != null) {
                out.attribute(null, DYNAMIC_BUNDLE_NAME_ATTR, getDynamicBundleName());
            }
            if (getDynamicBundleEmojiIcon() != null) {
                out.attribute(null, DYNAMIC_BUNDLE_EMOJI_ICON_ATTR, getDynamicBundleEmojiIcon());
            }
            out.endTag(null, ACTION_TAG);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Action)) return false;
            Action action = (Action) o;
            return mPrimaryAction == action.mPrimaryAction && mLightColor == action.mLightColor
                    && Objects.equals(mSoundHapticOverride, action.mSoundHapticOverride)
                    && Objects.equals(mModeBreakthroughs, action.mModeBreakthroughs)
                    && Objects.equals(mBundleName, action.mBundleName)
                    && Objects.equals(mEmojiIcon, action.mEmojiIcon);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPrimaryAction, mSoundHapticOverride, mLightColor,
                    mModeBreakthroughs, mBundleName, mEmojiIcon);
        }

        @Override
        public String toString() {
            return "Action{" +
                    "mPrimaryAction=" + mPrimaryAction +
                    ", mSoundHapticOverride=" + mSoundHapticOverride +
                    ", mLightColor=" + mLightColor +
                    ", mModeBreakthroughs=" + mModeBreakthroughs +
                    ", mBundleName='" + mBundleName + '\'' +
                    ", mEmojiIcon='" + mEmojiIcon + '\'' +
                    '}';
        }

        public static final class Builder {
            private @PrimaryAction int mPrimaryAction = PRIMARY_ACTION_NONE;
            private Uri mSoundHapticOverride;
            private @ColorInt int mLightColor = 0;
            private final List<String> mModeBreakthroughs = new ArrayList<>();
            private String mBundleName;
            private String mEmojiIcon;

            public Builder(@NonNull Action action) {
                this.mPrimaryAction = action.getPrimaryAction();
                this.mSoundHapticOverride = action.getSoundHapticOverride();
                this.mLightColor = action.getLightColorOverride();
                this.mModeBreakthroughs.addAll(action.getModeBreakthroughIds());
                this.mBundleName = action.getDynamicBundleName();
                this.mEmojiIcon = action.getDynamicBundleEmojiIcon();
            }

            public Builder(@PrimaryAction int primaryAction) {
                mPrimaryAction = primaryAction;
            }

            /**
             * Sets the light color for the lights that should flash when a notification
             * meets this rule's filter conditions.
             * <p>Set to null if this rule should not override the notification's
             * inherent 'lights' behavior (see {@link NotificationChannel#enableLights(boolean)}
             * and {@link NotificationChannel#getLightColor()}).
             */
            public @NonNull Builder setLightColorOverride(@ColorInt int lightColor) {
                mLightColor = lightColor;
                return this;
            }

            /**
             * Sets the uri for the sound and haptics that should play when a notification
             * meets this rule's filter conditions.
             * <p>Set to null if this rule should not override the notification's inherent
             * 'sound and haptics' behavior (see {@link NotificationChannel#getSound()} and
             * {@link NotificationChannel#getVibrationEffect()}).
             */
            public @NonNull Builder setSoundHapticOverride(@Nullable Uri soundHapticOverride) {
                mSoundHapticOverride = soundHapticOverride;
                return this;
            }

            /**
             * Sets the list of {@link ContextualMode#getId() modes} that can be
             * bypassed when a notification meets this rule's filter conditions.
             * <p>Set to null if these notifications should not break through any modes due to this
             * rule.
             */
            public @NonNull Builder setModeBreakthroughIds(
                    @Nullable List<String> modeBreakthroughIds) {
                mModeBreakthroughs.clear();
                if (modeBreakthroughIds != null) {
                    mModeBreakthroughs.addAll(modeBreakthroughIds);
                }
                return this;
            }

            /**
             * If the primary action for this rule is {@link #PRIMARY_ACTION_BUNDLE}, sets the name
             * for the bundle.
             * <p>This has no effect if the primary action is not {@link #PRIMARY_ACTION_BUNDLE}. If
             * the primary action is {@link #PRIMARY_ACTION_BUNDLE} and no bundle name is specified,
             * a default name will be shown.
             */
            public @NonNull Builder setDynamicBundleName(@Nullable String name) {
                mBundleName = name;
                return this;
            }

            /**
             * If the primary action for this rule is {@link #PRIMARY_ACTION_BUNDLE}, sets the emoji
             * icon for the bundle.
             */
            public @NonNull Builder setDynamicBundleEmojiIcon(@Nullable String emojiIcon) {
                mEmojiIcon = emojiIcon;
                return this;
            }

            public @NonNull Action build() {
                return new Action(mPrimaryAction, mSoundHapticOverride, mLightColor,
                        mModeBreakthroughs, mBundleName, mEmojiIcon);
            }
        }
    }

    /**
     * Criteria for which notifications this rule applies to.
     */
    public static final class Filter implements Parcelable {
        /** @hide */
        @IntDef(prefix = {"CONTACT_LEVEL_"}, value = {
                CONTACT_LEVEL_ANY, CONTACT_LEVEL_CONTACT, CONTACT_LEVEL_STARRED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ContactLevel {}

        /** @hide */
        @IntDef(prefix = {"CONVERSATION_LEVEL_"}, value = {
                CONVERSATION_LEVEL_ANY, CONVERSATION_LEVEL_PRIORITY,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ConversationLevel {}

        /**
         * Do not filter on the contact level of a notification.
         */
        public static final int CONTACT_LEVEL_ANY = 0;
        /**
         * Messaging notification is from a contact.
         */
        public static final int CONTACT_LEVEL_CONTACT = 1 ;
        /**
         * Messaging notification is from a starred contact.
         */
        public static final int CONTACT_LEVEL_STARRED = 2;
        /**
         * Do not filter on the conversation level of a notification.
         */
        public static final int CONVERSATION_LEVEL_ANY = 0;
        /**
         * Messaging notification is from a priority conversation.
         */
        public static final int CONVERSATION_LEVEL_PRIORITY = 1;

        final List<Integer> mIncludedPackages = new ArrayList<>();
        final List<Integer> mExcludedPackages = new ArrayList<>();
        int mContactLevel = CONTACT_LEVEL_ANY;
        int mConversationLevel = CONVERSATION_LEVEL_ANY;
        final List<Uri> mContacts = new ArrayList<>();
        final List<String> mShortcutIds = new ArrayList<>();
        final List<String> mKeywords = new ArrayList<>();
        final List<UserHandle> mUsers = new ArrayList<>();
        final List<String> mCategories = new ArrayList<>();
        final List<Integer> mStaticBundleTypes = new ArrayList<>();
        int mFlags = 0;

        private Filter(List<Integer> includedPackages, List<Integer> excludedPackages,
                int contactLevel, int conversationLevel, List<Uri> contacts,
                List<String> shortcutIds,
                List<String> keywords, List<UserHandle> users,
                List<String> categories, List<Integer> staticBundleTypes, int flags) {
            mIncludedPackages.addAll(includedPackages);
            mExcludedPackages.addAll(excludedPackages);
            mContactLevel = contactLevel;
            mConversationLevel = conversationLevel;
            mContacts.addAll(contacts);
            mShortcutIds.addAll(shortcutIds);
            mKeywords.addAll(keywords);
            mUsers.addAll(users);
            mCategories.addAll(categories);
            mStaticBundleTypes.addAll(staticBundleTypes);
            mFlags = flags;
        }

        /**
         * Returns the list of package UIDs that this rule applies to.
         * If empty, this rule applies to all packages (unless excluded).
         * <p>Compare to {@link StatusBarNotification#getUid()}.
         */
        public @NonNull List<Integer> getIncludedPackageUids() {
            return mIncludedPackages;
        }

        /**
         * Returns the list of package UIDs that this rule does NOT apply to.
         * <p>Compare to {@link StatusBarNotification#getUid()}.
         */
        public @NonNull List<Integer> getExcludedPackageUids() {
            return mExcludedPackages;
        }

        /**
         * Returns the contact level required for this rule to apply.
         * <p>Compare to {@link Notification#EXTRA_PEOPLE_LIST} and
         * {@link Notification#EXTRA_PEOPLE}.
         */
        public @ContactLevel int getContactLevel() {
            return mContactLevel;
        }

        /**
         * Returns the conversation level required for this rule to apply.
         * <p>Compare to {@link NotificationChannel#isImportantConversation()}.
         */
        public @ConversationLevel int getConversationLevel() {
            return mConversationLevel;
        }

        /**
         * Returns the list of specific contacts that this rule applies to.
         */
        public @NonNull List<Uri> getContacts() {
            return mContacts;
        }

        /**
         * Returns the list of shortcut IDs that this rule applies to.
         * <p>Compare to {@link Notification#getShortcutId()}.
         */
        public @NonNull List<String> getShortcutIds() {
            return mShortcutIds;
        }

        /**
         * Returns the list of keywords to match against notification text.
         */
        public @NonNull List<String> getKeywords() {
            return mKeywords;
        }

        /**
         * Returns the list of users that this rule applies to.
         * If empty, this rule applies to the current user and all of its profiles.
         * <p>Compare to {@link StatusBarNotification#getUser()}.
         */
        public @NonNull List<UserHandle> getUsers() {
            return mUsers;
        }

        /**
         * Returns the list of {@link Notification#category categories} that this rule applies to.
         * <p>Compare to {@link Notification#category}.
         */
        public @NonNull List<String> getCategories() {
            return mCategories;
        }

        /**
         * Returns the list of static bundle types that this rule applies to.
         * See {@link android.service.notification.Adjustment#TYPE_NEWS},
         * {@link android.service.notification.Adjustment#TYPE_CONTENT_RECOMMENDATION},
         * {@link android.service.notification.Adjustment#TYPE_PROMOTION},
         * {@link android.service.notification.Adjustment#TYPE_SOCIAL_MEDIA}.
         */
        public @NonNull List<Integer> getStaticBundleTypes() {
            return mStaticBundleTypes;
        }

        /**
         * Returns the {@link Notification#flags flags mask} that this rule applies to.
         * <p>Compare to {@link Notification#flags}.
         */
        public @Notification.NotificationFlags int getFlags() {
            return mFlags;
        }

        @Override
        public String toString() {
            return "Filter{" +
                    "mIncludedPackages=" + mIncludedPackages +
                    ", mExcludedPackages=" + mExcludedPackages +
                    ", mContactLevel=" + mContactLevel +
                    ", mConversationLevel=" + mConversationLevel +
                    ", mContacts=" + mContacts +
                    ", mShortcutIds=" + mShortcutIds +
                    ", mKeywords=" + mKeywords +
                    ", mUsers=" + mUsers +
                    ", mCategories=" + mCategories +
                    ", mStaticBundleTypes=" + mStaticBundleTypes +
                    ", mFlags=" + mFlags +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Filter)) return false;
            Filter filter = (Filter) o;
            return mContactLevel == filter.mContactLevel
                    && mConversationLevel == filter.mConversationLevel
                    && mFlags == filter.mFlags
                    && Objects.equals(mIncludedPackages, filter.mIncludedPackages)
                    && Objects.equals(mExcludedPackages, filter.mExcludedPackages)
                    && Objects.equals(mContacts, filter.mContacts) && Objects.equals(
                    mShortcutIds, filter.mShortcutIds) && Objects.equals(mKeywords,
                    filter.mKeywords) && Objects.equals(mUsers, filter.mUsers)
                    && Objects.equals(mCategories, filter.mCategories)
                    && Objects.equals(mStaticBundleTypes, filter.mStaticBundleTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIncludedPackages, mExcludedPackages, mContactLevel,
                    mConversationLevel, mContacts, mShortcutIds, mKeywords, mUsers,
                    mCategories, mStaticBundleTypes, mFlags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Creator<Filter> CREATOR =
                new Creator<>() {
                    @Override
                    public Filter createFromParcel(Parcel in) {
                        return new Filter(in);
                    }

                    @Override
                    public Filter[] newArray(int size) {
                        return new Filter[size];
                    }
                };

        private Filter(Parcel in) {
            mIncludedPackages.addAll(in.readArrayList(null, Integer.class));
            mExcludedPackages.addAll(in.readArrayList(null, Integer.class));
            mContactLevel = in.readInt();
            mConversationLevel = in.readInt();
            mContacts.addAll(in.readArrayList(Uri.class.getClassLoader(), Uri.class));
            in.readStringList(mShortcutIds);
            mKeywords.addAll(in.readArrayList(null, String.class));
            mUsers.addAll(in.readArrayList(UserHandle.class.getClassLoader(), UserHandle.class));
            in.readStringList(mCategories);
            mStaticBundleTypes .addAll(in.readArrayList(null, Integer.class));
            mFlags = in.readInt();
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeList(mIncludedPackages);
            dest.writeList(mExcludedPackages);
            dest.writeInt(mContactLevel);
            dest.writeInt(mConversationLevel);
            dest.writeList(mContacts);
            dest.writeStringList(mShortcutIds);
            dest.writeList(mKeywords);
            dest.writeList(mUsers);
            dest.writeStringList(mCategories);
            dest.writeList(mStaticBundleTypes);
            dest.writeInt(mFlags);
        }

        /**
         * @hide
         */
        public static NotificationRule.Filter readXml(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int type = parser.getEventType();
            String tag = parser.getName();
            if (type != XmlPullParser.START_TAG || !FILTER_TAG.equals(tag)) return null;
            NotificationRule.Filter.Builder builder = new NotificationRule.Filter.Builder();

            type = parser.next();
            tag = parser.getName();
            while (type != XmlPullParser.END_DOCUMENT
                    && !(FILTER_TAG.equals(tag) && type == XmlPullParser.END_TAG)) {
                tag = parser.getName();
                if (type == XmlPullParser.START_TAG) {
                    if (INCLUDED_PACKAGES_TAG.equals(tag)) {
                        builder.addIncludedPackageUid(parser.getAttributeInt(null, VALUE_ATTR, 0));
                    } else if (EXCLUDED_PACKAGES_TAG.equals(tag)) {
                        builder.addExcludedPackageUid(parser.getAttributeInt(null, VALUE_ATTR, 0));
                    } else if (SHORTCUT_ID_TAG.equals(tag)) {
                        builder.addShortcutId(parser.getAttributeValue(null, VALUE_ATTR));
                    } else if (KEYWORD_TAG.equals(tag)) {
                        builder.addKeyword(parser.getAttributeValue(null, VALUE_ATTR));
                    } else if (CONTACTS_TAG.equals(tag)) {
                        builder.setConversationLevel(parser.getAttributeInt(
                                null, CONVERSATION_LEVEL_ATTR, CONVERSATION_LEVEL_ANY));
                        builder.setContactLevel(parser.getAttributeInt(
                                null, CONTACT_LEVEL_ATTR, CONTACT_LEVEL_ANY));
                    } else if (CONTACT_TAG.equals(tag)) {
                        builder.addContact(Uri.parse(parser.getAttributeValue(null, VALUE_ATTR)));
                    } else if (USER_TAG.equals(tag)) {
                        builder.addUser(UserHandle.of(parser.getAttributeInt(null, VALUE_ATTR, 0)));
                    } else if (CATEGORY_TAG.equals(tag)) {
                        builder.addCategory(parser.getAttributeValue(null, VALUE_ATTR));
                    } else if (STATIC_BUNDLE_TYPE_TAG.equals(tag)) {
                        builder.addStaticBundleType(parser.getAttributeInt(null, VALUE_ATTR, 0));
                    } else if (FLAGS_TAG.equals(tag)) {
                        builder.setFlags(parser.getAttributeInt(null, VALUE_ATTR, 0));
                    }
                }
                type = parser.next();
                tag = parser.getName();
            }

            return builder.build();
        }

        /**
         * @hide
         */
        public void writeXml(TypedXmlSerializer out) throws IOException {
            out.startTag(null, FILTER_TAG);
            for (int uid : getIncludedPackageUids()) {
                out.startTag(null, INCLUDED_PACKAGES_TAG);
                out.attributeInt(null, VALUE_ATTR, uid);
                out.endTag(null, INCLUDED_PACKAGES_TAG);
            }
            for (int uid : getExcludedPackageUids()) {
                out.startTag(null, EXCLUDED_PACKAGES_TAG);
                out.attributeInt(null, VALUE_ATTR, uid);
                out.endTag(null, EXCLUDED_PACKAGES_TAG);
            }
            for (String shortcutId : getShortcutIds()) {
                out.startTag(null, SHORTCUT_ID_TAG);
                out.attribute(null, VALUE_ATTR, shortcutId);
                out.endTag(null, SHORTCUT_ID_TAG);
            }
            for (String category : getCategories()) {
                out.startTag(null, CATEGORY_TAG);
                out.attribute(null, VALUE_ATTR, category);
                out.endTag(null, CATEGORY_TAG);
            }
            for (String keyword : getKeywords()) {
                out.startTag(null, KEYWORD_TAG);
                out.attribute(null, VALUE_ATTR, keyword);
                out.endTag(null, KEYWORD_TAG);
            }
            out.startTag(null, CONTACTS_TAG);
            out.attributeInt(null, CONTACT_LEVEL_ATTR, getContactLevel());
            out.attributeInt(null, CONVERSATION_LEVEL_ATTR, getConversationLevel());
            for (Uri contact : getContacts()) {
                out.startTag(null, CONTACT_TAG);
                out.attribute(null, VALUE_ATTR, contact.toString());
                out.endTag(null, CONTACT_TAG);
            }
            out.endTag(null, CONTACTS_TAG);

            for (int staticBundleType : getStaticBundleTypes()) {
                out.startTag(null, STATIC_BUNDLE_TYPE_TAG);
                out.attributeInt(null, VALUE_ATTR, staticBundleType);
                out.endTag(null, STATIC_BUNDLE_TYPE_TAG);
            }
            out.startTag(null, FLAGS_TAG);
            out.attributeInt(null, VALUE_ATTR, getFlags());
            out.endTag(null, FLAGS_TAG);

            for (UserHandle user : getUsers()) {
                out.startTag(null, USER_TAG);
                out.attributeInt(null, VALUE_ATTR, user.getIdentifier());
                out.endTag(null, USER_TAG);
            }
            out.endTag(null, FILTER_TAG);
        }

        public static final class Builder {
            final List<Integer> mIncludedPackages = new ArrayList<>();
            final List<Integer> mExcludedPackages = new ArrayList<>();
            int mContactLevel = CONTACT_LEVEL_ANY;
            int mConversationLevel = CONVERSATION_LEVEL_ANY;
            final List<Uri> mContacts = new ArrayList<>();
            final List<String> mShortcutIds  = new ArrayList<>();
            final List<String> mKeywords = new ArrayList<>();
            final List<UserHandle> mUsers = new ArrayList<>();
            final List<String> mCategories = new ArrayList<>();
            final List<Integer> mStaticBundleTypes = new ArrayList<>();
            int mFlags = 0;

            public Builder() {}

            public Builder(@NonNull Filter filter) {
                mIncludedPackages.addAll(filter.getIncludedPackageUids());
                mExcludedPackages.addAll(filter.getExcludedPackageUids());
                mContactLevel = filter.getContactLevel();
                mConversationLevel = filter.getConversationLevel();
                mContacts.addAll(filter.getContacts());
                mShortcutIds.addAll(filter.getShortcutIds());
                mKeywords.addAll(filter.getKeywords());
                mUsers.addAll(filter.getUsers());
                mCategories.addAll(filter.getCategories());
                mStaticBundleTypes.addAll(filter.getStaticBundleTypes());
                mFlags = filter.getFlags();
            }

            /**
             * Apply this rule only to notifications that are sent from any of these packages.
             * <p>Compare to {@link StatusBarNotification#getUid()}.
             * <p>If unspecified this rule will apply to all packages.
             * @param packageList A set of package uids
             */
            @NonNull
            public Builder setIncludedPackageUids(@Nullable List<Integer> packageList) {
                mIncludedPackages.clear();
                if (packageList != null) {
                    mIncludedPackages.addAll(packageList);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addIncludedPackageUid(int packageUid) {
                mIncludedPackages.add(packageUid);
                return this;
            }

            /**
             * Do not apply this rule to any notifications that are sent from any of these packages
             * <p>Compare to {@link StatusBarNotification#getUid()}.
             * <p>If unspecified this rule will apply to all packages.
             * @param packageList A set of package uids
             */
            @NonNull
            public Builder setExcludedPackageUids(@Nullable List<Integer> packageList) {
                mExcludedPackages.clear();
                if (packageList != null) {
                    mExcludedPackages.addAll(packageList);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addExcludedPackageUid(int packageUid) {
                mExcludedPackages.add(packageUid);
                return this;
            }

            /**
             * Apply this rule to any notification that is from a contact that matches this level.
             * <p>Compare to {@link Notification#EXTRA_PEOPLE_LIST} and
             * {@link Notification#EXTRA_PEOPLE}.
             * @param contactLevel A contact level that this rule should apply to
             */
            @NonNull
            public Builder setContactLevel(@ContactLevel int contactLevel) {
                mContactLevel = contactLevel;
                return this;
            }

            /**
             * Apply this rule to any notification that is from a conversation that matches this
             * level.
             * <p>Compare to {@link NotificationChannel#isImportantConversation()}.
             * @param conversationLevel A conversation level that this rule should apply to
             */
            @NonNull
            public Builder setConversationLevel(@ConversationLevel int conversationLevel) {
                mConversationLevel = conversationLevel;
                return this;
            }

            /**
             * Apply this rule to any notification that is from one of these contacts.
             * <p>Compare to {@link Notification#EXTRA_PEOPLE_LIST} and
             * {@link Notification#EXTRA_PEOPLE}.
             * @param contactList A set of contacts in the contact database that this rule should
             *                    apply to.
             */
            @NonNull
            public Builder setContacts(@Nullable List<Uri> contactList) {
                mContacts.clear();
                if (contactList != null) {
                    mContacts.addAll(contactList);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addContact(@NonNull Uri contact) {
                mContacts.add(contact);
                return this;
            }

            /**
             * Apply this rule to any notification that is affiliated with one of these shortcuts
             * <p>Compare to {@link Notification#getShortcutId()}.
             * @param shortcutIds A set of {@link ShortcutInfo#getId() shortcut ids} that this rule
             *                    should apply to.
             */
            @NonNull
            public Builder setShortcutIds(@Nullable List<String> shortcutIds) {
                mShortcutIds.clear();
                if (shortcutIds != null) {
                    mShortcutIds.addAll(shortcutIds);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addShortcutId(@NonNull String shortcutId) {
                mShortcutIds.add(shortcutId);
                return this;
            }

            /**
             * Apply this rule to any notification whose visible text matches any of these keywords.
             * @param keywords A list of keywords to match against notification text
             */
            @NonNull
            public Builder setKeywords(@Nullable List<String> keywords) {
                mKeywords.clear();
                if (keywords != null) {
                    mKeywords.addAll(keywords);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addKeyword(@NonNull String keyword) {
                mKeywords.add(keyword);
                return this;
            }

            /**
             * Apply this rule to notifications from the provided users.
             * <p>Compare to {@link StatusBarNotification#getUser()}.
             * @param users Zero of more users from the set of [current user,
             *              current user's profile(s)]
             */
            @NonNull
            public Builder setUsers(@Nullable List<UserHandle> users) {
                mUsers.clear();
                if (users != null) {
                    mUsers.addAll(users);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addUser(@NonNull UserHandle user) {
                mUsers.add(user);
                return this;
            }

            /**
             * Apply this rule to notifications that match one of the provided categories
             * <p>Compare to {@link Notification#category}.
             * @param categories A set of categories that this rule should apply to
             */
            @NonNull
            public Builder setCategories(@Nullable List<String> categories) {
                mCategories.clear();
                if (categories != null) {
                    mCategories.addAll(categories);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addCategory(@NonNull String category) {
                mCategories.add(category);
                return this;
            }

            /**
             * Apply this rule to notifications whose content matches one of these classification
             * types. See {@link android.service.notification.Adjustment#TYPE_NEWS},
             * {@link android.service.notification.Adjustment#TYPE_CONTENT_RECOMMENDATION},
             * {@link android.service.notification.Adjustment#TYPE_PROMOTION},
             * {@link android.service.notification.Adjustment#TYPE_SOCIAL_MEDIA}.
             */
            @NonNull
            public Builder setStaticBundleTypes(@Nullable List<Integer> bundleTypes) {
                mStaticBundleTypes.clear();
                if (bundleTypes != null) {
                    mStaticBundleTypes.addAll(bundleTypes);
                }
                return this;
            }

            /**
             * @hide
             */
            @NonNull
            public Builder addStaticBundleType(int bundleType) {
                mStaticBundleTypes.add(bundleType);
                return this;
            }

            /**
             * Apply this rule to notifications that match all the provided flags.
             * <p>Compare to {@link Notification#flags}.
             * @param flagMask A mask of flags that this rule should apply to
             */
            @NonNull
            public Builder setFlags(int flagMask) {
                mFlags = flagMask;
                return this;
            }

            @NonNull
            public Filter build() {
                return new Filter(mIncludedPackages, mExcludedPackages, mContactLevel,
                        mConversationLevel, mContacts, mShortcutIds, mKeywords,
                        mUsers, mCategories, mStaticBundleTypes, mFlags);
            }
        }
    }
}
