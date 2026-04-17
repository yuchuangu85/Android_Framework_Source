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

import static android.os.ParcelFileDescriptor.AutoCloseInputStream;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.system.virtualmachine.VirtualMachineCallback.ERROR_PAYLOAD_CHANGED;
import static android.system.virtualmachine.VirtualMachineCallback.ERROR_PAYLOAD_INVALID_CONFIG;
import static android.system.virtualmachine.VirtualMachineCallback.ERROR_PAYLOAD_VERIFICATION_FAILED;
import static android.system.virtualmachine.VirtualMachineCallback.ERROR_UNKNOWN;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_CRASH;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_HANGUP;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_INFRASTRUCTURE_ERROR;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_KILLED;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_MICRODROID_UNKNOWN_RUNTIME_ERROR;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_PVM_FIRMWARE_PUBLIC_KEY_MISMATCH;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_REBOOT;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_SHUTDOWN;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_START_FAILED;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_UNKNOWN;
import static android.system.virtualmachine.VirtualMachineCallback.STOP_REASON_VIRTUALIZATION_SERVICE_DIED;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.virtualizationcommon.DeathReason;
import android.system.virtualizationcommon.ErrorCode;
import android.system.virtualizationcommon.IEncryptedStoreKEK;
import android.system.virtualizationcommon.IGuestAgent;
import android.system.virtualizationservice.DisplayConfig;
import android.system.virtualizationservice.IVirtualMachine;
import android.system.virtualizationservice.IVirtualMachineCallback;
import android.system.virtualizationservice.IVirtualizationService;
import android.system.virtualizationservice.InputDevice;
import android.system.virtualizationservice.PartitionType;
import android.system.virtualizationservice.VirtualMachineAppConfig;
import android.system.virtualizationservice.VirtualMachineDisplay;
import android.system.virtualizationservice.VirtualMachineRawConfig;
import android.system.virtualizationservice.VirtualMachineState;
import android.util.JsonReader;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoBridge;
import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.ZipFile;

/**
 * Represents an VM instance, with its own configuration and state. Instances are persistent and are
 * created or retrieved via {@link VirtualMachineManager}.
 *
 * <p>The {@link #run} method actually starts up the VM and allows the payload code to execute. It
 * will continue until it exits or {@link #stop} is called. Updates on the state of the VM can be
 * received using {@link #setCallback}. The app can communicate with the VM using {@link
 * #connectToVsockServer} or {@link #connectVsock}.
 *
 * <p>The payload code running inside the VM has access to a set of native APIs; see the <a
 * href="https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Virtualization/libs/libvm_payload/README.md">README
 * file</a> for details.
 *
 * <p>Each VM has a unique secret, computed from the APK that contains the code running in it, the
 * VM configuration, and a random per-instance salt. The secret can be accessed by the payload code
 * running inside the VM (using {@code AVmPayload_getVmInstanceSecret}) but is not made available
 * outside it.
 *
 * @hide
 */
@SystemApi
public class VirtualMachine implements AutoCloseable {
    /** The permission needed to create or run a virtual machine. */
    public static final String MANAGE_VIRTUAL_MACHINE_PERMISSION =
            "android.permission.MANAGE_VIRTUAL_MACHINE";

    /**
     * The permission needed to create a virtual machine with more advanced configuration options.
     */
    public static final String USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION =
            "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";

    /**
     * The lowest port number that can be used to communicate with the virtual machine payload.
     *
     * @see #connectToVsockServer
     * @see #connectVsock
     */
    @SuppressLint("MinMaxConstant") // Won't change: see man 7 vsock.
    public static final long MIN_VSOCK_PORT = 1024;

    /**
     * The highest port number that can be used to communicate with the virtual machine payload.
     *
     * @see #connectToVsockServer
     * @see #connectVsock
     */
    @SuppressLint("MinMaxConstant") // Won't change: see man 7 vsock.
    public static final long MAX_VSOCK_PORT = (1L << 32) - 1;

    private ParcelFileDescriptor mTouchSock;
    private ParcelFileDescriptor mKeySock;
    private ParcelFileDescriptor mMouseSock;
    private ParcelFileDescriptor mSwitchesSock;
    private ParcelFileDescriptor mTrackpadSock;

    private enum InputEventType {
        TOUCH,
        MOUSE,
        TRACKPAD
    }

    private BlockingQueue<Pair<InputEventType, MotionEvent>> mInputEventQueue =
            new LinkedBlockingQueue<>();

    /**
     * Status of a virtual machine
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "STATUS_",
            value = {STATUS_STOPPED, STATUS_RUNNING, STATUS_DELETED})
    public @interface Status {}

    /** The virtual machine has just been created, or {@link #stop} was called on it. */
    public static final int STATUS_STOPPED = 0;

    /** The virtual machine is running. */
    public static final int STATUS_RUNNING = 1;

    @NonNull
    private Float touchScale = 1F;

    /**
     * The virtual machine has been deleted. This is an irreversible state. Once a virtual machine
     * is deleted all its secrets are permanently lost, and it cannot be run. A new virtual machine
     * with the same name and config may be created, with new and different secrets.
     */
    public static final int STATUS_DELETED = 2;

    private static final String TAG = "VirtualMachine";

    /** Name of the directory under the files directory where all VMs created for the app exist. */
    private static final String VM_DIR = "vm";

    /** Name of the persisted config file for a VM. */
    private static final String CONFIG_FILE = "config.xml";

    /** Name of the instance image file for a VM. (Not implemented) */
    private static final String INSTANCE_IMAGE_FILE = "instance.img";

    /** Name of the file for a VM containing Id. */
    private static final String INSTANCE_ID_FILE = "instance_id";

    /** Name of the idsig file for a VM */
    private static final String IDSIG_FILE = "idsig";

    /** Name of the idsig files for extra APKs. */
    private static final String EXTRA_IDSIG_FILE_PREFIX = "extra_idsig_";

    /** Name of the idsig files for tenants APKs. */
    private static final String TENANT_IDSIG_FILE_PREFIX = "tenant_idsig_";

    /** Size of the instance image. 10 MB. */
    private static final long INSTANCE_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * Fixed pressure value to be sent for trackpad events when pressed.
     *
     * <p>1. The value of MotionEvent.getPressure() varies by device. For example, it is [0, 1] on
     * some devices, but not always. 2. Even for devices that send values between [0, 1], some only
     * send 0 for not pressed and 1 for pressed. In such cases, if we send the maximum pressure
     * (e.g., 255), libraries like libinput on the guest side might perceive it as an abnormal touch
     * (like a palm) and trigger palm rejection, ignoring the input. 3. Therefore, 50 is a sweet
     * spot that ensures reliable input without triggering rejection. We ignore the actual pressure
     * from the event and always send this fixed value.
     */
    private static final int TRACKPAD_PRESSURE_PRESSED = 50;

    /** Name of the file backing the encrypted storage */
    private static final String ENCRYPTED_STORE_FILE = "storage.img";

    /** Name of the file with KEK used to setup encrypted store */
    private static final String ENCRYPTED_STORE_KEK_FILE = "encrypted_store_kek.bin";

    // Both binders are for virtmgr, and not virtualizationservice
    private IBinder.DeathRecipient mVSDeathRecipient;
    private IBinder.DeathRecipient mVMDeathRecipient;

    /** The package which owns this VM. */
    @NonNull private final String mPackageName;

    /** Name of this VM within the package. The name should be unique in the package. */
    @NonNull private final String mName;

    /** Path to the directory containing all the files related to this VM. */
    @NonNull private final File mVmRootPath;

    /**
     * Path to the config file for this VM. The config file is where the configuration is persisted.
     */
    @NonNull private final File mConfigFilePath;

    /** Path to the instance image file for this VM. */
    @NonNull private final File mInstanceFilePath;

    /** Path to the idsig file for this VM. */
    @NonNull private final File mIdsigFilePath;

    /** File that backs the encrypted storage - Will be null if not enabled. */
    @Nullable private final File mEncryptedStoreFilePath;

    /**
     * File that stores KEK used to setup encrypted store.
     *
     * <p>Is only set iff encrypted store is enabled in the {@link
     * VirtualMachineConfig#ENCRYPTED_STORE_MODE_KEK_ON_CE} mode.
     */
    @Nullable private final EncryptedStoreKEK mEncryptedStoreKEK;

    /** File that contains the Id. This is NULL iff FEATURE_LLPVM is disabled */
    @Nullable private final File mInstanceIdPath;

    /**
     * Unmodifiable list of extra apks. Apks are specified by the vm config, and corresponding
     * idsigs are to be generated.
     */
    @NonNull private final List<ApkSpec> mExtraApks;

    // TODO(b/455544131): mTenantApks should be part of VirtualMachineConfig.
    /** Unmodifiable list of tenant apks. */
    @NonNull private List<ApkSpec> mTenantApks;

    @NonNull private final Executor mMemoryCallbackExecutor = Executors.newSingleThreadExecutor();

    private class MemoryManagementCallbacks implements ComponentCallbacks2 {
        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {}

        @Override
        public void onLowMemory() {}

        @Override
        public void onTrimMemory(int level) {
            final int currentLevel = level;
            mMemoryCallbackExecutor.execute(
                    () -> {
                        int percent = 10;

                        if (currentLevel >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                            percent = 30;
                        }

                        if (currentLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                            percent = 50;
                        }

                        setMemoryBalloonByPercent(percent);
                        Log.d(
                                TAG,
                                "onTrimMemory: Completed setMemoryBalloonByPercent on thread: "
                                        + Thread.currentThread().getName());
                    });
            }
    }

    private final MemoryManagementCallbacks mMemoryManagementCallbacks;

    @NonNull private final Context mContext;

    // A note on lock ordering:
    // You can take mLock while holding VirtualMachineManager.sCreateLock, but not vice versa.
    // We never take any other lock while holding CallbackTranslator.mLock; therefore you can
    // take CallbackTranslator.mLock while holding any other lock.

    /** Lock protecting our mutable state (other than callbacks). */
    private final Object mLock = new Object();

    private final boolean mVmOutputCaptured;

    private final boolean mVmConsoleInputSupported;

    private final boolean mConnectVmConsole;

    private final Executor mConsoleExecutor = Executors.newSingleThreadExecutor();

    private ExecutorService mInputEventExecutor;

    /** Running instance of virtmgr that hosts VirtualizationService for this VM. */
    @GuardedBy("mLock")
    @Nullable
    private VirtualizationService mVirtualizationService;

    /** The configuration that is currently associated with this VM. */
    @GuardedBy("mLock")
    @NonNull
    private VirtualMachineConfig mConfig;

    /** Handle to the "running" VM. */
    @GuardedBy("mLock")
    @Nullable
    private IVirtualMachine mVirtualMachine;

    /** CID of the "running" VM. */
    @GuardedBy("mLock")
    private int mCid = -1;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mConsoleOutReader;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mConsoleOutWriter;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mConsoleInReader;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mConsoleInWriter;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mTeeConsoleOutReader;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mTeeConsoleOutWriter;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mPtyFd;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mPtsFd;

    @GuardedBy("mLock")
    @Nullable
    private String mPtsName;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mLogReader;

    @GuardedBy("mLock")
    @Nullable
    private ParcelFileDescriptor mLogWriter;

    @GuardedBy("mLock")
    private boolean mWasDeleted = false;

    @GuardedBy("mLock")
    private boolean mStopHandled = false;

    /**
     * The registered callback.
     *
     * <p>Don't use directly for callbacks! Only for creating CallbackTranslator.
     */
    @GuardedBy("mLock")
    @Nullable
    private VirtualMachineCallback mConfiguredCallback;

    /**
     * The executor on which the callback will be executed.
     *
     * <p>Don't use directly for callbacks! Only for creating CallbackTranslator.
     */
    @GuardedBy("mLock")
    @Nullable
    private Executor mConfiguredCallbackExecutor;

    /** The actual callback binder object registered for the current VM. */
    @GuardedBy("mLock")
    @Nullable
    private CallbackTranslator mCallbackTranslator;

    private static class ApkSpec {
        public final File apk;
        public final File idsig;

        ApkSpec(File apk, File idsig) {
            this.apk = apk;
            this.idsig = idsig;
        }
    }

    static {
        System.loadLibrary("virtualmachine_jni");
    }

    private VirtualMachine(
            @NonNull Context context,
            @NonNull String name,
            @NonNull VirtualMachineConfig config,
            @NonNull VirtualizationService service)
            throws VirtualMachineException {
        mPackageName = context.getPackageName();
        mName = requireNonNull(name, "Name must not be null");
        mConfig = requireNonNull(config, "Config must not be null");

        File thisVmDir = getVmDir(context, mName);
        mVmRootPath = thisVmDir;
        mConfigFilePath = new File(thisVmDir, CONFIG_FILE);
        try {
            mInstanceIdPath =
                    (service.getBinder()
                                    .isFeatureEnabled(IVirtualizationService.FEATURE_LLPVM_CHANGES))
                            ? new File(thisVmDir, INSTANCE_ID_FILE)
                            : null;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
        mInstanceFilePath = new File(thisVmDir, INSTANCE_IMAGE_FILE);
        mIdsigFilePath = new File(thisVmDir, IDSIG_FILE);
        mExtraApks = setupExtraApks(context, config, thisVmDir);
        mTenantApks = setupTenantApks(context, config, thisVmDir);

        mContext = context;
        mEncryptedStoreFilePath =
                (config.isEncryptedStorageEnabled())
                        ? new File(thisVmDir, ENCRYPTED_STORE_FILE)
                        : null;
        if (config.getEncryptedStoreMode() == VirtualMachineConfig.ENCRYPTED_STORE_MODE_KEK_ON_CE) {
            mEncryptedStoreKEK = new EncryptedStoreKEK(context, mName);
        } else {
            mEncryptedStoreKEK = null;
        }

        mVmOutputCaptured = config.isVmOutputCaptured();
        mVmConsoleInputSupported = config.isVmConsoleInputSupported();
        mConnectVmConsole = Build.isDebuggable() && config.isConnectVmConsole();
        if (mConnectVmConsole != config.isConnectVmConsole()) {
            Log.w(TAG, "Ignored 'connectVmConsole' option on non-debuggable build");
        }

        VirtualMachineCustomImageConfig customImageConfig;
        customImageConfig = config.getCustomImageConfig();
        if (customImageConfig == null || customImageConfig.useAutoMemoryBalloon()) {
            mMemoryManagementCallbacks = new MemoryManagementCallbacks();
        } else {
            mMemoryManagementCallbacks = null;
        }
    }

    /**
     * Creates a virtual machine from an {@link VirtualMachineDescriptor} object and associates it
     * with the given name.
     *
     * <p>The new virtual machine will be in the same state as the descriptor indicates.
     *
     * <p>Once a virtual machine is imported it is persisted until it is deleted by calling {@link
     * #delete}. The imported virtual machine is in {@link #STATUS_STOPPED} state. To run the VM,
     * call {@link #run}.
     */
    @GuardedBy("VirtualMachineManager.sCreateLock")
    @NonNull
    static VirtualMachine fromDescriptor(
            @NonNull Context context,
            @NonNull String name,
            @NonNull VirtualMachineDescriptor vmDescriptor)
            throws VirtualMachineException {
        VirtualizationService service = VirtualizationService.getInstance();
        File vmDir = createVmDir(context, name);

        try {
            VirtualMachine vm;
            try (vmDescriptor) {
                VirtualMachineConfig config = VirtualMachineConfig.from(vmDescriptor.getConfigFd());
                vm = new VirtualMachine(context, name, config, service);
                config.serialize(vm.mConfigFilePath);
                try {
                    vm.mInstanceFilePath.createNewFile();
                } catch (IOException e) {
                    throw new VirtualMachineException("failed to create instance image", e);
                }
                vm.importInstanceFrom(vmDescriptor.getInstanceImgFd());

                // TODO(b/406258175): handle the encrypted store KEK for VM transfer.
                if (vmDescriptor.getEncryptedStoreFd() != null) {
                    try {
                        vm.mEncryptedStoreFilePath.createNewFile();
                    } catch (IOException e) {
                        throw new VirtualMachineException(
                                "failed to create encrypted storage image", e);
                    }
                    vm.importEncryptedStoreFrom(vmDescriptor.getEncryptedStoreFd());
                }
                if (vm.mInstanceIdPath != null) {
                    vm.importInstanceIdFrom(vmDescriptor.getInstanceIdFd());
                    vm.claimInstance(service);
                }
            }
            return vm;
        } catch (VirtualMachineException | RuntimeException e) {
            // If anything goes wrong, delete any files created so far and the VM's directory
            try {
                vmInstanceCleanup(context, name);
            } catch (Exception innerException) {
                e.addSuppressed(innerException);
            }
            throw e;
        }
    }

    /**
     * Creates a virtual machine with the given name and config. Once a virtual machine is created
     * it is persisted until it is deleted by calling {@link #delete}. The created virtual machine
     * is in {@link #STATUS_STOPPED} state. To run the VM, call {@link #run}.
     */
    @GuardedBy("VirtualMachineManager.sCreateLock")
    @NonNull
    static VirtualMachine create(
            @NonNull Context context, @NonNull String name, @NonNull VirtualMachineConfig config)
            throws VirtualMachineException {
        VirtualizationService virtualizationService = VirtualizationService.getInstance();
        File vmDir = createVmDir(context, name);

        try {
            VirtualMachine vm = new VirtualMachine(context, name, config, virtualizationService);
            try {
                vm.mInstanceFilePath.createNewFile();
            } catch (IOException e) {
                throw new VirtualMachineException("failed to create instance image", e);
            }
            if (config.isEncryptedStorageEnabled()) {
                try {
                    vm.mEncryptedStoreFilePath.createNewFile();
                } catch (IOException e) {
                    throw new VirtualMachineException(
                            "failed to create encrypted storage image", e);
                }
            }

            IVirtualizationService service = virtualizationService.getBinder();

            if (vm.mInstanceIdPath != null) {
                try (FileOutputStream stream = new FileOutputStream(vm.mInstanceIdPath)) {
                    byte[] id = service.allocateInstanceId();
                    stream.write(id);
                } catch (FileNotFoundException e) {
                    throw new VirtualMachineException("instance_id file missing", e);
                } catch (IOException e) {
                    throw new VirtualMachineException("failed to persist instance_id", e);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                } catch (ServiceSpecificException | IllegalArgumentException e) {
                    throw new VirtualMachineException("failed to create instance_id", e);
                }
            }

            try (ParcelFileDescriptor fd =
                    ParcelFileDescriptor.open(vm.mInstanceFilePath, MODE_READ_WRITE)) {
                service.initializeWritablePartition(
                        fd, INSTANCE_FILE_SIZE, PartitionType.ANDROID_VM_INSTANCE);
            } catch (FileNotFoundException e) {
                throw new VirtualMachineException("instance image missing", e);
            } catch (IOException e) {
                throw new VirtualMachineException("failed to initialize instance partition", e);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } catch (ServiceSpecificException | IllegalArgumentException e) {
                throw new VirtualMachineException("failed to create instance partition", e);
            }

            if (config.isEncryptedStorageEnabled()) {
                try (ParcelFileDescriptor fd =
                        ParcelFileDescriptor.open(vm.mEncryptedStoreFilePath, MODE_READ_WRITE)) {
                    service.initializeWritablePartition(
                            fd, config.getEncryptedStorageBytes(), PartitionType.ENCRYPTEDSTORE);
                } catch (FileNotFoundException e) {
                    throw new VirtualMachineException("encrypted storage image missing", e);
                } catch (IOException e) {
                    throw new VirtualMachineException(
                            "failed to initialize encrypted store partition", e);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                } catch (ServiceSpecificException | IllegalArgumentException e) {
                    throw new VirtualMachineException(
                            "failed to create encrypted storage partition", e);
                }
            }
            // persist the config only after we pass all the checks above.
            config.serialize(vm.mConfigFilePath);
            return vm;
        } catch (VirtualMachineException | RuntimeException e) {
            // If anything goes wrong, delete any files created so far and the VM's directory
            try {
                vmInstanceCleanup(context, name);
            } catch (Exception innerException) {
                e.addSuppressed(innerException);
            }
            throw e;
        }
    }

    /** Loads a virtual machine that is already created before. */
    @GuardedBy("VirtualMachineManager.sCreateLock")
    @Nullable
    static VirtualMachine load(@NonNull Context context, @NonNull String name)
            throws VirtualMachineException {
        File thisVmDir = getVmDir(context, name);
        if (!thisVmDir.exists()) {
            // The VM doesn't exist.
            return null;
        }
        File configFilePath = new File(thisVmDir, CONFIG_FILE);
        VirtualMachineConfig config = VirtualMachineConfig.from(configFilePath);
        VirtualMachine vm =
                new VirtualMachine(context, name, config, VirtualizationService.getInstance());

        if (vm.mInstanceIdPath != null && !vm.mInstanceIdPath.exists()) {
            throw new VirtualMachineException("instance_id file missing");
        }
        if (!vm.mInstanceFilePath.exists()) {
            throw new VirtualMachineException("instance image missing");
        }
        if (config.isEncryptedStorageEnabled() && !vm.mEncryptedStoreFilePath.exists()) {
            throw new VirtualMachineException("Storage image missing");
        }
        // TODO(b/406258175): here we need to detect if migration is required.
        return vm;
    }

    @GuardedBy("VirtualMachineManager.sCreateLock")
    void delete(Context context, String name) throws VirtualMachineException {
        synchronized (mLock) {
            checkStopped();
            // Once we explicitly delete a VM it must remain permanently in the deleted state;
            // if a new VM is created with the same name (and files) that's unrelated.
            mWasDeleted = true;
        }
        vmInstanceCleanup(context, name);
    }

    // Delete the full VM directory and notify VirtualizationService to remove this
    // VM instance for housekeeping.
    @GuardedBy("VirtualMachineManager.sCreateLock")
    static void vmInstanceCleanup(Context context, String name) throws VirtualMachineException {
        File vmDir = getVmDir(context, name);
        File ceVmDir = getVmDir(context.createCredentialProtectedStorageContext(), name);
        notifyInstanceRemoval(vmDir, VirtualizationService.getInstance());
        try {
            deleteRecursively(vmDir);
            if (ceVmDir.exists() && !vmDir.getCanonicalPath().equals(ceVmDir.getCanonicalPath())) {
                deleteRecursively(ceVmDir);
            }
        } catch (IOException e) {
            throw new VirtualMachineException(e);
        }
    }

    private static void notifyInstanceRemoval(
            File vmDirectory, @NonNull VirtualizationService service) {
        File instanceIdFile = new File(vmDirectory, INSTANCE_ID_FILE);
        try {
            byte[] instanceId = Files.readAllBytes(instanceIdFile.toPath());
            service.getBinder().removeVmInstance(instanceId);
        } catch (Exception e) {
            // Deliberately ignoring error in removing VM instance. This potentially leads to
            // unaccounted instances in the VS' database. But, nothing much can be done by caller.
            Log.w(TAG, "Failed to notify VS to remove the VM instance", e);
        }
    }

    // Claim the instance. This notifies the global VS about the ownership of this
    // instance_id for housekeeping purpose.
    void claimInstance(@NonNull VirtualizationService service) throws VirtualMachineException {
        if (mInstanceIdPath != null) {
            try {
                byte[] instanceId = Files.readAllBytes(mInstanceIdPath.toPath());
                service.getBinder().claimVmInstance(instanceId);
            } catch (IOException e) {
                throw new VirtualMachineException("failed to read instance_id", e);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    @GuardedBy("VirtualMachineManager.sCreateLock")
    @NonNull
    private static File createVmDir(@NonNull Context context, @NonNull String name)
            throws VirtualMachineException {
        File vmDir = getVmDir(context, name);
        try {
            // We don't need to undo this even if VM creation fails.
            Files.createDirectories(vmDir.getParentFile().toPath());

            // The checking of the existence of this directory and the creation of it is done
            // atomically. If the directory already exists (i.e. the VM with the same name was
            // already created), FileAlreadyExistsException is thrown.
            Files.createDirectory(vmDir.toPath());
        } catch (FileAlreadyExistsException e) {
            throw new VirtualMachineException(
                    "virtual machine already exists",
                    e,
                    VirtualMachineException.CODE_NAME_ALREADY_EXISTS);
        } catch (IOException e) {
            throw new VirtualMachineException("failed to create directory for VM", e);
        }
        return vmDir;
    }

    @NonNull
    private static File getVmDir(@NonNull Context context, @NonNull String name) {
        if (name.contains(File.separator) || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid VM name: " + name);
        }
        File vmRoot = new File(context.getDataDir(), VM_DIR);
        return new File(vmRoot, name);
    }

    /**
     * Returns the name of this virtual machine. The name is unique in the package and can't be
     * changed.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the currently selected config of this virtual machine. There can be multiple virtual
     * machines sharing the same config. Even in that case, the virtual machines are completely
     * isolated from each other; they have different secrets. It is also possible that a virtual
     * machine can change its config, which can be done by calling {@link #setConfig}.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public VirtualMachineConfig getConfig() {
        synchronized (mLock) {
            return mConfig;
        }
    }

    /**
     * Returns the current status of this virtual machine.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @hide
     */
    @SystemApi
    @WorkerThread
    @Status
    public int getStatus() {
        IVirtualMachine virtualMachine;
        synchronized (mLock) {
            if (mWasDeleted) {
                return STATUS_DELETED;
            }
            virtualMachine = mVirtualMachine;
        }

        int status;
        if (virtualMachine == null) {
            status = STATUS_STOPPED;
        } else {
            try {
                status = stateToStatus(virtualMachine.getState());
            } catch (RemoteException e) {
                status = STATUS_STOPPED;
            }
        }
        if (status == STATUS_STOPPED && !mVmRootPath.exists()) {
            // A VM can quite happily keep running if its backing files have been deleted.
            // But once it stops, it's gone forever.
            synchronized (mLock) {
                mWasDeleted = true;
            }
            return STATUS_DELETED;
        }
        return status;
    }

    /**
     * Returns the cid of this VM
     *
     * @hide
     */
    public int getCid() {
        synchronized (mLock) {
            return mCid;
        }
    }

    private int stateToStatus(@VirtualMachineState int state) {
        switch (state) {
            case VirtualMachineState.STARTING:
            case VirtualMachineState.STARTED:
            case VirtualMachineState.READY:
            case VirtualMachineState.FINISHED:
                return STATUS_RUNNING;
            case VirtualMachineState.NOT_STARTED:
            case VirtualMachineState.DEAD:
            default:
                return STATUS_STOPPED;
        }
    }

    // Throw an appropriate exception if we have a running VM, or the VM has been deleted.
    @GuardedBy("mLock")
    private void checkStopped() throws VirtualMachineException {
        switch (getStatus()) {
            case STATUS_STOPPED:
                // no-op
                return;
            case STATUS_RUNNING:
                throw new VirtualMachineException(
                        "VM is not in stopped state",
                        VirtualMachineException.CODE_VIRTUAL_MACHINE_RUNNING);
            case STATUS_DELETED:
                throw new VirtualMachineException(
                        "VM has been deleted",
                        VirtualMachineException.CODE_VIRTUAL_MACHINE_DELETED);
        }
    }

    private void handleStopped(int cid, @VirtualMachineCallback.StopReason int reason) {
        synchronized (mLock) {
            // Since we just took the lock, we can't assume the stopped
            // notification is for the current VM.
            if (mCid == cid) {
                if (mStopHandled) {
                    return;
                }
                mStopHandled = true;
                dropVm();
            }
        }
    }


    /**
     * This should only be called when we know our VM has stopped; we no longer need to hold a
     * reference to it (this allows resources to be GC'd) and we no longer need to be informed of
     * memory pressure.
     */
    @GuardedBy("mLock")
    private void dropVm() {
        if (mInputEventExecutor != null) {
            mInputEventExecutor.shutdownNow();
            mInputEventExecutor = null;
        }
        if (mMemoryManagementCallbacks != null) {
            mContext.unregisterComponentCallbacks(mMemoryManagementCallbacks);
        }
        if (mVirtualMachine != null) {
            try {
                mVirtualMachine.asBinder().unlinkToDeath(mVMDeathRecipient, /* flags= */ 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "Ignore exception unlinkToDeath() for IVirtualMachine, exception=" + e);
            }
            mVirtualMachine = null;
            mCid = -1;
        }
        if (mVirtualizationService != null) {
            try {
                mVirtualizationService
                        .getBinder()
                        .asBinder()
                        .unlinkToDeath(mVSDeathRecipient, /* flags= */ 0);
            } catch (RuntimeException e) {
                Log.w(
                        TAG,
                        "Ignore exception unlinkToDeath() for IVirtualizationService, exception="
                                + e);
            }
            mVirtualizationService = null;
        }
        mCallbackTranslator = null;
    }

    /** If we have an IVirtualMachine in the running state return it, otherwise throw. */
    @GuardedBy("mLock")
    private IVirtualMachine getRunningVm() throws VirtualMachineException {
        switch (getStatus()) {
            case STATUS_STOPPED:
                throw new VirtualMachineException(
                        "VM is not in running state",
                        VirtualMachineException.CODE_VIRTUAL_MACHINE_STOPPED);
            case STATUS_RUNNING:
                return mVirtualMachine;
            case STATUS_DELETED:
                throw new VirtualMachineException(
                        "VM has been deleted",
                        VirtualMachineException.CODE_VIRTUAL_MACHINE_DELETED);
            default:
                // unreachable code. Just make compiler happy.
                return null;
        }
    }

    /**
     * Registers the callback object to get events from the virtual machine. If a callback was
     * already registered, it is replaced with the new one.
     *
     * @hide
     */
    @SystemApi
    public void setCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull VirtualMachineCallback callback) {
        synchronized (mLock) {
            mConfiguredCallback = callback;
            mConfiguredCallbackExecutor = executor;
            if (mCallbackTranslator != null) {
                synchronized (mCallbackTranslator.mLock) {
                    mCallbackTranslator.mCallback = callback;
                    mCallbackTranslator.mCallbackExecutor = executor;
                }
            }
        }
    }

    /**
     * Clears the currently registered callback.
     *
     * @hide
     */
    @SystemApi
    public void clearCallback() {
        synchronized (mLock) {
            mConfiguredCallback = null;
            mConfiguredCallbackExecutor = null;
            if (mCallbackTranslator != null) {
                synchronized (mCallbackTranslator.mLock) {
                    mCallbackTranslator.mCallback = null;
                    mCallbackTranslator.mCallbackExecutor = null;
                }
            }
        }
    }

    private android.system.virtualizationservice.VirtualMachineConfig
            createVirtualMachineConfigForRawFrom(VirtualMachineConfig vmConfig)
                    throws IllegalStateException, IOException {
        VirtualMachineRawConfig rawConfig = vmConfig.toVsRawConfig();

        // Handle input devices here
        List<InputDevice> inputDevices = new ArrayList<>();
        if (vmConfig.getCustomImageConfig() != null && rawConfig.displayConfig != null) {
            if (vmConfig.getCustomImageConfig().useTouch()) {
                ParcelFileDescriptor[] pfds = ParcelFileDescriptor.createSocketPair();
                mTouchSock = pfds[0];
                InputDevice.MultiTouch t = new InputDevice.MultiTouch();
                t.width = rawConfig.displayConfig.width;
                t.height = rawConfig.displayConfig.height;
                t.pfd = pfds[1];
                inputDevices.add(InputDevice.multiTouch(t));
            }
            if (vmConfig.getCustomImageConfig().useKeyboard()) {
                ParcelFileDescriptor[] pfds = ParcelFileDescriptor.createSocketPair();
                mKeySock = pfds[0];
                InputDevice.Keyboard k = new InputDevice.Keyboard();
                k.pfd = pfds[1];
                inputDevices.add(InputDevice.keyboard(k));
            }
            if (vmConfig.getCustomImageConfig().useMouse()) {
                ParcelFileDescriptor[] pfds = ParcelFileDescriptor.createSocketPair();
                mMouseSock = pfds[0];
                InputDevice.Mouse m = new InputDevice.Mouse();
                m.pfd = pfds[1];
                inputDevices.add(InputDevice.mouse(m));
            }
            if (vmConfig.getCustomImageConfig().useSwitches()) {
                ParcelFileDescriptor[] pfds = ParcelFileDescriptor.createSocketPair();
                mSwitchesSock = pfds[0];
                InputDevice.Switches s = new InputDevice.Switches();
                s.pfd = pfds[1];
                inputDevices.add(InputDevice.switches(s));
            }
            if (vmConfig.getCustomImageConfig().useTrackpad()) {
                ParcelFileDescriptor[] pfds = ParcelFileDescriptor.createSocketPair();
                mTrackpadSock = pfds[0];
                InputDevice.Trackpad t = new InputDevice.Trackpad();
                // TODO(b/347253952): make it configurable
                // Set a large square area to avoid aspect ratio distortion.
                // We only use a portion of this area (mapped from the screen).
                t.width = 3000;
                t.height = 3000;
                t.pfd = pfds[1];
                inputDevices.add(InputDevice.trackpad(t));
            }
        }
        rawConfig.inputDevices = inputDevices.toArray(new InputDevice[0]);

        // Handle network support
        if (vmConfig.getCustomImageConfig() != null) {
            rawConfig.networkSupported = vmConfig.getCustomImageConfig().useNetwork();
        }

        return android.system.virtualizationservice.VirtualMachineConfig.rawConfig(rawConfig);
    }

    private static record InputEvent(short type, short code, int value) {}

    /** @hide */
    public boolean sendKeyEvent(short hardwareKeyCode, boolean down) {
        if (mKeySock == null) {
            Log.d(TAG, "mKeySock == null");
            return false;
        }
        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_KEY = 0x01;
        short SYN_REPORT = 0x00;

        return writeEventsToSock(
                mKeySock,
                Arrays.asList(
                        new InputEvent(EV_KEY, hardwareKeyCode, down ? 1 : 0),
                        new InputEvent(EV_SYN, SYN_REPORT, 0)));
    }

    /** @hide */
    public boolean sendMouseEvent(MotionEvent event) {
        try {
            mInputEventQueue.add(
                    Pair.create(InputEventType.MOUSE, MotionEvent.obtainNoHistory(event)));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Exception in sendMouseEvent(), " + e.toString());
            return false;
        }
    }

    /** @hide */
    private boolean sendMouseEventInternal(MotionEvent event) {
        if (mMouseSock == null) {
            Log.d(TAG, "mMouseSock == null");
            return false;
        }
        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_REL = 0x02;
        short EV_KEY = 0x01;
        short REL_X = 0x00;
        short REL_Y = 0x01;
        short SYN_REPORT = 0x00;
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                int x = (int) event.getX();
                int y = (int) event.getY();
                return writeEventsToSock(
                        mMouseSock,
                        Arrays.asList(
                                new InputEvent(EV_REL, REL_X, x),
                                new InputEvent(EV_REL, REL_Y, y),
                                new InputEvent(EV_SYN, SYN_REPORT, 0)));
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                short BTN_LEFT = 0x110;
                short BTN_RIGHT = 0x111;
                short BTN_MIDDLE = 0x112;
                short keyCode;
                switch (event.getActionButton()) {
                    case MotionEvent.BUTTON_PRIMARY:
                        keyCode = BTN_LEFT;
                        break;
                    case MotionEvent.BUTTON_SECONDARY:
                        keyCode = BTN_RIGHT;
                        break;
                    case MotionEvent.BUTTON_TERTIARY:
                        keyCode = BTN_MIDDLE;
                        break;
                    default:
                        Log.d(TAG, event.toString());
                        return false;
                }
                return writeEventsToSock(
                        mMouseSock,
                        Arrays.asList(
                                new InputEvent(
                                        EV_KEY,
                                        keyCode,
                                        event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                                                ? 1
                                                : 0),
                                new InputEvent(EV_SYN, SYN_REPORT, 0)));
            case MotionEvent.ACTION_SCROLL:
                short REL_HWHEEL = 0x06;
                short REL_WHEEL = 0x08;
                int scrollX = (int) event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                int scrollY = (int) event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                boolean status = true;
                if (scrollX != 0) {
                    status &=
                            writeEventsToSock(
                                    mMouseSock,
                                    Arrays.asList(
                                            new InputEvent(EV_REL, REL_HWHEEL, scrollX),
                                            new InputEvent(EV_SYN, SYN_REPORT, 0)));
                } else if (scrollY != 0) {
                    status &=
                            writeEventsToSock(
                                    mMouseSock,
                                    Arrays.asList(
                                            new InputEvent(EV_REL, REL_WHEEL, scrollY),
                                            new InputEvent(EV_SYN, SYN_REPORT, 0)));
                } else {
                    Log.d(TAG, event.toString());
                    return false;
                }
                return status;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_DOWN:
                // Ignored because it's handled by ACTION_BUTTON_PRESS and ACTION_BUTTON_RELEASE
                return true;
            default:
                Log.d(TAG, event.toString());
                return false;
        }
    }

    /** @hide */
    public boolean sendMultiTouchEvent(MotionEvent event) {
        try {
            mInputEventQueue.add(
                    Pair.create(InputEventType.TOUCH, MotionEvent.obtainNoHistory(event)));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Exception in sendMultiTouchEvent(), " + e.toString());
            return false;
        }
    }

    /** @hide */
    private boolean sendMultiTouchEventInternal(MotionEvent event) {
        if (mTouchSock == null) {
            Log.d(TAG, "mTouchSock == null");
            return false;
        }
        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_ABS = 0x03;
        short EV_KEY = 0x01;
        short BTN_TOUCH = 0x14a;
        short ABS_X = 0x00;
        short ABS_Y = 0x01;
        short SYN_REPORT = 0x00;
        short ABS_MT_SLOT = 0x2f;
        short ABS_MT_POSITION_X = 0x35;
        short ABS_MT_POSITION_Y = 0x36;
        short ABS_MT_TRACKING_ID = 0x39;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                List<InputEvent> events =
                        new ArrayList<>(
                                event.getPointerCount() * 6 /*InputEvent per a pointer*/
                                        + 1 /*SYN*/);
                for (int actionIdx = 0; actionIdx < event.getPointerCount(); actionIdx++) {
                    int pointerId = event.getPointerId(actionIdx);
                    int x = (int) (event.getX(actionIdx) * touchScale);
                    int y = (int) (event.getY(actionIdx) * touchScale);
                    events.add(new InputEvent(EV_ABS, ABS_MT_SLOT, pointerId));
                    events.add(new InputEvent(EV_ABS, ABS_MT_TRACKING_ID, pointerId));
                    events.add(new InputEvent(EV_ABS, ABS_MT_POSITION_X, x));
                    events.add(new InputEvent(EV_ABS, ABS_MT_POSITION_Y, y));
                    events.add(new InputEvent(EV_ABS, ABS_X, x));
                    events.add(new InputEvent(EV_ABS, ABS_Y, y));
                }
                events.add(new InputEvent(EV_SYN, SYN_REPORT, 0));
                return writeEventsToSock(mTouchSock, events);
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                break;
            default:
                return false;
        }

        boolean down =
                event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN;
        int actionIdx = event.getActionIndex();
        int pointerId = event.getPointerId(actionIdx);
        int x = (int) (event.getX(actionIdx) * touchScale);
        int y = (int) (event.getY(actionIdx) * touchScale);
        return writeEventsToSock(
                mTouchSock,
                Arrays.asList(
                        new InputEvent(EV_KEY, BTN_TOUCH, down ? 1 : 0),
                        new InputEvent(EV_ABS, ABS_MT_SLOT, pointerId),
                        new InputEvent(EV_ABS, ABS_MT_TRACKING_ID, down ? pointerId : -1),
                        new InputEvent(EV_ABS, ABS_MT_POSITION_X, x),
                        new InputEvent(EV_ABS, ABS_MT_POSITION_Y, y),
                        new InputEvent(EV_ABS, ABS_X, x),
                        new InputEvent(EV_ABS, ABS_Y, y),
                        new InputEvent(EV_SYN, SYN_REPORT, 0)));
    }

    /** @hide */
    public boolean sendLidEvent(boolean close) {
        if (mSwitchesSock == null) {
            Log.d(TAG, "mSwitcheSock == null");
            return false;
        }

        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_SW = 0x05;
        short SW_LID = 0x00;
        short SYN_REPORT = 0x00;
        return writeEventsToSock(
                mSwitchesSock,
                Arrays.asList(
                        new InputEvent(EV_SW, SW_LID, close ? 1 : 0),
                        new InputEvent(EV_SYN, SYN_REPORT, 0)));
    }

    /** @hide */
    public boolean sendTabletModeEvent(boolean tabletMode) {
        if (mSwitchesSock == null) {
            Log.d(TAG, "mSwitcheSock == null");
            return false;
        }

        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_SW = 0x05;
        short SW_TABLET_MODE = 0x01;
        short SYN_REPORT = 0x00;
        return writeEventsToSock(
                mSwitchesSock,
                Arrays.asList(
                        new InputEvent(EV_SW, SW_TABLET_MODE, tabletMode ? 1 : 0),
                        new InputEvent(EV_SYN, SYN_REPORT, 0)));
    }

    /** @hide */
    public boolean sendTrackpadEvent(MotionEvent event) {
        try {
            mInputEventQueue.add(
                    Pair.create(InputEventType.TRACKPAD, MotionEvent.obtainNoHistory(event)));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Exception in sendTrackpadEvent(), " + e.toString());
            return false;
        }
    }

    /** @hide */
    private boolean sendTrackpadEventInternal(MotionEvent event) {
        if (mTrackpadSock == null) {
            Log.d(TAG, "mTrackpadSock == null");
            return false;
        }
        // from include/uapi/linux/input-event-codes.h in the kernel.
        short EV_SYN = 0x00;
        short EV_ABS = 0x03;
        short EV_KEY = 0x01;
        short BTN_TOUCH = 0x14a;
        short BTN_TOOL_FINGER = 0x145;
        short BTN_TOOL_DOUBLETAP = 0x14d;
        short BTN_TOOL_TRIPLETAP = 0x14e;
        short BTN_TOOL_QUADTAP = 0x14f;
        short ABS_X = 0x00;
        short ABS_Y = 0x01;
        short SYN_REPORT = 0x00;
        short ABS_MT_SLOT = 0x2f;
        short ABS_MT_TOUCH_MAJOR = 0x30;
        short ABS_MT_TOUCH_MINOR = 0x31;
        short ABS_MT_WIDTH_MAJOR = 0x32;
        short ABS_MT_WIDTH_MINOR = 0x33;
        short ABS_MT_ORIENTATION = 0x34;
        short ABS_MT_POSITION_X = 0x35;
        short ABS_MT_POSITION_Y = 0x36;
        short ABS_MT_TOOL_TYPE = 0x37;
        short ABS_MT_BLOB_ID = 0x38;
        short ABS_MT_TRACKING_ID = 0x39;
        short ABS_MT_PRESSURE = 0x3a;
        short ABS_MT_DISTANCE = 0x3b;
        short ABS_MT_TOOL_X = 0x3c;
        short ABS_MT_TOOL_Y = 0x3d;
        short ABS_PRESSURE = 0x18;
        short ABS_TOOL_WIDTH = 0x1c;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                short BTN_LEFT = 0x110;
                short keyCode;
                switch (event.getActionButton()) {
                    case MotionEvent.BUTTON_PRIMARY:
                        keyCode = BTN_LEFT;
                        break;
                    default:
                        Log.d(TAG, event.toString());
                        return false;
                }
                return writeEventsToSock(
                        mTrackpadSock,
                        Arrays.asList(
                                new InputEvent(
                                        EV_KEY,
                                        keyCode,
                                        event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                                                ? 1
                                                : 0),
                                new InputEvent(EV_SYN, SYN_REPORT, 0)));
            case MotionEvent.ACTION_MOVE:
                List<InputEvent> events =
                        new ArrayList<>(
                                event.getPointerCount() * 10 /*InputEvent per a pointer*/
                                        + 1 /*SYN*/);
                for (int actionIdx = 0; actionIdx < event.getPointerCount(); actionIdx++) {
                    // Linux expects pointerId to be positive.
                    int pointerId = event.getPointerId(actionIdx) + 1;
                    int x = (int) event.getX(actionIdx);
                    int y = (int) event.getY(actionIdx);
                    events.add(new InputEvent(EV_ABS, ABS_MT_SLOT, pointerId));
                    events.add(new InputEvent(EV_ABS, ABS_MT_TRACKING_ID, pointerId));
                    events.add(new InputEvent(EV_ABS, ABS_MT_POSITION_X, x));
                    events.add(new InputEvent(EV_ABS, ABS_MT_POSITION_Y, y));
                    events.add(
                            new InputEvent(
                                    EV_ABS,
                                    ABS_MT_TOUCH_MAJOR,
                                    (short) event.getTouchMajor(actionIdx)));
                    events.add(
                            new InputEvent(
                                    EV_ABS,
                                    ABS_MT_TOUCH_MINOR,
                                    (short) event.getTouchMinor(actionIdx)));
                    events.add(new InputEvent(EV_ABS, ABS_X, x));
                    events.add(new InputEvent(EV_ABS, ABS_Y, y));
                    events.add(new InputEvent(EV_ABS, ABS_PRESSURE, TRACKPAD_PRESSURE_PRESSED));
                    events.add(new InputEvent(EV_ABS, ABS_MT_PRESSURE, TRACKPAD_PRESSURE_PRESSED));
                }
                events.add(new InputEvent(EV_SYN, SYN_REPORT, 0));
                return writeEventsToSock(mTrackpadSock, events);
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                break;
            default:
                return false;
        }

        boolean down =
                event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN;
        int actionIdx = event.getActionIndex();
        // Linux expects pointerId to be positive.
        int pointerId = event.getPointerId(actionIdx) + 1;
        int x = (int) event.getX(actionIdx);
        int y = (int) event.getY(actionIdx);
        return writeEventsToSock(
                mTrackpadSock,
                Arrays.asList(
                        new InputEvent(EV_KEY, BTN_TOUCH, down ? 1 : 0),
                        new InputEvent(
                                EV_KEY,
                                BTN_TOOL_FINGER,
                                down && event.getPointerCount() == 1 ? 1 : 0),
                        new InputEvent(
                                EV_KEY, BTN_TOOL_DOUBLETAP, event.getPointerCount() == 2 ? 1 : 0),
                        new InputEvent(
                                EV_KEY, BTN_TOOL_TRIPLETAP, event.getPointerCount() == 3 ? 1 : 0),
                        new InputEvent(
                                EV_KEY, BTN_TOOL_QUADTAP, event.getPointerCount() > 4 ? 1 : 0),
                        new InputEvent(EV_ABS, ABS_MT_SLOT, pointerId),
                        new InputEvent(EV_ABS, ABS_MT_TRACKING_ID, down ? pointerId : -1),
                        new InputEvent(EV_ABS, ABS_MT_TOOL_TYPE, 0 /* MT_TOOL_FINGER */),
                        new InputEvent(EV_ABS, ABS_MT_POSITION_X, x),
                        new InputEvent(EV_ABS, ABS_MT_POSITION_Y, y),
                        new InputEvent(
                                EV_ABS, ABS_MT_TOUCH_MAJOR, (short) event.getTouchMajor(actionIdx)),
                        new InputEvent(
                                EV_ABS, ABS_MT_TOUCH_MINOR, (short) event.getTouchMinor(actionIdx)),
                        new InputEvent(EV_ABS, ABS_X, x),
                        new InputEvent(EV_ABS, ABS_Y, y),
                        new InputEvent(EV_ABS, ABS_PRESSURE, TRACKPAD_PRESSURE_PRESSED),
                        new InputEvent(EV_ABS, ABS_MT_PRESSURE, TRACKPAD_PRESSURE_PRESSED),
                        new InputEvent(EV_SYN, SYN_REPORT, 0)));
    }

    /** @hide */
    public long getActualMemoryBalloonBytes() {
        long bytes = 0;

        if (mMemoryManagementCallbacks != null) {
            Log.d(TAG, "Auto balloon enabled in getActualMemoryBalloonBytes");
            return bytes;
        }

        synchronized (mLock) {
            try {
                if (mVirtualMachine != null) {
                    bytes = mVirtualMachine.getActualMemoryBalloonBytes();
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot getActualMemoryBalloonBytes", e);
            }
        }

        return bytes;
    }

    /** @hide */
    public void setMemoryBalloon(long bytes) {
        if (mMemoryManagementCallbacks != null) {
            Log.d(TAG, "Auto balloon enabled in setMemoryBalloon");
            return;
        }

        synchronized (mLock) {
            try {
                if (mVirtualMachine != null) {
                    mVirtualMachine.setMemoryBalloon(bytes);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot setMemoryBalloon", e);
            }
        }
    }

    /**
     * Adds a new display to the virtual machine.
     *
     * @param config The configuration for the new display.
     * @throws VirtualMachineException if the display could not be added.
     * @hide
     */
    public void addDisplay(@NonNull DisplayConfig config) throws VirtualMachineException {
        synchronized (mLock) {
            if (mVirtualMachine == null) {
                throw new VirtualMachineException("Failed to add display: VM is not running");
            }
            try {
                mVirtualMachine.addDisplay(config);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new VirtualMachineException("Failed to add display", e);
            }
        }
    }

    /**
     * Removes a display from the virtual machine.
     *
     * @param displayId The ID of the display to remove.
     * @throws VirtualMachineException if the display could not be removed.
     * @hide
     */
    public void removeDisplay(int displayId) throws VirtualMachineException {
        synchronized (mLock) {
            if (mVirtualMachine == null) {
                throw new VirtualMachineException("Failed to remove display: VM is not running");
            }
            try {
                mVirtualMachine.removeDisplay(displayId);
            } catch (RemoteException | ServiceSpecificException e) {
                throw new VirtualMachineException("Failed to remove display", e);
            }
        }
    }

    /**
     * Returns the list of displays currently attached to the virtual machine.
     *
     * @return A list of {@link VirtualMachineDisplay} objects.
     * @throws VirtualMachineException if the displays could not be retrieved.
     * @hide
     */
    @NonNull
    public List<VirtualMachineDisplay> getDisplays() throws VirtualMachineException {
        synchronized (mLock) {
            if (mVirtualMachine == null) {
                throw new VirtualMachineException("Failed to get displays: VM is not running");
            }
            try {
                return Arrays.asList(mVirtualMachine.getDisplays());
            } catch (RemoteException | ServiceSpecificException e) {
                throw new VirtualMachineException("Failed to get displays", e);
            }
        }
    }

    /** @hide */
    public void setMemoryBalloonByPercent(int percent) {
        if (percent < 0 || percent > 100) {
            Log.e(TAG, String.format("Invalid percent value: %d", percent));
            return;
        }
        synchronized (mLock) {
            try {
                if (mVirtualMachine != null && mVirtualMachine.isMemoryBalloonEnabled()) {
                    long bytes = mConfig.getMemoryBytes();
                    mVirtualMachine.setMemoryBalloon(bytes * percent / 100);
                }
            } catch (RemoteException | ServiceSpecificException e) {
                Log.w(TAG, "Cannot setMemoryBalloon", e);
            }
        }
    }

    private boolean writeEventsToSock(ParcelFileDescriptor sock, List<InputEvent> evtList) {
        ByteBuffer byteBuffer =
                ByteBuffer.allocate(8 /* (type: u16 + code: u16 + value: i32) */ * evtList.size());
        byteBuffer.clear();
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (InputEvent e : evtList) {
            byteBuffer.putShort(e.type);
            byteBuffer.putShort(e.code);
            byteBuffer.putInt(e.value);
        }
        try {
            IoBridge.write(
                    sock.getFileDescriptor(), byteBuffer.array(), 0, byteBuffer.array().length);
        } catch (IOException e) {
            Log.d(TAG, "cannot send event", e);
            return false;
        }
        return true;
    }

    private android.system.virtualizationservice.VirtualMachineConfig
            createVirtualMachineConfigForAppFrom(
                    VirtualMachineConfig vmConfig, IVirtualizationService service)
                    throws RemoteException, IOException, VirtualMachineException {
        VirtualMachineAppConfig appConfig = vmConfig.toVsConfig(mContext.getPackageManager());
        appConfig.instanceImage = ParcelFileDescriptor.open(mInstanceFilePath, MODE_READ_WRITE);
        appConfig.name = mName;
        if (mInstanceIdPath != null) {
            appConfig.instanceId = Files.readAllBytes(mInstanceIdPath.toPath());
        } else {
            // FEATURE_LLPVM_CHANGES is disabled, instance_id is not used.
            appConfig.instanceId = new byte[64];
        }
        if (mEncryptedStoreFilePath != null) {
            appConfig.encryptedStorageImage =
                    ParcelFileDescriptor.open(mEncryptedStoreFilePath, MODE_READ_WRITE);
        }

        if (!vmConfig.getExtraApks().isEmpty()) {
            // Extra APKs were specified directly, rather than via config file.
            // We've already populated the file names for the extra APKs and IDSigs
            // (via setupExtraApks). But we also need to open the APK files and add
            // fds for them to the payload config.
            // This isn't needed when the extra APKs are specified in a config file -
            // then
            // Virtualization Manager opens them itself.
            List<ParcelFileDescriptor> extraApkFiles = new ArrayList<>(mExtraApks.size());
            for (ApkSpec extraApk : mExtraApks) {
                try {
                    extraApkFiles.add(ParcelFileDescriptor.open(extraApk.apk, MODE_READ_ONLY));
                } catch (FileNotFoundException e) {
                    throw new VirtualMachineException(
                            "Failed to open extra APK",
                            e,
                            VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
                }
            }
            appConfig.payload.getPayloadConfig().extraApks = extraApkFiles;
        }

        appConfig.encryptedStoreKEK = mEncryptedStoreKEK;
        try {
            createIdSigsAndUpdateConfig(service, appConfig);
        } catch (FileNotFoundException e) {
            throw new VirtualMachineException("Failed to generate APK signature", e);
        }
        return android.system.virtualizationservice.VirtualMachineConfig.appConfig(appConfig);
    }

    /**
     * Runs this virtual machine. The returning of this method however doesn't mean that the VM has
     * actually started running or the OS has booted there. Such events can be notified by
     * registering a callback using {@link #setCallback} before calling {@code run()}. There is no
     * limit other than available memory that limits the number of virtual machines that can run at
     * the same time.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the virtual machine is not stopped or could not be
     *     started.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @RequiresPermission(MANAGE_VIRTUAL_MACHINE_PERMISSION)
    public void run() throws VirtualMachineException {
        synchronized (VirtualMachineManager.sCreateLock) {
            synchronized (mLock) {
                checkStopped();

                mVirtualizationService = VirtualizationService.getInstance();
                mStopHandled = false;

            try {
                mIdsigFilePath.createNewFile();
                for (ApkSpec extraApk : mExtraApks) {
                    extraApk.idsig.createNewFile();
                }
                for (ApkSpec tenantApk : mTenantApks) {
                    tenantApk.idsig.createNewFile();
                }
            } catch (IOException e) {
                // If the file already exists, exception is not thrown.
                throw new VirtualMachineException("Failed to create APK signature file", e);
            }

            IVirtualizationService service = mVirtualizationService.getBinder();

            try {
                if (mConnectVmConsole) {
                    createPtyConsole();
                }

                if (mVmOutputCaptured) {
                    createVmOutputPipes();
                }

                if (mVmConsoleInputSupported) {
                    createVmInputPipes();
                }

                ParcelFileDescriptor consoleOutFd = null;
                if (mConnectVmConsole && mVmOutputCaptured) {
                    // If we are enabling output pipes AND the host console, then we tee the console
                    // output to both.
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    mTeeConsoleOutReader = pipe[0];
                    mTeeConsoleOutWriter = pipe[1];
                    consoleOutFd = mTeeConsoleOutWriter;
                    TeeWorker tee =
                            new TeeWorker(
                                    mName + " console",
                                    new FileInputStream(mTeeConsoleOutReader.getFileDescriptor()),
                                    List.of(
                                            new FileOutputStream(mPtyFd.getFileDescriptor()),
                                            new FileOutputStream(
                                                    mConsoleOutWriter.getFileDescriptor())));
                    // If the VM is stopped then the tee worker thread would get an EOF or read()
                    // error which would tear down itself.
                    mConsoleExecutor.execute(tee);
                } else if (mConnectVmConsole) {
                    consoleOutFd = mPtyFd;
                } else if (mVmOutputCaptured) {
                    consoleOutFd = mConsoleOutWriter;
                }
                mInputEventExecutor = Executors.newSingleThreadExecutor();
                mInputEventExecutor.execute(
                        () -> {
                            while (true) {
                                try {
                                    Pair<InputEventType, MotionEvent> event =
                                            mInputEventQueue.take();
                                    switch (event.first) {
                                        case TOUCH:
                                            sendMultiTouchEventInternal(event.second);
                                            break;
                                        case TRACKPAD:
                                            sendTrackpadEventInternal(event.second);
                                            break;
                                        case MOUSE:
                                            sendMouseEventInternal(event.second);
                                            break;
                                    }
                                    event.second.recycle();
                                } catch (Exception e) {
                                    Log.w(
                                            TAG,
                                            "Exception in input event executor. Maybe shutting"
                                                    + " down? "
                                                    + e.toString());
                                }
                            }
                        });
                ParcelFileDescriptor consoleInFd = null;
                if (mConnectVmConsole) {
                    consoleInFd = mPtyFd;
                } else if (mVmConsoleInputSupported) {
                    consoleInFd = mConsoleInReader;
                }

                VirtualMachineConfig vmConfig = getConfig();
                android.system.virtualizationservice.VirtualMachineConfig vmConfigParcel =
                        vmConfig.getCustomImageConfig() != null
                                ? createVirtualMachineConfigForRawFrom(vmConfig)
                                : createVirtualMachineConfigForAppFrom(vmConfig, service);

                if (vmConfig.isEncryptedStorageEnabled()) {
                    service.setEncryptedStorageSize(
                            ParcelFileDescriptor.open(mEncryptedStoreFilePath, MODE_READ_WRITE),
                            vmConfig.getEncryptedStorageBytes());
                }

                mVirtualMachine =
                        service.createVm(
                                vmConfigParcel, consoleOutFd, consoleInFd, mLogWriter, null);
                mCid = mVirtualMachine.getCid();
                mCallbackTranslator =
                        new CallbackTranslator(
                                this, service, mConfiguredCallback, mConfiguredCallbackExecutor);
                mVirtualMachine.registerCallback(mCallbackTranslator);

                // Copy to a local variable so that the lambdas below are not
                // updated when mCid or mCallbackTranslator are mutated.
                final int cid = mCid;
                final CallbackTranslator callbackTranslator = mCallbackTranslator;
                mVSDeathRecipient =
                        () ->
                                callbackTranslator.onDied(
                                        cid, STOP_REASON_VIRTUALIZATION_SERVICE_DIED);
                mVMDeathRecipient =
                        () ->
                                callbackTranslator.onDied(
                                        cid, STOP_REASON_VIRTUALIZATION_SERVICE_DIED);
                mVirtualMachine.asBinder().linkToDeath(mVMDeathRecipient, /* flags= */ 0);
                service.asBinder().linkToDeath(mVSDeathRecipient, /* flags= */ 0);

                if (mMemoryManagementCallbacks != null) {
                    mContext.registerComponentCallbacks(mMemoryManagementCallbacks);
                }
                // TODO(b/406258175): here we need to register new callback to handle encrypted
                // store KEK.
                mVirtualMachine.start();

                if (mConnectVmConsole) {
                    mVirtualMachine.setHostConsoleName(getHostConsoleName());
                }
            } catch (IOException e) {
                throw new VirtualMachineException("failed to persist files", e);
            } catch (IllegalStateException | ServiceSpecificException e) {
                throw new VirtualMachineException(e);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }
    }

    private void createIdSigsAndUpdateConfig(
            IVirtualizationService service, VirtualMachineAppConfig appConfig)
            throws RemoteException, FileNotFoundException {
        // Fill the idsig file by hashing the apk
        service.createOrUpdateIdsigFile(
                appConfig.apk, ParcelFileDescriptor.open(mIdsigFilePath, MODE_READ_WRITE));

        for (ApkSpec extraApk : mExtraApks) {
            service.createOrUpdateIdsigFile(
                    ParcelFileDescriptor.open(extraApk.apk, MODE_READ_ONLY),
                    ParcelFileDescriptor.open(extraApk.idsig, MODE_READ_WRITE));
        }

        // Re-open idsig files in read-only mode
        appConfig.idsig = ParcelFileDescriptor.open(mIdsigFilePath, MODE_READ_ONLY);
        List<ParcelFileDescriptor> extraIdsigs = new ArrayList<>();
        for (ApkSpec extraApk : mExtraApks) {
            extraIdsigs.add(ParcelFileDescriptor.open(extraApk.idsig, MODE_READ_ONLY));
        }
        appConfig.extraIdsigs = extraIdsigs;

        List<ParcelFileDescriptor> tenantApks = new ArrayList<>();
        List<ParcelFileDescriptor> tenantIdsigs = new ArrayList<>();
        for (ApkSpec apk : mTenantApks) {
            service.createOrUpdateIdsigFile(
                    ParcelFileDescriptor.open(apk.apk, MODE_READ_ONLY),
                    ParcelFileDescriptor.open(apk.idsig, MODE_READ_WRITE));
            // Re-open idsig files in read-only mode
            tenantApks.add(ParcelFileDescriptor.open(apk.apk, MODE_READ_ONLY));
            tenantIdsigs.add(ParcelFileDescriptor.open(apk.idsig, MODE_READ_ONLY));
        }
        appConfig.tenantApks = tenantApks;
        appConfig.tenantIdsigs = tenantIdsigs;
    }

    @GuardedBy("mLock")
    private void createVmOutputPipes() throws VirtualMachineException {
        try {
            if (mConsoleOutReader == null || mConsoleOutWriter == null) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                mConsoleOutReader = pipe[0];
                mConsoleOutWriter = pipe[1];
            }

            if (mLogReader == null || mLogWriter == null) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                mLogReader = pipe[0];
                mLogWriter = pipe[1];
            }
        } catch (IOException e) {
            throw new VirtualMachineException("Failed to create output stream for VM", e);
        }
    }

    @GuardedBy("mLock")
    private void createVmInputPipes() throws VirtualMachineException {
        try {
            if (mConsoleInReader == null || mConsoleInWriter == null) {
                if (mConnectVmConsole) {
                    // If we are enabling input pipes AND the host console, then we should just use
                    // the host pty peer end as the console write end.
                    createPtyConsole();
                    mConsoleInReader = mPtyFd.dup();
                    mConsoleInWriter = mPtsFd.dup();
                } else {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    mConsoleInReader = pipe[0];
                    mConsoleInWriter = pipe[1];
                }
            }
        } catch (IOException e) {
            throw new VirtualMachineException("Failed to create input stream for VM", e);
        }
    }

    @FunctionalInterface
    private static interface OpenPtyCallback {
        public void apply(FileDescriptor mfd, FileDescriptor sfd, byte[] name);
    }

    // Opens a pty and set the master end to raw mode and O_NONBLOCK.
    private static native void nativeOpenPtyRawNonblock(OpenPtyCallback resultCallback)
            throws IOException;

    @GuardedBy("mLock")
    private void createPtyConsole() throws VirtualMachineException {
        if (mPtyFd != null && mPtsFd != null) {
            return;
        }
        List<FileDescriptor> fd = new ArrayList<>(2);
        StringBuilder nameBuilder = new StringBuilder();
        try {
            try {
                nativeOpenPtyRawNonblock(
                        (FileDescriptor mfd, FileDescriptor sfd, byte[] ptsName) -> {
                            fd.add(mfd);
                            fd.add(sfd);
                            nameBuilder.append(new String(ptsName, StandardCharsets.UTF_8));
                        });
            } catch (Exception e) {
                fd.forEach(IoUtils::closeQuietly);
                throw e;
            }
        } catch (IOException e) {
            throw new VirtualMachineException(
                    "Failed to create host console to connect to the VM console", e);
        }
        mPtyFd = new ParcelFileDescriptor(fd.get(0));
        mPtsFd = new ParcelFileDescriptor(fd.get(1));
        mPtsName = nameBuilder.toString();
        Log.d(TAG, "Serial console device: " + mPtsName);
    }

    /**
     * Returns the name of the peer end (ptsname) of the host console. The host console is only
     * available if the {@link VirtualMachineConfig} specifies that a host console should
     * {@linkplain VirtualMachineConfig#isConnectVmConsole connect} to the VM console.
     *
     * @throws VirtualMachineException if the host pseudoterminal could not be created, or
     *     connecting to the VM console is not enabled.
     * @hide
     */
    @NonNull
    private String getHostConsoleName() throws VirtualMachineException {
        if (!mConnectVmConsole) {
            throw new VirtualMachineException(
                    "Host console is not enabled", VirtualMachineException.CODE_FEATURE_DISABLED);
        }
        synchronized (mLock) {
            createPtyConsole();
            return mPtsName;
        }
    }

    /**
     * Returns the stream object representing the console output from the virtual machine. The
     * console output is only available if the {@link VirtualMachineConfig} specifies that it should
     * be {@linkplain VirtualMachineConfig#isVmOutputCaptured captured}.
     *
     * <p>If you turn on output capture, you must consume data from {@code getConsoleOutput} -
     * because otherwise the code in the VM may get blocked when the pipe buffer fills up.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the stream could not be created, or capturing is turned
     *     off.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public InputStream getConsoleOutput() throws VirtualMachineException {
        if (!mVmOutputCaptured) {
            throw new VirtualMachineException(
                    "Capturing vm outputs is turned off",
                    VirtualMachineException.CODE_FEATURE_DISABLED);
        }
        synchronized (mLock) {
            createVmOutputPipes();
            return new FileInputStream(mConsoleOutReader.getFileDescriptor());
        }
    }

    /**
     * Returns the stream object representing the console input to the virtual machine. The console
     * input is only available if the {@link VirtualMachineConfig} specifies that it should be
     * {@linkplain VirtualMachineConfig#isVmConsoleInputSupported supported}.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the stream could not be created, or console input is not
     *     supported.
     * @hide
     */
    @TestApi
    @WorkerThread
    @NonNull
    public OutputStream getConsoleInput() throws VirtualMachineException {
        if (!mVmConsoleInputSupported) {
            throw new VirtualMachineException(
                    "VM console input is not supported",
                    VirtualMachineException.CODE_FEATURE_DISABLED);
        }
        synchronized (mLock) {
            createVmInputPipes();
            return new FileOutputStream(mConsoleInWriter.getFileDescriptor());
        }
    }

    /**
     * Returns the stream object representing the log output from the virtual machine. The log
     * output is only available if the VirtualMachineConfig specifies that it should be {@linkplain
     * VirtualMachineConfig#isVmOutputCaptured captured}.
     *
     * <p>If you turn on output capture, you must consume data from {@code getLogOutput} - because
     * otherwise the code in the VM may get blocked when the pipe buffer fills up.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the stream could not be created, or capturing is turned
     *     off.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public InputStream getLogOutput() throws VirtualMachineException {
        if (!mVmOutputCaptured) {
            throw new VirtualMachineException(
                    "Capturing vm outputs is turned off",
                    VirtualMachineException.CODE_FEATURE_DISABLED);
        }
        synchronized (mLock) {
            createVmOutputPipes();
            return new FileInputStream(mLogReader.getFileDescriptor());
        }
    }

    /**
     * Stops this virtual machine. Stopping a virtual machine is like pulling the plug on a real
     * computer; the machine halts immediately. Software running on the virtual machine is not
     * notified of the event. Writes to {@linkplain
     * VirtualMachineConfig.Builder#setEncryptedStorageBytes encrypted storage} might not be
     * persisted, and the instance might be left in an inconsistent state.
     *
     * <p>For a graceful shutdown, you could request the payload to call {@code exit()}, e.g. via a
     * {@linkplain #connectToVsockServer binder request}, and wait for {@link
     * VirtualMachineCallback#onPayloadFinished} to be called.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the virtual machine is not running or could not be
     *     stopped.
     * @hide
     */
    @SystemApi
    @WorkerThread
    public void stop() throws VirtualMachineException {
        synchronized (mLock) {
            if (getStatus() == STATUS_RUNNING) {
                try {
                    mVirtualMachine.stop();
                    dropVm();
                    return;
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                } catch (ServiceSpecificException e) {
                    throw new VirtualMachineException(e);
                }
            }
        }
        throw new VirtualMachineException(
                "VM is not running", VirtualMachineException.CODE_VIRTUAL_MACHINE_STOPPED);
    }

    /** @hide */
    public void suspend() throws VirtualMachineException {
        synchronized (mLock) {
            if (mVirtualMachine == null) {
                throw new VirtualMachineException(
                        "VM is not running", VirtualMachineException.CODE_VIRTUAL_MACHINE_STOPPED);
            }
            try {
                mVirtualMachine.suspend();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } catch (ServiceSpecificException e) {
                throw new VirtualMachineException(e);
            }
        }
    }

    /** @hide */
    public void resume() throws VirtualMachineException {
        synchronized (mLock) {
            if (mVirtualMachine == null) {
                throw new VirtualMachineException(
                        "VM is not running", VirtualMachineException.CODE_VIRTUAL_MACHINE_STOPPED);
            }
            try {
                mVirtualMachine.resume();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } catch (ServiceSpecificException e) {
                throw new VirtualMachineException(e);
            }
        }
    }

    /**
     * Stops this virtual machine, if it is running.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @see #stop()
     * @hide
     */
    @SystemApi
    @WorkerThread
    @Override
    public void close() {
        try {
            stop();

            synchronized (mLock) {
                if (mVirtualizationService != null) {
                    mVirtualizationService
                            .getBinder()
                            .asBinder()
                            .unlinkToDeath(mVSDeathRecipient, /* flags= */ 0);
                }
            }
        } catch (Exception e) {
            // Deliberately ignored; this almost certainly means the VM exited just as
            // we tried to stop it.
            Log.i(TAG, "Ignoring error on close()", e);
        }
    }

    private static void deleteRecursively(File dir) throws IOException {
        // Note: This doesn't follow symlinks, which is important. Instead they are just deleted
        // (and Files.delete deletes the link not the target).
        Files.walkFileTree(
                dir.toPath(),
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e)
                            throws IOException {
                        // Directory is deleted after we've visited (deleted) all its contents, so
                        // it
                        // should be empty by now.
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * Changes the config of this virtual machine to a new one. This can be used to adjust things
     * like the number of CPU and size of the RAM, depending on the situation (e.g. the size of the
     * application to run on the virtual machine, etc.)
     *
     * <p>The new config must be {@linkplain VirtualMachineConfig#isCompatibleWith compatible with}
     * the existing config.
     *
     * <p>NOTE: Modification of the encrypted storage size is restricted to expansion only and is an
     * irreversible operation.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @return the old config
     * @throws VirtualMachineException if the virtual machine is not stopped, or the new config is
     *     incompatible.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public VirtualMachineConfig setConfig(@NonNull VirtualMachineConfig newConfig)
            throws VirtualMachineException {
        synchronized (mLock) {
            VirtualMachineConfig oldConfig = mConfig;
            if (!oldConfig.isCompatibleWith(newConfig)
                    || oldConfig.getEncryptedStorageBytes()
                            > newConfig.getEncryptedStorageBytes()) {
                throw new VirtualMachineException(
                        "incompatible config", VirtualMachineException.CODE_CONFIG_INCOMPATIBLE);
            }
            checkStopped();

            if (oldConfig != newConfig) {
                // Note that VirtualMachineConfig.serialize replaces the old config atomically.
                // This ensures that errors while writing the new config won't leave the config on
                // disk in a broken state and also that any VirtualMachineDescriptor that refers to
                // the old file does not see the new config.
                newConfig.serialize(mConfigFilePath);
                if (!Objects.equals(
                        oldConfig.getPayloadConfigPath(), newConfig.getPayloadConfigPath())) {
                    mTenantApks = setupTenantApks(mContext, newConfig, mVmRootPath);
                }
                mConfig = newConfig;
            }
            return oldConfig;
        }
    }

    /**
     * Abstracts away the task of creating a vsock connection. Normally, in the same process, you'll
     * make the connection and then promote it to a binder as part of the same API call, but if you
     * want to pass a connection to another process first, before establishing the RPC binder
     * connection, you may implement this method by getting a vsock connection from another process.
     *
     * <p>It is recommended to convert other types of exceptions (e.g. remote exceptions) to
     * VirtualMachineException, so that all connection failures will be visible under the same type
     * of exception.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi") // already existing functionality exposed, users should flag
    public interface VsockConnectionProvider {
        /**
         * Returns a connection, either from {@link #connectVsock} or from
         * the VM owner which would call {@link #connectVsock} on your behalf.
         *
         * <p>Each call should return a new connection.
         */
        @NonNull
        @SuppressLint("UnflaggedApi") // already existing functionality exposed, users should flag
        public ParcelFileDescriptor addConnection() throws VirtualMachineException;
    }

    /**
     * Class to make it easy to use JNI, without needing PFD and other classes.
     *
     * @hide
     */
    private static class NativeProviderWrapper {
        private VsockConnectionProvider mProvider = null;

        // ensures last FD is owned until get is called again
        private ParcelFileDescriptor mMoreLifetime = null;

        public NativeProviderWrapper(VsockConnectionProvider provider) {
            mProvider = provider;
        }

        int connect() throws VirtualMachineException {
            mMoreLifetime = mProvider.addConnection();
            return mMoreLifetime.getFileDescriptor().getInt$();
        }
    }

    /** @hide */
    @Nullable
    public IGuestAgent getGuestAgent() throws VirtualMachineException {
        synchronized (mLock) {
            try {
                if (mVirtualMachine != null) {
                    return mVirtualMachine.getGuestAgent();
                }
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to get guest agent");
            }
        }
        return null;
    }

    /**
     * Connect to a VM's binder service via vsock and return the root IBinder object. Guest VMs are
     * expected to set up vsock servers in their payload. After the host app receives the {@link
     * VirtualMachineCallback#onPayloadReady}, it can use this method to establish a connection to
     * the guest VM.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if the virtual machine is not running or the connection
     *     failed.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public IBinder connectToVsockServer(
            @IntRange(from = MIN_VSOCK_PORT, to = MAX_VSOCK_PORT) long port)
            throws VirtualMachineException {
        VsockConnectionProvider provider =
                new VsockConnectionProvider() {
                    @Override
                    public ParcelFileDescriptor addConnection() throws VirtualMachineException {
                        return connectVsock(port);
                    }
                };
        return binderFromPreconnectedClient(provider);
    }

    @Nullable
    private static native IBinder nativeBinderFromPreconnectedClient(
            NativeProviderWrapper provider);

    /**
     * Convert existing vsock connection to a binder connection.
     *
     * <p>See {@linkplain #connectToVsockServer} for details. This method allows
     * you to create the connects independently from upgrading them to the
     * binder connection. Specifically:
     *
     * <p>connectToVsockServer = connectToVsock + binderFromPreconnectedClient
     *
     * <p>This method is useful if you want to pass the vsock connection to
     * another process before establishing the RPC binder connection, so that
     * you can create a direct connection.
     *
     * @args
     *     provider: a provider that provides the vsock connection. This
     *              provider should return connections from
     *              {@link #connectVsock}, from the VM owner.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi") // already existing functionality exposed, users should flag
    @WorkerThread
    @NonNull
    public static IBinder binderFromPreconnectedClient(@NonNull VsockConnectionProvider provider)
            throws VirtualMachineException {
        IBinder binder = nativeBinderFromPreconnectedClient(new NativeProviderWrapper(provider));
        if (binder == null) {
            throw new VirtualMachineException("Failed to connect to vsock server");
        }
        // b/420703082
        // We don't allow 3P apps to host RPC Binder services. Additionally,
        // our clients are updatable, so they can't allow blocking calls.
        // Allow it here, to avoid all the spam/logs from these services.
        // If we ever allow 3P apps to host these services, we should have
        // a higher-level API that does not set this.
        Binder.allowBlocking(binder);
        return binder;
    }

    /**
     * Opens a vsock connection to the VM on the given port.
     *
     * <p>The caller is responsible for closing the returned {@code ParcelFileDescriptor}.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @throws VirtualMachineException if connecting fails.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public ParcelFileDescriptor connectVsock(
            @IntRange(from = MIN_VSOCK_PORT, to = MAX_VSOCK_PORT) long port)
            throws VirtualMachineException {
        synchronized (mLock) {
            try {
                return getRunningVm().connectVsock(validatePort(port));
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            } catch (ServiceSpecificException e) {
                throw new VirtualMachineException(e);
            }
        }
    }

    private int validatePort(long port) {
        // Ports below 1024 are "privileged" (payload code can't bind to these), and port numbers
        // are 32-bit unsigned numbers at the OS level, even though we pass them as 32-bit signed
        // numbers internally.
        if (port < MIN_VSOCK_PORT || port > MAX_VSOCK_PORT) {
            throw new IllegalArgumentException("Bad port " + port);
        }
        return (int) port;
    }

    /**
     * Returns the root directory where all files related to this {@link VirtualMachine} (e.g.
     * {@code instance.img}, {@code apk.idsig}, etc) are stored.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public File getRootDir() {
        return mVmRootPath;
    }

    /**
     * Enables the VM to request attestation in testing mode.
     *
     * <p>This function provisions a key pair for the VM attestation testing, a fake certificate
     * will be associated to the fake key pair when the VM requests attestation in testing mode.
     *
     * <p>The provisioned key pair can only be used in subsequent calls to {@link
     * AVmPayload_requestAttestationForTesting} within a running VM.
     *
     * @hide
     */
    // TODO: Make this static API.
    @TestApi
    @RequiresPermission(USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION)
    public void enableTestAttestation() throws VirtualMachineException {
        try {
            synchronized (VirtualMachineManager.sCreateLock) {
                VirtualizationService.getInstance().getBinder().enableTestAttestation();
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Captures the current state of the VM in a {@link VirtualMachineDescriptor} instance. The VM
     * needs to be stopped to avoid inconsistency in its state representation.
     *
     * <p>The state of the VM is not actually copied until {@link
     * VirtualMachineManager#importFromDescriptor} is called. It is recommended that the VM not be
     * started until that operation is complete.
     *
     * <p>NOTE: This method may block and should not be called on the main thread.
     *
     * @return a {@link VirtualMachineDescriptor} instance that represents the VM's state.
     * @throws VirtualMachineException if the virtual machine is not stopped, or the state could not
     *     be captured.
     * @hide
     */
    @SystemApi
    @WorkerThread
    @NonNull
    public VirtualMachineDescriptor toDescriptor() throws VirtualMachineException {
        synchronized (mLock) {
            checkStopped();
            if (mEncryptedStoreKEK != null) {
                throw new VirtualMachineException(
                        "`toDescriptor` is disabled for VMs with encryptedstore keyblob in CE",
                        VirtualMachineException.CODE_FEATURE_DISABLED);
            }
            try {
                return new VirtualMachineDescriptor(
                        ParcelFileDescriptor.open(mConfigFilePath, MODE_READ_ONLY),
                        mInstanceIdPath != null
                                ? ParcelFileDescriptor.open(mInstanceIdPath, MODE_READ_ONLY)
                                : null,
                        ParcelFileDescriptor.open(mInstanceFilePath, MODE_READ_ONLY),
                        mEncryptedStoreFilePath != null
                                ? ParcelFileDescriptor.open(mEncryptedStoreFilePath, MODE_READ_ONLY)
                                : null);
            } catch (IOException e) {
                throw new VirtualMachineException(e);
            }
        }
    }

    @Override
    public String toString() {
        VirtualMachineConfig config = getConfig();
        String payloadConfigPath = config.getPayloadConfigPath();
        String payloadBinaryName = config.getPayloadBinaryName();

        StringBuilder result = new StringBuilder();
        result.append("VirtualMachine(").append("name:").append(getName()).append(", ");
        if (payloadBinaryName != null) {
            result.append("payload:").append(payloadBinaryName).append(", ");
        }
        if (payloadConfigPath != null) {
            result.append("config:").append(payloadConfigPath).append(", ");
        }
        result.append("package: ").append(mPackageName).append(")");
        return result.toString();
    }

    /**
     * Reads the payload config inside the application, parses extra APK information, and then
     * creates corresponding idsig file paths.
     */
    private static List<ApkSpec> setupExtraApks(
            @NonNull Context context, @NonNull VirtualMachineConfig config, @NonNull File vmDir)
            throws VirtualMachineException {
        String configPath = config.getPayloadConfigPath();
        List<String> extraApks = config.getExtraApks();
        if (configPath != null) {
            return setupExtraApksFromConfigFile(context, vmDir, configPath);
        } else if (!extraApks.isEmpty()) {
            return setupExtraApksFromList(context, vmDir, extraApks);
        } else {
            return Collections.emptyList();
        }
    }

    private static List<ApkSpec> setupTenantApks(
            @NonNull Context context, @NonNull VirtualMachineConfig config, @NonNull File vmDir)
            throws VirtualMachineException {
        String configPath = config.getPayloadConfigPath();
        if (configPath != null) {
            return setupTenantApksFromConfigFile(context, vmDir, configPath);
        } else {
            return Collections.emptyList();
        }
    }

    private static List<ApkSpec> setupTenantApksFromConfigFile(
            Context context, File vmDir, String configPath) throws VirtualMachineException {
        List<String> apkList;
        try (ZipFile zipFile = new ZipFile(context.getPackageCodePath())) {
            InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(configPath));
            apkList =
                    parseTenantApkListFromPayloadConfig(
                            new JsonReader(new InputStreamReader(inputStream)));
        } catch (IOException e) {
            throw new VirtualMachineException(
                    "Couldn't parse tenant apks from the vm config",
                    e,
                    VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
        }

        List<ApkSpec> apks = new ArrayList<>(apkList.size());
        for (int i = 0; i < apkList.size(); ++i) {
            String packageName = apkList.get(i);
            ApplicationInfo appInfo;
            try {
                appInfo =
                        context.getPackageManager()
                                .getApplicationInfo(
                                        packageName, PackageManager.ApplicationInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException e) {
                throw new VirtualMachineException(
                        "Tenant apk package " + packageName + " not found", e);
            }
            apks.add(
                    new ApkSpec(
                            new File(appInfo.sourceDir),
                            new File(vmDir, TENANT_IDSIG_FILE_PREFIX + i)));
        }
        return apks;
    }

    private static List<String> parseExtraApkListFromPayloadConfig(JsonReader reader)
            throws VirtualMachineException {
        /*
         * JSON schema from packages/modules/Virtualization/microdroid/libs/libmicrodroid_payload_metadata/config/src/lib.rs:
         *
         * <p>{ "extra_apks": [ { "path": "/system/app/foo.apk", }, ... ], ... }
         */
        try {
            List<String> apks = new ArrayList<>();

            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("extra_apks")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginObject();
                        String name = reader.nextName();
                        if (name.equals("path")) {
                            apks.add(reader.nextString());
                        } else {
                            reader.skipValue();
                        }
                        reader.endObject();
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            return apks;
        } catch (IOException e) {
            throw new VirtualMachineException(
                    e, VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
        }
    }

    private static List<String> parseTenantApkListFromPayloadConfig(JsonReader reader)
            throws VirtualMachineException {
        try {
            List<String> apks = new ArrayList<>();
            reader.beginObject(); // Start of the main JSON object
            while (reader.hasNext()) {
                if (reader.nextName().equals("tenants")) {
                    reader.beginArray(); // Start of the tenants array
                    while (reader.hasNext()) {
                        reader.beginObject(); // Start of a tenant object
                        String pkg = "";
                        String name = "";
                        while (reader.hasNext()) {
                            String tenantName = reader.nextName();
                            if (tenantName.equals("package")) {
                                pkg = reader.nextString();
                            } else if (tenantName.equals("name")) {
                                name = reader.nextString();
                            } else {
                                reader.skipValue(); // Skip other fields
                            }
                        }
                        reader.endObject(); // End of a tenant object

                        if ("apk".equals(pkg)) {
                            apks.add(name);
                        }
                    }
                    reader.endArray(); // End of the tenants array
                } else {
                    reader.skipValue(); // Skip other top-level fields
                }
            }
            reader.endObject(); // End of the main JSON object
            return apks;
        } catch (IOException e) {
            throw new VirtualMachineException(
                    e, VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
        }
    }

    private static List<ApkSpec> setupExtraApksFromConfigFile(
            Context context, File vmDir, String configPath) throws VirtualMachineException {
        try (ZipFile zipFile = new ZipFile(context.getPackageCodePath())) {
            InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(configPath));
            List<String> apkList =
                    parseExtraApkListFromPayloadConfig(
                            new JsonReader(new InputStreamReader(inputStream)));

            List<ApkSpec> extraApks = new ArrayList<>(apkList.size());
            for (int i = 0; i < apkList.size(); ++i) {
                extraApks.add(
                        new ApkSpec(
                                new File(apkList.get(i)),
                                new File(vmDir, EXTRA_IDSIG_FILE_PREFIX + i)));
            }

            return extraApks;
        } catch (IOException e) {
            throw new VirtualMachineException(
                    "Couldn't parse extra apks from the vm config",
                    e,
                    VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
        }
    }

    private static List<ApkSpec> setupExtraApksFromList(
            Context context, File vmDir, List<String> extraApkInfo) throws VirtualMachineException {
        int count = extraApkInfo.size();
        List<ApkSpec> extraApks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String packageName = extraApkInfo.get(i);
            ApplicationInfo appInfo;
            try {
                appInfo =
                        context.getPackageManager()
                                .getApplicationInfo(
                                        packageName, PackageManager.ApplicationInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException e) {
                throw new VirtualMachineException(
                        "Extra APK package not found",
                        e,
                        VirtualMachineException.CODE_PAYLOAD_CONFIG_MALFORMED);
            }

            extraApks.add(
                    new ApkSpec(
                            new File(appInfo.sourceDir),
                            new File(vmDir, EXTRA_IDSIG_FILE_PREFIX + i)));
        }
        return extraApks;
    }

    private void importInstanceIdFrom(@NonNull ParcelFileDescriptor instanceIdFd)
            throws VirtualMachineException {
        try (FileChannel idOutput = new FileOutputStream(mInstanceIdPath).getChannel();
                FileChannel idInput = new AutoCloseInputStream(instanceIdFd).getChannel()) {
            idOutput.transferFrom(idInput, /* position= */ 0, idInput.size());
        } catch (IOException e) {
            throw new VirtualMachineException("failed to copy instance_id", e);
        }
    }

    private void importInstanceFrom(@NonNull ParcelFileDescriptor instanceFd)
            throws VirtualMachineException {
        try (FileChannel instance = new FileOutputStream(mInstanceFilePath).getChannel();
                FileChannel instanceInput = new AutoCloseInputStream(instanceFd).getChannel()) {
            instance.transferFrom(instanceInput, /* position= */ 0, instanceInput.size());
        } catch (IOException e) {
            throw new VirtualMachineException("failed to transfer instance image", e);
        }
    }

    private void importEncryptedStoreFrom(@NonNull ParcelFileDescriptor encryptedStoreFd)
            throws VirtualMachineException {
        try (FileChannel storeOutput = new FileOutputStream(mEncryptedStoreFilePath).getChannel();
                FileChannel storeInput = new AutoCloseInputStream(encryptedStoreFd).getChannel()) {
            storeOutput.transferFrom(storeInput, /* position= */ 0, storeInput.size());
        } catch (IOException e) {
            throw new VirtualMachineException("failed to transfer encryptedstore image", e);
        }
    }

    /**
     * Map the raw AIDL (& binder) callbacks to what the client expects.
     *
     * <p>Must to be static to avoid keeping an implicit strong reference on the parent
     * VirtualMachine object, otherwise, because IVirtualizationService will have a cross-process
     * reference, we can end up with a cross-process circular ref count, resulting in a leak.
     */
    private static class CallbackTranslator extends IVirtualMachineCallback.Stub {
        private final WeakReference<VirtualMachine> mVirtualMachine;
        private final WeakReference<IVirtualizationService> mService;

        private final Object mLock = new Object();

        /** The registered callback */
        @GuardedBy("mLock")
        @Nullable
        private VirtualMachineCallback mCallback;

        /** The executor on which the callback will be executed */
        @GuardedBy("mLock")
        @Nullable
        private Executor mCallbackExecutor;

        public CallbackTranslator(
                VirtualMachine virtualMachine,
                IVirtualizationService service,
                VirtualMachineCallback callback,
                Executor callbackExecutor)
                throws RemoteException {
            this.mVirtualMachine = new WeakReference<>(virtualMachine);
            this.mService = new WeakReference<>(service);
            this.mCallback = callback;
            this.mCallbackExecutor = callbackExecutor;
        }

        @FunctionalInterface
        interface CallbackFunc {
            public void apply(VirtualMachineCallback cb, VirtualMachine vm);
        }

        /** Executes a callback on the callback executor. */
        private void executeCallback(CallbackFunc fn) {
            final VirtualMachineCallback callback;
            final Executor executor;
            synchronized (mLock) {
                callback = mCallback;
                executor = mCallbackExecutor;
            }
            VirtualMachine vm = mVirtualMachine.get();
            if (callback == null || executor == null || vm == null) {
                return;
            }
            final long restoreToken = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> fn.apply(callback, vm));
            } finally {
                Binder.restoreCallingIdentity(restoreToken);
            }
        }

        @Override
        public void onPayloadStarted(int cid) {
            executeCallback((cb, vm) -> cb.onPayloadStarted(vm));
        }

        @Override
        public void onPayloadReady(int cid) {
            executeCallback((cb, vm) -> cb.onPayloadReady(vm));
        }

        @Override
        public void onPayloadFinished(int cid, int exitCode) {
            executeCallback((cb, vm) -> cb.onPayloadFinished(vm, exitCode));
        }

        @Override
        public void onError(int cid, int errorCode, String message) {
            int translatedError = getTranslatedError(errorCode);
            executeCallback((cb, vm) -> cb.onError(vm, translatedError, message));
        }

        @Override
        public void onDied(int cid, int reason) {
            int translatedReason = getTranslatedReason(reason);
            executeCallback(
                    (cb, vm) -> {
                        vm.handleStopped(translatedReason, cid);
                        cb.onStopped(vm, reason);
                    });
        }

        @Override
        public void onGuestAgentRegistered(int cid, IGuestAgent guestAgent) {
            executeCallback((cb, vm) -> cb.onGuestAgentRegistered(vm, guestAgent));
        }

        @VirtualMachineCallback.ErrorCode
        private int getTranslatedError(int reason) {
            switch (reason) {
                case ErrorCode.PAYLOAD_VERIFICATION_FAILED:
                    return ERROR_PAYLOAD_VERIFICATION_FAILED;
                case ErrorCode.PAYLOAD_CHANGED:
                    return ERROR_PAYLOAD_CHANGED;
                case ErrorCode.PAYLOAD_INVALID_CONFIG:
                    return ERROR_PAYLOAD_INVALID_CONFIG;
                default:
                    return ERROR_UNKNOWN;
            }
        }

        @VirtualMachineCallback.StopReason
        private int getTranslatedReason(int reason) {
            switch (reason) {
                case DeathReason.INFRASTRUCTURE_ERROR:
                    return STOP_REASON_INFRASTRUCTURE_ERROR;
                case DeathReason.KILLED:
                    return STOP_REASON_KILLED;
                case DeathReason.SHUTDOWN:
                    return STOP_REASON_SHUTDOWN;
                case DeathReason.START_FAILED:
                    return STOP_REASON_START_FAILED;
                case DeathReason.REBOOT:
                    return STOP_REASON_REBOOT;
                case DeathReason.CRASH:
                    return STOP_REASON_CRASH;
                case DeathReason.PVM_FIRMWARE_PUBLIC_KEY_MISMATCH:
                    return STOP_REASON_PVM_FIRMWARE_PUBLIC_KEY_MISMATCH;
                case DeathReason.PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED:
                    return STOP_REASON_PVM_FIRMWARE_INSTANCE_IMAGE_CHANGED;
                case DeathReason.MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE:
                    return STOP_REASON_MICRODROID_FAILED_TO_CONNECT_TO_VIRTUALIZATION_SERVICE;
                case DeathReason.MICRODROID_PAYLOAD_HAS_CHANGED:
                    return STOP_REASON_MICRODROID_PAYLOAD_HAS_CHANGED;
                case DeathReason.MICRODROID_PAYLOAD_VERIFICATION_FAILED:
                    return STOP_REASON_MICRODROID_PAYLOAD_VERIFICATION_FAILED;
                case DeathReason.MICRODROID_INVALID_PAYLOAD_CONFIG:
                    return STOP_REASON_MICRODROID_INVALID_PAYLOAD_CONFIG;
                case DeathReason.MICRODROID_UNKNOWN_RUNTIME_ERROR:
                    return STOP_REASON_MICRODROID_UNKNOWN_RUNTIME_ERROR;
                case DeathReason.HANGUP:
                    return STOP_REASON_HANGUP;
                default:
                    return STOP_REASON_UNKNOWN;
            }
        }
    }

    /**
     * Duplicates {@code InputStream} data to multiple {@code OutputStream}. Like the "tee" command.
     *
     * <p>Supports non-blocking writes to the output streams by ignoring EAGAIN error.
     */
    private static class TeeWorker implements Runnable {
        private final String mName;
        private final InputStream mIn;
        private final List<OutputStream> mOuts;

        TeeWorker(String name, InputStream in, Collection<OutputStream> outs) {
            mName = name;
            mIn = in;
            mOuts = new ArrayList<>(outs);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[2048];
            try {
                while (!Thread.interrupted()) {
                    int len = mIn.read(buffer);
                    if (len < 0) {
                        break;
                    }
                    for (OutputStream out : mOuts) {
                        try {
                            out.write(buffer, 0, len);
                        } catch (IOException e) {
                            // EAGAIN is expected because the file description has O_NONBLOCK flag.
                            if (!isErrnoError(e, OsConstants.EAGAIN)) {
                                throw e;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Tee " + mName, e);
            }
        }

        private static ErrnoException asErrnoException(Throwable e) {
            if (e instanceof ErrnoException) {
                return (ErrnoException) e;
            } else if (e instanceof IOException) {
                // Try to unwrap ErrnoException#rethrowAsIOException()
                return asErrnoException(e.getCause());
            }
            return null;
        }

        private static boolean isErrnoError(Exception e, int expectedValue) {
            ErrnoException errno = asErrnoException(e);
            return errno != null && errno.errno == expectedValue;
        }
    }

    private static class EncryptedStoreKEK extends IEncryptedStoreKEK.Stub {
        private final File mKEKFile;

        private EncryptedStoreKEK(Context context, String name) {
            File vmDir = getVmDir(context.createCredentialProtectedStorageContext(), name);
            mKEKFile = new File(vmDir, ENCRYPTED_STORE_KEK_FILE);
        }

        @Override
        public byte[] getKEK() {
            if (!mKEKFile.exists()) {
                return null;
            }
            try {
                return Files.readAllBytes(mKEKFile.toPath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to read " + mKEKFile.getAbsolutePath(), e);
                return null;
            }
        }

        @Override
        public void onKEKCreated(byte[] kekBlob) {
            try {
                if (mKEKFile.exists()) {
                    Log.w(TAG, "KEK existed, overwriting..." + mKEKFile.getAbsolutePath());
                    mKEKFile.delete();
                }
                mKEKFile.getParentFile().mkdirs();
                mKEKFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Failed to create " + mKEKFile.getAbsolutePath(), e);
            }
            try (FileOutputStream fos = new FileOutputStream(mKEKFile.getAbsolutePath())) {
                fos.write(kekBlob);
                fos.getFD().sync();
            } catch (IOException e) {
                throw new RuntimeException("Failed to write to " + mKEKFile.getAbsolutePath(), e);
            }
        }
    }

    /** @hide */
    public void setTouchScale(Float touchScale) {
        this.touchScale = touchScale;
    }
}
