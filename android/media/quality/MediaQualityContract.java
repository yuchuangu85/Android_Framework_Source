/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;


import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.StringDef;
import android.media.tv.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The contract between the media quality service and applications. Contains definitions for the
 * commonly used parameter names.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public class MediaQualityContract {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "LEVEL_", value = {
            LEVEL_LOW,
            LEVEL_MEDIUM,
            LEVEL_HIGH,
            LEVEL_OFF,
            LEVEL_USER,
            LEVEL_UNKNOWN,
    })
    public @interface Level {}

    /**
     * Low level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the low level
     * option.
     */
    public static final String LEVEL_LOW = "level_low";

    /**
     * Medium level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the medium level
     * option.
     */
    public static final String LEVEL_MEDIUM = "level_medium";

    /**
     * High level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is turned on with the high level
     * option.
     */
    public static final String LEVEL_HIGH = "level_high";

    /**
     * Off level for parameters.
     *
     * <p>This level represents that the corresponding feature is turned off.
     */
    public static final String LEVEL_OFF = "level_off";

    /**
     * User level option for a parameter.
     *
     * <p>This level represents that the corresponding feature is controlled by the user.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_USER = "level_user";

    /**
     * Unknown level for a parameter.
     *
     * <p>This value is used to represent a level that is not recognized by the current version
     * of the SDK. It serves as a fallback to ensure backwards compatibility when new levels
     * are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_UNKNOWN = "level_unknown";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "COLOR_TEMP", value = {
            COLOR_TEMP_USER,
            COLOR_TEMP_COOL,
            COLOR_TEMP_STANDARD,
            COLOR_TEMP_WARM,
            COLOR_TEMP_USER_HDR10PLUS,
            COLOR_TEMP_COOL_HDR10PLUS,
            COLOR_TEMP_STANDARD_HDR10PLUS,
            COLOR_TEMP_WARM_HDR10PLUS,
            COLOR_TEMP_FMMSDR,
            COLOR_TEMP_FMMHDR,
            COLOR_TEMP_UNKNOWN,
    })
    public @interface ColorTempValue {}

    /**
     * Key for the "User" color temperature preset.
     * <p>
     * Represents a custom color temperature configuration defined by the user.
     * Unlike the fixed presets (Cool, Standard, Warm), this mode typically
     * allows for manual adjustment of RGB gain and offset values to achieve
     * a specific white point.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_USER = "color_temp_user";

    /**
     * Key for the "Cool" color temperature preset.
     * <p>
     * Represents a cooler, bluish white point, typically with a color temperature
     * higher than 7000K (often 9000K-11000K). This makes white appear crisp and bright,
     * which can be desirable for sports content or viewing in brightly lit environments.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_COOL = "color_temp_cool";

    /**
     * Key for the "Standard" color temperature preset.
     * <p>
     * Represents a balanced white point, typically positioned between Cool and Warm.
     * This is often the default factory setting, offering a compromise between brightness
     * and color accuracy suitable for general daytime viewing.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_STANDARD = "color_temp_standard";

    /**
     * Key for the "Warm" color temperature preset.
     * <p>
     * Represents a warmer, yellowish/reddish white point, typically targeted at
     * the D65 standard (6500K). This is the industry standard for cinema and
     * high-end video production. It is the recommended setting for movie watching
     * in dim environments to ensure colors are seen as the director intended.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_WARM = "color_temp_warm";

    /**
     * Key for the "User" color temperature preset, specifically applied during
     * HDR10+ content playback.
     * <p>
     * Stores custom user adjustments for white balance that are applied only when
     * the display is in HDR10+ mode. This ensures that manual calibrations for
     * High Dynamic Range content do not affect SDR viewing.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_USER_HDR10PLUS = "color_temp_user_hdr10plus";

    /**
     * Key for the "Cool" color temperature preset, specifically applied during
     * HDR10+ content playback.
     * <p>
     * Applies a high-Kelvin (bluish) white point optimized for the higher
     * brightness levels of HDR10+ content.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_COOL_HDR10PLUS = "color_temp_cool_hdr10plus";

    /**
     * Key for the "Standard" color temperature preset, specifically applied during
     * HDR10+ content playback.
     * <p>
     * Applies a balanced white point optimized for the higher brightness levels
     * of HDR10+ content.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_STANDARD_HDR10PLUS = "color_temp_standard_hdr10plus";

    /**
     * Key for the "Warm" color temperature preset, specifically applied during
     * HDR10+ content playback.
     * <p>
     * Applies a D65-targeted (6500K) white point optimized for HDR10+ content.
     * Maintaining D65 accuracy is critical in HDR to prevent bright highlights
     * from appearing tinted.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_WARM_HDR10PLUS = "color_temp_warm_hdr10plus";

    /**
     * Key for the color temperature used in Filmmaker Mode (FMM) during
     * Standard Dynamic Range (SDR) playback.
     * <p>
     * Filmmaker Mode is designed to preserve the creative intent of the content creator.
     * Consequently, this parameter is typically locked to the industry standard D65
     * (6500K) white point to ensure accurate color reproduction for SDR movies.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_FMMSDR = "color_temp_fmmsdr";

    /**
     * Key for the color temperature used in Filmmaker Mode (FMM) during
     * High Dynamic Range (HDR) playback.
     * <p>
     * Similar to {@link #COLOR_TEMP_FMMSDR}, this parameter enforces the creative
     * intent for HDR content. It targets the D65 (6500K) white point, calibrated
     * specifically for the HDR tone mapping curve to ensure accurate highlights
     * and shadow detail.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_FMMHDR = "color_temp_fmmhdr";

    /**
     * Key for an unknown color temperature preset.
     *
     * <p>Represents a color temperature state that the system cannot identify or that is
     * defined in a newer version of the API than the one the application is currently using.
     * This ensures the application remains stable even when encountering undefined presets.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_TEMP_UNKNOWN = "color_temp_unknown";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "LEVEL_RANGE", value = {
            LEVEL_RANGE_AUTO,
            LEVEL_RANGE_LIMITED,
            LEVEL_RANGE_FULL,
            LEVEL_RANGE_UNKNOWN,
    })
    public @interface LevelRangeValue {}

    /**
     * Automatic level range option.
     *
     * <p>Represents that the level range is determined automatically by the system.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_RANGE_AUTO = "AUTO";

    /**
     * Limited level range option.
     *
     * <p>Represents a limited level range, typically for video content (e.g., 16-235).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_RANGE_LIMITED = "LIMITED";

    /**
     * Full level range option.
     *
     * <p>Represents a full level range, typically for PC content (e.g., 0-255).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_RANGE_FULL = "FULL";

    /**
     * Unknown level range option.
     *
     * <p>This value is used to represent a quantization level range that is not recognized
     * by the current version of the SDK. It serves as a fallback to ensure backwards
     * compatibility when new level range options are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String LEVEL_RANGE_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "HDMIRGB_RANGE", value = {
            HDMIRGB_RANGE_AUTO,
            HDMIRGB_RANGE_LIMITED,
            HDMIRGB_RANGE_FULL,
            HDMIRGB_RANGE_UNKNOWN,
    })
    public @interface HdmiRgbRangeValue {}

    /**
     * Automatic HDMI RGB range option.
     *
     * <p>Represents that the HDMI RGB range is determined automatically by the system.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String HDMIRGB_RANGE_AUTO = "AUTO";

    /**
     * Limited HDMI RGB range option.
     *
     * <p>Represents a limited HDMI RGB range, typically for video content (e.g., 16-235).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String HDMIRGB_RANGE_LIMITED = "LIMITED";

    /**
     * Full HDMI RGB range option.
     *
     * <p>Represents a full HDMI RGB range, typically for PC content (e.g., 0-255).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String HDMIRGB_RANGE_FULL = "FULL";

    /**
     * Unknown HDMI RGB range option.
     *
     * <p>This value is used to represent an HDMI RGB range that is not recognized by the
     * current version of the SDK. It serves as a fallback to ensure backwards compatibility
     * when new HDMI RGB range options are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String HDMIRGB_RANGE_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "COLOR_SPACE", value = {
            COLOR_SPACE_AUTO,
            COLOR_SPACE_S_RGB_BT_709,
            COLOR_SPACE_DCI,
            COLOR_SPACE_ADOBE_RGB,
            COLOR_SPACE_BT2020,
            COLOR_SPACE_ON,
            COLOR_SPACE_OFF,
            COLOR_SPACE_UNKNOWN,
    })
    public @interface ColorSpaceValue {}

    /**
     * Automatic color space option.
     *
     * <p>Represents that the color space is determined automatically by the system.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_AUTO = "AUTO";

    /**
     * sRGB/BT.709 color space option.
     *
     * <p>Represents the sRGB/BT.709 color space, standard for web and HD video.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_S_RGB_BT_709 = "S_RGB_BT_709";

    /**
     * DCI-P3 color space option.
     *
     * <p>Represents the DCI-P3 color space, commonly used in digital cinema.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_DCI = "DCI";

    /**
     * Adobe RGB color space option.
     *
     * <p>Represents the Adobe RGB color space.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_ADOBE_RGB = "ADOBE_RGB";

    /**
     * BT.2020 color space option.
     *
     * <p>Represents the BT.2020 color space, used for Ultra HD.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_BT2020 = "BT2020";

    /**
     * On option for color space.
     *
     * <p>Represents that the color space feature is turned on.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_ON = "ON";

    /**
     * Off option for color space.
     *
     * <p>Represents that the color space feature is turned off.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_OFF = "OFF";

    /**
     * Unknown color space option.
     *
     * <p>This value is used to represent a color space that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * color space options are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String COLOR_SPACE_UNKNOWN = "UNKNOWN";

    /**
     * Defines the supported display panel technology types.
     * <p>
     * This is used with
     * {@link android.media.quality.MediaQualityManager#usesDisplayTechnology(int)}
     * to query if a specific panel technology is used by the device.
     *
     * <p>More panel technologies will be added here in the future.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PANEL_TECHNOLOGY" }, value = {
            PANEL_TECHNOLOGY_OLED,
            PANEL_TECHNOLOGY_UNKNOWN,
    })
    public @interface PanelTechnology {}

    /**
     * Display panel technology for OLED (Organic Light Emitting Diode).
     * <p>OLED displays are known for their high contrast ratios and deep blacks.
     *
     * Corresponds to PanelTechnologyType.OLED in the HAL.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final int PANEL_TECHNOLOGY_OLED = 0;

    /**
     * Unknown display panel technology type.
     *
     * <p>This value is used to represent a panel technology that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * technologies are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final int PANEL_TECHNOLOGY_UNKNOWN = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "GAMMA", value = {
            GAMMA_DARK,
            GAMMA_MIDDLE,
            GAMMA_BRIGHT,
            GAMMA_UNKNOWN,
    })
    public @interface GammaValue {}

    /**
     * Dark gamma option.
     *
     * <p>Represents a dark gamma setting.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String GAMMA_DARK = "DARK";

    /**
     * Middle gamma option.
     *
     * <p>Represents a middle (neutral) gamma setting.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String GAMMA_MIDDLE = "MIDDLE";

    /**
     * Bright gamma option.
     *
     * <p>Represents a bright gamma setting.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String GAMMA_BRIGHT = "BRIGHT";

    /**
     * Unknown gamma option.
     *
     * <p>This value is used to represent a gamma setting that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * gamma settings are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String GAMMA_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "PICTURE_QUALITY_EVENT_TYPE", value = {
            PICTURE_QUALITY_EVENT_TYPE_NONE,
            PICTURE_QUALITY_EVENT_TYPE_BBD_RESULT,
            PICTURE_QUALITY_EVENT_TYPE_VIDEO_DELAY_CHANGE,
            PICTURE_QUALITY_EVENT_TYPE_CAPTUREPOINT_INFO_CHANGE,
            PICTURE_QUALITY_EVENT_TYPE_VIDEOPATH_CHANGE,
            EXTRA_PICTURE_QUALITY_EVENT_TYPE_FRAME_CHANGE,
            PICTURE_QUALITY_EVENT_TYPE_DOLBY_IQ_CHANGE,
            PICTURE_QUALITY_EVENT_TYPE_DOLBY_APO_CHANGE,
            PICTURE_QUALITY_EVENT_TYPE_UNKNOWN,
    })
    public @interface PictureQualityEventTypeValue {}

    /**
     * None event type.
     *
     * <p>Indicates that no picture quality event has occurred.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_NONE = "NONE";

    /**
     * BBD result event type.
     *
     * <p>Indicates an event with a result from Black Bar Detection (BBD).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_BBD_RESULT = "BBD_RESULT";

    /**
     * Video delay change event type.
     *
     * <p>Indicates an event for a change in video processing delay.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_VIDEO_DELAY_CHANGE = "VIDEO_DELAY_CHANGE";

    /**
     * Capture point info change event type.
     *
     * <p>Indicates an event for a change in the capture point information.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_CAPTUREPOINT_INFO_CHANGE =
            "CAPTUREPOINT_INFO_CHANGE";

    /**
     * Video path change event type.
     *
     * <p>Indicates an event for a change in the video path.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_VIDEOPATH_CHANGE = "VIDEOPATH_CHANGE";

    /**
     * Frame change event type.
     *
     * <p>Indicates a picture quality event related to a frame change. This is an extra data key.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String EXTRA_PICTURE_QUALITY_EVENT_TYPE_FRAME_CHANGE =
            "android.media.quality.extra.PICTURE_QUALITY_EVENT_TYPE_FRAME_CHANGE";

    /**
     * Dolby IQ change event type.
     *
     * <p>Indicates an event for a change related to Dolby IQ processing.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_DOLBY_IQ_CHANGE = "DOLBY_IQ_CHANGE";

    /**
     * Dolby APO change event type.
     *
     * <p>Indicates an event for a change related to a Dolby Audio Processing Object (APO).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_DOLBY_APO_CHANGE = "DOLBY_APO_CHANGE";

    /**
     * Unknown picture quality event type.
     *
     * <p>This value is used to represent a picture quality event type that is not recognized
     * by the current version of the SDK. It serves as a fallback to ensure backwards
     * compatibility when new event types are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String PICTURE_QUALITY_EVENT_TYPE_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "STREAM_STATUS", value = {
            STREAM_STATUS_SDR,
            STREAM_STATUS_DOLBY_VISION,
            STREAM_STATUS_HDR10,
            STREAM_STATUS_TCH,
            STREAM_STATUS_HLG,
            STREAM_STATUS_HDR10_PLUS,
            STREAM_STATUS_HDR_VIVID,
            STREAM_STATUS_IMAX_SDR,
            STREAM_STATUS_IMAX_HDR10,
            STREAM_STATUS_IMAX_HDR10_PLUS,
            STREAM_STATUS_FMM_SDR,
            STREAM_STATUS_FMM_HDR10,
            STREAM_STATUS_FMM_HDR10_PLUS,
            STREAM_STATUS_FMM_HLG,
            STREAM_STATUS_HDR10_PLUS,
            STREAM_STATUS_FMM_DOLBY,
            STREAM_STATUS_FMM_TCH,
            STREAM_STATUS_FMM_HDR_VIVID,
            STREAM_STATUS_UNKNOWN,
    })
    public @interface StreamStatusValue {}

    /**
     * SDR stream status.
     *
     * <p>Represents that the stream is Standard Dynamic Range (SDR).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_SDR = "SDR";

    /**
     * Dolby Vision stream status.
     *
     * <p>Represents that the stream is Dolby Vision.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_DOLBY_VISION = "DOLBYVISION";

    /**
     * HDR10 stream status.
     *
     * <p>Represents that the stream is HDR10.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_HDR10 = "HDR10";

    /**
     * TCH stream status.
     *
     * <p>Represents that the stream is TCH.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_TCH = "TCH";

    /**
     * HLG stream status.
     *
     * <p>Represents that the stream is Hybrid Log-Gamma (HLG).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_HLG = "HLG";

    /**
     * HDR10+ stream status.
     *
     * <p>Represents that the stream is HDR10+.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_HDR10_PLUS = "HDR10PLUS";

    /**
     * HDR Vivid stream status.
     *
     * <p>Represents that the stream is HDR Vivid.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_HDR_VIVID = "HDRVIVID";

    /**
     * IMAX SDR stream status.
     *
     * <p>Represents that the stream is IMAX SDR.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_IMAX_SDR = "IMAXSDR";

    /**
     * IMAX HDR10 stream status.
     *
     * <p>Represents that the stream is IMAX HDR10.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_IMAX_HDR10 = "IMAXHDR10";

    /**
     * IMAX HDR10+ stream status.
     *
     * <p>Represents that the stream is IMAX HDR10+.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_IMAX_HDR10_PLUS = "IMAXHDR10PLUS";

    /**
     * FMM SDR stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with SDR.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_SDR = "FMMSDR";

    /**
     * FMM HDR10 stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with HDR10.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_HDR10 = "FMMHDR10";

    /**
     * FMM HDR10+ stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with HDR10+.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_HDR10_PLUS = "FMMHDR10PLUS";

    /**
     * FMM HLG stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with HLG.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_HLG = "FMMHLG";

    /**
     * FMM Dolby stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with Dolby.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_DOLBY = "FMMDOLBY";

    /**
     * FMM TCH stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with TCH.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_TCH = "FMMTCH";

    /**
     * FMM HDR Vivid stream status.
     *
     * <p>Represents that the stream is in Filmmaker Mode (FMM) with HDR Vivid.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_FMM_HDR_VIVID = "FMMHDRVIVID";

    /**
     * Unknown stream status.
     *
     * <p>This value is used to represent a stream status that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * stream statuses are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String STREAM_STATUS_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "DOWN_MIX_MODE", value = {
            DOWN_MIX_MODE_STEREO,
            DOWN_MIX_MODE_SURROUND,
            DOWN_MIX_MODE_UNKNOWN,
    })
    public @interface DownMixModeValue {}

    /**
     * Stereo down-mix mode option.
     *
     * <p>Represents a down-mix mode that converts multi-channel audio to a standard stereo
     * output (Lo/Ro).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOWN_MIX_MODE_STEREO = "STEREO";

    /**
     * Surround down-mix mode option.
     *
     * <p>Represents a down-mix mode that converts multi-channel audio to a matrix-encoded surround
     * sound output (Lt/Rt).
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOWN_MIX_MODE_SURROUND = "SURROUND";

    /**
     * Unknown down-mix mode option.
     *
     * <p>This value is used to represent a down-mix mode that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * down-mix modes are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOWN_MIX_MODE_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "SOUND_STYLE", value = {
            SOUND_STYLE_USER,
            SOUND_STYLE_STANDARD,
            SOUND_STYLE_VIVID,
            SOUND_STYLE_SPORTS,
            SOUND_STYLE_MOVIE,
            SOUND_STYLE_MUSIC,
            SOUND_STYLE_NEWS,
            SOUND_STYLE_AUTO,
            SOUND_STYLE_UNKNOWN,
    })
    public @interface SoundStyleValue {}

    /**
     * User-defined sound style.
     *
     * <p>Represents a sound style customized by the user.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_USER = "USER";

    /**
     * Standard sound style.
     *
     * <p>Represents a standard, neutral sound style.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_STANDARD = "STANDARD";

    /**
     * Vivid sound style.
     *
     * <p>Represents a vivid and dynamic sound style.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_VIVID = "VIVID";

    /**
     * Sports sound style.
     *
     * <p>Represents a sound style optimized for sports content.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_SPORTS = "SPORTS";

    /**
     * Movie sound style.
     *
     * <p>Represents a sound style optimized for movies.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_MOVIE = "MOVIE";

    /**
     * Music sound style.
     *
     * <p>Represents a sound style optimized for music.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_MUSIC = "MUSIC";

    /**
     * News sound style.
     *
     * <p>Represents a sound style optimized for news and dialogue.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_NEWS = "NEWS";

    /**
     * Automatic sound style.
     *
     * <p>Represents that the sound style is determined automatically by the system.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_AUTO = "AUTO";

    /**
     * Unknown sound style.
     *
     * <p>This value is used to represent a sound style that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * sound styles are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String SOUND_STYLE_UNKNOWN = "UNKNOWN";

    /**
     * Defines the allowed values for the 3D mode parameter.
     * <p>
     * 3D mode specifies the format of the incoming stereoscopic video signal,
     * allowing the display to correctly interpret and render the separate images
     * intended for the left and right eyes. The mode typically corresponds to
     * common 3D formats such as Side-by-Side, Top-and-Bottom, or Frame Packing.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "THREE_D_MODE_", value = {
            THREE_D_MODE_OFF,
            THREE_D_MODE_SIDE_BY_SIDE,
            THREE_D_MODE_TOP_AND_BOTTOM,
            THREE_D_MODE_FRAME_PACKING,
            THREE_D_MODE_UNKNOWN,
    })
    public @interface ThreeDModeValue {}

    /**
     * Disables 3D mode.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String THREE_D_MODE_OFF = "off";

    /**
     * Side-by-side 3D mode, where the left and right views are in the same
     * frame, placed horizontally next to each other.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String THREE_D_MODE_SIDE_BY_SIDE = "side_by_side";

    /**
     * Top-and-bottom 3D mode, where the left and right views are in the same
     * frame, placed vertically on top of each other.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String THREE_D_MODE_TOP_AND_BOTTOM = "top_and_bottom";

    /**
     * Frame packing 3D mode, where left and right eye views are packed into
     * a single frame. This format typically provides full resolution for
     * each eye.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String THREE_D_MODE_FRAME_PACKING = "frame_packing";

    /**
     * Unknown 3D mode.
     *
     * <p>This value is used to represent a 3D mode that is not recognized by the current version
     * of the SDK. It serves as a fallback to ensure backwards compatibility when new 3D modes
     * are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String THREE_D_MODE_UNKNOWN = "unknown";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "DIGITAL_OUTPUT_MODE", value = {
            DIGITAL_OUTPUT_MODE_AUTO,
            DIGITAL_OUTPUT_MODE_BYPASS,
            DIGITAL_OUTPUT_MODE_PCM,
            DIGITAL_OUTPUT_MODE_DOLBY_DIGITAL_PLUS,
            DIGITAL_OUTPUT_MODE_DOLBY_DIGITAL,
            DIGITAL_OUTPUT_MODE_DOLBY_MAT,
            DIGITAL_OUTPUT_MODE_UNKNOWN,
    })
    public @interface DigitalOutputModeValue {}

    /**
     * Automatic digital output mode.
     *
     * <p>The system automatically selects the preferred digital audio format supported by the
     * connected device.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_AUTO = "AUTO";

    /**
     * Bypass digital output mode.
     *
     * <p>The encoded audio stream is sent directly to the output without being decoded by the
     * device.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_BYPASS = "BYPASS";

    /**
     * PCM digital output mode.
     *
     * <p>Audio is decoded to uncompressed Pulse-Code Modulation (PCM) before output.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_PCM = "PCM";

    /**
     * Dolby Digital Plus output mode.
     *
     * <p>Audio is output in Dolby Digital Plus (E-AC-3) format.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_DOLBY_DIGITAL_PLUS = "DolbyDigitalPlus";

    /**
     * Dolby Digital output mode.
     *
     * <p>Audio is output in Dolby Digital (AC-3) format.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_DOLBY_DIGITAL = "DolbyDigital";

    /**
     * Dolby MAT output mode.
     *
     * <p>Audio is output in Dolby Meridian Audio Taper (MAT) format.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_DOLBY_MAT = "DolbyMat";

    /**
     * Unknown digital output mode.
     *
     * <p>This value is used to represent a digital audio output mode that is not recognized
     * by the current version of the SDK. It serves as a fallback to ensure backwards
     * compatibility when new digital output modes are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DIGITAL_OUTPUT_MODE_UNKNOWN = "UNKNOWN";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "DOLBY_SOUND_MODE", value = {
            DOLBY_SOUND_MODE_GAME,
            DOLBY_SOUND_MODE_MOVIE,
            DOLBY_SOUND_MODE_MUSIC,
            DOLBY_SOUND_MODE_NEWS,
            DOLBY_SOUND_MODE_STANDARD,
            DOLBY_SOUND_MODE_STADIUM,
            DOLBY_SOUND_MODE_USER,
            DOLBY_SOUND_MODE_UNKNOWN,
    })
    public @interface DolbySoundModeValue {}

    /**
     * Game sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode optimized for gaming.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_GAME = "GAME";

    /**
     * Movie sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode optimized for movies.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_MOVIE = "MOVIE";

    /**
     * Music sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode optimized for music.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_MUSIC = "MUSIC";

    /**
     * News sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode optimized for news and dialogue.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_NEWS = "NEWS";

    /**
     * Standard sound mode for Dolby audio.
     *
     * <p>Represents a standard, neutral Dolby sound mode.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_STANDARD = "STANDARD";

    /**
     * Stadium sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode that simulates a stadium environment.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_STADIUM = "STADIUM";

    /**
     * User-defined sound mode for Dolby audio.
     *
     * <p>Represents a Dolby sound mode customized by the user.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_USER = "USER";

    /**
     * Unknown Dolby sound mode.
     *
     * <p>This value is used to represent a Dolby sound mode that is not recognized by the current
     * version of the SDK. It serves as a fallback to ensure backwards compatibility when new
     * Dolby sound modes are introduced in future platform versions.
     */
    @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
    public static final String DOLBY_SOUND_MODE_UNKNOWN = "UNKNOWN";


    /** @hide */
    public interface BaseParameters {
        String PARAMETER_ID = "_id";
        String PARAMETER_TYPE = "_type";
        String PARAMETER_NAME = "_name";
        String PARAMETER_PACKAGE = "_package";
        String PARAMETER_INPUT_ID = "_input_id";
    }

    /**
     * Parameters picture quality.
     */
    public static final class PictureQuality {
        /**
         * The brightness.
         *
         * <p>Brightness value range are from 0.0 to 1.0 (inclusive), where 0.0 represents the
         * minimum brightness and 1.0 represents the maximum brightness. The content-unmodified
         * value is 0.5.
         *
         * <p>Type: FLOAT
         */
        public static final String PARAMETER_BRIGHTNESS = "brightness";

        /**
         * The contrast.
         *
         * <p>This value represents the image contrast on an arbitrary scale from 0 to 100,
         * where 0 represents the darkest black (black screen) and 100 represents the brightest
         * white (brighter).
         * The default/unmodified value for contrast is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_CONTRAST = "contrast";

        /**
         * The sharpness.
         *
         * <p>Sharpness value range are from 0 to 100 (inclusive), where 0 represents the minimum
         * sharpness that makes the image appear softer with less defined edges, 100 represents the
         * maximum sharpness that makes the image appear halos around objects due to excessive
         * edges.
         * The default/unmodified value for sharpness is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SHARPNESS = "sharpness";

        /**
         * The saturation.
         *
         * <p>Saturation value controls the intensity or purity of colors.
         * Saturation values are from 0 to 100, where 0 represents grayscale (no color) and 100
         * represents the most vivid colors.
         * The default/unmodified value for saturation is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SATURATION = "saturation";

        /**
         * The hue.
         *
         * <p>Hue affects the balance between red, green and blue primary colors on the screen.
         * Hue values are from -50 to 50, where -50 represents cooler and 50 represents warmer.
         * The default/unmodified value for hue is 0.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_HUE = "hue";

        /**
         * Adjust brightness in advance color engine. Similar to a "brightness" control on a TV
         * but acts at a lower level.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 represents the minimum brightness and
         * 100 represents the maximum brightness. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_BRIGHTNESS
         */
        public static final String PARAMETER_COLOR_TUNER_BRIGHTNESS = "color_tuner_brightness";

        /**
         * Adjust saturation in advance color engine. Similar to a "saturation" control on a TV
         * but acts at a lower level.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 being completely desaturated/grayscale
         * and 100 being the most saturated. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_SATURATION
         */
        public static final String PARAMETER_COLOR_TUNER_SATURATION = "color_tuner_saturation";

        /**
         * Adjust hue in advance color engine. Similar to a "hue" control on a TV but acts at a
         * lower level.
         *
         * <p>The range is from -50 to 50 (inclusive), where -50 represents cooler setting for a
         * specific color and 50 represents warmer setting for a specific color. The
         * default/unmodified value is 0.
         *
         * <p>Type: INTEGER
         * @see #PARAMETER_HUE
         */
        public static final String PARAMETER_COLOR_TUNER_HUE = "color_tuner_hue";

        /**
         * Advance setting for red offset. Adjust the black level of red color channels, it controls
         * the minimum intensity of each color, affecting the shadows and dark areas of the image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_RED_OFFSET = "color_tuner_red_offset";

        /**
         * Advance setting for green offset. Adjust the black level of green color channels, it
         * controls the minimum intensity of each color, affecting the shadows and dark areas of the
         * image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_OFFSET = "color_tuner_green_offset";

        /**
         * Advance setting for blue offset. Adjust the black level of blue color channels, it
         * controls the minimum intensity of each color, affecting the shadows and dark areas of the
         * image.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes shadows darker and 100 makes
         * shadows brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_OFFSET = "color_tuner_blue_offset";

        /**
         * Advance setting for red gain. Adjust the gain or amplification of the red color channels.
         * They control the overall intensity and white balance of red.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the red dimmer and 100 makes the
         * red brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_RED_GAIN = "color_tuner_red_gain";

        /**
         * Advance setting for green gain. Adjust the gain or amplification of the green color
         * channels. They control the overall intensity and white balance of green.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the green dimmer and 100 makes
         * the green brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_GREEN_GAIN = "color_tuner_green_gain";

        /**
         * Advance setting for blue gain. Adjust the gain or amplification of the blue color
         * channels. They control the overall intensity and white balance of blue.
         *
         * <p>The range is from 0 to 100 (inclusive), where 0 makes the blue dimmer and 100 makes
         * the blue brighter. The default/unmodified value is 50.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_COLOR_TUNER_BLUE_GAIN = "color_tuner_blue_gain";

        /**
         * Noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_NOISE_REDUCTION = "noise_reduction";

        /**
         * MPEG (moving picture experts group) noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_MPEG_NOISE_REDUCTION = "mpeg_noise_reduction";

        /**
         * Refine the flesh colors in the pictures without affecting the other colors on the screen.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_FLESH_TONE = "flesh_tone";

        /**
         * Contour noise reduction.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DECONTOUR = "decontour";

        /**
         * Dynamically change picture luma to enhance contrast.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DYNAMIC_LUMA_CONTROL = "dynamic_luma_control";

        /**
         * Enable/disable film mode.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_FILM_MODE = "film_mode";

        /**
         * Enable/disable black color auto stretch
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_BLACK_STRETCH = "black_stretch";

        /**
         * Enable/disable blue color auto stretch
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_BLUE_STRETCH = "blue_stretch";

        /**
         * Enable/disable the overall color tuning feature.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_COLOR_TUNE = "color_tune";

        /**
         * Adjust color temperature type
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_COLOR_TEMPERATURE = "color_temperature";

        /**
         * Enable/disable globe dimming.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_GLOBAL_DIMMING = "global_dimming";

        /**
         * Enable/disable auto adjust picture parameter based on the TV content.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_PICTURE_QUALITY_ENABLED =
                "auto_picture_quality_enabled";

        /**
         * Enable/disable auto upscaling the picture quality. It analyzes the lower-resolution
         * image and uses its knowledge to invent the missing pixel, make the image look sharper.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_SUPER_RESOLUTION_ENABLED =
                "auto_super_resolution_enabled";

        /**
         * The quantization level range for the video signal, which specifies
         * the mapping of digital code values to black and white levels.
         * <p>Must be one of the following values:
         * <ul>
         * <li><b>Auto:</b> The system determines the appropriate level range
         * automatically.</li>
         * <li><b>Limited:</b> Represents the limited range where black is 16 and
         * white is 235 (for 8-bit color). Standard for broadcast video.</li>
         * <li><b>Full:</b> Represents the full range where black is 0 and white
         * is 255 (for 8-bit color). Common for PC content and graphics.</li>
         * </ul>
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_LEVEL_RANGE = "level_range";

        /**
         * If {@code true}, enables gamut mapping to translate colors
         * from the source's color space to the display's gamut. This is used to
         * prevent color clipping when the source gamut is wider than the display's.
         * If {@code false}, out-of-gamut colors may be clipped.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_GAMUT_MAPPING = "gamut_mapping";

        /**
         * Set to {@code true} to enable PC Mode. This ensures a "dot by dot"
         * or 1:1 pixel mapping from the source signal to the display panel. When
         * enabled, this mode disables overscan, preventing the edges of the picture
         * from being cut off. This is ideal for sources like a personal computer
         * where sharp text and precise pixel representation are critical. If
         * {@code false}, standard TV processing, including potential overscan, will
         * be applied.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_PC_MODE = "pc_mode";

        /**
         * Set to {@code true} to enable a low latency mode (e.g., "Game Mode").
         * This mode minimizes video processing latency (input lag) by reducing or
         * bypassing non-essential image enhancement features. This is ideal for
         * interactive content like video games where responsiveness is critical.
         * Set to {@code false} for standard processing.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_LOW_LATENCY = "low_latency";

        /**
         * Set to {@code true} to enable Variable Refresh Rate (VRR). VRR synchronizes
         * the display's refresh rate in real-time with the frame rate of the source device
         * (e.g., a game console or PC). This eliminates screen tearing and reduces stutter,
         * providing a smoother visual experience, especially in video games. Set to {@code false}
         * to use a standard fixed refresh rate.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_VRR = "vrr";

        /**
         * Set to {@code true} to enable Cinema Variable Refresh Rate (CVRR).
         * This mode synchronizes the display's refresh rate with the cadence of
         * cinematic content, which often has a frame rate (e.g., 24fps) that does
         * not divide evenly into standard display refresh rates (e.g., 60Hz).
         * Enabling CVRR eliminates the motion "judder" that can result from this
         * mismatch, ensuring smooth playback as the director intended.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_CVRR = "cvrr";

        /**
         * Specifies the RGB color range for the HDMI signal to ensure
         * correct black and white levels.
         * <ul>
         * <li><b>Auto:</b> Allows the source and display to automatically negotiate the
         * correct range.</li>
         * <li><b>Limited:</b> Sets the range to 16-235 (for 8-bit color). This is the
         * standard for most broadcast, Blu-ray, and streaming video content.</li>
         * <li><b>Full:</b> Sets the range to 0-255 (for 8-bit color). This is the
         * standard for PC graphics, game consoles, and digital photography.</li>
         * </ul>
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_HDMI_RGB_RANGE = "hdmi_rgb_range";

        /**
         * Sets the color space of the video signal, which defines the
         * range of colors (gamut) it can represent. This must be set correctly
         * to ensure accurate color reproduction.
         * <ul>
         * <li> {@link #COLOR_SPACE_S_RGB_BT_709} Standard for web and High Definition (HD) content.
         * </li>
         * <li> {@link #COLOR_SPACE_DCI} Wide color gamut (WCG) common in digital cinema and on
         * premium displays.</li>
         * <li> {@link #COLOR_SPACE_BT2020} Wide color gamut (WCG) standard for Ultra High
         * Definition (UHD, 4K/8K) and HDR content.</li>
         * <li> {@link #COLOR_SPACE_ADOBE_RGB} RGB created by adobe system. </li>
         * <li> {@link #COLOR_SPACE_AUTO} </li>
         * <li> {@link #COLOR_SPACE_ON} </li>
         * <li> {@link #COLOR_SPACE_OFF} </li>
         * </ul>
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_SPACE = "color_space";

        /**
         * Specifies the initial maximum luminance of the display panel, in nits.
         * <p>
         * This value typically represents the factory-calibrated peak brightness
         * of the panel and is used by the system as a baseline for brightness
         * control and HDR tone mapping calculations.
         *
         * The value range is from 0 - 10000
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_PANEL_INIT_MAX_LUMINCE_NITS =
                "panel_init_max_lumince_nits";

        /**
         * A flag indicating if the
         * {@code panelInitMaxLuminceNits} value is valid and can be trusted.
         * <p>
         * If {@code false}, the panel was unable to report a valid maximum
         * luminance (e.g., a read error occurred), and the associated
         * {@code panelInitMaxLuminceNits} value should be ignored.
         *
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_PANEL_INIT_MAX_LUMINCE_VALID =
                "panel_init_max_lumince_valid";

        /**
         * Sets the electro-optical transfer function (EOTF), or "gamma,"
         * to be used. This non-linear curve dictates the display's brightness
         * response to the video signal, ensuring correct contrast and shadow detail.
         * <p>
         *
         * <p>Possible values:
         * <ul>
         *     <li> {@link #GAMMA_DARK} </li>
         *     <li> {@link #GAMMA_MIDDLE} </li>
         *     <li> {@link #GAMMA_BRIGHT} </li>
         * </ul>
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_GAMMA = "gamma";

        /**
         * The color red gain value for color temperature adjustment.
         * The value adjusts the intensity of red in the bright areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate red color
         * and 100 would significantly boost red color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to red color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_RED_GAIN =
                "color_temperature_red_gain";

        /**
         * The color green gain value for color temperature adjustment.
         * The value adjusts the intensity of green in the bright areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate green color
         * and 100 would significantly boost green color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to green color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_GREEN_GAIN =
                "color_temperature_green_gain";

        /**
         * The color blue gain value for color temperature adjustment.
         * The value adjusts the intensity of blue in the bright areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate blue color
         * and 100 would significantly boost blue color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to blue color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_BLUE_GAIN =
                "color_temperature_blue_gain";

        /**
         * The color red offset value for color temperature adjustment.
         * This value adjusts the intensity of red color in the dark areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate red color
         * and 100 would significantly boost red color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to red color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_RED_OFFSET =
                "color_temperature_red_offset";

        /**
         * The color green offset value for color temperature adjustment.
         * This value adjusts the intensity of green color in the dark areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate green color
         * and 100 would significantly boost green color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to green color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_GREEN_OFFSET =
                "color_temperature_green_offset";

        /**
         * The color blue offset value for color temperature adjustment.
         * This value adjusts the intensity of blue color in the dark areas on the TV.
         * <p>
         * The value range is from -100 to 100 where -100 would eliminate blue color
         * and 100 would significantly boost blue color.
         * <p>
         * The default/unmodified value is 0. No adjustment is applied to blue color.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TEMPERATURE_BLUE_OFFSET =
                "color_temperature_blue_offset";

        /**
         * The parameters in this section is for 11-point white balance in advanced TV picture
         * setting. 11-Point White Balance allows for very precise adjustment of the color
         * temperature of the TV. It aims to make sure white looks truly white, without any unwanted
         * color tints, across the entire range of brightness levels.
         * <p>
         * The "11 points" refer to 11 different brightness levels from 0 (black) to 10 (white).
         * At each of these points, we can fine-tune the mixture of red, green and blue to achieve
         * neutral white.
         * <p>
         * Control the amount of red at each of the 11 brightness points. The parameter type is an
         * int array with a fix size of 11. The indexes 0 - 10 are the 11 different points. For
         * example, elevenPointRed[0] adjusts the red level at the darkest black level.
         * elevenPointRed[1] adjusts red at the next brightness level up, and so on.
         * <p>
         * The value range is from 0 - 100 for each indexes, where 0 is the minimum intensity of
         * red at a specific brightness point and 100 is the maximum intensity of red at that point.
         * <p>
         * The default/unmodified value is 50. It can be other values depends on different TVs.
         *
         * <p>Type: INTEGER ARRAY
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_ELEVEN_POINT_RED = "eleven_point_red";

        /**
         * Control the amount of green at each of the 11 brightness points. The parameter type is an
         * int array with a fix size of 11. The indexes 0 - 10 are the 11 different points. For
         * example, elevenPointGreen[0] adjust the green level at the darkest black level.
         * elevenPointGreen[1] adjust green at the next brightness level up, and so on.
         * <p>
         * The value range is from 0 - 100 for each indexes, where 0 is the minimum intensity of
         * green at a specific brightness point and 100 is the maximum intensity of green at that
         * point.
         * <p>
         * The default/unmodified value is 50. It can be other values depends on different TVs.
         *
         * <p>Type: INTEGER ARRAY
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_ELEVEN_POINT_GREEN = "eleven_point_green";

        /**
         * Control the amount of blue at each of the 11 brightness points. The parameter type is an
         * int array with a fix size of 11. The indexes 0 - 10 are the 11 different points. For
         * example, elevenPointBlue[0] adjust the blue level at the darkest black level.
         * elevenPointBlue[1] adjust blue at the next brightness level up, and so on.
         * <p>
         * The value range is from 0 - 100 for each indexes, where 0 is the minimum intensity of
         * blue at a specific brightness point and 100 is the maximum intensity of blue at that
         * point.
         * <p>
         * The default/unmodified value is 50. It can be other values depends on different TVs.
         *
         * <p>Type: INTEGER ARRAY
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_ELEVEN_POINT_BLUE = "eleven_point_blue";

        /**
         * Adjust gamma blue gain/offset.
         *
         * <p>Possible values:
         *
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_MEDIUM}. Can be different depends on different TVs.
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_LOW_BLUE_LIGHT = "low_blue_light";

        /**
         * Advance setting for local dimming level.
         *
         * <p>Possible values:
         *
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}. Can be different depends on different TVs.
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_LD_MODE = "ld_mode";

        /**
         * The parameters in this section are for on-screen display color gain and offset.
         * <p>
         * Color gain is to adjust the intensity of that color (red, blue, green) in the brighter
         * part of the image.
         * Color offset is to adjust the intensity of that color in the darker part of the image.
         * <p>
         * Increasing OSD (on-screen display) red gain will make brighter reds even more
         * intense, while decreasing it will make them less vibrant.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_RED_GAIN = "osd_red_gain";

        /**
         * Increasing OSD (on-screen display) green gain will make brighter greens even more
         * intense, while decreasing it will make them less vibrant.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_GREEN_GAIN = "osd_green_gain";

        /**
         * Increasing OSD (on-screen display) blue gain will make brighter blues even more
         * intense, while decreasing it will make them less vibrant.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_BLUE_GAIN = "osd_blue_gain";

        /**
         * Increasing OSD red offset will add more red to the darker areas, while decreasing it will
         * reduce the red in the shadows.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_RED_OFFSET = "osd_red_offset";

        /**
         * Increasing OSD green offset will add more green to the darker areas, while decreasing it
         * will reduce the green in the shadows.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_GREEN_OFFSET = "osd_green_offset";

        /**
         * Increasing OSD blue offset will add more blue to the darker areas, while decreasing it
         * will reduce the blue in the shadows.
         * <p>
         * The value range is from 0 to 2047. (11-bit resolution for the adjustment)
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_BLUE_OFFSET = "osd_blue_offset";

        /**
         * Key for adjusting the Hue (color tint) of the On-Screen Display (OSD).
         * <p>
         * This parameter affects only the graphical user interface layer (e.g., system menus,
         * volume bars, channel information) and does not alter the underlying video content.
         * <p>
         * Adjusting the OSD Hue rotates the color phase of the UI elements. While rarely
         * changed for standard usage, it can be used for accessibility purposes or specific
         * stylistic themes.
         * <p>
         * The value range is from 0 - 100.
         * The default value is 50.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_HUE = "osd_hue";

        /**
         * Key for adjusting the Saturation (color intensity) of the On-Screen Display (OSD).
         * <p>
         * Controls the vividness of the colors in the UI layer.
         * <ul>
         * <li><b>Lower values:</b> Reduce color intensity, moving towards grayscale. This is
         * often used to prevent screen burn-in on OLED panels for static UI elements or
         * to make the menu less distracting during movie playback.</li>
         * <li><b>Higher values:</b> Increase color intensity, making the UI appear more
         * vivid and punchy.</li>
         * </ul>
         *
         * The value range is 0 - 255.
         * The default value depends on different TVs.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_SATURATION = "osd_saturation";

        /**
         * Key for adjusting the Contrast of the On-Screen Display (OSD).
         * <p>
         * Controls the difference in luminance between the brightest and darkest parts
         * of the UI layer.
         * <p>
         * Unlike the "Brightness" setting which lifts the overall backlight, OSD Contrast
         * specifically affects the digital signal values of the graphics plane.
         * Increasing this value can make text and icons stand out more sharply against
         * their background, improving legibility, while decreasing it can soften the UI
         * to reduce eye strain in dark viewing environments.
         * <p>
         * The value range is 0 - 100.
         * The default value is 50.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_OSD_CONTRAST = "osd_contrast";

        /**
         * Key for the master switch to enable or disable the Color Tuner feature.
         * <p>
         * The Color Tuner allows for advanced calibration of the display's color reproduction,
         * including 6-axis adjustment of Hue, Saturation, and Luminance, as well as
         * detailed White Balance (Color Temperature) controls.
         * <ul>
         * <li><b>{@code true}:</b> Enables the Color Tuner. Custom values for hue,
         * saturation, luminance, and gain/offset are applied.</li>
         * <li><b>{@code false}:</b> Disables the Color Tuner. The display reverts to
         * its factory default color calibration.</li>
         * </ul>
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SWITCH = "color_tuner_switch";

        /**
         * Key for adjusting the Hue of the red color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of red pixels. Moving the value away from
         * default shifts red towards **Magenta** (purplish-red) or **Yellow** (orange-red).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_RED = "color_tuner_hue_red";

        /**
         * Key for adjusting the Hue of the green color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of green pixels. Moving the value away from
         * default shifts green towards yellow (lime-green) or cyan (teal-green).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_GREEN = "color_tuner_hue_green";

        /**
         * Key for adjusting the Hue of the blue color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of blue pixels. Moving the value away from
         * default shifts blue towards cyan (sky blue) or magenta (purplish-blue).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_BLUE = "color_tuner_hue_blue";

        /**
         * Key for adjusting the Hue of the cyan color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of cyan pixels. Moving the value away from
         * default shifts cyan towards green or blue.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_CYAN = "color_tuner_hue_cyan";

        /**
         * Key for adjusting the Hue of the magenta color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of magenta pixels. Moving the value away from
         * default shifts magenta towards blue (violet) or red (rose).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_MAGENTA = "color_tuner_hue_magenta";

        /**
         * Key for adjusting the Hue of the yellow color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS).
         * <ul>
         * <li><b>Effect:</b> Rotates the hue of yellow pixels. Moving the value away from
         * default shifts yellow towards red (orange) or green (lime).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_YELLOW = "color_tuner_hue_yellow";

        /**
         * Key for adjusting the Hue of the flesh (Skin Tone) color component.
         * <p>
         * This is a dedicated parameter for fine-tuning skin tones.
         * <ul>
         * <li><b>Effect:</b> Adjusts the complexion of human subjects. Moving the value
         * away from default shifts skin tones towards Reddish/Rosy or
         * Yellowish/Golden.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_HUE_FLESH = "color_tuner_hue_flesh";

        /**
         * Key for adjusting the Hue of the red color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of red colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the red phase. Moving away from the default value
         * will shift red pixels towards magenta (purplish-red) or yellow
         * (orange-red).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_RED =
                "color_tuner_saturation_red";

        /**
         * Key for adjusting the Hue of the Green color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of green colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the green phase. Moving away from the default value
         * will shift green pixels towards Yellow (lime-green) or Cyan (teal-green).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_GREEN =
                "color_tuner_saturation_green";

        /**
         * Key for adjusting the Hue of the blue color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of blue colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the blue phase. Moving away from the default value
         * will shift blue pixels towards Cyan (sky blue) or Magenta (purplish-blue).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_BLUE =
                "color_tuner_saturation_blue";

        /**
         * Key for adjusting the Hue of the cyan color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of cyan colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the cyan phase. Moving away from the default value
         * will shift cyan pixels towards Green or Blue.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_CYAN =
                "color_tuner_saturation_cyan";

        /**
         * Key for adjusting the Hue of the magenta color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of magenta colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the magenta phase. Moving away from the default value
         * will shift magenta pixels towards blue (violet) or red (rose).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_MAGENTA =
                "color_tuner_saturation_magenta";

        /**
         * Key for adjusting the Hue of the yellow color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * independent adjustment of yellow colors without affecting other hues.
         * <ul>
         * <li><b>Effect:</b> Rotates the yellow phase. Moving away from the default value
         * will shift yellow pixels towards red (orange) or green (lime).</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_YELLOW =
                "color_tuner_saturation_yellow";

        /**
         * Key for adjusting the Hue of the flesh (Skin Tone) color component.
         * <p>
         * This is a dedicated parameter for fine-tuning skin tones, which typically reside
         * in the orange/red region of the color spectrum.
         * <ul>
         * <li><b>Effect:</b> Adjusts the complexion of human subjects. Moving away from
         * the default value typically shifts skin tones towards Reddish/Rosy or
         * Yellowish/Golden, helping to correct "sunburned" or "greenish" looks.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_SATURATION_FLESH =
                "color_tuner_saturation_flesh";

        /**
         * Key for adjusting the Luminance (brightness) of the red color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of red areas to be adjusted independently without affecting
         * the saturation or hue.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of red pixels. Values higher
         * than default make red colors appear brighter and more vibrant, while lower
         * values make them appear darker and deeper.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_RED =
                "color_tuner_luminance_red";

        /**
         * Key for adjusting the Luminance (brightness) of the green color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of green areas to be adjusted independently.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of green pixels. Values higher
         * than default make green colors appear brighter, while lower values make
         * them appear darker.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_GREEN =
                "color_tuner_luminance_green";

        /**
         * Key for adjusting the Luminance (brightness) of the blue color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of blue areas to be adjusted independently.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of blue pixels. Values higher
         * than default make blue colors appear brighter, while lower values make
         * them appear darker.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_BLUE =
                "color_tuner_luminance_blue";

        /**
         * Key for adjusting the Luminance (brightness) of the cyan color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of cyan areas to be adjusted independently.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of cyan pixels. Values higher
         * than default make cyan colors appear brighter, while lower values make
         * them appear darker.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_CYAN =
                "color_tuner_luminance_cyan";

        /**
         * Key for adjusting the Luminance (brightness) of the magenta color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of magenta areas to be adjusted independently.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of magenta pixels. Values higher
         * than default make magenta colors appear brighter, while lower values make
         * them appear darker.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_MAGENTA =
                "color_tuner_luminance_magenta";

        /**
         * Key for adjusting the Luminance (brightness) of the yellow color component.
         * <p>
         * This parameter is part of the 6-axis Color Management System (CMS). It allows
         * the brightness of yellow areas to be adjusted independently.
         * <ul>
         * <li><b>Effect:</b> Controls the light intensity of yellow pixels. Values higher
         * than default make yellow colors appear brighter, while lower values make
         * them appear darker.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_YELLOW =
                "color_tuner_luminance_yellow";

        /**
         * Key for adjusting the Luminance (brightness) of the flesh (Skin Tone) component.
         * <p>
         * This is a dedicated parameter for fine-tuning the brightness of skin tones.
         * <ul>
         * <li><b>Effect:</b> Adjusts the exposure of human subjects. Increasing this value
         * can help brighten faces in shadow or dark scenes, while decreasing it can
         * help recover detail in bright, washed-out highlights on faces.</li>
         * <li><b>Value Range:</b> 0 to 100.</li>
         * <li><b>Default:</b> 50 (Neutral/No change).</li>
         * </ul>
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_COLOR_TUNER_LUMINANCE_FLESH =
                "color_tuner_luminance_flesh";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_PICTURE_QUALITY_EVENT_TYPE =
                "picture_quality_event_type";

        /**
         * @hide
         *
         */
        public static final String PARAMETER_STREAM_STATUS =
                "stream_status";

        /**
         * Adjusts the 'MEMC' (Motion Estimation, Motion Compensation) effect.
         *
         * <p>Possible values:
         * <ul>
         * <li>{@link #LEVEL_OFF}
         * <li>{@link #LEVEL_LOW}
         * <li>{@link #LEVEL_MEDIUM}
         * <li>{@link #LEVEL_HIGH}
         * <li>{@link #LEVEL_USER}
         * </ul>
         *
         * <p>The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_MEMC_EFFECT = "memc_effect";

        /**
         * Adjusts the 'Deblur' component of MEMC. The range is from 0 to 10.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_MEMC_DEBLUR = "memc_deblur";

        /**
         * Adjusts the 'De-judder' component of MEMC. The range is from 0 to 10.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_MEMC_DEJUDDER = "memc_dejudder";

        /**
         * Enables a mode to play content at its original frame rate.
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_ORIGINAL_FRAMERATE = "original_framerate";

        /**
         * Selects the 3D display mode based on the source format.
         * <p>Type: STRING
         * <p>See {@link ThreeDModeValue} for possible values.
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_3D_MODE = "3d_mode";

        /**
         * Controls the conversion from a 3D source to a 2D image.
         * <p>Type: STRING
         * <p>See {@link ThreeDModeValue} for possible values.
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_3D_TO_2D = "3d_to_2d";

        private PictureQuality() {
        }
    }

    /**
     * Parameters for sound quality.
     */
    public static final class SoundQuality {
        /**
         * The audio volume balance.
         *
         * <p>This parameter controls the balance between the left and right speakers.
         * The valid range is -50 to 50 (inclusive), where:
         *   - Negative values shift the balance towards the left speaker.
         *   - Positive values shift the balance towards the right speaker.
         *   - 0 represents a balanced output.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BALANCE = "balance";

        /**
         * The bass.
         *
         * <p>Bass controls the intensity of low-frequency sounds.
         * The valid range is 0 - 100 (inclusive).
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_BASS = "bass";

        /**
         * The treble.
         *
         * <p>Treble controls the intensity of high-frequency sounds.
         * The valid range is 0 - 100 (inclusive).
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_TREBLE = "treble";

        /**
         * Enable/disable surround sound.
         * Stereo Pulse-Code Modulation to apply a customizable filter. There is no difference on
         * any use cases.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_SURROUND_SOUND = "surround_sound";

        /**
         * Equalizer can fine-tune the audio output by adjusting the loudness of different
         * frequency bands;
         * Normally each band have a value of -50 to 50.
         *
         * <p>Type: String
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_EQUALIZER_SETTINGS = "equalizer_settings";

        /**
         * Enable/disable speaker output.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_SPEAKERS = "speakers";

        /**
         * Speaker delay in milliseconds.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_SPEAKERS_DELAY_MILLIS = "speakers_delay_millis";

        /**
         * Enable/disable enhanced audio return channel (eARC).
         *
         * <p>eARC allows for higher bandwidth audio transmission over HDMI.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_EARC = "earc";

        /**
         * Enable/disable auto volume control sound effect.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_AUTO_VOLUME_CONTROL = "auto_volume_control";

        /**
         * Sets the downmix mode for multi-channel audio.
         *
         * <p>This parameter determines how multi-channel audio (e.g., 5.1) is converted
         * to a two-channel stereo output. This is useful when the playback device, like
         * headphones or TV speakers, has fewer channels than the source audio.
         *
         * <p>The supported string values are:
         * <ul>
         * <li>{@code "Stereo"}: A standard downmix (Lo/Ro) suitable for most stereo
         * playback devices. <b>(Default)</b></li>
         * <li>{@code "Surround"}: A downmix that is matrix-encoded with surround sound
         * information (Lt/Rt).
         * </ul>
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DOWN_MIX_MODE = "down_mix_mode";

        /**
         * Enable/disable dynamic range compression (DRC) of digital theater system (DTS).
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_DRC = "dts_drc";

        /**
         * Key used to enable or disable Dolby Audio Processing (DAP).
         *
         * <p><b>Value Type:</b> String</p>
         * <p><b>Valid Values:</b>
         * <ul>
         * <li>{@code "on"} - Enables Dolby post-processing (volume leveling, dialogue enhancement,
         * surround virtualization).</li>
         * <li>{@code "off"} - Disables processing (audio is passed through without modification).
         * </li>
         * </ul>
         * </p>
         * <p><b>Default Value:</b> {@code "off"}</p>
         *
         * <p>When set to {@code "on"}, the audio engine applies the currently configured
         * Dolby profile to the output mix. When set to {@code "off"}, the system operates
         * in a "passthrough" or reference mode, preserving the original dynamic range
         * and frequency response of the content.</p>
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING = "dolby_audio_processing";

        /**
         * Sets the sound mode for Dolby audio processing.
         *
         * <p>This parameter allows the selection of a preset audio profile to optimize the
         * listening experience for different types of content. The supported values are:
         * <ul>
         * <li>{@code "Game"}
         * <li>{@code "Movie"}
         * <li>{@code "Music"}
         * <li>{@code "News"}
         * <li>{@code "Stadium"}
         * <li>{@code "Standard"}
         * <li>{@code "User"}
         * </ul>
         *
         * <p>The default value is {@code "Standard"}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SOUND_MODE =
                "dolby_audio_processing_sound_mode";

        /**
         * Enable/disable Volume Leveler.
         *
         * <p>Volume Leveler helps to maintain a consistent volume level across different
         * types of content and even within the same program. It minimizes the jarring jumps
         * between loud commercials or action sequences and quiet dialogue.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_VOLUME_LEVELER =
                "dolby_audio_processing_volume_leveler";

        /**
         * Enable/disable the Surround Virtualizer.
         *
         * <p>The Surround Virtualizer creates a virtual surround sound experience when
         * playing back Atmos, surround, and stereo content over two-channel endpoints
         * like TV built-in speakers and headphones. It expands the soundstage and adds
         * depth to the audio, creating an immersive effect without a multi-speaker setup.
         * Note: When Dolby Atoms playback streams are active, this will always be true.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_SURROUND_VIRTUALIZER =
                "dolby_audio_processing_surround_virtualizer";

        /**
         * Enables or disables Dolby Atmos processing.
         *
         * <p>Dolby Atmos creates a more immersive and realistic sound experience by adding
         * a height dimension to surround sound. It allows sound to be placed and moved
         * precisely around you, including overhead.
         *
         * <p>When set to {@code true}, Dolby Atmos processing is enabled. When set to
         * {@code false}, it is disabled, and the audio will be processed using other
         * standard settings. Disabling this parameter does not change the list of
         * available audio formats presented to the audio framework.
         *
         * <p><b>Note:</b> This setting is only effective on devices that support Dolby
         * Atmos; on unsupported systems, this option may be ignored or hidden. To
         * experience Dolby Atmos, you need content specifically mixed for it. The
         * immersive effect can be delivered through a dedicated Dolby Atmos sound
         * system or through virtualization technologies for headphones and built-in
         * speakers.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DOLBY_AUDIO_PROCESSING_DOLBY_ATMOS =
                "dolby_audio_processing_dolby_atmos";

        /**
         * Dialogue enhancer.
         *
         * <p>Possible values:
         * <ul>
         *   <li>{@link #LEVEL_LOW}
         *   <li>{@link #LEVEL_MEDIUM}
         *   <li>{@link #LEVEL_HIGH}
         *   <li>{@link #LEVEL_OFF}
         * </ul>
         * The default value is {@link #LEVEL_OFF}.
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DIALOGUE_ENHANCER = "dialogue_enhancer";

        /**
         * Enable/disable DTS Virtual:X.
         *
         * <p>DTS Virtual:X is an audio post-processing technology that provides an immersive
         * listening experience by virtualizing a height and surround soundstage from any input
         * source. When enabled, it aims to create a wider and taller sound field, enhancing
         * spatial perception without the need for additional speakers.
         *
         * <p>Type: BOOLEAN
         * <p>Possible values: {@code true} to enable, {@code false} to disable.
         * The default value is {@code false}.
         * <p>
         * Note: When this set to false, other dts relate parameter should also be false.
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_DTS_VIRTUAL_X = "dts_virtual_x";

        /**
         * Enable/disable Total Bass Harmonic Distortion (X).
         *
         * <p>TBHDX bass enhancement provides a richer low-frequency experience, simulating deeper
         * bass.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TBHDX = "dts_virtual_x_tbhdx";

        /**
         * Enable/disable audio limiter.
         *
         * <p>It prevents excessive volume peaks that could cause distortion or speaker damage.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_LIMITER = "dts_virtual_x_limiter";

        /**
         * Enable/disable the core DTS Virtual:X surround sound processing.
         *
         * <p>It creates an immersive, multi-channel audio experience from the speaker
         * configuration.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_SURROUND_X =
                "dts_virtual_x_tru_surround_x";

        /**
         * Enable/disable DTS TruVolume HD.
         *
         * <p>It reduces the dynamic range of audio, minimizing loudness variations between content
         * and channels.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_TRU_VOLUME_HD =
                "dts_virtual_x_tru_volume_hd";

        /**
         * Enable/disable dialog clarity.
         *
         * <p>It enhances the clarity and intelligibility of speech in audio content.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DIALOG_CLARITY =
                "dts_virtual_x_dialog_clarity";

        /**
         * Enable/disable virtual X definition.
         *
         * <p>It applies audio processing to improve overall sound definition and clarity.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_DEFINITION = "dts_virtual_x_definition";

        /**
         * Enable/disable the processing of virtual height channels.
         *
         * <p>It creates a more immersive audio experience by simulating sounds from above.
         *
         * <p>Type: BOOLEAN
         */
        public static final String PARAMETER_DTS_VIRTUAL_X_HEIGHT = "dts_virtual_x_height";

        /**
         * Digital output delay in milliseconds.
         *
         * <p>Type: INTEGER
         */
        public static final String PARAMETER_DIGITAL_OUTPUT_DELAY_MILLIS =
                "digital_output_delay_millis";

        /**
         * Sets the digital audio output mode.
         *
         * <p>This parameter controls the audio format sent to a digital output like
         * HDMI or S/PDIF. This allows the user to select a specific audio format or
         * let the system decide automatically. The supported values are:
         * <ul>
         * <li>{@code "Auto"}: The system automatically selects the preferred format supported
         * by the connected device. (Default)</li>
         * <li>{@code "Bypass"}: The encoded audio stream is sent directly to the output
         * without being decoded by this device.</li>
         * <li>{@code "PCM"}: Audio is decoded to uncompressed Pulse-Code Modulation.</li>
         * <li>{@code "Dolby Digital Plus"}</li>
         * <li>{@code "Dolby Digital"}</li>
         * <li>{@code "Dolby MAT"}</li>
         * </ul>
         *
         * <p>Type: STRING
         */
        public static final String PARAMETER_DIGITAL_OUTPUT_MODE = "digital_output_mode";

        /**
         * The sound style of this profile.
         * <p>Must be one of the following values, defined in {@link SoundQuality.SoundStyleValue}:
         * <ul>
         *   <li>{@link #SOUND_STYLE_USER}</li>
         *   <li>{@link #SOUND_STYLE_STANDARD}</li>
         *   <li>{@link #SOUND_STYLE_VIVID}</li>
         *   <li>{@link #SOUND_STYLE_SPORTS}</li>
         *   <li>{@link #SOUND_STYLE_MOVIE}</li>
         *   <li>{@link #SOUND_STYLE_MUSIC}</li>
         *   <li>{@link #SOUND_STYLE_NEWS}</li>
         *   <li>{@link #SOUND_STYLE_AUTO}</li>
         * </ul>
         * The default value is {@link #SOUND_STYLE_STANDARD}.
         *
         * <p>Type: STRING
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_SOUND_STYLE = "sound_style";

        /**
         * Adjusts the left/right audio balance for the built-in speakers.
         * The range is -50 (left) to 50 (right), with 0 being centered.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_BALANCE_SPEAKER = "balance_speaker";

        /**
         * Adjusts the left/right audio balance for a connected Bluetooth device.
         * The range is -50 (left) to 50 (right), with 0 being centered.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_BALANCE_BLUETOOTH = "balance_bluetooth";

        /**
         * Adjusts the left/right audio balance for a connected headphone.
         * The range is -50 (left) to 50 (right), with 0 being centered.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_BALANCE_HEADPHONES = "balance_headphones";

        /**
         * Toggles the High-Resolution Audio path, which offers better-than-CD quality
         * playback with higher sampling rates and/or bit depth.
         * <p>Type: BOOLEAN
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_HI_RES_AUDIO = "hi_res_audio";

        /**
         * Reports the audio latency of the connected Bluetooth (BT) media device in microseconds.
         * This value can be used by A/V sync logic to maintain lip-sync.
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_BT_LATENCY_US = "bt_latency_us";

        /**
         * Controls the output routing of the Audio Description track to the internal speakers.
         * <p>If set to {@code true}, the AD track will be mixed with the main audio
         * and played through the device's built-in speakers.</p>
         * <p><b>Dependency:</b> This setting is ignored if the device does not have
         * internal speakers or if audio routing is forcibly overridden by system policy.</p>
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_AD_SPEAKER_ENABLE = "ad_speaker_enable";

        /**
         * Controls the output routing of the Audio Description track to connected headphones.
         * <p>If set to {@code true}, the AD track will be mixed and played through
         * wired or Bluetooth headsets.</p>
         * <p><b>Note:</b> This enables independent consumption of AD content if the
         * audio engine supports dual-routing (e.g., AD on headphones, Main Audio on speakers).</p>
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_AD_HEADPHONE_ENABLE = "ad_headphone_enable";

        /**
         * Sets the relative volume gain for the Audio Description track.
         *
         * <p><b>Unit:</b> Integer Percentage (0-100)</p>
         * <p><b>Default:</b> Typically defaults to 50 or the system-wide accessibility volume
         * preference.</p>
         *
         * <p>This value controls the mixing level of the secondary audio stream (AD)
         * before it is combined with the main program audio.
         * <ul>
         * <li>{@code 0}: AD track is muted.</li>
         * <li>{@code 100}: AD track is at maximum mixing volume.</li>
         * </ul>
         * </p>
         *
         * Range: An integer between 0 and 100.
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_AD_VOLUME = "ad_volume";

        /**
         * Enables automatic Pan and Fade (Ducking) behavior for the main audio.
         * <p>When set to {@code true}, the audio engine will apply standard broadcast mixing rules:
         * <ul>
         * <li><b>Fade:</b> The main program audio volume is lowered ("ducked") when
         * audio description is present to ensure the narrator is intelligible.</li>
         * <li><b>Pan:</b> The main audio may be spatially shifted (e.g., to background channels)
         * to center the audio description track.</li>
         * </ul>
         * </p>
         * * <p>If set to {@code false}, the AD track is mixed simply as an overlay without
         * modifying the volume or position of the main audio track.</p>
         */
        @FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
        public static final String PARAMETER_PAN_FADE_ENABLE = "pan_fade_enable";

        private SoundQuality() {
        }
    }

    private MediaQualityContract() {
    }
}
