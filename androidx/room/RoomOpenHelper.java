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

import android.database.Cursor;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

/**
 * An open helper that holds a reference to the configuration until the database is opened.
 *
 * 参考:
 * https://www.jianshu.com/p/8c0071f34756
 *
 * @hide
 */
@SuppressWarnings("unused")
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class RoomOpenHelper extends SupportSQLiteOpenHelper.Callback {
    @Nullable
    private DatabaseConfiguration mConfiguration;
    @NonNull
    private final Delegate mDelegate;
    @NonNull
    private final String mIdentityHash;
    /**
     * Room v1 had a bug where the hash was not consistent if fields are reordered.
     * The new has fixes it but we still need to accept the legacy hash.
     */
    @NonNull // b/64290754
    private final String mLegacyHash;

    public RoomOpenHelper(@NonNull DatabaseConfiguration configuration, @NonNull Delegate delegate,
            @NonNull String identityHash, @NonNull String legacyHash) {
        super(delegate.version);
        mConfiguration = configuration;
        mDelegate = delegate;
        mIdentityHash = identityHash;
        mLegacyHash = legacyHash;
    }

    public RoomOpenHelper(@NonNull DatabaseConfiguration configuration, @NonNull Delegate delegate,
            @NonNull String legacyHash) {
        this(configuration, delegate, null, legacyHash);
    }

    @Override
    public void onConfigure(SupportSQLiteDatabase db) {
        super.onConfigure(db);
    }

    @Override
    public void onCreate(SupportSQLiteDatabase db) {
        updateIdentity(db);
        mDelegate.createAllTables(db);
        mDelegate.onCreate(db);
    }

    /**
     * 将数据库的升降级抽象为一个类Migration，它包含了数据库升降级的全部信息：startVersion、endVersion和migrate。
     * 通过扩展Migration类，我们可以方便地定义一个个具体的升降级“行为”。数据库升降级在使用层面被大大简化。
     * 把各个Migration存储在MigrationContainer的二维SparseArray中。这种数据结构方便查找出最佳的升降级路径，高效升降级。
     * 数据库升降级之后，Room会通过RoomOpenHelper.Delegate的validateMigration方法帮我们验证升降级后数据库表结构的正确性。
     * Room提供了测试工具方便我们测试数据库，特别适合于验证数据库升降级前后数据迁移的正确性。
     *
     * @param db         The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    @Override
    public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
        boolean migrated = false;
        if (mConfiguration != null) {
            // 查找数据库迁移列表
            List<Migration> migrations = mConfiguration.migrationContainer.findMigrationPath(
                    oldVersion, newVersion);
            if (migrations != null) {
                // 执行配置的每一个迁移命令
                for (Migration migration : migrations) {
                    migration.migrate(db);
                }
                // 验证数据库完整性
                mDelegate.validateMigration(db);
                updateIdentity(db);
                migrated = true;
            }
        }
        //如果数据库版本发生变化，必须定义相应的 Migration
        //除非我们通过RoomDatabase.Builder设置了可以通过destruct进行升级
        if (!migrated) {
            if (mConfiguration != null && !mConfiguration.isMigrationRequiredFrom(oldVersion)) {
                //destruct指的就是丢弃旧表，创建新表；所有之前的数据都会被丢弃
                mDelegate.dropAllTables(db);
                mDelegate.createAllTables(db);
            } else {
                throw new IllegalStateException("A migration from " + oldVersion + " to "
                        + newVersion + " was required but not found. Please provide the "
                        + "necessary Migration path via "
                        + "RoomDatabase.Builder.addMigration(Migration ...) or allow for "
                        + "destructive migrations via one of the "
                        + "RoomDatabase.Builder.fallbackToDestructiveMigration* methods.");
            }
        }
    }

    @Override
    public void onDowngrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
        // 升降级一起处理
        onUpgrade(db, oldVersion, newVersion);
    }

    @Override
    public void onOpen(SupportSQLiteDatabase db) {
        super.onOpen(db);
        checkIdentity(db);
        mDelegate.onOpen(db);
        // there might be too many configurations etc, just clear it.
        mConfiguration = null;
    }

    private void checkIdentity(SupportSQLiteDatabase db) {
        String identityHash = null;
        if (hasRoomMasterTable(db)) {
            Cursor cursor = db.query(new SimpleSQLiteQuery(RoomMasterTable.READ_QUERY));
            //noinspection TryFinallyCanBeTryWithResources
            try {
                if (cursor.moveToFirst()) {
                    identityHash = cursor.getString(0);
                }
            } finally {
                cursor.close();
            }
        }
        if (!mIdentityHash.equals(identityHash) && !mLegacyHash.equals(identityHash)) {
            throw new IllegalStateException("Room cannot verify the data integrity. Looks like"
                    + " you've changed schema but forgot to update the version number. You can"
                    + " simply fix this by increasing the version number.");
        }
    }

    private void updateIdentity(SupportSQLiteDatabase db) {
        createMasterTableIfNotExists(db);
        db.execSQL(RoomMasterTable.createInsertQuery(mIdentityHash));
    }

    private void createMasterTableIfNotExists(SupportSQLiteDatabase db) {
        db.execSQL(RoomMasterTable.CREATE_QUERY);
    }

    private static boolean hasRoomMasterTable(SupportSQLiteDatabase db) {
        Cursor cursor = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name='"
                + RoomMasterTable.TABLE_NAME + "'");
        //noinspection TryFinallyCanBeTryWithResources
        try {
            return cursor.moveToFirst() && cursor.getInt(0) != 0;
        } finally {
            cursor.close();
        }
    }

    /**
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public abstract static class Delegate {
        public final int version;

        public Delegate(int version) {
            this.version = version;
        }

        //丢弃原有的数据库表，创建新的表，也是一种升级策略
        protected abstract void dropAllTables(SupportSQLiteDatabase database);

        protected abstract void createAllTables(SupportSQLiteDatabase database);

        protected abstract void onOpen(SupportSQLiteDatabase database);

        protected abstract void onCreate(SupportSQLiteDatabase database);

        /**
         * Called after a migration run to validate database integrity.
         * //验证数据库升级的完整性
         *
         * @param db The SQLite database.
         */
        protected abstract void validateMigration(SupportSQLiteDatabase db);
    }

}
