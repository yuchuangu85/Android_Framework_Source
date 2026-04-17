/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.media.audiopolicy.AudioVolumeGroup.DEFAULT_VOLUME_GROUP;
import static android.media.audiopolicy.Flags.multiZoneAudio;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 * A class to encapsulate a collection of attributes associated to a given product strategy
 * (and for legacy reason, keep the association with the stream type).
 */
@SystemApi
public final class AudioProductStrategy implements Parcelable {
    /**
     * group value to use when introspection API fails.
     * @hide
     */
    public static final int DEFAULT_GROUP = -1;

    /**
     * Default zone id for audio product strategies. Product strategies without assigned zone id
     * will be this value.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @SystemApi
    public static final int DEFAULT_ZONE_ID = 0;

    /**
     * Invalid zone id.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @SystemApi
    public static final int INVALID_ZONE_ID = -1;

    private static final int MATCH_ON_ZONE_ID_SCORE = 1 << 4;
    private static final int MATCH_ON_TAGS_SCORE = 1 << 3;
    private static final int MATCH_ON_FLAGS_SCORE = 1 << 2;
    private static final int MATCH_ON_USAGE_SCORE = 1 << 1;
    private static final int MATCH_ON_CONTENT_TYPE_SCORE = 1 << 0;
    private static final int MATCH_ON_DEFAULT_SCORE = 0;
    private static final int NO_MATCH = -1;
    private static final int MATCH_ATTRIBUTES_EQUALS = MATCH_ON_TAGS_SCORE | MATCH_ON_FLAGS_SCORE
            | MATCH_ON_CONTENT_TYPE_SCORE | MATCH_ON_USAGE_SCORE;
    private static final int MATCH_EQUALS = MATCH_ON_ZONE_ID_SCORE | MATCH_ATTRIBUTES_EQUALS;

    private static final String TAG = "AudioProductStrategy";

    /**
     * The audio flags that will affect product strategy selection.
     */
    private static final int AUDIO_FLAGS_AFFECT_STRATEGY_SELECTION =
            AudioAttributes.FLAG_AUDIBILITY_ENFORCED
                    | AudioAttributes.FLAG_SCO
                    | AudioAttributes.FLAG_BEACON;

    private final AudioAttributesGroup[] mAudioAttributesGroups;
    private final String mName;

    /**
     * Unique identifier of a product strategy.
     * This Id can be assimilated to Car Audio Usage and even more generally to usage.
     * For legacy platforms, the product strategy id is the routing_strategy, which was hidden to
     * upper layer but was transpiring in the {@link AudioAttributes#getUsage()}.
     */
    private final int mId;

    /**
     * Product strategy zone ID, default is {@code DEFAULT_ZONE_ID}.
     *
     * @hide
     */
    private int mZoneId = DEFAULT_ZONE_ID;

    private static IAudioService sService;

    private static IAudioService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    /**
     * Select the best {@link AudioProductStrategy} object for the given {@link AudioAttributes}.
     * @param attributes to consider
     * @param fallbackOnDefault if set, allows to fallback on the default strategy (e.g. the
     * strategy associated to {@code DEFAULT_ATTRIBUTES}).
     * @return the highest matching score {@link AudioProductStrategy} if found, default if fallback
     * on default is set, {@code null} otherwise.
     *
     * @hide
     */
    @Nullable
    public static AudioProductStrategy getAudioProductStrategyForAudioAttributes(
            @NonNull AudioAttributes attributes, boolean fallbackOnDefault) {
        AudioAttributesGroup aag =
                getAudioAttributesGroupForAttributes(attributes, fallbackOnDefault);
        return getAudioProductStrategyForAudioAttributes(attributes, DEFAULT_ZONE_ID,
                fallbackOnDefault);
    }

    /**
     * Selects the zone id for a volume group id.
     *
     * @param groupId to consider
     * @return the zone id for the given groupId if found, {@code #INVALID_ZONE_ID} otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public static int getZoneIdForAudioVolumeGroupId(int groupId) {
        IAudioService service = getService();
        try {
            return service.getZoneIdForAudioVolumeGroupId(groupId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error getting audio product strategies", e);
            return DEFAULT_ZONE_ID;
        }
    }

    /**
     * Selects the zone id for a volume group id.
     *
     * @param strategies audio product strategies to search
     * @param groupId to consider
     * @return the zone id for the given groupId if found, {@code #INVALID_ZONE_ID} otherwise.
     *
     * @hide
     */
    public static int getZoneIdForAudioVolumeGroupId(List<AudioProductStrategy> strategies,
            int groupId) {
        for (AudioProductStrategy strategy : strategies) {
            for (AudioAttributesGroup aag : strategy.mAudioAttributesGroups) {
                if (aag.mVolumeGroupId == groupId) {
                    return strategy.getZoneId();
                }
            }
        }
        return INVALID_ZONE_ID;
    }

    /**
     * Select the best {@link AudioProductStrategy} object for the given {@link AudioAttributes}
     *  and zone id.
     * @param attributes to consider
     * @param zoneId zone id to consider
     * @param fallbackOnDefault if set, allows to fallback on the default strategy (e.g. the
     * strategy associated to {@code DEFAULT_ATTRIBUTES}).
     * @return the highest matching score {@link AudioProductStrategy} if found, default if fallback
     * on default is set, {@code null} otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @Nullable
    public static AudioProductStrategy getAudioProductStrategyForAudioAttributes(
            @NonNull AudioAttributes attributes, int zoneId, boolean fallbackOnDefault) {
        AudioAttributesGroup aag =
                getAudioAttributesGroupForAttributes(attributes, zoneId, fallbackOnDefault);
        return aag != null ? getAudioProductStrategyWithId(aag.getStrategyId()) :  null;
    }

    /**
     * @hide
     * Return the AudioProductStrategy object for the given strategy ID.
     * @param id the ID of the strategy to find
     * @return an AudioProductStrategy on which getId() would return id, null if no such strategy
     *     exists.
     */
    public static @Nullable AudioProductStrategy getAudioProductStrategyWithId(int id) {
        List<AudioProductStrategy> strategies =
                getAudioProductStrategiesFromService(/* filterInternal= */ true);
        for (AudioProductStrategy strategy : strategies) {
            if (strategy.getId() == id) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * Create an invalid AudioProductStrategy instance for testing
     * @param id the ID for the invalid strategy, always use a different one than in use
     *        Unused: do not let caller to set it as some ids are allocated to internal strategies.
     * @return an invalid instance that cannot successfully be used for volume groups or routing
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public static @NonNull AudioProductStrategy createInvalidAudioProductStrategy(int id) {
        // Must keep all audio product strategies to prevent collisions with internal
        List<AudioProductStrategy> strategies =
                getAudioProductStrategiesFromService(/* filterInternal= */ false);
        return new AudioProductStrategy("invalid strategy", strategies.size() + 1,
                DEFAULT_ZONE_ID, new AudioAttributesGroup[0]);
    }

    /**
     * @param streamType to match against AudioProductStrategy
     * @return the AudioAttributes for the first strategy found with the associated stream type
     *          If no match is found, returns AudioAttributes with unknown content_type and usage
     *
     * @hide
     */
    @NonNull
    public static AudioAttributes getAudioAttributesForStrategyWithLegacyStreamType(
            int streamType) {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        native_get_audio_attributes_for_legacy_stream(streamType, builder);
        return builder.build();
    }


    /**
     * Gets audio attributes for legacy stream type
     * @param strategies strategies to search over
     * @param streamType to match against AudioProductStrategy
     * @return the AudioAttributes for the first strategy found with the associated stream type
     *          If no match is found, returns AudioAttributes with unknown content_type and usage
     *
     * @hide
     */
    @NonNull
    public static AudioAttributes getAudioAttributesForStrategyWithLegacyStreamType(
            List<AudioProductStrategy> strategies, int streamType) {
        Objects.requireNonNull(strategies, "Product strategies must not be null");
        for (var strategy : strategies) {
            var group = strategy.getAudioAttributeGroupForLegacyStreamType(streamType);
            if (group != null) {
                return group.getAudioAttributes();
            }
        }
        return DEFAULT_ATTRIBUTES;
    }

    /**
     * @param audioAttributes to identify {@link AudioProductStrategy} with
     * @return legacy stream type associated with matched {@link AudioProductStrategy}. If no
     *              strategy found or found {@link AudioProductStrategy} does not have associated
     *              legacy stream defaults to {@link AudioSystem#STREAM_MUSIC}
     *
     * @hide
     */
    public static int getLegacyStreamTypeForStrategyWithAudioAttributes(
            @NonNull AudioAttributes audioAttributes) {
        Objects.requireNonNull(audioAttributes, "AudioAttributes must not be null");
        int streamType = native_get_legacy_stream_for_audio_attributes(audioAttributes);
        if (streamType < AudioSystem.getNumStreamTypes()) {
            return streamType;
        }
        return AudioSystem.STREAM_MUSIC;
    }

    /**
     * Selects matching volume id for audio attributes for audio attributes, or fallbacks back on
     * default if {@code fallbackOnDefault} is {@code true}
     *
     * @param attributes the {@link AudioAttributes} that best identify VolumeGroupId
     * @param fallbackOnDefault if set, allows to fallback on the default group (e.g. the group
     *                          associated to {@link AudioManager#STREAM_MUSIC}).
     * @return volume group id associated with the given {@link AudioAttributes} if found,
     *     default volume group id if fallbackOnDefault is set
     * <p>By convention, the product strategy with default attributes will be associated to the
     * default volume group (e.g. associated to {@link AudioManager#STREAM_MUSIC})
     * or {@code DEFAULT_VOLUME_GROUP} if not found.
     *
     * @hide
     */
    public static int getVolumeGroupIdForAudioAttributes(
            @NonNull AudioAttributes attributes, boolean fallbackOnDefault) {
        return getVolumeGroupIdForAudioAttributes(attributes, DEFAULT_ZONE_ID, fallbackOnDefault);
    }

    /**
     * Selects matching volume id for audio attributes for audio attributes and zone, or fallbacks
     * back on default if {@code fallbackOnDefault} is {@code true}
     *
     * @param attributes the {@link AudioAttributes} to identify VolumeGroupId with
     * @param fallbackOnDefault if set, allows to fallback on the default group (e.g. the group
     *                          associated to {@link AudioManager#STREAM_MUSIC}).
     * @return volume group id associated with the given {@link AudioAttributes} if found,
     *     default volume group id if fallbackOnDefault is set
     * <p>By convention, the product strategy with default attributes will be associated to the
     * default volume group (e.g. associated to {@link AudioManager#STREAM_MUSIC})
     * or {@code DEFAULT_VOLUME_GROUP} if not found.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public static int getVolumeGroupIdForAudioAttributes(
            @NonNull AudioAttributes attributes, int zoneId, boolean fallbackOnDefault) {
        AudioAttributesGroup aag = getAudioAttributesGroupForAttributes(attributes, zoneId,
                fallbackOnDefault);
        return aag != null ? aag.getVolumeGroupId() : DEFAULT_VOLUME_GROUP;
    }

    /**
     * Filters internal product strategies.
     * @param strategies input list of product strategies that will be filtered
     * @return non-modifiable list of product strategies without internal strategies
     *
     * @hide
     */
    public static List<AudioProductStrategy> filterNonInternalStrategies(
            List<AudioProductStrategy> strategies) {
        List<AudioProductStrategy> filteredStrategies = new ArrayList<>(strategies);
        filteredStrategies.removeIf(AudioProductStrategy::isInternalStrategy);
        return Collections.unmodifiableList(filteredStrategies);
    }

    private static boolean isDefaultMatchScore(int matchScore) {
        if (multiZoneAudio()) {
            return (matchScore == MATCH_ON_DEFAULT_SCORE) || (matchScore == MATCH_ON_ZONE_ID_SCORE);
        }
        return matchScore == MATCH_ON_DEFAULT_SCORE;
    }

    private static boolean isMatchScoreEquals(int matchScore) {
        if (multiZoneAudio()) {
            return matchScore == MATCH_EQUALS;
        }
        return matchScore == MATCH_ATTRIBUTES_EQUALS;
    }

    @Nullable
    private static AudioAttributesGroup getAudioAttributesGroupForAttributes(
            @NonNull AudioAttributes attributes, int zoneId, boolean fallbackOnDefault) {
        Objects.requireNonNull(attributes, "attributes must not be null");
        int matchScore = NO_MATCH;
        AudioAttributesGroup bestAudioAttributesGroupOrDefault = null;
        List<AudioProductStrategy> strategies =
                getAudioProductStrategiesFromService(/* filterInternal= */ true);
        for (AudioProductStrategy productStrategy : strategies) {
            ScoredAudioAttributesGroup scoredAag =
                    productStrategy.getScoredAttributeGroupForAttribute(attributes, zoneId);
            int score = scoredAag.getScore();
            if (isMatchScoreEquals(score)) {
                return scoredAag.getAudioAttributesGroup();
            }
            if (score > matchScore) {
                matchScore = score;
                bestAudioAttributesGroupOrDefault = scoredAag.getAudioAttributesGroup();
            }
        }
        return (!isDefaultMatchScore(matchScore) || fallbackOnDefault)
                ? bestAudioAttributesGroupOrDefault : null;
    }

    @Nullable
    private static AudioAttributesGroup getAudioAttributesGroupForAttributes(
            @NonNull AudioAttributes attributes, boolean fallbackOnDefault) {
        Objects.requireNonNull(attributes, "attributes must not be null");
        int matchScore = NO_MATCH;
        AudioAttributesGroup bestAudioAttributesGroupOrDefault = null;
        List<AudioProductStrategy> strategies =
                getAudioProductStrategiesFromService(/* filterInternal= */ true);
        for (AudioProductStrategy productStrategy : strategies) {
            ScoredAudioAttributesGroup scoredAag =
                    productStrategy.getScoredAttributeGroupForAttribute(attributes);
            int score = scoredAag.getScore();
            if (isMatchScoreEquals(score)) {
                return scoredAag.getAudioAttributesGroup();
            }
            if (score > matchScore) {
                matchScore = score;
                bestAudioAttributesGroupOrDefault = scoredAag.getAudioAttributesGroup();
            }
        }

        return (!isDefaultMatchScore(matchScore) || fallbackOnDefault)
                ? bestAudioAttributesGroupOrDefault : null;
    }

    private static List<AudioProductStrategy> getAudioProductStrategiesFromService(
            boolean filterInternal) {
        IAudioService service = getService();
        try {
            return service.getAudioProductStrategies(filterInternal);
        } catch (RemoteException e) {
            Log.e(TAG, "Error getting audio product strategies", e);
            return Collections.emptyList();
        }
    }

    /**
     * List audio products strategies
     *
     * @return an immutable list of AudioProductStrategy discovered from platform configuration
     * file. or an empty list on error (if audioserver is unavailable).
     *
     * @hide
     */
    public static native int native_list_audio_product_strategies(
            ArrayList<AudioProductStrategy> strategies);

    private static native int native_get_audio_attributes_for_legacy_stream(int legacyStreamType,
            AudioAttributes.Builder attributes);

    private static native int native_get_legacy_stream_for_audio_attributes(
            AudioAttributes attributes);

    /**
     * Return a volume group id for legacy stream type
     *
     * @param strategies list of audio product strategies which should be searched
     * @param stream legacy stream type to query
     * @return volume group id if the list of audio product strategies contains a matching legacy
     * stream, {*code DEFAULT_VOLUME_GROUP} if the legacy stream is not found in the product
     * strategies
     *
     * @hide
     */
    public static int getVolumeGroupIdForStreamType(
            List<AudioProductStrategy> strategies, int stream) {
        Objects.requireNonNull(strategies, "Product strategies must not be null");
        for (AudioProductStrategy strategy : strategies) {
            int group = strategy.getVolumeGroupIdForLegacyStreamType(stream);
            if (group != DEFAULT_VOLUME_GROUP) {
                return group;
            }
        }
        return DEFAULT_VOLUME_GROUP;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioProductStrategy thatStrategy = (AudioProductStrategy) o;

        return mId == thatStrategy.mId
                && Objects.equals(mName, thatStrategy.mName)
                && Arrays.equals(mAudioAttributesGroups, thatStrategy.mAudioAttributesGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mName, Arrays.hashCode(mAudioAttributesGroups));
    }

    /**
     * @param name of the product strategy
     * @param id of the product strategy
     * @param zoneId zone for product strategy
     * @param aag {@link AudioAttributesGroup} associated to the given product strategy
     */
    private AudioProductStrategy(@NonNull String name, int id, int zoneId,
                                 @NonNull AudioAttributesGroup[] aag) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(aag, "AudioAttributesGroups must not be null");
        mName = name;
        mId = id;
        mZoneId = zoneId;
        mAudioAttributesGroups = aag;
        for (AudioAttributesGroup audioAttributesGroup : mAudioAttributesGroups) {
            if (audioAttributesGroup.getStrategyId() != mId) {
                throw new IllegalArgumentException("AudioAttributesGroup strategy id: "
                        + audioAttributesGroup.getStrategyId()
                        + " does not match AudioProductStrategy id: " + mId);
            }
        }
    }

    /**
     * @hide
     * @return the product strategy ID (which is the generalisation of Car Audio Usage / legacy
     *         routing_strategy linked to {@link AudioAttributes#getUsage()}).
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public int getId() {
        return mId;
    }

    /**
     * @return the product strategy zone ID, default is {@code DEFAULT_ZONE_ID}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public int getZoneId() {
        return mZoneId;
    }

    /**
     * @hide
     * @return the product strategy name (which is the generalisation of Car Audio Usage / legacy
     *         routing_strategy linked to {@link AudioAttributes#getUsage()}).
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @NonNull public String getName() {
        return mName;
    }

    /**
     * @hide
     * @return first {@link AudioAttributes} associated to this product strategy.
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public @NonNull AudioAttributes getAudioAttributes() {
        // We need a choice, so take the first one
        return mAudioAttributesGroups.length == 0 ? DEFAULT_ATTRIBUTES
                : mAudioAttributesGroups[0].getAudioAttributes();
    }

    /**
     * @param streamType legacy stream type used for volume operation only
     * @return the {@link AudioAttributes} relevant for the given streamType.
     *         If none is found, it builds the default attributes.
     *
     * @hide
     */
    @Nullable
    public AudioAttributes getAudioAttributesForLegacyStreamType(int streamType) {
        AudioAttributesGroup aag = getAudioAttributeGroupForLegacyStreamType(streamType);
        return aag != null ? aag.getAudioAttributes() : null;
    }

    /**
     * @param aa the {@link AudioAttributes} to be considered
     * @return the legacy stream type relevant for the given {@link AudioAttributes}.
     *         If none is found, it return DEFAULT stream type.
     *
     * @hide
     */
    @TestApi
    public int getLegacyStreamTypeForAudioAttributes(@NonNull AudioAttributes attributes) {
        ScoredAudioAttributesGroup scoredAag;
        // If there is no match (score = NO_MATCH) audio attributes group (aag) is guaranteed to be
        // null
        if (multiZoneAudio()) {
            scoredAag = getScoredAttributeGroupForAttribute(attributes, mZoneId);
        } else {
            scoredAag = getScoredAttributeGroupForAttribute(attributes);
        }
        AudioAttributesGroup aag = scoredAag.getAudioAttributesGroup();
        int score = scoredAag.getScore();
        return (aag != null && !isDefaultMatchScore(score))
                ? aag.getStreamType() : AudioSystem.STREAM_DEFAULT;
    }

    /**
     * @param aa the {@link AudioAttributes} to be considered
     * @return true if the {@link AudioProductStrategy} supports the given {@link AudioAttributes},
     *         false otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public boolean supportsAudioAttributes(@NonNull AudioAttributes aa) {
        if (multiZoneAudio()) {
            return supportsAudioAttributes(aa, mZoneId);
        }
        return getScoredAttributeGroupForAttribute(aa).getScore() > MATCH_ON_DEFAULT_SCORE;
    }

    /**
     * Checks if the given {@link AudioAttributes} and zone id are supported by this
     * {@link AudioProductStrategy}.
     *
     * @param aa the {@link AudioAttributes} to be considered
     * @param zoneId to be considered
     * @return true if the {@link AudioProductStrategy} supports the given {@link AudioAttributes},
     *         false otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public boolean supportsAudioAttributes(@NonNull AudioAttributes aa, int zoneId) {
        int score = getAudioAttributesSupportScore(aa, zoneId);
        return score > MATCH_ON_DEFAULT_SCORE && score != MATCH_ON_ZONE_ID_SCORE;
    }

    /**
     * Checks if the strategy supports the given {@link AudioAttributes} and gives a
     * compatibility score.
     *
     * @param aa to evaluate
     * @param zoneId to be considered
     * @return {@code NO_MATCH} if not supporting the given {@link AudioAttributes},
     * positive or zero score otherwise.
     */
    private int getAudioAttributesSupportScore(@NonNull AudioAttributes aa, int zoneId) {
        return getScoredAttributeGroupForAttribute(aa, zoneId).getScore();
    }

    private ScoredAudioAttributesGroup getScoredAttributeGroupForAttribute(
            @NonNull AudioAttributes aa, int zoneId) {
        Objects.requireNonNull(aa, "AudioAttributes must not be null");
        int bestScore = NO_MATCH;
        AudioAttributesGroup bestAttributGroupOrDefault = null;
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            int score = aag.getAttributesMatchingScore(aa, mZoneId, zoneId);
            if (isMatchScoreEquals(score)) {
                return new ScoredAudioAttributesGroup(MATCH_EQUALS, aag);
            }
            if (score > bestScore) {
                bestAttributGroupOrDefault = aag;
                bestScore = score;
            }
        }
        return new ScoredAudioAttributesGroup(bestScore, bestAttributGroupOrDefault);
    }

    /**
     * Get score and audio attribute group for audio attributes
     *
     * @param aa the {@link AudioAttributes} to be considered
     * @return the {@link ScoredAudioAttributesGroup} containing the best matching score and the
     *         associated {@link AudioAttributesGroup}. If no match is found, the score is
     *         {@code NO_MATCH} and the {@link AudioAttributesGroup} is {@code null}.
     */
    private ScoredAudioAttributesGroup getScoredAttributeGroupForAttribute(
            @NonNull AudioAttributes aa) {
        Objects.requireNonNull(aa, "AudioAttributes must not be null");
        int bestScore = NO_MATCH;
        AudioAttributesGroup bestAttributGroupOrDefault = null;
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            int score = aag.getAttributesMatchingScore(aa);
            if (isMatchScoreEquals(score)) {
                return new ScoredAudioAttributesGroup(MATCH_EQUALS, aag);
            }
            if (score > bestScore) {
                bestAttributGroupOrDefault = aag;
                bestScore = score;
            }
        }
        return new ScoredAudioAttributesGroup(bestScore, bestAttributGroupOrDefault);
    }

    /**
     * @param streamType legacy stream type used for volume operation only
     * @return the volume group id relevant for the given streamType.
     *         If none is found, {@code DEFAULT_VOLUME_GROUP} is returned.
     *
     * @hide
     */
    @TestApi
    public int getVolumeGroupIdForLegacyStreamType(int streamType) {
        AudioAttributesGroup aag = getAudioAttributeGroupForLegacyStreamType(streamType);
        return aag != null ? aag.getVolumeGroupId() : DEFAULT_VOLUME_GROUP;
    }

    /**
     * Selects the {@link AudioVolumeGroup} id associated with highest matching
     * {@link AudioAttributes} score.
     * @param attributes the {@link AudioAttributes} to be considered
     * @return the volume group id associated with the highest and non zero matching
     * {@link AudioAttributes} score, {@code DEFAULT_VOLUME_GROUP} otherwise.
     *
     * @hide
     */
    @TestApi
    public int getVolumeGroupIdForAudioAttributes(@NonNull AudioAttributes attributes) {
        if (multiZoneAudio()) {
            return getVolumeGroupIdForAudioAttributes(attributes, mZoneId);
        }
        ScoredAudioAttributesGroup scoredAag = getScoredAttributeGroupForAttribute(attributes);
        AudioAttributesGroup aag = scoredAag.getAudioAttributesGroup();
        int score = scoredAag.getScore();
        return (aag != null && score != MATCH_ON_DEFAULT_SCORE)
                ? aag.getVolumeGroupId() : DEFAULT_VOLUME_GROUP;
    }

    /**
     * Selects the {@link AudioVolumeGroup} id associated with highest matching
     * {@link AudioAttributes} score.
     * @param attributes the {@link AudioAttributes} to be considered
     * @return the volume group id associated with the highest and non zero matching
     * {@link AudioAttributes} score, {@code DEFAULT_VOLUME_GROUP} otherwise.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_ZONE_AUDIO)
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public int getVolumeGroupIdForAudioAttributes(@NonNull AudioAttributes attributes, int zoneId) {
        ScoredAudioAttributesGroup scoredAag = getScoredAttributeGroupForAttribute(attributes,
                zoneId);
        AudioAttributesGroup aag = scoredAag.getAudioAttributesGroup();
        int score = scoredAag.getScore();
        return (aag != null && !isDefaultMatchScore(score))
                ? aag.getVolumeGroupId() : DEFAULT_VOLUME_GROUP;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mId);
        dest.writeInt(mZoneId);
        dest.writeInt(mAudioAttributesGroups.length);
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            aag.writeToParcel(dest, flags);
        }
    }

    @NonNull
    public static final Parcelable.Creator<AudioProductStrategy> CREATOR =
            new Parcelable.Creator<AudioProductStrategy>() {
                @Override
                public AudioProductStrategy createFromParcel(@NonNull Parcel in) {
                    String name = in.readString();
                    int id = in.readInt();
                    int zoneId = in.readInt();
                    int nbAttributesGroups = in.readInt();
                    AudioAttributesGroup[] aag = new AudioAttributesGroup[nbAttributesGroups];
                    for (int index = 0; index < nbAttributesGroups; index++) {
                        aag[index] = AudioAttributesGroup.CREATOR.createFromParcel(in);
                    }
                    return new AudioProductStrategy(name, id, zoneId, aag);
                }

                @Override
                public @NonNull AudioProductStrategy[] newArray(int size) {
                    return new AudioProductStrategy[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        return toString("");
    }

    @NonNull
    String toString(@NonNull String indent) {
        StringBuilder s = new StringBuilder();
        s.append("\n").append(indent).append("Name: ").append(mName);
        s.append(" Id: ").append(mId);
        if (multiZoneAudio()) {
            s.append(" ZoneId: ");
            s.append(mZoneId);
        }
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            s.append(aag.toString(indent)).append(indent);
        }
        return s.toString();
    }

    /**
     * @hide
     * Default attributes, with default source to be aligned with native.
     */
    private static final @NonNull AudioAttributes DEFAULT_ATTRIBUTES =
            new AudioAttributes.Builder().build();

    /**
     * @hide
     */
    @TestApi
    public static @NonNull AudioAttributes getDefaultAttributes() {
        return DEFAULT_ATTRIBUTES;
    }

    /** Internal strategies to AudioPolicy, no external volume control allowed */
    private static final String INTERNAL_TAG = "reserved_internal_strategy";

    /**
     * To avoid duplicating the logic in java and native, we shall make use of
     * native API native_get_product_strategies_from_audio_attributes
     * Keep in sync with native counterpart code in
     * frameworks/av/media/libaudioclient/AudioProductStrategy::attributesMatchesScore
     *
     * @param refAttr {@link AudioAttributes} to be taken as the reference
     * @param attr {@link AudioAttributes} of the requester.
     * @param refZoneId zone id of the strategy evaluated
     * @param zoneId zone id to consider
     * @return matching score
     */
    private static int attributesAndZonesMatchesScore(@NonNull AudioAttributes refAttr,
            @NonNull AudioAttributes attr, int refZoneId, int zoneId) {
        if (zoneId != refZoneId && refZoneId != DEFAULT_ZONE_ID) {
            // Default zone shall match for all zoneId requested to ensure a fallback
            return NO_MATCH;
        }
        int score = MATCH_ON_DEFAULT_SCORE;
        if (refZoneId == zoneId) {
            score |= MATCH_ON_ZONE_ID_SCORE;
        }
        final int attributeMatchScore = attributesMatchesScore(refAttr, attr);
        if (attributeMatchScore == NO_MATCH) {
            return NO_MATCH;
        }
        return score | attributeMatchScore;
    }

    /**
     * To avoid duplicating the logic in java and native, we shall make use of
     * native API native_get_product_strategies_from_audio_attributes
     * Keep in sync with native counterpart code in
     * frameworks/av/media/libaudioclient/AudioProductStrategy::attributesMatchesScore
     * @param refAttr {@link AudioAttributes} to be taken as the reference
     * @param attr {@link AudioAttributes} of the requester.
     * @return matching score
     */
    private static int attributesMatchesScore(@NonNull AudioAttributes refAttr,
            @NonNull AudioAttributes attr) {
        Objects.requireNonNull(refAttr, "Reference audio attributes must not be null");
        Objects.requireNonNull(attr, "Audio attributes to check must not be null");
        if (refAttr.equals(attr)) {
            return MATCH_ATTRIBUTES_EQUALS;
        }
        if (refAttr.equals(DEFAULT_ATTRIBUTES)) {
            return MATCH_ON_DEFAULT_SCORE;
        }
        int score = MATCH_ON_DEFAULT_SCORE;
        if (refAttr.getSystemUsage() == AudioAttributes.USAGE_UNKNOWN) {
            score |= MATCH_ON_DEFAULT_SCORE;
        } else if (attr.getSystemUsage() == refAttr.getSystemUsage()) {
            score |= MATCH_ON_USAGE_SCORE;
        } else {
            return NO_MATCH;
        }
        if (refAttr.getContentType() == AudioAttributes.CONTENT_TYPE_UNKNOWN) {
            score |= MATCH_ON_DEFAULT_SCORE;
        } else if (attr.getContentType() == refAttr.getContentType()) {
            score |= MATCH_ON_CONTENT_TYPE_SCORE;
        } else {
            return NO_MATCH;
        }
        String refFormattedTags = TextUtils.join(";", refAttr.getTags());
        String cliFormattedTags = TextUtils.join(";", attr.getTags());
        if (refFormattedTags.length() == 0) {
            score |= MATCH_ON_DEFAULT_SCORE;
        } else if (refFormattedTags.equals(cliFormattedTags)) {
            score |= MATCH_ON_TAGS_SCORE;
        } else {
            return NO_MATCH;
        }
        if ((refAttr.getAllFlags() & AUDIO_FLAGS_AFFECT_STRATEGY_SELECTION) == 0) {
            score |= MATCH_ON_DEFAULT_SCORE;
        } else if (((attr.getAllFlags() & AUDIO_FLAGS_AFFECT_STRATEGY_SELECTION) != 0)
                && ((attr.getAllFlags() & refAttr.getAllFlags()) == refAttr.getAllFlags())) {
            score |= MATCH_ON_FLAGS_SCORE;
        } else {
            return NO_MATCH;
        }
        return score;
    }

    @Nullable
    private AudioAttributesGroup getAudioAttributeGroupForLegacyStreamType(int streamType) {
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.supportsStreamType(streamType)) {
                return aag;
            }
        }
        return null;
    }

    private boolean isInternalStrategy() {
        for (AudioAttributesGroup aag : mAudioAttributesGroups) {
            if (aag.isInternalStrategy()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the strategy is an internal strategy, for exclusive use of the frameworks.
     * Internal strategies are the strategy for reserved use by native audio service (e.g.
     * patch and rerouting strategies).
     * It is idendified by specific {@code #INTERNAL_TAG} tag.
     */
    /** private package */ static boolean isInternalAttributesForStrategy(
            @NonNull AudioAttributes aa) {
        final String formattedTags = TextUtils.join(";", aa.getTags());
        return formattedTags.equals(INTERNAL_TAG);
    }

    private static final class ScoredAudioAttributesGroup {
        private final int mScore;
        private final AudioAttributesGroup mAudioAttributesGroup;

        ScoredAudioAttributesGroup(int score, AudioAttributesGroup aag) {
            mScore = score;
            mAudioAttributesGroup = aag;
        }

        public int getScore() {
            return mScore;
        }

        public AudioAttributesGroup getAudioAttributesGroup() {
            return mAudioAttributesGroup;
        }
    }

    private static final class AudioAttributesGroup implements Parcelable {
        private int mVolumeGroupId;
        private int mLegacyStreamType;
        private int mProductStrategyId;
        private final AudioAttributes[] mAudioAttributes;

        AudioAttributesGroup(int volumeGroupId, int streamType, int productStrategyId,
                @NonNull AudioAttributes[] audioAttributes) {
            mVolumeGroupId = volumeGroupId;
            mLegacyStreamType = streamType;
            mProductStrategyId = productStrategyId;
            mAudioAttributes = audioAttributes;
        }

        private boolean isInternalStrategy() {
            for (AudioAttributes aa : mAudioAttributes) {
                if (isInternalAttributesForStrategy(aa)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AudioAttributesGroup thatAag = (AudioAttributesGroup) o;

            return mVolumeGroupId == thatAag.mVolumeGroupId
                    && mLegacyStreamType == thatAag.mLegacyStreamType
                    && mProductStrategyId == thatAag.mProductStrategyId
                    && Arrays.equals(mAudioAttributes, thatAag.mAudioAttributes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mVolumeGroupId, mLegacyStreamType, mProductStrategyId,
                    Arrays.hashCode(mAudioAttributes));
        }

        public int getStreamType() {
            return mLegacyStreamType;
        }

        public int getVolumeGroupId() {
            return mVolumeGroupId;
        }

        public @NonNull AudioAttributes getAudioAttributes() {
            // We need a choice, so take the first one
            return mAudioAttributes.length == 0 ? DEFAULT_ATTRIBUTES : mAudioAttributes[0];
        }

        int getStrategyId() {
            return mProductStrategyId;
        }

        /**
         * Checks if the {@link AudioProductStrategy.AudioAttributesGroup} supports the given
         * {@link AudioAttributes} and gives a compatibility score.
         * @param attributes to evaluate
         * @return {@code NO_MATCH} if not supporting the given {@link AudioAttributes},
         * positive or zero score otherwise.
         */
        public int getAttributesMatchingScore(@NonNull AudioAttributes attributes) {
            int strategyScore = NO_MATCH;
            for (AudioAttributes refAa : mAudioAttributes) {
                int attributesGroupScore = attributesMatchesScore(refAa, attributes);
                if (isMatchScoreEquals(attributesGroupScore)) {
                    return attributesGroupScore;
                }
                strategyScore = Math.max(strategyScore, attributesGroupScore);
            }
            return strategyScore;
        }

        /**
         * Checks if the {@link AudioProductStrategy.AudioAttributesGroup} supports the given
         * {@link AudioAttributes} and gives a compatibility score.
         *
         * @param attributes to evaluate
         * @param refZoneId zone id of the strategy evaluated
         * @param zoneId zone id to consider
         * @return {@code NO_MATCH} if not supporting the given {@link AudioAttributes},
         * positive or zero score otherwise.
         */
        int getAttributesMatchingScore(@NonNull AudioAttributes attributes, int refZoneId,
                int zoneId) {
            int strategyScore = NO_MATCH;
            for (AudioAttributes refAa : mAudioAttributes) {
                int attributesGroupScore = attributesAndZonesMatchesScore(refAa, attributes,
                        refZoneId, zoneId);
                if (isMatchScoreEquals(attributesGroupScore)) {
                    return attributesGroupScore;
                }
                strategyScore = Math.max(strategyScore, attributesGroupScore);
            }
            return strategyScore;
        }

        public boolean supportsStreamType(int streamType) {
            return mLegacyStreamType == streamType;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mVolumeGroupId);
            dest.writeInt(mLegacyStreamType);
            dest.writeInt(mProductStrategyId);
            dest.writeInt(mAudioAttributes.length);
            for (AudioAttributes attributes : mAudioAttributes) {
                attributes.writeToParcel(dest, flags | AudioAttributes.FLATTEN_TAGS/*flags*/);
            }
        }

        public static final @android.annotation.NonNull Parcelable.Creator<AudioAttributesGroup> CREATOR =
                new Parcelable.Creator<AudioAttributesGroup>() {
                    @Override
                    public AudioAttributesGroup createFromParcel(@NonNull Parcel in) {
                        int volumeGroupId = in.readInt();
                        int streamType = in.readInt();
                        int strategyId = in.readInt();
                        int nbAttributes = in.readInt();
                        AudioAttributes[] aa = new AudioAttributes[nbAttributes];
                        for (int index = 0; index < nbAttributes; index++) {
                            aa[index] = AudioAttributes.CREATOR.createFromParcel(in);
                        }
                        return new AudioAttributesGroup(volumeGroupId, streamType, strategyId, aa);
                    }

                    @Override
                    public @NonNull AudioAttributesGroup[] newArray(int size) {
                        return new AudioAttributesGroup[size];
                    }
                };


        @Override
        public @NonNull String toString() {
            return toString("");
        }

        String toString(String indent) {
            StringBuilder s = new StringBuilder();
            s.append("\n").append(indent).append("Legacy Stream Type: ");
            s.append(mLegacyStreamType);
            s.append(" Volume Group Id: ");
            s.append(mVolumeGroupId);

            for (AudioAttributes attribute : mAudioAttributes) {
                s.append("\n").append(indent).append("-");
                s.append(attribute.toString());
            }
            return s.toString();
        }
    }

    private static final String INDENT = "  ";

    /**
     * @hide
     */
    public static void dump(@NonNull PrintWriter pw) {
        pw.println("- AUDIO PRODUCT STRATEGIES:");
        getAudioProductStrategiesFromService(/* filterInternal= */ true).forEach(aps -> {
            pw.printf("%s%s\n", INDENT, aps.toString(INDENT + INDENT));
        });
        pw.println();
        pw.println("- AUDIO VOLUME GROUPS:");
        AudioVolumeGroup.getAudioVolumeGroups().forEach(avg -> {
            pw.printf("%s%s\n", INDENT, avg.toString(INDENT + INDENT));
        });
        pw.println();
    }
}
