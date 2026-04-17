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

package android.health.connect.internal.datatypes.utils;

import static android.health.connect.HealthPermissions.READ_ACTIVE_CALORIES_BURNED;
import static android.health.connect.HealthPermissions.READ_ACTIVITY_INTENSITY;
import static android.health.connect.HealthPermissions.READ_ALCOHOL_CONSUMPTION;
import static android.health.connect.HealthPermissions.READ_BASAL_BODY_TEMPERATURE;
import static android.health.connect.HealthPermissions.READ_BASAL_METABOLIC_RATE;
import static android.health.connect.HealthPermissions.READ_BLOOD_GLUCOSE;
import static android.health.connect.HealthPermissions.READ_BLOOD_PRESSURE;
import static android.health.connect.HealthPermissions.READ_BODY_FAT;
import static android.health.connect.HealthPermissions.READ_BODY_TEMPERATURE;
import static android.health.connect.HealthPermissions.READ_BODY_WATER_MASS;
import static android.health.connect.HealthPermissions.READ_BONE_MASS;
import static android.health.connect.HealthPermissions.READ_CERVICAL_MUCUS;
import static android.health.connect.HealthPermissions.READ_DISTANCE;
import static android.health.connect.HealthPermissions.READ_ELEVATION_GAINED;
import static android.health.connect.HealthPermissions.READ_EXERCISE;
import static android.health.connect.HealthPermissions.READ_FLOORS_CLIMBED;
import static android.health.connect.HealthPermissions.READ_HEART_RATE;
import static android.health.connect.HealthPermissions.READ_HEART_RATE_VARIABILITY;
import static android.health.connect.HealthPermissions.READ_HEIGHT;
import static android.health.connect.HealthPermissions.READ_HYDRATION;
import static android.health.connect.HealthPermissions.READ_INTERMENSTRUAL_BLEEDING;
import static android.health.connect.HealthPermissions.READ_LEAN_BODY_MASS;
import static android.health.connect.HealthPermissions.READ_MENSTRUAL_CYCLE_PHASE;
import static android.health.connect.HealthPermissions.READ_MENSTRUATION;
import static android.health.connect.HealthPermissions.READ_MINDFULNESS;
import static android.health.connect.HealthPermissions.READ_NICOTINE_INTAKE;
import static android.health.connect.HealthPermissions.READ_NUTRITION;
import static android.health.connect.HealthPermissions.READ_OVULATION_TEST;
import static android.health.connect.HealthPermissions.READ_OXYGEN_SATURATION;
import static android.health.connect.HealthPermissions.READ_PLANNED_EXERCISE;
import static android.health.connect.HealthPermissions.READ_POWER;
import static android.health.connect.HealthPermissions.READ_RESPIRATORY_RATE;
import static android.health.connect.HealthPermissions.READ_RESTING_HEART_RATE;
import static android.health.connect.HealthPermissions.READ_SEXUAL_ACTIVITY;
import static android.health.connect.HealthPermissions.READ_SKIN_TEMPERATURE;
import static android.health.connect.HealthPermissions.READ_SLEEP;
import static android.health.connect.HealthPermissions.READ_SPEED;
import static android.health.connect.HealthPermissions.READ_STEPS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_ABDOMINAL_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_ACNE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BACK_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BLOATING;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BRAIN_FOG;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BREAST_TENDERNESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BRITTLE_NAILS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_BURNING_MOUTH;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CHEST_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CHEST_TIGHTNESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CHILLS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CONSTIPATION;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_COUGH;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CRAMPS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_CRAVINGS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_DEHYDRATION;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_DIARRHEA;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_DIFFICULTY_SWALLOWING;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_DIZZINESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_DRY_SKIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_EARACHES;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_FATIGUE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_FEVER;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_GENERALIZED_BODY_ACHE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_HAIR_LOSS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_HEADACHE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_HEARTBURN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_HEART_PALPITATIONS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_HOT_FLASHES;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_INSOMNIA;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_JOINT_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_JOINT_STIFFNESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_LOSS_OF_APPETITE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_LOWER_BACK_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_MEMORY_LAPSE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_MOOD_CHANGE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_MUSCLE_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_NAUSEA;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_NIGHT_SWEATS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_PELVIC_PAIN;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_RUNNY_NOSE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SHORTNESS_OF_BREATH;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SKIPPED_HEARTBEAT;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SLEEPINESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SLEEP_CHANGES;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SNEEZING;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SNORE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_SORE_THROAT;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_STOMACH_ACHE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_STUFFY_NOSE;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_VAGINAL_DRYNESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_VAGINAL_ITCHINESS;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_VOMITING;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_WATER_RETENTION;
import static android.health.connect.HealthPermissions.READ_SYMPTOM_WHEEZING;
import static android.health.connect.HealthPermissions.READ_TOTAL_CALORIES_BURNED;
import static android.health.connect.HealthPermissions.READ_VO2_MAX;
import static android.health.connect.HealthPermissions.READ_WEIGHT;
import static android.health.connect.HealthPermissions.READ_WHEELCHAIR_PUSHES;
import static android.health.connect.HealthPermissions.WRITE_ACTIVE_CALORIES_BURNED;
import static android.health.connect.HealthPermissions.WRITE_ACTIVITY_INTENSITY;
import static android.health.connect.HealthPermissions.WRITE_ALCOHOL_CONSUMPTION;
import static android.health.connect.HealthPermissions.WRITE_BASAL_BODY_TEMPERATURE;
import static android.health.connect.HealthPermissions.WRITE_BASAL_METABOLIC_RATE;
import static android.health.connect.HealthPermissions.WRITE_BLOOD_GLUCOSE;
import static android.health.connect.HealthPermissions.WRITE_BLOOD_PRESSURE;
import static android.health.connect.HealthPermissions.WRITE_BODY_FAT;
import static android.health.connect.HealthPermissions.WRITE_BODY_TEMPERATURE;
import static android.health.connect.HealthPermissions.WRITE_BODY_WATER_MASS;
import static android.health.connect.HealthPermissions.WRITE_BONE_MASS;
import static android.health.connect.HealthPermissions.WRITE_CERVICAL_MUCUS;
import static android.health.connect.HealthPermissions.WRITE_DISTANCE;
import static android.health.connect.HealthPermissions.WRITE_ELEVATION_GAINED;
import static android.health.connect.HealthPermissions.WRITE_EXERCISE;
import static android.health.connect.HealthPermissions.WRITE_FLOORS_CLIMBED;
import static android.health.connect.HealthPermissions.WRITE_HEART_RATE;
import static android.health.connect.HealthPermissions.WRITE_HEART_RATE_VARIABILITY;
import static android.health.connect.HealthPermissions.WRITE_HEIGHT;
import static android.health.connect.HealthPermissions.WRITE_HYDRATION;
import static android.health.connect.HealthPermissions.WRITE_INTERMENSTRUAL_BLEEDING;
import static android.health.connect.HealthPermissions.WRITE_LEAN_BODY_MASS;
import static android.health.connect.HealthPermissions.WRITE_MENSTRUAL_CYCLE_PHASE;
import static android.health.connect.HealthPermissions.WRITE_MENSTRUATION;
import static android.health.connect.HealthPermissions.WRITE_MINDFULNESS;
import static android.health.connect.HealthPermissions.WRITE_NICOTINE_INTAKE;
import static android.health.connect.HealthPermissions.WRITE_NUTRITION;
import static android.health.connect.HealthPermissions.WRITE_OVULATION_TEST;
import static android.health.connect.HealthPermissions.WRITE_OXYGEN_SATURATION;
import static android.health.connect.HealthPermissions.WRITE_PLANNED_EXERCISE;
import static android.health.connect.HealthPermissions.WRITE_POWER;
import static android.health.connect.HealthPermissions.WRITE_RESPIRATORY_RATE;
import static android.health.connect.HealthPermissions.WRITE_RESTING_HEART_RATE;
import static android.health.connect.HealthPermissions.WRITE_SEXUAL_ACTIVITY;
import static android.health.connect.HealthPermissions.WRITE_SKIN_TEMPERATURE;
import static android.health.connect.HealthPermissions.WRITE_SLEEP;
import static android.health.connect.HealthPermissions.WRITE_SPEED;
import static android.health.connect.HealthPermissions.WRITE_STEPS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_ABDOMINAL_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_ACNE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BACK_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BLOATING;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BRAIN_FOG;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BREAST_TENDERNESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BRITTLE_NAILS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_BURNING_MOUTH;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CHEST_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CHEST_TIGHTNESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CHILLS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CONSTIPATION;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_COUGH;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CRAMPS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_CRAVINGS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_DEHYDRATION;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_DIARRHEA;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_DIFFICULTY_SWALLOWING;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_DIZZINESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_DRY_SKIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_EARACHES;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_FATIGUE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_FEVER;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_GENERALIZED_BODY_ACHE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_HAIR_LOSS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_HEADACHE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_HEARTBURN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_HEART_PALPITATIONS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_HOT_FLASHES;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_INSOMNIA;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_JOINT_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_JOINT_STIFFNESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_LOSS_OF_APPETITE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_LOWER_BACK_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_MEMORY_LAPSE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_MOOD_CHANGE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_MUSCLE_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_NAUSEA;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_NIGHT_SWEATS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_PELVIC_PAIN;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_RUNNY_NOSE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SHORTNESS_OF_BREATH;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SKIPPED_HEARTBEAT;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SLEEPINESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SLEEP_CHANGES;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SNEEZING;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SNORE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_SORE_THROAT;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_STOMACH_ACHE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_STUFFY_NOSE;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_VAGINAL_DRYNESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_VAGINAL_ITCHINESS;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_VOMITING;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_WATER_RETENTION;
import static android.health.connect.HealthPermissions.WRITE_SYMPTOM_WHEEZING;
import static android.health.connect.HealthPermissions.WRITE_TOTAL_CALORIES_BURNED;
import static android.health.connect.HealthPermissions.WRITE_VO2_MAX;
import static android.health.connect.HealthPermissions.WRITE_WEIGHT;
import static android.health.connect.HealthPermissions.WRITE_WHEELCHAIR_PUSHES;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_ACTIVE_CALORIES_BURNED;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_ACTIVITY_INTENSITY;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_ALCOHOL_CONSUMPTION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BASAL_BODY_TEMPERATURE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BASAL_METABOLIC_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BLOOD_GLUCOSE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BLOOD_PRESSURE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BODY_FAT;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BODY_TEMPERATURE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BODY_WATER_MASS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BONE_MASS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_CERVICAL_MUCUS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_DISTANCE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_ELEVATION_GAINED;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_EXERCISE_SESSION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_FLOORS_CLIMBED;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_HEART_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_HEART_RATE_VARIABILITY_RMSSD;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_HEIGHT;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_HYDRATION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_INTERMENSTRUAL_BLEEDING;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_LEAN_BODY_MASS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_MENSTRUAL_CYCLE_PHASE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_MENSTRUATION_FLOW;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_MENSTRUATION_PERIOD;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_MINDFULNESS_SESSION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_NICOTINE_INTAKE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_NUTRITION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_OVULATION_TEST;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_OXYGEN_SATURATION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_PLANNED_EXERCISE_SESSION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_POWER;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_RESPIRATORY_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_RESTING_HEART_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_SEXUAL_ACTIVITY;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_SKIN_TEMPERATURE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_SLEEP_SESSION;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_SPEED;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_STEPS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_STEPS_CADENCE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_SYMPTOM;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_TOTAL_CALORIES_BURNED;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_VO2_MAX;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_WEIGHT;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_WHEELCHAIR_PUSHES;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.health.connect.HealthDataCategory;
import android.health.connect.HealthPermissionCategory;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.ActivityIntensityRecord;
import android.health.connect.datatypes.AlcoholConsumptionRecord;
import android.health.connect.datatypes.BasalBodyTemperatureRecord;
import android.health.connect.datatypes.BasalMetabolicRateRecord;
import android.health.connect.datatypes.BloodGlucoseRecord;
import android.health.connect.datatypes.BloodPressureRecord;
import android.health.connect.datatypes.BodyFatRecord;
import android.health.connect.datatypes.BodyTemperatureRecord;
import android.health.connect.datatypes.BodyWaterMassRecord;
import android.health.connect.datatypes.BoneMassRecord;
import android.health.connect.datatypes.CervicalMucusRecord;
import android.health.connect.datatypes.CyclingPedalingCadenceRecord;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ElevationGainedRecord;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.FloorsClimbedRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord;
import android.health.connect.datatypes.HeightRecord;
import android.health.connect.datatypes.HydrationRecord;
import android.health.connect.datatypes.IntermenstrualBleedingRecord;
import android.health.connect.datatypes.LeanBodyMassRecord;
import android.health.connect.datatypes.MenstrualCyclePhaseRecord;
import android.health.connect.datatypes.MenstruationFlowRecord;
import android.health.connect.datatypes.MenstruationPeriodRecord;
import android.health.connect.datatypes.MindfulnessSessionRecord;
import android.health.connect.datatypes.NicotineIntakeRecord;
import android.health.connect.datatypes.NutritionRecord;
import android.health.connect.datatypes.OvulationTestRecord;
import android.health.connect.datatypes.OxygenSaturationRecord;
import android.health.connect.datatypes.PlannedExerciseSessionRecord;
import android.health.connect.datatypes.PowerRecord;
import android.health.connect.datatypes.RecordTypeSensitivity;
import android.health.connect.datatypes.RespiratoryRateRecord;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SexualActivityRecord;
import android.health.connect.datatypes.SkinTemperatureRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.SpeedRecord;
import android.health.connect.datatypes.StepsCadenceRecord;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.SymptomRecord;
import android.health.connect.datatypes.TotalCaloriesBurnedRecord;
import android.health.connect.datatypes.Vo2MaxRecord;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.WheelchairPushesRecord;
import android.health.connect.internal.datatypes.ActiveCaloriesBurnedRecordInternal;
import android.health.connect.internal.datatypes.ActivityIntensityRecordInternal;
import android.health.connect.internal.datatypes.AlcoholConsumptionRecordInternal;
import android.health.connect.internal.datatypes.BasalBodyTemperatureRecordInternal;
import android.health.connect.internal.datatypes.BasalMetabolicRateRecordInternal;
import android.health.connect.internal.datatypes.BloodGlucoseRecordInternal;
import android.health.connect.internal.datatypes.BloodPressureRecordInternal;
import android.health.connect.internal.datatypes.BodyFatRecordInternal;
import android.health.connect.internal.datatypes.BodyTemperatureRecordInternal;
import android.health.connect.internal.datatypes.BodyWaterMassRecordInternal;
import android.health.connect.internal.datatypes.BoneMassRecordInternal;
import android.health.connect.internal.datatypes.CervicalMucusRecordInternal;
import android.health.connect.internal.datatypes.CyclingPedalingCadenceRecordInternal;
import android.health.connect.internal.datatypes.DistanceRecordInternal;
import android.health.connect.internal.datatypes.ElevationGainedRecordInternal;
import android.health.connect.internal.datatypes.ExerciseSessionRecordInternal;
import android.health.connect.internal.datatypes.FloorsClimbedRecordInternal;
import android.health.connect.internal.datatypes.HeartRateRecordInternal;
import android.health.connect.internal.datatypes.HeartRateVariabilityRmssdRecordInternal;
import android.health.connect.internal.datatypes.HeightRecordInternal;
import android.health.connect.internal.datatypes.HydrationRecordInternal;
import android.health.connect.internal.datatypes.IntermenstrualBleedingRecordInternal;
import android.health.connect.internal.datatypes.LeanBodyMassRecordInternal;
import android.health.connect.internal.datatypes.MenstrualCyclePhaseRecordInternal;
import android.health.connect.internal.datatypes.MenstruationFlowRecordInternal;
import android.health.connect.internal.datatypes.MenstruationPeriodRecordInternal;
import android.health.connect.internal.datatypes.MindfulnessSessionRecordInternal;
import android.health.connect.internal.datatypes.NicotineIntakeRecordInternal;
import android.health.connect.internal.datatypes.NutritionRecordInternal;
import android.health.connect.internal.datatypes.OvulationTestRecordInternal;
import android.health.connect.internal.datatypes.OxygenSaturationRecordInternal;
import android.health.connect.internal.datatypes.PlannedExerciseSessionRecordInternal;
import android.health.connect.internal.datatypes.PowerRecordInternal;
import android.health.connect.internal.datatypes.RespiratoryRateRecordInternal;
import android.health.connect.internal.datatypes.RestingHeartRateRecordInternal;
import android.health.connect.internal.datatypes.SexualActivityRecordInternal;
import android.health.connect.internal.datatypes.SkinTemperatureRecordInternal;
import android.health.connect.internal.datatypes.SleepSessionRecordInternal;
import android.health.connect.internal.datatypes.SpeedRecordInternal;
import android.health.connect.internal.datatypes.StepsCadenceRecordInternal;
import android.health.connect.internal.datatypes.StepsRecordInternal;
import android.health.connect.internal.datatypes.SymptomRecordInternal;
import android.health.connect.internal.datatypes.TotalCaloriesBurnedRecordInternal;
import android.health.connect.internal.datatypes.Vo2MaxRecordInternal;
import android.health.connect.internal.datatypes.WeightRecordInternal;
import android.health.connect.internal.datatypes.WheelchairPushesRecordInternal;

import com.android.healthfitness.flags.AconfigFlagHelper;
import com.android.healthfitness.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** @hide */
@VisibleForTesting(visibility = PACKAGE)
public class DataTypeDescriptors {

    /** Returns descriptors for all supported data types. */
    @VisibleForTesting(visibility = PACKAGE)
    public static List<DataTypeDescriptor> getAllDataTypeDescriptors() {
        return Stream.of(
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_ACTIVE_CALORIES_BURNED)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(ActiveCaloriesBurnedRecord.class)
                                .setRecordInternalClass(ActiveCaloriesBurnedRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.ACTIVE_CALORIES_BURNED,
                                        READ_ACTIVE_CALORIES_BURNED,
                                        WRITE_ACTIVE_CALORIES_BURNED)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_ACTIVITY_INTENSITY)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(ActivityIntensityRecord.class)
                                .setRecordInternalClass(ActivityIntensityRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.ACTIVITY_INTENSITY,
                                        READ_ACTIVITY_INTENSITY,
                                        WRITE_ACTIVITY_INTENSITY)
                                .build(),
                        Flags.alcoholConsumption()
                                        && AconfigFlagHelper.isAlcoholConsumptionEnabled()
                                ? DataTypeDescriptor.builder()
                                        .setRecordTypeIdentifier(RECORD_TYPE_ALCOHOL_CONSUMPTION)
                                        .setDataCategory(HealthDataCategory.WELLNESS)
                                        .setRecordClass(AlcoholConsumptionRecord.class)
                                        .setRecordInternalClass(
                                                AlcoholConsumptionRecordInternal.class)
                                        .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.ALCOHOL_CONSUMPTION,
                                                READ_ALCOHOL_CONSUMPTION,
                                                WRITE_ALCOHOL_CONSUMPTION)
                                        .build()
                                : null,
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BASAL_BODY_TEMPERATURE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(BasalBodyTemperatureRecord.class)
                                .setRecordInternalClass(BasalBodyTemperatureRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.SENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BASAL_BODY_TEMPERATURE,
                                        READ_BASAL_BODY_TEMPERATURE,
                                        WRITE_BASAL_BODY_TEMPERATURE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BASAL_METABOLIC_RATE)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(BasalMetabolicRateRecord.class)
                                .setRecordInternalClass(BasalMetabolicRateRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BASAL_METABOLIC_RATE,
                                        READ_BASAL_METABOLIC_RATE,
                                        WRITE_BASAL_METABOLIC_RATE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BLOOD_GLUCOSE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(BloodGlucoseRecord.class)
                                .setRecordInternalClass(BloodGlucoseRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.SENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BLOOD_GLUCOSE,
                                        READ_BLOOD_GLUCOSE,
                                        WRITE_BLOOD_GLUCOSE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BLOOD_PRESSURE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(BloodPressureRecord.class)
                                .setRecordInternalClass(BloodPressureRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BLOOD_PRESSURE,
                                        READ_BLOOD_PRESSURE,
                                        WRITE_BLOOD_PRESSURE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BODY_FAT)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(BodyFatRecord.class)
                                .setRecordInternalClass(BodyFatRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BODY_FAT,
                                        READ_BODY_FAT,
                                        WRITE_BODY_FAT)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BODY_TEMPERATURE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(BodyTemperatureRecord.class)
                                .setRecordInternalClass(BodyTemperatureRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BODY_TEMPERATURE,
                                        READ_BODY_TEMPERATURE,
                                        WRITE_BODY_TEMPERATURE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BODY_WATER_MASS)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(BodyWaterMassRecord.class)
                                .setRecordInternalClass(BodyWaterMassRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BODY_WATER_MASS,
                                        READ_BODY_WATER_MASS,
                                        WRITE_BODY_WATER_MASS)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_BONE_MASS)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(BoneMassRecord.class)
                                .setRecordInternalClass(BoneMassRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.BONE_MASS,
                                        READ_BONE_MASS,
                                        WRITE_BONE_MASS)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_CERVICAL_MUCUS)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(CervicalMucusRecord.class)
                                .setRecordInternalClass(CervicalMucusRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.CERVICAL_MUCUS,
                                        READ_CERVICAL_MUCUS,
                                        WRITE_CERVICAL_MUCUS)
                                .build(),
                        AconfigFlagHelper.isCyclePhasesEnabled()
                                ? DataTypeDescriptor.builder()
                                        .setRecordTypeIdentifier(RECORD_TYPE_MENSTRUAL_CYCLE_PHASE)
                                        .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                        .setRecordClass(MenstrualCyclePhaseRecord.class)
                                        .setRecordInternalClass(
                                                MenstrualCyclePhaseRecordInternal.class)
                                        .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.MENSTRUAL_CYCLE_PHASE,
                                                READ_MENSTRUAL_CYCLE_PHASE,
                                                WRITE_MENSTRUAL_CYCLE_PHASE)
                                        .build()
                                : null,
                        Flags.smoking() && AconfigFlagHelper.isNicotineIntakeEnabled()
                                ? DataTypeDescriptor.builder()
                                        .setRecordTypeIdentifier(RECORD_TYPE_NICOTINE_INTAKE)
                                        .setDataCategory(HealthDataCategory.WELLNESS)
                                        .setRecordClass(NicotineIntakeRecord.class)
                                        .setRecordInternalClass(NicotineIntakeRecordInternal.class)
                                        .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.NICOTINE_INTAKE,
                                                READ_NICOTINE_INTAKE,
                                                WRITE_NICOTINE_INTAKE)
                                        .build()
                                : null,
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_CYCLING_PEDALING_CADENCE)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(CyclingPedalingCadenceRecord.class)
                                .setRecordInternalClass(CyclingPedalingCadenceRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.EXERCISE,
                                        READ_EXERCISE,
                                        WRITE_EXERCISE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_DISTANCE)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(DistanceRecord.class)
                                .setRecordInternalClass(DistanceRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.DISTANCE,
                                        READ_DISTANCE,
                                        WRITE_DISTANCE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_ELEVATION_GAINED)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(ElevationGainedRecord.class)
                                .setRecordInternalClass(ElevationGainedRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.ELEVATION_GAINED,
                                        READ_ELEVATION_GAINED,
                                        WRITE_ELEVATION_GAINED)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_EXERCISE_SESSION)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(ExerciseSessionRecord.class)
                                .setRecordInternalClass(ExerciseSessionRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.EXERCISE,
                                        READ_EXERCISE,
                                        WRITE_EXERCISE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_FLOORS_CLIMBED)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(FloorsClimbedRecord.class)
                                .setRecordInternalClass(FloorsClimbedRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.FLOORS_CLIMBED,
                                        READ_FLOORS_CLIMBED,
                                        WRITE_FLOORS_CLIMBED)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_HEART_RATE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(HeartRateRecord.class)
                                .setRecordInternalClass(HeartRateRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.HEART_RATE,
                                        READ_HEART_RATE,
                                        WRITE_HEART_RATE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_HEART_RATE_VARIABILITY_RMSSD)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(HeartRateVariabilityRmssdRecord.class)
                                .setRecordInternalClass(
                                        HeartRateVariabilityRmssdRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.HEART_RATE_VARIABILITY,
                                        READ_HEART_RATE_VARIABILITY,
                                        WRITE_HEART_RATE_VARIABILITY)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_HEIGHT)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(HeightRecord.class)
                                .setRecordInternalClass(HeightRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.HEIGHT, READ_HEIGHT, WRITE_HEIGHT)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_HYDRATION)
                                .setDataCategory(HealthDataCategory.NUTRITION)
                                .setRecordClass(HydrationRecord.class)
                                .setRecordInternalClass(HydrationRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.HYDRATION,
                                        READ_HYDRATION,
                                        WRITE_HYDRATION)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_INTERMENSTRUAL_BLEEDING)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(IntermenstrualBleedingRecord.class)
                                .setRecordInternalClass(IntermenstrualBleedingRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.INTERMENSTRUAL_BLEEDING,
                                        READ_INTERMENSTRUAL_BLEEDING,
                                        WRITE_INTERMENSTRUAL_BLEEDING)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_LEAN_BODY_MASS)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(LeanBodyMassRecord.class)
                                .setRecordInternalClass(LeanBodyMassRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.LEAN_BODY_MASS,
                                        READ_LEAN_BODY_MASS,
                                        WRITE_LEAN_BODY_MASS)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_MENSTRUATION_FLOW)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(MenstruationFlowRecord.class)
                                .setRecordInternalClass(MenstruationFlowRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.MENSTRUATION,
                                        READ_MENSTRUATION,
                                        WRITE_MENSTRUATION)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_MENSTRUATION_PERIOD)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(MenstruationPeriodRecord.class)
                                .setRecordInternalClass(MenstruationPeriodRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.MENSTRUATION,
                                        READ_MENSTRUATION,
                                        WRITE_MENSTRUATION)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_MINDFULNESS_SESSION)
                                .setDataCategory(HealthDataCategory.WELLNESS)
                                .setRecordClass(MindfulnessSessionRecord.class)
                                .setRecordInternalClass(MindfulnessSessionRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.MINDFULNESS,
                                        READ_MINDFULNESS,
                                        WRITE_MINDFULNESS)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_NUTRITION)
                                .setDataCategory(HealthDataCategory.NUTRITION)
                                .setRecordClass(NutritionRecord.class)
                                .setRecordInternalClass(NutritionRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.NUTRITION,
                                        READ_NUTRITION,
                                        WRITE_NUTRITION)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_OVULATION_TEST)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(OvulationTestRecord.class)
                                .setRecordInternalClass(OvulationTestRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.SENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.OVULATION_TEST,
                                        READ_OVULATION_TEST,
                                        WRITE_OVULATION_TEST)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_OXYGEN_SATURATION)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(OxygenSaturationRecord.class)
                                .setRecordInternalClass(OxygenSaturationRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.OXYGEN_SATURATION,
                                        READ_OXYGEN_SATURATION,
                                        WRITE_OXYGEN_SATURATION)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_PLANNED_EXERCISE_SESSION)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(PlannedExerciseSessionRecord.class)
                                .setRecordInternalClass(PlannedExerciseSessionRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.PLANNED_EXERCISE,
                                        READ_PLANNED_EXERCISE,
                                        WRITE_PLANNED_EXERCISE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_POWER)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(PowerRecord.class)
                                .setRecordInternalClass(PowerRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.POWER, READ_POWER, WRITE_POWER)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_RESPIRATORY_RATE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(RespiratoryRateRecord.class)
                                .setRecordInternalClass(RespiratoryRateRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.RESPIRATORY_RATE,
                                        READ_RESPIRATORY_RATE,
                                        WRITE_RESPIRATORY_RATE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_RESTING_HEART_RATE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(RestingHeartRateRecord.class)
                                .setRecordInternalClass(RestingHeartRateRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.RESTING_HEART_RATE,
                                        READ_RESTING_HEART_RATE,
                                        WRITE_RESTING_HEART_RATE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_SEXUAL_ACTIVITY)
                                .setDataCategory(HealthDataCategory.CYCLE_TRACKING)
                                .setRecordClass(SexualActivityRecord.class)
                                .setRecordInternalClass(SexualActivityRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.SEXUAL_ACTIVITY,
                                        READ_SEXUAL_ACTIVITY,
                                        WRITE_SEXUAL_ACTIVITY)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_SKIN_TEMPERATURE)
                                .setDataCategory(HealthDataCategory.VITALS)
                                .setRecordClass(SkinTemperatureRecord.class)
                                .setRecordInternalClass(SkinTemperatureRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.SKIN_TEMPERATURE,
                                        READ_SKIN_TEMPERATURE,
                                        WRITE_SKIN_TEMPERATURE)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_SLEEP_SESSION)
                                .setDataCategory(HealthDataCategory.SLEEP)
                                .setRecordClass(SleepSessionRecord.class)
                                .setRecordInternalClass(SleepSessionRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.SLEEP, READ_SLEEP, WRITE_SLEEP)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_SPEED)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(SpeedRecord.class)
                                .setRecordInternalClass(SpeedRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.SPEED, READ_SPEED, WRITE_SPEED)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_STEPS)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(StepsRecord.class)
                                .setRecordInternalClass(StepsRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.STEPS, READ_STEPS, WRITE_STEPS)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_STEPS_CADENCE)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(StepsCadenceRecord.class)
                                .setRecordInternalClass(StepsCadenceRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.STEPS, READ_STEPS, WRITE_STEPS)
                                .build(),
                        Flags.symptoms() && AconfigFlagHelper.isSymptomsEnabled()
                                ? DataTypeDescriptor.builder()
                                        .setRecordTypeIdentifier(RECORD_TYPE_SYMPTOM)
                                        .setDataCategory(HealthDataCategory.SYMPTOMS)
                                        .setRecordClass(SymptomRecord.class)
                                        .setRecordInternalClass(SymptomRecordInternal.class)
                                        .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_COUGH,
                                                READ_SYMPTOM_COUGH,
                                                WRITE_SYMPTOM_COUGH)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_ABDOMINAL_PAIN,
                                                READ_SYMPTOM_ABDOMINAL_PAIN,
                                                WRITE_SYMPTOM_ABDOMINAL_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_ACNE,
                                                READ_SYMPTOM_ACNE,
                                                WRITE_SYMPTOM_ACNE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BACK_PAIN,
                                                READ_SYMPTOM_BACK_PAIN,
                                                WRITE_SYMPTOM_BACK_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BLOATING,
                                                READ_SYMPTOM_BLOATING,
                                                WRITE_SYMPTOM_BLOATING)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BRAIN_FOG,
                                                READ_SYMPTOM_BRAIN_FOG,
                                                WRITE_SYMPTOM_BRAIN_FOG)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BREAST_TENDERNESS,
                                                READ_SYMPTOM_BREAST_TENDERNESS,
                                                WRITE_SYMPTOM_BREAST_TENDERNESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BRITTLE_NAILS,
                                                READ_SYMPTOM_BRITTLE_NAILS,
                                                WRITE_SYMPTOM_BRITTLE_NAILS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_BURNING_MOUTH,
                                                READ_SYMPTOM_BURNING_MOUTH,
                                                WRITE_SYMPTOM_BURNING_MOUTH)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CHEST_PAIN,
                                                READ_SYMPTOM_CHEST_PAIN,
                                                WRITE_SYMPTOM_CHEST_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CHEST_TIGHTNESS,
                                                READ_SYMPTOM_CHEST_TIGHTNESS,
                                                WRITE_SYMPTOM_CHEST_TIGHTNESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CHILLS,
                                                READ_SYMPTOM_CHILLS,
                                                WRITE_SYMPTOM_CHILLS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CONSTIPATION,
                                                READ_SYMPTOM_CONSTIPATION,
                                                WRITE_SYMPTOM_CONSTIPATION)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CRAMPS,
                                                READ_SYMPTOM_CRAMPS,
                                                WRITE_SYMPTOM_CRAMPS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_CRAVINGS,
                                                READ_SYMPTOM_CRAVINGS,
                                                WRITE_SYMPTOM_CRAVINGS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_DEHYDRATION,
                                                READ_SYMPTOM_DEHYDRATION,
                                                WRITE_SYMPTOM_DEHYDRATION)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_DIARRHEA,
                                                READ_SYMPTOM_DIARRHEA,
                                                WRITE_SYMPTOM_DIARRHEA)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_DIFFICULTY_SWALLOWING,
                                                READ_SYMPTOM_DIFFICULTY_SWALLOWING,
                                                WRITE_SYMPTOM_DIFFICULTY_SWALLOWING)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_DIZZINESS,
                                                READ_SYMPTOM_DIZZINESS,
                                                WRITE_SYMPTOM_DIZZINESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_DRY_SKIN,
                                                READ_SYMPTOM_DRY_SKIN,
                                                WRITE_SYMPTOM_DRY_SKIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_EARACHES,
                                                READ_SYMPTOM_EARACHES,
                                                WRITE_SYMPTOM_EARACHES)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_FATIGUE,
                                                READ_SYMPTOM_FATIGUE,
                                                WRITE_SYMPTOM_FATIGUE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_FEVER,
                                                READ_SYMPTOM_FEVER,
                                                WRITE_SYMPTOM_FEVER)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_GENERALIZED_BODY_ACHE,
                                                READ_SYMPTOM_GENERALIZED_BODY_ACHE,
                                                WRITE_SYMPTOM_GENERALIZED_BODY_ACHE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_HAIR_LOSS,
                                                READ_SYMPTOM_HAIR_LOSS,
                                                WRITE_SYMPTOM_HAIR_LOSS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_HEADACHE,
                                                READ_SYMPTOM_HEADACHE,
                                                WRITE_SYMPTOM_HEADACHE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_HEARTBURN,
                                                READ_SYMPTOM_HEARTBURN,
                                                WRITE_SYMPTOM_HEARTBURN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_HEART_PALPITATIONS,
                                                READ_SYMPTOM_HEART_PALPITATIONS,
                                                WRITE_SYMPTOM_HEART_PALPITATIONS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_HOT_FLASHES,
                                                READ_SYMPTOM_HOT_FLASHES,
                                                WRITE_SYMPTOM_HOT_FLASHES)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_INSOMNIA,
                                                READ_SYMPTOM_INSOMNIA,
                                                WRITE_SYMPTOM_INSOMNIA)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_JOINT_PAIN,
                                                READ_SYMPTOM_JOINT_PAIN,
                                                WRITE_SYMPTOM_JOINT_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_JOINT_STIFFNESS,
                                                READ_SYMPTOM_JOINT_STIFFNESS,
                                                WRITE_SYMPTOM_JOINT_STIFFNESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_LOSS_OF_APPETITE,
                                                READ_SYMPTOM_LOSS_OF_APPETITE,
                                                WRITE_SYMPTOM_LOSS_OF_APPETITE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                                                READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                                                WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_LOWER_BACK_PAIN,
                                                READ_SYMPTOM_LOWER_BACK_PAIN,
                                                WRITE_SYMPTOM_LOWER_BACK_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_MEMORY_LAPSE,
                                                READ_SYMPTOM_MEMORY_LAPSE,
                                                WRITE_SYMPTOM_MEMORY_LAPSE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_MOOD_CHANGE,
                                                READ_SYMPTOM_MOOD_CHANGE,
                                                WRITE_SYMPTOM_MOOD_CHANGE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_MUSCLE_PAIN,
                                                READ_SYMPTOM_MUSCLE_PAIN,
                                                WRITE_SYMPTOM_MUSCLE_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_NAUSEA,
                                                READ_SYMPTOM_NAUSEA,
                                                WRITE_SYMPTOM_NAUSEA)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_NIGHT_SWEATS,
                                                READ_SYMPTOM_NIGHT_SWEATS,
                                                WRITE_SYMPTOM_NIGHT_SWEATS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_PELVIC_PAIN,
                                                READ_SYMPTOM_PELVIC_PAIN,
                                                WRITE_SYMPTOM_PELVIC_PAIN)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                                                READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                                                WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                                                READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                                                WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_RUNNY_NOSE,
                                                READ_SYMPTOM_RUNNY_NOSE,
                                                WRITE_SYMPTOM_RUNNY_NOSE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_SHORTNESS_OF_BREATH,
                                                READ_SYMPTOM_SHORTNESS_OF_BREATH,
                                                WRITE_SYMPTOM_SHORTNESS_OF_BREATH)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SKIPPED_HEARTBEAT,
                                                READ_SYMPTOM_SKIPPED_HEARTBEAT,
                                                WRITE_SYMPTOM_SKIPPED_HEARTBEAT)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SLEEPINESS,
                                                READ_SYMPTOM_SLEEPINESS,
                                                WRITE_SYMPTOM_SLEEPINESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SLEEP_CHANGES,
                                                READ_SYMPTOM_SLEEP_CHANGES,
                                                WRITE_SYMPTOM_SLEEP_CHANGES)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SNEEZING,
                                                READ_SYMPTOM_SNEEZING,
                                                WRITE_SYMPTOM_SNEEZING)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SNORE,
                                                READ_SYMPTOM_SNORE,
                                                WRITE_SYMPTOM_SNORE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_SORE_THROAT,
                                                READ_SYMPTOM_SORE_THROAT,
                                                WRITE_SYMPTOM_SORE_THROAT)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_STOMACH_ACHE,
                                                READ_SYMPTOM_STOMACH_ACHE,
                                                WRITE_SYMPTOM_STOMACH_ACHE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_STUFFY_NOSE,
                                                READ_SYMPTOM_STUFFY_NOSE,
                                                WRITE_SYMPTOM_STUFFY_NOSE)
                                        .addPermissionCategory(
                                                HealthPermissionCategory
                                                        .SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                                                READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                                                WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_VAGINAL_DRYNESS,
                                                READ_SYMPTOM_VAGINAL_DRYNESS,
                                                WRITE_SYMPTOM_VAGINAL_DRYNESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_VAGINAL_ITCHINESS,
                                                READ_SYMPTOM_VAGINAL_ITCHINESS,
                                                WRITE_SYMPTOM_VAGINAL_ITCHINESS)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_VOMITING,
                                                READ_SYMPTOM_VOMITING,
                                                WRITE_SYMPTOM_VOMITING)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_WATER_RETENTION,
                                                READ_SYMPTOM_WATER_RETENTION,
                                                WRITE_SYMPTOM_WATER_RETENTION)
                                        .addPermissionCategory(
                                                HealthPermissionCategory.SYMPTOM_WHEEZING,
                                                READ_SYMPTOM_WHEEZING,
                                                WRITE_SYMPTOM_WHEEZING)
                                        .build()
                                : null,
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_TOTAL_CALORIES_BURNED)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(TotalCaloriesBurnedRecord.class)
                                .setRecordInternalClass(TotalCaloriesBurnedRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.TOTAL_CALORIES_BURNED,
                                        READ_TOTAL_CALORIES_BURNED,
                                        WRITE_TOTAL_CALORIES_BURNED)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_VO2_MAX)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(Vo2MaxRecord.class)
                                .setRecordInternalClass(Vo2MaxRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.VO2_MAX,
                                        READ_VO2_MAX,
                                        WRITE_VO2_MAX)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_WEIGHT)
                                .setDataCategory(HealthDataCategory.BODY_MEASUREMENTS)
                                .setRecordClass(WeightRecord.class)
                                .setRecordInternalClass(WeightRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.WEIGHT, READ_WEIGHT, WRITE_WEIGHT)
                                .build(),
                        DataTypeDescriptor.builder()
                                .setRecordTypeIdentifier(RECORD_TYPE_WHEELCHAIR_PUSHES)
                                .setDataCategory(HealthDataCategory.ACTIVITY)
                                .setRecordClass(WheelchairPushesRecord.class)
                                .setRecordInternalClass(WheelchairPushesRecordInternal.class)
                                .setRecordTypeSensitivity(RecordTypeSensitivity.INSENSITIVE)
                                .addPermissionCategory(
                                        HealthPermissionCategory.WHEELCHAIR_PUSHES,
                                        READ_WHEELCHAIR_PUSHES,
                                        WRITE_WHEELCHAIR_PUSHES)
                                .build())
                .filter(Objects::nonNull)
                .toList();
    }
}
