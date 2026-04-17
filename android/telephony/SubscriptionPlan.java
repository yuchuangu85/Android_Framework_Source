/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telephony;

import android.annotation.BytesLong;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.NetworkType;
import android.util.Range;
import android.util.RecurrenceRule;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Description of a billing relationship plan between a carrier and a specific
 * subscriber. This information is used to present more useful UI to users, such
 * as explaining how much mobile data they have remaining, and what will happen
 * when they run out.
 * <p>
 * If specifying network types, the developer must supply at least one plan
 * that applies to all network types (default), and all additional plans
 * may not include a particular network type more than once.
 * This is enforced by {@link SubscriptionManager} when setting the plans.
 * <p>
 * Plan selection will prefer plans that have specific network types defined
 * over plans that apply to all network types.
 *
 * @see SubscriptionManager#setSubscriptionPlans(int, java.util.List)
 * @see SubscriptionManager#getSubscriptionPlans(int)
 */
public final class SubscriptionPlan implements Parcelable {
    /** @hide */
    @IntDef(prefix = "LIMIT_BEHAVIOR_", value = {
            LIMIT_BEHAVIOR_UNKNOWN,
            LIMIT_BEHAVIOR_DISABLED,
            LIMIT_BEHAVIOR_BILLED,
            LIMIT_BEHAVIOR_THROTTLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LimitBehavior {}

    /**
     * Indicates that the behavior for when a data limit is reached is unknown.
     * <p>
     * This is the default value and should be used when the carrier has not specified what
     * happens when the user's data usage exceeds the limit.
     */
    public static final int LIMIT_BEHAVIOR_UNKNOWN = -1;

    /**
     * Indicates that data access is disabled when the data limit is reached.
     * <p>
     * Once the user's data usage hits the defined limit, their mobile data connection will be
     * turned off until the next billing cycle begins.
     */
    public static final int LIMIT_BEHAVIOR_DISABLED = 0;

    /**
     * Indicates that the user will be billed for data usage beyond the limit.
     * <p>
     * When the user exceeds their data limit, they will incur overage charges. Data access
     * continues, but at an additional cost.
     */
    public static final int LIMIT_BEHAVIOR_BILLED = 1;

    /**
     * Indicates that data access is throttled to a slower speed when the limit is reached.
     * <p>
     * After the user consumes their high-speed data allowance, the data connection remains
     * active but is reduced to a lower bandwidth for the remainder of the billing cycle.
     */
    public static final int LIMIT_BEHAVIOR_THROTTLED = 2;

    /**
     * Value indicating a number of bytes is unknown.
     * <p>
     * This value is used when the carrier has not provided a specific data limit or usage value.
     */
    public static final long BYTES_UNKNOWN = -1;

    /**
     * Value indicating a number of bytes is unlimited.
     * <p>
     * This value is used when the carrier has not specified a data limit.
     */
    public static final long BYTES_UNLIMITED = Long.MAX_VALUE;

    /** Value indicating data rate is unknown. */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final long BITRATE_UNKNOWN = -1;

    /** Value indicating a timestamp is unknown. */
    public static final long TIME_UNKNOWN = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SUBSCRIPTION_STATUS_" }, value = {
            SUBSCRIPTION_STATUS_UNKNOWN,
            SUBSCRIPTION_STATUS_ACTIVE,
            SUBSCRIPTION_STATUS_INACTIVE,
            SUBSCRIPTION_STATUS_TRIAL,
            SUBSCRIPTION_STATUS_SUSPENDED
    })
    public @interface SubscriptionStatus {}

    /**
     * The subscription status is unknown.
     * <p>
     * This is the default value, used when the carrier is unable to provide the current status
     * of the subscription.
     */
    public static final int SUBSCRIPTION_STATUS_UNKNOWN = 0;

    /**
     * The subscription is active.
     * <p>
     * This indicates that the subscription is in good standing and all services are available
     * to the user.
     */
    public static final int SUBSCRIPTION_STATUS_ACTIVE = 1;

    /**
     * The subscription is inactive.
     * <p>
     * This status means the subscription is not currently in service. This could be because it
     * has been canceled, has expired, or has not yet been activated.
     */
    public static final int SUBSCRIPTION_STATUS_INACTIVE = 2;

    /**
     * The subscription is in a trial period.
     * <p>
     * This indicates that the user is on a promotional or trial plan, which may have different
     * features or limitations than a standard subscription. After the trial period ends, the
     * status will typically change to active or inactive.
     */
    public static final int SUBSCRIPTION_STATUS_TRIAL = 3;

    /**
     * The subscription is suspended.
     * <p>
     * A suspended subscription has been temporarily disabled. This can occur due to billing
     * issues, a user's request, or a violation of the carrier's terms of service. Services are
     * unavailable, but the subscription can typically be reactivated.
     */
    public static final int SUBSCRIPTION_STATUS_SUSPENDED = 4;

    /** @hide */
    @IntDef(prefix = { "PLAN_TYPE_" }, value = {
            PLAN_TYPE_CELLULAR,
            PLAN_TYPE_SATELLITE,
            PLAN_TYPE_IOT,
            PLAN_TYPE_POSTPAID,
            PLAN_TYPE_PREPAID,
            PLAN_TYPE_DATA_ONLY,
            PLAN_TYPE_FAMILY,
            PLAN_TYPE_BUSINESS,
            PLAN_TYPE_ROAMING,
            PLAN_TYPE_TETHERING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlanType {}

    /**
     * The plan provides conventional cellular service.
     * <p>
     * This type is for standard mobile plans that offer data, voice, and messaging services over
     * terrestrial cellular networks (e.g., 4G LTE, 5G).
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_CELLULAR = 1;

    /**
     * The plan provides satellite-based service.
     * <p>
     * This is used for plans that deliver connectivity via satellite.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_SATELLITE = 2;

    /**
     * The plan is designed for Internet of Things (IoT) devices.
     * <p>
     * These plans are typically tailored for devices with low data consumption and specific
     * network requirements, such as smart sensors or asset trackers.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_IOT = 3;

    /**
     * The plan is a postpaid plan.
     * <p>
     * With a postpaid plan, the user consumes services and is billed at the end of each billing
     * cycle for the usage incurred.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_POSTPAID = 4;

    /**
     * The plan is a prepaid plan.
     * <p>
     * With a prepaid plan, the user pays for services in advance, and the service is available
     * until the paid balance is depleted or the service period expires.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_PREPAID = 5;

    /**
     * The plan is for data-only service.
     * <p>
     * This type indicates that the plan provides only mobile data connectivity and does not
     * include traditional voice or SMS services. It is commonly used for tablets, mobile hotspots,
     * or as a secondary data line.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_DATA_ONLY = 6;

    /**
     * The plan is a family plan, shared among multiple users.
     * <p>
     * This type indicates that data limits and usage may be pooled across several subscriptions.
     * Data management applications can use this to provide a consolidated view of the shared usage
     * for all members of the family plan.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_FAMILY = 7;

    /**
     * The plan is for business or enterprise customers.
     * <p>
     * This type indicates that the plan may include features tailored for corporate use, such as
     * dedicated support, static IP addresses, or specific service-level agreements (SLAs).
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_BUSINESS = 8;

    /**
     * The plan is specifically for roaming services.
     * <p>
     * This type is used for plans or add-ons designed for international or out-of-network usage.
     * It helps the system and applications to better inform the user about potential costs or
     * usage restrictions while roaming.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_ROAMING = 9;


    /**
     * The plan provides a data allowance specifically for tethering or mobile hotspot usage.
     * <p>
     * This type is used for plans or add-ons that allocate a specific amount of data for use
     * when the device is acting as a mobile hotspot. Many carriers offer a separate, often smaller,
     * data cap for tethering, which is distinct from the on-device data limit. This allows the
     * system and data management applications to track usage against this specific allowance
     * and inform the user of their remaining hotspot data.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int PLAN_TYPE_TETHERING = 10;

    /**
     * Value indicating that the SubscriptionPlan ID is not specified.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public static final int UNSPECIFIED_ID = -1;

    /**
     * The billing cycle of the subscription plan. It defines a series of time intervals
     * over which usage is measured and billed.
     * <p>
     * For example, a monthly plan might have a cycle that starts on the 5th of each month and
     * ends on the 4th of the following month.
     *
     * @see #cycleIterator()
     */
    @NonNull
    private final RecurrenceRule mCycleRule;

    /**
     * A user-visible title for this plan. For example, "Unlimited+" or "Family plan".
     */
    @Nullable
    private final CharSequence mTitle;

    /**
     * A brief, human-readable summary of the subscription plan.
     * <p>
     * This could include details about the plan's features, such as "10GB of high-speed data"
     * or "Unlimited talk and text". This summary is intended for display to the user.
     *
     * @see #getSummary()
     */
    @Nullable
    private final CharSequence mSummary;

    /**
     * The data limit for this billing cycle, in bytes. When the user's data usage reaches this
     * limit, the behavior of data access will change as defined by
     * {@link #getDataLimitBehavior()}.
     * <p>
     * This value can be {@link #BYTES_UNKNOWN} if the data limit is unknown, or
     * {@link #BYTES_UNLIMITED} if there is no data limit.
     *
     * @see #getDataLimitBytes()
     * @see #getDataLimitBehavior()
     */
    private final long mDataLimitBytes;

    /**
     * The behavior of data access when usage reaches the data limit defined by
     * {@link #getDataLimitBytes()}. This defines what happens to the user's data connection
     * once they have consumed a certain amount of data.
     * <p>
     * For example, the carrier might throttle the connection to a slower speed, disable it
     * entirely, or start billing for overage.
     *
     * @see #LIMIT_BEHAVIOR_UNKNOWN
     * @see #LIMIT_BEHAVIOR_DISABLED
     * @see #LIMIT_BEHAVIOR_BILLED
     * @see #LIMIT_BEHAVIOR_THROTTLED
     */
    private final int mDataLimitBehavior;

    /**
     * A snapshot of the mobile data usage, in bytes. This value is paired with
     * {@link #getDataUsageTime()} to indicate when the measurement was taken.
     * <p>
     * The usage reported here corresponds to the data consumption within the current billing cycle,
     * as defined by {@link #getCycleRule()}. If the plan specifies different limits for various
     * network types, this value represents the total usage across all applicable networks.
     * <p>
     * May be {@link #BYTES_UNKNOWN} if the carrier has not provided this information.
     *
     * @see #getDataUsageTime()
     * @see #getDataLimitBytes()
     */
    private final long mDataUsageBytes;

    /**
     * The time at which the data usage was measured, in milliseconds since the Unix epoch.
     * <p>
     * This timestamp indicates when the {@link #getDataUsageBytes()} value was recorded.
     * It allows applications to determine how recent the usage information is.
     * <p>
     * This value can be {@link #TIME_UNKNOWN} if the data usage time is not available.
     *
     * @see #getDataUsageTime()
     * @see System#currentTimeMillis()
     */
    @CurrentTimeMillisLong
    private final long mDataUsageTime;

    /**
     * The network types that this plan applies to.
     * <p>
     * An empty array indicates that this plan is not valid on any network.
     * <p>
     * If this is not specified, this plan is considered a "default" plan, which applies to any
     * network type that is not explicitly covered by another plan. Each subscription can have
     * at most one default plan.
     * <p>
     * When selecting a plan, the system prioritizes plans that explicitly match the current
     * network type over the default plan.
     */
    @NonNull
    @NetworkType
    private final Set<Integer> mNetworkTypes;

    /**
     * The status of this subscription plan.
     * <p>
     * This indicates the current state of the subscription, such as whether it is active,
     * inactive, in a trial period, or suspended. The status provides context for the plan's
     * availability and can be used to inform the user about their subscription's lifecycle.
     * <p>
     * The value will be one of the {@code SUBSCRIPTION_STATUS_*} constants.
     *
     * @see #getSubscriptionStatus()
     * @see #SUBSCRIPTION_STATUS_UNKNOWN
     * @see #SUBSCRIPTION_STATUS_ACTIVE
     * @see #SUBSCRIPTION_STATUS_INACTIVE
     * @see #SUBSCRIPTION_STATUS_TRIAL
     * @see #SUBSCRIPTION_STATUS_SUSPENDED
     */
    @SubscriptionStatus
    private final int mSubscriptionStatus;

    /**
     * The maximum downstream bitrate for streaming applications, in kilobits per second (Kbps)
     * defined in GSMA TS.43 9.1.3.
     * <p>
     * This value indicates the data rate that the carrier has allocated for streaming services
     * under this plan. Streaming applications can use this information to select an appropriate
     * media quality that avoids buffering.
     * <p>
     * For example, a value of {@code 2000} represents a 2000 Kbps (2 Mbps) connection.
     * <p>
     * The value may be {@link #BITRATE_UNKNOWN} if this information is not available from the
     * carrier or not applicable to the plan.
     */
    private final long mStreamingAppMaxDownlinkKbps;

    /**
     * The maximum upstream bitrate for streaming applications on this subscription plan, in
     * Kilobits per second (Kbps) defined in GSMA TS.43 9.1.3.
     * <p>
     * This value represents the data rate that the carrier has allocated for streaming
     * applications to upload data. It can be used by streaming apps to select an appropriate
     * media quality for outgoing streams, helping to avoid buffering or connection issues.
     * <p>
     * For example, a 1000 Kbps connection would be represented as {@code 1000}.
     *
     * @see #BITRATE_UNKNOWN
     */
    private final long mStreamingAppMaxUplinkKbps;


    /**
     * The time at which the data usage will reset.
     *
     * <p>This is distinct from the billing cycle or plan duration returned by
     * {@link #getCycleRule()}. For example, a 15-day travel plan might have a
     * {@link #getCycleRule()} covering the entire 15-day duration, but a daily data limit
     * of 1GB. In this case, {@link #getDataUsageResetTime()} would indicate the time
     * (e.g., midnight) when that daily 1GB limit resets, while {@link #getCycleRule()}
     * defines when the plan itself expires.
     *
     * <p>This is particularly relevant for plans with a daily data allowance. The value
     * provided for {@link #getDataUsageBytes()} must reflect the data consumed since
     * this reset time.
     *
     * <p>It may be {@code null} if this information is not available or not applicable
     * to the plan.
     *
     * @see #getDataUsageBytes()
     * @see #getCycleRule()
     */
    @Nullable
    private final ZonedDateTime mDataUsageResetTime;

    /**
     * The unique integer identifier for this subscription plan.
     * <p>
     * This ID is provided by the carrier and can be used to uniquely identify this plan,
     * particularly when interacting with entitlement server for enrolling.
     * <p>
     * It may be {@link #UNSPECIFIED_ID} if no ID is specified.
     *
     * @see #getId()
     */
    private final int mId;

    /**
     * The type of this subscription plan.
     * <p>
     * This indicates the type of the subscription, such as whether it is a cellular or satellite
     * plan, postpaid or prepaid, or data-only. The type provides context for the plan's
     * characteristics and can be used to inform the user about their subscription's nature.
     * <p>
     * The value will be a combination of the {@code PLAN_TYPE_*} constants.
     *
     * @see #getTypes()
     * @see #PLAN_TYPE_CELLULAR
     * @see #PLAN_TYPE_SATELLITE
     * @see #PLAN_TYPE_IOT
     * @see #PLAN_TYPE_POSTPAID
     * @see #PLAN_TYPE_PREPAID
     * @see #PLAN_TYPE_DATA_ONLY
     * @see #PLAN_TYPE_FAMILY
     * @see #PLAN_TYPE_BUSINESS
     * @see #PLAN_TYPE_ROAMING
     * @see #PLAN_TYPE_TETHERING
     */
    @NonNull
    @PlanType
    private final Set<Integer> mTypes;

    /**
     * Creates a new SubscriptionPlan by initializing its fields from a {@link Builder}.
     * This constructor is private and is only intended to be called from the
     * {@link Builder#build()} method, which ensures that all plan properties are
     * set correctly.
     *
     * @param builder The builder object containing all the properties for this plan.
     */
    private SubscriptionPlan(Builder builder) {
        this.mCycleRule = Objects.requireNonNull(builder.mCycleRule);
        this.mTitle = builder.mTitle;
        this.mSummary = builder.mSummary;
        this.mDataLimitBytes = builder.mDataLimitBytes;
        this.mDataLimitBehavior = builder.mDataLimitBehavior;
        this.mDataUsageBytes = builder.mDataUsageBytes;
        this.mDataUsageTime = builder.mDataUsageTime;
        this.mNetworkTypes = Arrays.stream(builder.mNetworkTypes)
                .boxed().collect(Collectors.toUnmodifiableSet());
        this.mSubscriptionStatus = builder.mSubscriptionStatus;
        this.mTypes = Arrays.stream(builder.mTypes)
                .boxed().collect(Collectors.toUnmodifiableSet());
        this.mStreamingAppMaxDownlinkKbps = builder.mStreamingAppMaxDownlinkKbps;
        this.mStreamingAppMaxUplinkKbps = builder.mStreamingAppMaxUplinkKbps;
        this.mDataUsageResetTime = builder.mDataUsageResetTime;
        this.mId = builder.mId;
    }

    /**
     * Reconstruct a SubscriptionPlan from a Parcel.
     *
     * @param in The Parcel containing the flattened SubscriptionPlan data.
     */
    private SubscriptionPlan(Parcel in) {
        mCycleRule = in.readParcelable(RecurrenceRule.class.getClassLoader(), RecurrenceRule.class);
        mTitle = in.readCharSequence();
        mSummary = in.readCharSequence();
        mDataLimitBytes = in.readLong();
        mDataLimitBehavior = in.readInt();
        mDataUsageBytes = in.readLong();
        mDataUsageTime = in.readLong();
        mNetworkTypes = Arrays.stream(in.createIntArray())
                .boxed().collect(Collectors.toUnmodifiableSet());
        mSubscriptionStatus = in.readInt();
        mTypes = Arrays.stream(in.createIntArray())
                .boxed().collect(Collectors.toUnmodifiableSet());
        mStreamingAppMaxDownlinkKbps = in.readLong();
        mStreamingAppMaxUplinkKbps = in.readLong();
        mDataUsageResetTime = RecurrenceRule.convertZonedDateTime(in.readString());
        mId = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCycleRule, flags);
        dest.writeCharSequence(mTitle);
        dest.writeCharSequence(mSummary);
        dest.writeLong(mDataLimitBytes);
        dest.writeInt(mDataLimitBehavior);
        dest.writeLong(mDataUsageBytes);
        dest.writeLong(mDataUsageTime);
        dest.writeIntArray(mNetworkTypes.stream().mapToInt(Integer::intValue).toArray());
        dest.writeInt(mSubscriptionStatus);
        dest.writeIntArray(mTypes.stream().mapToInt(Integer::intValue).toArray());
        dest.writeLong(mStreamingAppMaxDownlinkKbps);
        dest.writeLong(mStreamingAppMaxUplinkKbps);
        dest.writeString(RecurrenceRule.convertZonedDateTime(mDataUsageResetTime));
        dest.writeInt(mId);
    }

    @Override
    public String toString() {
        return "SubscriptionPlan{"
                + "cycleRule=" + mCycleRule
                + ", title=" + mTitle
                + ", summary=" + mSummary
                + ", dataLimitBytes=" + mDataLimitBytes
                + ", dataLimitBehavior=" + limitBehaviorToString(mDataLimitBehavior)
                + ", dataUsageBytes=" + mDataUsageBytes
                + ", dataUsageTime=" + timeToString(mDataUsageTime)
                + ", networkTypes=" + mNetworkTypes.stream()
                .map(TelephonyManager::getNetworkTypeName)
                .sorted() // Sort for consistent output
                .collect(Collectors.joining(", ", "[", "]"))
                + ", subscriptionStatus=" + subscriptionStatusToString(mSubscriptionStatus)
                + ", types=" + mTypes.stream()
                .map(SubscriptionPlan::planTypeToString)
                .sorted() // Sort for consistent output
                .collect(Collectors.joining(", ", "[", "]"))
                + ", streamingAppMaxDownlinkKbps=" + mStreamingAppMaxDownlinkKbps
                + ", streamingAppMaxUplinkKbps=" + mStreamingAppMaxUplinkKbps
                + ", dataUsageResetTime=" + mDataUsageResetTime
                + ", id=" + mId
                + "}";
    }

    private static String timeToString(@CurrentTimeMillisLong long time) {
        if (time == TIME_UNKNOWN) {
            return "UNKNOWN";
        }
        // Instant represents a moment on the UTC timeline, and its default
        // toString() is the standard ISO 8601 format.
        return Instant.ofEpochMilli(time).toString();
    }

    private static String limitBehaviorToString(@LimitBehavior int behavior) {
        return switch (behavior) {
            case LIMIT_BEHAVIOR_UNKNOWN -> "UNKNOWN";
            case LIMIT_BEHAVIOR_DISABLED -> "DISABLED";
            case LIMIT_BEHAVIOR_BILLED -> "BILLED";
            case LIMIT_BEHAVIOR_THROTTLED -> "THROTTLED";
            default -> String.valueOf(behavior);
        };
    }

    private static String subscriptionStatusToString(@SubscriptionStatus int status) {
        return switch (status) {
            case SUBSCRIPTION_STATUS_UNKNOWN -> "UNKNOWN";
            case SUBSCRIPTION_STATUS_ACTIVE -> "ACTIVE";
            case SUBSCRIPTION_STATUS_INACTIVE -> "INACTIVE";
            case SUBSCRIPTION_STATUS_TRIAL -> "TRIAL";
            case SUBSCRIPTION_STATUS_SUSPENDED -> "SUSPENDED";
            default -> String.valueOf(status);
        };
    }

    private static String planTypeToString(@PlanType int type) {
        return switch (type) {
            case PLAN_TYPE_CELLULAR -> "CELLULAR";
            case PLAN_TYPE_SATELLITE -> "SATELLITE";
            case PLAN_TYPE_IOT -> "IOT";
            case PLAN_TYPE_POSTPAID -> "POSTPAID";
            case PLAN_TYPE_PREPAID -> "PREPAID";
            case PLAN_TYPE_DATA_ONLY -> "DATA_ONLY";
            case PLAN_TYPE_FAMILY -> "FAMILY";
            case PLAN_TYPE_BUSINESS -> "BUSINESS";
            case PLAN_TYPE_ROAMING -> "ROAMING";
            case PLAN_TYPE_TETHERING -> "TETHERING";
            default -> String.valueOf(type);
        };
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCycleRule, mTitle, mSummary, mDataLimitBytes,
                mDataLimitBehavior, mDataUsageBytes, mDataUsageTime, mNetworkTypes,
                mSubscriptionStatus, mTypes, mStreamingAppMaxDownlinkKbps,
                mStreamingAppMaxUplinkKbps, mDataUsageResetTime, mId);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof SubscriptionPlan other) {
            return mDataLimitBytes == other.mDataLimitBytes
                    && mDataLimitBehavior == other.mDataLimitBehavior
                    && mDataUsageBytes == other.mDataUsageBytes
                    && mDataUsageTime == other.mDataUsageTime
                    && mSubscriptionStatus == other.mSubscriptionStatus
                    && Objects.equals(mCycleRule, other.mCycleRule)
                    && Objects.equals(mTitle, other.mTitle)
                    && Objects.equals(mSummary, other.mSummary)
                    && Objects.equals(mNetworkTypes, other.mNetworkTypes)
                    && Objects.equals(mTypes, other.mTypes)
                    && mStreamingAppMaxDownlinkKbps == other.mStreamingAppMaxDownlinkKbps
                    && mStreamingAppMaxUplinkKbps == other.mStreamingAppMaxUplinkKbps
                    && Objects.equals(mDataUsageResetTime, other.mDataUsageResetTime)
                    && mId == other.mId;
        }
        return false;
    }

    @NonNull
    public static final Creator<SubscriptionPlan> CREATOR = new Creator<>() {
        @Override
        public SubscriptionPlan createFromParcel(Parcel source) {
            return new SubscriptionPlan(source);
        }

        @Override
        public SubscriptionPlan[] newArray(int size) {
            return new SubscriptionPlan[size];
        }
    };

    /**
     * Get the recurrence rule for the billing cycle of this plan.
     *
     * @see #cycleIterator()
     * @see #getPlanEndDate()
     *
     * @hide
     */
    @NonNull
    public RecurrenceRule getCycleRule() {
        return mCycleRule;
    }

    /**
     * Returns the end date of this subscription plan.
     * <p>
     * This indicates the specific date and time when the plan is no longer valid. For recurring
     * plans that do not have a defined end, this will be {@code null}.
     *
     * @return The plan's end date as a {@link ZonedDateTime}, or {@code null} if unavailable.
     */
    @Nullable
    public ZonedDateTime getPlanEndDate() {
        // ZonedDateTime is immutable, so no need to create a defensive copy.
        return mCycleRule.end;
    }

    /**
     * Returns a user-visible title for this plan.
     * <p>
     * This title is provided by the carrier to identify the subscription plan, for example,
     * "Unlimited+" or "Family plan". It is intended for display to the user.
     *
     * @return The title of the plan, or {@code null} if not specified.
     */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns a brief, human-readable summary of the subscription plan.
     * <p>
     * This could include details about the plan's features, such as "10GB of high-speed data"
     * or "Unlimited talk and text". This summary is intended for display to the user.
     *
     * @return A short, user-friendly summary of the plan, or {@code null} if not specified.
     */
    @Nullable
    public CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Returns the data limit for the plan.
     * <p>
     * This is the usage threshold, in bytes, at which data access changes according to
     * the behavior defined by {@link #getDataLimitBehavior()}. For example, once data usage
     * reaches this limit, the connection might be throttled or disabled.
     *
     * @return The data limit in bytes. This may be {@link #BYTES_UNKNOWN} if the limit is not
     * available, or {@link #BYTES_UNLIMITED} if there is no limit.
     */
    @BytesLong
    public long getDataLimitBytes() {
        return mDataLimitBytes;
    }

    /**
     * Returns the behavior of data access when usage reaches the limit defined by
     * {@link #getDataLimitBytes()}.
     * <p>
     * This defines what happens to the user's data connection once they have consumed the amount
     * of data specified in the data limit. For example, the carrier might throttle the connection
     * to a slower speed, disable it entirely, or start billing for overage.
     *
     * @return The data limit behavior, which will be one of {@link #LIMIT_BEHAVIOR_UNKNOWN},
     * {@link #LIMIT_BEHAVIOR_DISABLED}, {@link #LIMIT_BEHAVIOR_BILLED}, or
     * {@link #LIMIT_BEHAVIOR_THROTTLED}.
     */
    @LimitBehavior
    public int getDataLimitBehavior() {
        return mDataLimitBehavior;
    }

    /**
     * Returns a snapshot of the mobile data usage, in bytes, as of the time reported by
     * {@link #getDataUsageTime()}.
     * <p>
     * The usage reported here corresponds to the data consumption within the current billing
     * cycle defined by {@link #getCycleRule()}. If the plan specifies different limits for
     * various network types, this value represents the total usage across all applicable
     * networks.
     *
     * @return The data usage in bytes, or {@link #BYTES_UNKNOWN} if unavailable.
     */
    @BytesLong
    public long getDataUsageBytes() {
        return mDataUsageBytes;
    }

    /**
     * Returns the time at which {@link #getDataUsageBytes()} was measured, in milliseconds
     * since the Unix epoch.
     * <p>
     * This timestamp indicates how recent the data usage information is. It allows applications
     * to decide whether the usage value is current enough for their needs.
     *
     * @return The time of the data usage snapshot as a Unix epoch timestamp, or
     * {@link #TIME_UNKNOWN} if unavailable.
     *
     * @see System#currentTimeMillis()
     */
    @CurrentTimeMillisLong
    public long getDataUsageTime() {
        return mDataUsageTime;
    }

    /**
     * Returns the network types that this plan applies to.
     * <p>
     * A "default" plan, which applies to any network type not explicitly covered by another
     * plan, will include all possible network types in the returned array. Each subscription
     * should have at most one default plan.
     * <p>
     * A RAT-specific plan will return an array containing only the network types it applies to,
     * for example, {@link TelephonyManager#NETWORK_TYPE_LTE}.
     *
     * @return A new copy of the array of network types this plan applies to. The values will be
     * constants from {@link TelephonyManager}, such as {@link TelephonyManager#NETWORK_TYPE_LTE}.
     *
     * @see TelephonyManager#getAllNetworkTypes()
     */
    @NonNull
    @NetworkType
    public int[] getNetworkTypes() {
        return mNetworkTypes.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Returns an iterator that provides the data usage billing cycles for this plan,
     * based on its recurrence rule.
     * <p>
     * The iterator starts from the cycle that is currently active and walks backwards through time.
     * Each cycle is represented as a {@link Range} of {@link ZonedDateTime} objects, indicating the
     * start and end of that period.
     *
     * @return An iterator for the plan's billing cycles.
     */
    public Iterator<Range<ZonedDateTime>> cycleIterator() {
        return mCycleRule.cycleIterator();
    }

    /**
     * Returns the status of the subscription plan.
     * <p>
     * This indicates the current state of the subscription, such as whether it is active,
     * suspended, or in a trial period. This status provides context on the plan's
     * availability and can be used to inform the user about their subscription's lifecycle.
     *
     * @return The subscription status, which is one of the {@code SUBSCRIPTION_STATUS_*}
     * constants, or {@link #SUBSCRIPTION_STATUS_UNKNOWN} if not available.
     *
     * @see #SUBSCRIPTION_STATUS_UNKNOWN
     * @see #SUBSCRIPTION_STATUS_ACTIVE
     * @see #SUBSCRIPTION_STATUS_INACTIVE
     * @see #SUBSCRIPTION_STATUS_TRIAL
     * @see #SUBSCRIPTION_STATUS_SUSPENDED
     */
    @SubscriptionStatus
    public int getSubscriptionStatus() {
        return mSubscriptionStatus;
    }

    /**
     * Returns the maximum downstream bitrate for streaming applications on this subscription
     * plan, in Kilobits per second (Kbps) defined in GSMA TS.43 9.1.3.
     *
     * <p>This value represents the data rate that the carrier has allocated for streaming
     * applications. It can be used by streaming apps to select an appropriate media quality
     * that matches the available bandwidth, helping to avoid buffering.
     *
     * <p>For example, a 2000 Kbps connection would be represented as {@code 2000}.
     *
     * @return The maximum downstream bandwidth for streaming in Kbps, or
     * {@link #BITRATE_UNKNOWN} if unknown or not applicable.
     *
     * @hide
     */
    public long getStreamingAppMaxDownlinkKbps() {
        return mStreamingAppMaxDownlinkKbps;
    }

    /**
     * Returns the maximum upstream bitrate for streaming applications on this subscription
     * plan, in Kilobits per second (Kbps) defined in GSMA TS.43 9.1.3.
     *
     * <p>This value represents the data rate that the carrier has allocated for streaming
     * applications to upload data. It can be used by streaming apps to select an appropriate
     * media quality for outgoing streams, helping to avoid buffering or connection issues.
     *
     * <p>For example, a 1000 Kbps connection would be represented as {@code 1000}.
     *
     * @return The maximum upstream bitrate for streaming in Kbps, or
     * {@link #BITRATE_UNKNOWN} if unknown or not applicable.
     *
     * @hide
     */
    public long getStreamingAppMaxUplinkKbps() {
        return mStreamingAppMaxUplinkKbps;
    }

    /**
     * Returns the time at which the data usage will reset.
     *
     * <p>This is distinct from the billing cycle or plan duration returned by
     * {@link #getCycleRule()}. For example, a 15-day travel plan might have a
     * {@link #getCycleRule()} covering the entire 15-day duration, but a daily data limit
     * of 1GB. In this case, {@link #getDataUsageResetTime()} would indicate the time
     * (e.g., midnight) when that daily 1GB limit resets, while {@link #getCycleRule()}
     * defines when the plan itself expires.
     *
     * <p>This is particularly relevant for plans with a daily data allowance. The value
     * provided for {@link #getDataUsageBytes()} must reflect the data consumed since
     * this reset time.
     *
     * <p>It may be {@code null} if this information is not available or not applicable
     * to the plan.
     *
     * @see #getDataUsageBytes()
     * @see #getCycleRule()
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    @Nullable
    public ZonedDateTime getDataUsageResetTime() {
        return mDataUsageResetTime;
    }

    /**
     * Returns the unique integer identifier for this subscription plan.
     * <p>
     * This ID is provided by the carrier and can be used to uniquely identify this plan,
     * particularly when interacting with entitlement server for enrolling.
     * <p>
     * It may be {@link #UNSPECIFIED_ID} if no ID is specified.
     *
     * @return The unique integer identifier for the plan, or {@link #UNSPECIFIED_ID} if not
     * specified.
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    public int getId() {
        return mId;
    }

    /**
     * Returns the characteristics of this subscription plan as a set of type constants.
     * A plan can have multiple types that describe its nature. For example, a plan might be both a
     * {@link #PLAN_TYPE_CELLULAR} and a {@link #PLAN_TYPE_POSTPAID} plan.
     *
     * <p>The returned set allows for efficient checks of plan characteristics using
     * {@link Set#contains(Object)} and {@link Set#containsAll(java.util.Collection)}.
     *
     * <p>The types can be used to understand the plan's attributes and tailor an application's
     * behavior. For instance, a data management app could display different UI elements for a
     * {@link #PLAN_TYPE_PREPAID} versus a {@link #PLAN_TYPE_POSTPAID} plan.
     *
     * @return An unmodifiable {@link Set} of plan type constants, for example
     * {@link #PLAN_TYPE_CELLULAR} or {@link #PLAN_TYPE_DATA_ONLY}. The set will be empty if no
     * types are associated with the plan.
     *
     * @see #PLAN_TYPE_CELLULAR
     * @see #PLAN_TYPE_SATELLITE
     * @see #PLAN_TYPE_IOT
     * @see #PLAN_TYPE_POSTPAID
     * @see #PLAN_TYPE_PREPAID
     * @see #PLAN_TYPE_DATA_ONLY
     * @see #PLAN_TYPE_FAMILY
     * @see #PLAN_TYPE_BUSINESS
     * @see #PLAN_TYPE_ROAMING
     * @see #PLAN_TYPE_TETHERING
     */
    @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
    @NonNull
    @PlanType
    public Set<Integer> getTypes() {
        return Collections.unmodifiableSet(mTypes);
    }

    /**
     * Builder for a {@link SubscriptionPlan}.
     */
    public static class Builder {
        /**
         * The billing cycle of the subscription plan. It defines a series of time intervals
         * over which usage is measured and billed.
         * <p>
         * For example, a monthly plan might have a cycle that starts on the 5th of each month and
         * ends on the 4th of the following month.
         *
         * @see #cycleIterator()
         */
        private final RecurrenceRule mCycleRule;

        /**
         * A user-visible title for this plan. For example, "Unlimited+" or "Family plan".
         *
         * @see #getTitle()
         */
        @Nullable
        private CharSequence mTitle = null;

        /**
         * A brief, user-visible summary of the plan.
         * <p>
         * This could include details about the plan's features, such as "10GB of high-speed data"
         * or "Unlimited talk and text". This summary is intended for display to the user.
         *
         * @see #getSummary()
         */
        @Nullable
        private CharSequence mSummary = null;

        /**
         * The data limit for this billing cycle, in bytes. When the user's data usage reaches this
         * limit, the behavior of data access will change as defined by
         * {@link #getDataLimitBehavior()}.
         * <p>
         * This value can be {@link #BYTES_UNKNOWN} if the data limit is unknown, or
         * {@link #BYTES_UNLIMITED} if there is no data limit.
         *
         * @see #getDataLimitBytes()
         * @see #getDataLimitBehavior()
         */
        private long mDataLimitBytes = BYTES_UNKNOWN;

        /**
         * The behavior of data access when usage reaches the data limit defined by
         * {@link #mDataLimitBytes}. This defines what happens to the user's data connection
         * once they have consumed a certain amount of data.
         * <p>
         * For example, the carrier might throttle the connection to a slower speed, disable it
         * entirely, or start billing for overage. The value will be one of the
         * {@code LIMIT_BEHAVIOR_*} constants.
         *
         * @see #LIMIT_BEHAVIOR_UNKNOWN
         * @see #LIMIT_BEHAVIOR_DISABLED
         * @see #LIMIT_BEHAVIOR_BILLED
         * @see #LIMIT_BEHAVIOR_THROTTLED
         */
        @LimitBehavior
        private int mDataLimitBehavior = LIMIT_BEHAVIOR_UNKNOWN;

        /**
         * A snapshot of the mobile data usage, in bytes. This value is paired with
         * {@link #mDataUsageTime} to indicate when the measurement was taken.
         * <p>
         * The usage reported here corresponds to the data consumption within the current billing
         * cycle, as defined by {@link #mCycleRule}.
         * <p>
         * May be {@link #BYTES_UNKNOWN} if the carrier has not provided this information.
         *
         * @see #getDataUsageBytes()
         */
        private long mDataUsageBytes = BYTES_UNKNOWN;

        /**
         * The time at which the data usage was measured, in milliseconds since the Unix epoch.
         * <p>
         * This timestamp indicates when the {@link #mDataUsageBytes} value was recorded.
         * It allows applications to determine how recent the usage information is.
         * <p>
         * This value can be {@link #TIME_UNKNOWN} if the data usage time is not available.
         *
         * @see #getDataUsageTime()
         * @see System#currentTimeMillis()
         */
        @CurrentTimeMillisLong
        private long mDataUsageTime = TIME_UNKNOWN;

        /**
         * The network types that this plan applies to.
         * <p>
         * A "default" plan, which applies to any network type not explicitly covered by another
         * plan, will include all possible network types in the returned array. Each subscription
         * should have at most one default plan.
         * <p>
         * A RAT-specific plan will return an array containing only the network types it applies
         * to, for example, {@link TelephonyManager#NETWORK_TYPE_LTE}.
         *
         * @see #setNetworkTypes(int[])
         * @see #resetNetworkTypes()
         * @see TelephonyManager#getAllNetworkTypes()
         */
        @NetworkType
        private int[] mNetworkTypes = TelephonyManager.getAllNetworkTypes();

        /**
         * The status of this subscription plan.
         * <p>
         * This indicates the current state of the subscription, such as whether it is active,
         * inactive, in a trial period, or suspended. The status provides context for the plan's
         * availability and can be used to inform the user about their subscription's lifecycle.
         * <p>
         * The value will be one of the {@code SUBSCRIPTION_STATUS_*} constants. Defaults to
         * {@link #SUBSCRIPTION_STATUS_UNKNOWN}.
         *
         * @see #getSubscriptionStatus()
         * @see #SUBSCRIPTION_STATUS_UNKNOWN
         * @see #SUBSCRIPTION_STATUS_ACTIVE
         * @see #SUBSCRIPTION_STATUS_INACTIVE
         * @see #SUBSCRIPTION_STATUS_TRIAL
         * @see #SUBSCRIPTION_STATUS_SUSPENDED
         */
        @SubscriptionStatus
        private int mSubscriptionStatus = SUBSCRIPTION_STATUS_UNKNOWN;

        /**
         * The maximum downstream bitrate for streaming applications, in kilobits per second
         * (Kbps) defined in GSMA TS.43 9.1.3.
         * <p>
         * This value indicates the data rate that the carrier has allocated for streaming services
         * under this plan. Streaming applications can use this information to select an appropriate
         * media quality that avoids buffering.
         * <p>
         * For example, a value of {@code 2000} represents a 2000 Kbps (2 Mbps) connection.
         * <p>
         * The value may be {@link #BITRATE_UNKNOWN} if this information is not available from the
         * carrier or not applicable to the plan.
         */
        private long mStreamingAppMaxDownlinkKbps = BITRATE_UNKNOWN;

        /**
         * The maximum upstream bitrate for streaming applications, in kilobits per second (Kbps)
         * defined in GSMA TS.43 9.1.3.
         * <p>
         * This value represents the data rate that the carrier has allocated for streaming
         * applications to upload data. Streaming applications can use this to select an appropriate
         * media quality for outgoing streams, helping to avoid buffering or connection issues.
         * <p>
         * For example, a value of {@code 1000} represents a 1000 Kbps (1 Mbps) connection.
         * <p>
         * The value may be {@link #BITRATE_UNKNOWN} if this information is not available from the
         * carrier or not applicable to the plan.
         */
        private long mStreamingAppMaxUplinkKbps = BITRATE_UNKNOWN;

        /**
         * The time at which the data usage allowance resets.
         * <p>
         * This is particularly relevant for plans with a daily data allowance, as it specifies
         * the exact moment (e.g., midnight in a specific timezone) when the usage for the
         * next 24-hour period begins.
         * <p>
         * It may be {@code null} if this information is not available or not applicable to the
         * plan.
         *
         * @see #getDataUsageResetTime()
         */
        @Nullable
        private ZonedDateTime mDataUsageResetTime = null;

        /**
         * The unique integer identifier for this subscription plan.
         * <p>
         * This ID is provided by the carrier and can be used to uniquely identify this plan,
         * particularly when interacting with entitlement server for enrolling.
         * <p>
         * It may be {@link #UNSPECIFIED_ID} if no ID is specified. Defaults to
         * {@link #UNSPECIFIED_ID}.
         *
         * @see #getId()
         */
        private int mId = UNSPECIFIED_ID;

        /**
         * The type of this subscription plan.
         * <p>
         * This indicates the type of the subscription, such as whether it is a cellular or
         * satellite plan, postpaid or prepaid, or data-only. The type provides context for the
         * plan's characteristics and can be used to inform the user about their subscription's
         * nature.
         * <p>
         * The value will be a combination of the {@code PLAN_TYPE_*} constants.
         *
         * @see #getTypes()
         * @see #PLAN_TYPE_CELLULAR
         * @see #PLAN_TYPE_SATELLITE
         * @see #PLAN_TYPE_IOT
         * @see #PLAN_TYPE_POSTPAID
         * @see #PLAN_TYPE_PREPAID
         * @see #PLAN_TYPE_DATA_ONLY
         * @see #PLAN_TYPE_FAMILY
         * @see #PLAN_TYPE_BUSINESS
         * @see #PLAN_TYPE_ROAMING
         * @see #PLAN_TYPE_TETHERING
         */
        @NonNull
        @PlanType
        private int[] mTypes = new int[0];

        /**
         * Create a {@link Builder} to build a {@link SubscriptionPlan}.
         *
         * @param start The start of the recurrence.
         * @param end The end of the recurrence. It is exclusive. Can be {@code null} if the
         * recurrence is infinite.
         * @param period The period of the recurrence. Can be {@code null} if this is a
         * non-recurring plan.
         *
         * @hide
         */
        public Builder(ZonedDateTime start, ZonedDateTime end, Period period) {
            this.mCycleRule = new RecurrenceRule(start, end, period);
        }

        /**
         * Start defining a {@link SubscriptionPlan} that covers a very specific window of time, and
         * never automatically recurs.
         *
         * @param start The exact time at which the plan starts.
         * @param end The exact time at which the plan ends.
         */
        public static Builder createNonrecurring(ZonedDateTime start, ZonedDateTime end) {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "End " + end + " isn't after start " + start);
            }
            return new Builder(start, end, null);
        }

        /**
         * Start defining a template for a non-recurring {@link SubscriptionPlan} with a specific
         * duration.
         * <p>
         * This is useful for describing fixed-duration plans that users can enroll in, such as
         * a travel pass or a one-time data package. The actual start and end dates are determined
         * upon enrollment.
         * <p>
         * Plans created with this builder should be marked with
         * {@link #setSubscriptionStatus(int)} and a status of
         * {@link #SUBSCRIPTION_STATUS_INACTIVE}.
         *
         * @param duration The total duration of the plan once activated (e.g., Period.ofDays(15)).
         * @return A {@link Builder} instance for a non-recurring plan template.
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public static Builder createNonrecurring(@NonNull Period duration) {
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Duration " + duration + " must be positive");
            }
            return new Builder(null, null, duration)
                    .setSubscriptionStatus(SUBSCRIPTION_STATUS_INACTIVE);
        }

        /**
         * Start defining a {@link SubscriptionPlan} that starts at a specific time, and
         * automatically recurs after each specific period of time, repeating indefinitely.
         * <p>
         * When the given period is set to exactly one month, the plan will always recur on the day
         * of the month defined by {@link ZonedDateTime#getDayOfMonth()}. When a particular month
         * ends before this day, the plan will recur on the last possible instant of that month.
         *
         * @param start The exact time at which the plan starts.
         * @param period The period after which the plan automatically recurs.
         */
        public static Builder createRecurring(ZonedDateTime start, Period period) {
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("Period " + period + " must be positive");
            }
            return new Builder(start, null, period);
        }

        /** @hide */
        @SystemApi
        @Deprecated
        public static Builder createRecurringMonthly(ZonedDateTime start) {
            return new Builder(start, null, Period.ofMonths(1));
        }

        /** @hide */
        @SystemApi
        @Deprecated
        public static Builder createRecurringWeekly(ZonedDateTime start) {
            return new Builder(start, null, Period.ofDays(7));
        }

        /** @hide */
        @SystemApi
        @Deprecated
        public static Builder createRecurringDaily(ZonedDateTime start) {
            return new Builder(start, null, Period.ofDays(1));
        }

        public SubscriptionPlan build() {
            return new SubscriptionPlan(this);
        }

        /**
         * Sets a user-visible title for this plan.
         * <p>
         * This title is provided by the carrier to identify the subscription plan, for example,
         * "Unlimited+" or "Family plan". It is intended for display to the user.
         *
         * @param title The title of the plan.
         * @return The same {@link Builder} instance to continue building the plan.
         */
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets a brief, human-readable summary of the subscription plan.
         * <p>
         * This could include details about the plan's features, such as "10GB of high-speed data"
         * or "Unlimited talk and text". This summary is intended for display to the user.
         *
         * @param summary A short, user-friendly summary of the plan, or {@code null} to clear it.
         */
        public Builder setSummary(@Nullable CharSequence summary) {
            mSummary = summary;
            return this;
        }

        /**
         * Set the usage threshold at which data access changes.
         *
         * @param dataLimitBytes the usage threshold at which data access changes
         * @param dataLimitBehavior the behavior of data access when usage reaches the threshold
         */
        public Builder setDataLimit(@BytesLong long dataLimitBytes,
                @LimitBehavior int dataLimitBehavior) {
            if (dataLimitBytes < 0 && dataLimitBytes != BYTES_UNKNOWN) {
                throw new IllegalArgumentException("Limit bytes must be positive or unknown");
            }
            if (dataLimitBehavior < LIMIT_BEHAVIOR_UNKNOWN
                    || dataLimitBehavior > LIMIT_BEHAVIOR_THROTTLED) {
                throw new IllegalArgumentException("Limit behavior must be a valid value");
            }
            mDataLimitBytes = dataLimitBytes;
            mDataLimitBehavior = dataLimitBehavior;
            return this;
        }

        /**
         * Set a snapshot of currently known mobile data usage.
         *
         * @param dataUsageBytes the currently known mobile data usage
         * @param dataUsageTime the time at which this snapshot was valid
         */
        public Builder setDataUsage(@BytesLong long dataUsageBytes,
                @CurrentTimeMillisLong long dataUsageTime) {
            if (dataUsageBytes < 0 && dataUsageBytes != BYTES_UNKNOWN) {
                throw new IllegalArgumentException("Usage bytes must be positive or unknown");
            }
            if (dataUsageTime < 0 && dataUsageTime != TIME_UNKNOWN) {
                throw new IllegalArgumentException("Usage time must be positive or unknown");
            }
            mDataUsageBytes = dataUsageBytes;
            mDataUsageTime = dataUsageTime;
            return this;
        }

        /**
         * Set the network types this SubscriptionPlan applies to. By default the plan will apply
         * to all network types. An empty array means this plan applies to no network types.
         *
         * @param networkTypes an array of all network types that apply to this plan.
         *
         * @see TelephonyManager#getAllNetworkTypes()
         */
        @NonNull
        public Builder setNetworkTypes(@NonNull @NetworkType int[] networkTypes) {
            mNetworkTypes = Arrays.copyOf(networkTypes, networkTypes.length);
            return this;
        }

        /**
         * Reset any network types that were set with {@link #setNetworkTypes(int[])}.
         * This will make the SubscriptionPlan apply to all network types.
         */
        @NonNull
        public Builder resetNetworkTypes() {
            mNetworkTypes = Arrays.copyOf(TelephonyManager.getAllNetworkTypes(),
                    TelephonyManager.getAllNetworkTypes().length);
            return this;
        }

        /**
         * Set the subscription status.
         * <p>
         * This indicates the current state of the subscription, such as whether it is active,
         * suspended, or in a trial period. This status provides context on the plan's
         * availability and can be used to inform the user about their subscription's lifecycle.
         *
         * @param subscriptionStatus the current subscription status
         */
        @NonNull
        public Builder setSubscriptionStatus(@SubscriptionStatus int subscriptionStatus) {
            if (subscriptionStatus < SUBSCRIPTION_STATUS_UNKNOWN
                    || subscriptionStatus > SUBSCRIPTION_STATUS_SUSPENDED) {
                throw new IllegalArgumentException(
                        "Subscription status must be defined with a valid value");
            }
            mSubscriptionStatus = subscriptionStatus;
            return this;
        }

        /**
         * Sets the maximum downstream bitrate for streaming applications on this subscription
         * plan, in Kilobits per second (Kbps) defined in GSMA TS.43 9.1.3.
         *
         * @param downlinkKbps The maximum downstream bandwidth in Kbps.
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public Builder setStreamingAppMaxDownlinkKbps(long downlinkKbps) {
            mStreamingAppMaxDownlinkKbps = downlinkKbps;
            return this;
        }

        /**
         * Sets the maximum upstream bitrate for streaming applications on this subscription
         * plan, in Kilobits per second (Kbps) defined in GSMA TS.43 9.1.3.
         *
         * @param uplinkKbps The maximum upstream bitrate in Kbps.
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public Builder setStreamingAppMaxUplinkKbps(long uplinkKbps) {
            mStreamingAppMaxUplinkKbps = uplinkKbps;
            return this;
        }

        /**
         * Sets the time when the data usage allowance will reset.
         * <p>
         * This is particularly relevant for plans with a daily data allowance, as it specifies
         * the exact moment (e.g., midnight in a specific timezone) when the usage for the
         * next 24-hour will reset. For other types of plans, this may not be applicable.
         * <p>
         * The value provided for {@link #setDataUsage(long, long)} should reflect the data consumed
         * since the last reset time.
         *
         * @param dataUsageResetTime The data usage reset time, or {@code null} if this information
         * is not available or not applicable to the plan.
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public Builder setDataUsageResetTime(@Nullable ZonedDateTime dataUsageResetTime) {
            this.mDataUsageResetTime = dataUsageResetTime;
            return this;
        }

        /**
         * Sets the unique integer identifier for this subscription plan.
         * <p>
         * This ID is provided by the carrier and can be used to uniquely identify this plan,
         * particularly when interacting with entitlement server for enrolling.
         *
         * @param id The unique integer integer identifier for the plan, or {@link #UNSPECIFIED_ID}
         * to clear it.
         *
         * @return The same {@link Builder} instance to continue building the plan.
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public Builder setId(int id) {
            mId = id;
            return this;
        }

        /**
         * Set the types of this subscription plan. A plan can have multiple types, and this method
         * will return all that are applicable. For example, a plan could be both a cellular plan
         * and a postpaid plan.
         *
         * @return An array containing the applicable {@code PLAN_TYPE_} constants.
         *
         * @see #PLAN_TYPE_CELLULAR
         * @see #PLAN_TYPE_SATELLITE
         * @see #PLAN_TYPE_IOT
         * @see #PLAN_TYPE_POSTPAID
         * @see #PLAN_TYPE_PREPAID
         * @see #PLAN_TYPE_DATA_ONLY
         * @see #PLAN_TYPE_FAMILY
         * @see #PLAN_TYPE_BUSINESS
         * @see #PLAN_TYPE_ROAMING
         * @see #PLAN_TYPE_TETHERING
         */
        @FlaggedApi(Flags.FLAG_SUBSCRIPTION_PLAN_ENHANCEMENT)
        @NonNull
        public Builder setTypes(@NonNull @PlanType int[] types) {
            mTypes = Arrays.copyOf(types, types.length);
            return this;
        }
    }
}
