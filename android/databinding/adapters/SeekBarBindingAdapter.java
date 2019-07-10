/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.databinding.adapters;

import android.databinding.BindingAdapter;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

public class SeekBarBindingAdapter {
    @BindingAdapter("android:onProgressChanged")
    public static void setListener(SeekBar view, OnProgressChanged listener) {
        setListener(view, null, null, listener);
    }

    @BindingAdapter("android:onStartTrackingTouch")
    public static void setListener(SeekBar view, OnStartTrackingTouch listener) {
        setListener(view, listener, null, null);
    }

    @BindingAdapter("android:onStopTrackingTouch")
    public static void setListener(SeekBar view, OnStopTrackingTouch listener) {
        setListener(view, null, listener, null);
    }

    @BindingAdapter({"android:onStartTrackingTouch", "android:onStopTrackingTouch"})
    public static void setListener(SeekBar view, final OnStartTrackingTouch start,
            final OnStopTrackingTouch stop) {
        setListener(view, start, stop, null);
    }

    @BindingAdapter({"android:onStartTrackingTouch", "android:onProgressChanged"})
    public static void setListener(SeekBar view, final OnStartTrackingTouch start,
            final OnProgressChanged progressChanged) {
        setListener(view, start, null, progressChanged);
    }

    @BindingAdapter({"android:onStopTrackingTouch", "android:onProgressChanged"})
    public static void setListener(SeekBar view, final OnStopTrackingTouch stop,
            final OnProgressChanged progressChanged) {
        setListener(view, null, stop, progressChanged);
    }

    @BindingAdapter({"android:onStartTrackingTouch", "android:onStopTrackingTouch", "android:onProgressChanged"})
    public static void setListener(SeekBar view, final OnStartTrackingTouch start,
            final OnStopTrackingTouch stop, final OnProgressChanged progressChanged) {
        if (start == null && stop == null && progressChanged == null) {
            view.setOnSeekBarChangeListener(null);
        } else {
            view.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (progressChanged != null) {
                        progressChanged.onProgressChanged(seekBar, progress, fromUser);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (start != null) {
                        start.onStartTrackingTouch(seekBar);
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (stop != null) {
                        stop.onStopTrackingTouch(seekBar);
                    }
                }
            });
        }
    }

    public interface OnStartTrackingTouch {
        void onStartTrackingTouch(SeekBar seekBar);
    }

    public interface OnStopTrackingTouch {
        void onStopTrackingTouch(SeekBar seekBar);
    }

    public interface OnProgressChanged {
        void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser);
    }
}
