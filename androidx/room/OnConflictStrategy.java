/*
 * Copyright (C) 2017 The Android Open Source Project
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

package androidx.room;

import java.lang.annotation.Retention;

import androidx.annotation.IntDef;

import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * Set of conflict handling strategies for various {@link Dao} methods.
 * <p>
 * Check <a href="https://sqlite.org/lang_conflict.html">SQLite conflict documentation</a> for
 * details.
 */
@Retention(SOURCE)
@IntDef({OnConflictStrategy.REPLACE, OnConflictStrategy.ROLLBACK, OnConflictStrategy.ABORT,
        OnConflictStrategy.FAIL, OnConflictStrategy.IGNORE})
public @interface OnConflictStrategy {
    /**
     * OnConflict strategy constant to replace the old data and continue the transaction.
     * 冲突替换
     */
    int REPLACE = 1;
    /**
     * OnConflict strategy constant to rollback the transaction.
     * 冲突回滚
     */
    int ROLLBACK = 2;
    /**
     * OnConflict strategy constant to abort the transaction.
     * 冲突放弃
     */
    int ABORT = 3;
    /**
     * OnConflict strategy constant to fail the transaction.
     * 冲突失败
     */
    int FAIL = 4;
    /**
     * OnConflict strategy constant to ignore the conflict.
     * 冲突忽略
     */
    int IGNORE = 5;

}
