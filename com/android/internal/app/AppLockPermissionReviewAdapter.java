/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A RecyclerView adapter for displaying a list of applications in the
 * {@link AppLockPermissionReviewActivity}.
 *
 * <p>This adapter is responsible for taking a list of {@link AppWithPermissionInfo} objects and
 * binding each object's data, specifically the application icon and name to a row in the
 * RecyclerView, defined by the {@code R.layout.app_lock_permission_app_item} layout.
 *
 * <p>The list of applications displayed by the adapter can be updated dynamically by calling the
 * {@link #updateData(List)} method, which will refresh the contents of the RecyclerView.
 */
public class AppLockPermissionReviewAdapter
        extends RecyclerView.Adapter<AppLockPermissionReviewAdapter.AppViewHolder> {

    private final List<AppLockPermissionReviewActivity.AppWithPermissionInfo> mAppList =
            new ArrayList<>();

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView mAppIconView;
        final TextView mAppNameView;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            mAppIconView = itemView.findViewById(R.id.app_lock_permission_review_list_app_icon);
            mAppNameView = itemView.findViewById(R.id.app_lock_permission_review_list_app_name);
        }

        /**
         * Binds the provided application details to the views in this ViewHolder.
         *
         * @param detail The {@link AppLockPermissionReviewActivity.AppWithPermissionInfo}
         *               containing the icon and name to display.
         */
        public void bind(AppLockPermissionReviewActivity.AppWithPermissionInfo detail) {
            mAppIconView.setImageDrawable(detail.mAppIcon);
            mAppNameView.setText(detail.mAppName);
        }
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View appItemView =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.app_lock_permission_app_item, parent, false);
        return new AppViewHolder(appItemView);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(mAppList.get(position));
    }

    @Override
    public int getItemCount() {
        return mAppList.size();
    }

    /**
     * Updates the list of applications displayed by this adapter and refreshes the UI.
     *
     * @param newAppList The new list of
     *                   {@link AppLockPermissionReviewActivity.AppWithPermissionInfo} objects.
     */
    public void updateData(List<AppLockPermissionReviewActivity.AppWithPermissionInfo> newAppList) {
        if (isListDataIdentical(mAppList, newAppList)) {
            return;
        }

        mAppList.clear();
        mAppList.addAll(newAppList);
        notifyDataSetChanged();
    }

    /**
     * Manually compares two lists of apps to see if they are identical.
     * This replaces the need for DiffUtil in the system framework.
     */
    private boolean isListDataIdentical(
            List<AppLockPermissionReviewActivity.AppWithPermissionInfo> oldList,
            List<AppLockPermissionReviewActivity.AppWithPermissionInfo> newList) {

        if (oldList == newList) return true;
        if (oldList.size() != newList.size()) return false;

        for (int i = 0; i < oldList.size(); i++) {
            AppLockPermissionReviewActivity.AppWithPermissionInfo oldApp = oldList.get(i);
            AppLockPermissionReviewActivity.AppWithPermissionInfo newApp = newList.get(i);

            // Check if the package or name has changed
            if (!Objects.equals(oldApp.mPackageName, newApp.mPackageName)
                    || !TextUtils.equals(oldApp.mAppName, newApp.mAppName)
                    || !Objects.equals(oldApp.mAppIcon, newApp.mAppIcon)) {
                return false;
            }
        }
        return true;
    }
}
