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

package android.graphics.pdf;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.pdf.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a set of parameters that will be used to render a page of the PDF document.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PDF_VIEWER)
public final class RenderParams {
    /**
     * Mode to render the content for display on a screen.
     */
    public static final int RENDER_MODE_FOR_DISPLAY = 1;

    /**
     * Mode to render the content for printing.
     */
    public static final int RENDER_MODE_FOR_PRINT = 2;

    // LINT.IfChange
    /**
     * Flag to enable rendering of text annotation on the page.
     *
     * @see RenderParams#getRenderFlags()
     * @see RenderParams.Builder#setRenderFlags(int)
     */
    public static final int FLAG_RENDER_TEXT_ANNOTATIONS = 1 << 1;

    /**
     * Flag to enable rendering of highlight annotation on the page.
     *
     * @see RenderParams#getRenderFlags()
     * @see RenderParams.Builder#setRenderFlags(int)
     */
    public static final int FLAG_RENDER_HIGHLIGHT_ANNOTATIONS = 1 << 2;

    /**
     * Flag to enable rendering of stamp annotation on the page.
     *
     * @see RenderParams#getRenderFlags()
     * @see RenderParams.Builder#setRenderFlags(int)
     */
    @FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_STAMP_ANNOTATIONS)
    public static final int FLAG_RENDER_STAMP_ANNOTATIONS = 1 << 3;

    /**
     * Flag to enable rendering of freetext annotation on the page.
     *
     * @see RenderParams#getRenderFlags()
     * @see RenderParams.Builder#setRenderFlags(int)
     */
    @FlaggedApi(Flags.FLAG_ENABLE_EDIT_PDF_TEXT_ANNOTATIONS)
    public static final int FLAG_RENDER_FREETEXT_ANNOTATIONS = 1 << 4;
    // LINT.ThenChange(packages/providers/MediaProvider/pdf/framework/libs/pdfClient/page.h)

    /** Mode to include PDF form content in rendered PDF bitmaps. */
    @FlaggedApi(Flags.FLAG_ENABLE_RENDER_PARAMS_FORM_OPTIONS)
    public static final int RENDER_FORM_CONTENT_ENABLED = 1;

    /** Mode to exclude PDF form content from rendered PDF bitmaps. */
    @FlaggedApi(Flags.FLAG_ENABLE_RENDER_PARAMS_FORM_OPTIONS)
    public static final int RENDER_FORM_CONTENT_DISABLED = 2;

    /**
     * Mode to rely on the default behavior with respect to including PDF form content in rendered
     * PDF bitmaps.
     *
     * <p> {@link PdfRenderer} will render form content by default if the application is targeting
     * SDK version {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} or higher.
     *
     * <p> {@link PdfRendererPreV} will always render form content by default.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_RENDER_PARAMS_FORM_OPTIONS)
    public static final int RENDER_FORM_CONTENT_DEFAULT = 3;

    private final int mRenderMode;
    private final int mRenderFlags;

    private final int mRenderFormContentMode;

    private RenderParams(int renderMode, int renderFlags,
            @RenderFormContentMode int renderFormContentMode) {
        this.mRenderMode = renderMode;
        this.mRenderFlags = renderFlags;
        this.mRenderFormContentMode = renderFormContentMode;
    }

    private static int getRenderMask() {
        int renderMask = FLAG_RENDER_TEXT_ANNOTATIONS | FLAG_RENDER_HIGHLIGHT_ANNOTATIONS;
        if (android.graphics.pdf.flags.readonly.Flags.enableEditPdfTextAnnotations()) {
            renderMask |= FLAG_RENDER_FREETEXT_ANNOTATIONS;
        }
        if (android.graphics.pdf.flags.readonly.Flags.enableEditPdfStampAnnotations()) {
            renderMask |= FLAG_RENDER_STAMP_ANNOTATIONS;
        }
        return renderMask;
    }

    /**
     * Returns the render mode.
     */
    @RenderMode
    public int getRenderMode() {
        return mRenderMode;
    }

    /**
     * Returns the bitmask of the render flags.
     */
    @RenderFlags
    public int getRenderFlags() {
        return mRenderFlags;
    }

    /**
     * Returns the mode for rendering PDF form content, one of:
     * {@link #RENDER_FORM_CONTENT_ENABLED}, {@link #RENDER_FORM_CONTENT_DISABLED}, or
     * {@link #RENDER_FORM_CONTENT_DEFAULT}
     */
    @FlaggedApi(Flags.FLAG_ENABLE_RENDER_PARAMS_FORM_OPTIONS)
    @RenderFormContentMode
    public int getRenderFormContentMode() {
        return mRenderFormContentMode;
    }

    /** @hide */
    public int getRenderAnnotations() {
        return mRenderFlags & getRenderMask();
    }

    /** @hide */
    @IntDef({
            RENDER_MODE_FOR_DISPLAY,
            RENDER_MODE_FOR_PRINT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RenderMode {
    }

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_"}, value = {
            FLAG_RENDER_TEXT_ANNOTATIONS,
            FLAG_RENDER_HIGHLIGHT_ANNOTATIONS,
            FLAG_RENDER_STAMP_ANNOTATIONS,
            FLAG_RENDER_FREETEXT_ANNOTATIONS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RenderFlags {
    }

    /** @hide */
    @IntDef({
            RENDER_FORM_CONTENT_ENABLED,
            RENDER_FORM_CONTENT_DISABLED,
            RENDER_FORM_CONTENT_DEFAULT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RenderFormContentMode {
    }

    /**
     * Builder for constructing {@link RenderParams}.
     */
    public static final class Builder {

        private final int mRenderMode;

        @RenderFlags
        private int mRenderFlags;

        @RenderFormContentMode
        private int mRenderFormContentMode = RENDER_FORM_CONTENT_DEFAULT;

        /**
         * Create a builder for constructing a {@link RenderParams} object with the render mode.
         *
         * @param renderMode render mode for the content.
         */
        public Builder(@RenderMode int renderMode) {
            this.mRenderMode = renderMode;
        }

        /**
         * Sets the state of the render flag.
         * See {@link #setRenderFlags(int, int)} for usage information.
         *
         * @param renderFlags the bitmask of the render flag should be enabled, or {@code 0} to
         *                    disable all flags.
         * @see #setRenderFlags(int, int)
         * @see #getRenderFlags()
         */
        @NonNull
        public Builder setRenderFlags(@RenderFlags int renderFlags) {
            setRenderFlags(renderFlags, getRenderMask());
            return this;
        }

        /**
         * Sets the state of the render flag specified by the mask. To change all render flags at
         * once, see {@link #setRenderFlags(int)}.
         * <p>
         * When a render flag is enabled, it will be displayed on the updated
         * {@link android.graphics.Bitmap} of the renderer.
         * <p>
         * Multiple indicator types may be enabled or disabled by passing the logical OR of the
         * desired flags. If multiple flags are specified, they
         * will all be set to the same enabled state.
         * <p>
         * For example, to enable the render text annotations flag:
         * {@code setRenderFlags(FLAG_RENDER_TEXT_ANNOTATIONS, FLAG_RENDER_TEXT_ANNOTATIONS)}
         * <p>
         * To disable the render text annotations flag:
         * {@code setRenderFlags(0, FLAG_RENDER_TEXT_ANNOTATIONS)}
         *
         * @param renderFlags the render flag, or the logical OR of multiple
         *                    render flags. One or more of:
         *                    <ul>
         *                      <li>{@link #FLAG_RENDER_TEXT_ANNOTATIONS}</li>
         *                      <li>{@link #FLAG_RENDER_HIGHLIGHT_ANNOTATIONS}</li>
         *                    </ul>
         * @see #setRenderFlags(int)
         * @see #getRenderFlags()
         */
        @NonNull
        public Builder setRenderFlags(@RenderFlags int renderFlags, @RenderFlags int mask) {
            // Sanitize the mask
            mask &= getRenderMask();

            // Mask the flags
            renderFlags &= mask;

            // Merge with non-masked flags
            this.mRenderFlags = renderFlags | (this.mRenderFlags & ~mask);
            return this;
        }

        /**
         * Sets the mode to include or exclude form content in rendered PDF bitmaps. One of:
         * {@link #RENDER_FORM_CONTENT_ENABLED}, {@link #RENDER_FORM_CONTENT_DISABLED}, or
         * {@link #RENDER_FORM_CONTENT_DEFAULT}.
         *
         * <p>In {@link PdfRenderer} form content is rendered by default for applications targeting
         * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} or higher. This option can be
         * used to enable this behavior when targeting lower SDK versions, or to disable this
         * behavior when targeting higher SDK versions.
         *
         * <p>In {@link PdfRendererPreV} form content is always rendered by default. This option can
         * be used to disable this behavior.
         */
        @FlaggedApi(Flags.FLAG_ENABLE_RENDER_PARAMS_FORM_OPTIONS)
        @NonNull
        public Builder setRenderFormContentMode(@RenderFormContentMode int renderFormContentMode) {
            this.mRenderFormContentMode = renderFormContentMode;
            return this;
        }

        /**
         * Builds the {@link RenderParams} after the optional values has been set.
         *
         * @return the newly constructed {@link RenderParams} object
         */
        @NonNull
        public RenderParams build() {
            return new RenderParams(this.mRenderMode, this.mRenderFlags, mRenderFormContentMode);
        }
    }
}
