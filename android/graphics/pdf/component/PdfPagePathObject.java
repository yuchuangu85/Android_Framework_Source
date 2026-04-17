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
import android.graphics.Path;
import android.graphics.pdf.flags.Flags;
import android.graphics.pdf.utils.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a path object on a PDF page. This class extends
 * {@link PdfPageObject} and provides methods to access and modify the
 * path's content, such as its shape, fill color, stroke color and line width.
 */
@FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_PAGE_OBJECTS)
public final class PdfPagePathObject extends PdfPageObject {
    private final Path mPath;
    private @ColorInt int mStrokeColor;
    private float mStrokeWidth;
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
     * Constructor for the PdfPagePathObject. Sets the object type
     * to {@link PdfPageObjectType#PATH}.
     */
    public PdfPagePathObject(@NonNull Path path) {
        super(PdfPageObjectType.PATH);
        Preconditions.checkNotNull(path, "Path should not be null");
        this.mPath = path;
        this.mRenderMode = RENDER_MODE_FILL;
        this.mFillColor = Color.BLACK;
    }

    /**
     * Returns the path of the object.
     * The returned path object might be an approximation of the one used to
     * create the original one if the original object has elements with curvature.
     * <p>
     * Note: The path is immutable because the underlying library does
     * not allow modifying the path once it is created.
     *
     * @return The path.
     */
    @NonNull
    public Path toPath() {
        return new Path(mPath);
    }

    /**
     * Returns the stroke color of the object.
     *
     * @return The stroke color of the object.
     */
    public @ColorInt int getStrokeColor() {
        return mStrokeColor;
    }

    /**
     * Sets the stroke color of the object.
     * <p>
     * Note: The strokeColor cannot be transparent and
     * setting the strokeColor will have no effect if {@link RenderMode} is not
     * {@link #RENDER_MODE_STROKE} or {@link #RENDER_MODE_FILL_STROKE}.
     *
     * @param strokeColor The stroke color of the object.
     */
    public void setStrokeColor(@ColorInt int strokeColor) {
        this.mStrokeColor = strokeColor;
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
     * Returns the fill color of the object.
     * Returns {@link Color#BLACK} if {@link #mFillColor} is not set.
     *
     * @return The fill color of the object.
     */
    public @ColorInt int getFillColor() {
        return mFillColor;
    }

    /**
     * Sets the fill color of the object.
     * <p>
     * Note: The fillColor cannot be transparent and
     * setting the fillColor will have no effect if {@link RenderMode} is not
     * {@link #RENDER_MODE_FILL} or {@link #RENDER_MODE_FILL_STROKE}.
     *
     * @param fillColor The fill color of the object.
     */
    public void setFillColor(@ColorInt int fillColor) {
        this.mFillColor = fillColor;
    }

    /**
     * Returns the {@link RenderMode} of the object.
     * Returns {@link RenderMode#RENDER_MODE_FILL} by default
     * if {@link PdfPagePathObject#mRenderMode} is not set.
     *
     * @return The {@link RenderMode} of the object.
     */
    public @RenderMode int getRenderMode() {
        return mRenderMode;
    }

    /**
     * Sets the {@link PdfPagePathObject.RenderMode} of the object.
     *
     * @param renderMode The {@link PdfPagePathObject.RenderMode} to be set.
     * @throws IllegalArgumentException if the provided renderMode is invalid.
     */
    public void setRenderMode(@RenderMode int renderMode) {
        Preconditions.checkArgument(isValidRenderMode(renderMode), "RenderMode is invalid");
        this.mRenderMode = renderMode;
    }

    /**
     * Defines rendering modes for {@link PdfPagePathObject} (fill, stroke, etc.).
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
