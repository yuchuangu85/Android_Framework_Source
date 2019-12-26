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

/**
 * Marks a method in a {@link Dao} annotated class as an update method.
 * <p>
 * The implementation of the method will update its parameters in the database if they already
 * exists (checked by primary keys). If they don't already exists, this option will not change the
 * database.
 * <p>
 * All of the parameters of the Update method must either be classes annotated with {@link Entity}
 * or collections/array of it.
 * <p>
 * {@literal @}Dao
 * public interface MyDao {
 * {@literal @}Update(onConflict = OnConflictStrategy.REPLACE)
 * public void insertUsers(User... users);// 更新数组
 * {@literal @}Update
 * public void insertBoth(User user1, User user2);// 更新单个或者多个
 * {@literal @}Update
 * public void insertWithFriends(User user, List&lt;User&gt; friends);// 更新单个或者列表
 * }
 * </p>
 *
 * @see Insert
 * @see Delete
 * <p>
 * 更新数据(单个,多个,列表,数组),同insert
 */
public @interface Update {
    /**
     * What to do if a conflict happens.
     *
     * @return How to handle conflicts. Defaults to {@link OnConflictStrategy#ABORT}.
     *
     * @see <a href="https://sqlite.org/lang_conflict.html">SQLite conflict documentation</a>
     */
    @OnConflictStrategy
    int onConflict() default OnConflictStrategy.ABORT;
}
