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
package android.health.connect.internal.datatypes.utils;

import static android.health.connect.datatypes.SymptomRecord.SymptomType;

import android.health.connect.HealthPermissionCategory;
import android.health.connect.HealthPermissions;
import android.health.connect.datatypes.SymptomRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A helper class to map symptom types to their corresponding permissions and categories.
 *
 * @hide
 */
public final class SymptomTypePermissionMapper {
    private record SymptomMapping(
            int symptomType,
            String readPermission,
            String writePermission,
            int permissionCategory) {}

    private static final Set<SymptomMapping> SYMPTOM_MAPPINGS = new HashSet<>();
    private static final Map<Integer, String> TYPE_TO_READ_PERMISSION = new HashMap<>();
    private static final Map<Integer, String> TYPE_TO_WRITE_PERMISSION = new HashMap<>();
    private static final Map<Integer, Integer> TYPE_TO_CATEGORY = new HashMap<>();
    private static final Set<Integer> SYMPTOM_CATEGORIES = new HashSet<>();

    static {
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_ABDOMINAL_PAIN,
                        HealthPermissions.READ_SYMPTOM_ABDOMINAL_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_ABDOMINAL_PAIN,
                        HealthPermissionCategory.SYMPTOM_ABDOMINAL_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_ACNE,
                        HealthPermissions.READ_SYMPTOM_ACNE,
                        HealthPermissions.WRITE_SYMPTOM_ACNE,
                        HealthPermissionCategory.SYMPTOM_ACNE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BACK_PAIN,
                        HealthPermissions.READ_SYMPTOM_BACK_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_BACK_PAIN,
                        HealthPermissionCategory.SYMPTOM_BACK_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BLOATING,
                        HealthPermissions.READ_SYMPTOM_BLOATING,
                        HealthPermissions.WRITE_SYMPTOM_BLOATING,
                        HealthPermissionCategory.SYMPTOM_BLOATING));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BRAIN_FOG,
                        HealthPermissions.READ_SYMPTOM_BRAIN_FOG,
                        HealthPermissions.WRITE_SYMPTOM_BRAIN_FOG,
                        HealthPermissionCategory.SYMPTOM_BRAIN_FOG));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BREAST_TENDERNESS,
                        HealthPermissions.READ_SYMPTOM_BREAST_TENDERNESS,
                        HealthPermissions.WRITE_SYMPTOM_BREAST_TENDERNESS,
                        HealthPermissionCategory.SYMPTOM_BREAST_TENDERNESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BRITTLE_NAILS,
                        HealthPermissions.READ_SYMPTOM_BRITTLE_NAILS,
                        HealthPermissions.WRITE_SYMPTOM_BRITTLE_NAILS,
                        HealthPermissionCategory.SYMPTOM_BRITTLE_NAILS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_BURNING_MOUTH,
                        HealthPermissions.READ_SYMPTOM_BURNING_MOUTH,
                        HealthPermissions.WRITE_SYMPTOM_BURNING_MOUTH,
                        HealthPermissionCategory.SYMPTOM_BURNING_MOUTH));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CHEST_PAIN,
                        HealthPermissions.READ_SYMPTOM_CHEST_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_CHEST_PAIN,
                        HealthPermissionCategory.SYMPTOM_CHEST_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CHEST_TIGHTNESS,
                        HealthPermissions.READ_SYMPTOM_CHEST_TIGHTNESS,
                        HealthPermissions.WRITE_SYMPTOM_CHEST_TIGHTNESS,
                        HealthPermissionCategory.SYMPTOM_CHEST_TIGHTNESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CHILLS,
                        HealthPermissions.READ_SYMPTOM_CHILLS,
                        HealthPermissions.WRITE_SYMPTOM_CHILLS,
                        HealthPermissionCategory.SYMPTOM_CHILLS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CONSTIPATION,
                        HealthPermissions.READ_SYMPTOM_CONSTIPATION,
                        HealthPermissions.WRITE_SYMPTOM_CONSTIPATION,
                        HealthPermissionCategory.SYMPTOM_CONSTIPATION));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_COUGH,
                        HealthPermissions.READ_SYMPTOM_COUGH,
                        HealthPermissions.WRITE_SYMPTOM_COUGH,
                        HealthPermissionCategory.SYMPTOM_COUGH));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CRAMPS,
                        HealthPermissions.READ_SYMPTOM_CRAMPS,
                        HealthPermissions.WRITE_SYMPTOM_CRAMPS,
                        HealthPermissionCategory.SYMPTOM_CRAMPS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_CRAVINGS,
                        HealthPermissions.READ_SYMPTOM_CRAVINGS,
                        HealthPermissions.WRITE_SYMPTOM_CRAVINGS,
                        HealthPermissionCategory.SYMPTOM_CRAVINGS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_DEHYDRATION,
                        HealthPermissions.READ_SYMPTOM_DEHYDRATION,
                        HealthPermissions.WRITE_SYMPTOM_DEHYDRATION,
                        HealthPermissionCategory.SYMPTOM_DEHYDRATION));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_DIARRHEA,
                        HealthPermissions.READ_SYMPTOM_DIARRHEA,
                        HealthPermissions.WRITE_SYMPTOM_DIARRHEA,
                        HealthPermissionCategory.SYMPTOM_DIARRHEA));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_DIFFICULTY_SWALLOWING,
                        HealthPermissions.READ_SYMPTOM_DIFFICULTY_SWALLOWING,
                        HealthPermissions.WRITE_SYMPTOM_DIFFICULTY_SWALLOWING,
                        HealthPermissionCategory.SYMPTOM_DIFFICULTY_SWALLOWING));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_DIZZINESS,
                        HealthPermissions.READ_SYMPTOM_DIZZINESS,
                        HealthPermissions.WRITE_SYMPTOM_DIZZINESS,
                        HealthPermissionCategory.SYMPTOM_DIZZINESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_DRY_SKIN,
                        HealthPermissions.READ_SYMPTOM_DRY_SKIN,
                        HealthPermissions.WRITE_SYMPTOM_DRY_SKIN,
                        HealthPermissionCategory.SYMPTOM_DRY_SKIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_EARACHES,
                        HealthPermissions.READ_SYMPTOM_EARACHES,
                        HealthPermissions.WRITE_SYMPTOM_EARACHES,
                        HealthPermissionCategory.SYMPTOM_EARACHES));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_FATIGUE,
                        HealthPermissions.READ_SYMPTOM_FATIGUE,
                        HealthPermissions.WRITE_SYMPTOM_FATIGUE,
                        HealthPermissionCategory.SYMPTOM_FATIGUE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_FEVER,
                        HealthPermissions.READ_SYMPTOM_FEVER,
                        HealthPermissions.WRITE_SYMPTOM_FEVER,
                        HealthPermissionCategory.SYMPTOM_FEVER));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_GENERALIZED_BODY_ACHE,
                        HealthPermissions.READ_SYMPTOM_GENERALIZED_BODY_ACHE,
                        HealthPermissions.WRITE_SYMPTOM_GENERALIZED_BODY_ACHE,
                        HealthPermissionCategory.SYMPTOM_GENERALIZED_BODY_ACHE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_HAIR_LOSS,
                        HealthPermissions.READ_SYMPTOM_HAIR_LOSS,
                        HealthPermissions.WRITE_SYMPTOM_HAIR_LOSS,
                        HealthPermissionCategory.SYMPTOM_HAIR_LOSS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_HEADACHE,
                        HealthPermissions.READ_SYMPTOM_HEADACHE,
                        HealthPermissions.WRITE_SYMPTOM_HEADACHE,
                        HealthPermissionCategory.SYMPTOM_HEADACHE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_HEARTBURN,
                        HealthPermissions.READ_SYMPTOM_HEARTBURN,
                        HealthPermissions.WRITE_SYMPTOM_HEARTBURN,
                        HealthPermissionCategory.SYMPTOM_HEARTBURN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_HEART_PALPITATIONS,
                        HealthPermissions.READ_SYMPTOM_HEART_PALPITATIONS,
                        HealthPermissions.WRITE_SYMPTOM_HEART_PALPITATIONS,
                        HealthPermissionCategory.SYMPTOM_HEART_PALPITATIONS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_HOT_FLASHES,
                        HealthPermissions.READ_SYMPTOM_HOT_FLASHES,
                        HealthPermissions.WRITE_SYMPTOM_HOT_FLASHES,
                        HealthPermissionCategory.SYMPTOM_HOT_FLASHES));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_INSOMNIA,
                        HealthPermissions.READ_SYMPTOM_INSOMNIA,
                        HealthPermissions.WRITE_SYMPTOM_INSOMNIA,
                        HealthPermissionCategory.SYMPTOM_INSOMNIA));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_JOINT_PAIN,
                        HealthPermissions.READ_SYMPTOM_JOINT_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_JOINT_PAIN,
                        HealthPermissionCategory.SYMPTOM_JOINT_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_JOINT_STIFFNESS,
                        HealthPermissions.READ_SYMPTOM_JOINT_STIFFNESS,
                        HealthPermissions.WRITE_SYMPTOM_JOINT_STIFFNESS,
                        HealthPermissionCategory.SYMPTOM_JOINT_STIFFNESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_LOSS_OF_APPETITE,
                        HealthPermissions.READ_SYMPTOM_LOSS_OF_APPETITE,
                        HealthPermissions.WRITE_SYMPTOM_LOSS_OF_APPETITE,
                        HealthPermissionCategory.SYMPTOM_LOSS_OF_APPETITE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_LOSS_OF_CONSCIOUSNESS,
                        HealthPermissions.READ_SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                        HealthPermissions.WRITE_SYMPTOM_LOSS_OF_CONSCIOUSNESS,
                        HealthPermissionCategory.SYMPTOM_LOSS_OF_CONSCIOUSNESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_LOWER_BACK_PAIN,
                        HealthPermissions.READ_SYMPTOM_LOWER_BACK_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_LOWER_BACK_PAIN,
                        HealthPermissionCategory.SYMPTOM_LOWER_BACK_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_MEMORY_LAPSE,
                        HealthPermissions.READ_SYMPTOM_MEMORY_LAPSE,
                        HealthPermissions.WRITE_SYMPTOM_MEMORY_LAPSE,
                        HealthPermissionCategory.SYMPTOM_MEMORY_LAPSE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_MOOD_CHANGE,
                        HealthPermissions.READ_SYMPTOM_MOOD_CHANGE,
                        HealthPermissions.WRITE_SYMPTOM_MOOD_CHANGE,
                        HealthPermissionCategory.SYMPTOM_MOOD_CHANGE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_MUSCLE_PAIN,
                        HealthPermissions.READ_SYMPTOM_MUSCLE_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_MUSCLE_PAIN,
                        HealthPermissionCategory.SYMPTOM_MUSCLE_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_NAUSEA,
                        HealthPermissions.READ_SYMPTOM_NAUSEA,
                        HealthPermissions.WRITE_SYMPTOM_NAUSEA,
                        HealthPermissionCategory.SYMPTOM_NAUSEA));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_NIGHT_SWEATS,
                        HealthPermissions.READ_SYMPTOM_NIGHT_SWEATS,
                        HealthPermissions.WRITE_SYMPTOM_NIGHT_SWEATS,
                        HealthPermissionCategory.SYMPTOM_NIGHT_SWEATS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_PELVIC_PAIN,
                        HealthPermissions.READ_SYMPTOM_PELVIC_PAIN,
                        HealthPermissions.WRITE_SYMPTOM_PELVIC_PAIN,
                        HealthPermissionCategory.SYMPTOM_PELVIC_PAIN));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                        HealthPermissions.READ_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                        HealthPermissions.WRITE_SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                        HealthPermissionCategory.SYMPTOM_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_REDUCED_CAPACITY_FOR_EXERCISE,
                        HealthPermissions.READ_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                        HealthPermissions.WRITE_SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE,
                        HealthPermissionCategory.SYMPTOM_REDUCED_CAPACITY_FOR_EXERCISE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_RUNNY_NOSE,
                        HealthPermissions.READ_SYMPTOM_RUNNY_NOSE,
                        HealthPermissions.WRITE_SYMPTOM_RUNNY_NOSE,
                        HealthPermissionCategory.SYMPTOM_RUNNY_NOSE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SHORTNESS_OF_BREATH,
                        HealthPermissions.READ_SYMPTOM_SHORTNESS_OF_BREATH,
                        HealthPermissions.WRITE_SYMPTOM_SHORTNESS_OF_BREATH,
                        HealthPermissionCategory.SYMPTOM_SHORTNESS_OF_BREATH));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SKIPPED_HEARTBEAT,
                        HealthPermissions.READ_SYMPTOM_SKIPPED_HEARTBEAT,
                        HealthPermissions.WRITE_SYMPTOM_SKIPPED_HEARTBEAT,
                        HealthPermissionCategory.SYMPTOM_SKIPPED_HEARTBEAT));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SLEEP_CHANGES,
                        HealthPermissions.READ_SYMPTOM_SLEEP_CHANGES,
                        HealthPermissions.WRITE_SYMPTOM_SLEEP_CHANGES,
                        HealthPermissionCategory.SYMPTOM_SLEEP_CHANGES));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SLEEPINESS,
                        HealthPermissions.READ_SYMPTOM_SLEEPINESS,
                        HealthPermissions.WRITE_SYMPTOM_SLEEPINESS,
                        HealthPermissionCategory.SYMPTOM_SLEEPINESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SNEEZING,
                        HealthPermissions.READ_SYMPTOM_SNEEZING,
                        HealthPermissions.WRITE_SYMPTOM_SNEEZING,
                        HealthPermissionCategory.SYMPTOM_SNEEZING));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SNORE,
                        HealthPermissions.READ_SYMPTOM_SNORE,
                        HealthPermissions.WRITE_SYMPTOM_SNORE,
                        HealthPermissionCategory.SYMPTOM_SNORE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_SORE_THROAT,
                        HealthPermissions.READ_SYMPTOM_SORE_THROAT,
                        HealthPermissions.WRITE_SYMPTOM_SORE_THROAT,
                        HealthPermissionCategory.SYMPTOM_SORE_THROAT));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_STOMACH_ACHE,
                        HealthPermissions.READ_SYMPTOM_STOMACH_ACHE,
                        HealthPermissions.WRITE_SYMPTOM_STOMACH_ACHE,
                        HealthPermissionCategory.SYMPTOM_STOMACH_ACHE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_STUFFY_NOSE,
                        HealthPermissions.READ_SYMPTOM_STUFFY_NOSE,
                        HealthPermissions.WRITE_SYMPTOM_STUFFY_NOSE,
                        HealthPermissionCategory.SYMPTOM_STUFFY_NOSE));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_UNEXPLAINED_WEIGHT_CHANGES,
                        HealthPermissions.READ_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                        HealthPermissions.WRITE_SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES,
                        HealthPermissionCategory.SYMPTOM_UNEXPLAINED_WEIGHT_CHANGES));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_VAGINAL_DRYNESS,
                        HealthPermissions.READ_SYMPTOM_VAGINAL_DRYNESS,
                        HealthPermissions.WRITE_SYMPTOM_VAGINAL_DRYNESS,
                        HealthPermissionCategory.SYMPTOM_VAGINAL_DRYNESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_VAGINAL_ITCHINESS,
                        HealthPermissions.READ_SYMPTOM_VAGINAL_ITCHINESS,
                        HealthPermissions.WRITE_SYMPTOM_VAGINAL_ITCHINESS,
                        HealthPermissionCategory.SYMPTOM_VAGINAL_ITCHINESS));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_VOMITING,
                        HealthPermissions.READ_SYMPTOM_VOMITING,
                        HealthPermissions.WRITE_SYMPTOM_VOMITING,
                        HealthPermissionCategory.SYMPTOM_VOMITING));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_WATER_RETENTION,
                        HealthPermissions.READ_SYMPTOM_WATER_RETENTION,
                        HealthPermissions.WRITE_SYMPTOM_WATER_RETENTION,
                        HealthPermissionCategory.SYMPTOM_WATER_RETENTION));
        SYMPTOM_MAPPINGS.add(
                new SymptomMapping(
                        SymptomRecord.SYMPTOM_TYPE_WHEEZING,
                        HealthPermissions.READ_SYMPTOM_WHEEZING,
                        HealthPermissions.WRITE_SYMPTOM_WHEEZING,
                        HealthPermissionCategory.SYMPTOM_WHEEZING));

        for (SymptomMapping mapping : SYMPTOM_MAPPINGS) {
            TYPE_TO_READ_PERMISSION.put(mapping.symptomType, mapping.readPermission);
            TYPE_TO_WRITE_PERMISSION.put(mapping.symptomType, mapping.writePermission);
            TYPE_TO_CATEGORY.put(mapping.symptomType, mapping.permissionCategory);
            SYMPTOM_CATEGORIES.add(mapping.permissionCategory);
        }
    }

    /** Returns the read permission for the given symptom type. */
    public static String getReadPermission(@SymptomType int symptomType) {
        if (!TYPE_TO_READ_PERMISSION.containsKey(symptomType)) {
            throw new IllegalArgumentException("Invalid symptom type: " + symptomType);
        }
        return TYPE_TO_READ_PERMISSION.get(symptomType);
    }

    /** Returns the write permission for the given symptom type. */
    public static String getWritePermission(@SymptomType int symptomType) {
        if (!TYPE_TO_WRITE_PERMISSION.containsKey(symptomType)) {
            throw new IllegalArgumentException("Invalid symptom type: " + symptomType);
        }
        return TYPE_TO_WRITE_PERMISSION.get(symptomType);
    }

    /** Returns the set of all symptom types. */
    public static Set<Integer> getSymptomTypes() {
        return Collections.unmodifiableSet(TYPE_TO_READ_PERMISSION.keySet());
    }

    /** Checks if the given HealthPermissionCategory is a symptom category. */
    public static boolean isSymptomCategory(int healthPermissionCategory) {
        return SYMPTOM_CATEGORIES.contains(healthPermissionCategory);
    }

    private SymptomTypePermissionMapper() {}
}

