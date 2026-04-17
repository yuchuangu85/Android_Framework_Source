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

package android.app;

import static android.aiseal.Flags.aisealHostApis;
import static android.app.appfunctions.flags.Flags.enableAppFunctionManager;
import static android.app.appfunctions.flags.Flags.enableAppFunctionPermissionV2;
import static android.app.lskfreset.flags.Flags.enableLskfResetManager;
import static android.app.privatecompute.flags.Flags.enablePccFrameworkSupport;
import static android.content.pm.PackageManager.FEATURE_AISEAL;
import static android.hardware.serial.flags.Flags.enableWiredSerialApi;
import static android.permission.flags.Flags.assistSettingsPrivacyImprovementsEnabled;
import static android.provider.flags.Flags.newStoragePublicApi;
import static android.service.chooser.Flags.interactiveChooser;

import android.accounts.AccountManager;
import android.accounts.IAccountManager;
import android.adservices.AdServicesFrameworkInitializer;
import android.aiseal.AiSealManager;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ContextImpl.ServiceInitializationState;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IDevicePolicyManager;
import android.app.ambientcontext.AmbientContextManager;
import android.app.ambientcontext.IAmbientContextManager;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.AppFunctionManagerConfiguration;
import android.app.appfunctions.IAppFunctionManager;
import android.app.appsearch.AppSearchManagerFrameworkInitializer;
import android.app.blob.BlobStoreManagerFrameworkInitializer;
import android.app.contentrestriction.ContentRestrictionManager;
import android.app.contentrestriction.IContentRestrictionManager;
import android.app.contentsafety.ContentSafetyManager;
import android.app.contentsafety.IContentSafetyManager;
import android.app.contentsuggestions.ContentSuggestionsManager;
import android.app.contentsuggestions.IContentSuggestionsManager;
import android.app.contextualsearch.ContextualSearchManager;
import android.app.ecm.EnhancedConfirmationFrameworkInitializer;
import android.app.job.JobSchedulerFrameworkInitializer;
import android.app.lskfreset.ILskfResetManager;
import android.app.lskfreset.LskfResetManager;
import android.app.modes.ContextualModeManager;
import android.app.ondeviceintelligence.OnDeviceIntelligenceFrameworkInitializer;
import android.app.people.PeopleManager;
import android.app.prediction.AppPredictionManager;
import android.app.privatecompute.IPccSandboxManager;
import android.app.privatecompute.PccSandboxManager;
import android.app.role.RoleFrameworkInitializer;
import android.app.sdksandbox.SdkSandboxManagerFrameworkInitializer;
import android.app.search.SearchUiManager;
import android.app.slice.SliceManager;
import android.app.smartspace.SmartspaceManager;
import android.app.supervision.ISupervisionManager;
import android.app.supervision.SupervisionManager;
import android.app.time.TimeManager;
import android.app.timedetector.TimeDetector;
import android.app.timedetector.TimeDetectorImpl;
import android.app.timezonedetector.TimeZoneDetector;
import android.app.timezonedetector.TimeZoneDetectorImpl;
import android.app.trust.TrustManager;
import android.app.usage.IStorageStatsManager;
import android.app.usage.IUsageStatsManager;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStatsManager;
import android.app.voiceinteraction.VoiceInteractionManager;
import android.app.wallpapereffectsgeneration.IWallpaperEffectsGenerationManager;
import android.app.wallpapereffectsgeneration.WallpaperEffectsGenerationManager;
import android.app.wearable.IWearableSensingManager;
import android.app.wearable.WearableSensingManager;
import android.apphibernation.AppHibernationManager;
import android.appwidget.AppWidgetManager;
import android.attention.AttentionManager;
import android.attention.IAttentionManager;
import android.bluetooth.BluetoothFrameworkInitializer;
import android.companion.CompanionDeviceManager;
import android.companion.ICompanionDeviceManager;
import android.companion.datatransfer.continuity.ITaskContinuityManager;
import android.companion.datatransfer.continuity.IUniversalClipboardManager;
import android.companion.datatransfer.continuity.TaskContinuityManager;
import android.companion.datatransfer.continuity.UniversalClipboardManager;
import android.companion.virtual.IVirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.ClipboardManager;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.IRestrictionsManager;
import android.content.RestrictionsManager;
import android.content.om.IOverlayManager;
import android.content.om.OverlayManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.CrossProfileApps;
import android.content.pm.DataLoaderManager;
import android.content.pm.ICrossProfileApps;
import android.content.pm.IDataLoaderManager;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.IDomainVerificationManager;
import android.content.pm.webapp.WebAppFrameworkInitializer;
import android.content.res.Resources;
import android.content.rollback.RollbackManagerFrameworkInitializer;
import android.credentials.CredentialManager;
import android.credentials.ICredentialManager;
import android.debug.AdbManager;
import android.debug.IAdbManager;
import android.devicelock.DeviceLockFrameworkInitializer;
import android.graphics.fonts.FontManager;
import android.hardware.ConsumerIrManager;
import android.hardware.ISensorPrivacyManager;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.hardware.SystemSensorManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IAuthService;
import android.hardware.camera2.CameraManager;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.input.InputManager;
import android.hardware.iris.IIrisService;
import android.hardware.iris.IrisManager;
import android.hardware.lights.LightsManager;
import android.hardware.lights.SystemLightsManager;
import android.hardware.location.ContextHubManager;
import android.hardware.location.IContextHubService;
import android.hardware.radio.RadioManager;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.health.connect.HealthServicesInitializer;
import android.location.CountryDetector;
import android.location.ICountryDetector;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.MediaFrameworkInitializer;
import android.media.MediaFrameworkPlatformInitializer;
import android.media.MediaRouter;
import android.media.metrics.IMediaMetricsManager;
import android.media.metrics.MediaMetricsManager;
import android.media.midi.IMidiManager;
import android.media.midi.MidiManager;
import android.media.musicrecognition.IMusicRecognitionManager;
import android.media.musicrecognition.MusicRecognitionManager;
import android.media.projection.MediaProjectionManager;
import android.media.quality.IMediaQualityManager;
import android.media.quality.MediaQualityManager;
import android.media.soundtrigger.SoundTriggerManager;
import android.media.tv.ITvInputManager;
import android.media.tv.TvInputManager;
import android.media.tv.ad.ITvAdManager;
import android.media.tv.ad.TvAdManager;
import android.media.tv.interactive.ITvInteractiveAppManager;
import android.media.tv.interactive.TvInteractiveAppManager;
import android.media.tv.tunerresourcemanager.ITunerResourceManager;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.nearby.NearbyFrameworkInitializer;
import android.net.ConnectivityFrameworkInitializer;
import android.net.ConnectivityFrameworkInitializerBaklava;
import android.net.ConnectivityFrameworkInitializerTiramisu;
import android.net.INetworkPolicyManager;
import android.net.IPacProxyManager;
import android.net.IVpnManager;
import android.net.NetworkPolicyManager;
import android.net.NetworkScoreManager;
import android.net.NetworkWatchlistManager;
import android.net.PacProxyManager;
import android.net.TetheringManager;
import android.net.VpnManager;
import android.net.wifi.WifiFrameworkInitializer;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.net.wifi.sharedconnectivity.app.SharedConnectivityManager;
import android.nfc.NfcFrameworkInitializer;
import android.npumanager.NpuManagerFrameworkInitializer;
import android.ondevicepersonalization.OnDevicePersonalizationFrameworkInitializer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryStatsManager;
import android.os.BugreportManager;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.HardwarePropertiesManager;
import android.os.IBatteryPropertiesRegistrar;
import android.os.IBinder;
import android.os.IDumpstate;
import android.os.IHardwarePropertiesManager;
import android.os.IHintManager;
import android.os.IPowerManager;
import android.os.IPowerStatsService;
import android.os.IRecoverySystem;
import android.os.ISecurityStateManager;
import android.os.ISystemUpdateManager;
import android.os.IThermalService;
import android.os.IUserManager;
import android.os.IncidentManager;
import android.os.PerformanceHintManager;
import android.os.PermissionEnforcer;
import android.os.PowerManager;
import android.os.ProfilingFrameworkInitializer;
import android.os.RecoverySystem;
import android.os.SecurityStateManager;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.StatsFrameworkInitializer;
import android.os.SystemConfigManager;
import android.os.SystemUpdateManager;
import android.os.SystemVibrator;
import android.os.SystemVibratorManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.allowlist.AllowlistManager;
import android.os.allowlist.IAllowlistService;
import android.os.flagging.ConfigInfrastructureFrameworkInitializer;
import android.os.health.SystemHealthManager;
import android.os.image.DynamicSystemManager;
import android.os.image.IDynamicSystemService;
import android.os.incremental.IIncrementalService;
import android.os.incremental.IncrementalManager;
import android.os.profiling.anomaly.AnomalyDetectorFrameworkInitializer;
import android.os.storage.FileManager;
import android.os.storage.IFileService;
import android.os.storage.StorageManager;
import android.permission.LegacyPermissionManager;
import android.permission.PermissionCheckerManager;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.print.IPrintManager;
import android.print.PrintManager;
import android.provider.E2eeContactKeysManager;
import android.provider.ProviderFrameworkInitializer;
import android.ranging.RangingFrameworkInitializer;
import android.ravenwood.annotation.RavenwoodKeep;
import android.ravenwood.annotation.RavenwoodKeepPartialClass;
import android.ravenwood.annotation.RavenwoodKeepStaticInitializer;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.safetycenter.SafetyCenterFrameworkInitializer;
import android.scheduling.SchedulingFrameworkInitializer;
import android.security.FileIntegrityManager;
import android.security.IFileIntegrityService;
import android.security.advancedprotection.AdvancedProtectionManager;
import android.security.advancedprotection.IAdvancedProtectionService;
import android.security.attestationverification.AttestationVerificationManager;
import android.security.attestationverification.IAttestationVerificationManagerService;
import android.security.authenticationpolicy.AuthenticationPolicyManager;
import android.security.authenticationpolicy.IAuthenticationPolicyService;
import android.security.intrusiondetection.IIntrusionDetectionService;
import android.security.intrusiondetection.IntrusionDetectionManager;
import android.security.keystore.KeyStoreManager;
import android.service.chooser.ChooserManager;
import android.service.oemlock.IOemLockService;
import android.service.oemlock.OemLockManager;
import android.service.persistentdata.IPersistentDataBlockService;
import android.service.persistentdata.PersistentDataBlockManager;
import android.service.personalcontext.IPersonalContextManager;
import android.service.personalcontext.PersonalContextManager;
import android.service.uprobestats.DynamicInstrumentationManager;
import android.service.uprobestats.UprobestatsFrameworkInitializer;
import android.service.vr.IVrManager;
import android.system.virtualmachine.VirtualizationFrameworkInitializer;
import android.telecom.TelecomManager;
import android.telephony.MmsManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyRegistryManager;
import android.transparency.BinaryTransparencyManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.uwb.UwbFrameworkInitializer;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.CaptioningManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutoFillManager;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.IContentCaptureManager;
import android.view.displayhash.DisplayHashManager;
import android.view.inputmethod.InputMethodManager;
import android.view.selectiontoolbar.ISelectionToolbarManager;
import android.view.selectiontoolbar.SelectionToolbarManager;
import android.view.textclassifier.TextClassificationManager;
import android.view.textservice.TextServicesManager;
import android.view.translation.ITranslationManager;
import android.view.translation.TranslationManager;
import android.view.translation.UiTranslationManager;
import android.webkit.WebViewBootstrapFrameworkInitializer;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.internal.app.ISoundTriggerService;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.net.INetworkWatchlistManager;
import com.android.internal.os.IBinaryTransparencyService;
import com.android.internal.os.IDropBoxManagerService;
import com.android.internal.policy.PhoneLayoutInflater;
import com.android.internal.telecom.TelecomDependencies;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.ravenwood.RavenwoodHelper;

import java.util.Map;
import java.util.Objects;

/**
 * Manages all of the system services that can be returned by {@link Context#getSystemService}.
 * Used by {@link ContextImpl}.
 *
 * @hide
 */
@SystemApi
@RavenwoodKeepPartialClass(comment =
        "The whole service registration is done in SystemServiceRegistry_ravenwood"
)
@RavenwoodKeepStaticInitializer
@RavenwoodRedirectionClass("SystemServiceRegistry_ravenwood")
public final class SystemServiceRegistry {
    private static final String TAG = "SystemServiceRegistry";

    /** @hide */
    public static boolean sEnableServiceNotFoundWtf = false;

    /**
     * After {@link Build.VERSION_CODES.VANILLA_ICE_CREAM}, Wear devices will be allowed to publish
     * no {@link GameManager} instance. This is because the respective system service is no longer
     * started for Wear devices given that the applications of the service do not currently apply to
     * Wear.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long NULL_GAME_MANAGER_IN_WEAR = 340929737;

    // Service registry information.
    // This information is never changed once static initialization has completed.
    private static final Map<Class<?>, String> SYSTEM_SERVICE_NAMES =
            new ArrayMap<Class<?>, String>();
    private static final Map<String, ServiceFetcher<?>> SYSTEM_SERVICE_FETCHERS =
            new ArrayMap<String, ServiceFetcher<?>>();
    private static final Map<String, String> SYSTEM_SERVICE_CLASS_NAMES = new ArrayMap<>();

    private static int sServiceCacheSize;

    private static volatile boolean sInitializing;

    // Not instantiable.
    private SystemServiceRegistry() { }

    static {
        registerServices();
    }

    @RavenwoodRedirect
    private static void registerServices() {
        //CHECKSTYLE:OFF IndentationCheck
        registerService(Context.ACCESSIBILITY_SERVICE, AccessibilityManager.class,
                new CachedServiceFetcher<AccessibilityManager>() {
            @Override
            public AccessibilityManager createService(ContextImpl ctx) {
                return AccessibilityManager.getInstance(ctx);
            }});

        registerService(Context.CAPTIONING_SERVICE, CaptioningManager.class,
                new CachedServiceFetcher<CaptioningManager>() {
            @Override
            public CaptioningManager createService(ContextImpl ctx) {
                return new CaptioningManager(ctx);
            }});

        registerService(Context.ACCOUNT_SERVICE, AccountManager.class,
                new CachedServiceFetcher<AccountManager>() {
            @Override
            public AccountManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.ACCOUNT_SERVICE);
                IAccountManager service = IAccountManager.Stub.asInterface(b);
                return new AccountManager(ctx, service);
            }});

        registerService(Context.ACTIVITY_SERVICE, ActivityManager.class,
                new CachedServiceFetcher<ActivityManager>() {
            @Override
            public ActivityManager createService(ContextImpl ctx) {
                return new ActivityManager(ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});

        registerService(Context.ACTIVITY_TASK_SERVICE, ActivityTaskManager.class,
                new CachedServiceFetcher<ActivityTaskManager>() {
            @Override
            public ActivityTaskManager createService(ContextImpl ctx) {
                return ActivityTaskManager.getInstance();
            }});

        registerService(Context.URI_GRANTS_SERVICE, UriGrantsManager.class,
                new CachedServiceFetcher<UriGrantsManager>() {
            @Override
            public UriGrantsManager createService(ContextImpl ctx) {
                return new UriGrantsManager(
                        ctx.getOuterContext(), ctx.mMainThread.getHandler());
            }});

        registerService(Context.ALARM_SERVICE, AlarmManager.class,
                new CachedServiceFetcher<AlarmManager>() {
            @Override
            public AlarmManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.ALARM_SERVICE);
                IAlarmManager service = IAlarmManager.Stub.asInterface(b);
                return new AlarmManager(service, ctx);
            }});

        registerService(Context.AUDIO_SERVICE, AudioManager.class,
                new CachedServiceFetcher<AudioManager>() {
            @Override
            public AudioManager createService(ContextImpl ctx) {
                return new AudioManager(ctx);
            }});

        registerService(Context.AUDIO_DEVICE_VOLUME_SERVICE, AudioDeviceVolumeManager.class,
                new CachedServiceFetcher<AudioDeviceVolumeManager>() {
            @Override
            public AudioDeviceVolumeManager createService(ContextImpl ctx) {
                return new AudioDeviceVolumeManager(ctx);
            }});

        if (android.app.contentsafety.flags.Flags.enableContentsafety()) {
            registerService(Context.CONTENT_SAFETY_SERVICE, ContentSafetyManager.class,
                    new CachedServiceFetcher<ContentSafetyManager>() {
                        @Override
                        public ContentSafetyManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {

                            IBinder b = ServiceManager.getServiceOrThrow(
                                    Context.CONTENT_SAFETY_SERVICE);
                            IContentSafetyManager service = IContentSafetyManager.Stub.asInterface(
                                    b);
                            return new ContentSafetyManager(ctx, service);
                        }
                    });
        }

        registerService(Context.MEDIA_ROUTER_SERVICE, MediaRouter.class,
                new CachedServiceFetcher<MediaRouter>() {
            @Override
            public MediaRouter createService(ContextImpl ctx) {
                return new MediaRouter(ctx);
            }});

        registerService(Context.HDMI_CONTROL_SERVICE, HdmiControlManager.class,
                new StaticServiceFetcher<HdmiControlManager>() {
            @Override
            public HdmiControlManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.HDMI_CONTROL_SERVICE);
                return new HdmiControlManager(IHdmiControlService.Stub.asInterface(b));
            }});

        registerService(Context.TEXT_CLASSIFICATION_SERVICE, TextClassificationManager.class,
                new CachedServiceFetcher<TextClassificationManager>() {
            @Override
            public TextClassificationManager createService(ContextImpl ctx) {
                return new TextClassificationManager(ctx);
            }});

        registerService(Context.SELECTION_TOOLBAR_SERVICE, SelectionToolbarManager.class,
                new CachedServiceFetcher<SelectionToolbarManager>() {
                    @Override
                    public SelectionToolbarManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final PackageManager pm = ctx.getPackageManager();
                        final boolean serviceRequired =
                                !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                                && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
                        // If the service is required on the device, use `getServiceOrThrow`, which
                        // will throw an Exception if the service is missing.
                        // If the service is not required on the device, use `getService`. This
                        // method simply returns `null` if the service is absent. Avoiding the
                        // Exception is critical for such devices, because the Exception may cause
                        // process crashes, and we do not want that given that the service is not
                        // mandatory.
                        IBinder b = serviceRequired
                                ? ServiceManager.getServiceOrThrow(
                                        Context.SELECTION_TOOLBAR_SERVICE)
                                : ServiceManager.getService(Context.SELECTION_TOOLBAR_SERVICE);
                        if (b == null) {
                            return null;
                        }
                        return new SelectionToolbarManager(
                                ISelectionToolbarManager.Stub.asInterface(b));
                    }});

        registerService(Context.FONT_SERVICE, FontManager.class,
                new CachedServiceFetcher<FontManager>() {
            @Override
            public FontManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.FONT_SERVICE);
                return FontManager.create(IFontManager.Stub.asInterface(b));
            }});

        registerService(Context.CLIPBOARD_SERVICE, ClipboardManager.class,
                new CachedServiceFetcher<ClipboardManager>() {
            @Override
            public ClipboardManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new ClipboardManager(ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        // The clipboard service moved to a new package.  If someone asks for the old
        // interface by class then we want to redirect over to the new interface instead
        // (which extends it).
        SYSTEM_SERVICE_NAMES.put(android.text.ClipboardManager.class, Context.CLIPBOARD_SERVICE);

        registerService(Context.PAC_PROXY_SERVICE, PacProxyManager.class,
                new CachedServiceFetcher<PacProxyManager>() {
            @Override
            public PacProxyManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.PAC_PROXY_SERVICE);
                IPacProxyManager service = IPacProxyManager.Stub.asInterface(b);
                return new PacProxyManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.NETD_SERVICE, IBinder.class, new StaticServiceFetcher<IBinder>() {
            @Override
            public IBinder createService() throws ServiceNotFoundException {
                return ServiceManager.getServiceOrThrow(Context.NETD_SERVICE);
            }
        });

        registerService(Context.TETHERING_SERVICE, TetheringManager.class,
                new CachedServiceFetcher<TetheringManager>() {
            @Override
            public TetheringManager createService(ContextImpl ctx) {
                return new TetheringManager(
                        ctx, () -> ServiceManager.getService(Context.TETHERING_SERVICE));
            }});

        registerService(Context.VPN_MANAGEMENT_SERVICE, VpnManager.class,
                new CachedServiceFetcher<VpnManager>() {
            @Override
            public VpnManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getService(Context.VPN_MANAGEMENT_SERVICE);
                IVpnManager service = IVpnManager.Stub.asInterface(b);
                if (service == null &&
                        ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                    return null;
                }
                return new VpnManager(ctx, service);
            }});

        registerService(Context.COUNTRY_DETECTOR, CountryDetector.class,
                new StaticServiceFetcher<CountryDetector>() {
            @Override
            public CountryDetector createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.COUNTRY_DETECTOR);
                return new CountryDetector(ICountryDetector.Stub.asInterface(b));
            }});

        registerService(Context.DEVICE_POLICY_SERVICE, DevicePolicyManager.class,
                new CachedServiceFetcher<DevicePolicyManager>() {
            @Override
            public DevicePolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.DEVICE_POLICY_SERVICE);
                return new DevicePolicyManager(ctx, IDevicePolicyManager.Stub.asInterface(b));
            }});

        registerService(Context.DOWNLOAD_SERVICE, DownloadManager.class,
                new CachedServiceFetcher<DownloadManager>() {
            @Override
            public DownloadManager createService(ContextImpl ctx) {
                return new DownloadManager(ctx);
            }});

        registerService(Context.BATTERY_SERVICE, BatteryManager.class,
                new CachedServiceFetcher<BatteryManager>() {
            @Override
            public BatteryManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBatteryStats stats = IBatteryStats.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(BatteryStats.SERVICE_NAME));
                IBatteryPropertiesRegistrar registrar = IBatteryPropertiesRegistrar.Stub
                        .asInterface(ServiceManager.getServiceOrThrow("batteryproperties"));
                return new BatteryManager(ctx, stats, registrar);
            }});

        registerService(Context.DROPBOX_SERVICE, DropBoxManager.class,
                new CachedServiceFetcher<DropBoxManager>() {
            @Override
            public DropBoxManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.DROPBOX_SERVICE);
                IDropBoxManagerService service = IDropBoxManagerService.Stub.asInterface(b);
                return new DropBoxManager(ctx, service);
            }});

        registerService(Context.BINARY_TRANSPARENCY_SERVICE, BinaryTransparencyManager.class,
                new CachedServiceFetcher<BinaryTransparencyManager>() {
            @Override
            public BinaryTransparencyManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(
                        Context.BINARY_TRANSPARENCY_SERVICE);
                IBinaryTransparencyService service = IBinaryTransparencyService.Stub.asInterface(b);
                return new BinaryTransparencyManager(ctx, service);
            }});

        // InputManager stores its own static instance for historical purposes.
        registerService(Context.INPUT_SERVICE, InputManager.class,
                new CachedServiceFetcher<InputManager>() {
            @Override
            public InputManager createService(ContextImpl ctx) {
                return new InputManager(ctx.getOuterContext());
            }});

        registerService(Context.DISPLAY_SERVICE, DisplayManager.class,
                new CachedServiceFetcher<DisplayManager>() {
            @Override
            public DisplayManager createService(ContextImpl ctx) {
                return new DisplayManager(ctx.getOuterContext());
            }});

        registerService(Context.COLOR_DISPLAY_SERVICE, ColorDisplayManager.class,
                new CachedServiceFetcher<ColorDisplayManager>() {
                    @Override
                    public ColorDisplayManager createService(ContextImpl ctx) {
                        return new ColorDisplayManager();
                    }
                });

        // InputMethodManager has its own cache strategy based on display id to support apps that
        // still assume InputMethodManager is a per-process singleton and it's safe to directly
        // access internal fields via reflection.  Hence directly use ServiceFetcher instead of
        // StaticServiceFetcher/CachedServiceFetcher.
        registerService(Context.INPUT_METHOD_SERVICE, InputMethodManager.class,
                new ServiceFetcher<InputMethodManager>() {
            @Override
            public InputMethodManager getService(ContextImpl ctx) {
                return InputMethodManager.forContext(ctx.getOuterContext());
            }});

        registerService(Context.TEXT_SERVICES_MANAGER_SERVICE, TextServicesManager.class,
                new CachedServiceFetcher<TextServicesManager>() {
            @Override
            public TextServicesManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH) &&
                        ServiceManager.getService(Context.TEXT_SERVICES_MANAGER_SERVICE) == null) {
                    return null;
                }
                return TextServicesManager.createInstance(ctx);
            }});

        registerService(Context.KEYGUARD_SERVICE, KeyguardManager.class,
                new CachedServiceFetcher<KeyguardManager>() {
            @Override
            public KeyguardManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new KeyguardManager(ctx);
            }});

        registerService(Context.LAYOUT_INFLATER_SERVICE, LayoutInflater.class,
                new CachedServiceFetcher<LayoutInflater>() {
            @Override
            public LayoutInflater createService(ContextImpl ctx) {
                return new PhoneLayoutInflater(ctx.getOuterContext());
            }});

        registerService(Context.LOCATION_SERVICE, LocationManager.class,
                new CachedServiceFetcher<LocationManager>() {
            @Override
            public LocationManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.LOCATION_SERVICE);
                return new LocationManager(ctx, ILocationManager.Stub.asInterface(b));
            }});

        registerService(Context.NETWORK_POLICY_SERVICE, NetworkPolicyManager.class,
                new CachedServiceFetcher<NetworkPolicyManager>() {
            @Override
            public NetworkPolicyManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new NetworkPolicyManager(ctx, INetworkPolicyManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(Context.NETWORK_POLICY_SERVICE)));
            }});

        registerService(Context.NOTIFICATION_SERVICE, NotificationManager.class,
                new CachedServiceFetcher<NotificationManager>() {
            @Override
            public NotificationManager createService(ContextImpl ctx) {
                final Context outerContext = ctx.getOuterContext();
                return new NotificationManager(
                    new ContextThemeWrapper(outerContext,
                            Resources.selectSystemTheme(0,
                                    outerContext.getApplicationInfo().targetSdkVersion,
                                    com.android.internal.R.style.Theme_Dialog,
                                    com.android.internal.R.style.Theme_Holo_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Dialog,
                                    com.android.internal.R.style.Theme_DeviceDefault_Light_Dialog))
                );
            }});

        if (android.service.notification.Flags.enableDndSync()) {
            registerService(Context.CONTEXTUAL_MODE_SERVICE, ContextualModeManager.class,
                    new CachedServiceFetcher<ContextualModeManager>() {
                @Override
                public ContextualModeManager createService(ContextImpl ctx) {
                    return new ContextualModeManager(ctx);
                }
            });
        }

        registerService(Context.PEOPLE_SERVICE, PeopleManager.class,
                new CachedServiceFetcher<PeopleManager>() {
            @Override
            public PeopleManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new PeopleManager(ctx);
            }});

        registerService(Context.POWER_SERVICE, PowerManager.class,
                new CachedServiceFetcher<PowerManager>() {
            @Override
            public PowerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder powerBinder = ServiceManager.getServiceOrThrow(Context.POWER_SERVICE);
                IPowerManager powerService = IPowerManager.Stub.asInterface(powerBinder);
                IBinder thermalBinder = ServiceManager.getServiceOrThrow(Context.THERMAL_SERVICE);
                IThermalService thermalService = IThermalService.Stub.asInterface(thermalBinder);
                return new PowerManager(ctx.getOuterContext(), powerService, thermalService,
                        ctx.mMainThread.getHandler());
            }});

        registerService(Context.PERFORMANCE_HINT_SERVICE, PerformanceHintManager.class,
                new CachedServiceFetcher<PerformanceHintManager>() {
            @Override
            public PerformanceHintManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                return PerformanceHintManager.create();
            }});

        registerService(Context.RECOVERY_SERVICE, RecoverySystem.class,
                new CachedServiceFetcher<RecoverySystem>() {
            @Override
            public RecoverySystem createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.RECOVERY_SERVICE);
                IRecoverySystem service = IRecoverySystem.Stub.asInterface(b);
                return new RecoverySystem(service);
            }});

        registerService(Context.SEARCH_SERVICE, SearchManager.class,
                new CachedServiceFetcher<SearchManager>() {
            @Override
            public SearchManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new SearchManager(ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        registerService(Context.SECURITY_STATE_SERVICE, SecurityStateManager.class,
                new CachedServiceFetcher<SecurityStateManager>() {
                    @Override
                    public SecurityStateManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.SECURITY_STATE_SERVICE);
                        ISecurityStateManager service = ISecurityStateManager.Stub.asInterface(b);
                        return new SecurityStateManager(service);
                    }});

        registerService(Context.SENSOR_SERVICE, SensorManager.class,
                new CachedServiceFetcher<SensorManager>() {
            @Override
            public SensorManager createService(ContextImpl ctx) {
                return new SystemSensorManager(ctx.getOuterContext(),
                  ctx.mMainThread.getHandler().getLooper());
            }});

        registerService(Context.SENSOR_PRIVACY_SERVICE, SensorPrivacyManager.class,
                new CachedServiceFetcher<SensorPrivacyManager>() {
                    @Override
                    public SensorPrivacyManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.SENSOR_PRIVACY_SERVICE);
                        return SensorPrivacyManager.getInstance(
                                ctx, ISensorPrivacyManager.Stub.asInterface(b));
                    }});

        registerService(Context.STATUS_BAR_SERVICE, StatusBarManager.class,
                new CachedServiceFetcher<StatusBarManager>() {
            @Override
            public StatusBarManager createService(ContextImpl ctx) {
                return new StatusBarManager(ctx.getOuterContext());
            }});

        registerService(Context.STORAGE_SERVICE, StorageManager.class,
                new CachedServiceFetcher<StorageManager>() {
            @Override
            public StorageManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new StorageManager(ctx, ctx.mMainThread.getHandler().getLooper());
            }});

        if (android.app.privatecompute.flags.Flags.enablePccFrameworkSupport()) {
            registerService(Context.FILE_SERVICE, FileManager.class,
                    new CachedServiceFetcher<FileManager>() {
                @Override
                public FileManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                    IBinder b = ServiceManager.getServiceOrThrow(Context.FILE_SERVICE);
                    IFileService service = IFileService.Stub.asInterface(b);
                    return new FileManager(ctx, service);
                }});
        }

        registerService(Context.STORAGE_STATS_SERVICE, StorageStatsManager.class,
                new CachedServiceFetcher<StorageStatsManager>() {
            @Override
            public StorageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IStorageStatsManager service = IStorageStatsManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(Context.STORAGE_STATS_SERVICE));
                return new StorageStatsManager(ctx, service);
            }});

        registerService(Context.SYSTEM_UPDATE_SERVICE, SystemUpdateManager.class,
                new CachedServiceFetcher<SystemUpdateManager>() {
                    @Override
                    public SystemUpdateManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.SYSTEM_UPDATE_SERVICE);
                        ISystemUpdateManager service = ISystemUpdateManager.Stub.asInterface(b);
                        return new SystemUpdateManager(service);
                    }});

        registerService(Context.SYSTEM_CONFIG_SERVICE, SystemConfigManager.class,
                new CachedServiceFetcher<SystemConfigManager>() {
                    @Override
                    public SystemConfigManager createService(ContextImpl ctx) {
                        return new SystemConfigManager();
                    }});

        registerService(Context.TELEPHONY_REGISTRY_SERVICE, TelephonyRegistryManager.class,
            new CachedServiceFetcher<TelephonyRegistryManager>() {
                @Override
                public TelephonyRegistryManager createService(ContextImpl ctx) {
                    return new TelephonyRegistryManager(ctx);
                }});

        if (!TelecomDependencies.isMainlineBuildFlagEnabled()) {
            registerService(Context.TELECOM_SERVICE, TelecomManager.class,
                new CachedServiceFetcher<>() {
                    @Override
                    public TelecomManager createService(ContextImpl ctx) {
                        return TelecomDependencies.createTelecomManager(ctx);
                    }
                });
        }

        registerService(Context.MMS_SERVICE, MmsManager.class,
                new CachedServiceFetcher<MmsManager>() {
                    @Override
                    public MmsManager createService(ContextImpl ctx) {
                        return new MmsManager(ctx.getOuterContext());
                    }});

        registerService(Context.UI_MODE_SERVICE, UiModeManager.class,
                new CachedServiceFetcher<UiModeManager>() {
            @Override
            public UiModeManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new UiModeManager(ctx.getOuterContext());
            }});

        registerService(Context.USB_SERVICE, UsbManager.class,
                new CachedServiceFetcher<UsbManager>() {
            @Override
            public UsbManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.USB_SERVICE);
                return new UsbManager(ctx, IUsbManager.Stub.asInterface(b));
            }});

        registerService(Context.ADB_SERVICE, AdbManager.class,
                new CachedServiceFetcher<AdbManager>() {
                    @Override
                    public AdbManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.ADB_SERVICE);
                        return new AdbManager(ctx, IAdbManager.Stub.asInterface(b));
                    }});

        if (enableWiredSerialApi()) {
            registerService(Context.SERIAL_SERVICE, android.hardware.serial.SerialManager.class,
                    new CachedServiceFetcher<android.hardware.serial.SerialManager>() {
                        @Override
                        public android.hardware.serial.SerialManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder b = ServiceManager.getServiceOrThrow(Context.SERIAL_SERVICE);
                            return new android.hardware.serial.SerialManager(ctx,
                                    android.hardware.serial.ISerialManager.Stub.asInterface(b));
                        }
                    });
            // The serial service moved to a new package. If someone asks for the old
            // interface by class then we want to redirect over to the new interface instead
            // (which extends it).
            SYSTEM_SERVICE_NAMES.put(android.hardware.SerialManager.class, Context.SERIAL_SERVICE);
        } else {
            registerService(Context.SERIAL_SERVICE, android.hardware.SerialManager.class,
                    new CachedServiceFetcher<android.hardware.SerialManager>() {
                        @Override
                        public android.hardware.SerialManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder b = ServiceManager.getServiceOrThrow(Context.SERIAL_SERVICE);
                            return new android.hardware.SerialManager(ctx,
                                    android.hardware.ISerialManager.Stub.asInterface(b));
                        }
                    });
        }

        registerService(Context.VIBRATOR_MANAGER_SERVICE, VibratorManager.class,
                new CachedServiceFetcher<VibratorManager>() {
                    @Override
                    public VibratorManager createService(ContextImpl ctx) {
                        return new SystemVibratorManager(ctx);
                    }});

        registerService(Context.VIBRATOR_SERVICE, Vibrator.class,
                new CachedServiceFetcher<Vibrator>() {
            @Override
            public Vibrator createService(ContextImpl ctx) {
                return new SystemVibrator(ctx);
            }});

        if (assistSettingsPrivacyImprovementsEnabled()) {
            registerService(Context.VOICE_INTERACTION_MANAGER_SERVICE,
                    VoiceInteractionManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public VoiceInteractionManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IVoiceInteractionManagerService service;
                            service = IVoiceInteractionManagerService.Stub.asInterface(
                                    ServiceManager.getServiceOrThrow(
                                            Context.VOICE_INTERACTION_MANAGER_SERVICE));
                            return new VoiceInteractionManager(service, ctx);
                        }
                    });
        }

        registerService(Context.THEME_SERVICE, ThemeManager.class,
            new CachedServiceFetcher<ThemeManager>() {
                @Override
                public ThemeManager createService(ContextImpl ctx) {
                    return new ThemeManager();
                }
            });

        registerService(Context.WALLPAPER_SERVICE, WallpaperManager.class,
                new CachedServiceFetcher<WallpaperManager>() {
            @Override
            public WallpaperManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                final IBinder b = ServiceManager.getService(Context.WALLPAPER_SERVICE);
                if (b == null) {
                    ApplicationInfo appInfo = ctx.getApplicationInfo();
                    if (appInfo.targetSdkVersion >= Build.VERSION_CODES.P
                            && appInfo.isInstantApp()) {
                        // Instant app
                        throw new ServiceNotFoundException(Context.WALLPAPER_SERVICE);
                    }
                    final boolean enabled = Resources.getSystem()
                            .getBoolean(com.android.internal.R.bool.config_enableWallpaperService);
                    if (!enabled) {
                        // Device doesn't support wallpaper, return a limited manager
                        return DisabledWallpaperManager.getInstance();
                    }
                    // Bad state - WallpaperManager methods will throw exception
                    Log.e(TAG, "No wallpaper service");
                }
                IWallpaperManager service = IWallpaperManager.Stub.asInterface(b);
                return new WallpaperManager(service, ctx.getOuterContext(),
                        ctx.mMainThread.getHandler());
            }});

        registerService(Context.WIFI_NL80211_SERVICE, WifiNl80211Manager.class,
                new CachedServiceFetcher<WifiNl80211Manager>() {
                    @Override
                    public WifiNl80211Manager createService(ContextImpl ctx) {
                        return new WifiNl80211Manager(ctx.getOuterContext());
                    }
                });

        registerService(Context.WINDOW_SERVICE, WindowManager.class,
                new CachedServiceFetcher<WindowManager>() {
            @Override
            public WindowManager createService(ContextImpl ctx) {
                return new WindowManagerImpl(ctx);
            }});

        registerService(Context.USER_SERVICE, UserManager.class,
                new CachedServiceFetcher<UserManager>() {
            @Override
            public UserManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.USER_SERVICE);
                IUserManager service = IUserManager.Stub.asInterface(b);
                return new UserManager(ctx, service);
            }});

        registerService(Context.APP_OPS_SERVICE, AppOpsManager.class,
                new CachedServiceFetcher<AppOpsManager>() {
            @Override
            public AppOpsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.APP_OPS_SERVICE);
                IAppOpsService service = IAppOpsService.Stub.asInterface(b);
                return new AppOpsManager(ctx, service);
            }});

        registerService(Context.CAMERA_SERVICE, CameraManager.class,
                new CachedServiceFetcher<CameraManager>() {
            @Override
            public CameraManager createService(ContextImpl ctx) {
                return new CameraManager(ctx);
            }});

        registerService(Context.LAUNCHER_APPS_SERVICE, LauncherApps.class,
                new CachedServiceFetcher<LauncherApps>() {
            @Override
            public LauncherApps createService(ContextImpl ctx) {
                return new LauncherApps(ctx);
            }});

        registerService(Context.RESTRICTIONS_SERVICE, RestrictionsManager.class,
                new CachedServiceFetcher<RestrictionsManager>() {
            @Override
            public RestrictionsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.RESTRICTIONS_SERVICE);
                IRestrictionsManager service = IRestrictionsManager.Stub.asInterface(b);
                return new RestrictionsManager(ctx, service);
            }});

        registerService(Context.PRINT_SERVICE, PrintManager.class,
                new CachedServiceFetcher<PrintManager>() {
            @Override
            public PrintManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IPrintManager service = null;
                // If the feature not present, don't try to look up every time
                if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PRINTING)) {
                    service = IPrintManager.Stub.asInterface(ServiceManager
                            .getServiceOrThrow(Context.PRINT_SERVICE));
                }
                final int userId = ctx.getUserId();
                final int appId = UserHandle.getAppId(ctx.getApplicationInfo().uid);
                return new PrintManager(ctx.getOuterContext(), service, userId, appId);
            }});

        registerService(Context.COMPANION_DEVICE_SERVICE, CompanionDeviceManager.class,
                new CachedServiceFetcher<CompanionDeviceManager>() {
            @Override
            public CompanionDeviceManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                ICompanionDeviceManager service = null;
                // If the feature not present, don't try to look up every time
                if (ctx.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                    service = ICompanionDeviceManager.Stub.asInterface(
                            ServiceManager.getServiceOrThrow(Context.COMPANION_DEVICE_SERVICE));
                }
                return new CompanionDeviceManager(service, ctx.getOuterContext());
            }});

        if (enableAppFunctionManager()) {
            registerService(Context.APP_FUNCTION_SERVICE, AppFunctionManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public AppFunctionManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            if (!AppFunctionManagerConfiguration.isSupported(ctx)) {
                                return null;
                            }
                            IAppFunctionManager service;
                            service = IAppFunctionManager.Stub.asInterface(
                                    ServiceManager.getServiceOrThrow(Context.APP_FUNCTION_SERVICE));
                            return new AppFunctionManager(service, ctx.getOuterContext());
                        }
                    });
        }

      if (enableLskfResetManager()) {
            registerService(Context.LSKF_RESET_SERVICE, LskfResetManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public LskfResetManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                                    ILskfResetManager service;
                            service = ILskfResetManager.Stub.asInterface(
                                    ServiceManager.getServiceOrThrow(
                                        Context.LSKF_RESET_SERVICE));
                            return new LskfResetManager(service, ctx.getOuterContext());
                        }
                    });
        }


        registerService(Context.VIRTUAL_DEVICE_SERVICE, VirtualDeviceManager.class,
                new CachedServiceFetcher<VirtualDeviceManager>() {
            @Override
            public VirtualDeviceManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                if (!ctx.getResources().getBoolean(R.bool.config_enableVirtualDeviceManager)) {
                    return null;
                }

                IVirtualDeviceManager service = IVirtualDeviceManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(Context.VIRTUAL_DEVICE_SERVICE));
                return new VirtualDeviceManager(service, ctx.getOuterContext());
            }});

        registerService(Context.CONSUMER_IR_SERVICE, ConsumerIrManager.class,
                new CachedServiceFetcher<ConsumerIrManager>() {
            @Override
            public ConsumerIrManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new ConsumerIrManager(ctx);
            }});

        registerService(Context.TRUST_SERVICE, TrustManager.class,
                new StaticServiceFetcher<TrustManager>() {
            @Override
            public TrustManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.TRUST_SERVICE);
                return new TrustManager(b);
            }});

        registerService(Context.FINGERPRINT_SERVICE, FingerprintManager.class,
                new CachedServiceFetcher<FingerprintManager>() {
            @Override
            public FingerprintManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                final IBinder binder;
                if (ctx.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
                    binder = ServiceManager.getServiceOrThrow(Context.FINGERPRINT_SERVICE);
                } else {
                    binder = ServiceManager.getService(Context.FINGERPRINT_SERVICE);
                }
                IFingerprintService service = IFingerprintService.Stub.asInterface(binder);
                return new FingerprintManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.FACE_SERVICE, FaceManager.class,
                new CachedServiceFetcher<FaceManager>() {
                    @Override
                    public FaceManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final IBinder binder;
                        if (ctx.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
                            binder = ServiceManager.getServiceOrThrow(Context.FACE_SERVICE);
                        } else {
                            binder = ServiceManager.getService(Context.FACE_SERVICE);
                        }
                        IFaceService service = IFaceService.Stub.asInterface(binder);
                        return new FaceManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.IRIS_SERVICE, IrisManager.class,
                new CachedServiceFetcher<IrisManager>() {
                    @Override
                    public IrisManager createService(ContextImpl ctx)
                        throws ServiceNotFoundException {
                        final IBinder binder =
                                ServiceManager.getServiceOrThrow(Context.IRIS_SERVICE);
                        IIrisService service = IIrisService.Stub.asInterface(binder);
                        return new IrisManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.BIOMETRIC_SERVICE, BiometricManager.class,
                new CachedServiceFetcher<BiometricManager>() {
                    @Override
                    public BiometricManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final IBinder binder =
                                ServiceManager.getServiceOrThrow(Context.AUTH_SERVICE);
                        final IAuthService service =
                                IAuthService.Stub.asInterface(binder);
                        return new BiometricManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.AUTHENTICATION_POLICY_SERVICE,
                AuthenticationPolicyManager.class,
                new CachedServiceFetcher<AuthenticationPolicyManager>() {
                    @Override
                    public AuthenticationPolicyManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final IBinder binder = ServiceManager.getServiceOrThrow(
                                Context.AUTHENTICATION_POLICY_SERVICE);
                        final IAuthenticationPolicyService service =
                                IAuthenticationPolicyService.Stub.asInterface(binder);
                        return new AuthenticationPolicyManager(ctx.getOuterContext(), service);
                    }
                });

        registerService(Context.TV_INTERACTIVE_APP_SERVICE, TvInteractiveAppManager.class,
                new CachedServiceFetcher<TvInteractiveAppManager>() {
            @Override
            public TvInteractiveAppManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                IBinder iBinder =
                        ServiceManager.getServiceOrThrow(Context.TV_INTERACTIVE_APP_SERVICE);
                ITvInteractiveAppManager service =
                        ITvInteractiveAppManager.Stub.asInterface(iBinder);
                return new TvInteractiveAppManager(service, ctx.getUserId());
            }});

        registerService(Context.TV_AD_SERVICE, TvAdManager.class,
                new CachedServiceFetcher<TvAdManager>() {
                    @Override
                    public TvAdManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder =
                                ServiceManager.getServiceOrThrow(Context.TV_AD_SERVICE);
                        ITvAdManager service =
                                ITvAdManager.Stub.asInterface(iBinder);
                        return new TvAdManager(service, ctx.getUserId());
                    }});

        registerService(Context.TV_INPUT_SERVICE, TvInputManager.class,
                new CachedServiceFetcher<TvInputManager>() {
            @Override
            public TvInputManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder iBinder = ServiceManager.getServiceOrThrow(Context.TV_INPUT_SERVICE);
                ITvInputManager service = ITvInputManager.Stub.asInterface(iBinder);
                return new TvInputManager(service, ctx.getUserId());
            }});

        registerService(Context.TV_TUNER_RESOURCE_MGR_SERVICE, TunerResourceManager.class,
                new CachedServiceFetcher<TunerResourceManager>() {
            @Override
            public TunerResourceManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                IBinder iBinder =
                        ServiceManager.getServiceOrThrow(Context.TV_TUNER_RESOURCE_MGR_SERVICE);
                ITunerResourceManager service = ITunerResourceManager.Stub.asInterface(iBinder);
                return new TunerResourceManager(service, ctx.getUserId());
            }});

        registerService(Context.NETWORK_SCORE_SERVICE, NetworkScoreManager.class,
                new CachedServiceFetcher<NetworkScoreManager>() {
            @Override
            public NetworkScoreManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new NetworkScoreManager(ctx);
            }});

        registerService(Context.USAGE_STATS_SERVICE, UsageStatsManager.class,
                new CachedServiceFetcher<UsageStatsManager>() {
            @Override
            public UsageStatsManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder iBinder = ServiceManager.getServiceOrThrow(Context.USAGE_STATS_SERVICE);
                IUsageStatsManager service = IUsageStatsManager.Stub.asInterface(iBinder);
                return new UsageStatsManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.PERSISTENT_DATA_BLOCK_SERVICE, PersistentDataBlockManager.class,
                new StaticServiceFetcher<PersistentDataBlockManager>() {
            @Override
            public PersistentDataBlockManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.PERSISTENT_DATA_BLOCK_SERVICE);
                IPersistentDataBlockService persistentDataBlockService =
                        IPersistentDataBlockService.Stub.asInterface(b);
                if (persistentDataBlockService != null) {
                    return new PersistentDataBlockManager(persistentDataBlockService);
                } else {
                    // not supported
                    return null;
                }
            }
         });

        registerService(Context.OEM_LOCK_SERVICE, OemLockManager.class,
                new StaticServiceFetcher<OemLockManager>() {
            @Override
            public OemLockManager createService() throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.OEM_LOCK_SERVICE);
                IOemLockService oemLockService = IOemLockService.Stub.asInterface(b);
                if (oemLockService != null) {
                    return new OemLockManager(oemLockService);
                } else {
                    // not supported
                    return null;
                }
            }});

        registerService(Context.MEDIA_PROJECTION_SERVICE, MediaProjectionManager.class,
                new CachedServiceFetcher<MediaProjectionManager>() {
            @Override
            public MediaProjectionManager createService(ContextImpl ctx) {
                return new MediaProjectionManager(ctx);
            }});

        registerService(Context.APPWIDGET_SERVICE, AppWidgetManager.class,
                new CachedServiceFetcher<AppWidgetManager>() {
            @Override
            public AppWidgetManager createService(ContextImpl ctx) {
                IBinder b = ServiceManager.getService(Context.APPWIDGET_SERVICE);
                return b == null ? null : new AppWidgetManager(ctx,
                        IAppWidgetService.Stub.asInterface(b));
            }});

        registerService(Context.MIDI_SERVICE, MidiManager.class,
                new CachedServiceFetcher<MidiManager>() {
            @Override
            public MidiManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.MIDI_SERVICE);
                return new MidiManager(IMidiManager.Stub.asInterface(b));
            }});

        registerService(Context.RADIO_SERVICE, RadioManager.class,
                new CachedServiceFetcher<RadioManager>() {
            @Override
            public RadioManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new RadioManager(ctx);
            }});

        registerService(Context.HARDWARE_PROPERTIES_SERVICE, HardwarePropertiesManager.class,
                new CachedServiceFetcher<HardwarePropertiesManager>() {
            @Override
            public HardwarePropertiesManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.HARDWARE_PROPERTIES_SERVICE);
                IHardwarePropertiesManager service =
                        IHardwarePropertiesManager.Stub.asInterface(b);
                return new HardwarePropertiesManager(ctx, service);
            }});

        registerService(Context.SOUND_TRIGGER_SERVICE, SoundTriggerManager.class,
                new CachedServiceFetcher<SoundTriggerManager>() {
            @Override
            public SoundTriggerManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.SOUND_TRIGGER_SERVICE);
                return new SoundTriggerManager(ctx, ISoundTriggerService.Stub.asInterface(b));
            }});

        registerService(Context.SHORTCUT_SERVICE, ShortcutManager.class,
                new CachedServiceFetcher<ShortcutManager>() {
            @Override
            public ShortcutManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.SHORTCUT_SERVICE);
                return new ShortcutManager(ctx, IShortcutService.Stub.asInterface(b));
            }});

        registerService(Context.OVERLAY_SERVICE, OverlayManager.class,
                new CachedServiceFetcher<OverlayManager>() {
            @Override
            public OverlayManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                final IBinder b =
                        (Compatibility.isChangeEnabled(OverlayManager.SELF_TARGETING_OVERLAY))
                                ? ServiceManager.getService(Context.OVERLAY_SERVICE)
                                : ServiceManager.getServiceOrThrow(Context.OVERLAY_SERVICE);
                return new OverlayManager(ctx, IOverlayManager.Stub.asInterface(b));
            }});

        registerService(Context.NETWORK_WATCHLIST_SERVICE, NetworkWatchlistManager.class,
                new CachedServiceFetcher<NetworkWatchlistManager>() {
                    @Override
                    public NetworkWatchlistManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b =
                                ServiceManager.getServiceOrThrow(Context.NETWORK_WATCHLIST_SERVICE);
                        return new NetworkWatchlistManager(ctx,
                                INetworkWatchlistManager.Stub.asInterface(b));
                    }});

        registerService(Context.SYSTEM_HEALTH_SERVICE, SystemHealthManager.class,
                new CachedServiceFetcher<SystemHealthManager>() {
            @Override
            public SystemHealthManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder batteryStats = ServiceManager.getServiceOrThrow(BatteryStats.SERVICE_NAME);
                IBinder powerStats = ServiceManager.getService(Context.POWER_STATS_SERVICE);
                IBinder perfHint = ServiceManager.getService(Context.PERFORMANCE_HINT_SERVICE);
                return new SystemHealthManager(IBatteryStats.Stub.asInterface(batteryStats),
                        IPowerStatsService.Stub.asInterface(powerStats),
                        IHintManager.Stub.asInterface(perfHint));
            }});

        registerService(Context.CONTEXTHUB_SERVICE, ContextHubManager.class,
                new CachedServiceFetcher<ContextHubManager>() {
            @Override
            public ContextHubManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getService(Context.CONTEXTHUB_SERVICE);
                if (b == null) {
                    return null;
                }
                return new ContextHubManager(IContextHubService.Stub.asInterface(b),
                        ctx.mMainThread.getHandler().getLooper());
            }});

        registerService(Context.INCIDENT_SERVICE, IncidentManager.class,
                new CachedServiceFetcher<IncidentManager>() {
            @Override
            public IncidentManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                return new IncidentManager(ctx);
            }});

        registerService(Context.BUGREPORT_SERVICE, BugreportManager.class,
                new CachedServiceFetcher<BugreportManager>() {
                    @Override
                    public BugreportManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.BUGREPORT_SERVICE);
                        return new BugreportManager(ctx.getOuterContext(),
                                IDumpstate.Stub.asInterface(b));
                    }});

        registerService(Context.AUTOFILL_SERVICE, AutofillManager.class,
                new CachedServiceFetcher<AutofillManager>() {
            @Override
            public AutofillManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                // Get the services without throwing as this is an optional feature
                IBinder b = ServiceManager.getService(Context.AUTOFILL_SERVICE);
                IAutoFillManager service = IAutoFillManager.Stub.asInterface(b);
                return new AutofillManager(ctx.getOuterContext(), service);
            }});

        registerService(Context.CREDENTIAL_SERVICE, CredentialManager.class,
                new CachedServiceFetcher<CredentialManager>() {
                    @Override
                    public CredentialManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getService(Context.CREDENTIAL_SERVICE);
                        ICredentialManager service = ICredentialManager.Stub.asInterface(b);
                        if (service != null) {
                            return new CredentialManager(ctx.getOuterContext(), service);
                        }
                        return null;
                    }});

        registerService(Context.MUSIC_RECOGNITION_SERVICE, MusicRecognitionManager.class,
                new CachedServiceFetcher<MusicRecognitionManager>() {
                    @Override
                    public MusicRecognitionManager createService(ContextImpl ctx) {
                        IBinder b = ServiceManager.getService(
                                Context.MUSIC_RECOGNITION_SERVICE);
                        return new MusicRecognitionManager(
                                IMusicRecognitionManager.Stub.asInterface(b));
                    }
                });

        registerService(Context.CONTENT_CAPTURE_MANAGER_SERVICE, ContentCaptureManager.class,
                new CachedServiceFetcher<ContentCaptureManager>() {
            @Override
            public ContentCaptureManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                // Get the services without throwing as this is an optional feature
                Context outerContext = ctx.getOuterContext();
                ContentCaptureOptions options = outerContext.getContentCaptureOptions();
                // Options is null when the service didn't allowlist the activity or package
                if (options != null && (options.lite || options.isWhitelisted(outerContext))) {
                    IBinder b = ServiceManager
                            .getService(Context.CONTENT_CAPTURE_MANAGER_SERVICE);
                    IContentCaptureManager service = IContentCaptureManager.Stub.asInterface(b);
                    // Service is null when not provided by OEM or disabled by kill-switch.
                    if (service != null) {
                        return new ContentCaptureManager(outerContext, service, options);
                    }
                }
                // When feature is disabled or app / package not allowlisted, we return a null
                // manager to apps so the performance impact is practically zero
                return null;
            }});

        registerService(Context.TRANSLATION_MANAGER_SERVICE, TranslationManager.class,
                new CachedServiceFetcher<TranslationManager>() {
                    @Override
                    public TranslationManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getService(Context.TRANSLATION_MANAGER_SERVICE);
                        ITranslationManager service = ITranslationManager.Stub.asInterface(b);
                        // Service is null when not provided by OEM.
                        if (service != null) {
                            return new TranslationManager(ctx.getOuterContext(), service);
                        }
                        return null;
                    }});

        registerService(Context.UI_TRANSLATION_SERVICE, UiTranslationManager.class,
                new CachedServiceFetcher<UiTranslationManager>() {
                    @Override
                    public UiTranslationManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getService(Context.TRANSLATION_MANAGER_SERVICE);
                        ITranslationManager service = ITranslationManager.Stub.asInterface(b);
                        if (service != null) {
                            return new UiTranslationManager(ctx.getOuterContext(), service);
                        }
                        return null;
                    }});

        registerService(Context.SEARCH_UI_SERVICE, SearchUiManager.class,
            new CachedServiceFetcher<SearchUiManager>() {
                @Override
                public SearchUiManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                    IBinder b = ServiceManager.getService(Context.SEARCH_UI_SERVICE);
                    return b == null ? null : new SearchUiManager(ctx);
                }
            });

        registerService(Context.SMARTSPACE_SERVICE, SmartspaceManager.class,
            new CachedServiceFetcher<SmartspaceManager>() {
                @Override
                public SmartspaceManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                    IBinder b = ServiceManager.getService(Context.SMARTSPACE_SERVICE);
                    return b == null ? null : new SmartspaceManager(ctx);
                }
            });

        registerService(Context.CONTEXTUAL_SEARCH_SERVICE, ContextualSearchManager.class,
                new CachedServiceFetcher<>() {
                    @Override
                    public ContextualSearchManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getService(Context.CONTEXTUAL_SEARCH_SERVICE);
                        return b == null ? null : new ContextualSearchManager();
                    }
                });

        registerService(Context.APP_PREDICTION_SERVICE, AppPredictionManager.class,
                new CachedServiceFetcher<AppPredictionManager>() {
            @Override
            public AppPredictionManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                IBinder b = ServiceManager.getService(Context.APP_PREDICTION_SERVICE);
                return b == null ? null : new AppPredictionManager(ctx);
            }
        });

        registerService(Context.CONTENT_SUGGESTIONS_SERVICE,
                ContentSuggestionsManager.class,
                new CachedServiceFetcher<ContentSuggestionsManager>() {
                    @Override
                    public ContentSuggestionsManager createService(ContextImpl ctx) {
                        // No throw as this is an optional service
                        IBinder b = ServiceManager.getService(
                                Context.CONTENT_SUGGESTIONS_SERVICE);
                        IContentSuggestionsManager service =
                                IContentSuggestionsManager.Stub.asInterface(b);
                        return new ContentSuggestionsManager(ctx.getUserId(), service);
                    }
                });

        registerService(Context.WALLPAPER_EFFECTS_GENERATION_SERVICE,
                WallpaperEffectsGenerationManager.class,
                new CachedServiceFetcher<WallpaperEffectsGenerationManager>() {
                    @Override
                    public WallpaperEffectsGenerationManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getService(
                                Context.WALLPAPER_EFFECTS_GENERATION_SERVICE);
                        return b == null ? null :
                                new WallpaperEffectsGenerationManager(
                                        IWallpaperEffectsGenerationManager.Stub.asInterface(b));
                    }
                });

        registerService(Context.VR_SERVICE, VrManager.class, new CachedServiceFetcher<VrManager>() {
            @Override
            public VrManager createService(ContextImpl ctx) throws ServiceNotFoundException {
                IBinder b = ServiceManager.getServiceOrThrow(Context.VR_SERVICE);
                return new VrManager(IVrManager.Stub.asInterface(b));
            }
        });

        if (android.companion.Flags.taskContinuity()) {
            registerService(Context.TASK_CONTINUITY_SERVICE, TaskContinuityManager.class,
                    new CachedServiceFetcher<TaskContinuityManager>() {
                        @Override
                        public TaskContinuityManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder iBinder = ServiceManager.getServiceOrThrow(
                                    Context.TASK_CONTINUITY_SERVICE);
                            ITaskContinuityManager service =
                                    ITaskContinuityManager.Stub.asInterface(iBinder);
                            return new TaskContinuityManager(ctx, service);
                        }
                    });
        }

        if (android.companion.Flags.universalClipboard()) {
            registerService(Context.UNIVERSAL_CLIPBOARD_SERVICE, UniversalClipboardManager.class,
                    new CachedServiceFetcher<UniversalClipboardManager>() {
                        @Override
                        public UniversalClipboardManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder iBinder = ServiceManager.getServiceOrThrow(
                                    Context.UNIVERSAL_CLIPBOARD_SERVICE);
                            IUniversalClipboardManager service =
                                    IUniversalClipboardManager.Stub.asInterface(iBinder);
                            return new UniversalClipboardManager(ctx, service);
                        }
                    });
        }

        registerService(Context.CROSS_PROFILE_APPS_SERVICE, CrossProfileApps.class,
                new CachedServiceFetcher<CrossProfileApps>() {
                    @Override
                    public CrossProfileApps createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.CROSS_PROFILE_APPS_SERVICE);
                        return new CrossProfileApps(ctx.getOuterContext(),
                                ICrossProfileApps.Stub.asInterface(b));
                    }
                });

        registerService(Context.SLICE_SERVICE, SliceManager.class,
                new CachedServiceFetcher<SliceManager>() {
                    @Override
                    public SliceManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new SliceManager(ctx.getOuterContext(),
                                ctx.mMainThread.getHandler());
                    }
            });

        registerService(Context.TIME_DETECTOR_SERVICE, TimeDetector.class,
                new CachedServiceFetcher<TimeDetector>() {
                    @Override
                    public TimeDetector createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new TimeDetectorImpl();
                    }});

        registerService(Context.TIME_ZONE_DETECTOR_SERVICE, TimeZoneDetector.class,
                new CachedServiceFetcher<TimeZoneDetector>() {
                    @Override
                    public TimeZoneDetector createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new TimeZoneDetectorImpl();
                    }});

        registerService(Context.TIME_MANAGER_SERVICE, TimeManager.class,
                new CachedServiceFetcher<TimeManager>() {
                    @Override
                    public TimeManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new TimeManager();
                    }});

        registerService(Context.PERMISSION_SERVICE, PermissionManager.class,
                new CachedServiceFetcher<PermissionManager>() {
                    @Override
                    public PermissionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new PermissionManager(ctx.getOuterContext());
                    }});

        registerService(Context.LEGACY_PERMISSION_SERVICE, LegacyPermissionManager.class,
                new CachedServiceFetcher<LegacyPermissionManager>() {
                    @Override
                    public LegacyPermissionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new LegacyPermissionManager();
                    }});

        registerService(Context.PERMISSION_CONTROLLER_SERVICE, PermissionControllerManager.class,
                new CachedServiceFetcher<PermissionControllerManager>() {
                    @Override
                    public PermissionControllerManager createService(ContextImpl ctx) {
                        return new PermissionControllerManager(ctx.getOuterContext(),
                                ctx.getMainThreadHandler());
                    }});

        registerService(Context.PERMISSION_CHECKER_SERVICE, PermissionCheckerManager.class,
                new CachedServiceFetcher<PermissionCheckerManager>() {
                    @Override
                    public PermissionCheckerManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new PermissionCheckerManager(ctx.getOuterContext());
                    }});

        registerService(Context.PERMISSION_ENFORCER_SERVICE, PermissionEnforcer.class,
                new CachedServiceFetcher<PermissionEnforcer>() {
                    @Override
                    public PermissionEnforcer createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new PermissionEnforcer(ctx.getOuterContext());
                    }});

        registerService(Context.DYNAMIC_SYSTEM_SERVICE, DynamicSystemManager.class,
                new CachedServiceFetcher<DynamicSystemManager>() {
                    @Override
                    public DynamicSystemManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.DYNAMIC_SYSTEM_SERVICE);
                        return new DynamicSystemManager(
                                IDynamicSystemService.Stub.asInterface(b));
                    }});

        registerService(Context.BATTERY_STATS_SERVICE, BatteryStatsManager.class,
                new CachedServiceFetcher<BatteryStatsManager>() {
                    @Override
                    public BatteryStatsManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.BATTERY_STATS_SERVICE);
                        return new BatteryStatsManager(
                                IBatteryStats.Stub.asInterface(b));
                    }});
        registerService(Context.DATA_LOADER_MANAGER_SERVICE, DataLoaderManager.class,
                new CachedServiceFetcher<DataLoaderManager>() {
                    @Override
                    public DataLoaderManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.DATA_LOADER_MANAGER_SERVICE);
                        return new DataLoaderManager(IDataLoaderManager.Stub.asInterface(b));
                    }});
        registerService(Context.LIGHTS_SERVICE, LightsManager.class,
            new CachedServiceFetcher<LightsManager>() {
                @Override
                public LightsManager createService(ContextImpl ctx)
                    throws ServiceNotFoundException {
                    return new SystemLightsManager(ctx);
                }});
        registerService(Context.LOCALE_SERVICE, LocaleManager.class,
                new CachedServiceFetcher<LocaleManager>() {
                    @Override
                    public LocaleManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new LocaleManager(ctx, ILocaleManager.Stub.asInterface(
                                ServiceManager.getServiceOrThrow(Context.LOCALE_SERVICE)));
                    }});
        registerService(Context.INCREMENTAL_SERVICE, IncrementalManager.class,
                new CachedServiceFetcher<IncrementalManager>() {
                    @Override
                    public IncrementalManager createService(ContextImpl ctx) {
                        IBinder b = ServiceManager.getService(Context.INCREMENTAL_SERVICE);
                        if (b == null) {
                            return null;
                        }
                        return new IncrementalManager(
                                IIncrementalService.Stub.asInterface(b));
                    }});

        registerService(Context.FILE_INTEGRITY_SERVICE, FileIntegrityManager.class,
                new CachedServiceFetcher<FileIntegrityManager>() {
                    @Override
                    public FileIntegrityManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.FILE_INTEGRITY_SERVICE);
                        return new FileIntegrityManager(ctx.getOuterContext(),
                                IFileIntegrityService.Stub.asInterface(b));
                    }});

        registerService(Context.ATTESTATION_VERIFICATION_SERVICE,
                AttestationVerificationManager.class,
                new CachedServiceFetcher<AttestationVerificationManager>() {
                    @Override
                    public AttestationVerificationManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.ATTESTATION_VERIFICATION_SERVICE);
                        return new AttestationVerificationManager(ctx.getOuterContext(),
                                IAttestationVerificationManagerService.Stub.asInterface(b));
                    }});
        registerService(Context.APP_HIBERNATION_SERVICE, AppHibernationManager.class,
                new CachedServiceFetcher<AppHibernationManager>() {
                    @Override
                    public AppHibernationManager createService(ContextImpl ctx) {
                        IBinder b = ServiceManager.getService(Context.APP_HIBERNATION_SERVICE);
                        return b == null ? null : new AppHibernationManager(ctx);
                    }});

        if (enablePccFrameworkSupport()) {
            registerService(Context.PCC_SANDBOX_SERVICE, PccSandboxManager.class,
                    new CachedServiceFetcher<PccSandboxManager>() {
                        @Override
                        public PccSandboxManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IPccSandboxManager service;
                            service = IPccSandboxManager.Stub.asInterface(
                                    ServiceManager.getServiceOrThrow(Context.PCC_SANDBOX_SERVICE));
                            return new PccSandboxManager(service, ctx.getOuterContext());
                        }
                    });
        }

        registerService(Context.DREAM_SERVICE, DreamManager.class,
                new CachedServiceFetcher<DreamManager>() {
                    @Override
                    public DreamManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new DreamManager(ctx);
                    }});

        if (android.service.personalcontext.Flags.enablePersonalContextService()) {
            registerService(Context.PERSONAL_CONTEXT_SERVICE, PersonalContextManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public PersonalContextManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder iBinder = ServiceManager.getServiceOrThrow(
                                    Context.PERSONAL_CONTEXT_SERVICE);
                            IPersonalContextManager service =
                                    IPersonalContextManager.Stub.asInterface(iBinder);
                            return new PersonalContextManager(ctx, service);
                        }
                    });
        }

        registerService(Context.DEVICE_STATE_SERVICE, DeviceStateManager.class,
                new CachedServiceFetcher<DeviceStateManager>() {
                    @Override
                    public DeviceStateManager createService(ContextImpl ctx) {
                        return new DeviceStateManager();
                    }});

        registerService(Context.MEDIA_METRICS_SERVICE, MediaMetricsManager.class,
                new CachedServiceFetcher<MediaMetricsManager>() {
                    @Override
                    public MediaMetricsManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder =
                                ServiceManager.getServiceOrThrow(Context.MEDIA_METRICS_SERVICE);
                        IMediaMetricsManager service =
                                IMediaMetricsManager.Stub.asInterface(iBinder);
                        return new MediaMetricsManager(service, ctx.getUserId());
                    }});

        registerService(Context.GAME_SERVICE, GameManager.class,
                new CachedServiceFetcher<GameManager>() {
                    @Override
                    public GameManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        final PackageManager pm = ctx.getPackageManager();
                        final boolean isWatch = pm.hasSystemFeature(PackageManager.FEATURE_WATCH);
                        // Allow a potentially absent GameManagerService only for
                        // Wear devices. For non-Wear devices, throw a
                        // ServiceNotFoundException when the service is missing.
                        final IBinder binder = isWatch
                                ? ServiceManager.getService(Context.GAME_SERVICE)
                                : ServiceManager.getServiceOrThrow(Context.GAME_SERVICE);

                        if (binder == null
                                && Compatibility.isChangeEnabled(NULL_GAME_MANAGER_IN_WEAR)) {
                            return null;
                        }

                        return new GameManager(
                                ctx.getOuterContext(),
                                IGameManagerService.Stub.asInterface(binder));
                    }
                });

        registerService(Context.DOMAIN_VERIFICATION_SERVICE, DomainVerificationManager.class,
                new CachedServiceFetcher<DomainVerificationManager>() {
                    @Override
                    public DomainVerificationManager createService(ContextImpl context)
                            throws ServiceNotFoundException {
                        IBinder binder = ServiceManager.getServiceOrThrow(
                                Context.DOMAIN_VERIFICATION_SERVICE);
                        IDomainVerificationManager service =
                                IDomainVerificationManager.Stub.asInterface(binder);
                        return new DomainVerificationManager(context, service);
                    }
                });

        registerService(Context.DISPLAY_HASH_SERVICE, DisplayHashManager.class,
                new CachedServiceFetcher<DisplayHashManager>() {
                    @Override
                    public DisplayHashManager createService(ContextImpl ctx) {
                        return new DisplayHashManager();
                    }
                });

        registerService(Context.AMBIENT_CONTEXT_SERVICE, AmbientContextManager.class,
                new CachedServiceFetcher<AmbientContextManager>() {
                    @Override
                    public AmbientContextManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder = ServiceManager.getServiceOrThrow(
                                Context.AMBIENT_CONTEXT_SERVICE);
                        IAmbientContextManager manager =
                                IAmbientContextManager.Stub.asInterface(iBinder);
                        return new AmbientContextManager(ctx.getOuterContext(), manager);
                    }});

        registerService(Context.WEARABLE_SENSING_SERVICE, WearableSensingManager.class,
                new CachedServiceFetcher<WearableSensingManager>() {
                    @Override
                    public WearableSensingManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder = ServiceManager.getService(
                                Context.WEARABLE_SENSING_SERVICE);
                        if (iBinder != null) {
                            IWearableSensingManager manager =
                                    IWearableSensingManager.Stub.asInterface(iBinder);
                            return new WearableSensingManager(ctx.getOuterContext(), manager);
                        }
                        // Wear intentionally removes the service, so do not throw a
                        // ServiceNotFoundException when the service is not absent.
                        if (ctx.getPackageManager().hasSystemFeature(
                                PackageManager.FEATURE_WATCH)) {
                            return null;
                        }
                        throw new ServiceNotFoundException(Context.WEARABLE_SENSING_SERVICE);
                    }});

        registerService(Context.GRAMMATICAL_INFLECTION_SERVICE, GrammaticalInflectionManager.class,
                new CachedServiceFetcher<GrammaticalInflectionManager>() {
                    @Override
                    public GrammaticalInflectionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return new GrammaticalInflectionManager(ctx,
                                IGrammaticalInflectionManager.Stub.asInterface(
                                        ServiceManager.getServiceOrThrow(
                                                Context.GRAMMATICAL_INFLECTION_SERVICE)));
                    }});

        registerService(Context.SHARED_CONNECTIVITY_SERVICE, SharedConnectivityManager.class,
                new CachedServiceFetcher<SharedConnectivityManager>() {
                    @Override
                    public SharedConnectivityManager createService(ContextImpl ctx) {
                        return SharedConnectivityManager.create(ctx);
                    }
                });

        registerService(Context.KEYSTORE_SERVICE, KeyStoreManager.class,
                new StaticServiceFetcher<KeyStoreManager>() {
                    @Override
                    public KeyStoreManager createService()
                            throws ServiceNotFoundException {
                        if (!android.security.Flags.keystoreGrantApi()) {
                            throw new ServiceNotFoundException("KeyStoreManager is not supported");
                        }
                        return KeyStoreManager.getInstance();
                    }});

        registerService(Context.CONTACT_KEYS_SERVICE, E2eeContactKeysManager.class,
                new CachedServiceFetcher<E2eeContactKeysManager>() {
                    @Override
                    public E2eeContactKeysManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        if (!android.provider.Flags.userKeys()) {
                            throw new ServiceNotFoundException(
                                    "ContactKeysManager is not supported");
                        }
                        return new E2eeContactKeysManager(ctx);
                    }});

      registerService(Context.CONTENT_RESTRICTION_SERVICE, ContentRestrictionManager.class,
                new CachedServiceFetcher<>() {
                    @Override
                    public ContentRestrictionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        if (!android.app.contentrestriction.flags.Flags.contentRestrictionApi()) {
                            throw new ServiceNotFoundException(
                                    "ContentRestrictionManager is not supported");
                        }
                        IBinder iBinder = ServiceManager.getServiceOrThrow(
                                Context.CONTENT_RESTRICTION_SERVICE);
                        IContentRestrictionManager service =
                                IContentRestrictionManager.Stub.asInterface(iBinder);
                        return new ContentRestrictionManager(ctx, service);
                    }
                });

        registerService(Context.SUPERVISION_SERVICE, SupervisionManager.class,
                new CachedServiceFetcher<>() {
                    @Override
                    public SupervisionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder = ServiceManager.getServiceOrThrow(
                                Context.SUPERVISION_SERVICE);
                        ISupervisionManager service = ISupervisionManager.Stub.asInterface(iBinder);
                        return new SupervisionManager(ctx, service);
                    }
                });

        registerService(Context.ADVANCED_PROTECTION_SERVICE, AdvancedProtectionManager.class,
                new CachedServiceFetcher<>() {
                    @Override
                    public AdvancedProtectionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder = ServiceManager.getService(
                                Context.ADVANCED_PROTECTION_SERVICE);
                        IAdvancedProtectionService service =
                                IAdvancedProtectionService.Stub.asInterface(iBinder);
                        if (service == null) {
                            return null;
                        }
                        return new AdvancedProtectionManager(service);
                    }
                });

        // DO NOT do a flag check like this unless the flag is read-only.
        // (because this code is executed during preload in zygote.)
        // If the flag is mutable, the check should be inside CachedServiceFetcher.
        if (Flags.bicClient()) {
            registerService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE,
                    BackgroundInstallControlManager.class,
                    new CachedServiceFetcher<BackgroundInstallControlManager>() {
                        @Override
                        public BackgroundInstallControlManager createService(ContextImpl ctx) {
                            return new BackgroundInstallControlManager(ctx);
                        }
                    });
        }
        registerService(Context.MEDIA_QUALITY_SERVICE, MediaQualityManager.class,
                new CachedServiceFetcher<MediaQualityManager>() {
                    @Override
                    public MediaQualityManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        IBinder iBinder = ServiceManager
                                .getServiceOrThrow(Context.MEDIA_QUALITY_SERVICE);
                        IMediaQualityManager service = IMediaQualityManager
                                .Stub.asInterface(iBinder);
                        return new MediaQualityManager(ctx, service);
                    }
                });

        registerService(Context.INTRUSION_DETECTION_SERVICE, IntrusionDetectionManager.class,
                new CachedServiceFetcher<IntrusionDetectionManager>() {
                    @Override
                    public IntrusionDetectionManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        if (!android.security.Flags.aflApi()) {
                            throw new ServiceNotFoundException(
                                    "Intrusion Detection is not supported");
                        }
                        IBinder b = ServiceManager.getServiceOrThrow(
                                Context.INTRUSION_DETECTION_SERVICE);
                        IIntrusionDetectionService service =
                                IIntrusionDetectionService.Stub.asInterface(b);
                        return new IntrusionDetectionManager(service);
                    }
                });

        registerService(Context.UI_LATENCY_STATS_SERVICE,
                android.uilatencystats.UiLatencyStatsManager.class,
                new CachedServiceFetcher<android.uilatencystats.UiLatencyStatsManager>() {
                    @Override
                    public android.uilatencystats.UiLatencyStatsManager createService(
                            ContextImpl ctx) throws ServiceNotFoundException {
                        if (!com.android.server.ui_latency_stats.Flags.uiLatencyStatsService()) {
                            throw new ServiceNotFoundException(
                                    "UiLatencyStatsManager is not supported");
                        }
                        android.uilatencystats.IUiLatencyStats service =
                                android.uilatencystats.IUiLatencyStats.Stub.asInterface(
                                        ServiceManager.getServiceOrThrow(
                                                Context.UI_LATENCY_STATS_SERVICE));
                        return new android.uilatencystats.UiLatencyStatsManager(ctx, service);
                    }
                });

        if (interactiveChooser()) {
            registerService(
                    Context.CHOOSER_SERVICE,
                    ChooserManager.class,
                    new StaticServiceFetcher<>() {
                        @Override
                        public boolean isServiceEnabled(ContextImpl ctx) {
                            return isChooserManagerSupported(ctx);
                        }

                        @Override
                        public ChooserManager createService() {
                            return new ChooserManager();
                        }
                    });
        }

        if (aisealHostApis()) {
            registerService(
                    Context.AISEAL_HOST_SERVICE,
                    AiSealManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public AiSealManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            if (!ctx.getPackageManager().hasSystemFeature(FEATURE_AISEAL)) {
                                return null;
                            }
                            return new AiSealManager(ctx);
                        }
                    });
        }

        if (enableAppFunctionPermissionV2()) {
            registerService(
                    Context.ALLOWLIST_SERVICE,
                    AllowlistManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public AllowlistManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            IBinder b = ServiceManager.getServiceOrThrow(Context.ALLOWLIST_SERVICE);
                            IAllowlistService service = IAllowlistService.Stub.asInterface(b);
                            return new AllowlistManager(ctx, service);
                        }
                    });
        }

        registerService(Context.DYNAMIC_INSTRUMENTATION_SERVICE,
                DynamicInstrumentationManager.class,
                new CachedServiceFetcher<DynamicInstrumentationManager>() {
                    @Override
                    public DynamicInstrumentationManager createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        if (!android.security.Flags.dynamicInstrumentationApi()) {
                            throw new ServiceNotFoundException(
                                    Context.DYNAMIC_INSTRUMENTATION_SERVICE + " is not supported");
                        }
                        return new DynamicInstrumentationManager(ctx);
                    }
                });

        if (com.android.input.flags.Flags.enableAttentionServiceApis()) {
            registerService(Context.ATTENTION_SERVICE, AttentionManager.class,
                    new CachedServiceFetcher<>() {
                        @Override
                        public AttentionManager createService(ContextImpl ctx)
                                throws ServiceNotFoundException {
                            return new AttentionManager(
                                    Objects.requireNonNull(IAttentionManager.Stub.asInterface(
                                            ServiceManager.getService(
                                                    Context.ATTENTION_SERVICE))));
                        }
                    });
        }


        sInitializing = true;
        try {
            // Note: the following functions need to be @SystemApis, once they become mainline
            // modules.
            ConnectivityFrameworkInitializer.registerServiceWrappers();
            JobSchedulerFrameworkInitializer.registerServiceWrappers();
            BlobStoreManagerFrameworkInitializer.initialize();
            BluetoothFrameworkInitializer.registerServiceWrappers();
            NfcFrameworkInitializer.registerServiceWrappers();
            TelephonyFrameworkInitializer.registerServiceWrappers();
            AppSearchManagerFrameworkInitializer.initialize();
            HealthServicesInitializer.registerServiceWrappers();
            WifiFrameworkInitializer.registerServiceWrappers();
            StatsFrameworkInitializer.registerServiceWrappers();
            RollbackManagerFrameworkInitializer.initialize();
            MediaFrameworkPlatformInitializer.registerServiceWrappers();
            MediaFrameworkInitializer.registerServiceWrappers();
            RoleFrameworkInitializer.registerServiceWrappers();
            SchedulingFrameworkInitializer.registerServiceWrappers();
            SdkSandboxManagerFrameworkInitializer.registerServiceWrappers();
            AdServicesFrameworkInitializer.registerServiceWrappers();
            UwbFrameworkInitializer.registerServiceWrappers();
            SafetyCenterFrameworkInitializer.registerServiceWrappers();
            ConnectivityFrameworkInitializerTiramisu.registerServiceWrappers();
            NearbyFrameworkInitializer.registerServiceWrappers();
            OnDevicePersonalizationFrameworkInitializer.registerServiceWrappers();
            OnDeviceIntelligenceFrameworkInitializer.registerServiceWrappers();
            DeviceLockFrameworkInitializer.registerServiceWrappers();
            NpuManagerFrameworkInitializer.registerServiceWrappers();
            VirtualizationFrameworkInitializer.registerServiceWrappers();
            ConnectivityFrameworkInitializerBaklava.registerServiceWrappers();

            if (TelecomDependencies.isMainlineBuildFlagEnabled()) {
                TelecomDependencies.registerServiceWrapper();
            }

            if (com.android.webapp.flags.Flags.enableWebAppServiceV2()) {
                WebAppFrameworkInitializer.registerServiceWrappers();
            }

            if (newStoragePublicApi()) {
                ConfigInfrastructureFrameworkInitializer.registerServiceWrappers();
            }

            if (com.android.server.telecom.flags.Flags.telecomMainlineBlockedNumbersManager()) {
                ProviderFrameworkInitializer.registerServiceWrappers();
            }
            // This code is executed on zygote during preload, where only read-only
            // flags can be used. Do not use mutable flags.
            if (android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()) {
                EnhancedConfirmationFrameworkInitializer.registerServiceWrappers();
            }
            ProfilingFrameworkInitializer.registerServiceWrappers();
            if (android.os.profiling.anomaly.flags.Flags.anomalyDetectorCore()) {
                AnomalyDetectorFrameworkInitializer.registerServiceWrappers();
            }
            if (android.webkit.Flags.updateServiceIpcWrapper()) {
                WebViewBootstrapFrameworkInitializer.registerServiceWrappers();
            }
            // This is guarded by aconfig flag "com.android.ranging.flags.ranging_stack_enabled"
            // when the build flag RELEASE_RANGING_STACK is enabled. When disabled, this calls the
            // mock RangingFrameworkInitializer#registerServiceWrappers which is no-op. As the
            // aconfig lib for ranging module is built only if  RELEASE_RANGING_STACK is enabled,
            // flagcannot be added here.
            RangingFrameworkInitializer.registerServiceWrappers();

            // When RELEASE_ANOMALY_DETECTOR is "false", this call is a no-op.
            AnomalyDetectorFrameworkInitializer.registerServiceWrappers();
            if (android.security.Flags.uprobestatsBridgeService()) {
                UprobestatsFrameworkInitializer.registerServiceWrappers();
            }
        } finally {
            // If any of the above code throws, we're in a pretty bad shape and the process
            // will likely crash, but we'll reset it just in case there's an exception handler...
            sInitializing = false;
        }
    }

    /** Throws {@link IllegalStateException} if not during a static initialization. */
    private static void ensureInitializing(String methodName) {
        Preconditions.checkState(sInitializing, "Internal error: %s"
                + " can only be called during class initialization.", methodName);
    }
    /**
     * Creates an array which is used to cache per-Context service instances.
     * @hide
     */
    @RavenwoodKeep
    public static Object[] createServiceCache() {
        return new Object[sServiceCacheSize];
    }

    @RavenwoodRedirect
    private static void onUnknownSystemServiceError(String name) {
        if (sEnableServiceNotFoundWtf) {
            Slog.wtf(TAG, "Unknown manager requested: " + name);
        }
    }

    @RavenwoodKeep
    private static ServiceFetcher<?> getSystemServiceFetcher(String name) {
        if (name == null) {
            return null;
        }
        final ServiceFetcher<?> fetcher = SYSTEM_SERVICE_FETCHERS.get(name);
        if (fetcher == null) {
            onUnknownSystemServiceError(name);
            return null;
        }
        return fetcher;
    }

    private static boolean hasSystemFeatureOpportunistic(@NonNull ContextImpl ctx,
            @NonNull String featureName) {
        PackageManager manager = ctx.getPackageManager();
        if (manager == null) return true;
        return manager.hasSystemFeature(featureName);
    }

    /**
     * Gets a system service from a given context.
     * @hide
     */
    @RavenwoodKeep
    public static Object getSystemService(@NonNull ContextImpl ctx, String name) {
        final ServiceFetcher<?> fetcher = getSystemServiceFetcher(name);
        if (fetcher == null) {
            return null;
        }

        final Object ret = fetcher.getService(ctx);
        if (sEnableServiceNotFoundWtf && ret == null) {
            // Some services do return null in certain situations, so don't do WTF for them.
            switch (name) {
                case Context.CONTENT_CAPTURE_MANAGER_SERVICE:
                case Context.APP_PREDICTION_SERVICE:
                case Context.INCREMENTAL_SERVICE:
                case Context.ETHERNET_SERVICE:
                case Context.CONTEXTHUB_SERVICE:
                case Context.VIRTUALIZATION_SERVICE:
                case Context.VIRTUAL_DEVICE_SERVICE:
                    return null;
                case Context.VCN_MANAGEMENT_SERVICE:
                    if (!hasSystemFeatureOpportunistic(ctx,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)) {
                        return null;
                    }
                    break;
                case Context.SEARCH_SERVICE:
                    // Wear device does not support SEARCH_SERVICE so we do not print WTF here
                    if (hasSystemFeatureOpportunistic(ctx, PackageManager.FEATURE_WATCH)) {
                        return null;
                    }
                    break;
                case Context.APPWIDGET_SERVICE:
                    if (!hasSystemFeatureOpportunistic(ctx, PackageManager.FEATURE_APP_WIDGETS)) {
                        return null;
                    }
                    break;
                case Context.TEXT_SERVICES_MANAGER_SERVICE:
                    if (hasSystemFeatureOpportunistic(ctx, PackageManager.FEATURE_WATCH)) {
                        return null;
                    }
                    break;
                case Context.WIFI_AWARE_SERVICE:
                    if (!hasSystemFeatureOpportunistic(ctx, PackageManager.FEATURE_WIFI_AWARE)) {
                        return null;
                    }
                    break;
            }
            // TODO (b/404593897): make it a case of the switch statement above when the flag is
            //  removed.
            if (interactiveChooser()) {
                if (Context.CHOOSER_SERVICE.equals(name) && !isChooserManagerSupported(ctx)) {
                    return null;
                }
            }
            Slog.wtf(TAG, "Manager wrapper not available: " + name);
            return null;
        }
        return ret;
    }

    private static boolean isChooserManagerSupported(ContextImpl ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm == null) {
            return true;
        }
        return !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && !pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Gets a system service which has opted-in to being fetched without a context.
     * @hide
     */
    @FlaggedApi(android.webkit.Flags.FLAG_UPDATE_SERVICE_IPC_WRAPPER)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static @Nullable Object getSystemServiceWithNoContext(@NonNull String name) {
        final ServiceFetcher<?> fetcher = getSystemServiceFetcher(name);
        if (fetcher == null) {
            return null;
        }

        if (!fetcher.supportsFetchWithoutContext()) {
            throw new IllegalArgumentException(
                    "Manager cannot be fetched without a context: " + name);
        }

        return fetcher.getService(null);
    }

    /**
     * Gets the name of the system-level service that is represented by the specified class.
     * @hide
     */
    @RavenwoodKeep
    public static String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == null) {
            return null;
        }
        final String serviceName = SYSTEM_SERVICE_NAMES.get(serviceClass);
        if (serviceName == null) {
            // This should be a caller bug.
            onUnknownSystemServiceError(serviceClass.getCanonicalName());
        }
        return serviceName;
    }

    /**
     * Statically registers a system service with the context.
     * This method must be called during static initialization only.
     */
    @RavenwoodKeep
    private static <T> void registerService(@NonNull String serviceName,
            @NonNull Class<T> serviceClass, @NonNull ServiceFetcher<T> serviceFetcher) {
        SYSTEM_SERVICE_NAMES.put(serviceClass, serviceName);
        SYSTEM_SERVICE_FETCHERS.put(serviceName, serviceFetcher);
        SYSTEM_SERVICE_CLASS_NAMES.put(serviceName, serviceClass.getSimpleName());
    }

    /**
     * Provides a package-private access to {@link #registerService}. Must be only used on
     * Ravenwood.
     * (We don't use @RavenwoodReplace because a package-private $ravenwood method can
     * be used on the device side too anyway)
     */
    @RavenwoodKeep
    static <T> void registerServiceForRavenwood(@NonNull String serviceName,
            @NonNull Class<T> serviceClass, @NonNull ServiceFetcher<T> serviceFetcher) {
        if (!RavenwoodHelper.isRunningOnRavenwood()) {
            throw new IllegalStateException(
                    "registerServiceForRavenwood must not be called once static {} is done");
        }
        registerService(serviceName, serviceClass, serviceFetcher);
    }

    /**
     * Returns system service class name by system service name. This method is mostly an inverse of
     * {@link #getSystemServiceName(Class)}
     *
     * @return system service class name. {@code null} if service name is invalid.
     * @hide
     */
    @Nullable
    public static String getSystemServiceClassName(@NonNull String name) {
        return SYSTEM_SERVICE_CLASS_NAMES.get(name);
    }

    /**
     * Callback interface used as a parameter to {@link #registerStaticService(
     * String, Class, StaticServiceProducerWithoutBinder)}, which generates a service wrapper
     * instance that's not tied to any context and does not take a service binder object in the
     * constructor.
     *
     * @param <TServiceClass> type of the service wrapper class.
     *
     * @hide
     */
    @SystemApi
    public interface StaticServiceProducerWithoutBinder<TServiceClass> {
        /**
         * Return a new service wrapper of type {@code TServiceClass}.
         */
        @NonNull
        TServiceClass createService();
    }

    /**
     * Callback interface used as a parameter to {@link #registerStaticService(
     * String, Class, StaticServiceProducerWithBinder)}, which generates a service wrapper instance
     * that's not tied to any context and takes a service binder object in the constructor.
     *
     * @param <TServiceClass> type of the service wrapper class.
     *
     * @hide
     */
    @SystemApi
    public interface StaticServiceProducerWithBinder<TServiceClass> {
        /**
         * Return a new service wrapper of type {@code TServiceClass} backed by a given
         * service binder object.
         */
        @NonNull
        TServiceClass createService(@NonNull IBinder serviceBinder);
    }

    /**
     * Callback interface used as a parameter to {@link #registerContextAwareService(
     * String, Class, ContextAwareServiceProducerWithoutBinder)},
     * which generates a service wrapper instance
     * that's tied to a specific context and does not take a service binder object in the
     * constructor.
     *
     * @param <TServiceClass> type of the service wrapper class.
     *
     * @hide
     */
    @SystemApi
    public interface ContextAwareServiceProducerWithoutBinder<TServiceClass> {
        /**
         * Return a new service wrapper of type {@code TServiceClass} tied to a given
         * {@code context}.
         */
        @NonNull
        //TODO Do we need to pass the "base context" too?
        TServiceClass createService(@NonNull Context context);
    }

    /**
     * Callback interface used as a parameter to {@link #registerContextAwareService(
     * String, Class, ContextAwareServiceProducerWithBinder)},
     * which generates a service wrapper instance
     * that's tied to a specific context and takes a service binder object in the constructor.
     *
     * @param <TServiceClass> type of the service wrapper class.
     *
     * @hide
     */
    @SystemApi
    public interface ContextAwareServiceProducerWithBinder<TServiceClass> {
        /**
         * Return a new service wrapper of type {@code TServiceClass} backed by a given
         * service binder object that's tied to a given {@code context}.
         */
        @NonNull
        //TODO Do we need to pass the "base context" too?
        TServiceClass createService(@NonNull Context context, @NonNull IBinder serviceBinder);
    }

    /**
     * Used by apex modules to register a "service wrapper" that is not tied to any {@link Context}.
     *
     * <p>This can only be called from the methods called by the static initializer of
     * {@link SystemServiceRegistry}. (Otherwise it throws a {@link IllegalStateException}.)
     *
     * @param serviceName the name of the binder object, such as
     *     {@link Context#JOB_SCHEDULER_SERVICE}.
     * @param serviceWrapperClass the wrapper class, such as the class of
     *     {@link android.app.job.JobScheduler}.
     * @param serviceProducer Callback that takes the service binder object with the name
     *     {@code serviceName} and returns an actual service wrapper instance.
     *
     * @hide
     */
    @SystemApi
    public static <TServiceClass> void registerStaticService(
            @NonNull String serviceName, @NonNull Class<TServiceClass> serviceWrapperClass,
            @NonNull StaticServiceProducerWithBinder<TServiceClass> serviceProducer) {
        ensureInitializing("registerStaticService");
        Preconditions.checkStringNotEmpty(serviceName);
        Objects.requireNonNull(serviceWrapperClass);
        Objects.requireNonNull(serviceProducer);

        registerService(serviceName, serviceWrapperClass,
                new StaticServiceFetcher<TServiceClass>() {
                    @Override
                    public TServiceClass createService() throws ServiceNotFoundException {
                        return serviceProducer.createService(
                                ServiceManager.getServiceOrThrow(serviceName));
                    }});
    }

    /**
     * Used by apex modules to register a "service wrapper" that is not tied to any {@link Context}
     * and will never require a context in the future.
     *
     * Services registered in this way can be fetched via
     * {@link #getSystemServiceWithNoContext(String)}, so cannot require a context in future without
     * a breaking change.
     *
     * <p>This can only be called from the methods called by the static initializer of
     * {@link SystemServiceRegistry}. (Otherwise it throws a {@link IllegalStateException}.)
     *
     * @param serviceName the name of the binder object, such as
     *     {@link Context#JOB_SCHEDULER_SERVICE}.
     * @param serviceWrapperClass the wrapper class, such as the class of
     *     {@link android.app.job.JobScheduler}.
     * @param serviceProducer Callback that takes the service binder object with the name
     *     {@code serviceName} and returns an actual service wrapper instance.
     *
     * @hide
     */
    @FlaggedApi(android.webkit.Flags.FLAG_UPDATE_SERVICE_IPC_WRAPPER)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static <TServiceClass> void registerForeverStaticService(
            @NonNull String serviceName, @NonNull Class<TServiceClass> serviceWrapperClass,
            @NonNull StaticServiceProducerWithBinder<TServiceClass> serviceProducer) {
        ensureInitializing("registerStaticService");
        Preconditions.checkStringNotEmpty(serviceName);
        Objects.requireNonNull(serviceWrapperClass);
        Objects.requireNonNull(serviceProducer);

        registerService(serviceName, serviceWrapperClass,
                new StaticServiceFetcher<TServiceClass>() {
                    @Override
                    public TServiceClass createService() throws ServiceNotFoundException {
                        return serviceProducer.createService(
                                ServiceManager.getServiceOrThrow(serviceName));
                    }

                    @Override
                    public boolean supportsFetchWithoutContext() {
                        return true;
                    }});
    }

    /**
     * Similar to {@link #registerStaticService(String, Class, StaticServiceProducerWithBinder)},
     * but used for a "service wrapper" that doesn't take a service binder in its constructor.
     *
     * @hide
     */
    @SystemApi
    public static <TServiceClass> void registerStaticService(
            @NonNull String serviceName, @NonNull Class<TServiceClass> serviceWrapperClass,
            @NonNull StaticServiceProducerWithoutBinder<TServiceClass> serviceProducer) {
        ensureInitializing("registerStaticService");
        Preconditions.checkStringNotEmpty(serviceName);
        Objects.requireNonNull(serviceWrapperClass);
        Objects.requireNonNull(serviceProducer);

        registerService(serviceName, serviceWrapperClass,
                new StaticServiceFetcher<TServiceClass>() {
                    @Override
                    public TServiceClass createService() {
                        return serviceProducer.createService();
                    }});
    }

    /**
     * Used by apex modules to register a "service wrapper" that is tied to a specific
     * {@link Context}.
     *
     * <p>This can only be called from the methods called by the static initializer of
     * {@link SystemServiceRegistry}. (Otherwise it throws a {@link IllegalStateException}.)
     *
     * @param serviceName the name of the binder object, such as
     *     {@link Context#JOB_SCHEDULER_SERVICE}.
     * @param serviceWrapperClass the wrapper class, such as the class of
     *     {@link android.app.job.JobScheduler}.
     * @param serviceProducer lambda that takes the service binder object with the name
     *     {@code serviceName}, a {@link Context} and returns an actual service wrapper instance.
     *
     * @hide
     */
    @SystemApi
    public static <TServiceClass> void registerContextAwareService(
            @NonNull String serviceName, @NonNull Class<TServiceClass> serviceWrapperClass,
            @NonNull ContextAwareServiceProducerWithBinder<TServiceClass> serviceProducer) {
        ensureInitializing("registerContextAwareService");
        Preconditions.checkStringNotEmpty(serviceName);
        Objects.requireNonNull(serviceWrapperClass);
        Objects.requireNonNull(serviceProducer);

        registerService(serviceName, serviceWrapperClass,
                new CachedServiceFetcher<TServiceClass>() {
                    @Override
                    public TServiceClass createService(ContextImpl ctx)
                            throws ServiceNotFoundException {
                        return serviceProducer.createService(
                                ctx.getOuterContext(),
                                ServiceManager.getServiceOrThrow(serviceName));
                    }});
    }


    /**
     * Similar to {@link #registerContextAwareService(String, Class,
     * ContextAwareServiceProducerWithBinder)},
     * but used for a "service wrapper" that doesn't take a service binder in its constructor.
     *
     * @hide
     */
    @SystemApi
    public static <TServiceClass> void registerContextAwareService(
            @NonNull String serviceName, @NonNull Class<TServiceClass> serviceWrapperClass,
            @NonNull ContextAwareServiceProducerWithoutBinder<TServiceClass> serviceProducer) {
        ensureInitializing("registerContextAwareService");
        Preconditions.checkStringNotEmpty(serviceName);
        Objects.requireNonNull(serviceWrapperClass);
        Objects.requireNonNull(serviceProducer);

        registerService(serviceName, serviceWrapperClass,
                new CachedServiceFetcher<TServiceClass>() {
                    @Override
                    public TServiceClass createService(ContextImpl ctx) {
                        return serviceProducer.createService(ctx.getOuterContext());
                    }});
    }

    /**
     * Base interface for classes that fetch services.
     * These objects must only be created during static initialization.
     */
    @RavenwoodKeepWholeClass
    static abstract interface ServiceFetcher<T> {
        T getService(ContextImpl ctx);

        /**
         * Should this service fetcher support being fetched via {@link #getSystemService(String)},
         * without a Context?
         *
         * This means that the service cannot depend on a Context in future!
         *
         * @return true if this is supported for this service.
         */
        default boolean supportsFetchWithoutContext() {
            return false;
        }
    }

    /**
     * Override this class when the system service constructor needs a
     * ContextImpl and should be cached and retained by that context.
     */
    @RavenwoodKeepWholeClass
    static abstract class CachedServiceFetcher<T> implements ServiceFetcher<T> {
        private final int mCacheIndex;

        CachedServiceFetcher() {
            // Note this class must be instantiated only by the static initializer of the
            // outer class (SystemServiceRegistry), which already does the synchronization,
            // so bare access to sServiceCacheSize is okay here.
            mCacheIndex = sServiceCacheSize++;
        }

        @Override
        @SuppressWarnings("unchecked")
        public final T getService(ContextImpl ctx) {
            // These arrays should only be touched within synchronized (cache).
            final Object[] cache = ctx.mServiceCache;
            final byte[] gates = ctx.mServiceInitializationStateArray;
            boolean interrupted = false;

            T ret = null;

            // If the service is cached, we return it.
            // Otherwise, we create it with createService().
            //
            // We used to run the whole logic within a per-ContextImpl
            // lock and the logic was a lot simpler.
            //
            // However, unfortunately, some of the services are
            // very slow to create, so we need to release the lock
            // before calling createService(). We use a per-service,
            // per-contgext "gate" to ensure only one thread calls
            // into createService() for each service at a time
            // (for each context), while allowing other threads to
            // call createService() for other services
            // (or the same service on different contexts)
            //
            // See b/457769621 for more information about this code.
            for (;;) {
                boolean doInitialize = false;
                synchronized (cache) {
                    // Return it if we already have a cached instance.
                    T service = (T) cache[mCacheIndex];
                    if (service != null) {
                        ret = service;
                        break; // exit the for (;;)
                    }

                    // If we get here, there's no cached instance.

                    // Grr... if gate is STATE_READY, then this means we initialized the service
                    // once but someone cleared it.
                    // We start over from STATE_UNINITIALIZED.
                    // Similarly, if the previous attempt returned null, we'll retry again.
                    if (gates[mCacheIndex] == ContextImpl.STATE_READY
                            || gates[mCacheIndex] == ContextImpl.STATE_NOT_FOUND) {
                        gates[mCacheIndex] = ContextImpl.STATE_UNINITIALIZED;
                    }

                    // It's possible for multiple threads to get here at the same time, so
                    // use the "gate" to make sure only the first thread will call createService().

                    // At this point, the gate must be either UNINITIALIZED or INITIALIZING.
                    if (gates[mCacheIndex] == ContextImpl.STATE_UNINITIALIZED) {
                        doInitialize = true;
                        gates[mCacheIndex] = ContextImpl.STATE_INITIALIZING;
                    }
                }

                if (doInitialize) {
                    // Only the first thread gets here.

                    T service = null;
                    @ServiceInitializationState byte newState = ContextImpl.STATE_NOT_FOUND;
                    try {
                        // This thread is the first one to get here. Instantiate the service
                        // *without* the cache lock held.
                        service = createService(ctx);
                        newState = ContextImpl.STATE_READY;

                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);

                    } finally {
                        synchronized (cache) {
                            cache[mCacheIndex] = service;
                            gates[mCacheIndex] = newState;
                            cache.notifyAll();
                        }
                    }
                    ret = service;
                    break; // exit the for (;;)
                }
                // The other threads will wait for the first thread to call notifyAll(),
                // and go back to the top and retry.
                synchronized (cache) {
                    // Repeat until the state becomes STATE_READY or STATE_NOT_FOUND.
                    // We can't respond to interrupts here; just like we can't in the "doInitialize"
                    // path, so we remember the interrupt state here and re-interrupt later.
                    while (gates[mCacheIndex] < ContextImpl.STATE_READY) {
                        try {
                            // Clear the interrupt state.
                            interrupted |= Thread.interrupted();
                            cache.wait();
                        } catch (InterruptedException e) {
                            // This shouldn't normally happen, but if someone interrupts the
                            // thread, it will.
                            Slog.w(TAG, "getService() interrupted");
                            interrupted = true;
                        }
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return ret;
        }

        public abstract T createService(ContextImpl ctx) throws ServiceNotFoundException;

        // Services that explicitly use a Context can never be fetched without one.
        public final boolean supportsFetchWithoutContext() {
            return false;
        }
    }

    /**
     * Override this class when the system service does not need a ContextImpl
     * and should be cached and retained process-wide.
     */
    @RavenwoodKeepWholeClass
    static abstract class StaticServiceFetcher<T> implements ServiceFetcher<T> {
        /**
         * Indicates whether a service reference has been cached.
         * The cached reference can be {@code null} if the service is not available i.e.
         * {@link #isServiceEnabled(ContextImpl)} returns {@code false}.
         */
        @GuardedBy("StaticServiceFetcher.this")
        private boolean mIsCached = false;
        @GuardedBy("StaticServiceFetcher.this")
        private T mCachedInstance;

        @Override
        public final T getService(ContextImpl ctx) {
            synchronized (StaticServiceFetcher.this) {
                if (mIsCached) {
                    return mCachedInstance;
                }
            }
            // In case isServiceEnabled would require an IPC, run it outside a synchronized block.
            boolean isEnabled = isServiceEnabled(ctx);
            synchronized (StaticServiceFetcher.this) {
                if (!mIsCached) {
                    try {
                        if (isEnabled) {
                            mCachedInstance = createService();
                            // for a bug-to-bug compatibility, do not cache null-references
                            mIsCached = mCachedInstance != null;
                        } else {
                            mCachedInstance = null;
                            mIsCached = true;
                        }
                    } catch (ServiceNotFoundException e) {
                        onServiceNotFound(e);
                    }
                }
                return mCachedInstance;
            }
        }

        protected boolean isServiceEnabled(ContextImpl ctx) {
            return true;
        }

        public abstract T createService() throws ServiceNotFoundException;

        // Services that do not need a Context can potentially be fetched without one, but the
        // default is false, so that the service can require one in future without this being a
        // breaking change.
        public boolean supportsFetchWithoutContext() {
            return false;
        }
    }

    /** @hide */
    @RavenwoodKeepWholeClass
    public static void onServiceNotFound(ServiceNotFoundException e) {
        // We're mostly interested in tracking down long-lived core system
        // components that might stumble if they obtain bad references; just
        // emit a tidy log message for normal apps
        if (android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID) {
            Log.wtf(TAG, e.getMessage(), e);
        } else {
            Log.w(TAG, e.getMessage());
        }
    }
}
