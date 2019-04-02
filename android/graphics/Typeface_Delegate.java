/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.SdkConstants;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.android.RenderParamsFlags;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.FontResourcesParser;
import android.graphics.FontFamily_Delegate.FontVariant;
import android.graphics.fonts.FontVariationAxis;
import android.text.FontConfig;
import android.util.ArrayMap;

import java.awt.Font;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;

import static android.graphics.FontFamily_Delegate.getFontLocation;

/**
 * Delegate implementing the native methods of android.graphics.Typeface
 * <p>
 * Through the layoutlib_create tool, the original native methods of Typeface have been replaced by
 * calls to methods of the same name in this delegate class.
 * <p>
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between it
 * and the original Typeface class.
 *
 * @see DelegateManager
 */
public final class Typeface_Delegate {

    public static final String SYSTEM_FONTS = "/system/fonts/";

    // ---- delegate manager ----
    private static final DelegateManager<Typeface_Delegate> sManager =
            new DelegateManager<>(Typeface_Delegate.class);


    // ---- delegate data ----
    private static long sDefaultTypeface;
    @NonNull
    private final FontFamily_Delegate[] mFontFamilies;  // the reference to FontFamily_Delegate.
    /** @see Font#getStyle() */
    private final int mStyle;
    private final int mWeight;
    private SoftReference<EnumMap<FontVariant, List<Font>>> mFontsCache = new SoftReference<>(null);


    // ---- Public Helper methods ----

    public Typeface_Delegate(@NonNull FontFamily_Delegate[] fontFamilies, int style, int weight) {
        mFontFamilies = fontFamilies;
        mStyle = style;
        mWeight = weight;
    }

    public static Typeface_Delegate getDelegate(long nativeTypeface) {
        return sManager.getDelegate(nativeTypeface);
    }

    /**
     * Clear the default typefaces when disposing bridge.
     */
    public static void resetDefaults() {
        // Sometimes this is called before the Bridge is initialized. In that case, we don't want to
        // initialize Typeface because the SDK fonts location hasn't been set.
        if (FontFamily_Delegate.getFontLocation() != null) {
            Typeface.sDefaults = null;
        }
    }


    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static synchronized long nativeCreateFromTypeface(long native_instance, int style) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            delegate = sManager.getDelegate(sDefaultTypeface);
        }
        if (delegate == null) {
            return 0;
        }

        return sManager.addNewDelegate(
                new Typeface_Delegate(delegate.mFontFamilies, style, delegate.mWeight));
    }

    @LayoutlibDelegate
    /*package*/ static long nativeCreateFromTypefaceWithExactStyle(long native_instance, int weight,
            boolean italic) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            delegate = sManager.getDelegate(sDefaultTypeface);
        }
        if (delegate == null) {
            return 0;
        }

        int style = weight >= 600 ? (italic ? Typeface.BOLD_ITALIC : Typeface.BOLD) :
                (italic ? Typeface.ITALIC : Typeface.NORMAL);
        return sManager.addNewDelegate(
                new Typeface_Delegate(delegate.mFontFamilies, style, weight));
    }

    @LayoutlibDelegate
    /*package*/ static synchronized long nativeCreateFromTypefaceWithVariation(long native_instance,
            List<FontVariationAxis> axes) {
        long newInstance = nativeCreateFromTypeface(native_instance, 0);

        if (newInstance != 0) {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                    "nativeCreateFromTypefaceWithVariation is not supported", null, null);
        }
        return newInstance;
    }

    @LayoutlibDelegate
    /*package*/ static synchronized int[] nativeGetSupportedAxes(long native_instance) {
        // nativeCreateFromTypefaceWithVariation is not supported so we do not keep the axes
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static long nativeCreateWeightAlias(long native_instance, int weight) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            delegate = sManager.getDelegate(sDefaultTypeface);
        }
        if (delegate == null) {
            return 0;
        }
        Typeface_Delegate weightAlias =
                new Typeface_Delegate(delegate.mFontFamilies, delegate.mStyle, weight);
        return sManager.addNewDelegate(weightAlias);
    }

    @LayoutlibDelegate
    /*package*/ static synchronized long nativeCreateFromArray(long[] familyArray, int weight,
            int italic) {
        FontFamily_Delegate[] fontFamilies = new FontFamily_Delegate[familyArray.length];
        for (int i = 0; i < familyArray.length; i++) {
            fontFamilies[i] = FontFamily_Delegate.getDelegate(familyArray[i]);
        }
        if (weight == Typeface.RESOLVE_BY_FONT_TABLE) {
            weight = 400;
        }
        if (italic == Typeface.RESOLVE_BY_FONT_TABLE) {
            italic = 0;
        }
        int style = weight >= 600 ? (italic == 1 ? Typeface.BOLD_ITALIC : Typeface.BOLD) :
                (italic == 1 ? Typeface.ITALIC : Typeface.NORMAL);
        Typeface_Delegate delegate = new Typeface_Delegate(fontFamilies, style, weight);
        return sManager.addNewDelegate(delegate);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeUnref(long native_instance) {
        sManager.removeJavaReferenceFor(native_instance);
    }

    @LayoutlibDelegate
    /*package*/ static int nativeGetStyle(long native_instance) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            return 0;
        }

        return delegate.mStyle;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetDefault(long native_instance) {
        sDefaultTypeface = native_instance;
    }

    @LayoutlibDelegate
    /*package*/ static int nativeGetWeight(long native_instance) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            return 0;
        }
        return delegate.mWeight;
    }

    @LayoutlibDelegate
    /*package*/ static void buildSystemFallback(String xmlPath, String fontDir,
            ArrayMap<String, Typeface> fontMap, ArrayMap<String, FontFamily[]> fallbackMap) {
        Typeface.buildSystemFallback_Original(getFontLocation() + "/fonts.xml", fontDir, fontMap,
                fallbackMap);
    }

    @LayoutlibDelegate
    /*package*/ static FontFamily createFontFamily(String familyName, List<FontConfig.Font> fonts,
            String[] languageTags, int variant, Map<String, ByteBuffer> cache, String fontDir) {
        FontFamily fontFamily = new FontFamily(languageTags, variant);
        for (FontConfig.Font font : fonts) {
            String fullPathName = fontDir + font.getFontName();
            FontFamily_Delegate.addFont(fontFamily.mBuilderPtr, fullPathName, font.getWeight(),
                    font.isItalic());
        }
        fontFamily.freeze();
        return fontFamily;
    }

    /**
     * Loads a single font or font family from disk
     */
    @Nullable
    public static Typeface createFromDisk(@NonNull BridgeContext context, @NonNull String path,
            boolean isFramework) {
        // Check if this is an asset that we've already loaded dynamically
        Typeface typeface = Typeface.findFromCache(context.getAssets(), path);
        if (typeface != null) {
            return typeface;
        }

        String lowerCaseValue = path.toLowerCase();
        if (lowerCaseValue.endsWith(SdkConstants.DOT_XML)) {
            // create a block parser for the file
            Boolean psiParserSupport = context.getLayoutlibCallback().getFlag(
                    RenderParamsFlags.FLAG_KEY_XML_FILE_PARSER_SUPPORT);
            XmlPullParser parser = null;
            if (psiParserSupport != null && psiParserSupport) {
                parser = context.getLayoutlibCallback().getXmlFileParser(path);
            } else {
                File f = new File(path);
                if (f.isFile()) {
                    try {
                        parser = ParserFactory.create(f);
                    } catch (XmlPullParserException | FileNotFoundException e) {
                        // this is an error and not warning since the file existence is checked
                        // before
                        // attempting to parse it.
                        Bridge.getLog().error(null, "Failed to parse file " + path, e,
                                null /*data*/);
                    }
                }
            }

            if (parser != null) {
                BridgeXmlBlockParser blockParser =
                        new BridgeXmlBlockParser(parser, context, isFramework);
                try {
                    FontResourcesParser.FamilyResourceEntry entry =
                            FontResourcesParser.parse(blockParser, context.getResources());
                    typeface = Typeface.createFromResources(entry, context.getAssets(), path);
                } catch (XmlPullParserException | IOException e) {
                    Bridge.getLog().error(null, "Failed to parse file " + path, e, null /*data*/);
                } finally {
                    blockParser.ensurePopped();
                }
            } else {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        String.format("File %s does not exist (or is not a file)", path),
                        null /*data*/);
            }
        } else {
            typeface = Typeface.createFromResources(context.getAssets(), path, 0);
        }

        return typeface;
    }

    @LayoutlibDelegate
    /*package*/ static Typeface create(String familyName, int style) {
        if (familyName != null && Files.exists(Paths.get(familyName))) {
            // Workaround for b/64137851
            // Support lib will call this method after failing to create the TypefaceCompat.
            return Typeface_Delegate.createFromDisk(RenderAction.getCurrentContext(), familyName,
                    false);
        }
        return Typeface.create_Original(familyName, style);
    }

    @LayoutlibDelegate
    /*package*/ static Typeface create(Typeface family, int style) {
        return Typeface.create_Original(family, style);
    }

    @LayoutlibDelegate
    /*package*/ static Typeface create(Typeface family, int style, boolean isItalic) {
        return Typeface.create_Original(family, style, isItalic);
    }

    // ---- Private delegate/helper methods ----

    private static List<Font> computeFonts(FontVariant variant, FontFamily_Delegate[] fontFamilies,
            int inputWeight, int inputStyle) {
        // Calculate the required weight based on style and weight of this typeface.
        int weight = inputWeight + 50 +
                ((inputStyle & Font.BOLD) == 0 ? 0 : FontFamily_Delegate.BOLD_FONT_WEIGHT_DELTA);
        if (weight > 1000) {
            weight = 1000;
        } else if (weight < 100) {
            weight = 100;
        }
        final boolean isItalic = (inputStyle & Font.ITALIC) != 0;
        List<Font> fonts = new ArrayList<Font>(fontFamilies.length);
        for (int i = 0; i < fontFamilies.length; i++) {
            FontFamily_Delegate ffd = fontFamilies[i];
            if (ffd != null && ffd.isValid()) {
                Font font = ffd.getFont(weight, isItalic);
                if (font != null) {
                    FontVariant ffdVariant = ffd.getVariant();
                    if (ffdVariant == FontVariant.NONE) {
                        fonts.add(font);
                        continue;
                    }
                    // We cannot open each font and get locales supported, etc to match the fonts.
                    // As a workaround, we hardcode certain assumptions like Elegant and Compact
                    // always appear in pairs.
                    assert i < fontFamilies.length - 1;
                    FontFamily_Delegate ffd2 = fontFamilies[++i];
                    assert ffd2 != null;
                    FontVariant ffd2Variant = ffd2.getVariant();
                    Font font2 = ffd2.getFont(weight, isItalic);
                    assert ffd2Variant != FontVariant.NONE && ffd2Variant != ffdVariant &&
                            font2 != null;
                    // Add the font with the matching variant to the list.
                    if (variant == ffd.getVariant()) {
                        fonts.add(font);
                    } else {
                        fonts.add(font2);
                    }
                } else {
                    // The FontFamily is valid but doesn't contain any matching font. This means
                    // that the font failed to load. We add null to the list of fonts. Don't throw
                    // the warning just yet. If this is a non-english font, we don't want to warn
                    // users who are trying to render only english text.
                    fonts.add(null);
                }
            }
        }

        return fonts;
    }

    /**
     * Return an Iterable of fonts that match the style and variant. The list is ordered
     * according to preference of fonts.
     * <p>
     * The Iterator may contain null when the font failed to load. If null is reached when trying to
     * render with this list of fonts, then a warning should be logged letting the user know that
     * some font failed to load.
     *
     * @param variant The variant preferred. Can only be {@link FontVariant#COMPACT} or {@link
     * FontVariant#ELEGANT}
     */
    @NonNull
    public Iterable<Font> getFonts(final FontVariant variant) {
        assert variant != FontVariant.NONE;

        return new FontsIterator(mFontFamilies, variant, mWeight, mStyle);
    }

    private static class FontsIterator implements Iterator<Font>, Iterable<Font> {
        private final FontFamily_Delegate[] fontFamilies;
        private final int weight;
        private final boolean isItalic;
        private final FontVariant variant;

        private int index = 0;

        private FontsIterator(@NonNull FontFamily_Delegate[] fontFamilies,
                @NonNull FontVariant variant, int weight, int style) {
            // Calculate the required weight based on style and weight of this typeface.
            int boldExtraWeight =
                    ((style & Font.BOLD) == 0 ? 0 : FontFamily_Delegate.BOLD_FONT_WEIGHT_DELTA);
            this.weight = Math.min(Math.max(100, weight + 50 + boldExtraWeight), 1000);
            this.isItalic = (style & Font.ITALIC) != 0;
            this.fontFamilies = fontFamilies;
            this.variant = variant;
        }

        @Override
        public boolean hasNext() {
            return index < fontFamilies.length;
        }

        @Override
        @Nullable
        public Font next() {
            FontFamily_Delegate ffd = fontFamilies[index++];
            if (ffd == null || !ffd.isValid()) {
                return null;
            }

            Font font = ffd.getFont(weight, isItalic);
            if (font == null) {
                // The FontFamily is valid but doesn't contain any matching font. This means
                // that the font failed to load. We add null to the list of fonts. Don't throw
                // the warning just yet. If this is a non-english font, we don't want to warn
                // users who are trying to render only english text.
                return null;
            }

            FontVariant ffdVariant = ffd.getVariant();
            if (ffdVariant == FontVariant.NONE) {
                return font;
            }

            // We cannot open each font and get locales supported, etc to match the fonts.
            // As a workaround, we hardcode certain assumptions like Elegant and Compact
            // always appear in pairs.
            assert index < fontFamilies.length - 1;
            FontFamily_Delegate ffd2 = fontFamilies[index++];
            assert ffd2 != null;

            if (ffdVariant == variant) {
                return font;
            }

            FontVariant ffd2Variant = ffd2.getVariant();
            Font font2 = ffd2.getFont(weight, isItalic);
            assert ffd2Variant != FontVariant.NONE && ffd2Variant != ffdVariant && font2 != null;
            // Add the font with the matching variant to the list.
            return variant == ffd.getVariant() ? font : font2;
        }

        @NonNull
        @Override
        public Iterator<Font> iterator() {
            return this;
        }

        @Override
        public Spliterator<Font> spliterator() {
            return Spliterators.spliterator(iterator(), fontFamilies.length,
                    Spliterator.IMMUTABLE | Spliterator.SIZED);
        }
    }
}
