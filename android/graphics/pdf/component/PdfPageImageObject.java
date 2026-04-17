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

package android.graphics.pdf.component;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.graphics.Bitmap;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

/**
 * Represents an image object on a PDF page. This class extends
 * {@link PdfPageObject} and provides methods to access and modify the
 * image content.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_PAGE_OBJECTS)
public final class PdfPageImageObject extends PdfPageObject {
    private Bitmap mBitmap;

    /**
     * Constructor for the PdfPageImageObject. Sets the object type
     * to IMAGE.
     *
     * @throws IllegalArgumentException if bitmap config is not ARGB_8888.
     */
    public PdfPageImageObject(@NonNull Bitmap bitmap) {
        super(PdfPageObjectType.IMAGE);
        Preconditions.checkNotNull(bitmap, "Bitmap should not be null");
        Preconditions.checkArgument(bitmap.getConfig() == Bitmap.Config.ARGB_8888,
                "Bitmap should be of type ARGB_8888");
        this.mBitmap = bitmap;
    }

    /**
     * Returns the bitmap image of the object.
     *
     * @return The bitmap image of the object.
     */
    @NonNull
    public Bitmap getBitmap() {
        return mBitmap;
    }

    /**
     * Sets the bitmap image of the object.
     *
     * @param bitmap The bitmap image to set.
     * @throws IllegalArgumentException if bitmap config is not ARGB_8888.
     */
    public void setBitmap(@NonNull Bitmap bitmap) {
        Preconditions.checkNotNull(bitmap, "Bitmap should not be null");
        Preconditions.checkArgument(bitmap.getConfig() == Bitmap.Config.ARGB_8888,
                "Bitmap should be of type ARGB_8888");
        this.mBitmap = bitmap;
    }

}
