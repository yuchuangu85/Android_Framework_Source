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

package android.graphics.pdf.component;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents the font attributes of a {@link PdfPageTextObject}.
 * This class encapsulates the attributes font family, boldness and italic styling.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_TEXT_OBJECTS)
public class PdfPageTextObjectFont {
    private @FontFamily int mFontFamily;
    private boolean mIsBold;
    private boolean mIsItalic;

    /**
     * Constant representing the Courier font family.
     */
    public static final int FONT_FAMILY_COURIER = 0;

    /**
     * Constant representing the Helvetica font family.
     */
    public static final int FONT_FAMILY_HELVETICA = 1;

    /**
     * Constant representing the Symbol font family.
     * Note: This font family only renders symbols and does not support bold or italic.
     */
    public static final int FONT_FAMILY_SYMBOL = 2;

    /**
     * Constant representing the Times New Roman font family.
     */
    public static final int FONT_FAMILY_TIMES_NEW_ROMAN = 3;

    /**
     * Constructs a new {@link PdfPageTextObjectFont} with the specified attributes.
     *
     * @param fontFamily The font family, as defined by {@link FontFamily}
     * @param isBold true if the text should be bold, false otherwise
     * @param isItalic true if the text should be italic, false otherwise
     */
    public PdfPageTextObjectFont(@FontFamily int fontFamily,
            boolean isBold, boolean isItalic) {
        Preconditions.checkArgument(isValidFontFamily(fontFamily), "FontFamily is invalid");
        this.mFontFamily = fontFamily;
        this.mIsBold = isBold;
        this.mIsItalic = isItalic;
    }

    /**
     * Creates a new {@link PdfPageTextObjectFont} by copying attributes from the another
     * {@link PdfPageTextObjectFont} instance.
     *
     * @param font The {@link PdfPageTextObjectFont} instance to copy attributes from.
     */
    public PdfPageTextObjectFont(@NonNull PdfPageTextObjectFont font) {
        Preconditions.checkNotNull(font, "Font should not be null");
        this.mFontFamily = font.getFontFamily();
        this.mIsBold = font.isBold();
        this.mIsItalic = font.isItalic();
    }

    /**
     * Returns the font-family which is of type {@link FontFamily}, previously set using
     * {@link PdfPageTextObjectFont#setFontFamily(int)} or the constructor.
     *
     * @return The font-family.
     */
    public @FontFamily int getFontFamily() {
        return mFontFamily;
    }

    /**
     * Set the font family of the object.
     *
     * @param fontFamily The font family to be set.
     */
    public void setFontFamily(@FontFamily int fontFamily) {
        Preconditions.checkArgument(isValidFontFamily(fontFamily), "FontFamily is invalid");
        this.mFontFamily = fontFamily;
    }

    /**
     * Determines if the text is bold.
     *
     * @return true if the text is bold, false otherwise.
     */
    public boolean isBold() {
        return mIsBold;
    }

    /**
     * Sets whether the text should be bold or not.
     *
     * @param bold true if the text should be bold, false otherwise.
     */
    public void setBold(boolean bold) {
        this.mIsBold = bold;
    }

    /**
     * Determines if the text is italic.
     *
     * @return true if the text is italic, false otherwise.
     */
    public boolean isItalic() {
        return mIsItalic;
    }

    /**
     * Set whether the text should be italic or not.
     *
     * @param italic true if the text should be italic, false otherwise.
     */
    public void setItalic(boolean italic) {
        this.mIsItalic = italic;
    }

    /**
     * Holds the set of font families supported by {@link PdfPageTextObject}.
     * The specified font families are standard font families defined
     * in the PDF Spec 1.7 - Page 146.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FONT_FAMILY_COURIER, FONT_FAMILY_HELVETICA, FONT_FAMILY_SYMBOL,
            FONT_FAMILY_TIMES_NEW_ROMAN})
    public @interface FontFamily {
    }

    private boolean isValidFontFamily(int fontFamily) {
        return fontFamily == FONT_FAMILY_COURIER
               || fontFamily == FONT_FAMILY_HELVETICA
               || fontFamily == FONT_FAMILY_SYMBOL
               || fontFamily == FONT_FAMILY_TIMES_NEW_ROMAN;
    }
}
