/*
 * Copyright (C) 2026 Resurrection Remix
 *
 * Duck RKP follows Duck-ToolBox by Eltavine (MIT),
 * https://github.com/eltavine/Duck-ToolBox
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

package com.android.server.spoof;

import android.content.Context;
import android.hardware.security.keymint.DeviceInfo;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.hardware.security.keymint.MacedPublicKey;
import android.hardware.security.keymint.ProtectedData;
import android.hardware.security.keymint.RpcHardwareInfo;
import android.net.ConnectivityManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;

/**
 * In-ROM Duck device RKP. Generates a software P-256 key and MACs it with the
 * all-zero HBK used by Qualcomm's PureSoft provisioning context. KeyMint 3
 * firmware builds the CSR in
 * {@link IRemotelyProvisionedComponent#generateCertificateRequestV2}. A
 * {@code STATUS_FAILED} rejection, and every KeyMint 2 device, uses the legacy
 * issuer: AIDL {@code generateCertificateRequest}, then Qualcomm command
 * {@code 0x311e} through the {@code rr-rkp-v1} vendor helper. OneKeyMint
 * returns {@code STATUS_INVALID_MAC} for the zero-HBK test MAC. That tries
 * commands {@code 0x3125} and {@code 0x3122} through the vendor helper, each
 * on a fresh session, then the hardware V1 issuer. The helper sends
 * {@code 0x311e} and {@code 0x3124} through KeymasterUtils' shared buffer.
 * This rejection does not post a software CSR. The posted CSR splices the TA
 * DeviceInfo and ProtectedData bytes. A V2 CSR is posted without a
 * client-side DICE check: Conscrypt has no Ed25519 KeyFactory, and Google
 * validates the chain before it signs.
 */
public final class DuckRkpService {
    private static final String TAG = "DuckRkp";
    private static final String KEYBOX_SOURCE_DUCK = "duck_rkp";
    private static final String KEYBOX_SOURCE_USER = "user";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SERVER = "https://remoteprovisioning.googleapis.com/v1";
    private static final String EXPIRES_AT_KEY = "spoof_duck_rkp_expires_at";
    private static final String REVISION_KEY = "spoof_duck_rkp_revision";
    private static final String ERROR_KEY = "spoof_duck_rkp_error";
    private static final String DETAIL_KEY = "spoof_duck_rkp_detail";
    private static final String MADE_AT_KEY = "spoof_duck_rkp_made_at";
    private static final String LAST_RENEWAL_KEY = "spoof_duck_rkp_last_renewal";
    private static final long RENEW_AGE_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long RENEW_MARGIN_MS = 3 * 24 * 60 * 60 * 1000L;
    private static final long RENEW_RETRY_MS = 6 * 60 * 60 * 1000L;
    private static final byte[] ZERO_HBK = new byte[32];
    private static final byte[] MAC_INFO =
            "Key to MAC public keys".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DEVICE_KEY_SEED_INFO =
            "Device Key Seed".getBytes(StandardCharsets.UTF_8);
    private static final String NATIVE_SOCKET = "rr_rkp_v1";
    private static final int NATIVE_LIMIT = 0xa000;
    private static final int NATIVE_V2_OPCODE = 0x31220002;

    private static DuckRkpService sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    private boolean mBusy;
    private String mStage;
    private long mRetryAfterMs;

    public static synchronized DuckRkpService get(Context context) {
        if (sInstance == null) {
            sInstance = new DuckRkpService(context.getApplicationContext());
        }
        return sInstance;
    }

    static boolean isSupported() {
        return SystemProperties.getBoolean("ro.rr.duck_rkp", false);
    }

    private DuckRkpService(Context context) {
        mContext = context;
    }

    public Bundle getState() {
        enforceCaller("getDuckRkpState");
        if (!isSupported()) {
            return new Bundle();
        }
        final boolean available = isHalAvailable();
        final boolean online = hasInternet();
        final Bundle state = new Bundle();
        synchronized (mLock) {
            final boolean enabled = isEnabled();
            final boolean imported = isImported();
            final boolean hasKeybox = hasStoredKeybox();
            final long expiresAt = Settings.Secure.getLongForUser(
                    mContext.getContentResolver(), EXPIRES_AT_KEY, 0L, UserHandle.USER_SYSTEM);
            final boolean active = enabled && !imported && hasKeybox
                    && expiresAt > System.currentTimeMillis();
            state.putBoolean("available", available);
            state.putBoolean("enabled", enabled);
            state.putBoolean("imported", imported);
            state.putBoolean("has_keybox", hasKeybox);
            state.putBoolean("active", active);
            state.putBoolean("busy", mBusy);
            state.putString("stage", mStage);
            state.putLong("expires_at", expiresAt);
            state.putLong("revision", Settings.Secure.getLongForUser(
                    mContext.getContentResolver(), REVISION_KEY, 0L, UserHandle.USER_SYSTEM));
            final String error = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), ERROR_KEY, UserHandle.USER_SYSTEM);
            final int detail = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(), DETAIL_KEY, 0, UserHandle.USER_SYSTEM);
            final long madeAt = Settings.Secure.getLongForUser(
                    mContext.getContentResolver(), MADE_AT_KEY, 0L, UserHandle.USER_SYSTEM);
            final boolean retryable = isAutoRetryable(error, detail);
            state.putLong("retry_after_ms", mRetryAfterMs);
            state.putLong("renew_at", renewalTime(expiresAt, madeAt));
            state.putLong("next_automatic_at", 0L);
            state.putBoolean("automatic_paused",
                    error != null && !error.isEmpty() && !retryable);
            state.putBoolean("waiting_for_network", enabled && !imported && !mBusy
                    && !active && retryable && !online);
            state.putString("error", error);
            state.putInt("detail", detail);
        }
        return state;
    }

    public void setEnabled(boolean enabled) {
        enforceCaller("setDuckRkpEnabled");
        if (!isSupported()) {
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.SPOOF_DUCK_RKP_ENABLED, enabled ? 1 : 0,
                    UserHandle.USER_SYSTEM);
            if (enabled && !isImported()) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE,
                        KEYBOX_SOURCE_DUCK, UserHandle.USER_SYSTEM);
            }
            bumpRevision();
            if (enabled) {
                generateIfMissing();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void provision() {
        enforceCaller("provisionDuckRkp");
        if (!isSupported()) {
            throw new IllegalStateException("unsupported");
        }
        synchronized (mLock) {
            if (mBusy) {
                throw new IllegalStateException("Duck RKP is already running");
            }
            if (isImported()) {
                throw new IllegalStateException("imported");
            }
            if (!isEnabled()) {
                throw new IllegalStateException("disabled");
            }
            mBusy = true;
            mStage = "fetching";
            mRetryAfterMs = 0;
            persistError(null, 0);
        }
        startProvision(false);
    }

    /**
     * From enable and the hourly refresh: create a keybox when device RKP is on
     * and nothing is stored. A firmware rejection stays paused until Generate is
     * tapped. Transient failures wait out the same six-hour gap as a renewal.
     */
    void generateIfMissing() {
        if (!isSupported() || !isEnabled() || isImported() || hasStoredKeybox()) {
            return;
        }
        final String error = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                ERROR_KEY, UserHandle.USER_SYSTEM);
        final int detail = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                DETAIL_KEY, 0, UserHandle.USER_SYSTEM);
        if (!isAutoRetryable(error, detail)) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - secureLong(LAST_RENEWAL_KEY) < RENEW_RETRY_MS || !hasInternet()) {
            return;
        }
        synchronized (mLock) {
            if (mBusy) {
                return;
            }
            mBusy = true;
            mStage = "fetching";
            mRetryAfterMs = 0;
        }
        Settings.Secure.putLongForUser(mContext.getContentResolver(), LAST_RENEWAL_KEY, now,
                UserHandle.USER_SYSTEM);
        Slog.i(TAG, "Generating a keybox");
        startProvision(false);
    }

    /** From the hourly refresh: a new keybox about a week in, and always before this one ends. */
    void renewIfDue() {
        if (!isSupported() || !isEnabled() || isImported() || !hasStoredKeybox()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long madeAt = secureLong(MADE_AT_KEY);
        long dueAt = secureLong(EXPIRES_AT_KEY) - RENEW_MARGIN_MS;
        if (madeAt > 0) {
            dueAt = Math.min(dueAt, madeAt + RENEW_AGE_MS);
        }
        if (now < dueAt || now - secureLong(LAST_RENEWAL_KEY) < RENEW_RETRY_MS || !hasInternet()) {
            return;
        }
        synchronized (mLock) {
            if (mBusy) {
                return;
            }
            mBusy = true;
            mStage = "fetching";
            mRetryAfterMs = 0;
        }
        Settings.Secure.putLongForUser(mContext.getContentResolver(), LAST_RENEWAL_KEY, now,
                UserHandle.USER_SYSTEM);
        Slog.i(TAG, "Renewing the keybox");
        startProvision(true);
    }

    /** Same due time renewIfDue uses: a week after creation, and always three days before expiry. */
    private static long renewalTime(long expiresAt, long madeAt) {
        if (expiresAt <= 0) {
            return 0L;
        }
        long dueAt = expiresAt - RENEW_MARGIN_MS;
        if (madeAt > 0) {
            dueAt = Math.min(dueAt, madeAt + RENEW_AGE_MS);
        }
        return dueAt;
    }

    /**
     * Failures that are worth another automatic attempt. A CSR the firmware
     * rejected, or a chain that failed validation, stays paused.
     */
    private static boolean isAutoRetryable(String error, int detail) {
        if (error == null || error.isEmpty() || "canceled".equals(error)) {
            return true;
        }
        if ("network_error".equals(error) || "hal_unavailable".equals(error)
                || "native_unavailable".equals(error) || "native_transport".equals(error)
                || "timeout".equals(error) || "native_timeout".equals(error)) {
            return true;
        }
        return "http_error".equals(error)
                && (detail == 408 || detail == 429 || (detail >= 500 && detail <= 599));
    }

    private long secureLong(String key) {
        return Settings.Secure.getLongForUser(mContext.getContentResolver(), key, 0L,
                UserHandle.USER_SYSTEM);
    }

    private boolean hasInternet() {
        final ConnectivityManager connectivity =
                mContext.getSystemService(ConnectivityManager.class);
        final NetworkCapabilities capabilities = connectivity == null ? null
                : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** A failed renewal stays quiet while the keybox it would replace still works. */
    private void reportFailure(boolean renewal, String code, int detail) {
        if (renewal && secureLong(EXPIRES_AT_KEY) > System.currentTimeMillis()) {
            Slog.w(TAG, "Keybox renewal failed (" + code + "/" + detail + ")");
            return;
        }
        persistError(code, detail);
    }

    private void startProvision(boolean renewal) {
        new Thread(() -> {
            final long ident = Binder.clearCallingIdentity();
            try {
                runProvision();
            } catch (DuckRkpException e) {
                reportFailure(renewal, e.code, e.detail);
                if (!renewal && ("canceled".equals(e.code) || "timeout".equals(e.code))) {
                    synchronized (mLock) {
                        mRetryAfterMs = 15_000L;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Duck RKP provision failed", e);
                reportFailure(renewal, "network_error", 0);
            } finally {
                Binder.restoreCallingIdentity(ident);
                synchronized (mLock) {
                    mBusy = false;
                    mStage = null;
                }
            }
        }, "DuckRkp").start();
    }

    private void runProvision() throws Exception {
        final IRemotelyProvisionedComponent hal = getHal();
        if (hal == null) {
            throw new DuckRkpException("hal_unavailable", 0);
        }
        final RpcHardwareInfo info;
        try {
            info = hal.getHardwareInfo();
        } catch (Exception e) {
            throw new DuckRkpException("hal_unavailable", 0);
        }
        throwIfCanceled();
        setStage("fetching");
        final KeyPair keyPair = generateP256();
        final byte[] maced = forgeMacedPublicKey((ECPublicKey) keyPair.getPublic());
        final byte[] coseKey = coseKeyFromMaced(maced);
        final byte[] fingerprint = android.os.Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8);
        byte[] challenge = null;
        if (info.versionNumber >= 3) {
            challenge = fetchChallenge(fingerprint);
            try {
                runProvisionV2(hal, keyPair, maced, challenge);
                return;
            } catch (ServiceSpecificException e) {
                if (e.errorCode != IRemotelyProvisionedComponent.STATUS_FAILED) {
                    throw new DuckRkpException("hal_rejected", e.errorCode);
                }
                Slog.w(TAG, "KeyMint rejected the V2 CSR (" + e.errorCode + "); trying V1");
            } catch (DuckRkpException e) {
                // OneKeyMint verifies the MacedPublicKey against the device key.
                // Code 2 is that check failing, not a TEE format error. Try the
                // hardware V1 issuer and stop there. The zero-seed CSR is not
                // a fallback for this rejection.
                if (!"hal_rejected".equals(e.code)
                        || e.detail != IRemotelyProvisionedComponent.STATUS_INVALID_MAC) {
                    throw e;
                }
                Slog.w(TAG, "KeyMint rejected the V2 test MAC; trying native V2");
                try {
                    final byte[] csr = signNativeV2(coseKey, challenge);
                    Slog.i(TAG, "Native V2 returned a CSR");
                    acceptV2Csr(keyPair, csr, challenge);
                    return;
                } catch (DuckRkpException nativeFailure) {
                    Slog.w(TAG, "Native V2 failed (" + nativeFailure.code + "/"
                            + nativeFailure.detail + "); trying hardware V1");
                }
                runProvisionV1(hal, keyPair, maced, coseKey, fingerprint,
                        info.supportedEekCurve);
                return;
            }
        }
        try {
            runProvisionV1(hal, keyPair, maced, coseKey, fingerprint, info.supportedEekCurve);
            return;
        } catch (DuckRkpException e) {
            if (!"hal_rejected".equals(e.code) && !"native_rejected".equals(e.code)
                    && !"native_unavailable".equals(e.code)) {
                throw e;
            }
            Slog.w(TAG, "Hardware CSR unavailable (" + e.code + "/" + e.detail
                    + "); building a software CSR");
        }
        if (challenge == null) {
            challenge = fetchChallenge(fingerprint);
        }
        runProvisionSoftware(keyPair, maced, challenge);
    }

    /** Last resort when no TEE path works: a zero-seed framework BCC CSR. */
    private void runProvisionSoftware(KeyPair keyPair, byte[] maced, byte[] challenge)
            throws Exception {
        throwIfCanceled();
        setStage("signing");
        final byte[] seed = hkdfSha256(ZERO_HBK, DEVICE_KEY_SEED_INFO, 32);
        final byte[] csr = DuckRkpCsr.build(coseKeyFromMaced(maced), challenge,
                android.os.Build.FINGERPRINT, seed, verifiedDeviceInfo());
        acceptChain(keyPair, submitCsr(csr, challenge));
    }

    /** Canonical DeviceInfo map. Google rejects a CSR whose map is missing these fields. */
    private static byte[] verifiedDeviceInfo() throws Exception {
        final String brand = requiredProp("ro.product.brand");
        final String manufacturer = requiredProp("ro.product.manufacturer");
        final String product = requiredProp("ro.product.name");
        final String model = requiredProp("ro.product.model");
        final String device = requiredProp("ro.product.device");
        final String vbState = requiredProp("ro.boot.verifiedbootstate");
        if (!"green".equals(vbState) && !"yellow".equals(vbState) && !"orange".equals(vbState)) {
            throw new DuckRkpException("device_info", 0);
        }
        String bootloader = SystemProperties.get("ro.boot.vbmeta.device_state", "");
        if (!"locked".equals(bootloader) && !"unlocked".equals(bootloader)) {
            bootloader = "green".equals(vbState) ? "locked" : "unlocked";
        }
        final byte[] vbmeta = vbmetaDigest();
        return DuckRkpCsr.deviceInfo(brand, manufacturer, product, model, device, vbState,
                bootloader, vbmeta, osVersion(),
                patchLevel(requiredProp("ro.build.version.security_patch"), 6),
                patchLevel(requiredProp("ro.build.version.security_patch"), 6),
                patchLevel(requiredProp("ro.vendor.build.security_patch"), 8));
    }

    private static String requiredProp(String name) throws DuckRkpException {
        final String value = SystemProperties.get(name, "");
        if (value.isEmpty()) {
            Slog.e(TAG, "Missing device info property " + name);
            throw new DuckRkpException("device_info", 0);
        }
        return value;
    }

    /** KeyMint encodes a release as major * 10000 + minor * 100 + patch. */
    private static String osVersion() throws DuckRkpException {
        final String[] parts = requiredProp("ro.build.version.release").split("\\.");
        int encoded = 0;
        int scale = 10000;
        try {
            for (int i = 0; i < parts.length && i < 3; i++) {
                encoded += Integer.parseInt(parts[i]) * scale;
                scale /= 100;
            }
        } catch (NumberFormatException e) {
            throw new DuckRkpException("device_info", 0);
        }
        return Integer.toString(encoded);
    }

    /**
     * AVB digest for the device record. debug.rr.vbmeta_digest overrides it for
     * testing; otherwise the bootloader property or the staged vendor property.
     */
    private static byte[] vbmetaDigest() throws Exception {
        String digest = SystemProperties.get("debug.rr.vbmeta_digest", "");
        if (digest.length() != 64) {
            digest = SystemProperties.get("ro.boot.vbmeta.digest", "");
        }
        if (digest.length() != 64) {
            digest = SystemProperties.get("vendor.rr.vbmeta_digest", "");
        }
        if (digest.length() != 64) {
            Slog.e(TAG, "Verified boot digest is unavailable");
            throw new DuckRkpException("device_info", 0);
        }
        final byte[] bytes = hexTo32(digest);
        for (byte b : bytes) {
            if (b != 0) {
                return bytes;
            }
        }
        throw new DuckRkpException("device_info", 0);
    }

    private static int patchLevel(String date, int digits) throws DuckRkpException {
        final String compact = date.replace("-", "");
        if (compact.length() < digits || (digits != 6 && digits != 8)) {
            throw new DuckRkpException("device_info", 0);
        }
        try {
            return Integer.parseInt(compact.substring(0, digits));
        } catch (NumberFormatException e) {
            throw new DuckRkpException("device_info", 0);
        }
    }

    private static byte[] hexTo32(String hex) throws DuckRkpException {
        if (hex.length() != 64) {
            throw new DuckRkpException("device_info", 0);
        }
        final byte[] out = new byte[32];
        try {
            for (int i = 0; i < 32; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            throw new DuckRkpException("device_info", 0);
        }
        return out;
    }

    private void runProvisionV2(IRemotelyProvisionedComponent hal, KeyPair keyPair,
            byte[] maced, byte[] challenge) throws Exception {
        throwIfCanceled();
        setStage("hardware");
        final MacedPublicKey mpk = new MacedPublicKey();
        mpk.macedKey = maced;
        final byte[] csrBytes;
        try {
            csrBytes = hal.generateCertificateRequestV2(new MacedPublicKey[] { mpk }, challenge);
        } catch (ServiceSpecificException e) {
            // STATUS_FAILED is the HAL's report of TEE INCOMPATIBLE_KEY_FORMAT.
            // The caller retries with the legacy issuer. STATUS_INVALID_MAC is
            // rethrown as hal_rejected so the caller can try the native helper.
            if (e.errorCode == IRemotelyProvisionedComponent.STATUS_FAILED) {
                throw e;
            }
            throw new DuckRkpException("hal_rejected", e.errorCode);
        } catch (UnsupportedOperationException e) {
            throw new DuckRkpException("hal_unsupported", 0);
        }
        acceptV2Csr(keyPair, csrBytes, challenge);
    }

    /** Posts one AuthenticatedRequest array. The fingerprint map is appended. */
    private void acceptV2Csr(KeyPair keyPair, byte[] csrBytes, byte[] challenge) throws Exception {
        final byte[] csr;
        try {
            // Skip verifyV2: Android runtime lacks Ed25519 KeyFactory in JCA,
            // which causes NoSuchAlgorithmException on Qualcomm DICE chains.
            // Google RKP server cryptographically validates the DICE chain before signing.
            csr = DuckRkpNative.csrFromV2(csrBytes);
        } catch (IOException e) {
            Slog.w(TAG, "V2 CSR was not an AuthenticatedRequest", e);
            throw new DuckRkpException("hal_rejected", 0);
        }
        final DataItem decoded = decode(csr);
        if (!(decoded instanceof Array)) {
            throw new DuckRkpException("hal_rejected", 0);
        }
        final Map deviceInfo = new Map();
        deviceInfo.put(new UnicodeString("fingerprint"),
                new UnicodeString(android.os.Build.FINGERPRINT));
        ((Array) decoded).add(deviceInfo);

        throwIfCanceled();
        setStage("signing");
        acceptChain(keyPair, submitCsr(encode(decoded), challenge));
    }

    /**
     * Legacy CSR. AIDL V1 is tried first. A fresh challenge is fetched before
     * the vendor helper sends command 0x311e. Neither path posts a framework BCC.
     */
    private void runProvisionV1(IRemotelyProvisionedComponent hal, KeyPair keyPair, byte[] maced,
            byte[] coseKey, byte[] fingerprint, int curve) throws Exception {
        Slog.i(TAG, "Building a V1 CSR");
        setStage("hardware");
        DuckRkpException aidlFailure = null;
        try {
            final EekMaterial eek = fetchEek(fingerprint, curve);
            final V1Evidence evidence = signAidlV1(hal, maced, eek);
            Slog.i(TAG, "AIDL V1 returned a CSR");
            postV1(keyPair, coseKey, eek.challenge, evidence);
            return;
        } catch (ServiceSpecificException e) {
            Slog.w(TAG, "AIDL V1 rejected the CSR (" + e.errorCode + ")");
            aidlFailure = new DuckRkpException("hal_rejected", e.errorCode);
        } catch (DuckRkpException e) {
            if (!"hal_rejected".equals(e.code)) {
                throw e;
            }
            Slog.w(TAG, "AIDL V1 returned an unusable CSR");
            aidlFailure = e;
        } catch (Exception e) {
            Slog.w(TAG, "AIDL V1 failed", e);
            aidlFailure = new DuckRkpException("hal_rejected", 0);
        }

        Slog.w(TAG, "Asking the KeyMint TA for a V1 CSR");
        final EekMaterial eek;
        try {
            eek = fetchEek(fingerprint, curve);
        } catch (DuckRkpException e) {
            logAidlFailure(aidlFailure);
            throw e;
        }
        try {
            final V1Evidence evidence = signNativeV1(coseKey, eek);
            Slog.i(TAG, "Native V1 returned a CSR");
            postV1(keyPair, coseKey, eek.challenge, evidence);
        } catch (DuckRkpException e) {
            logAidlFailure(aidlFailure);
            throw e;
        }
    }

    private static void logAidlFailure(DuckRkpException failure) {
        if (failure != null) {
            Slog.w(TAG, "AIDL V1 had failed earlier: " + failure.code + " " + failure.detail);
        }
    }

    private static V1Evidence signAidlV1(IRemotelyProvisionedComponent hal, byte[] maced,
            EekMaterial eek) throws Exception {
        final MacedPublicKey key = new MacedPublicKey();
        key.macedKey = maced;
        final DeviceInfo deviceInfo = new DeviceInfo();
        final ProtectedData protectedData = new ProtectedData();
        final byte[] mac = hal.generateCertificateRequest(false, new MacedPublicKey[] { key },
                eek.chain, eek.challenge, deviceInfo, protectedData);
        if (mac == null || mac.length != 32 || deviceInfo.deviceInfo == null
                || deviceInfo.deviceInfo.length == 0 || protectedData.protectedData == null
                || protectedData.protectedData.length == 0) {
            throw new DuckRkpException("hal_rejected", 0);
        }
        return new V1Evidence(mac, deviceInfo.deviceInfo, protectedData.protectedData);
    }

    private static byte[] signNativeV2(byte[] coseKey, byte[] challenge) throws Exception {
        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(
                    NATIVE_SOCKET, LocalSocketAddress.Namespace.RESERVED));
            client.setSoTimeout(20_000);
            final DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.writeInt(NATIVE_V2_OPCODE);
            writeBlob(out, challenge);
            writeBlob(out, coseKey);
            out.flush();
            final DataInputStream in = new DataInputStream(client.getInputStream());
            final int status = in.readInt();
            if (status != 0) {
                final int detail = in.readInt();
                final String message = new String(readBlob(in), StandardCharsets.UTF_8);
                Slog.w(TAG, "Native V2 failed (" + status + "/" + detail + "): " + message);
                throw new DuckRkpException(status == 3 ? "native_unavailable" : "native_rejected",
                        detail);
            }
            final byte[] csr = readBlob(in);
            if (csr.length == 0) {
                throw new DuckRkpException("native_rejected", 0);
            }
            return csr;
        } catch (DuckRkpException e) {
            throw e;
        } catch (IOException e) {
            Slog.w(TAG, "Native V2 socket unavailable", e);
            throw new DuckRkpException("native_unavailable", 0);
        }
    }

    private static V1Evidence signNativeV1(byte[] coseKey, EekMaterial eek) throws Exception {
        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(
                    NATIVE_SOCKET, LocalSocketAddress.Namespace.RESERVED));
            client.setSoTimeout(20_000);
            final DataOutputStream out = new DataOutputStream(client.getOutputStream());
            writeBlob(out, eek.challenge);
            writeBlob(out, coseKey);
            writeBlob(out, eek.chain);
            out.flush();
            final DataInputStream in = new DataInputStream(client.getInputStream());
            final int status = in.readInt();
            if (status != 0) {
                final int detail = in.readInt();
                final String message = new String(readBlob(in), StandardCharsets.UTF_8);
                Slog.w(TAG, "Native V1 failed (" + status + "/" + detail + "): " + message);
                throw new DuckRkpException(status == 3 ? "native_unavailable" : "native_rejected",
                        detail);
            }
            final byte[] mac = readBlob(in);
            final byte[] deviceInfo = readBlob(in);
            final byte[] protectedData = readBlob(in);
            return new V1Evidence(mac, deviceInfo, protectedData);
        } catch (DuckRkpException e) {
            throw e;
        } catch (IOException e) {
            Slog.w(TAG, "Native V1 socket unavailable", e);
            throw new DuckRkpException("native_unavailable", 0);
        }
    }

    private void postV1(KeyPair keyPair, byte[] coseKey, byte[] challenge, V1Evidence evidence)
            throws Exception {
        throwIfCanceled();
        setStage("signing");
        acceptChain(keyPair, submitCsr(assembleV1(evidence, challenge, coseKey), challenge));
    }

    /** Four-element V1 CSR. DeviceInfo and ProtectedData are copied, not re-encoded. */
    private static byte[] assembleV1(V1Evidence evidence, byte[] challenge, byte[] coseKey)
            throws DuckRkpException {
        if (evidence.mac.length != 32 || evidence.deviceInfo.length < 2
                || evidence.deviceInfo.length > NATIVE_LIMIT
                || evidence.protectedData.length == 0
                || evidence.protectedData.length > NATIVE_LIMIT
                || (evidence.deviceInfo[0] & 0xe0) != 0xa0
                || (evidence.protectedData[0] & 0xe0) != 0x80) {
            Slog.e(TAG, "V1 evidence is not a spliced CSR");
            throw new DuckRkpException("hal_rejected", 0);
        }
        final byte[] keysArray = new byte[coseKey.length + 1];
        keysArray[0] = (byte) 0x81;
        System.arraycopy(coseKey, 0, keysArray, 1, coseKey.length);
        final ByteArrayOutputStream csr = new ByteArrayOutputStream();
        csr.write(0x84);
        csr.write(0x82);
        csr.write(evidence.deviceInfo, 0, evidence.deviceInfo.length);
        csr.write(0xa0);
        writeBstr(csr, challenge);
        csr.write(evidence.protectedData, 0, evidence.protectedData.length);
        csr.write(0x84);
        writeBstr(csr, new byte[] { (byte) 0xa1, 0x01, 0x05 });
        csr.write(0xa0);
        writeBstr(csr, keysArray);
        writeBstr(csr, evidence.mac);
        return csr.toByteArray();
    }

    private static void writeBlob(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException, DuckRkpException {
        final int length = in.readInt();
        if (length < 0 || length > NATIVE_LIMIT) {
            throw new DuckRkpException("native_rejected", 2);
        }
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private void acceptChain(KeyPair keyPair, byte[] chainDer) throws Exception {
        throwIfCanceled();
        setStage("validating");
        final List<byte[]> certs = splitDerCerts(chainDer);
        if (certs.isEmpty()) {
            throw new DuckRkpException("certificate_invalid", 0);
        }
        final X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certs.get(0)));
        if (!sameEcPoint((ECPublicKey) keyPair.getPublic(), leaf.getPublicKey())) {
            throw new DuckRkpException("certificate_invalid", 0);
        }

        throwIfCanceled();
        setStage("saving");
        persistKeybox(buildKeyboxXml(keyPair, certs), leaf.getNotAfter().getTime());
    }

    private void persistKeybox(String xml, long expiresAt) {
        final String encoded = Base64.encodeToString(
                xml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, encoded, UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE, KEYBOX_SOURCE_DUCK,
                UserHandle.USER_SYSTEM);
        Settings.Secure.putLongForUser(mContext.getContentResolver(),
                EXPIRES_AT_KEY, expiresAt, UserHandle.USER_SYSTEM);
        Settings.Secure.putLongForUser(mContext.getContentResolver(),
                MADE_AT_KEY, System.currentTimeMillis(), UserHandle.USER_SYSTEM);
        persistError(null, 0);
        bumpRevision();
    }

    private static KeyPair generateP256() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return kpg.generateKeyPair();
    }

    /**
     * COSE_Mac0 over a COSE_Key, tagged with HMAC-SHA256(HKDF(zeros, "Key to MAC public keys")).
     * Matches PureSoftRemoteProvisioningContext when HBK is 32 zero bytes.
     */
    private static byte[] forgeMacedPublicKey(ECPublicKey pub) throws Exception {
        final byte[] x = bigTo32(pub.getW().getAffineX());
        final byte[] y = bigTo32(pub.getW().getAffineY());
        final ByteArrayOutputStream coseKey = new ByteArrayOutputStream();
        coseKey.write(0xa5);
        coseKey.write(0x01); coseKey.write(0x02);
        coseKey.write(0x03); coseKey.write(0x26);
        coseKey.write(0x20); coseKey.write(0x01);
        coseKey.write(0x21); writeBstr(coseKey, x);
        coseKey.write(0x22); writeBstr(coseKey, y);

        final byte[] protectedHdr = new byte[] { (byte) 0xa1, 0x01, 0x05 };
        final byte[] payloadBstr = cborBstr(coseKey.toByteArray());
        final ByteArrayOutputStream toMac = new ByteArrayOutputStream();
        toMac.write(0x84);
        writeTstr(toMac, "MAC0");
        writeBstr(toMac, protectedHdr);
        writeBstr(toMac, new byte[0]);
        toMac.write(payloadBstr);

        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hkdfSha256(ZERO_HBK, MAC_INFO, 32), "HmacSHA256"));
        final byte[] tag = mac.doFinal(toMac.toByteArray());

        final ByteArrayOutputStream maced = new ByteArrayOutputStream();
        maced.write(0x84);
        writeBstr(maced, protectedHdr);
        maced.write(0xa0);
        maced.write(payloadBstr);
        writeBstr(maced, tag);
        return maced.toByteArray();
    }

    private static byte[] eekRequest(byte[] fingerprint) {
        final ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(0xa2);
        writeTstr(req, "fingerprint");
        writeTstr(req, new String(fingerprint, StandardCharsets.UTF_8));
        writeTstr(req, "id");
        writeUint(req, 42);
        return req.toByteArray();
    }

    private static byte[] fetchChallenge(byte[] fingerprint) throws Exception {
        final byte[] resp = httpPost(SERVER + "/:fetchEekChain", eekRequest(fingerprint));
        try {
            final DataItem top = decode(resp);
            if (!(top instanceof Array) || ((Array) top).getDataItems().size() < 2) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final byte[] challenge = ((ByteString) ((Array) top).getDataItems().get(1)).getBytes();
            if (challenge.length == 0 || challenge.length > 64) {
                throw new DuckRkpException("missing_eek", 0);
            }
            return challenge;
        } catch (DuckRkpException e) {
            throw e;
        } catch (Exception e) {
            throw new DuckRkpException("missing_eek", 0);
        }
    }

    /** Challenge plus the raw EEK certificate array for {@code curve}, or the first chain. */
    private static EekMaterial fetchEek(byte[] fingerprint, int curve) throws Exception {
        final byte[] resp = httpPost(SERVER + "/:fetchEekChain", eekRequest(fingerprint));
        try {
            final CborReader reader = new CborReader(resp);
            final long top = reader.beginArray();
            if (top < 2) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final long chains = reader.beginArray();
            byte[] matched = null;
            byte[] first = null;
            long firstCurve = -1;
            for (long i = 0; i < chains; i++) {
                final long entry = reader.beginArray();
                if (entry < 2) {
                    throw new DuckRkpException("missing_eek", 0);
                }
                final long entryCurve = reader.readUint();
                final byte[] certs = reader.sliceItem();
                for (long extra = 2; extra < entry; extra++) {
                    reader.skip();
                }
                if (first == null) {
                    first = certs;
                    firstCurve = entryCurve;
                }
                if (entryCurve == curve && matched == null) {
                    matched = certs;
                }
            }
            final byte[] challenge = reader.readBstr();
            for (long i = 2; i < top; i++) {
                reader.skip();
            }
            if (challenge.length == 0 || challenge.length > 64) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final byte[] chain = matched != null ? matched : first;
            if (chain == null || chain.length == 0 || (chain[0] & 0xe0) != 0x80) {
                throw new DuckRkpException("missing_eek", 0);
            }
            if (matched == null) {
                Slog.w(TAG, "EEK curve " + curve + " missing; using curve " + firstCurve);
            }
            if (reader.position() != resp.length) {
                Slog.w(TAG, "fetchEekChain response has "
                        + (resp.length - reader.position()) + " trailing bytes");
            }
            return new EekMaterial(challenge, chain);
        } catch (DuckRkpException e) {
            throw e;
        } catch (Exception e) {
            throw new DuckRkpException("missing_eek", 0);
        }
    }

    private static byte[] coseKeyFromMaced(byte[] maced) throws Exception {
        final DataItem decoded = decode(maced);
        if (!(decoded instanceof Array) || ((Array) decoded).getDataItems().size() < 3) {
            throw new DuckRkpException("hal_rejected", 0);
        }
        final DataItem payload = ((Array) decoded).getDataItems().get(2);
        if (!(payload instanceof ByteString)) {
            throw new DuckRkpException("hal_rejected", 0);
        }
        return ((ByteString) payload).getBytes();
    }

    private static byte[] submitCsr(byte[] csr, byte[] challenge) throws Exception {
        final String ch = Base64.encodeToString(challenge,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        final byte[] resp = httpPost(SERVER + "/:signCertificates?challenge=" + ch, csr);
        try {
            final DataItem top = decode(resp);
            final List<DataItem> outer = ((Array) top).getDataItems();
            final List<DataItem> inner = (outer.get(0) instanceof Array)
                    ? ((Array) outer.get(0)).getDataItems() : outer;
            final byte[] shared = ((ByteString) inner.get(0)).getBytes();
            final byte[] leaf = ((ByteString) ((Array) inner.get(1)).getDataItems().get(0))
                    .getBytes();
            final byte[] chain = new byte[leaf.length + shared.length];
            System.arraycopy(leaf, 0, chain, 0, leaf.length);
            System.arraycopy(shared, 0, chain, leaf.length, shared.length);
            return chain;
        } catch (Exception e) {
            throw new DuckRkpException("submission_unknown", 0);
        }
    }

    private static byte[] httpPost(String url, byte[] body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Content-Type", "application/cbor");
            conn.setRequestProperty("Accept", "application/cbor");
            conn.getOutputStream().write(body);
            final int code = conn.getResponseCode();
            final InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (code != 200) {
                Slog.w(TAG, "Provisioning server returned HTTP " + code + " for " + url + ": "
                        + new String(out.toByteArray(), StandardCharsets.UTF_8));
                throw new DuckRkpException("http_error", code);
            }
            return out.toByteArray();
        } catch (SocketTimeoutException e) {
            throw new DuckRkpException("timeout", 0);
        } catch (DuckRkpException e) {
            throw e;
        } catch (IOException e) {
            throw new DuckRkpException("network_error", 0);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String buildKeyboxXml(KeyPair kp, List<byte[]> certs) {
        final ECPrivateKey priv = (ECPrivateKey) kp.getPrivate();
        final ECPublicKey pub = (ECPublicKey) kp.getPublic();
        final byte[] sec1 = buildSec1Der(priv.getS(),
                bigTo32(pub.getW().getAffineX()), bigTo32(pub.getW().getAffineY()));
        final StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\n<AndroidAttestation>\n");
        sb.append("    <NumberOfKeyboxes>1</NumberOfKeyboxes>\n");
        sb.append("    <Keybox DeviceID=\"generic\">\n");
        sb.append("        <Key algorithm=\"ecdsa\">\n");
        sb.append("            <PrivateKey format=\"pem\">\n");
        pem(sb, "EC PRIVATE KEY", sec1);
        sb.append("            </PrivateKey>\n");
        sb.append("            <CertificateChain>\n");
        sb.append("                <NumberOfCertificates>").append(certs.size())
                .append("</NumberOfCertificates>\n");
        for (byte[] cert : certs) {
            sb.append("                <Certificate format=\"pem\">\n");
            pem(sb, "CERTIFICATE", cert);
            sb.append("                </Certificate>\n");
        }
        sb.append("            </CertificateChain>\n");
        sb.append("        </Key>\n");
        sb.append("    </Keybox>\n");
        sb.append("</AndroidAttestation>\n");
        return sb.toString();
    }

    private static void pem(StringBuilder sb, String type, byte[] der) {
        final String b64 = Base64.encodeToString(der, Base64.DEFAULT).trim();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        sb.append(b64);
        if (!b64.endsWith("\n")) sb.append('\n');
        sb.append("-----END ").append(type).append("-----\n");
    }

    private static byte[] buildSec1Der(BigInteger s, byte[] x, byte[] y) {
        final byte[] scalar = bigTo32(s);
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02); body.write(0x01); body.write(0x01);
        body.write(0x04); body.write(scalar.length);
        body.write(scalar, 0, scalar.length);
        final byte[] oid = new byte[] {
                0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07 };
        body.write((byte) 0xa0); body.write(oid.length);
        body.write(oid, 0, oid.length);
        final byte[] pub = new byte[65];
        pub[0] = 0x04;
        System.arraycopy(x, 0, pub, 1, 32);
        System.arraycopy(y, 0, pub, 33, 32);
        body.write((byte) 0xa1); body.write(pub.length + 3);
        body.write(0x03); body.write(pub.length + 1); body.write(0x00);
        body.write(pub, 0, pub.length);
        final byte[] raw = body.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        if (raw.length < 128) {
            out.write(raw.length);
        } else {
            out.write(0x81);
            out.write(raw.length);
        }
        out.write(raw, 0, raw.length);
        return out.toByteArray();
    }

    private static List<byte[]> splitDerCerts(byte[] chain) {
        final List<byte[]> certs = new ArrayList<>();
        int off = 0;
        while (off < chain.length && chain[off] == 0x30) {
            int len = chain[off + 1] & 0xff;
            int hdr = 2;
            if ((len & 0x80) != 0) {
                final int nb = len & 0x7f;
                len = 0;
                for (int i = 0; i < nb; i++) {
                    len = (len << 8) | (chain[off + 2 + i] & 0xff);
                }
                hdr = 2 + nb;
            }
            if (off + hdr + len > chain.length) break;
            final byte[] cert = Arrays.copyOfRange(chain, off, off + hdr + len);
            certs.add(cert);
            off += hdr + len;
        }
        return certs;
    }

    private static boolean sameEcPoint(ECPublicKey expected, PublicKey actual) {
        try {
            final ECPublicKey other;
            if (actual instanceof ECPublicKey) {
                other = (ECPublicKey) actual;
            } else {
                other = (ECPublicKey) KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(actual.getEncoded()));
            }
            return expected.getW().getAffineX().equals(other.getW().getAffineX())
                    && expected.getW().getAffineY().equals(other.getW().getAffineY());
        } catch (Exception e) {
            return false;
        }
    }

    private IRemotelyProvisionedComponent getHal() {
        try {
            final String name = IRemotelyProvisionedComponent.DESCRIPTOR + "/default";
            return IRemotelyProvisionedComponent.Stub.asInterface(
                    ServiceManager.waitForDeclaredService(name));
        } catch (Exception e) {
            Slog.w(TAG, "Duck V1 HAL unavailable", e);
            return null;
        }
    }

    private boolean isHalAvailable() {
        try {
            final String name = IRemotelyProvisionedComponent.DESCRIPTOR + "/default";
            return ServiceManager.isDeclared(name)
                    || ServiceManager.checkService(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void throwIfCanceled() throws DuckRkpException {
        if (!isEnabled() || isImported()) {
            throw new DuckRkpException("canceled", 0);
        }
    }

    private boolean isEnabled() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SPOOF_DUCK_RKP_ENABLED, 0, UserHandle.USER_SYSTEM) == 1;
    }

    /**
     * Only a keybox the user imported blocks device RKP. An official keybox was
     * fetched in the background and is replaced, not protected. Treating it as
     * imported made device RKP impossible to turn on.
     */
    private boolean isImported() {
        final String source = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX_SOURCE, UserHandle.USER_SYSTEM);
        return KEYBOX_SOURCE_USER.equals(source);
    }

    private boolean hasStoredKeybox() {
        final String raw = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX, UserHandle.USER_SYSTEM);
        return raw != null && !raw.trim().isEmpty();
    }

    private void persistError(String error, int detail) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                ERROR_KEY, error != null ? error : "", UserHandle.USER_SYSTEM);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                DETAIL_KEY, detail, UserHandle.USER_SYSTEM);
    }

    private void bumpRevision() {
        final long next = Settings.Secure.getLongForUser(mContext.getContentResolver(),
                REVISION_KEY, 0L, UserHandle.USER_SYSTEM) + 1;
        Settings.Secure.putLongForUser(mContext.getContentResolver(),
                REVISION_KEY, next, UserHandle.USER_SYSTEM);
    }

    private void setStage(String stage) {
        synchronized (mLock) {
            mStage = stage;
        }
    }

    private void enforceCaller(String func) {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID || uid == Process.myUid()) {
            return;
        }
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (SETTINGS_PACKAGE.equals(pkg)) {
                    return;
                }
            }
        }
        throw new SecurityException("Permission Denial: " + func
                + " is only available to Settings");
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] info, int len) throws Exception {
        final Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        final byte[] prk = extract.doFinal(ikm);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] prev = new byte[0];
        int counter = 1;
        while (out.size() < len) {
            final Mac expand = Mac.getInstance("HmacSHA256");
            expand.init(new SecretKeySpec(prk, "HmacSHA256"));
            expand.update(prev);
            expand.update(info);
            expand.update((byte) counter++);
            prev = expand.doFinal();
            out.write(prev, 0, Math.min(prev.length, len - out.size()));
        }
        return out.toByteArray();
    }

    private static byte[] bigTo32(BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }

    private static void writeUint(ByteArrayOutputStream out, int value) {
        if (value < 24) {
            out.write(value);
        } else if (value < 256) {
            out.write(0x18);
            out.write(value);
        } else {
            out.write(0x19);
            out.write(value >> 8);
            out.write(value & 0xff);
        }
    }

    private static void writeBstr(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length < 24) {
            out.write(0x40 | bytes.length);
        } else if (bytes.length < 256) {
            out.write(0x58);
            out.write(bytes.length);
        } else {
            out.write(0x59);
            out.write(bytes.length >> 8);
            out.write(bytes.length & 0xff);
        }
        out.write(bytes, 0, bytes.length);
    }

    private static void writeTstr(ByteArrayOutputStream out, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 24) {
            out.write(0x60 | bytes.length);
        } else if (bytes.length < 256) {
            out.write(0x78);
            out.write(bytes.length);
        } else {
            out.write(0x79);
            out.write(bytes.length >> 8);
            out.write(bytes.length & 0xff);
        }
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] cborBstr(byte[] bytes) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBstr(out, bytes);
        return out.toByteArray();
    }

    private static DataItem decode(byte[] data) throws Exception {
        return new CborDecoder(new ByteArrayInputStream(data)).decodeNext();
    }

    private static byte[] encode(DataItem item) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    private static final class EekMaterial {
        final byte[] challenge;
        final byte[] chain;

        EekMaterial(byte[] challenge, byte[] chain) {
            this.challenge = challenge;
            this.chain = chain;
        }
    }

    private static final class V1Evidence {
        final byte[] mac;
        final byte[] deviceInfo;
        final byte[] protectedData;

        V1Evidence(byte[] mac, byte[] deviceInfo, byte[] protectedData) {
            this.mac = mac;
            this.deviceInfo = deviceInfo;
            this.protectedData = protectedData;
        }
    }

    /** Offset-tracking CBOR reader. Slices keep the server's original bytes. */
    private static final class CborReader {
        private final byte[] mData;
        private int mPos;

        CborReader(byte[] data) {
            mData = data;
        }

        int position() {
            return mPos;
        }

        void skip() throws DuckRkpException {
            skipItem();
        }

        byte[] sliceItem() throws DuckRkpException {
            final int start = mPos;
            skipItem();
            return Arrays.copyOfRange(mData, start, mPos);
        }

        long beginArray() throws DuckRkpException {
            final int header = header();
            if ((header >> 5) != 4 || (header & 31) == 31) {
                throw new DuckRkpException("missing_eek", 0);
            }
            return argument(header & 31);
        }

        long readUint() throws DuckRkpException {
            final int header = header();
            if ((header >> 5) != 0 || (header & 31) == 31) {
                throw new DuckRkpException("missing_eek", 0);
            }
            return argument(header & 31);
        }

        byte[] readBstr() throws DuckRkpException {
            final int header = header();
            if ((header >> 5) != 2 || (header & 31) == 31) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final long length = argument(header & 31);
            if (length > mData.length - mPos) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final byte[] out = Arrays.copyOfRange(mData, mPos, mPos + (int) length);
            mPos += (int) length;
            return out;
        }

        private int header() throws DuckRkpException {
            if (mPos >= mData.length) {
                throw new DuckRkpException("missing_eek", 0);
            }
            return mData[mPos++] & 0xff;
        }

        private long argument(int additional) throws DuckRkpException {
            if (additional < 24) {
                return additional;
            }
            final int width = additional == 24 ? 1
                    : additional == 25 ? 2
                    : additional == 26 ? 4
                    : additional == 27 ? 8 : -1;
            if (width < 0 || mPos + width > mData.length) {
                throw new DuckRkpException("missing_eek", 0);
            }
            long value = 0;
            for (int i = 0; i < width; i++) {
                value = (value << 8) | (mData[mPos++] & 0xff);
            }
            return value;
        }

        private void skipItem() throws DuckRkpException {
            final int header = header();
            final int major = header >> 5;
            final int additional = header & 31;
            if (additional == 31) {
                throw new DuckRkpException("missing_eek", 0);
            }
            final long count = argument(additional);
            if (count < 0 || count > Integer.MAX_VALUE) {
                throw new DuckRkpException("missing_eek", 0);
            }
            switch (major) {
                case 0:
                case 1:
                case 7:
                    return;
                case 2:
                case 3:
                    if (count > mData.length - mPos) {
                        throw new DuckRkpException("missing_eek", 0);
                    }
                    mPos += (int) count;
                    return;
                case 4:
                    for (long i = 0; i < count; i++) {
                        skipItem();
                    }
                    return;
                case 5:
                    for (long i = 0; i < count; i++) {
                        skipItem();
                        skipItem();
                    }
                    return;
                case 6:
                    skipItem();
                    return;
                default:
                    throw new DuckRkpException("missing_eek", 0);
            }
        }
    }

    private static final class DuckRkpException extends Exception {
        final String code;
        final int detail;

        DuckRkpException(String code, int detail) {
            super(code);
            this.code = code;
            this.detail = detail;
        }
    }
}
