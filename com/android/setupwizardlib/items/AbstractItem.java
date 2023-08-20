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

package com.android.setupwizardlib.items;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Abstract implementation of an item, which implements {@link IItem} and takes care of implementing
 * methods for {@link ItemHierarchy} for items representing itself.
 */
public abstract class AbstractItem extends AbstractItemHierarchy implements IItem {

  public AbstractItem() {
    super();
  }

  public AbstractItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public int getCount() {
    return 1;
  }

  @Override
  public IItem getItemAt(int position) {
    return this;
  }

  @Override
  public ItemHierarchy findItemById(int id) {
    if (id == getId()) {
      return this;
    }
    return null;
  }

  /**
   * Convenience method to notify the adapter that the contents of this item has changed. This only
   * includes non-structural changes. Changes that causes the item to be removed should use the
   * other notification methods.
   *
   * @see #notifyItemRangeChanged(int, int)
   * @see #notifyItemRangeInserted(int, int)
   * @see #notifyItemRangeRemoved(int, int)
   */
  public void notifyItemChanged() {
    notifyItemRangeChanged(0, 1);
  }
}
