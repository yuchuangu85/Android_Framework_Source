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
package android.app.permissionui;

import static android.app.permissionui.LocationButtonProviderFactory.LocationButtonProviderImpl;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.view.SurfaceControlViewHost;

/**
 * The client-side implementation of {@link LocationButtonSession}. This class
 * wraps the {@link ILocationButtonSession} AIDL interface and provides a
 * concrete implementation of the session methods.
 *
 * @hide
 */
final class LocationButtonSessionWrapper implements LocationButtonSession, IBinder.DeathRecipient {
    private final LocationButtonProviderImpl mProvider;
    private final LocationButtonSessionResponse mSessionResponse;
    private final LocationButtonClient mLocationButtonClient;
    private final LocationButtonClientWrapper mLocationButtonClientWrapper;

    LocationButtonSessionWrapper(@NonNull LocationButtonProviderImpl provider,
            @NonNull LocationButtonSessionResponse response, @NonNull LocationButtonClient client,
            @NonNull LocationButtonClientWrapper clientWrapper) {
        mProvider = provider;
        mLocationButtonClient = client;
        mSessionResponse = response;
        mLocationButtonClientWrapper = clientWrapper;
        linkDeathRecipient();
    }

    private void linkDeathRecipient() {
        try {
            mSessionResponse.getSession().asBinder().linkToDeath(this, 0 /* flags*/);
        } catch (RemoteException e) {
            this.binderDied();
        }
    }

    @Override
    @NonNull
    public SurfaceControlViewHost.SurfacePackage getSurfacePackage() {
        return mSessionResponse.getSurfacePackage();
    }

    @Override
    public void resize(int width, int height) {
        try {
            mSessionResponse.getSession().resize(width, height);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        try {
            mSessionResponse.getSession().setPadding(left, top, right, bottom);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void changeConfiguration(@NonNull Configuration newConfig) {
        try {
            mSessionResponse.getSession().changeConfiguration(newConfig);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        try {
            mSessionResponse.getSession().close();
            mSessionResponse.getSession().asBinder().unlinkToDeath(this, 0 /* flags*/);
        } catch (Exception e) {
            // Ignore as client has already asked to close this session.
        } finally {
            mProvider.onSessionClosed(mLocationButtonClient);
        }
    }

    @Override
    public void setCornerRadius(float cornerRadius) {
        try {
            mSessionResponse.getSession().setCornerRadius(cornerRadius);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setPressedCornerRadius(float cornerRadius) {
        try {
            mSessionResponse.getSession().setPressedCornerRadius(cornerRadius);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setTextColor(int color) {
        try {
            mSessionResponse.getSession().setTextColor(color);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setBackgroundColor(int color) {
        try {
            mSessionResponse.getSession().setBackgroundColor(color);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setIconTint(int color) {
        try {
            mSessionResponse.getSession().setIconTint(color);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setTextType(int textType) {
        try {
            mSessionResponse.getSession().setTextType(textType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setStrokeColor(int color) {
        try {
            mSessionResponse.getSession().setStrokeColor(color);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void setStrokeWidth(int width) {
        try {
            mSessionResponse.getSession().setStrokeWidth(width);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void binderDied() {
        mLocationButtonClientWrapper.onSessionError(new ParcelableException(
                new RuntimeException("Binder object hosting this session has died. "
                        + "Clean up resources.")));
    }
}
