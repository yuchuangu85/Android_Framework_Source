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
import static com.android.healthfitness.flags.Flags.FLAG_CYCLE_PHASES_FLAG;
import static com.android.healthfitness.flags.Flags.FLAG_MINDFULNESS;
import static com.android.healthfitness.flags.Flags.FLAG_SMOKING;
import static com.android.healthfitness.flags.Flags.FLAG_SYMPTOMS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.BasalMetabolicRateRecord;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ElevationGainedRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.MindfulnessSessionRecord;
import android.health.connect.datatypes.PowerRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.SpeedRecord;
import android.health.connect.datatypes.StepsRecord;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Represents the permission category of a {@link Record}. A record can only belong to one and only
 * one {@link HealthPermissionCategory}
 *
 * @hide
 */
@SystemApi
public class HealthPermissionCategory {
    public static final int UNKNOWN = 0;

    // ACTIVITY
    /** Permission category for {@link ActiveCaloriesBurnedRecord} */
    public static final int ACTIVE_CALORIES_BURNED = 1;

    /** Permission category for {@link android.health.connect.datatypes.ActivityIntensityRecord}. */
    @FlaggedApi(FLAG_ACTIVITY_INTENSITY)
    public static final int ACTIVITY_INTENSITY = 42;

    /** Permission category for {@link DistanceRecord} */
    public static final int DISTANCE = 2;

    /** Permission category for {@link ElevationGainedRecord} */
    public static final int ELEVATION_GAINED = 3;

    /**
     * Permission category for {@link android.health.connect.datatypes.ExerciseSessionRecord} and
     * {@link android.health.connect.datatypes.ExerciseLap}
     */
    public static final int EXERCISE = 4;

    /** Permission category for {FloorsClimbedRecord} */
    public static final int FLOORS_CLIMBED = 5;

    /** Permission category for {@link StepsRecord} */
    public static final int STEPS = 6;

    // BODY_MEASUREMENTS
    /** Permission category for {@link BasalMetabolicRateRecord} */
    public static final int BASAL_METABOLIC_RATE = 9;

    /** Permission category for {BodyFatRecord} */
    public static final int BODY_FAT = 10;

    /** Permission category for {BodyWaterMassRecord} */
    public static final int BODY_WATER_MASS = 11;

    /** Permission category for {BoneMassRecord} */
    public static final int BONE_MASS = 12;

    /** Permission category for {HeightRecord} */
    public static final int HEIGHT = 13;

    /** Permission category for {LeanBodyMassRecord} */
    public static final int LEAN_BODY_MASS = 15;

    /** Permission category for {@link PowerRecord} */
    public static final int POWER = 36;

    /** Permission category for {@link SpeedRecord} */
    public static final int SPEED = 37;

    /** Permission category for {TotalCaloriesBurnedRecord} */
    public static final int TOTAL_CALORIES_BURNED = 35;

    /** Permission category for {Vo2MaxRecord} */
    public static final int VO2_MAX = 7;

    /** Permission category for {WeightRecord} */
    public static final int WEIGHT = 17;

    /** Permission category for {WheelChairPushesRecord} */
    public static final int WHEELCHAIR_PUSHES = 8;

    // CYCLE_TRACKING
    /** Permission category for {CervicalMucusRecord} */
    public static final int CERVICAL_MUCUS = 18;

    /** Permission category for {MenstrualCyclePhaseRecord} */
    @FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
    public static final int MENSTRUAL_CYCLE_PHASE = 104;

    /** Permission category for {IntermenstrualBleedingRecord} */
    public static final int INTERMENSTRUAL_BLEEDING = 38;

    /** Permission category for {MenstruationRecord} */
    public static final int MENSTRUATION = 20;

    /** Permission category for {OvulationTestRecord} */
    public static final int OVULATION_TEST = 21;

    /** Permission category for {SexualActivityRecord} */
    public static final int SEXUAL_ACTIVITY = 22;

    // NUTRITION
    /** Permission category for {HydrationRecord} */
    public static final int HYDRATION = 23;

    /** Permission category for {NutritionRecord} */
    public static final int NUTRITION = 24;

    // SLEEP
    /** Permission category for {BasalBodyTemperatureRecord} */
    public static final int BASAL_BODY_TEMPERATURE = 33;

    /** Permission category for {SleepRecord} */
    public static final int SLEEP = 25;

    // VITALS
    /** Permission category for {BloodGlucose} */
    public static final int BLOOD_GLUCOSE = 26;

    /** Permission category for {BloodPressure} */
    public static final int BLOOD_PRESSURE = 27;

    /** Permission category for {BodyTemperature} */
    public static final int BODY_TEMPERATURE = 28;

    /** Permission category for {@link HeartRateRecord} */
    public static final int HEART_RATE = 29;

    /** Permission category for {HeartRateVariability} */
    public static final int HEART_RATE_VARIABILITY = 30;

    /** Permission category for {OxygenSaturation} */
    public static final int OXYGEN_SATURATION = 31;

    /** Permission category for {RespiratoryRate} */
    public static final int RESPIRATORY_RATE = 32;

    /** Permission category for {RestingHeartRate} */
    public static final int RESTING_HEART_RATE = 34;

    /** Permission category for {SkinTemperature} */
    @FlaggedApi("com.android.healthconnect.flags.skin_temperature")
    public static final int SKIN_TEMPERATURE = 39;

    /** Permission category for {PlannedExerciseSession} */
    public static final int PLANNED_EXERCISE = 40;

    // WELLNESS
    /** Permission category for {@link MindfulnessSessionRecord}. */
    @FlaggedApi(FLAG_MINDFULNESS)
    public static final int MINDFULNESS = 41;

    /** Permission category for {@link android.health.connect.datatypes.NicotineIntakeRecord} */
    @FlaggedApi(FLAG_SMOKING)
    public static final int NICOTINE_INTAKE = 43;

    /** Permission category for {AlcoholConsumptionRecord} */
    @FlaggedApi(FLAG_ALCOHOL_CONSUMPTION)
    public static final int ALCOHOL_CONSUMPTION = 44;

    /** Permission category for abdominal pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_ABDOMINAL_PAIN = 45;

    /** Permission category for acne. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_ACNE = 46;

    /** Permission category for back pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BACK_PAIN = 47;

    /** Permission category for bloating. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BLOATING = 48;

    /** Permission category for brain fog. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BRAIN_FOG = 49;

    /** Permission category for breast tenderness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BREAST_TENDERNESS = 50;

    /** Permission category for brittle nails. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BRITTLE_NAILS = 51;

    /** Permission category for burning mouth. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_BURNING_MOUTH = 52;

    /** Permission category for chest pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CHEST_PAIN = 53;

    /** Permission category for chest tightness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CHEST_TIGHTNESS = 54;

    /** Permission category for chills. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CHILLS = 55;

    /** Permission category for constipation. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CONSTIPATION = 56;

    /** Permission category for cough. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_COUGH = 57;

    /** Permission category for cramps. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CRAMPS = 58;

    /** Permission category for cravings. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_CRAVINGS = 59;

    /** Permission category for dehydration. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_DEHYDRATION = 60;

    /** Permission category for diarrhea. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_DIARRHEA = 61;

    /** Permission category for difficulty swallowing. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_DIFFICULTY_SWALLOWING = 62;

    /** Permission category for dizziness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_DIZZINESS = 63;

    /** Permission category for dry skin. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_DRY_SKIN = 64;

    /** Permission category for earaches. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_EARACHES = 65;

    /** Permission category for fatigue. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_FATIGUE = 66;

    /** Permission category for fever. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_FEVER = 67;

    /** Permission category for generalized body ache. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_GENERALIZED_BODY_ACHE = 68;

    /** Permission category for hair loss. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_HAIR_LOSS = 69;

    /** Permission category for headache. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_HEADACHE = 70;

    /** Permission category for heartburn. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_HEARTBURN = 71;

    /** Permission category for heart palpitations. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_HEART_PALPITATIONS = 72;

    /** Permission category for hot flashes. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_HOT_FLASHES = 73;

    /** Permission category for insomnia. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_INSOMNIA = 74;

    /** Permission category for joint pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_JOINT_PAIN = 75;

    /** Permission category for joint stiffness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_JOINT_STIFFNESS = 76;

    /** Permission category for loss of appetite. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_LOSS_OF_APPETITE = 77;

    /** Permission category for loss of consciousness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_LOSS_OF_CONSCIOUSNESS = 78;

    /** Permission category for lower back pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_LOWER_BACK_PAIN = 79;

    /** Permission category for memory lapse. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_MEMORY_LAPSE = 80;

    /** Permission category for mood change. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_MOOD_CHANGE = 81;

    /** Permission category for muscle pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_MUSCLE_PAIN = 82;

    /** Permission category for nausea. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_NAUSEA = 83;

    /** Permission category for night sweats. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_NIGHT_SWEATS = 84;

    /** Permission category for pelvic pain. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_PELVIC_PAIN = 85;

    /** Permission category for rapid, pounding, or fluttering heartbeat. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT = 86;

    /** Permission category for reduced capacity for exercise. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE = 87;

    /** Permission category for runny nose. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_RUNNY_NOSE = 88;

    /** Permission category for shortness of breath. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SHORTNESS_OF_BREATH = 89;

    /** Permission category for skipped heartbeat. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SKIPPED_HEARTBEAT = 90;

    /** Permission category for sleep changes. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SLEEP_CHANGES = 91;

    /** Permission category for sleepiness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SLEEPINESS = 92;

    /** Permission category for sneezing. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SNEEZING = 93;

    /** Permission category for snore. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SNORE = 94;

    /** Permission category for sore throat. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_SORE_THROAT = 95;

    /** Permission category for stomach ache. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_STOMACH_ACHE = 96;

    /** Permission category for stuffy nose. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_STUFFY_NOSE = 97;

    /** Permission category for unexplained weight changes. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES = 98;

    /** Permission category for vaginal dryness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_VAGINAL_DRYNESS = 99;

    /** Permission category for vaginal itchiness. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_VAGINAL_ITCHINESS = 100;

    /** Permission category for vomiting. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_VOMITING = 101;

    /** Permission category for water retention. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_WATER_RETENTION = 102;

    /** Permission category for wheezing. */
    @FlaggedApi(FLAG_SYMPTOMS)
    public static final int SYMPTOM_WHEEZING = 103;

    private HealthPermissionCategory() {}

    /** @hide */
    @IntDef({
        UNKNOWN,
        ACTIVE_CALORIES_BURNED,
        ACTIVITY_INTENSITY,
        DISTANCE,
        ELEVATION_GAINED,
        EXERCISE,
        FLOORS_CLIMBED,
        STEPS,
        TOTAL_CALORIES_BURNED,
        VO2_MAX,
        WHEELCHAIR_PUSHES,
        POWER,
        SPEED,
        BASAL_METABOLIC_RATE,
        BODY_FAT,
        BODY_WATER_MASS,
        BONE_MASS,
        HEIGHT,
        LEAN_BODY_MASS,
        WEIGHT,
        CERVICAL_MUCUS,
        MENSTRUATION,
        OVULATION_TEST,
        SEXUAL_ACTIVITY,
        INTERMENSTRUAL_BLEEDING,
        HYDRATION,
        NUTRITION,
        SLEEP,
        BASAL_BODY_TEMPERATURE,
        BLOOD_GLUCOSE,
        BLOOD_PRESSURE,
        BODY_TEMPERATURE,
        HEART_RATE,
        HEART_RATE_VARIABILITY,
        OXYGEN_SATURATION,
        RESPIRATORY_RATE,
        RESTING_HEART_RATE,
        SKIN_TEMPERATURE,
        PLANNED_EXERCISE,
        MINDFULNESS,
        NICOTINE_INTAKE,
        ALCOHOL_CONSUMPTION,
        SYMPTOM_ABDOMINAL_PAIN,
        SYMPTOM_ACNE,
        SYMPTOM_BACK_PAIN,
        SYMPTOM_BLOATING,
        SYMPTOM_BRAIN_FOG,
        SYMPTOM_BREAST_TENDERNESS,
        SYMPTOM_BRITTLE_NAILS,
        SYMPTOM_BURNING_MOUTH,
        SYMPTOM_CHEST_PAIN,
        SYMPTOM_CHEST_TIGHTNESS,
        SYMPTOM_CHILLS,
        SYMPTOM_CONSTIPATION,
        SYMPTOM_COUGH,
        SYMPTOM_CRAMPS,
        SYMPTOM_CRAVINGS,
        SYMPTOM_DEHYDRATION,
        SYMPTOM_DIARRHEA,
        SYMPTOM_DIFFICULTY_SWALLOWING,
        SYMPTOM_DIZZINESS,
        SYMPTOM_DRY_SKIN,
        SYMPTOM_EARACHES,
        SYMPTOM_FATIGUE,
        SYMPTOM_FEVER,
        SYMPTOM_GENERALIZED_BODY_ACHE,
        SYMPTOM_HAIR_LOSS,
        SYMPTOM_HEADACHE,
        SYMPTOM_HEARTBURN,
        SYMPTOM_HEART_PALPITATIONS,
        SYMPTOM_HOT_FLASHES,
        SYMPTOM_INSOMNIA,
        SYMPTOM_JOINT_PAIN,
        SYMPTOM_JOINT_STIFFNESS,
        SYMPTOM_LOSS_OF_APPETITE,
        SYMPTOM_LOSS_OF_CONSCIOUSNESS,
        SYMPTOM_LOWER_BACK_PAIN,
        SYMPTOM_MEMORY_LAPSE,
        SYMPTOM_MOOD_CHANGE,
        SYMPTOM_MUSCLE_PAIN,
        SYMPTOM_NAUSEA,
        SYMPTOM_NIGHT_SWEATS,
        SYMPTOM_PELVIC_PAIN,
        SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
        SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
        SYMPTOM_RUNNY_NOSE,
        SYMPTOM_SHORTNESS_OF_BREATH,
        SYMPTOM_SKIPPED_HEARTBEAT,
        SYMPTOM_SLEEP_CHANGES,
        SYMPTOM_SLEEPINESS,
        SYMPTOM_SNEEZING,
        SYMPTOM_SNORE,
        SYMPTOM_SORE_THROAT,
        SYMPTOM_STOMACH_ACHE,
        SYMPTOM_STUFFY_NOSE,
        SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
        SYMPTOM_VAGINAL_DRYNESS,
        SYMPTOM_VAGINAL_ITCHINESS,
        SYMPTOM_VOMITING,
        SYMPTOM_WATER_RETENTION,
        SYMPTOM_WHEEZING,
        MENSTRUAL_CYCLE_PHASE
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE_USE)
    public @interface Type {}
}
