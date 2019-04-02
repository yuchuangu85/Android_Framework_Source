/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.media;

import static android.media.MediaMetadata2.*;

import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.media.MediaMetadata2;
import android.media.MediaMetadata2.BitmapKey;
import android.media.MediaMetadata2.Builder;
import android.media.MediaMetadata2.LongKey;
import android.media.MediaMetadata2.RatingKey;
import android.media.MediaMetadata2.TextKey;
import android.media.Rating2;
import android.media.update.MediaMetadata2Provider;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Set;

public class MediaMetadata2Impl implements MediaMetadata2Provider {
    private static final String TAG = "MediaMetadata2";

    static final int METADATA_TYPE_LONG = 0;
    static final int METADATA_TYPE_TEXT = 1;
    static final int METADATA_TYPE_BITMAP = 2;
    static final int METADATA_TYPE_RATING = 3;
    static final int METADATA_TYPE_FLOAT = 4;
    static final ArrayMap<String, Integer> METADATA_KEYS_TYPE;

    static {
        METADATA_KEYS_TYPE = new ArrayMap<String, Integer>();
        METADATA_KEYS_TYPE.put(METADATA_KEY_TITLE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ARTIST, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DURATION, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_AUTHOR, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_WRITER, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMPOSER, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_COMPILATION, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DATE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_YEAR, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_GENRE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_TRACK_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_NUM_TRACKS, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISC_NUMBER, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ARTIST, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ART, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ART_URI, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART_URI, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_USER_RATING, METADATA_TYPE_RATING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_RATING, METADATA_TYPE_RATING);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_TITLE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_SUBTITLE, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_DESCRIPTION, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_ICON, METADATA_TYPE_BITMAP);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_ICON_URI, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_MEDIA_ID, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_BT_FOLDER_TYPE, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_MEDIA_URI, METADATA_TYPE_TEXT);
        METADATA_KEYS_TYPE.put(METADATA_KEY_ADVERTISEMENT, METADATA_TYPE_LONG);
        METADATA_KEYS_TYPE.put(METADATA_KEY_DOWNLOAD_STATUS, METADATA_TYPE_LONG);
    }

    private static final @TextKey
    String[] PREFERRED_DESCRIPTION_ORDER = {
            METADATA_KEY_TITLE,
            METADATA_KEY_ARTIST,
            METADATA_KEY_ALBUM,
            METADATA_KEY_ALBUM_ARTIST,
            METADATA_KEY_WRITER,
            METADATA_KEY_AUTHOR,
            METADATA_KEY_COMPOSER
    };

    private static final @BitmapKey
    String[] PREFERRED_BITMAP_ORDER = {
            METADATA_KEY_DISPLAY_ICON,
            METADATA_KEY_ART,
            METADATA_KEY_ALBUM_ART
    };

    private static final @TextKey
    String[] PREFERRED_URI_ORDER = {
            METADATA_KEY_DISPLAY_ICON_URI,
            METADATA_KEY_ART_URI,
            METADATA_KEY_ALBUM_ART_URI
    };

    private final MediaMetadata2 mInstance;
    private final Bundle mBundle;

    public MediaMetadata2Impl(Bundle bundle) {
        mInstance = new MediaMetadata2(this);
        mBundle = bundle;
    }

    public MediaMetadata2 getInstance() {
        return mInstance;
    }

    @Override
    public boolean containsKey_impl(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        return mBundle.containsKey(key);
    }

    @Override
    public CharSequence getText_impl(@TextKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        return mBundle.getCharSequence(key);
    }

    @Override
    public @Nullable String getMediaId_impl() {
        return mInstance.getString(METADATA_KEY_MEDIA_ID);
    }

    @Override
    public String getString_impl(@TextKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        CharSequence text = mBundle.getCharSequence(key);
        if (text != null) {
            return text.toString();
        }
        return null;
    }

    @Override
    public long getLong_impl(@LongKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        return mBundle.getLong(key, 0);
    }

    @Override
    public Rating2 getRating_impl(@RatingKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        // TODO(jaewan): Add backward compatibility
        Rating2 rating = null;
        try {
            rating = Rating2.fromBundle(mBundle.getBundle(key));
        } catch (Exception e) {
            // ignore, value was not a rating
            Log.w(TAG, "Failed to retrieve a key as Rating.", e);
        }
        return rating;
    }

    @Override
    public float getFloat_impl(@FloatKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        return mBundle.getFloat(key);
    }

    @Override
    public Bitmap getBitmap_impl(@BitmapKey String key) {
        if (key == null) {
            throw new IllegalArgumentException("key shouldn't be null");
        }
        Bitmap bmp = null;
        try {
            bmp = mBundle.getParcelable(key);
        } catch (Exception e) {
            // ignore, value was not a bitmap
            Log.w(TAG, "Failed to retrieve a key as Bitmap.", e);
        }
        return bmp;
    }

    @Override
    public Bundle getExtras_impl() {
        try {
            return mBundle.getBundle(METADATA_KEY_EXTRAS);
        } catch (Exception e) {
            // ignore, value was not an bundle
            Log.w(TAG, "Failed to retrieve an extra");
        }
        return null;
    }

    @Override
    public int size_impl() {
        return mBundle.size();
    }

    @Override
    public Set<String> keySet_impl() {
        return mBundle.keySet();
    }

    @Override
    public Bundle toBundle_impl() {
        return mBundle;
    }

    public static MediaMetadata2 fromBundle_impl(Bundle bundle) {
        return (bundle == null) ? null : new MediaMetadata2Impl(bundle).getInstance();
    }

    public static final class BuilderImpl implements MediaMetadata2Provider.BuilderProvider {
        private final MediaMetadata2.Builder mInstance;
        private final Bundle mBundle;

        public BuilderImpl(MediaMetadata2.Builder instance) {
            mInstance = instance;
            mBundle = new Bundle();
        }

        public BuilderImpl(MediaMetadata2.Builder instance, MediaMetadata2 source) {
            if (source == null) {
                throw new IllegalArgumentException("source shouldn't be null");
            }
            mInstance = instance;
            mBundle = new Bundle(source.toBundle());
        }

        public BuilderImpl(int maxBitmapSize) {
            mInstance = new MediaMetadata2.Builder(this);
            mBundle = new Bundle();

            for (String key : mBundle.keySet()) {
                Object value = mBundle.get(key);
                if (value instanceof Bitmap) {
                    Bitmap bmp = (Bitmap) value;
                    if (bmp.getHeight() > maxBitmapSize || bmp.getWidth() > maxBitmapSize) {
                        mInstance.putBitmap(key, scaleBitmap(bmp, maxBitmapSize));
                    }
                }
            }
        }

        @Override
        public Builder putText_impl(@TextKey String key, CharSequence value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_TEXT) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a CharSequence");
                }
            }
            mBundle.putCharSequence(key, value);
            return mInstance;
        }

        @Override
        public Builder putString_impl(@TextKey String key, String value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_TEXT) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a String");
                }
            }
            mBundle.putCharSequence(key, value);
            return mInstance;
        }

        @Override
        public Builder putLong_impl(@LongKey String key, long value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_LONG) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a long");
                }
            }
            mBundle.putLong(key, value);
            return mInstance;
        }

        @Override
        public Builder putRating_impl(@RatingKey String key, Rating2 value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_RATING) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a Rating");
                }
            }
            mBundle.putBundle(key, value.toBundle());
            return mInstance;
        }

        @Override
        public Builder putBitmap_impl(@BitmapKey String key, Bitmap value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_BITMAP) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a Bitmap");
                }
            }
            mBundle.putParcelable(key, value);
            return mInstance;
        }

        @Override
        public Builder putFloat_impl(@FloatKey String key, float value) {
            if (key == null) {
                throw new IllegalArgumentException("key shouldn't be null");
            }
            if (METADATA_KEYS_TYPE.containsKey(key)) {
                if (METADATA_KEYS_TYPE.get(key) != METADATA_TYPE_FLOAT) {
                    throw new IllegalArgumentException("The " + key
                            + " key cannot be used to put a float");
                }
            }
            mBundle.putFloat(key, value);
            return mInstance;
        }

        @Override
        public Builder setExtras_impl(Bundle bundle) {
            mBundle.putBundle(METADATA_KEY_EXTRAS, bundle);
            return mInstance;
        }

        @Override
        public MediaMetadata2 build_impl() {
            return new MediaMetadata2Impl(mBundle).getInstance();
        }

        private Bitmap scaleBitmap(Bitmap bmp, int maxSize) {
            float maxSizeF = maxSize;
            float widthScale = maxSizeF / bmp.getWidth();
            float heightScale = maxSizeF / bmp.getHeight();
            float scale = Math.min(widthScale, heightScale);
            int height = (int) (bmp.getHeight() * scale);
            int width = (int) (bmp.getWidth() * scale);
            return Bitmap.createScaledBitmap(bmp, width, height, true);
        }
    }
}

