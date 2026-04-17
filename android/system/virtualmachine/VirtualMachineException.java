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

package android.system.virtualmachine;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import com.android.system.virtualmachine.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Exception thrown when operations on virtual machines fail.
 *
 * @hide
 */
@SystemApi
public class VirtualMachineException extends Exception {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "CODE_",
            value = {
                CODE_INTERNAL,
                CODE_VIRTUAL_MACHINE_DELETED,
                CODE_VIRTUAL_MACHINE_STOPPED,
                CODE_VIRTUAL_MACHINE_RUNNING,
                CODE_NAME_ALREADY_EXISTS,
                CODE_PAYLOAD_CONFIG_MALFORMED,
                CODE_CONFIG_FILE_CORRUPTED,
                CODE_CONFIG_INCOMPATIBLE,
                CODE_FEATURE_DISABLED
            })
    public @interface Code {}

    private final int code;

    VirtualMachineException(@Nullable String message) {
        super(message);
        this.code = CODE_INTERNAL;
    }

    VirtualMachineException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = CODE_INTERNAL;
    }

    VirtualMachineException(@Nullable Throwable cause) {
        super(cause);
        this.code = CODE_INTERNAL;
    }

    VirtualMachineException(@Nullable String message, int code) {
        super(message);
        this.code = code;
    }

    VirtualMachineException(@Nullable Throwable cause, int code) {
        super(cause);
        this.code = code;
    }

    VirtualMachineException(@Nullable String message, @Nullable Throwable cause, int code) {
        super(message, cause);
        this.code = code;
    }

    /** Gets the code explaining the cause of the exception. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    @Code
    public int getCode() {
        return this.code;
    }

    /** The exception is internal to the virtualization framework. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_INTERNAL = 1;

    /** Virtual machine is already deleted. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_VIRTUAL_MACHINE_DELETED = 2;

    /** Virtual machine is not running but was attempted to stop. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_VIRTUAL_MACHINE_STOPPED = 3;

    /** Virtual machine is running, but was attemped to run it again. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_VIRTUAL_MACHINE_RUNNING = 4;

    /** Virtual machine of the given name already exists. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_NAME_ALREADY_EXISTS = 5;

    /** VM Payload config is malformed. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_PAYLOAD_CONFIG_MALFORMED = 6;

    /** Virtual machine's config file is corrupted and thus can't be read. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_CONFIG_FILE_CORRUPTED = 7;

    /** The new config is incompatible with the current config. */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_CONFIG_INCOMPATIBLE = 8;

    /**
     * API for a feature is called, but the feature is not turned on in the virtual machine config
     */
    @FlaggedApi(Flags.FLAG_VIRTUALMACHINEEXCEPTION_CODE)
    public static final int CODE_FEATURE_DISABLED = 9;
}
