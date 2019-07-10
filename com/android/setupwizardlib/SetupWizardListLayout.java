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

package com.android.setupwizardlib;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build.VERSION_CODES;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;

public class SetupWizardListLayout extends SetupWizardLayout {

    private static final String TAG = "SetupWizardListLayout";
    private ListView mListView;

    public SetupWizardListLayout(Context context) {
        super(context);
    }

    public SetupWizardListLayout(Context context, int template) {
        super(context, template);
    }

    public SetupWizardListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @TargetApi(VERSION_CODES.HONEYCOMB)
    public SetupWizardListLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @TargetApi(VERSION_CODES.HONEYCOMB)
    public SetupWizardListLayout(Context context, int template, AttributeSet attrs,
            int defStyleAttr) {
        super(context, template, attrs, defStyleAttr);
    }

    @Override
    protected View onInflateTemplate(LayoutInflater inflater, int template) {
        if (template == 0) {
            template = R.layout.suw_list_template;
        }
        return inflater.inflate(template, this, false);
    }

    @Override
    protected void onTemplateInflated() {
        mListView = (ListView) findViewById(android.R.id.list);
    }

    @Override
    protected int getContainerId() {
        return android.R.id.list;
    }

    public ListView getListView() {
        return mListView;
    }

    public void setAdapter(ListAdapter adapter) {
        getListView().setAdapter(adapter);
    }
}
