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

package android.os.profiling.anomaly;

import static android.Manifest.permission.CONFIGURE_ANOMALY_DETECTOR;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.profiling.anomaly.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manager used to interact with the system anomaly detector service.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
@SystemApi
@SystemService(Context.ANOMALY_DETECTOR_SERVICE)
public final class AnomalyDetectorManager {
    private final IAnomalyDetectorService mService;

    /** @hide */
    public AnomalyDetectorManager(Context context, IAnomalyDetectorService service) {
        mService = service;
    }

    /**
     * Sets the {@link Rule} objects used for anomaly detection.
     *
     * <p>The provided {@code rules} replaces any existing rules. The system uses these rules to
     * detect applications that violate the specified {@link Rule#getRuleCondition()}s.
     *
     * <p><b>Usage Notes:</b>
     *
     * <ul>
     *   <li>To update the rules, call this method again with the new set.
     *   <li>Passing an empty set disables anomaly detection.
     *   <li><b>Restriction:</b> Only one privileged application per device is permitted to call
     *       this API and set anomaly detection rules.
     * </ul>
     *
     * @param rules A Set of {@link Rule} objects to be enforced.
     * @hide
     */
    @RequiresApi(37)
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    @RequiresPermission(CONFIGURE_ANOMALY_DETECTOR)
    public void setAnomalyDetectorRules(@NonNull Set<Rule> rules) {
        Objects.requireNonNull(rules, "Rules can not be null");

        try {
            mService.setRules(convertRulesToRuleParcels(rules));
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    @RequiresApi(37)
    private static List<RuleParcel> convertRulesToRuleParcels(Set<Rule> rules) {
        ArrayList<RuleParcel> ruleParcels = new ArrayList<>();

        rules.forEach(
                rule -> {
                    RuleParcel ruleParcel = new RuleParcel();
                    ruleParcel.name = rule.getName();
                    ruleParcel.anomalyActions = convertListToIntArray(rule.getAnomalyActions());
                    ruleParcel.conditionType = rule.getConditionType();
                    ruleParcel.ruleCondition = rule.getRuleCondition();
                    ruleParcels.add(ruleParcel);
                });
        return ruleParcels;
    }

    private static int[] convertListToIntArray(List<Integer> anomalyAction) {
        int anomalyActionSize = anomalyAction.size();
        int[] anomalyActionArray = new int[anomalyActionSize];

        for (int i = 0; i < anomalyActionSize; i++) {
            anomalyActionArray[i] = anomalyAction.get(i);
        }

        return anomalyActionArray;
    }
}
