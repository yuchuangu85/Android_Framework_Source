package com.android.launcher3.icons;

import static android.graphics.Paint.DITHER_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;

import static com.android.launcher3.icons.ShadowGenerator.BLUR_FACTOR;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import com.android.launcher3.icons.BitmapInfo.Extender;

/**
 * This class will be moved to androidx library. There shouldn't be any dependency outside
 * this package.
 */
public class BaseIconFactory implements AutoCloseable {

    private static final String TAG = "BaseIconFactory";
    private static final int DEFAULT_WRAPPER_BACKGROUND = Color.WHITE;
    static final boolean ATLEAST_OREO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    static final boolean ATLEAST_P = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;

    private static final float ICON_BADGE_SCALE = 0.444f;

    private final Rect mOldBounds = new Rect();
    protected final Context mContext;
    private final Canvas mCanvas;
    private final PackageManager mPm;
    private final ColorExtractor mColorExtractor;
    private boolean mDisableColorExtractor;
    private boolean mBadgeOnLeft = false;

    protected final int mFillResIconDpi;
    protected final int mIconBitmapSize;

    private IconNormalizer mNormalizer;
    private ShadowGenerator mShadowGenerator;
    private final boolean mShapeDetection;

    private Drawable mWrapperIcon;
    private int mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND;
    private Bitmap mUserBadgeBitmap;

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final float PLACEHOLDER_TEXT_SIZE = 20f;
    private static int PLACEHOLDER_BACKGROUND_COLOR = Color.rgb(245, 245, 245);

    protected BaseIconFactory(Context context, int fillResIconDpi, int iconBitmapSize,
            boolean shapeDetection) {
        mContext = context.getApplicationContext();
        mShapeDetection = shapeDetection;
        mFillResIconDpi = fillResIconDpi;
        mIconBitmapSize = iconBitmapSize;

        mPm = mContext.getPackageManager();
        mColorExtractor = new ColorExtractor();

        mCanvas = new Canvas();
        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(DITHER_FLAG, FILTER_BITMAP_FLAG));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setColor(PLACEHOLDER_BACKGROUND_COLOR);
        mTextPaint.setTextSize(context.getResources().getDisplayMetrics().density *
                PLACEHOLDER_TEXT_SIZE);
        clear();
    }

    public BaseIconFactory(Context context, int fillResIconDpi, int iconBitmapSize) {
        this(context, fillResIconDpi, iconBitmapSize, false);
    }

    protected void clear() {
        mWrapperBackgroundColor = DEFAULT_WRAPPER_BACKGROUND;
        mDisableColorExtractor = false;
        mBadgeOnLeft = false;
    }

    public ShadowGenerator getShadowGenerator() {
        if (mShadowGenerator == null) {
            mShadowGenerator = new ShadowGenerator(mIconBitmapSize);
        }
        return mShadowGenerator;
    }

    public IconNormalizer getNormalizer() {
        if (mNormalizer == null) {
            mNormalizer = new IconNormalizer(mContext, mIconBitmapSize, mShapeDetection);
        }
        return mNormalizer;
    }

    @SuppressWarnings("deprecation")
    public BitmapInfo createIconBitmap(Intent.ShortcutIconResource iconRes) {
        try {
            Resources resources = mPm.getResourcesForApplication(iconRes.packageName);
            if (resources != null) {
                final int id = resources.getIdentifier(iconRes.resourceName, null, null);
                // do not stamp old legacy shortcuts as the app may have already forgotten about it
                return createBadgedIconBitmap(
                        resources.getDrawableForDensity(id, mFillResIconDpi),
                        Process.myUserHandle() /* only available on primary user */,
                        false /* do not apply legacy treatment */);
            }
        } catch (Exception e) {
            // Icon not found.
        }
        return null;
    }

    /**
     * Create a placeholder icon using the passed in text.
     *
     * @param placeholder used for foreground element in the icon bitmap
     * @param color used for the foreground text color
     * @return
     */
    public BitmapInfo createIconBitmap(String placeholder, int color) {
        if (!ATLEAST_OREO) return null;

        Bitmap placeholderBitmap = Bitmap.createBitmap(mIconBitmapSize, mIconBitmapSize,
                Bitmap.Config.ARGB_8888);
        mTextPaint.setColor(color);
        Canvas canvas = new Canvas(placeholderBitmap);
        canvas.drawText(placeholder, mIconBitmapSize / 2, mIconBitmapSize * 5 / 8, mTextPaint);
        AdaptiveIconDrawable drawable = new AdaptiveIconDrawable(
                new ColorDrawable(PLACEHOLDER_BACKGROUND_COLOR),
                new BitmapDrawable(mContext.getResources(), placeholderBitmap));
        Bitmap icon = createIconBitmap(drawable, IconNormalizer.ICON_VISIBLE_AREA_FACTOR);
        return BitmapInfo.of(icon, extractColor(icon));
    }

    public BitmapInfo createIconBitmap(Bitmap icon) {
        if (mIconBitmapSize != icon.getWidth() || mIconBitmapSize != icon.getHeight()) {
            icon = createIconBitmap(new BitmapDrawable(mContext.getResources(), icon), 1f);
        }

        return BitmapInfo.of(icon, extractColor(icon));
    }

    /**
     * Creates an icon from the bitmap cropped to the current device icon shape
     */
    public BitmapInfo createShapedIconBitmap(Bitmap icon, UserHandle user) {
        Drawable d = new FixedSizeBitmapDrawable(icon);
        if (ATLEAST_OREO) {
            float inset = AdaptiveIconDrawable.getExtraInsetFraction();
            inset = inset / (1 + 2 * inset);
            d = new AdaptiveIconDrawable(new ColorDrawable(Color.BLACK),
                    new InsetDrawable(d, inset, inset, inset, inset));
        }
        return createBadgedIconBitmap(d, user, true);
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            boolean shrinkNonAdaptiveIcons) {
        return createBadgedIconBitmap(icon, user, shrinkNonAdaptiveIcons, false, null);
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            int iconAppTargetSdk) {
        return createBadgedIconBitmap(icon, user, iconAppTargetSdk, false);
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            int iconAppTargetSdk, boolean isInstantApp) {
        return createBadgedIconBitmap(icon, user, iconAppTargetSdk, isInstantApp, null);
    }

    public BitmapInfo createBadgedIconBitmap(Drawable icon, UserHandle user,
            int iconAppTargetSdk, boolean isInstantApp, float[] scale) {
        boolean shrinkNonAdaptiveIcons = ATLEAST_P ||
                (ATLEAST_OREO && iconAppTargetSdk >= Build.VERSION_CODES.O);
        return createBadgedIconBitmap(icon, user, shrinkNonAdaptiveIcons, isInstantApp, scale);
    }

    public Bitmap createScaledBitmapWithoutShadow(Drawable icon, int iconAppTargetSdk) {
        boolean shrinkNonAdaptiveIcons = ATLEAST_P ||
                (ATLEAST_OREO && iconAppTargetSdk >= Build.VERSION_CODES.O);
        return  createScaledBitmapWithoutShadow(icon, shrinkNonAdaptiveIcons);
    }

    /**
     * Creates bitmap using the source drawable and various parameters.
     * The bitmap is visually normalized with other icons and has enough spacing to add shadow.
     *
     * @param icon                      source of the icon
     * @param user                      info can be used for a badge
     * @param shrinkNonAdaptiveIcons    {@code true} if non adaptive icons should be treated
     * @param isInstantApp              info can be used for a badge
     * @param scale                     returns the scale result from normalization
     * @return a bitmap suitable for disaplaying as an icon at various system UIs.
     */
    public BitmapInfo createBadgedIconBitmap(@NonNull Drawable icon, UserHandle user,
            boolean shrinkNonAdaptiveIcons, boolean isInstantApp, float[] scale) {
        if (scale == null) {
            scale = new float[1];
        }
        icon = normalizeAndWrapToAdaptiveIcon(icon, shrinkNonAdaptiveIcons, null, scale);
        Bitmap bitmap = createIconBitmap(icon, scale[0]);
        if (ATLEAST_OREO && icon instanceof AdaptiveIconDrawable) {
            mCanvas.setBitmap(bitmap);
            getShadowGenerator().recreateIcon(Bitmap.createBitmap(bitmap), mCanvas);
            mCanvas.setBitmap(null);
        }

        if (isInstantApp) {
            badgeWithDrawable(bitmap, mContext.getDrawable(R.drawable.ic_instant_app_badge));
        }
        if (user != null) {
            BitmapDrawable drawable = new FixedSizeBitmapDrawable(bitmap);
            Drawable badged = mPm.getUserBadgedIcon(drawable, user);
            if (badged instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) badged).getBitmap();
            } else {
                bitmap = createIconBitmap(badged, 1f);
            }
        }
        int color = extractColor(bitmap);
        return icon instanceof BitmapInfo.Extender
                ? ((BitmapInfo.Extender) icon).getExtendedInfo(bitmap, color, this, scale[0], user)
                : BitmapInfo.of(bitmap, color);
    }

    public Bitmap getUserBadgeBitmap(UserHandle user) {
        if (mUserBadgeBitmap == null) {
            Bitmap bitmap = Bitmap.createBitmap(
                    mIconBitmapSize, mIconBitmapSize, Bitmap.Config.ARGB_8888);
            Drawable badgedDrawable = mPm.getUserBadgedIcon(
                    new FixedSizeBitmapDrawable(bitmap), user);
            if (badgedDrawable instanceof BitmapDrawable) {
                mUserBadgeBitmap = ((BitmapDrawable) badgedDrawable).getBitmap();
            } else {
                badgedDrawable.setBounds(0, 0, mIconBitmapSize, mIconBitmapSize);
                mUserBadgeBitmap = BitmapRenderer.createSoftwareBitmap(
                        mIconBitmapSize, mIconBitmapSize, badgedDrawable::draw);
            }
        }
        return mUserBadgeBitmap;
    }

    public Bitmap createScaledBitmapWithoutShadow(Drawable icon, boolean shrinkNonAdaptiveIcons) {
        RectF iconBounds = new RectF();
        float[] scale = new float[1];
        icon = normalizeAndWrapToAdaptiveIcon(icon, shrinkNonAdaptiveIcons, iconBounds, scale);
        return createIconBitmap(icon,
                Math.min(scale[0], ShadowGenerator.getScaleForBounds(iconBounds)));
    }

    /**
     * Switches badging to left/right
     */
    public void setBadgeOnLeft(boolean badgeOnLeft) {
        mBadgeOnLeft = badgeOnLeft;
    }

    /**
     * Sets the background color used for wrapped adaptive icon
     */
    public void setWrapperBackgroundColor(int color) {
        mWrapperBackgroundColor = (Color.alpha(color) < 255) ? DEFAULT_WRAPPER_BACKGROUND : color;
    }

    /**
     * Disables the dominant color extraction for all icons loaded.
     */
    public void disableColorExtraction() {
        mDisableColorExtractor = true;
    }

    private Drawable normalizeAndWrapToAdaptiveIcon(@NonNull Drawable icon,
            boolean shrinkNonAdaptiveIcons, RectF outIconBounds, float[] outScale) {
        if (icon == null) {
            return null;
        }
        float scale = 1f;

        if (shrinkNonAdaptiveIcons && ATLEAST_OREO) {
            if (mWrapperIcon == null) {
                mWrapperIcon = mContext.getDrawable(R.drawable.adaptive_icon_drawable_wrapper)
                        .mutate();
            }
            AdaptiveIconDrawable dr = (AdaptiveIconDrawable) mWrapperIcon;
            dr.setBounds(0, 0, 1, 1);
            boolean[] outShape = new boolean[1];
            scale = getNormalizer().getScale(icon, outIconBounds, dr.getIconMask(), outShape);
            if (!(icon instanceof AdaptiveIconDrawable) && !outShape[0]) {
                FixedScaleDrawable fsd = ((FixedScaleDrawable) dr.getForeground());
                fsd.setDrawable(icon);
                fsd.setScale(scale);
                icon = dr;
                scale = getNormalizer().getScale(icon, outIconBounds, null, null);

                ((ColorDrawable) dr.getBackground()).setColor(mWrapperBackgroundColor);
            }
        } else {
            scale = getNormalizer().getScale(icon, outIconBounds, null, null);
        }

        outScale[0] = scale;
        return icon;
    }

    /**
     * Adds the {@param badge} on top of {@param target} using the badge dimensions.
     */
    public void badgeWithDrawable(Bitmap target, Drawable badge) {
        mCanvas.setBitmap(target);
        badgeWithDrawable(mCanvas, badge);
        mCanvas.setBitmap(null);
    }

    /**
     * Adds the {@param badge} on top of {@param target} using the badge dimensions.
     */
    public void badgeWithDrawable(Canvas target, Drawable badge) {
        int badgeSize = getBadgeSizeForIconSize(mIconBitmapSize);
        if (mBadgeOnLeft) {
            badge.setBounds(0, mIconBitmapSize - badgeSize, badgeSize, mIconBitmapSize);
        } else {
            badge.setBounds(mIconBitmapSize - badgeSize, mIconBitmapSize - badgeSize,
                    mIconBitmapSize, mIconBitmapSize);
        }
        badge.draw(target);
    }

    private Bitmap createIconBitmap(Drawable icon, float scale) {
        return createIconBitmap(icon, scale, mIconBitmapSize);
    }

    /**
     * @param icon drawable that should be flattened to a bitmap
     * @param scale the scale to apply before drawing {@param icon} on the canvas
     */
    public Bitmap createIconBitmap(@NonNull Drawable icon, float scale, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (icon == null) {
            return bitmap;
        }
        mCanvas.setBitmap(bitmap);
        mOldBounds.set(icon.getBounds());

        if (ATLEAST_OREO && icon instanceof AdaptiveIconDrawable) {
            int offset = Math.max((int) Math.ceil(BLUR_FACTOR * size),
                    Math.round(size * (1 - scale) / 2 ));
            icon.setBounds(offset, offset, size - offset, size - offset);
            if (icon instanceof BitmapInfo.Extender) {
                ((Extender) icon).drawForPersistence(mCanvas);
            } else {
                icon.draw(mCanvas);
            }
        } else {
            if (icon instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap b = bitmapDrawable.getBitmap();
                if (bitmap != null && b.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(mContext.getResources().getDisplayMetrics());
                }
            }
            int width = size;
            int height = size;

            int intrinsicWidth = icon.getIntrinsicWidth();
            int intrinsicHeight = icon.getIntrinsicHeight();
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                final float ratio = (float) intrinsicWidth / intrinsicHeight;
                if (intrinsicWidth > intrinsicHeight) {
                    height = (int) (width / ratio);
                } else if (intrinsicHeight > intrinsicWidth) {
                    width = (int) (height * ratio);
                }
            }
            final int left = (size - width) / 2;
            final int top = (size - height) / 2;
            icon.setBounds(left, top, left + width, top + height);
            mCanvas.save();
            mCanvas.scale(scale, scale, size / 2, size / 2);
            icon.draw(mCanvas);
            mCanvas.restore();

        }
        icon.setBounds(mOldBounds);
        mCanvas.setBitmap(null);
        return bitmap;
    }

    @Override
    public void close() {
        clear();
    }

    public BitmapInfo makeDefaultIcon(UserHandle user) {
        return createBadgedIconBitmap(getFullResDefaultActivityIcon(mFillResIconDpi),
                user, Build.VERSION.SDK_INT);
    }

    public static Drawable getFullResDefaultActivityIcon(int iconDpi) {
        return Resources.getSystem().getDrawableForDensity(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? android.R.drawable.sym_def_app_icon : android.R.mipmap.sym_def_app_icon,
                iconDpi);
    }

    /**
     * Badges the provided source with the badge info
     */
    public BitmapInfo badgeBitmap(Bitmap source, BitmapInfo badgeInfo) {
        Bitmap icon = BitmapRenderer.createHardwareBitmap(mIconBitmapSize, mIconBitmapSize, (c) -> {
            getShadowGenerator().recreateIcon(source, c);
            badgeWithDrawable(c, new FixedSizeBitmapDrawable(badgeInfo.icon));
        });
        return BitmapInfo.of(icon, badgeInfo.color);
    }

    private int extractColor(Bitmap bitmap) {
        return mDisableColorExtractor ? 0 : mColorExtractor.findDominantColorByHue(bitmap);
    }

    /**
     * Returns the correct badge size given an icon size
     */
    public static int getBadgeSizeForIconSize(int iconSize) {
        return (int) (ICON_BADGE_SCALE * iconSize);
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the action bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        public FixedSizeBitmapDrawable(Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }
}
