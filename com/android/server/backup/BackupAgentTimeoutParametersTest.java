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
 * limitations under the License
 */

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Tests for {@link BackupAgentTimeoutParameters}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupAgentTimeoutParametersTest {
    private ContentResolver mContentResolver;
    private BackupAgentTimeoutParameters mParameters;

    /** Initialize timeout parameters and start observing changes. */
    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.application.getApplicationContext();

        mContentResolver = context.getContentResolver();
        mParameters = new BackupAgentTimeoutParameters(new Handler(), mContentResolver);
    }

    /** Stop observing changes to the setting. */
    @After
    public void tearDown() {
        mParameters.stop();
    }

    /** Tests that timeout parameters are initialized with default values on creation. */
    // TODO: Break down tests
    @Test
    public void testGetParameters_afterConstructorWithStart_returnsDefaultValues() {
        mParameters.start();

        long kvBackupAgentTimeoutMillis = mParameters.getKvBackupAgentTimeoutMillis();
        long fullBackupAgentTimeoutMillis = mParameters.getFullBackupAgentTimeoutMillis();
        long sharedBackupAgentTimeoutMillis = mParameters.getSharedBackupAgentTimeoutMillis();
        long restoreSessionTimeoutMillis = mParameters.getRestoreSessionTimeoutMillis();
        long restoreAgentFinishedTimeoutMillis = mParameters.getRestoreAgentFinishedTimeoutMillis();

        assertEquals(
                BackupAgentTimeoutParameters.DEFAULT_KV_BACKUP_AGENT_TIMEOUT_MILLIS,
                kvBackupAgentTimeoutMillis);
        assertEquals(
                BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS,
                fullBackupAgentTimeoutMillis);
        assertEquals(
                BackupAgentTimeoutParameters.DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS,
                sharedBackupAgentTimeoutMillis);
        assertEquals(
                BackupAgentTimeoutParameters.DEFAULT_RESTORE_SESSION_TIMEOUT_MILLIS,
                restoreSessionTimeoutMillis);
        assertEquals(
                BackupAgentTimeoutParameters.DEFAULT_RESTORE_AGENT_FINISHED_TIMEOUT_MILLIS,
                restoreAgentFinishedTimeoutMillis);
    }

    @Test
    public void
            testGetRestoreAgentTimeout_afterConstructorWithStartForSystemAgent_returnsDefaultValue() {
        mParameters.start();

        // Numbers before FIRST_APPLICATION_UID are reserved as UIDs for system components.
        long restoreTimeout =
                mParameters.getRestoreAgentTimeoutMillis(Process.FIRST_APPLICATION_UID - 1);

        assertThat(restoreTimeout)
                .isEqualTo(
                        BackupAgentTimeoutParameters.DEFAULT_RESTORE_SYSTEM_AGENT_TIMEOUT_MILLIS);
    }

    @Test
    public void
            testGetRestoreAgentTimeout_afterConstructorWithStartForAppAgent_returnsDefaultValue() {
        mParameters.start();

        // Numbers starting from FIRST_APPLICATION_UID are reserved for app UIDs.
        long restoreTimeout =
                mParameters.getRestoreAgentTimeoutMillis(Process.FIRST_APPLICATION_UID);

        assertThat(restoreTimeout)
                .isEqualTo(BackupAgentTimeoutParameters.DEFAULT_RESTORE_AGENT_TIMEOUT_MILLIS);
    }

    @Test
    public void testGetQuotaExceededTimeoutMillis_returnsDefaultValue() {
        mParameters.start();

        long timeout = mParameters.getQuotaExceededTimeoutMillis();

        assertThat(timeout)
                .isEqualTo(BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
    }

    @Test
    public void testGetQuotaExceededTimeoutMillis_whenSettingSet_returnsSetValue() {
        putStringAndNotify(
                BackupAgentTimeoutParameters.SETTING_QUOTA_EXCEEDED_TIMEOUT_MILLIS + "=" + 1279);
        mParameters.start();

        long timeout = mParameters.getQuotaExceededTimeoutMillis();

        assertThat(timeout).isEqualTo(1279);
    }

    /**
     * Tests that timeout parameters are updated when we call start, even when a setting change
     * occurs while we are not observing.
     */
    @Test
    public void testGetParameters_withSettingChangeBeforeStart_updatesValues() {
        long testTimeout = BackupAgentTimeoutParameters.DEFAULT_KV_BACKUP_AGENT_TIMEOUT_MILLIS * 2;
        final String setting =
                BackupAgentTimeoutParameters.SETTING_KV_BACKUP_AGENT_TIMEOUT_MILLIS
                        + "="
                        + testTimeout;
        putStringAndNotify(setting);
        mParameters.start();

        long kvBackupAgentTimeoutMillis = mParameters.getKvBackupAgentTimeoutMillis();

        assertEquals(testTimeout, kvBackupAgentTimeoutMillis);
    }

    /**
     * Tests that timeout parameters are updated when a setting change occurs while we are observing
     * changes.
     */
    @Test
    public void testGetParameters_withSettingChangeAfterStart_updatesValues() {
        mParameters.start();
        long testTimeout = BackupAgentTimeoutParameters.DEFAULT_KV_BACKUP_AGENT_TIMEOUT_MILLIS * 2;
        final String setting =
                BackupAgentTimeoutParameters.SETTING_KV_BACKUP_AGENT_TIMEOUT_MILLIS
                        + "="
                        + testTimeout;
        putStringAndNotify(setting);

        long kvBackupAgentTimeoutMillis = mParameters.getKvBackupAgentTimeoutMillis();

        assertEquals(testTimeout, kvBackupAgentTimeoutMillis);
    }

    /**
     * Robolectric does not notify observers of changes to settings so we have to trigger it here.
     * Currently, the mock of {@link Settings.Secure#putString(ContentResolver, String, String)}
     * only stores the value. TODO: Implement properly in ShadowSettings.
     */
    private void putStringAndNotify(String value) {
        Settings.Global.putString(mContentResolver, BackupAgentTimeoutParameters.SETTING, value);

        // We pass null as the observer since notifyChange iterates over all available observers and
        // we don't have access to the local observer.
        mContentResolver.notifyChange(
                Settings.Global.getUriFor(BackupAgentTimeoutParameters.SETTING), /*observer*/ null);
    }
}
