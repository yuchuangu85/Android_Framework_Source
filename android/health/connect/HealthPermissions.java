/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_ACTIVITY_INTENSITY;
import static com.android.healthfitness.flags.Flags.FLAG_ALCOHOL_CONSUMPTION;
import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API;
import static com.android.healthfitness.flags.Flags.FLAG_CYCLE_PHASES_FLAG;
import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_UDI;
import static com.android.healthfitness.flags.Flags.FLAG_LAUNCH_ONBOARDING_ACTIVITY;
import static com.android.healthfitness.flags.Flags.FLAG_MINDFULNESS;
import static com.android.healthfitness.flags.Flags.FLAG_PERSONAL_HEALTH_RECORD;
import static com.android.healthfitness.flags.Flags.FLAG_SMOKING;
import static com.android.healthfitness.flags.Flags.FLAG_SYMPTOMS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.health.connect.datatypes.ExerciseRoute;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import android.util.ArraySet;

import com.android.healthfitness.flags.AconfigFlagHelper;
import com.android.healthfitness.flags.Flags;

import java.util.HashSet;
import java.util.Set;

// TODO(b/255340973): consider generate this class.
/**
 * Permissions for accessing the HealthConnect APIs.
 *
 * <p>Apps must support {@link android.content.Intent#ACTION_VIEW_PERMISSION_USAGE} with {@link
 * HealthConnectManager#CATEGORY_HEALTH_PERMISSIONS} category to be granted read/write health data
 * permissions.
 */
public final class HealthPermissions {
    /**
     * Allows an application to grant/revoke health-related permissions.
     *
     * <p>Protection level: signature.
     *
     * @hide
     */
    @SystemApi
    public static final String MANAGE_HEALTH_PERMISSIONS =
            "android.permission.MANAGE_HEALTH_PERMISSIONS";

    // Below permission was earlier declared in HealthConnectManager since it was only permission
    // used by access logs API, is now declared here along with the other system permission.
    // Please suggest if it will be ok to have it here.
    /**
     * Allows an application to modify health data.
     *
     * <p>Protection level: privileged.
     *
     * @hide
     */
    @SystemApi
    public static final String MANAGE_HEALTH_DATA_PERMISSION =
            "android.permission.MANAGE_HEALTH_DATA";

    /**
     * Allows an application to launch client onboarding activities responsible for connecting to
     * Health Connect. This permission can only be held by the system. Client apps that choose to
     * export an onboarding activity must guard it with this permission so that only the system can
     * launch it.
     *
     * <p>See {@link HealthConnectManager#ACTION_SHOW_ONBOARDING} for the corresponding intent used
     * by the system to launch onboarding activities.
     *
     * <p>Protection level: signature.
     */
    @FlaggedApi(FLAG_LAUNCH_ONBOARDING_ACTIVITY)
    public static final String START_ONBOARDING = "android.permission.health.START_ONBOARDING";

    /**
     * Allows an application to launch Backup and Restore Settings activities where the user can
     * change Cloud backup and restore settings such as enable/disable. This permission can only be
     * held by the system. Client apps that choose to export a health connect backup and restore
     * settings activity must guard it with this permission so that only the system can launch it.
     *
     * <p>See {@link HealthConnectManager#ACTION_VIEW_HEALTH_CONNECT_BACKUP_SETTINGS} for the
     * corresponding intent used by the system to launch backup and restore settings activities.
     *
     * <p>Protection level: signature.
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final String START_BACKUP_RESTORE_SETTINGS_PERMISSION =
            "android.permission.health.START_BACKUP_RESTORE_SETTINGS";

    /**
     * Allows an application to write the device's unique device identifier (UDI).
     *
     * <p>Protection level: normal.
     */
    @FlaggedApi(FLAG_DEVICE_UDI)
    public static final String WRITE_DEVICE_UDI = "android.permission.health.WRITE_DEVICE_UDI";

    /**
     * Used for runtime permissions which grant access to Health Connect data.
     *
     * @hide
     */
    @SystemApi
    public static final String HEALTH_PERMISSION_GROUP = "android.permission-group.HEALTH";

    /**
     * Allows an application to read health data (of any type) in background.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.background_read")
    public static final String READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";

    /**
     * Allows an application to read the entire history of health data (of any type).
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.history_read")
    public static final String READ_HEALTH_DATA_HISTORY =
            "android.permission.health.READ_HEALTH_DATA_HISTORY";

    /**
     * Allows an application to read the user's active calories burned data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_ACTIVE_CALORIES_BURNED =
            "android.permission.health.READ_ACTIVE_CALORIES_BURNED";

    /**
     * Allows an application to read the user's activity intensity data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_ACTIVITY_INTENSITY)
    public static final String READ_ACTIVITY_INTENSITY =
            "android.permission.health.READ_ACTIVITY_INTENSITY";

    /**
     * Allows an application to read the user's distance data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_DISTANCE = "android.permission.health.READ_DISTANCE";

    /**
     * Allows an application to read the user's elevation gained data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_ELEVATION_GAINED =
            "android.permission.health.READ_ELEVATION_GAINED";

    /**
     * Allows an application to read the user's exercise data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_EXERCISE = "android.permission.health.READ_EXERCISE";

    /**
     * Allows an application to read any {@link ExerciseRoute}. Not connected with READ_EXERCISE
     * permission, as it's used only by HealthConnectController to show routes in UI and share one
     * particular route with third party app after one-time user consent.
     *
     * <p>Protection level: signature.
     *
     * @hide
     */
    public static final String READ_EXERCISE_ROUTE =
            "android.permission.health.READ_EXERCISE_ROUTE";

    /**
     * Allows an application to read {@link ExerciseRoute}.
     *
     * <p>This permission can only be granted manually by a user in Health Connect settings or in
     * the route request activity which can be launched using {@link ACTION_REQUEST_EXERCISE_ROUTE}.
     * Attempts to request the permission by applications will be ignored.
     *
     * <p>Applications should check if the permission has been granted before reading {@link
     * ExerciseRoute}.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.read_exercise_routes_all_enabled")
    public static final String READ_EXERCISE_ROUTES =
            "android.permission.health.READ_EXERCISE_ROUTES";

    /**
     * Allows an application to read the user's floors climbed data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_FLOORS_CLIMBED =
            "android.permission.health.READ_FLOORS_CLIMBED";

    /**
     * Allows an application to read the user's steps data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_STEPS = "android.permission.health.READ_STEPS";

    /**
     * Allows an application to read the user's total calories burned data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_TOTAL_CALORIES_BURNED =
            "android.permission.health.READ_TOTAL_CALORIES_BURNED";

    /**
     * Allows an application to read the user's vo2 maximum data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_VO2_MAX = "android.permission.health.READ_VO2_MAX";

    /**
     * Allows an application to read the user's wheelchair pushes data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_WHEELCHAIR_PUSHES =
            "android.permission.health.READ_WHEELCHAIR_PUSHES";

    /**
     * Allows an application to read the user's power data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_POWER = "android.permission.health.READ_POWER";

    /**
     * Allows an application to read the user's speed data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_SPEED = "android.permission.health.READ_SPEED";

    /**
     * Allows an application to read the user's basal metabolic rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BASAL_METABOLIC_RATE =
            "android.permission.health.READ_BASAL_METABOLIC_RATE";

    /**
     * Allows an application to read the user's body fat data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BODY_FAT = "android.permission.health.READ_BODY_FAT";

    /**
     * Allows an application to read the user's body water mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BODY_WATER_MASS =
            "android.permission.health.READ_BODY_WATER_MASS";

    /**
     * Allows an application to read the user's bone mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BONE_MASS = "android.permission.health.READ_BONE_MASS";

    /**
     * Allows an application to read the user's height data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_HEIGHT = "android.permission.health.READ_HEIGHT";

    /**
     * Allows an application to read the user's lean body mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_LEAN_BODY_MASS =
            "android.permission.health.READ_LEAN_BODY_MASS";

    /**
     * Allows an application to read the user's weight data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_WEIGHT = "android.permission.health.READ_WEIGHT";

    /**
     * Allows an application to read the user's cervical mucus data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_CERVICAL_MUCUS =
            "android.permission.health.READ_CERVICAL_MUCUS";

    /**
     * Allows an application to read the user's cycle phases data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
    public static final String READ_MENSTRUAL_CYCLE_PHASE =
            "android.permission.health.READ_MENSTRUAL_CYCLE_PHASE";

    /**
     * Allows an application to read the user's menstruation data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_MENSTRUATION = "android.permission.health.READ_MENSTRUATION";

    /**
     * Allows an application to read the user's intermenstrual bleeding data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_INTERMENSTRUAL_BLEEDING =
            "android.permission.health.READ_INTERMENSTRUAL_BLEEDING";

    /**
     * Allows an application to read the user's ovulation test data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_OVULATION_TEST =
            "android.permission.health.READ_OVULATION_TEST";

    /**
     * Allows an application to read the user's sexual activity data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_SEXUAL_ACTIVITY =
            "android.permission.health.READ_SEXUAL_ACTIVITY";

    /**
     * Allows an application to read the user's hydration data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_HYDRATION = "android.permission.health.READ_HYDRATION";

    /**
     * Allows an application to read the user's nutrition data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_NUTRITION = "android.permission.health.READ_NUTRITION";

    /**
     * Allows an application to read the user's sleep data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_SLEEP = "android.permission.health.READ_SLEEP";

    /**
     * Allows an application to read the user's body temperature data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BASAL_BODY_TEMPERATURE =
            "android.permission.health.READ_BASAL_BODY_TEMPERATURE";

    /**
     * Allows an application to read the user's blood glucose data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BLOOD_GLUCOSE = "android.permission.health.READ_BLOOD_GLUCOSE";

    /**
     * Allows an application to read the user's blood pressure data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BLOOD_PRESSURE =
            "android.permission.health.READ_BLOOD_PRESSURE";

    /**
     * Allows an application to read the user's body temperature data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_BODY_TEMPERATURE =
            "android.permission.health.READ_BODY_TEMPERATURE";

    /**
     * Allows an application to read the user's heart rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";

    /**
     * Allows an application to read the user's heart rate variability data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_HEART_RATE_VARIABILITY =
            "android.permission.health.READ_HEART_RATE_VARIABILITY";

    /**
     * Allows an application to read the user's oxygen saturation data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_OXYGEN_SATURATION =
            "android.permission.health.READ_OXYGEN_SATURATION";

    /**
     * Allows an application to read the user's respiratory rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_RESPIRATORY_RATE =
            "android.permission.health.READ_RESPIRATORY_RATE";

    /**
     * Allows an application to read the user's resting heart rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String READ_RESTING_HEART_RATE =
            "android.permission.health.READ_RESTING_HEART_RATE";

    /**
     * Allows an application to read the user's skin temperature data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.skin_temperature")
    public static final String READ_SKIN_TEMPERATURE =
            "android.permission.health.READ_SKIN_TEMPERATURE";

    /**
     * Allows an application to read the user's training plan data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.training_plans")
    public static final String READ_PLANNED_EXERCISE =
            "android.permission.health.READ_PLANNED_EXERCISE";

    /**
     * Allows an application to read user's mindfulness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_MINDFULNESS)
    public static final String READ_MINDFULNESS = "android.permission.health.READ_MINDFULNESS";

    /**
     * Allows an application to read user's nicotine intake data.
     *
     * <p>Protection level: dangerous
     */
    @FlaggedApi(FLAG_SMOKING)
    public static final String READ_NICOTINE_INTAKE =
            "android.permission.health.READ_NICOTINE_INTAKE";

    /**
     * Allows an application to write user's alcohol consumption data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_ALCOHOL_CONSUMPTION)
    public static final String READ_ALCOHOL_CONSUMPTION =
            "android.permission.health.READ_ALCOHOL_CONSUMPTION";

    /**
     * Allows an application to read the user's abdominal pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_ABDOMINAL_PAIN =
            "android.permission.health.READ_SYMPTOM_ABDOMINAL_PAIN";

    /**
     * Allows an application to read the user's acne data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_ACNE = "android.permission.health.READ_SYMPTOM_ACNE";

    /**
     * Allows an application to read the user's back pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BACK_PAIN =
            "android.permission.health.READ_SYMPTOM_BACK_PAIN";

    /**
     * Allows an application to read the user's bloating data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BLOATING =
            "android.permission.health.READ_SYMPTOM_BLOATING";

    /**
     * Allows an application to read the user's brain fog data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BRAIN_FOG =
            "android.permission.health.READ_SYMPTOM_BRAIN_FOG";

    /**
     * Allows an application to read the user's breast tenderness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BREAST_TENDERNESS =
            "android.permission.health.READ_SYMPTOM_BREAST_TENDERNESS";

    /**
     * Allows an application to read the user's brittle nails data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BRITTLE_NAILS =
            "android.permission.health.READ_SYMPTOM_BRITTLE_NAILS";

    /**
     * Allows an application to read the user's burning mouth  data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_BURNING_MOUTH =
            "android.permission.health.READ_SYMPTOM_BURNING_MOUTH";

    /**
     * Allows an application to read the user's chest pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CHEST_PAIN =
            "android.permission.health.READ_SYMPTOM_CHEST_PAIN";

    /**
     * Allows an application to read the user's chest tightness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CHEST_TIGHTNESS =
            "android.permission.health.READ_SYMPTOM_CHEST_TIGHTNESS";

    /**
     * Allows an application to read the user's chills data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CHILLS =
            "android.permission.health.READ_SYMPTOM_CHILLS";

    /**
     * Allows an application to read the user's constipation data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CONSTIPATION =
            "android.permission.health.READ_SYMPTOM_CONSTIPATION";

    /**
     * Allows an application to read the user's cough symptom data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_COUGH = "android.permission.health.READ_SYMPTOM_COUGH";

    /**
     * Allows an application to read the user's cramps data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CRAMPS =
            "android.permission.health.READ_SYMPTOM_CRAMPS";

    /**
     * Allows an application to read the user's cravings data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_CRAVINGS =
            "android.permission.health.READ_SYMPTOM_CRAVINGS";

    /**
     * Allows an application to read the user's dehydration data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_DEHYDRATION =
            "android.permission.health.READ_SYMPTOM_DEHYDRATION";

    /**
     * Allows an application to read the user's diarrhea data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_DIARRHEA =
            "android.permission.health.READ_SYMPTOM_DIARRHEA";

    /**
     * Allows an application to read the user's difficulty swallowing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_DIFFICULTY_SWALLOWING =
            "android.permission.health.READ_SYMPTOM_DIFFICULTY_SWALLOWING";

    /**
     * Allows an application to read the user's dizziness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_DIZZINESS =
            "android.permission.health.READ_SYMPTOM_DIZZINESS";

    /**
     * Allows an application to read the user's dry skin data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_DRY_SKIN =
            "android.permission.health.READ_SYMPTOM_DRY_SKIN";

    /**
     * Allows an application to read the user's earaches data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_EARACHES =
            "android.permission.health.READ_SYMPTOM_EARACHES";

    /**
     * Allows an application to read the user's fatigue data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_FATIGUE =
            "android.permission.health.READ_SYMPTOM_FATIGUE";

    /**
     * Allows an application to read the user's fever data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_FEVER = "android.permission.health.READ_SYMPTOM_FEVER";

    /**
     * Allows an application to read the user's generalized body ache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_GENERALIZED_BODY_ACHE =
            "android.permission.health.READ_SYMPTOM_GENERALIZED_BODY_ACHE";

    /**
     * Allows an application to read the user's hair loss data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_HAIR_LOSS =
            "android.permission.health.READ_SYMPTOM_HAIR_LOSS";

    /**
     * Allows an application to read the user's headache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_HEADACHE =
            "android.permission.health.READ_SYMPTOM_HEADACHE";

    /**
     * Allows an application to read the user's heartburn data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_HEARTBURN =
            "android.permission.health.READ_SYMPTOM_HEARTBURN";

    /**
     * Allows an application to read the user's heart palpitations data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_HEART_PALPITATIONS =
            "android.permission.health.READ_SYMPTOM_HEART_PALPITATIONS";

    /**
     * Allows an application to read the user's hot flashes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_HOT_FLASHES =
            "android.permission.health.READ_SYMPTOM_HOT_FLASHES";

    /**
     * Allows an application to read the user's insomnia data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_INSOMNIA =
            "android.permission.health.READ_SYMPTOM_INSOMNIA";

    /**
     * Allows an application to read the user's joint pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_JOINT_PAIN =
            "android.permission.health.READ_SYMPTOM_JOINT_PAIN";

    /**
     * Allows an application to read the user's joint stiffness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_JOINT_STIFFNESS =
            "android.permission.health.READ_SYMPTOM_JOINT_STIFFNESS";

    /**
     * Allows an application to read the user's loss of appetite data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_LOSS_OF_APPETITE =
            "android.permission.health.READ_SYMPTOM_LOSS_OF_APPETITE";

    /**
     * Allows an application to read the user's loss of consciousness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS =
            "android.permission.health.READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS";

    /**
     * Allows an application to read the user's lower back pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_LOWER_BACK_PAIN =
            "android.permission.health.READ_SYMPTOM_LOWER_BACK_PAIN";

    /**
     * Allows an application to read the user's memory lapse data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_MEMORY_LAPSE =
            "android.permission.health.READ_SYMPTOM_MEMORY_LAPSE";

    /**
     * Allows an application to read the user's mood change data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_MOOD_CHANGE =
            "android.permission.health.READ_SYMPTOM_MOOD_CHANGE";

    /**
     * Allows an application to read the user's muscle pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_MUSCLE_PAIN =
            "android.permission.health.READ_SYMPTOM_MUSCLE_PAIN";

    /**
     * Allows an application to read the user's nausea data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_NAUSEA =
            "android.permission.health.READ_SYMPTOM_NAUSEA";

    /**
     * Allows an application to read the user's night sweats data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_NIGHT_SWEATS =
            "android.permission.health.READ_SYMPTOM_NIGHT_SWEATS";

    /**
     * Allows an application to read the user's pelvic pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_PELVIC_PAIN =
            "android.permission.health.READ_SYMPTOM_PELVIC_PAIN";

    /**
     * Allows an application to read the user's rapid, pounding, or fluttering heartbeat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT =
            "android.permission.health.READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT";

    /**
     * Allows an application to read the user's reduced capacity for exercise data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE =
            "android.permission.health.READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE";

    /**
     * Allows an application to read the user's runny nose data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_RUNNY_NOSE =
            "android.permission.health.READ_SYMPTOM_RUNNY_NOSE";

    /**
     * Allows an application to read the user's shortness of breath data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SHORTNESS_OF_BREATH =
            "android.permission.health.READ_SYMPTOM_SHORTNESS_OF_BREATH";

    /**
     * Allows an application to read the user's skipped heartbeat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SKIPPED_HEARTBEAT =
            "android.permission.health.READ_SYMPTOM_SKIPPED_HEARTBEAT";

    /**
     * Allows an application to read the user's sleep changes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SLEEP_CHANGES =
            "android.permission.health.READ_SYMPTOM_SLEEP_CHANGES";

    /**
     * Allows an application to read the user's sleepiness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SLEEPINESS =
            "android.permission.health.READ_SYMPTOM_SLEEPINESS";

    /**
     * Allows an application to read the user's sneezing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SNEEZING =
            "android.permission.health.READ_SYMPTOM_SNEEZING";

    /**
     * Allows an application to read the user's snore data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SNORE = "android.permission.health.READ_SYMPTOM_SNORE";

    /**
     * Allows an application to read the user's sore throat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_SORE_THROAT =
            "android.permission.health.READ_SYMPTOM_SORE_THROAT";

    /**
     * Allows an application to read the user's stomach ache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_STOMACH_ACHE =
            "android.permission.health.READ_SYMPTOM_STOMACH_ACHE";

    /**
     * Allows an application to read the user's stuffy nose data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_STUFFY_NOSE =
            "android.permission.health.READ_SYMPTOM_STUFFY_NOSE";

    /**
     * Allows an application to read the user's unexplained weight changes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES =
            "android.permission.health.READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES";

    /**
     * Allows an application to read the user's vaginal dryness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_VAGINAL_DRYNESS =
            "android.permission.health.READ_SYMPTOM_VAGINAL_DRYNESS";

    /**
     * Allows an application to read the user's vaginal itchiness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_VAGINAL_ITCHINESS =
            "android.permission.health.READ_SYMPTOM_VAGINAL_ITCHINESS";

    /**
     * Allows an application to read the user's vomiting data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_VOMITING =
            "android.permission.health.READ_SYMPTOM_VOMITING";

    /**
     * Allows an application to read the user's water retention data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_WATER_RETENTION =
            "android.permission.health.READ_SYMPTOM_WATER_RETENTION";

    /**
     * Allows an application to read the user's wheezing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String READ_SYMPTOM_WHEEZING =
            "android.permission.health.READ_SYMPTOM_WHEEZING";

    /**
     * Allows an application to write the user's calories burned data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_ACTIVE_CALORIES_BURNED =
            "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED";

    /**
     * Allows an application to write the user's activity intensity data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_ACTIVITY_INTENSITY)
    public static final String WRITE_ACTIVITY_INTENSITY =
            "android.permission.health.WRITE_ACTIVITY_INTENSITY";

    /**
     * Allows an application to write the user's distance data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_DISTANCE = "android.permission.health.WRITE_DISTANCE";

    /**
     * Allows an application to write the user's elevation gained data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_ELEVATION_GAINED =
            "android.permission.health.WRITE_ELEVATION_GAINED";

    /**
     * Allows an application to write the user's exercise data. Additional permission {@link
     * HealthPermissions#WRITE_EXERCISE_ROUTE} is required to write user's exercise route.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE";

    /**
     * Allows an application to write the user's exercise route.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_EXERCISE_ROUTE =
            "android.permission.health.WRITE_EXERCISE_ROUTE";

    /**
     * Allows an application to write the user's floors climbed data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_FLOORS_CLIMBED =
            "android.permission.health.WRITE_FLOORS_CLIMBED";

    /**
     * Allows an application to write the user's steps data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_STEPS = "android.permission.health.WRITE_STEPS";

    /**
     * Allows an application to write the user's total calories burned data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_TOTAL_CALORIES_BURNED =
            "android.permission.health.WRITE_TOTAL_CALORIES_BURNED";

    /**
     * Allows an application to write the user's vo2 maximum data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_VO2_MAX = "android.permission.health.WRITE_VO2_MAX";

    /**
     * Allows an application to write the user's wheelchair pushes data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_WHEELCHAIR_PUSHES =
            "android.permission.health.WRITE_WHEELCHAIR_PUSHES";

    /**
     * Allows an application to write the user's power data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_POWER = "android.permission.health.WRITE_POWER";

    /**
     * Allows an application to write the user's speed data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_SPEED = "android.permission.health.WRITE_SPEED";

    /**
     * Allows an application to write the user's basal metabolic rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BASAL_METABOLIC_RATE =
            "android.permission.health.WRITE_BASAL_METABOLIC_RATE";

    /**
     * Allows an application to write the user's body fat data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BODY_FAT = "android.permission.health.WRITE_BODY_FAT";

    /**
     * Allows an application to write the user's body water mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BODY_WATER_MASS =
            "android.permission.health.WRITE_BODY_WATER_MASS";

    /**
     * Allows an application to write the user's bone mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BONE_MASS = "android.permission.health.WRITE_BONE_MASS";

    /**
     * Allows an application to write the user's height data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_HEIGHT = "android.permission.health.WRITE_HEIGHT";

    /**
     * Allows an application to write the user's lean body mass data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_LEAN_BODY_MASS =
            "android.permission.health.WRITE_LEAN_BODY_MASS";

    /**
     * Allows an application to write the user's weight data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_WEIGHT = "android.permission.health.WRITE_WEIGHT";

    /**
     * Allows an application to write the user's cervical mucus data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_CERVICAL_MUCUS =
            "android.permission.health.WRITE_CERVICAL_MUCUS";

    /**
     * Allows an application to write the user's cycle phases data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
    public static final String WRITE_MENSTRUAL_CYCLE_PHASE =
            "android.permission.health.WRITE_MENSTRUAL_CYCLE_PHASE";

    /**
     * Allows an application to write the user's menstruation data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_MENSTRUATION = "android.permission.health.WRITE_MENSTRUATION";

    /**
     * Allows an application to write the user's intermenstrual bleeding data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_INTERMENSTRUAL_BLEEDING =
            "android.permission.health.WRITE_INTERMENSTRUAL_BLEEDING";

    /**
     * Allows an application to write the user's ovulation test data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_OVULATION_TEST =
            "android.permission.health.WRITE_OVULATION_TEST";

    /**
     * Allows an application to write the user's sexual activity data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_SEXUAL_ACTIVITY =
            "android.permission.health.WRITE_SEXUAL_ACTIVITY";

    /**
     * Allows an application to write the user's hydration data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_HYDRATION = "android.permission.health.WRITE_HYDRATION";

    /**
     * Allows an application to write the user's nutrition data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_NUTRITION = "android.permission.health.WRITE_NUTRITION";

    /**
     * Allows an application to write the user's sleep data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_SLEEP = "android.permission.health.WRITE_SLEEP";

    /**
     * Allows an application to write the user's basal body temperature data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BASAL_BODY_TEMPERATURE =
            "android.permission.health.WRITE_BASAL_BODY_TEMPERATURE";

    /**
     * Allows an application to write the user's blood glucose data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BLOOD_GLUCOSE =
            "android.permission.health.WRITE_BLOOD_GLUCOSE";

    /**
     * Allows an application to write the user's blood pressure data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BLOOD_PRESSURE =
            "android.permission.health.WRITE_BLOOD_PRESSURE";

    /**
     * Allows an application to write the user's body temperature data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_BODY_TEMPERATURE =
            "android.permission.health.WRITE_BODY_TEMPERATURE";

    /**
     * Allows an application to write the user's heart rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_HEART_RATE = "android.permission.health.WRITE_HEART_RATE";

    /**
     * Allows an application to write the user's heart rate variability data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_HEART_RATE_VARIABILITY =
            "android.permission.health.WRITE_HEART_RATE_VARIABILITY";

    /**
     * Allows an application to write the user's oxygen saturation data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_OXYGEN_SATURATION =
            "android.permission.health.WRITE_OXYGEN_SATURATION";

    /**
     * Allows an application to write the user's respiratory rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_RESPIRATORY_RATE =
            "android.permission.health.WRITE_RESPIRATORY_RATE";

    /**
     * Allows an application to write the user's resting heart rate data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_RESTING_HEART_RATE =
            "android.permission.health.WRITE_RESTING_HEART_RATE";

    /**
     * Allows an application to write the user's skin temperature data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi("com.android.healthconnect.flags.skin_temperature")
    public static final String WRITE_SKIN_TEMPERATURE =
            "android.permission.health.WRITE_SKIN_TEMPERATURE";

    /**
     * Allows an application to write the user's training plan data.
     *
     * <p>Protection level: dangerous.
     */
    public static final String WRITE_PLANNED_EXERCISE =
            "android.permission.health.WRITE_PLANNED_EXERCISE";

    /**
     * Allows an application to write user's mindfulness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_MINDFULNESS)
    public static final String WRITE_MINDFULNESS = "android.permission.health.WRITE_MINDFULNESS";

    /**
     * Allows an application to write user's nicotine intake data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SMOKING)
    public static final String WRITE_NICOTINE_INTAKE =
            "android.permission.health.WRITE_NICOTINE_INTAKE";

    /**
     * Allows an application to write user's alcohol consumption data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_ALCOHOL_CONSUMPTION)
    public static final String WRITE_ALCOHOL_CONSUMPTION =
            "android.permission.health.WRITE_ALCOHOL_CONSUMPTION";

    /**
     * Allows an application to write the user's cough symptom data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_COUGH =
            "android.permission.health.WRITE_SYMPTOM_COUGH";

    /**
     * Allows an application to write the user's acne data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_ACNE = "android.permission.health.WRITE_SYMPTOM_ACNE";

    /**
     * Allows an application to write the user's brittle nails data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BRITTLE_NAILS =
            "android.permission.health.WRITE_SYMPTOM_BRITTLE_NAILS";

    /**
     * Allows an application to write the user's burning mouth  data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BURNING_MOUTH =
            "android.permission.health.WRITE_SYMPTOM_BURNING_MOUTH";

    /**
     * Allows an application to write the user's chills data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CHILLS =
            "android.permission.health.WRITE_SYMPTOM_CHILLS";

    /**
     * Allows an application to write the user's dehydration data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_DEHYDRATION =
            "android.permission.health.WRITE_SYMPTOM_DEHYDRATION";

    /**
     * Allows an application to write the user's dry skin data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_DRY_SKIN =
            "android.permission.health.WRITE_SYMPTOM_DRY_SKIN";

    /**
     * Allows an application to write the user's fever data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_FEVER =
            "android.permission.health.WRITE_SYMPTOM_FEVER";

    /**
     * Allows an application to write the user's joint pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_JOINT_PAIN =
            "android.permission.health.WRITE_SYMPTOM_JOINT_PAIN";

    /**
     * Allows an application to write the user's joint stiffness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_JOINT_STIFFNESS =
            "android.permission.health.WRITE_SYMPTOM_JOINT_STIFFNESS";

    /**
     * Allows an application to write the user's muscle pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_MUSCLE_PAIN =
            "android.permission.health.WRITE_SYMPTOM_MUSCLE_PAIN";

    /**
     * Allows an application to write the user's loss of consciousness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS =
            "android.permission.health.WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS";

    /**
     * Allows an application to write the user's reduced capacity for exercise data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE =
            "android.permission.health.WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE";

    /**
     * Allows an application to write the user's unexplained weight changes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES =
            "android.permission.health.WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES";

    /**
     * Allows an application to write the user's generalized body ache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_GENERALIZED_BODY_ACHE =
            "android.permission.health.WRITE_SYMPTOM_GENERALIZED_BODY_ACHE";

    /**
     * Allows an application to write the user's fatigue data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_FATIGUE =
            "android.permission.health.WRITE_SYMPTOM_FATIGUE";

    /**
     * Allows an application to write the user's constipation data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CONSTIPATION =
            "android.permission.health.WRITE_SYMPTOM_CONSTIPATION";

    /**
     * Allows an application to write the user's diarrhea data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_DIARRHEA =
            "android.permission.health.WRITE_SYMPTOM_DIARRHEA";

    /**
     * Allows an application to write the user's loss of appetite data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_LOSS_OF_APPETITE =
            "android.permission.health.WRITE_SYMPTOM_LOSS_OF_APPETITE";

    /**
     * Allows an application to write the user's nausea data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_NAUSEA =
            "android.permission.health.WRITE_SYMPTOM_NAUSEA";

    /**
     * Allows an application to write the user's stomach ache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_STOMACH_ACHE =
            "android.permission.health.WRITE_SYMPTOM_STOMACH_ACHE";

    /**
     * Allows an application to write the user's vomiting data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_VOMITING =
            "android.permission.health.WRITE_SYMPTOM_VOMITING";

    /**
     * Allows an application to write the user's chest pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CHEST_PAIN =
            "android.permission.health.WRITE_SYMPTOM_CHEST_PAIN";

    /**
     * Allows an application to write the user's chest tightness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CHEST_TIGHTNESS =
            "android.permission.health.WRITE_SYMPTOM_CHEST_TIGHTNESS";

    /**
     * Allows an application to write the user's heart palpitations data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_HEART_PALPITATIONS =
            "android.permission.health.WRITE_SYMPTOM_HEART_PALPITATIONS";

    /**
     * Allows an application to write the user's shortness of breath data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SHORTNESS_OF_BREATH =
            "android.permission.health.WRITE_SYMPTOM_SHORTNESS_OF_BREATH";

    /**
     * Allows an application to write the user's wheezing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_WHEEZING =
            "android.permission.health.WRITE_SYMPTOM_WHEEZING";

    /**
     * Allows an application to write the user's heartburn data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_HEARTBURN =
            "android.permission.health.WRITE_SYMPTOM_HEARTBURN";

    /**
     * Allows an application to write the user's skipped heartbeat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SKIPPED_HEARTBEAT =
            "android.permission.health.WRITE_SYMPTOM_SKIPPED_HEARTBEAT";

    /**
     * Allows an application to write the user's rapid, pounding, or fluttering heartbeat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT =
            "android.permission.health.WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT";

    /**
     * Allows an application to write the user's difficulty swallowing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_DIFFICULTY_SWALLOWING =
            "android.permission.health.WRITE_SYMPTOM_DIFFICULTY_SWALLOWING";

    /**
     * Allows an application to write the user's dizziness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_DIZZINESS =
            "android.permission.health.WRITE_SYMPTOM_DIZZINESS";

    /**
     * Allows an application to write the user's earaches data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_EARACHES =
            "android.permission.health.WRITE_SYMPTOM_EARACHES";

    /**
     * Allows an application to write the user's hair loss data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_HAIR_LOSS =
            "android.permission.health.WRITE_SYMPTOM_HAIR_LOSS";

    /**
     * Allows an application to write the user's headache data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_HEADACHE =
            "android.permission.health.WRITE_SYMPTOM_HEADACHE";

    /**
     * Allows an application to write the user's runny nose data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_RUNNY_NOSE =
            "android.permission.health.WRITE_SYMPTOM_RUNNY_NOSE";

    /**
     * Allows an application to write the user's sneezing data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SNEEZING =
            "android.permission.health.WRITE_SYMPTOM_SNEEZING";

    /**
     * Allows an application to write the user's sore throat data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SORE_THROAT =
            "android.permission.health.WRITE_SYMPTOM_SORE_THROAT";

    /**
     * Allows an application to write the user's stuffy nose data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_STUFFY_NOSE =
            "android.permission.health.WRITE_SYMPTOM_STUFFY_NOSE";

    /**
     * Allows an application to write the user's back pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BACK_PAIN =
            "android.permission.health.WRITE_SYMPTOM_BACK_PAIN";

    /**
     * Allows an application to write the user's lower back pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_LOWER_BACK_PAIN =
            "android.permission.health.WRITE_SYMPTOM_LOWER_BACK_PAIN";

    /**
     * Allows an application to write the user's hot flashes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_HOT_FLASHES =
            "android.permission.health.WRITE_SYMPTOM_HOT_FLASHES";

    /**
     * Allows an application to write the user's night sweats data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_NIGHT_SWEATS =
            "android.permission.health.WRITE_SYMPTOM_NIGHT_SWEATS";

    /**
     * Allows an application to write the user's water retention data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_WATER_RETENTION =
            "android.permission.health.WRITE_SYMPTOM_WATER_RETENTION";

    /**
     * Allows an application to write the user's cravings data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CRAVINGS =
            "android.permission.health.WRITE_SYMPTOM_CRAVINGS";

    /**
     * Allows an application to write the user's bloating data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BLOATING =
            "android.permission.health.WRITE_SYMPTOM_BLOATING";

    /**
     * Allows an application to write the user's cramps data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_CRAMPS =
            "android.permission.health.WRITE_SYMPTOM_CRAMPS";

    /**
     * Allows an application to write the user's abdominal pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_ABDOMINAL_PAIN =
            "android.permission.health.WRITE_SYMPTOM_ABDOMINAL_PAIN";

    /**
     * Allows an application to write the user's breast tenderness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BREAST_TENDERNESS =
            "android.permission.health.WRITE_SYMPTOM_BREAST_TENDERNESS";

    /**
     * Allows an application to write the user's pelvic pain data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_PELVIC_PAIN =
            "android.permission.health.WRITE_SYMPTOM_PELVIC_PAIN";

    /**
     * Allows an application to write the user's vaginal dryness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_VAGINAL_DRYNESS =
            "android.permission.health.WRITE_SYMPTOM_VAGINAL_DRYNESS";

    /**
     * Allows an application to write the user's vaginal itchiness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_VAGINAL_ITCHINESS =
            "android.permission.health.WRITE_SYMPTOM_VAGINAL_ITCHINESS";

    /**
     * Allows an application to write the user's brain fog data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_BRAIN_FOG =
            "android.permission.health.WRITE_SYMPTOM_BRAIN_FOG";

    /**
     * Allows an application to write the user's mood change data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_MOOD_CHANGE =
            "android.permission.health.WRITE_SYMPTOM_MOOD_CHANGE";

    /**
     * Allows an application to write the user's memory lapse data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_MEMORY_LAPSE =
            "android.permission.health.WRITE_SYMPTOM_MEMORY_LAPSE";

    /**
     * Allows an application to write the user's sleepiness data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SLEEPINESS =
            "android.permission.health.WRITE_SYMPTOM_SLEEPINESS";

    /**
     * Allows an application to write the user's sleep changes data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SLEEP_CHANGES =
            "android.permission.health.WRITE_SYMPTOM_SLEEP_CHANGES";

    /**
     * Allows an application to write the user's insomnia data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_INSOMNIA =
            "android.permission.health.WRITE_SYMPTOM_INSOMNIA";

    /**
     * Allows an application to write the user's snore data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final String WRITE_SYMPTOM_SNORE =
            "android.permission.health.WRITE_SYMPTOM_SNORE";

    /* Personal Health Record permissions */

    /**
     * Allows an application to read the user's data about allergies and intolerances.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES =
            "android.permission.health.READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES";

    /**
     * Allows an application to read the user's data about medical conditions.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_CONDITIONS =
            "android.permission.health.READ_MEDICAL_DATA_CONDITIONS";

    /**
     * Allows an application to read the user's data about medical devices.
     *
     * <p>Protection level: dangerous.
     *
     * @hide
     */
    // TODO: b/417657261 - change this to @FlaggedApi(FLAG_DEVICE_RESOURCE)
    public static final String READ_MEDICAL_DATA_DEVICES =
            "android.permission.health.READ_MEDICAL_DATA_DEVICES";

    /**
     * Allows an application to read the user's laboratory result data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_LABORATORY_RESULTS =
            "android.permission.health.READ_MEDICAL_DATA_LABORATORY_RESULTS";

    /**
     * Allows an application to read the user's medication data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_MEDICATIONS =
            "android.permission.health.READ_MEDICAL_DATA_MEDICATIONS";

    /**
     * Allows an application to read the user's personal details.
     *
     * <p>This is demographic information such as name, date of birth, contact details like address
     * or telephone number and so on. For more examples see the <a
     * href="https://www.hl7.org/fhir/patient.html">FHIR Patient resource</a>.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_PERSONAL_DETAILS =
            "android.permission.health.READ_MEDICAL_DATA_PERSONAL_DETAILS";

    /**
     * Allows an application to read the user's data about the practitioners who have interacted
     * with them in their medical record. This is the information about the clinicians (doctors,
     * nurses, etc) but also other practitioners (masseurs, physiotherapists, etc) who have been
     * involved with the patient.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_PRACTITIONER_DETAILS =
            "android.permission.health.READ_MEDICAL_DATA_PRACTITIONER_DETAILS";

    /**
     * Allows an application to read the user's pregnancy data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_PREGNANCY =
            "android.permission.health.READ_MEDICAL_DATA_PREGNANCY";

    /**
     * Allows an application to read the user's data about medical procedures.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_PROCEDURES =
            "android.permission.health.READ_MEDICAL_DATA_PROCEDURES";

    /**
     * Allows an application to read the user's social history data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_SOCIAL_HISTORY =
            "android.permission.health.READ_MEDICAL_DATA_SOCIAL_HISTORY";

    /**
     * Allows an application to read the user's data about immunizations and vaccinations.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_VACCINES =
            "android.permission.health.READ_MEDICAL_DATA_VACCINES";

    /**
     * Allows an application to read the user's information about their encounters with health care
     * practitioners, including things like location, time of appointment, and name of organization
     * the visit was with. Despite the name visit it covers remote encounters such as telephone or
     * videoconference appointments.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_VISITS =
            "android.permission.health.READ_MEDICAL_DATA_VISITS";

    /**
     * Allows an application to read the user's vital signs data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String READ_MEDICAL_DATA_VITAL_SIGNS =
            "android.permission.health.READ_MEDICAL_DATA_VITAL_SIGNS";

    /**
     * Allows an application to write the user's medical data.
     *
     * <p>Protection level: dangerous.
     */
    @FlaggedApi(FLAG_PERSONAL_HEALTH_RECORD)
    public static final String WRITE_MEDICAL_DATA = "android.permission.health.WRITE_MEDICAL_DATA";

    private HealthPermissions() {}

    /**
     * Returns all medical permissions (read and write).
     *
     * @hide
     */
    public static Set<String> getAllMedicalPermissions() {
        Set<String> permissions = new ArraySet<>();
        permissions.add(WRITE_MEDICAL_DATA);
        permissions.add(READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES);
        if (Flags.deviceResource()) {
            permissions.add(READ_MEDICAL_DATA_DEVICES);
        }
        permissions.add(READ_MEDICAL_DATA_CONDITIONS);
        permissions.add(READ_MEDICAL_DATA_LABORATORY_RESULTS);
        permissions.add(READ_MEDICAL_DATA_MEDICATIONS);
        permissions.add(READ_MEDICAL_DATA_PERSONAL_DETAILS);
        permissions.add(READ_MEDICAL_DATA_PRACTITIONER_DETAILS);
        permissions.add(READ_MEDICAL_DATA_PREGNANCY);
        permissions.add(READ_MEDICAL_DATA_PROCEDURES);
        permissions.add(READ_MEDICAL_DATA_SOCIAL_HISTORY);
        permissions.add(READ_MEDICAL_DATA_VACCINES);
        permissions.add(READ_MEDICAL_DATA_VISITS);
        permissions.add(READ_MEDICAL_DATA_VITAL_SIGNS);
        return permissions;
    }

    /**
     * Returns a set of dataCategories for which this package has WRITE permissions
     *
     * @hide
     */
    @NonNull
    public static Set<Integer> getDataCategoriesWithWritePermissionsForPackage(
            @NonNull PackageInfo packageInfo, @NonNull Context context) {

        Set<Integer> dataCategoriesWithPermissions = new HashSet<>();

        for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
            String currPerm = packageInfo.requestedPermissions[i];
            if (!HealthConnectManager.isHealthPermission(context, currPerm)) {
                continue;
            }
            if ((packageInfo.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_GRANTED)
                    == 0) {
                continue;
            }

            int dataCategory =
                    HealthConnectMappings.getInstance()
                            .getHealthDataCategoryForWritePermission(currPerm);
            if (dataCategory >= 0) {
                dataCategoriesWithPermissions.add(dataCategory);
            }
        }

        return dataCategoriesWithPermissions;
    }

    /**
     * Returns true if this package has at least one granted WRITE permission for this category.
     *
     * @hide
     */
    public static boolean getPackageHasWriteHealthPermissionsForCategory(
            @NonNull PackageInfo packageInfo,
            @HealthDataCategory.Type int dataCategory,
            @NonNull Context context) {
        return getDataCategoriesWithWritePermissionsForPackage(packageInfo, context)
                .contains(dataCategory);
    }

    /** @hide */
    public static boolean isValidHealthPermission(PermissionInfo permissionInfo) {
        return HEALTH_PERMISSION_GROUP.equals(permissionInfo.group)
                && isPermissionEnabled(permissionInfo.name);
    }

    /** @hide */
    // TODO(b/377285620): flag the permissions in the Manifest when fully supported.
    public static boolean isPermissionEnabled(@NonNull String permission) {
        return switch (permission) {
            case READ_ALCOHOL_CONSUMPTION, WRITE_ALCOHOL_CONSUMPTION -> Flags.alcoholConsumption();
            case READ_NICOTINE_INTAKE, WRITE_NICOTINE_INTAKE -> Flags.smoking();
            case READ_SYMPTOM_ABDOMINAL_PAIN,
                    READ_SYMPTOM_ACNE,
                    READ_SYMPTOM_BACK_PAIN,
                    READ_SYMPTOM_BLOATING,
                    READ_SYMPTOM_BRAIN_FOG,
                    READ_SYMPTOM_BREAST_TENDERNESS,
                    READ_SYMPTOM_BRITTLE_NAILS,
                    READ_SYMPTOM_BURNING_MOUTH,
                    READ_SYMPTOM_CHEST_PAIN,
                    READ_SYMPTOM_CHEST_TIGHTNESS,
                    READ_SYMPTOM_CHILLS,
                    READ_SYMPTOM_CONSTIPATION,
                    READ_SYMPTOM_COUGH,
                    READ_SYMPTOM_CRAMPS,
                    READ_SYMPTOM_CRAVINGS,
                    READ_SYMPTOM_DEHYDRATION,
                    READ_SYMPTOM_DIARRHEA,
                    READ_SYMPTOM_DIFFICULTY_SWALLOWING,
                    READ_SYMPTOM_DIZZINESS,
                    READ_SYMPTOM_DRY_SKIN,
                    READ_SYMPTOM_EARACHES,
                    READ_SYMPTOM_FATIGUE,
                    READ_SYMPTOM_FEVER,
                    READ_SYMPTOM_GENERALIZED_BODY_ACHE,
                    READ_SYMPTOM_HAIR_LOSS,
                    READ_SYMPTOM_HEADACHE,
                    READ_SYMPTOM_HEARTBURN,
                    READ_SYMPTOM_HEART_PALPITATIONS,
                    READ_SYMPTOM_HOT_FLASHES,
                    READ_SYMPTOM_INSOMNIA,
                    READ_SYMPTOM_JOINT_PAIN,
                    READ_SYMPTOM_JOINT_STIFFNESS,
                    READ_SYMPTOM_LOSS_OF_APPETITE,
                    READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                    READ_SYMPTOM_LOWER_BACK_PAIN,
                    READ_SYMPTOM_MEMORY_LAPSE,
                    READ_SYMPTOM_MOOD_CHANGE,
                    READ_SYMPTOM_MUSCLE_PAIN,
                    READ_SYMPTOM_NAUSEA,
                    READ_SYMPTOM_NIGHT_SWEATS,
                    READ_SYMPTOM_PELVIC_PAIN,
                    READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                    READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                    READ_SYMPTOM_RUNNY_NOSE,
                    READ_SYMPTOM_SHORTNESS_OF_BREATH,
                    READ_SYMPTOM_SKIPPED_HEARTBEAT,
                    READ_SYMPTOM_SLEEPINESS,
                    READ_SYMPTOM_SLEEP_CHANGES,
                    READ_SYMPTOM_SNEEZING,
                    READ_SYMPTOM_SNORE,
                    READ_SYMPTOM_SORE_THROAT,
                    READ_SYMPTOM_STOMACH_ACHE,
                    READ_SYMPTOM_STUFFY_NOSE,
                    READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                    READ_SYMPTOM_VAGINAL_DRYNESS,
                    READ_SYMPTOM_VAGINAL_ITCHINESS,
                    READ_SYMPTOM_VOMITING,
                    READ_SYMPTOM_WATER_RETENTION,
                    READ_SYMPTOM_WHEEZING,
                    WRITE_SYMPTOM_ABDOMINAL_PAIN,
                    WRITE_SYMPTOM_ACNE,
                    WRITE_SYMPTOM_BACK_PAIN,
                    WRITE_SYMPTOM_BLOATING,
                    WRITE_SYMPTOM_BRAIN_FOG,
                    WRITE_SYMPTOM_BREAST_TENDERNESS,
                    WRITE_SYMPTOM_BRITTLE_NAILS,
                    WRITE_SYMPTOM_BURNING_MOUTH,
                    WRITE_SYMPTOM_CHEST_PAIN,
                    WRITE_SYMPTOM_CHEST_TIGHTNESS,
                    WRITE_SYMPTOM_CHILLS,
                    WRITE_SYMPTOM_CONSTIPATION,
                    WRITE_SYMPTOM_COUGH,
                    WRITE_SYMPTOM_CRAMPS,
                    WRITE_SYMPTOM_CRAVINGS,
                    WRITE_SYMPTOM_DEHYDRATION,
                    WRITE_SYMPTOM_DIARRHEA,
                    WRITE_SYMPTOM_DIFFICULTY_SWALLOWING,
                    WRITE_SYMPTOM_DIZZINESS,
                    WRITE_SYMPTOM_DRY_SKIN,
                    WRITE_SYMPTOM_EARACHES,
                    WRITE_SYMPTOM_FATIGUE,
                    WRITE_SYMPTOM_FEVER,
                    WRITE_SYMPTOM_GENERALIZED_BODY_ACHE,
                    WRITE_SYMPTOM_HAIR_LOSS,
                    WRITE_SYMPTOM_HEADACHE,
                    WRITE_SYMPTOM_HEARTBURN,
                    WRITE_SYMPTOM_HEART_PALPITATIONS,
                    WRITE_SYMPTOM_HOT_FLASHES,
                    WRITE_SYMPTOM_INSOMNIA,
                    WRITE_SYMPTOM_JOINT_PAIN,
                    WRITE_SYMPTOM_JOINT_STIFFNESS,
                    WRITE_SYMPTOM_LOSS_OF_APPETITE,
                    WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                    WRITE_SYMPTOM_LOWER_BACK_PAIN,
                    WRITE_SYMPTOM_MEMORY_LAPSE,
                    WRITE_SYMPTOM_MOOD_CHANGE,
                    WRITE_SYMPTOM_MUSCLE_PAIN,
                    WRITE_SYMPTOM_NAUSEA,
                    WRITE_SYMPTOM_NIGHT_SWEATS,
                    WRITE_SYMPTOM_PELVIC_PAIN,
                    WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                    WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                    WRITE_SYMPTOM_RUNNY_NOSE,
                    WRITE_SYMPTOM_SHORTNESS_OF_BREATH,
                    WRITE_SYMPTOM_SKIPPED_HEARTBEAT,
                    WRITE_SYMPTOM_SLEEPINESS,
                    WRITE_SYMPTOM_SLEEP_CHANGES,
                    WRITE_SYMPTOM_SNEEZING,
                    WRITE_SYMPTOM_SNORE,
                    WRITE_SYMPTOM_SORE_THROAT,
                    WRITE_SYMPTOM_STOMACH_ACHE,
                    WRITE_SYMPTOM_STUFFY_NOSE,
                    WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                    WRITE_SYMPTOM_VAGINAL_DRYNESS,
                    WRITE_SYMPTOM_VAGINAL_ITCHINESS,
                    WRITE_SYMPTOM_VOMITING,
                    WRITE_SYMPTOM_WATER_RETENTION,
                    WRITE_SYMPTOM_WHEEZING ->
                    Flags.symptoms();
            case READ_MEDICAL_DATA_DEVICES -> Flags.deviceResource();
            case READ_MENSTRUAL_CYCLE_PHASE, WRITE_MENSTRUAL_CYCLE_PHASE ->
                    AconfigFlagHelper.isCyclePhasesEnabled();
            case WRITE_DEVICE_UDI -> Flags.deviceUdi();
            default -> true;
        };
    }
}
