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

import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Color;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a text object on a PDF page.
 * This class extends PageObject and provides methods to access and modify the text content.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_TEXT_OBJECTS)
public final class PdfPageTextObject extends PdfPageObject {
    private String mText;
    private final PdfPageTextObjectFont mFont;
    private final float mFontSize;
    private @ColorInt int mStrokeColor;
    private float mStrokeWidth = 1.0f;
    private @ColorInt int mFillColor;

    /**
     * Unknown Render Mode.
     */
    public static final int RENDER_MODE_UNKNOWN = -1;

    /**
     * Fill Mode : Only the interior of the glyphs is filled with the fill color.
     */
    public static final int RENDER_MODE_FILL = 0;

    /**
     * Stroke Mode : Only the outline of the glyphs is stroked with the stroke color.
     */
    public static final int RENDER_MODE_STROKE = 1;

    /**
     * FillStroke Mode : Both the interior and outline of the glyphs are rendered
     * using the fill abd stroke colors respectively.
     */
    public static final int RENDER_MODE_FILL_STROKE = 2;

    private @RenderMode int mRenderMode;

    /**
     * Constructor for the PdfPageTextObject.
     * Sets the object type to TEXT and initializes the text color to black.
     *
     * @param font     The font of the text.
     * @param fontSize The font size of the text.
     */
    public PdfPageTextObject(@NonNull String text, @NonNull PdfPageTextObjectFont font,
            float fontSize) {
        super(PdfPageObjectType.TEXT);
        Preconditions.checkNotNull(text, "Text should not be null");
        Preconditions.checkNotNull(font, "Font should not be null");
        this.mText = text;
        this.mFont = font;
        this.mFontSize = fontSize;
        this.mStrokeColor = Color.BLACK;
        this.mFillColor = Color.BLACK;
        if (Flags.enableEditPdfPageObjects()) {
            this.mRenderMode = RENDER_MODE_FILL;
        }
    }

    /**
     * Returns the text content of the object.
     *
     * @return The text content.
     */
    @NonNull
    public String getText() {
        return mText;
    }

    /**
     * Sets the text content of the object.
     *
     * @param text The text content to set.
     */
    public void setText(@NonNull String text) {
        Preconditions.checkNotNull(text, "Text should not be null");
        this.mText = text;
    }

    /**
     * Returns the font size of the object.
     *
     * @return The font size.
     */
    public float getFontSize() {
        return mFontSize;
    }

    /**
     * Returns the font of the text.
     *
     * @return A copy of the font object.
     */
    @NonNull
    public PdfPageTextObjectFont getFont() {
        return new PdfPageTextObjectFont(mFont);
    }

    /**
     * Returns the fill color of the object.
     * Returns {@link android.graphics.Color#BLACK} by default if not set.
     *
     * @return The fill color of the object.
     */
    public @ColorInt int getFillColor() {
        return mFillColor;
    }

    /**
     * Sets the fill color of the object.
     * Setting the fillColor will have no effect if {@link RenderMode} is not
     * {@link #RENDER_MODE_FILL} or {@link #RENDER_MODE_FILL_STROKE}.
     *
     * @param fillColor The fill color of the object.
     */
    public void setFillColor(@ColorInt int fillColor) {
        this.mFillColor = fillColor;
    }

    /**
     * Returns the stroke width of the object.
     *
     * @return The stroke width of the object.
     */
    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    /**
     * Sets the stroke width of the object.
     *
     * @param strokeWidth The stroke width of the object.
     */
    public void setStrokeWidth(float strokeWidth) {
        this.mStrokeWidth = strokeWidth;
    }

    /**
     * Returns the stroke color of the object.
     * Returns {@link android.graphics.Color#BLACK} by default if not set.
     *
     * @return The stroke color of the object.
     */
    public @ColorInt int getStrokeColor() {
        return mStrokeColor;
    }

    /**
     * Sets the stroke color of the object.
     * Setting the strokeColor will have no effect if {@link RenderMode} is not
     * {@link #RENDER_MODE_STROKE} or {@link #RENDER_MODE_FILL_STROKE}.
     *
     * @param strokeColor The stroke color of the object.
     */
    public void setStrokeColor(@ColorInt int strokeColor) {
        this.mStrokeColor = strokeColor;
    }

    /**
     * Returns the render mode of the object.
     *
     * @return The render mode of the object.
     */
    public @RenderMode int getRenderMode() {
        return mRenderMode;
    }

    /**
     * Sets the {@link PdfPageTextObject.RenderMode} of the object.
     *
     * @param renderMode The {@link PdfPageTextObject.RenderMode} to be set.
     * @throws IllegalArgumentException if the provided renderMode is invalid.
     */
    public void setRenderMode(@RenderMode int renderMode) {
        Preconditions.checkArgument(isValidRenderMode(renderMode), "RenderMode is invalid");
        this.mRenderMode = renderMode;
    }

    /**
     * Defines rendering modes for {@link PdfPageTextObject} (fill, stroke, etc.).
     *
     * <p>It provides constants for specifying how graphical elements
     * are rendered on a PDF page. It dictates whether the glyph is filled, stroked, or both.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RENDER_MODE_"}, value = {RENDER_MODE_UNKNOWN, RENDER_MODE_FILL,
            RENDER_MODE_STROKE, RENDER_MODE_FILL_STROKE})
    public @interface RenderMode {
    }

    private boolean isValidRenderMode(int renderMode) {
        return renderMode == RENDER_MODE_FILL
                || renderMode == RENDER_MODE_STROKE
                || renderMode == RENDER_MODE_FILL_STROKE;
    }
}
