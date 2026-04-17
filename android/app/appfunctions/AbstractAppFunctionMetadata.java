/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;

/** An interface to be implemented by AppFunction's metadata classes. */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
interface AbstractAppFunctionMetadata {
    /** Returns a {@link GenericDocument} representation of the metadata. */
    @NonNull
    GenericDocument getMetadataDocument();
}
