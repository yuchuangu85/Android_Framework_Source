/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics.perftests;

import static org.junit.Assume.assumeNotNull;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.FontVariationAxis;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.Preconditions;
import com.android.perftests.core.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TypefaceCreatePerfTest {
    // A font file name in asset directory.
    private static final String TEST_FONT_NAME = "DancingScript-Regular.ttf";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testCreate_fromFamily() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            Typeface face = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
        }
    }

    @Test
    public void testCreate_fromFamilyName() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            Typeface face = Typeface.create("monospace", Typeface.NORMAL);
        }
    }

    @Test
    public void testCreate_fromAsset() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getContext();
        final AssetManager am = context.getAssets();

        while (state.keepRunning()) {
            Typeface face = createFromNonAsset(am, TEST_FONT_NAME);
        }
    }

    /**
     * {@link AssetManager#openNonAsset(String)} variant of
     * {@link Typeface#createFromAsset(AssetManager, String)}.
     */
    private static Typeface createFromNonAsset(AssetManager mgr, String path) {
        Preconditions.checkNotNull(path); // for backward compatibility
        Preconditions.checkNotNull(mgr);

        Typeface typeface = new Typeface.Builder(mgr, path).build();
        if (typeface != null) return typeface;
        // check if the file exists, and throw an exception for backward compatibility
        //noinspection EmptyTryBlock
        try (InputStream inputStream = mgr.openNonAsset(path)) {
            // Purposely empty
        } catch (IOException e) {
            throw new RuntimeException("Font asset not found " + path);
        }

        return Typeface.DEFAULT;
    }

    @Test
    public void testCreate_fromFile() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getContext();
        final AssetManager am = context.getAssets();

        File outFile = null;
        try {
            outFile = File.createTempFile("example", "ttf", context.getCacheDir());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (InputStream in = am.openNonAsset(TEST_FONT_NAME);
                OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[1024];
            int n = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        while (state.keepRunning()) {
            Typeface face = Typeface.createFromFile(outFile);
        }

        outFile.delete();
    }

    @Test
    public void testCreate_fromResources() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Resources r = InstrumentationRegistry.getContext().getResources();

        while (state.keepRunning()) {
            Typeface face = r.getFont(R.font.samplefont);
        }
    }

    @Test
    public void testCreate_fromTypefaceWithVariation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final List<FontVariationAxis> axes = Arrays.asList(
                new FontVariationAxis("wght", 100f),
                new FontVariationAxis("wdth", 0.8f));

        while (state.keepRunning()) {
            Typeface face = Typeface.createFromTypefaceWithVariation(Typeface.SANS_SERIF, axes);
            // Make sure that the test device has variable fonts.
            assumeNotNull(face);
        }
    }

}
