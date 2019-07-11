/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.framesequence.samples;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SamplesList extends ListActivity {

    static final String KEY_NAME = "name";
    static final String KEY_CLASS = "clazz";
    static final String KEY_RESOURCE = "res";

    static Map<String,?> makeSample(String name, Class<?> activity, int resourceId) {
        Map<String,Object> ret = new HashMap<String,Object>();
        ret.put(KEY_NAME, name);
        ret.put(KEY_CLASS, activity);
        ret.put(KEY_RESOURCE, resourceId);
        return ret;
    }

    @SuppressWarnings("serial")
    static final ArrayList<Map<String,?>> SAMPLES = new ArrayList<Map<String,?>>() {{
            add(makeSample("GIF animation", FrameSequenceTest.class, R.raw.animated_gif));
            add(makeSample("WEBP animation", FrameSequenceTest.class, R.raw.animated_webp));
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setListAdapter(new SimpleAdapter(this, SAMPLES,
                android.R.layout.simple_list_item_1, new String[] { KEY_NAME },
                new int[] { android.R.id.text1 }));
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Class<?> clazz = (Class<?>) SAMPLES.get(position).get(KEY_CLASS);
        int resourceId = ((Integer) SAMPLES.get(position).get(KEY_RESOURCE)).intValue();

        Intent intent = new Intent(this, clazz);
        intent.putExtra("resourceId", resourceId);
        startActivity(intent);
    }

}
