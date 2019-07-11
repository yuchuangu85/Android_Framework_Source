/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.content.res;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;

/**
 * Delegate used to provide implementation of a select few native methods of {@link AssetManager}
 * <p/>
 * Through the layoutlib_create tool, the original native methods of AssetManager have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class AssetManager_Delegate {

    @LayoutlibDelegate
    public static InputStream open(AssetManager mgr, String fileName) throws IOException {
        return mgr.open_Original(fileName);
    }

    @LayoutlibDelegate
    public static InputStream open(AssetManager mgr, String fileName, int accessMode)
            throws IOException {
        if (!(mgr instanceof BridgeAssetManager)) {
            return mgr.open_Original(fileName, accessMode);
        }
        return ((BridgeAssetManager) mgr).getAssetRepository().openAsset(fileName, accessMode);
    }

    @LayoutlibDelegate
    /*package*/ static long newTheme(AssetManager manager) {
        return Resources_Theme_Delegate.getDelegateManager()
                .addNewDelegate(new Resources_Theme_Delegate());
    }

    @LayoutlibDelegate
    /*package*/ static void deleteTheme(AssetManager manager, long theme) {
        Resources_Theme_Delegate.getDelegateManager().removeJavaReferenceFor(theme);
    }

    @LayoutlibDelegate
    /*package*/ static SparseArray<String> getAssignedPackageIdentifiers(AssetManager manager) {
        return new SparseArray<>();
    }
}
