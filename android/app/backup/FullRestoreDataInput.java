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

package android.app.backup;

import android.annotation.CurrentTimeSecondsLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.backup.BackupAgent.BackupFileSystemObjectType;
import android.app.backup.BackupAgent.BackupTransportFlags;
import android.os.ParcelFileDescriptor;

import com.android.server.backup.Flags;

import java.io.File;

/**
 * Provides the interface through which a {@link BackupAgent} reads entire files from a full backup
 * data set, via its {@link BackupAgent#onRestoreFile(FullRestoreDataInput)} method.
 */
@FlaggedApi(Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER)
public class FullRestoreDataInput {
    private final ParcelFileDescriptor mData;
    private final long mSize;
    private final File mDestination;
    private final int mType;
    private final long mMode;
    private final long mModificationTime;
    private final long mAppVersionCode;
    private final int mTransportFlags;
    private final String mContentVersion;

    /** @hide */
    public FullRestoreDataInput(
            ParcelFileDescriptor data,
            long size,
            File destination,
            @BackupFileSystemObjectType int type,
            long mode,
            long mtime,
            long appVersionCode,
            @BackupTransportFlags int transportFlags,
            String contentVersion) {
        this.mData = data;
        this.mSize = size;
        this.mDestination = destination;
        this.mType = type;
        this.mMode = mode;
        this.mModificationTime = mtime;
        this.mAppVersionCode = appVersionCode;
        this.mTransportFlags = transportFlags;
        this.mContentVersion = contentVersion;
    }

    /** Read-only file descriptor containing the file data. */
    @NonNull
    public ParcelFileDescriptor getData() {
        return mData;
    }

    /** Size of the file in bytes. */
    public long getSize() {
        return mSize;
    }

    /** The file on disk to be restored with the given data. */
    @NonNull
    public File getDestination() {
        return mDestination;
    }

    /**
     * The kind of file system object being restored. This will be either {@link
     * BackupAgent#TYPE_FILE} or {@link BackupAgent#TYPE_DIRECTORY}.
     */
    @BackupFileSystemObjectType
    public int getType() {
        return mType;
    }

    /**
     * The access mode to be assigned to the destination after its data is written. This is in the
     * standard format used by {@code chmod()}.
     */
    public long getMode() {
        return mMode;
    }

    /**
     * A timestamp in the standard Unix epoch that represents the last modification time of the file
     * when it was backed up, suitable to be assigned to the file after its data is written.
     */
    @CurrentTimeSecondsLong
    public long getModificationTimeSeconds() {
        return mModificationTime;
    }

    /** The version code of the app that created the backup. */
    public long getAppVersionCode() {
        return mAppVersionCode;
    }

    /**
     * Flags with additional information about the transport. The transport flags that can be set
     * are defined in {@link BackupAgent}.
     */
    @BackupTransportFlags
    public int getTransportFlags() {
        return mTransportFlags;
    }

    /**
     * Content version set by the source device during a cross-platform transfer. Empty string if
     * the source device did not provide a content version.
     */
    @NonNull
    public String getContentVersion() {
        return mContentVersion;
    }
}
