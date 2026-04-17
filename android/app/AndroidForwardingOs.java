/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import static android.content.ContentResolver.DEPRECATE_DATA_COLUMNS;
import static android.content.ContentResolver.DEPRECATE_DATA_PREFIX;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.FileUtils;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import libcore.io.ForwardingOs;
import libcore.io.IoUtils;
import libcore.io.Os;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


/**
 * Installs selective syscall interception.
 *
 * <p>For example, this is used to implement special filesystem paths that will be redirected to
 * {@link ContentResolver#openFileDescriptor(Uri, String)}.
 *
 * @hide
 */
class AndroidForwardingOs extends ForwardingOs {
    private static final String TAG = "AndroidForwardingOs";

    public static void install() {
        // If feature is disabled, we don't need to install
        if (!DEPRECATE_DATA_COLUMNS) return;

        // Install interception and make sure it sticks!
        Os def;
        do {
            def = Os.getDefault();
        } while (!Os.compareAndSetDefault(def, new AndroidForwardingOs(def)));
    }

    private AndroidForwardingOs(Os os) {
        super(os);
    }

    private FileDescriptor openDeprecatedDataPath(String path, int mode) throws ErrnoException {
        final Uri uri = ContentResolver.translateDeprecatedDataPath(path);
        Log.v(TAG, "Redirecting " + path + " to " + uri);

        final ContentResolver cr = ActivityThread.currentActivityThread().getApplication()
                .getContentResolver();
        try {
            final FileDescriptor fd = new FileDescriptor();
            fd.setInt$(cr.openFileDescriptor(uri,
                    FileUtils.translateModePosixToString(mode)).detachFd());
            return fd;
        } catch (SecurityException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.EACCES);
        } catch (FileNotFoundException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.ENOENT);
        }
    }

    private void deleteDeprecatedDataPath(String path) throws ErrnoException {
        final Uri uri = ContentResolver.translateDeprecatedDataPath(path);
        Log.v(TAG, "Redirecting " + path + " to " + uri);

        final ContentResolver cr = ActivityThread.currentActivityThread().getApplication()
                .getContentResolver();
        try {
            if (cr.delete(uri, null, null) == 0) {
                throw new FileNotFoundException();
            }
        } catch (SecurityException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.EACCES);
        } catch (FileNotFoundException e) {
            throw new ErrnoException(e.getMessage(), OsConstants.ENOENT);
        }
    }

    @Override
    public boolean access(String path, int mode) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            // If we opened it okay, then access check succeeded
            IoUtils.closeQuietly(
                    openDeprecatedDataPath(path, FileUtils.translateModeAccessToPosix(mode)));
            return true;
        } else {
            return super.access(path, mode);
        }
    }

    @Override
    public FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            return openDeprecatedDataPath(path, mode);
        } else {
            return super.open(path, flags, mode);
        }
    }

    @Override
    public StructStat stat(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            final FileDescriptor fd = openDeprecatedDataPath(path, OsConstants.O_RDONLY);
            try {
                return android.system.Os.fstat(fd);
            } finally {
                IoUtils.closeQuietly(fd);
            }
        } else {
            return super.stat(path);
        }
    }

    @Override
    public void unlink(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            deleteDeprecatedDataPath(path);
        } else {
            super.unlink(path);
        }
    }

    @Override
    public void remove(String path) throws ErrnoException {
        if (path != null && path.startsWith(DEPRECATE_DATA_PREFIX)) {
            deleteDeprecatedDataPath(path);
        } else {
            super.remove(path);
        }
    }

    @Override
    public void rename(String oldPath, String newPath) throws ErrnoException {
        try {
            super.rename(oldPath, newPath);
        } catch (ErrnoException e) {
            // On emulated volumes, we have bind mounts for /Android/data and
            // /Android/obb, which prevents move from working across those directories
            // and other directories on the filesystem. To work around that, try to
            // recover by doing a copy instead.
            // Note that we only do this for "/storage/emulated", because public volumes
            // don't have these bind mounts, neither do private volumes that are not
            // the primary storage.
            if (e.errno == OsConstants.EXDEV && oldPath.startsWith("/storage/emulated")
                    && newPath.startsWith("/storage/emulated")) {
                Log.v(TAG, "Recovering failed rename " + oldPath + " to " + newPath);
                try {
                    Files.move(new File(oldPath).toPath(), new File(newPath).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    Log.e(TAG, "Rename recovery failed ", e2);
                    throw e;
                }
            } else {
                throw e;
            }
        }
    }
}
