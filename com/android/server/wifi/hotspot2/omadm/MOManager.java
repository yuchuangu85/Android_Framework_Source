package com.android.server.wifi.hotspot2.omadm;

import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.anqp.eap.ExpandedEAPMethod;
import com.android.server.wifi.anqp.eap.InnerAuthEAP;
import com.android.server.wifi.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Handles provisioning of PerProviderSubscription data.
 */
public class MOManager {

    public static final String TAG_AAAServerTrustRoot = "AAAServerTrustRoot";
    public static final String TAG_AbleToShare = "AbleToShare";
    public static final String TAG_CertificateType = "CertificateType";
    public static final String TAG_CertSHA256Fingerprint = "CertSHA256Fingerprint";
    public static final String TAG_CertURL = "CertURL";
    public static final String TAG_CheckAAAServerCertStatus = "CheckAAAServerCertStatus";
    public static final String TAG_Country = "Country";
    public static final String TAG_CreationDate = "CreationDate";
    public static final String TAG_Credential = "Credential";
    public static final String TAG_CredentialPriority = "CredentialPriority";
    public static final String TAG_DataLimit = "DataLimit";
    public static final String TAG_DigitalCertificate = "DigitalCertificate";
    public static final String TAG_DLBandwidth = "DLBandwidth";
    public static final String TAG_EAPMethod = "EAPMethod";
    public static final String TAG_EAPType = "EAPType";
    public static final String TAG_ExpirationDate = "ExpirationDate";
    public static final String TAG_Extension = "Extension";
    public static final String TAG_FQDN = "FQDN";
    public static final String TAG_FQDN_Match = "FQDN_Match";
    public static final String TAG_FriendlyName = "FriendlyName";
    public static final String TAG_HESSID = "HESSID";
    public static final String TAG_HomeOI = "HomeOI";
    public static final String TAG_HomeOIList = "HomeOIList";
    public static final String TAG_HomeOIRequired = "HomeOIRequired";
    public static final String TAG_HomeSP = "HomeSP";
    public static final String TAG_IconURL = "IconURL";
    public static final String TAG_IMSI = "IMSI";
    public static final String TAG_InnerEAPType = "InnerEAPType";
    public static final String TAG_InnerMethod = "InnerMethod";
    public static final String TAG_InnerVendorID = "InnerVendorID";
    public static final String TAG_InnerVendorType = "InnerVendorType";
    public static final String TAG_IPProtocol = "IPProtocol";
    public static final String TAG_MachineManaged = "MachineManaged";
    public static final String TAG_MaximumBSSLoadValue = "MaximumBSSLoadValue";
    public static final String TAG_MinBackhaulThreshold = "MinBackhaulThreshold";
    public static final String TAG_NetworkID = "NetworkID";
    public static final String TAG_NetworkType = "NetworkType";
    public static final String TAG_Other = "Other";
    public static final String TAG_OtherHomePartners = "OtherHomePartners";
    public static final String TAG_Password = "Password";
    public static final String TAG_PerProviderSubscription = "PerProviderSubscription";
    public static final String TAG_Policy = "Policy";
    public static final String TAG_PolicyUpdate = "PolicyUpdate";
    public static final String TAG_PortNumber = "PortNumber";
    public static final String TAG_PreferredRoamingPartnerList = "PreferredRoamingPartnerList";
    public static final String TAG_Priority = "Priority";
    public static final String TAG_Realm = "Realm";
    public static final String TAG_RequiredProtoPortTuple = "RequiredProtoPortTuple";
    public static final String TAG_Restriction = "Restriction";
    public static final String TAG_RoamingConsortiumOI = "RoamingConsortiumOI";
    public static final String TAG_SIM = "SIM";
    public static final String TAG_SoftTokenApp = "SoftTokenApp";
    public static final String TAG_SPExclusionList = "SPExclusionList";
    public static final String TAG_SSID = "SSID";
    public static final String TAG_StartDate = "StartDate";
    public static final String TAG_SubscriptionParameters = "SubscriptionParameters";
    public static final String TAG_SubscriptionUpdate = "SubscriptionUpdate";
    public static final String TAG_TimeLimit = "TimeLimit";
    public static final String TAG_TrustRoot = "TrustRoot";
    public static final String TAG_TypeOfSubscription = "TypeOfSubscription";
    public static final String TAG_ULBandwidth = "ULBandwidth";
    public static final String TAG_UpdateIdentifier = "UpdateIdentifier";
    public static final String TAG_UpdateInterval = "UpdateInterval";
    public static final String TAG_UpdateMethod = "UpdateMethod";
    public static final String TAG_URI = "URI";
    public static final String TAG_UsageLimits = "UsageLimits";
    public static final String TAG_UsageTimePeriod = "UsageTimePeriod";
    public static final String TAG_Username = "Username";
    public static final String TAG_UsernamePassword = "UsernamePassword";
    public static final String TAG_VendorId = "VendorId";
    public static final String TAG_VendorType = "VendorType";

    private static final DateFormat DTFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    static {
        DTFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private final File mPpsFile;
    private final boolean mEnabled;
    private final Map<String, HomeSP> mSPs;

    public MOManager(File ppsFile, boolean hs2enabled) {
        mPpsFile = ppsFile;
        mEnabled = hs2enabled;
        mSPs = new HashMap<>();
    }

    public File getPpsFile() {
        return mPpsFile;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public boolean isConfigured() {
        return mEnabled && !mSPs.isEmpty();
    }

    public Map<String, HomeSP> getLoadedSPs() {
        return Collections.unmodifiableMap(mSPs);
    }

    public List<HomeSP> loadAllSPs() throws IOException {

        if (!mEnabled || !mPpsFile.exists()) {
            return Collections.emptyList();
        }

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(mPpsFile))) {
            MOTree moTree = MOTree.unmarshal(in);
            mSPs.clear();
            if (moTree == null) {
                return Collections.emptyList();     // Empty file
            }

            List<HomeSP> sps = buildSPs(moTree);
            if (sps != null) {
                for (HomeSP sp : sps) {
                    if (mSPs.put(sp.getFQDN(), sp) != null) {
                        throw new OMAException("Multiple SPs for FQDN '" + sp.getFQDN() + "'");
                    } else {
                        Log.d(Utils.hs2LogTag(getClass()), "retrieved " + sp.getFQDN() + " from PPS");
                    }
                }
                return sps;

            } else {
                throw new OMAException("Failed to build HomeSP");
            }
        }
    }

    public static HomeSP buildSP(String xml) throws IOException, SAXException {
        OMAParser omaParser = new OMAParser();
        MOTree tree = omaParser.parse(xml, OMAConstants.LOC_PPS + ":1.0");
        List<HomeSP> spList = buildSPs(tree);
        if (spList.size() != 1) {
            throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
        }
        return spList.iterator().next();
    }

    public HomeSP addSP(String xml) throws IOException, SAXException {
        OMAParser omaParser = new OMAParser();
        MOTree tree = omaParser.parse(xml, OMAConstants.LOC_PPS + ":1.0");
        List<HomeSP> spList = buildSPs(tree);
        if (spList.size() != 1) {
            throw new OMAException("Expected exactly one HomeSP, got " + spList.size());
        }
        HomeSP sp = spList.iterator().next();
        String fqdn = sp.getFQDN();
        if (mSPs.put(fqdn, sp) != null) {
            throw new OMAException("SP " + fqdn + " already exists");
        }

        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(mPpsFile, true));
            tree.marshal(out);
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    /**/
                }
            }
        }

        return sp;
    }

    public HomeSP getHomeSP(String fqdn) {
        return mSPs.get(fqdn);
    }

    public void addSP(HomeSP homeSP) throws IOException {
        if (!mEnabled) {
            throw new IOException("HS2.0 not enabled on this device");
        }
        if (mSPs.containsKey(homeSP.getFQDN())) {
            Log.d(Utils.hs2LogTag(getClass()), "HS20 profile for " +
                    homeSP.getFQDN() + " already exists");
            return;
        }
        Log.d(Utils.hs2LogTag(getClass()), "Adding new HS20 profile for " + homeSP.getFQDN());
        mSPs.put(homeSP.getFQDN(), homeSP);
        writeMO(mSPs.values(), mPpsFile);
    }

    public void removeSP(String fqdn) throws IOException {
        if (mSPs.remove(fqdn) == null) {
            Log.d(Utils.hs2LogTag(getClass()), "No HS20 profile to delete for " + fqdn);
            return;
        }
        Log.d(Utils.hs2LogTag(getClass()), "Deleting HS20 profile for " + fqdn);
        writeMO(mSPs.values(), mPpsFile);
    }

    public void updateAndSaveAllSps(Collection<HomeSP> homeSPs) throws IOException {

        boolean dirty = false;
        List<HomeSP> newSet = new ArrayList<>(homeSPs.size());

        Map<String, HomeSP> spClone = new HashMap<>(mSPs);
        for (HomeSP homeSP : homeSPs) {
            Log.d(Utils.hs2LogTag(getClass()), "Passed HomeSP: " + homeSP);
            HomeSP existing = spClone.remove(homeSP.getFQDN());
            if (existing == null) {
                dirty = true;
                newSet.add(homeSP);
                Log.d(Utils.hs2LogTag(getClass()), "New HomeSP");
            }
            else if (!homeSP.deepEquals(existing)) {
                dirty = true;
                newSet.add(homeSP.getClone(existing.getCredential().getPassword()));
                Log.d(Utils.hs2LogTag(getClass()), "Non-equal HomeSP: " + existing);
            }
            else {
                newSet.add(existing);
                Log.d(Utils.hs2LogTag(getClass()), "Keeping HomeSP: " + existing);
            }
        }

        Log.d(Utils.hs2LogTag(getClass()),
                String.format("Saving all SPs (%s): current %s (%d), new %s (%d)",
                dirty ? "dirty" : "clean",
                fqdnList(mSPs.values()), mSPs.size(),
                fqdnList(newSet), newSet.size()));

        if (!dirty && spClone.isEmpty()) {
            Log.d(Utils.hs2LogTag(getClass()), "Not persisting");
            return;
        }

        rewriteMO(newSet, mSPs, mPpsFile);
    }

    private static void rewriteMO(Collection<HomeSP> homeSPs, Map<String, HomeSP> current, File f)
            throws IOException {

        current.clear();

        OMAConstructed ppsNode = new OMAConstructed(null, TAG_PerProviderSubscription, null);
        int instance = 0;
        for (HomeSP homeSP : homeSPs) {
            buildHomeSPTree(homeSP, ppsNode, instance++);
            current.put(homeSP.getFQDN(), homeSP);
        }

        MOTree tree = new MOTree(OMAConstants.LOC_PPS + ":1.0", "1.2", ppsNode);
        try (BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(f, false))) {
            tree.marshal(out);
            out.flush();
        }
    }

    private static void writeMO(Collection<HomeSP> homeSPs, File f) throws IOException {

        OMAConstructed ppsNode = new OMAConstructed(null, TAG_PerProviderSubscription, null);
        int instance = 0;
        for (HomeSP homeSP : homeSPs) {
            buildHomeSPTree(homeSP, ppsNode, instance++);
        }

        MOTree tree = new MOTree(OMAConstants.LOC_PPS + ":1.0", "1.2", ppsNode);
        try (BufferedOutputStream out =
                     new BufferedOutputStream(new FileOutputStream(f, false))) {
            tree.marshal(out);
            out.flush();
        }
    }

    private static String fqdnList(Collection<HomeSP> sps) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (HomeSP sp : sps) {
            if (first) {
                first = false;
            }
            else {
                sb.append(", ");
            }
            sb.append(sp.getFQDN());
        }
        return sb.toString();
    }

    private static void buildHomeSPTree(HomeSP homeSP, OMAConstructed root, int spInstance)
            throws IOException {
        OMANode providerSubNode = root.addChild(getInstanceString(spInstance), null, null, null);

        // The HomeSP:
        OMANode homeSpNode = providerSubNode.addChild(TAG_HomeSP, null, null, null);
        if (!homeSP.getSSIDs().isEmpty()) {
            OMAConstructed nwkIDNode =
                    (OMAConstructed) homeSpNode.addChild(TAG_NetworkID, null, null, null);
            int instance = 0;
            for (Map.Entry<String, Long> entry : homeSP.getSSIDs().entrySet()) {
                OMAConstructed inode =
                        (OMAConstructed) nwkIDNode.addChild(getInstanceString(instance++), null, null, null);
                inode.addChild(TAG_SSID, null, entry.getKey(), null);
                if (entry.getValue() != null) {
                    inode.addChild(TAG_HESSID, null, String.format("%012x", entry.getValue()), null);
                }
            }
        }

        homeSpNode.addChild(TAG_FriendlyName, null, homeSP.getFriendlyName(), null);

        if (homeSP.getIconURL() != null) {
            homeSpNode.addChild(TAG_IconURL, null, homeSP.getIconURL(), null);
        }

        homeSpNode.addChild(TAG_FQDN, null, homeSP.getFQDN(), null);

        if (!homeSP.getMatchAllOIs().isEmpty() || !homeSP.getMatchAnyOIs().isEmpty()) {
            OMAConstructed homeOIList =
                    (OMAConstructed) homeSpNode.addChild(TAG_HomeOIList, null, null, null);

            int instance = 0;
            for (Long oi : homeSP.getMatchAllOIs()) {
                OMAConstructed inode =
                        (OMAConstructed) homeOIList.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_HomeOI, null, String.format("%x", oi), null);
                inode.addChild(TAG_HomeOIRequired, null, "TRUE", null);
            }
            for (Long oi : homeSP.getMatchAnyOIs()) {
                OMAConstructed inode =
                        (OMAConstructed) homeOIList.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_HomeOI, null, String.format("%x", oi), null);
                inode.addChild(TAG_HomeOIRequired, null, "FALSE", null);
            }
        }

        if (!homeSP.getOtherHomePartners().isEmpty()) {
            OMAConstructed otherPartners =
                    (OMAConstructed) homeSpNode.addChild(TAG_OtherHomePartners, null, null, null);
            int instance = 0;
            for (String fqdn : homeSP.getOtherHomePartners()) {
                OMAConstructed inode =
                        (OMAConstructed) otherPartners.addChild(getInstanceString(instance++),
                                null, null, null);
                inode.addChild(TAG_FQDN, null, fqdn, null);
            }
        }

        if (!homeSP.getRoamingConsortiums().isEmpty()) {
            homeSpNode.addChild(TAG_RoamingConsortiumOI, null, getRCList(homeSP.getRoamingConsortiums()), null);
        }

        // The Credential:
        OMANode credentialNode = providerSubNode.addChild(TAG_Credential, null, null, null);
        Credential cred = homeSP.getCredential();
        EAPMethod method = cred.getEAPMethod();

        if (cred.getCtime() > 0) {
            credentialNode.addChild(TAG_CreationDate,
                    null, DTFormat.format(new Date(cred.getCtime())), null);
        }
        if (cred.getExpTime() > 0) {
            credentialNode.addChild(TAG_ExpirationDate,
                    null, DTFormat.format(new Date(cred.getExpTime())), null);
        }

        if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_SIM
                || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKA
                || method.getEAPMethodID() == EAP.EAPMethodID.EAP_AKAPrim) {

            OMANode simNode = credentialNode.addChild(TAG_SIM, null, null, null);
            simNode.addChild(TAG_IMSI, null, cred.getImsi().toString(), null);
            simNode.addChild(TAG_EAPType, null,
                    Integer.toString(EAP.mapEAPMethod(method.getEAPMethodID())), null);

        } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TTLS) {

            OMANode unpNode = credentialNode.addChild(TAG_UsernamePassword, null, null, null);
            unpNode.addChild(TAG_Username, null, cred.getUserName(), null);
            unpNode.addChild(TAG_Password, null,
                    Base64.encodeToString(cred.getPassword().getBytes(StandardCharsets.UTF_8),
                            Base64.DEFAULT), null);
            OMANode eapNode = unpNode.addChild(TAG_EAPMethod, null, null, null);
            eapNode.addChild(TAG_EAPType, null,
                    Integer.toString(EAP.mapEAPMethod(method.getEAPMethodID())), null);
            eapNode.addChild(TAG_InnerMethod, null,
                    ((NonEAPInnerAuth) method.getAuthParam()).getOMAtype(), null);

        } else if (method.getEAPMethodID() == EAP.EAPMethodID.EAP_TLS) {

            OMANode certNode = credentialNode.addChild(TAG_DigitalCertificate, null, null, null);
            certNode.addChild(TAG_CertificateType, null, Credential.CertTypeX509, null);
            certNode.addChild(TAG_CertSHA256Fingerprint, null,
                    Utils.toHex(cred.getFingerPrint()), null);

        } else {
            throw new OMAException("Invalid credential on " + homeSP.getFQDN());
        }

        credentialNode.addChild(TAG_Realm, null, cred.getRealm(), null);

        // !!! Note: This node defines CRL checking through OSCP, I suspect we won't be able
        // to do that so it is commented out:
        //credentialNode.addChild(TAG_CheckAAAServerCertStatus, null, "TRUE", null);
    }

    private static String getInstanceString(int instance) {
        return "i" + instance;
    }

    private static String getRCList(Collection<Long> rcs) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Long roamingConsortium : rcs) {
            if (first) {
                first = false;
            }
            else {
                builder.append(',');
            }
            builder.append(String.format("%x", roamingConsortium));
        }
        return builder.toString();
    }

    private static List<HomeSP> buildSPs(MOTree moTree) throws OMAException {
        OMAConstructed spList;
        if (moTree.getRoot().getName().equals(TAG_PerProviderSubscription)) {
            // The PPS file is rooted at PPS instead of MgmtTree to conserve space
            spList = moTree.getRoot();
        }
        else {
            List<String> spPath = Arrays.asList(TAG_PerProviderSubscription);
            spList = moTree.getRoot().getListValue(spPath.iterator());
        }

        List<HomeSP> homeSPs = new ArrayList<>();

        if (spList == null) {
            return homeSPs;
        }
        for (OMANode spRoot : spList.getChildren()) {
            homeSPs.add(buildHomeSP(spRoot));
        }

        return homeSPs;
    }

    private static HomeSP buildHomeSP(OMANode ppsRoot) throws OMAException {
        OMANode spRoot = ppsRoot.getChild(TAG_HomeSP);

        String fqdn = spRoot.getScalarValue(Arrays.asList(TAG_FQDN).iterator());
        String friendlyName = spRoot.getScalarValue(Arrays.asList(TAG_FriendlyName).iterator());
        String iconURL = spRoot.getScalarValue(Arrays.asList(TAG_IconURL).iterator());

        HashSet<Long> roamingConsortiums = new HashSet<>();
        String oiString = spRoot.getScalarValue(Arrays.asList(TAG_RoamingConsortiumOI).iterator());
        if (oiString != null) {
            for (String oi : oiString.split(",")) {
                roamingConsortiums.add(Long.parseLong(oi.trim(), 16));
            }
        }

        Map<String, Long> ssids = new HashMap<>();

        OMANode ssidListNode = spRoot.getListValue(Arrays.asList(TAG_NetworkID).iterator());
        if (ssidListNode != null) {
            for (OMANode ssidRoot : ssidListNode.getChildren()) {
                OMANode hessidNode = ssidRoot.getChild(TAG_HESSID);
                ssids.put(ssidRoot.getChild(TAG_SSID).getValue(), getMac(hessidNode));
            }
        }

        Set<Long> matchAnyOIs = new HashSet<>();
        List<Long> matchAllOIs = new ArrayList<>();
        OMANode homeOIListNode = spRoot.getListValue(Arrays.asList(TAG_HomeOIList).iterator());
        if (homeOIListNode != null) {
            for (OMANode homeOIRoot : homeOIListNode.getChildren()) {
                String homeOI = homeOIRoot.getChild(TAG_HomeOI).getValue();
                if (Boolean.parseBoolean(homeOIRoot.getChild(TAG_HomeOIRequired).getValue())) {
                    matchAllOIs.add(Long.parseLong(homeOI, 16));
                } else {
                    matchAnyOIs.add(Long.parseLong(homeOI, 16));
                }
            }
        }

        Set<String> otherHomePartners = new HashSet<>();
        OMANode otherListNode =
                spRoot.getListValue(Arrays.asList(TAG_OtherHomePartners).iterator());
        if (otherListNode != null) {
            for (OMANode fqdnNode : otherListNode.getChildren()) {
                otherHomePartners.add(fqdnNode.getChild(TAG_FQDN).getValue());
            }
        }

        Credential credential = buildCredential(ppsRoot.getChild(TAG_Credential));

        return new HomeSP(ssids, fqdn, roamingConsortiums, otherHomePartners,
                matchAnyOIs, matchAllOIs, friendlyName, iconURL, credential);
    }

    private static Credential buildCredential(OMANode credNode) throws OMAException {
        long ctime = getTime(credNode.getChild(TAG_CreationDate));
        long expTime = getTime(credNode.getChild(TAG_ExpirationDate));
        String realm = getString(credNode.getChild(TAG_Realm));
        boolean checkAAACert = getBoolean(credNode.getChild(TAG_CheckAAAServerCertStatus));

        OMANode unNode = credNode.getChild(TAG_UsernamePassword);
        OMANode certNode = credNode.getChild(TAG_DigitalCertificate);
        OMANode simNode = credNode.getChild(TAG_SIM);

        int alternatives = 0;
        alternatives += unNode != null ? 1 : 0;
        alternatives += certNode != null ? 1 : 0;
        alternatives += simNode != null ? 1 : 0;
        if (alternatives != 1) {
            throw new OMAException("Expected exactly one credential type, got " + alternatives);
        }

        if (unNode != null) {
            String userName = getString(unNode.getChild(TAG_Username));
            String password = getString(unNode.getChild(TAG_Password));
            boolean machineManaged = getBoolean(unNode.getChild(TAG_MachineManaged));
            String softTokenApp = getString(unNode.getChild(TAG_SoftTokenApp));
            boolean ableToShare = getBoolean(unNode.getChild(TAG_AbleToShare));

            OMANode eapMethodNode = unNode.getChild(TAG_EAPMethod);
            int eapID = getInteger(eapMethodNode.getChild(TAG_EAPType));

            EAP.EAPMethodID eapMethodID = EAP.mapEAPMethod(eapID);
            if (eapMethodID == null) {
                throw new OMAException("Unknown EAP method: " + eapID);
            }

            Long vid = getOptionalInteger(eapMethodNode.getChild(TAG_VendorId));
            Long vtype = getOptionalInteger(eapMethodNode.getChild(TAG_VendorType));
            Long innerEAPType = getOptionalInteger(eapMethodNode.getChild(TAG_InnerEAPType));
            EAP.EAPMethodID innerEAPMethod = null;
            if (innerEAPType != null) {
                innerEAPMethod = EAP.mapEAPMethod(innerEAPType.intValue());
                if (innerEAPMethod == null) {
                    throw new OMAException("Bad inner EAP method: " + innerEAPType);
                }
            }

            Long innerVid = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorID));
            Long innerVtype = getOptionalInteger(eapMethodNode.getChild(TAG_InnerVendorType));
            String innerNonEAPMethod = getString(eapMethodNode.getChild(TAG_InnerMethod));

            EAPMethod eapMethod;
            if (innerEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new InnerAuthEAP(innerEAPMethod));
            } else if (vid != null) {
                eapMethod = new EAPMethod(eapMethodID,
                        new ExpandedEAPMethod(EAP.AuthInfoID.ExpandedEAPMethod,
                                vid.intValue(), vtype));
            } else if (innerVid != null) {
                eapMethod =
                        new EAPMethod(eapMethodID, new ExpandedEAPMethod(EAP.AuthInfoID
                                .ExpandedInnerEAPMethod, innerVid.intValue(), innerVtype));
            } else if (innerNonEAPMethod != null) {
                eapMethod = new EAPMethod(eapMethodID, new NonEAPInnerAuth(innerNonEAPMethod));
            } else {
                throw new OMAException("Incomplete set of EAP parameters");
            }

            return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, userName,
                    password, machineManaged, softTokenApp, ableToShare);
        }
        if (certNode != null) {
            try {
                String certTypeString = getString(certNode.getChild(TAG_CertificateType));
                byte[] fingerPrint = getOctets(certNode.getChild(TAG_CertSHA256Fingerprint));

                EAPMethod eapMethod = new EAPMethod(EAP.EAPMethodID.EAP_TLS, null);

                return new Credential(ctime, expTime, realm, checkAAACert, eapMethod,
                        Credential.mapCertType(certTypeString), fingerPrint);
            }
            catch (NumberFormatException nfe) {
                throw new OMAException("Bad hex string: " + nfe.toString());
            }
        }
        if (simNode != null) {
            try {
                IMSIParameter imsi = new IMSIParameter(getString(simNode.getChild(TAG_IMSI)));

                EAPMethod eapMethod =
                        new EAPMethod(EAP.mapEAPMethod(getInteger(simNode.getChild(TAG_EAPType))),
                                null);

                return new Credential(ctime, expTime, realm, checkAAACert, eapMethod, imsi);
            }
            catch (IOException ioe) {
                throw new OMAException("Failed to parse IMSI: " + ioe);
            }
        }
        throw new OMAException("Missing credential parameters");
    }

    private static boolean getBoolean(OMANode boolNode) {
        return boolNode != null && Boolean.parseBoolean(boolNode.getValue());
    }

    private static String getString(OMANode stringNode) {
        return stringNode != null ? stringNode.getValue() : null;
    }

    private static int getInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            throw new OMAException("Missing integer value");
        }
        try {
            return Integer.parseInt(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    private static Long getMac(OMANode macNode) throws OMAException {
        if (macNode == null) {
            return null;
        }
        try {
            return Long.parseLong(macNode.getValue(), 16);
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid MAC: " + macNode.getValue());
        }
    }

    private static Long getOptionalInteger(OMANode intNode) throws OMAException {
        if (intNode == null) {
            return null;
        }
        try {
            return Long.parseLong(intNode.getValue());
        } catch (NumberFormatException nfe) {
            throw new OMAException("Invalid integer: " + intNode.getValue());
        }
    }

    private static long getTime(OMANode timeNode) throws OMAException {
        if (timeNode == null) {
            return Utils.UNSET_TIME;
        }
        String timeText = timeNode.getValue();
        try {
            Date date = DTFormat.parse(timeText);
            return date.getTime();
        } catch (ParseException pe) {
            throw new OMAException("Badly formatted time: " + timeText);
        }
    }

    private static byte[] getOctets(OMANode octetNode) throws OMAException {
        if (octetNode == null) {
            throw new OMAException("Missing byte value");
        }
        return Utils.hexToBytes(octetNode.getValue());
    }
}
