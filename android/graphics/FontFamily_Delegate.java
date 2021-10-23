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

package android.graphics;

import com.android.ide.common.rendering.api.ILayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.fonts.FontVariationAxis;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Logger;

import libcore.util.NativeAllocationRegistry_Delegate;

import static android.graphics.Typeface.RESOLVE_BY_FONT_TABLE;
import static android.graphics.Typeface_Delegate.SYSTEM_FONTS;

/**
 * Delegate implementing the native methods of android.graphics.FontFamily
 *
 * Through the layoutlib_create tool, the original native methods of FontFamily have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original FontFamily class.
 *
 * @see DelegateManager
 */
public class FontFamily_Delegate {

    public static final int DEFAULT_FONT_WEIGHT = 400;
    public static final int BOLD_FONT_WEIGHT_DELTA = 300;
    public static final int BOLD_FONT_WEIGHT = 700;

    private static final String FONT_SUFFIX_ITALIC = "Italic.ttf";
    private static final String FN_ALL_FONTS_LIST = "fontsInSdk.txt";
    private static final String EXTENSION_OTF = ".otf";

    private static final int CACHE_SIZE = 10;
    // The cache has a drawback that if the font file changed after the font object was created,
    // we will not update it.
    private static final Map<String, FontInfo> sCache =
            new LinkedHashMap<String, FontInfo>(CACHE_SIZE) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FontInfo> eldest) {
            return size() > CACHE_SIZE;
        }

        @Override
        public FontInfo put(String key, FontInfo value) {
            // renew this entry.
            FontInfo removed = remove(key);
            super.put(key, value);
            return removed;
        }
    };

    /**
     * A class associating {@link Font} with its metadata.
     */
    public static final class FontInfo {
        @Nullable
        public Font mFont;
        public int mWeight;
        public boolean mIsItalic;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            FontInfo fontInfo = (FontInfo) o;
            return mWeight == fontInfo.mWeight && mIsItalic == fontInfo.mIsItalic;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWeight, mIsItalic);
        }

        @Override
        public String toString() {
            return "FontInfo{" + "mWeight=" + mWeight + ", mIsItalic=" + mIsItalic + '}';
        }
    }

    // ---- delegate manager ----
    private static final DelegateManager<FontFamily_Delegate> sManager =
            new DelegateManager<FontFamily_Delegate>(FontFamily_Delegate.class);
    private static long sFamilyFinalizer = -1;

    // ---- delegate helper data ----
    private static String sFontLocation;
    private static final List<FontFamily_Delegate> sPostInitDelegate = new
            ArrayList<FontFamily_Delegate>();
    private static Set<String> SDK_FONTS;


    // ---- delegate data ----

    // Order does not really matter but we use a LinkedHashMap to get reproducible results across
    // render calls
    private Map<FontInfo, Font> mFonts = new LinkedHashMap<>();

    /**
     * The variant of the Font Family - compact or elegant.
     * <p/>
     * 0 is unspecified, 1 is compact and 2 is elegant. This needs to be kept in sync with values in
     * android.graphics.FontFamily
     *
     * @see Paint#setElegantTextHeight(boolean)
     */
    private FontVariant mVariant;
    // List of runnables to process fonts after sFontLoader is initialized.
    private List<Runnable> mPostInitRunnables = new ArrayList<Runnable>();
    /** @see #isValid() */
    private boolean mValid = false;


    // ---- Public helper class ----

    public enum FontVariant {
        // The order needs to be kept in sync with android.graphics.FontFamily.
        NONE, COMPACT, ELEGANT
    }

    // ---- Public Helper methods ----

    public static FontFamily_Delegate getDelegate(long nativeFontFamily) {
        return sManager.getDelegate(nativeFontFamily);
    }

    public static synchronized void setFontLocation(String fontLocation) {
        sFontLocation = fontLocation;
        // init list of bundled fonts.
        File allFonts = new File(fontLocation, FN_ALL_FONTS_LIST);
        // Current number of fonts is 103. Use the next round number to leave scope for more fonts
        // in the future.
        Set<String> allFontsList = new HashSet<>(128);
        Scanner scanner = null;
        try {
            scanner = new Scanner(allFonts);
            while (scanner.hasNext()) {
                String name = scanner.next();
                // Skip font configuration files.
                if (!name.endsWith(".xml")) {
                    allFontsList.add(name);
                }
            }
        } catch (FileNotFoundException e) {
            Bridge.getLog().error(ILayoutLog.TAG_BROKEN,
                    "Unable to load the list of fonts. Try re-installing the SDK Platform from the SDK Manager.",
                    e, null, null);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
        SDK_FONTS = Collections.unmodifiableSet(allFontsList);
        for (FontFamily_Delegate fontFamily : sPostInitDelegate) {
            fontFamily.init();
        }
        sPostInitDelegate.clear();
    }

    @Nullable
    public Font getFont(int desiredWeight, boolean isItalic) {
        FontInfo desiredStyle = new FontInfo();
        desiredStyle.mWeight = desiredWeight;
        desiredStyle.mIsItalic = isItalic;

        Font cachedFont = mFonts.get(desiredStyle);
        if (cachedFont != null) {
            return cachedFont;
        }

        FontInfo bestFont = null;

        if (mFonts.size() == 1) {
            // No need to compute the match since we only have one candidate
            bestFont = mFonts.keySet().iterator().next();
        } else {
            int bestMatch = Integer.MAX_VALUE;

            for (FontInfo font : mFonts.keySet()) {
                int match = computeMatch(font, desiredStyle);
                if (match < bestMatch) {
                    bestMatch = match;
                    bestFont = font;
                    if (bestMatch == 0) {
                        break;
                    }
                }
            }
        }

        if (bestFont == null) {
            return null;
        }


        // Derive the font as required and add it to the list of Fonts.
        deriveFont(bestFont, desiredStyle);
        addFont(desiredStyle);
        return desiredStyle.mFont;
    }

    public FontVariant getVariant() {
        return mVariant;
    }

    /**
     * Returns if the FontFamily should contain any fonts. If this returns true and
     * {@link #getFont(int, boolean)} returns an empty list, it means that an error occurred while
     * loading the fonts. However, some fonts are deliberately skipped, for example they are not
     * bundled with the SDK. In such a case, this method returns false.
     */
    public boolean isValid() {
        return mValid;
    }

    private static Font loadFont(String path) {
        if (path.startsWith(SYSTEM_FONTS) ) {
            String relativePath = path.substring(SYSTEM_FONTS.length());
            File f = new File(sFontLocation, relativePath);

            try {
                return Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (Exception e) {
                if (path.endsWith(EXTENSION_OTF) && e instanceof FontFormatException) {
                    // If we aren't able to load an Open Type font, don't log a warning just yet.
                    // We wait for a case where font is being used. Only then we try to log the
                    // warning.
                    return null;
                }
                Bridge.getLog().fidelityWarning(ILayoutLog.TAG_BROKEN,
                        String.format("Unable to load font %1$s", relativePath),
                        e, null, null);
            }
        } else {
            Bridge.getLog().fidelityWarning(ILayoutLog.TAG_UNSUPPORTED,
                    "Only platform fonts located in " + SYSTEM_FONTS + "can be loaded.",
                    null, null, null);
        }

        return null;
    }

    @Nullable
    public static String getFontLocation() {
        return sFontLocation;
    }

    // ---- delegate methods ----
    @LayoutlibDelegate
    /*package*/ static boolean addFont(FontFamily thisFontFamily, String path, int ttcIndex,
            FontVariationAxis[] axes, int weight, int italic) {
        if (thisFontFamily.mBuilderPtr == 0) {
            assert false : "Unable to call addFont after freezing.";
            return false;
        }
        final FontFamily_Delegate delegate = getDelegate(thisFontFamily.mBuilderPtr);
        return delegate != null && delegate.addFont(path, ttcIndex, weight, italic);
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nInitBuilder(String lang, int variant) {
        // TODO: support lang. This is required for japanese locale.
        FontFamily_Delegate delegate = new FontFamily_Delegate();
        // variant can be 0, 1 or 2.
        assert variant < 3;
        delegate.mVariant = FontVariant.values()[variant];
        if (sFontLocation != null) {
            delegate.init();
        } else {
            sPostInitDelegate.add(delegate);
        }
        return sManager.addNewDelegate(delegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nCreateFamily(long builderPtr) {
        return builderPtr;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetFamilyReleaseFunc() {
        synchronized (FontFamily_Delegate.class) {
            if (sFamilyFinalizer == -1) {
                sFamilyFinalizer = NativeAllocationRegistry_Delegate.createFinalizer(
                        sManager::removeJavaReferenceFor);
            }
        }
        return sFamilyFinalizer;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nAddFont(long builderPtr, ByteBuffer font, int ttcIndex,
            int weight, int isItalic) {
        assert false : "The only client of this method has been overridden.";
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nAddFontWeightStyle(long builderPtr, ByteBuffer font,
            int ttcIndex, int weight, int isItalic) {
        assert false : "The only client of this method has been overridden.";
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void nAddAxisValue(long builderPtr, int tag, float value) {
        assert false : "The only client of this method has been overridden.";
    }

    static boolean addFont(long builderPtr, final String path, final int weight,
            final boolean isItalic) {
        final FontFamily_Delegate delegate = getDelegate(builderPtr);
        int italic = isItalic ? 1 : 0;
        if (delegate != null) {
            if (sFontLocation == null) {
                delegate.mPostInitRunnables.add(() -> delegate.addFont(path, weight, italic));
                return true;
            }
            return delegate.addFont(path, weight, italic);
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetBuilderReleaseFunc() {
        // Layoutlib uses the same reference for the builder and the font family,
        // so it should not release that reference at the builder stage.
        return -1;
    }

    // ---- private helper methods ----

    private void init() {
        for (Runnable postInitRunnable : mPostInitRunnables) {
            postInitRunnable.run();
        }
        mPostInitRunnables = null;
    }

    private boolean addFont(final String path, int ttcIndex, int weight, int italic) {
        // FIXME: support ttc fonts. Hack JRE??
        if (sFontLocation == null) {
            mPostInitRunnables.add(() -> addFont(path, weight, italic));
            return true;
        }
        return addFont(path, weight, italic);
    }

     private boolean addFont(@NonNull String path) {
         return addFont(path, DEFAULT_FONT_WEIGHT, path.endsWith(FONT_SUFFIX_ITALIC) ? 1 : RESOLVE_BY_FONT_TABLE);
     }

    private boolean addFont(@NonNull String path, int weight, int italic) {
        if (path.startsWith(SYSTEM_FONTS) &&
                !SDK_FONTS.contains(path.substring(SYSTEM_FONTS.length()))) {
            Logger.getLogger(FontFamily_Delegate.class.getSimpleName()).warning("Unable to load font " + path);
            return mValid = false;
        }
        // Set valid to true, even if the font fails to load.
        mValid = true;
        Font font = loadFont(path);
        if (font == null) {
            return false;
        }
        FontInfo fontInfo = new FontInfo();
        fontInfo.mFont = font;
        fontInfo.mWeight = weight;
        fontInfo.mIsItalic = italic == RESOLVE_BY_FONT_TABLE ? font.isItalic() : italic == 1;
        addFont(fontInfo);
        return true;
    }

    private boolean addFont(@NonNull FontInfo fontInfo) {
        return mFonts.putIfAbsent(fontInfo, fontInfo.mFont) == null;
    }

    /**
     * Compute matching metric between two styles - 0 is an exact match.
     */
    public static int computeMatch(@NonNull FontInfo font1, @NonNull FontInfo font2) {
        int score = Math.abs(font1.mWeight / 100 - font2.mWeight / 100);
        if (font1.mIsItalic != font2.mIsItalic) {
            score += 2;
        }
        return score;
    }

    /**
     * Try to derive a font from {@code srcFont} for the style in {@code outFont}.
     * <p/>
     * {@code outFont} is updated to reflect the style of the derived font.
     * @param srcFont the source font
     * @param outFont contains the desired font style. Updated to contain the derived font and
     *                its style
     */
    public static void deriveFont(@NonNull FontInfo srcFont, @NonNull FontInfo outFont) {
        int desiredWeight = outFont.mWeight;
        int srcWeight = srcFont.mWeight;
        assert srcFont.mFont != null;
        Font derivedFont = srcFont.mFont;
        int derivedStyle = 0;
        // Embolden the font if required.
        if (desiredWeight >= BOLD_FONT_WEIGHT && desiredWeight - srcWeight > BOLD_FONT_WEIGHT_DELTA / 2) {
            derivedStyle |= Font.BOLD;
            srcWeight += BOLD_FONT_WEIGHT_DELTA;
        }
        // Italicize the font if required.
        if (outFont.mIsItalic && !srcFont.mIsItalic) {
            derivedStyle |= Font.ITALIC;
        } else if (outFont.mIsItalic != srcFont.mIsItalic) {
            // The desired font is plain, but the src font is italics. We can't convert it back. So
            // we update the value to reflect the true style of the font we're deriving.
            outFont.mIsItalic = srcFont.mIsItalic;
        }

        if (derivedStyle != 0) {
            derivedFont = derivedFont.deriveFont(derivedStyle);
        }

        outFont.mFont = derivedFont;
        outFont.mWeight = srcWeight;
        // No need to update mIsItalics, as it's already been handled above.
    }
}
