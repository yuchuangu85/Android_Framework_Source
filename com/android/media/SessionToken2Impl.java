/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.media;

import static android.media.SessionToken2.TYPE_SESSION;
import static android.media.SessionToken2.TYPE_SESSION_SERVICE;
import static android.media.SessionToken2.TYPE_LIBRARY_SERVICE;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.media.MediaLibraryService2;
import android.media.MediaSessionService2;
import android.media.SessionToken2;
import android.media.SessionToken2.TokenType;
import android.media.update.SessionToken2Provider;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.util.List;

public class SessionToken2Impl implements SessionToken2Provider {
    private static final String KEY_UID = "android.media.token.uid";
    private static final String KEY_TYPE = "android.media.token.type";
    private static final String KEY_PACKAGE_NAME = "android.media.token.package_name";
    private static final String KEY_SERVICE_NAME = "android.media.token.service_name";
    private static final String KEY_ID = "android.media.token.id";
    private static final String KEY_SESSION_BINDER = "android.media.token.session_binder";

    private final SessionToken2 mInstance;
    private final int mUid;
    private final @TokenType int mType;
    private final String mPackageName;
    private final String mServiceName;
    private final String mId;
    private final IMediaSession2 mSessionBinder;

    /**
     * Public constructor for the legacy support (i.e. browser can try connecting to any browser
     * service if it knows the service name)
     */
    public SessionToken2Impl(Context context, SessionToken2 instance,
            String packageName, String serviceName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName shouldn't be empty");
        }
        if (TextUtils.isEmpty(serviceName)) {
            throw new IllegalArgumentException("serviceName shouldn't be empty");
        }
        mInstance = instance;
        // Calculate uid if it's not specified.
        final PackageManager manager = context.getPackageManager();
        if (uid < 0) {
            try {
                uid = manager.getPackageUid(packageName, 0);
            } catch (NameNotFoundException e) {
                throw new IllegalArgumentException("Cannot find package " + packageName);
            }
        }
        mUid = uid;

        // Infer id and type from package name and service name
        // TODO(jaewan): Handle multi-user.
        String id = getSessionIdFromService(manager, MediaLibraryService2.SERVICE_INTERFACE,
                packageName, serviceName);
        if (id != null) {
            mId = id;
            mType = TYPE_LIBRARY_SERVICE;
        } else {
            // retry with session service
            mId = getSessionIdFromService(manager, MediaSessionService2.SERVICE_INTERFACE,
                    packageName, serviceName);
            mType = TYPE_SESSION_SERVICE;
        }
        if (mId == null) {
            throw new IllegalArgumentException("service " + serviceName + " doesn't implement"
                    + " session service nor library service. Use service's full name.");
        }
        mPackageName = packageName;
        mServiceName = serviceName;
        mSessionBinder = null;
    }

    SessionToken2Impl(int uid, int type, String packageName, String serviceName, String id,
            IMediaSession2 sessionBinder) {
        // TODO(jaewan): Add sanity check (b/73863865)
        mUid = uid;
        mType = type;
        mPackageName = packageName;
        mServiceName = serviceName;
        mId = id;
        mSessionBinder = sessionBinder;
        mInstance = new SessionToken2(this);
    }

    private static String getSessionIdFromService(PackageManager manager, String serviceInterface,
            String packageName, String serviceName) {
        Intent serviceIntent = new Intent(serviceInterface);
        serviceIntent.setPackage(packageName);
        // Use queryIntentServices to find services with MediaLibraryService2.SERVICE_INTERFACE.
        // We cannot use resolveService with intent specified class name, because resolveService
        // ignores actions if Intent.setClassName() is specified.
        List<ResolveInfo> list = manager.queryIntentServices(
                serviceIntent, PackageManager.GET_META_DATA);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo resolveInfo = list.get(i);
                if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                    continue;
                }
                if (TextUtils.equals(resolveInfo.serviceInfo.name, serviceName)) {
                    return getSessionId(resolveInfo);
                }
            }
        }
        return null;
    }

    public static String getSessionId(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        } else if (resolveInfo.serviceInfo.metaData == null) {
            return "";
        } else {
            return resolveInfo.serviceInfo.metaData.getString(
                    MediaSessionService2.SERVICE_META_DATA, "");
        }
    }

    public SessionToken2 getInstance() {
        return mInstance;
    }

    @Override
    public String getPackageName_impl() {
        return mPackageName;
    }

    @Override
    public int getUid_impl() {
        return mUid;
    }

    @Override
    public String getId_imp() {
        return mId;
    }

    @Override
    public int getType_impl() {
        return mType;
    }

    String getServiceName() {
        return mServiceName;
    }

    IMediaSession2 getSessionBinder() {
        return mSessionBinder;
    }

    public static SessionToken2 fromBundle_impl(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        final int uid = bundle.getInt(KEY_UID);
        final @TokenType int type = bundle.getInt(KEY_TYPE, -1);
        final String packageName = bundle.getString(KEY_PACKAGE_NAME);
        final String serviceName = bundle.getString(KEY_SERVICE_NAME);
        final String id = bundle.getString(KEY_ID);
        final IBinder sessionBinder = bundle.getBinder(KEY_SESSION_BINDER);

        // Sanity check.
        switch (type) {
            case TYPE_SESSION:
                if (sessionBinder == null) {
                    throw new IllegalArgumentException("Unexpected sessionBinder for session,"
                            + " binder=" + sessionBinder);
                }
                break;
            case TYPE_SESSION_SERVICE:
            case TYPE_LIBRARY_SERVICE:
                if (TextUtils.isEmpty(serviceName)) {
                    throw new IllegalArgumentException("Session service needs service name");
                }
                break;
            default:
                throw new IllegalArgumentException("Invalid type");
        }
        if (TextUtils.isEmpty(packageName) || id == null) {
            throw new IllegalArgumentException("Package name nor ID cannot be null.");
        }
        return new SessionToken2Impl(uid, type, packageName, serviceName, id,
                sessionBinder != null ? IMediaSession2.Stub.asInterface(sessionBinder) : null)
                .getInstance();
    }

    @Override
    public Bundle toBundle_impl() {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_UID, mUid);
        bundle.putString(KEY_PACKAGE_NAME, mPackageName);
        bundle.putString(KEY_SERVICE_NAME, mServiceName);
        bundle.putString(KEY_ID, mId);
        bundle.putInt(KEY_TYPE, mType);
        bundle.putBinder(KEY_SESSION_BINDER,
                mSessionBinder != null ? mSessionBinder.asBinder() : null);
        return bundle;
    }

    @Override
    public int hashCode_impl() {
        final int prime = 31;
        return mType
                + prime * (mUid
                + prime * (mPackageName.hashCode()
                + prime * (mId.hashCode()
                + prime * (mServiceName != null ? mServiceName.hashCode() : 0))));
    }

    @Override
    public boolean equals_impl(Object obj) {
        if (!(obj instanceof SessionToken2)) {
            return false;
        }
        SessionToken2Impl other = from((SessionToken2) obj);
        return mUid == other.mUid
                && TextUtils.equals(mPackageName, other.mPackageName)
                && TextUtils.equals(mServiceName, other.mServiceName)
                && TextUtils.equals(mId, other.mId)
                && mType == other.mType;
    }

    @Override
    public String toString_impl() {
        return "SessionToken {pkg=" + mPackageName + " id=" + mId + " type=" + mType
                + " service=" + mServiceName + " binder=" + mSessionBinder + "}";
    }

    static SessionToken2Impl from(SessionToken2 token) {
        return ((SessionToken2Impl) token.getProvider());
    }
}
