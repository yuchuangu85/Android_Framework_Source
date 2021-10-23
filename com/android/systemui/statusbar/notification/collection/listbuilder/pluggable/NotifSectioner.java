/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import android.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.NodeSpec;

/**
 * Pluggable for participating in notif sectioning. See {@link ShadeListBuilder#setSections}.
 */
public abstract class NotifSectioner extends Pluggable<NotifSectioner> {
    protected NotifSectioner(String name) {
        super(name);
    }

    /**
     * If returns true, this notification is considered within this section.
     * Sectioning is performed on each top level notification each time the pipeline is run.
     * However, this doesn't necessarily mean that your section will get called on each top-level
     * notification. The first section to return true determines the section of the notification.
     */
    public abstract boolean isInSection(ListEntry entry);

    /**
     * Returns an optional {@link NodeSpec} for the section header. If {@code null}, no header will
     * be used for the section.
     */
    public @Nullable NodeController getHeaderNodeController() {
        return null;
    }
}
