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

package android.health.connect.internal.datatypes.utils;

import static android.health.connect.datatypes.ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL;
import static android.health.connect.datatypes.ActivityIntensityRecord.DURATION_TOTAL;
import static android.health.connect.datatypes.ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL;
import static android.health.connect.datatypes.ActivityIntensityRecord.MODERATE_DURATION_TOTAL;
import static android.health.connect.datatypes.ActivityIntensityRecord.VIGOROUS_DURATION_TOTAL;
import static android.health.connect.datatypes.BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL;
import static android.health.connect.datatypes.BloodPressureRecord.DIASTOLIC_AVG;
import static android.health.connect.datatypes.BloodPressureRecord.DIASTOLIC_MAX;
import static android.health.connect.datatypes.BloodPressureRecord.DIASTOLIC_MIN;
import static android.health.connect.datatypes.BloodPressureRecord.SYSTOLIC_AVG;
import static android.health.connect.datatypes.BloodPressureRecord.SYSTOLIC_MAX;
import static android.health.connect.datatypes.BloodPressureRecord.SYSTOLIC_MIN;
import static android.health.connect.datatypes.CyclingPedalingCadenceRecord.RPM_AVG;
import static android.health.connect.datatypes.CyclingPedalingCadenceRecord.RPM_MAX;
import static android.health.connect.datatypes.CyclingPedalingCadenceRecord.RPM_MIN;
import static android.health.connect.datatypes.DistanceRecord.DISTANCE_TOTAL;
import static android.health.connect.datatypes.ElevationGainedRecord.ELEVATION_GAINED_TOTAL;
import static android.health.connect.datatypes.ExerciseSessionRecord.EXERCISE_DURATION_TOTAL;
import static android.health.connect.datatypes.FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL;
import static android.health.connect.datatypes.HeartRateRecord.BPM_AVG;
import static android.health.connect.datatypes.HeartRateRecord.BPM_MAX;
import static android.health.connect.datatypes.HeartRateRecord.BPM_MIN;
import static android.health.connect.datatypes.HeartRateRecord.HEART_MEASUREMENTS_COUNT;
import static android.health.connect.datatypes.HeightRecord.HEIGHT_AVG;
import static android.health.connect.datatypes.HeightRecord.HEIGHT_MAX;
import static android.health.connect.datatypes.HeightRecord.HEIGHT_MIN;
import static android.health.connect.datatypes.HydrationRecord.VOLUME_TOTAL;
import static android.health.connect.datatypes.MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.BIOTIN_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.CAFFEINE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.CALCIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.CHLORIDE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.CHOLESTEROL_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.CHROMIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.COPPER_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.DIETARY_FIBER_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.ENERGY_FROM_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.ENERGY_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.FOLATE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.FOLIC_ACID_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.IODINE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.IRON_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.MAGNESIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.MANGANESE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.MOLYBDENUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.MONOUNSATURATED_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.NIACIN_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.PANTOTHENIC_ACID_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.PHOSPHORUS_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.POLYUNSATURATED_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.POTASSIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.PROTEIN_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.RIBOFLAVIN_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.SATURATED_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.SELENIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.SODIUM_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.SUGAR_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.THIAMIN_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.TOTAL_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.TRANS_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.UNSATURATED_FAT_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_A_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_B12_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_B6_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_C_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_D_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_E_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.VITAMIN_K_TOTAL;
import static android.health.connect.datatypes.NutritionRecord.ZINC_TOTAL;
import static android.health.connect.datatypes.PowerRecord.POWER_AVG;
import static android.health.connect.datatypes.PowerRecord.POWER_MAX;
import static android.health.connect.datatypes.PowerRecord.POWER_MIN;
import static android.health.connect.datatypes.SkinTemperatureRecord.SKIN_TEMPERATURE_DELTA_AVG;
import static android.health.connect.datatypes.SkinTemperatureRecord.SKIN_TEMPERATURE_DELTA_MAX;
import static android.health.connect.datatypes.SkinTemperatureRecord.SKIN_TEMPERATURE_DELTA_MIN;
import static android.health.connect.datatypes.SleepSessionRecord.SLEEP_DURATION_TOTAL;
import static android.health.connect.datatypes.SpeedRecord.SPEED_AVG;
import static android.health.connect.datatypes.SpeedRecord.SPEED_MAX;
import static android.health.connect.datatypes.SpeedRecord.SPEED_MIN;
import static android.health.connect.datatypes.StepsCadenceRecord.STEPS_CADENCE_RATE_AVG;
import static android.health.connect.datatypes.StepsCadenceRecord.STEPS_CADENCE_RATE_MAX;
import static android.health.connect.datatypes.StepsCadenceRecord.STEPS_CADENCE_RATE_MIN;
import static android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL;
import static android.health.connect.datatypes.WeightRecord.WEIGHT_AVG;
import static android.health.connect.datatypes.WeightRecord.WEIGHT_MAX;
import static android.health.connect.datatypes.WeightRecord.WEIGHT_MIN;
import static android.health.connect.datatypes.WheelchairPushesRecord.WHEEL_CHAIR_PUSHES_COUNT_TOTAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.AggregationType;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.TotalCaloriesBurnedRecord;
import android.health.connect.datatypes.units.Energy;
import android.health.connect.datatypes.units.Length;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Power;
import android.health.connect.datatypes.units.Pressure;
import android.health.connect.datatypes.units.TemperatureDelta;
import android.health.connect.datatypes.units.Velocity;
import android.health.connect.datatypes.units.Volume;
import android.os.Parcel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates and maintains a map of {@link AggregationType.AggregationTypeIdentifier} to {@link
 * AggregationType} and its {@link ParcelDataReader}.
 *
 * @hide
 */
public final class AggregationTypeIdMapper {
    @Nullable private static volatile AggregationTypeIdMapper sAggregationTypeIdMapper;

    private final Map<Integer, ParcelDataReader<?>> mIdToParcelDataReader;
    private final Map<Integer, AggregationType<?>> mIdDataAggregationTypeMap;
    private final Map<AggregationType<?>, Integer> mDataAggregationTypeIdMap;

    private AggregationTypeIdMapper() {
        mIdToParcelDataReader = new HashMap<>();
        mIdDataAggregationTypeMap = new HashMap<>();
        mDataAggregationTypeIdMap = new HashMap<>();

        List<AggregationType<?>> longAggregations =
                new ArrayList<>(
                        List.of(
                                BPM_MAX,
                                BPM_MIN,
                                STEPS_COUNT_TOTAL,
                                BPM_AVG,
                                RestingHeartRateRecord.BPM_MAX,
                                RestingHeartRateRecord.BPM_MIN,
                                RestingHeartRateRecord.BPM_AVG,
                                WHEEL_CHAIR_PUSHES_COUNT_TOTAL,
                                HEART_MEASUREMENTS_COUNT,
                                SLEEP_DURATION_TOTAL,
                                EXERCISE_DURATION_TOTAL,
                                MINDFULNESS_DURATION_TOTAL));
        List<AggregationType<?>> durationAggregations = new ArrayList<>();

        durationAggregations.add(MODERATE_DURATION_TOTAL);
        durationAggregations.add(VIGOROUS_DURATION_TOTAL);
        durationAggregations.add(DURATION_TOTAL);
        longAggregations.add(INTENSITY_MINUTES_TOTAL);

        addLongIdsToAggregateResultMap(longAggregations);
        addDoubleIdsToAggregateResultMap(
                Arrays.asList(
                        FLOORS_CLIMBED_TOTAL,
                        RPM_AVG,
                        RPM_MAX,
                        RPM_MIN,
                        STEPS_CADENCE_RATE_MAX,
                        STEPS_CADENCE_RATE_AVG,
                        STEPS_CADENCE_RATE_MIN));
        addPowerIdsToAggregateResultMap(Arrays.asList(POWER_MIN, POWER_MAX, POWER_AVG));
        addTemperatureDeltaIdsToAggregateResultMap(
                Arrays.asList(
                        SKIN_TEMPERATURE_DELTA_AVG,
                        SKIN_TEMPERATURE_DELTA_MIN,
                        SKIN_TEMPERATURE_DELTA_MAX));
        addEnergyIdsToAggregateResultMap(
                Arrays.asList(
                        ACTIVE_CALORIES_TOTAL,
                        BASAL_CALORIES_TOTAL,
                        ENERGY_TOTAL,
                        ENERGY_FROM_FAT_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL));
        addVolumeIdsToAggregateResultMap(Arrays.asList(VOLUME_TOTAL));
        addLengthIdsToAggregateResultMap(
                Arrays.asList(
                        DISTANCE_TOTAL,
                        ELEVATION_GAINED_TOTAL,
                        HEIGHT_AVG,
                        HEIGHT_MAX,
                        HEIGHT_MIN));
        addMassIdsToAggregateResultMap(
                Arrays.asList(
                        BIOTIN_TOTAL,
                        CAFFEINE_TOTAL,
                        CALCIUM_TOTAL,
                        CHLORIDE_TOTAL,
                        CHOLESTEROL_TOTAL,
                        CHROMIUM_TOTAL,
                        COPPER_TOTAL,
                        DIETARY_FIBER_TOTAL,
                        FOLATE_TOTAL,
                        FOLIC_ACID_TOTAL,
                        IODINE_TOTAL,
                        IRON_TOTAL,
                        MAGNESIUM_TOTAL,
                        MANGANESE_TOTAL,
                        MOLYBDENUM_TOTAL,
                        MONOUNSATURATED_FAT_TOTAL,
                        NIACIN_TOTAL,
                        PANTOTHENIC_ACID_TOTAL,
                        PHOSPHORUS_TOTAL,
                        POLYUNSATURATED_FAT_TOTAL,
                        POTASSIUM_TOTAL,
                        PROTEIN_TOTAL,
                        RIBOFLAVIN_TOTAL,
                        SATURATED_FAT_TOTAL,
                        SELENIUM_TOTAL,
                        SODIUM_TOTAL,
                        SUGAR_TOTAL,
                        THIAMIN_TOTAL,
                        TOTAL_CARBOHYDRATE_TOTAL,
                        TOTAL_FAT_TOTAL,
                        UNSATURATED_FAT_TOTAL,
                        VITAMIN_A_TOTAL,
                        VITAMIN_B12_TOTAL,
                        VITAMIN_B6_TOTAL,
                        VITAMIN_C_TOTAL,
                        VITAMIN_D_TOTAL,
                        VITAMIN_E_TOTAL,
                        VITAMIN_K_TOTAL,
                        ZINC_TOTAL,
                        WEIGHT_AVG,
                        WEIGHT_MAX,
                        WEIGHT_MIN,
                        TRANS_FAT_TOTAL));
        addVelocityIdsToAggregateResultMap(Arrays.asList(SPEED_MAX, SPEED_AVG, SPEED_MIN));
        addPressureIdsToAggregateResultMap(
                Arrays.asList(
                        DIASTOLIC_AVG,
                        DIASTOLIC_MAX,
                        DIASTOLIC_MIN,
                        SYSTOLIC_AVG,
                        SYSTOLIC_MAX,
                        SYSTOLIC_MIN));
        addDurationIdsToAggregateResultMap(durationAggregations);
    }

    @NonNull
    public static synchronized AggregationTypeIdMapper getInstance() {
        AggregationTypeIdMapper instance = sAggregationTypeIdMapper;
        if (instance == null) {
            instance = new AggregationTypeIdMapper();
            sAggregationTypeIdMapper = instance;
        }

        return instance;
    }

    @NonNull
    public ParcelDataReader<?> getParcelDataReaderFor(
            @AggregationType.AggregationTypeIdentifier.Id int id) {
        return Objects.requireNonNull(
                mIdToParcelDataReader.get(id), "No parcel data reader for aggregation type " + id);
    }

    public AggregationType<?> getAggregationTypeFor(
            @AggregationType.AggregationTypeIdentifier.Id int id) {
        return Objects.requireNonNull(
                mIdDataAggregationTypeMap.get(id), "No aggregation type for id " + id);
    }

    @AggregationType.AggregationTypeIdentifier.Id
    public int getIdFor(AggregationType<?> aggregationType) {
        return Objects.requireNonNull(
                mDataAggregationTypeIdMap.get(aggregationType),
                "No aggregation type id for " + aggregationType);
    }

    private void addLongIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(type.getAggregationTypeIdentifier(), Parcel::readLong);
            populateIdDataAggregationType(type);
        }
    }

    private void addDoubleIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(type.getAggregationTypeIdentifier(), Parcel::readDouble);
            populateIdDataAggregationType(type);
        }
    }

    private void addEnergyIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Energy.fromCalories(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addPowerIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Power.fromWatts(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addTemperatureDeltaIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> TemperatureDelta.fromCelsius(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addPressureIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Pressure.fromMillimetersOfMercury(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addLengthIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Length.fromMeters(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addVolumeIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Volume.fromLiters(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addMassIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Mass.fromGrams(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addVelocityIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Velocity.fromMetersPerSecond(parcel.readDouble()));
            populateIdDataAggregationType(type);
        }
    }

    private void addDurationIdsToAggregateResultMap(
            @NonNull List<AggregationType<?>> aggregationTypeList) {
        for (AggregationType<?> type : aggregationTypeList) {
            mIdToParcelDataReader.put(
                    type.getAggregationTypeIdentifier(),
                    parcel -> Duration.ofMillis(parcel.readLong()));
            populateIdDataAggregationType(type);
        }
    }

    private void populateIdDataAggregationType(AggregationType<?> aggregationType) {
        mIdDataAggregationTypeMap.put(
                aggregationType.getAggregationTypeIdentifier(), aggregationType);
        mDataAggregationTypeIdMap.put(
                aggregationType, aggregationType.getAggregationTypeIdentifier());
    }

    /**
     * Helper interface to read the value of an {@link android.health.connect.AggregateResult} from
     * the given parcel.
     *
     * @param <T> the type of data read.
     */
    public interface ParcelDataReader<T> {
        /** Read an aggregate result value of type T from the parcel. */
        T readData(Parcel in);
    }
}
