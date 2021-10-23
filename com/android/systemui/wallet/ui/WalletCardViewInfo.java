/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import android.app.PendingIntent;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

interface WalletCardViewInfo {
    String getCardId();

    /**
     * Image of the card.
     */
    @NonNull
    Drawable getCardDrawable();

    /**
     * Content description for the card.
     */
    @Nullable
    CharSequence getContentDescription();

    /**
     * Icon shown above the card.
     */
    @Nullable
    Drawable getIcon();

    /**
     * Text shown above the card.
     */
    @NonNull
    CharSequence getLabel();

    /**
     * Pending intent upon the card is clicked.
     */
    @NonNull
    PendingIntent getPendingIntent();

    default boolean isUiEquivalent(WalletCardViewInfo other) {
        if (other == null) {
            return false;
        }
        return getCardId().equals(other.getCardId());
    }
}
