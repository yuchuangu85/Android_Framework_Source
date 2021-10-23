/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Handles the attachment of {@link Coordinator}s to the {@link NotifPipeline} so that the
 * Coordinators can register their respective callbacks.
 */
@SysUISingleton
public class NotifCoordinators implements Dumpable {
    private static final String TAG = "NotifCoordinators";
    private final List<Coordinator> mCoordinators = new ArrayList<>();
    private final List<NotifSectioner> mOrderedSections = new ArrayList<>();

    /**
     * Creates all the coordinators.
     */
    @Inject
    public NotifCoordinators(
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            HideNotifsForOtherUsersCoordinator hideNotifsForOtherUsersCoordinator,
            KeyguardCoordinator keyguardCoordinator,
            RankingCoordinator rankingCoordinator,
            AppOpsCoordinator appOpsCoordinator,
            DeviceProvisionedCoordinator deviceProvisionedCoordinator,
            BubbleCoordinator bubbleCoordinator,
            HeadsUpCoordinator headsUpCoordinator,
            ConversationCoordinator conversationCoordinator,
            PreparationCoordinator preparationCoordinator,
            MediaCoordinator mediaCoordinator,
            SmartspaceDedupingCoordinator smartspaceDedupingCoordinator,
            VisualStabilityCoordinator visualStabilityCoordinator) {
        dumpManager.registerDumpable(TAG, this);

        mCoordinators.add(new HideLocallyDismissedNotifsCoordinator());
        mCoordinators.add(hideNotifsForOtherUsersCoordinator);
        mCoordinators.add(keyguardCoordinator);
        mCoordinators.add(rankingCoordinator);
        mCoordinators.add(appOpsCoordinator);
        mCoordinators.add(deviceProvisionedCoordinator);
        mCoordinators.add(bubbleCoordinator);
        mCoordinators.add(conversationCoordinator);
        mCoordinators.add(mediaCoordinator);
        mCoordinators.add(visualStabilityCoordinator);

        if (featureFlags.isSmartspaceDedupingEnabled()) {
            mCoordinators.add(smartspaceDedupingCoordinator);
        }

        if (featureFlags.isNewNotifPipelineRenderingEnabled()) {
            mCoordinators.add(headsUpCoordinator);
            mCoordinators.add(preparationCoordinator);
        }

        // Manually add Ordered Sections
        // HeadsUp > FGS > People > Alerting > Silent > Unknown/Default
        if (featureFlags.isNewNotifPipelineRenderingEnabled()) {
            mOrderedSections.add(headsUpCoordinator.getSectioner()); // HeadsUp
        }
        mOrderedSections.add(appOpsCoordinator.getSectioner()); // ForegroundService
        mOrderedSections.add(conversationCoordinator.getSectioner()); // People
        mOrderedSections.add(rankingCoordinator.getAlertingSectioner()); // Alerting
        mOrderedSections.add(rankingCoordinator.getSilentSectioner()); // Silent
    }

    /**
     * Sends the pipeline to each coordinator when the pipeline is ready to accept
     * {@link Pluggable}s, {@link NotifCollectionListener}s and {@link NotifLifetimeExtender}s.
     */
    public void attach(NotifPipeline pipeline) {
        for (Coordinator c : mCoordinators) {
            c.attach(pipeline);
        }

        pipeline.setSections(mOrderedSections);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println();
        pw.println(TAG + ":");
        for (Coordinator c : mCoordinators) {
            pw.println("\t" + c.getClass());
        }

        for (NotifSectioner s : mOrderedSections) {
            pw.println("\t" + s.getName());
        }
    }
}
