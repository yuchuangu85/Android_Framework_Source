/*
 * Copyright 2013 The Android Open Source Project
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

package androidx.media.filterfw.samples.simplecamera;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.MffFilterTestCase;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;


public class ExposureFilterTest extends MffFilterTestCase {

    private AssetManager assetMgr = null;
    @Override
    protected Filter createFilter(MffContext mffContext) {
        assetMgr = mffContext.getApplicationContext().getAssets();
        return new ExposureFilter(mffContext, "exposureFilter");
    }

    public void testExposureFilter() throws Exception{
        final int INPUT_WIDTH = 480;
        final int INPUT_HEIGHT = 640;
        FrameImage2D image =
                createFrame(FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_CPU),
                        new int[] {INPUT_WIDTH,INPUT_HEIGHT}).asFrameImage2D();

        Bitmap bitmap = BitmapFactory.decodeStream(assetMgr.open("0002_000390.jpg"));
        image.setBitmap(bitmap);

        injectInputFrame("image", image);
        process();
        final float EXPECTED_OVEREXPOSURE = 0.00757f;
        assertEquals(EXPECTED_OVEREXPOSURE, ((Float) getOutputFrame("overExposureRating").
                asFrameValue().getValue()).floatValue(), 0.001f);
        final float EXPECTED_UNDEREXPOSURE = 0.2077f;
        assertEquals(EXPECTED_UNDEREXPOSURE, ((Float) getOutputFrame("underExposureRating").
                asFrameValue().getValue()).floatValue(), 0.001f);
    }
}