/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.view;

import static android.view.WindowInsets.Side.BOTTOM;
import static android.view.WindowInsets.Side.LEFT;
import static android.view.WindowInsets.Side.RIGHT;
import static android.view.WindowInsets.Side.TOP;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.WindowInsets.Side.InsetsSide;

/**
 * Describes a bounding rectangle relative to an insets source frame.
 * @hide
 */
public class InsetsBoundingRect implements Parcelable {
    @InsetsSide
    private static final int ALIGNMENT_MASK_HORIZONTAL = LEFT | RIGHT;
    @InsetsSide
    private static final int ALIGNMENT_MASK_VERTICAL = TOP | BOTTOM;
    @InsetsSide
    private static final int CENTER_HORIZONTAL = 0;
    @InsetsSide
    private static final int CENTER_VERTICAL = 0;

    @InsetsSide
    private final int mAlignment;

    private int mX;
    private int mY;
    private int mWidth;
    private int mHeight;

    /**
     * Constructs a new instance of InsetsBoundingRect which describes a bounding rectangle relative
     * to an insets source frame.
     *
     * @param alignment the sides to align. If both horizontal (or vertical) sides are supplied,
     *                  the width (or height) of the bounding rectangle fills the source frame.
     *                  If none of horizontal (or vertical) sides is supplied, the rectangle will be
     *                  center aligned horizontally (or vertically).
     * @param x the offset in the x-coordinate. It will be applied after the alignment.
     * @param y the offset in the y-coordinate. It will be applied after the alignment.
     * @param width the width of this bounding rectangle. But if both LEFT | RIGHT are supplied,
     *              the width fills the source frame regardless of this parameter.
     * @param height the height of this bounding rectangle. But if both TOP | BOTTOM are supplied,
     *               the height fills the source frame regardless of this parameter.
     */
    public InsetsBoundingRect(@InsetsSide int alignment, int x, int y, int width, int height) {
        mAlignment = alignment;
        mX = x;
        mY = y;
        mWidth = width;
        mHeight = height;
    }

    public InsetsBoundingRect(@NonNull Parcel in) {
        mAlignment = in.readInt();
        mX = in.readInt();
        mY = in.readInt();
        mWidth = in.readInt();
        mHeight = in.readInt();
    }

    /**
     * Scales all the fields in length.
     */
    public void scale(float scale) {
        if (scale != 1f) {
            mX = (int) (mX * scale + 0.5f);
            mY = (int) (mY * scale + 0.5f);
            mWidth = (int) (mWidth * scale + 0.5f);
            mHeight = (int) (mHeight * scale + 0.5f);
        }
    }

    /**
     * Returns the bounding rectangle relative to the given source frame.
     *
     * @param sourceFrame the given insets source frame.
     * @param outRect the bounding {@link Rect} where the returned value writes to.
     */
    public void toRect(@NonNull Rect sourceFrame, @NonNull Rect outRect) {
        switch (mAlignment & ALIGNMENT_MASK_HORIZONTAL) {
            case LEFT -> {
                outRect.left = mX;
                outRect.right = outRect.left + mWidth;
            }
            case RIGHT -> {
                outRect.right = mX + sourceFrame.width();
                outRect.left = outRect.right - mWidth;
            }
            case CENTER_HORIZONTAL -> {
                outRect.left = mX + (sourceFrame.width() - mWidth) / 2;
                outRect.right = outRect.left + mWidth;
            }
            case ALIGNMENT_MASK_HORIZONTAL -> {
                outRect.left = mX;
                outRect.right = outRect.left + sourceFrame.width();
            }
        }
        switch (mAlignment & ALIGNMENT_MASK_VERTICAL) {
            case TOP -> {
                outRect.top = mY;
                outRect.bottom = outRect.top + mHeight;
            }
            case BOTTOM -> {
                outRect.bottom = mY + sourceFrame.height();
                outRect.top = outRect.bottom - mHeight;
            }
            case CENTER_VERTICAL -> {
                outRect.top = mY + (sourceFrame.height() - mHeight) / 2;
                outRect.bottom = outRect.top + mHeight;
            }
            case ALIGNMENT_MASK_VERTICAL -> {
                outRect.top = mY;
                outRect.bottom = outRect.top + sourceFrame.height();
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final InsetsBoundingRect r)) {
            return false;
        }
        return mAlignment == r.mAlignment
                && mX == r.mX
                && mY == r.mY
                && mWidth == r.mWidth
                && mHeight == r.mHeight;
    }

    @Override
    public int hashCode() {
        int result = mAlignment;
        result = 31 * result + mX;
        result = 31 * result + mY;
        result = 31 * result + mWidth;
        result = 31 * result + mHeight;
        return result;
    }

    @Override
    public String toString() {
        return TextUtils.formatSimple("InsetsBoundingRect{align=%s,(%d,%d)(%dx%d)}",
                WindowInsets.Side.toString(mAlignment), mX, mY, mWidth, mHeight);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAlignment);
        out.writeInt(mX);
        out.writeInt(mY);
        out.writeInt(mWidth);
        out.writeInt(mHeight);
    }

    @NonNull
    public static final Creator<InsetsBoundingRect> CREATOR = new Creator<>() {

        @Override
        public InsetsBoundingRect createFromParcel(Parcel in) {
            return new InsetsBoundingRect(in);
        }

        @Override
        public InsetsBoundingRect[] newArray(int size) {
            return new InsetsBoundingRect[size];
        }
    };
}
