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

package com.android.setupwizardlib.test;

import android.support.v7.widget.SwitchCompat;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.setupwizardlib.R;
import com.android.setupwizardlib.items.SwitchItem;

public class SwitchItemTest extends AndroidTestCase {

    private SwitchCompat mSwitch;

    @SmallTest
    public void testChecked() {
        SwitchItem item = new SwitchItem();
        item.setTitle("TestTitle");
        item.setSummary("TestSummary");
        View view = createLayout();

        item.setChecked(true);

        item.onBindView(view);

        assertTrue("Switch should be checked", mSwitch.isChecked());
    }

    @SmallTest
    public void testNotChecked() {
        SwitchItem item = new SwitchItem();
        item.setTitle("TestTitle");
        item.setSummary("TestSummary");
        View view = createLayout();

        item.setChecked(false);

        item.onBindView(view);

        assertFalse("Switch should be unchecked", mSwitch.isChecked());
    }

    @SmallTest
    public void testListener() {
        SwitchItem item = new SwitchItem();
        item.setTitle("TestTitle");
        item.setSummary("TestSummary");
        View view = createLayout();

        item.setChecked(true);

        final TestOnCheckedChangeListener listener = new TestOnCheckedChangeListener();
        item.setOnCheckedChangeListener(listener);

        item.onBindView(view);

        assertTrue("Switch should be checked", mSwitch.isChecked());
        mSwitch.setChecked(false);

        assertTrue("Listener should be called", listener.called);
        assertFalse("Listener should not be checked", listener.checked);

        mSwitch.setChecked(true);

        assertTrue("Listener should be called", listener.called);
        assertTrue("Listener should be checked", listener.checked);
    }

    @SmallTest
    public void testListenerSetChecked() {
        // Check that calling setChecked on the item will also call the listener.

        SwitchItem item = new SwitchItem();
        item.setTitle("TestTitle");
        item.setSummary("TestSummary");
        View view = createLayout();

        item.setChecked(true);

        final TestOnCheckedChangeListener listener = new TestOnCheckedChangeListener();
        item.setOnCheckedChangeListener(listener);

        item.onBindView(view);

        assertTrue("Switch should be checked", mSwitch.isChecked());
        item.setChecked(false);

        assertTrue("Listener should be called", listener.called);
        assertFalse("Listener should not be checked", listener.checked);

        item.setChecked(true);

        assertTrue("Listener should be called", listener.called);
        assertTrue("Listener should be checked", listener.checked);
    }

    @SmallTest
    public void testToggle() {
        SwitchItem item = new SwitchItem();
        item.setTitle("TestTitle");
        item.setSummary("TestSummary");
        View view = createLayout();

        item.setChecked(true);
        item.onBindView(view);

        assertTrue("Switch should be checked", mSwitch.isChecked());

        item.toggle(view);

        assertFalse("Switch should be unchecked", mSwitch.isChecked());
    }

    private ViewGroup createLayout() {
        ViewGroup root = new FrameLayout(mContext);

        TextView titleView = new TextView(mContext);
        titleView.setId(R.id.suw_items_title);
        root.addView(titleView);

        TextView summaryView = new TextView(mContext);
        summaryView.setId(R.id.suw_items_summary);
        root.addView(summaryView);

        FrameLayout iconContainer = new FrameLayout(mContext);
        iconContainer.setId(R.id.suw_items_icon_container);
        root.addView(iconContainer);

        ImageView iconView = new ImageView(mContext);
        iconView.setId(R.id.suw_items_icon);
        iconContainer.addView(iconView);

        mSwitch = new SwitchCompat(mContext);
        mSwitch.setId(R.id.suw_items_switch);
        root.addView(mSwitch);

        return root;
    }

    private static class TestOnCheckedChangeListener implements SwitchItem.OnCheckedChangeListener {

        public boolean called = false;
        public boolean checked = false;

        @Override
        public void onCheckedChange(SwitchItem item, boolean isChecked) {
            called = true;
            checked = isChecked;
        }
    }
}
