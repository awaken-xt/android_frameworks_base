/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.app.admin.PolicyUpdateReason.REASON_CONFLICTING_ADMIN_POLICY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_SET_RESULT_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_TARGET_USER_ID;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_UPDATE_REASON_KEY;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_FAILURE;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_SUCCESS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.PolicyUpdatesReceiver;
import android.app.admin.TargetUser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Class responsible for setting, resolving, and enforcing policies set by multiple management
 * admins on the device.
 */
final class DevicePolicyEngine {
    static final String TAG = "DevicePolicyEngine";

    private final Context mContext;
    private final UserManager mUserManager;

    // TODO(b/256849338): add more granular locks
    private final Object mLock = new Object();

    /**
     * Map of <userId, Map<policyKey, policyState>>
     */
    private final SparseArray<Map<String, PolicyState<?>>> mLocalPolicies;

    /**
     * Map of <policyKey, policyState>
     */
    private final Map<String, PolicyState<?>> mGlobalPolicies;

    DevicePolicyEngine(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mUserManager = mContext.getSystemService(UserManager.class);
        mLocalPolicies = new SparseArray<>();
        mGlobalPolicies = new HashMap<>();
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value,
            int userId) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            boolean hasGlobalPolicies = hasGlobalPolicyLocked(policyDefinition);
            boolean policyChanged;
            if (hasGlobalPolicies) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.addPolicy(
                        enforcingAdmin,
                        value,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.addPolicy(enforcingAdmin, value);
            }

            if (policyChanged) {
                onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
            }

            boolean policyEnforced = Objects.equals(
                    localPolicyState.getCurrentResolvedPolicy(), value);
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId);

            write();
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return;
            }
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            boolean policyChanged;
            if (hasGlobalPolicyLocked(policyDefinition)) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.removePolicy(
                        enforcingAdmin,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.removePolicy(enforcingAdmin);
            }

            if (policyChanged) {
                onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
            }

            // For a removePolicy to be enforced, it means no current policy exists
            boolean policyEnforced = localPolicyState.getCurrentResolvedPolicy() == null;
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId);

            if (localPolicyState.getPoliciesSetByAdmins().isEmpty()) {
                removeLocalPolicyStateLocked(policyDefinition, userId);
            }

            write();
        }
    }

    /**
     * Enforces the new policy and notifies relevant admins.
     */
    private <V> void onLocalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {

        PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
        enforcePolicy(
                policyDefinition, localPolicyState.getCurrentResolvedPolicy(), userId);

        // Send policy updates to admins who've set it locally
        sendPolicyChangedToAdmins(
                localPolicyState.getPoliciesSetByAdmins().keySet(),
                enforcingAdmin,
                policyDefinition,
                // This policy change is only relevant to a single user, not the global
                // policy value,
                userId);

        // Send policy updates to admins who've set it globally
        if (hasGlobalPolicyLocked(policyDefinition)) {
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
            sendPolicyChangedToAdmins(
                    globalPolicyState.getPoliciesSetByAdmins().keySet(),
                    enforcingAdmin,
                    policyDefinition,
                    userId);
        }
    }
    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = globalPolicyState.addPolicy(enforcingAdmin, value);
            if (policyChanged) {
                onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
            }

            boolean policyEnforcedOnAllUsers = enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, value);
            boolean policyEnforcedGlobally = Objects.equals(
                    globalPolicyState.getCurrentResolvedPolicy(), value);

            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforcedGlobally && policyEnforcedOnAllUsers,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    UserHandle.USER_ALL);

            write();
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);
            boolean policyChanged = policyState.removePolicy(enforcingAdmin);

            if (policyChanged) {
                onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
            }

            boolean policyEnforcedOnAllUsers = enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, /* value= */ null);
            // For a removePolicy to be enforced, it means no current policy exists
            boolean policyEnforcedGlobally = policyState.getCurrentResolvedPolicy() == null;

            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforcedGlobally && policyEnforcedOnAllUsers,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    UserHandle.USER_ALL);

            if (policyState.getPoliciesSetByAdmins().isEmpty()) {
                removeGlobalPolicyStateLocked(policyDefinition);
            }

            write();
        }
    }

    /**
     * Enforces the new policy globally and notifies relevant admins.
     */
    private <V> void onGlobalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {
        PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);

        enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(),
                UserHandle.USER_ALL);

        sendPolicyChangedToAdmins(
                policyState.getPoliciesSetByAdmins().keySet(),
                enforcingAdmin,
                policyDefinition,
                UserHandle.USER_ALL);
    }

    /**
     * Tries to enforce the global policy locally on all users that have the same policy set
     * locally, this is only applicable to policies that can be set locally or globally
     * (e.g. setCameraDisabled, setScreenCaptureDisabled) rather than
     * policies that are global by nature (e.g. setting Wifi enabled/disabled).
     *
     * <p> A {@code null} policy value means the policy was removed
     *
     * <p>Returns {@code true} if the policy is enforced successfully on all users.
     */
    private <V> boolean enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @Nullable V value) {
        // Global only policies can't be applied locally, return early.
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return true;
        }
        boolean isAdminPolicyEnforced = true;
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            int userId = mLocalPolicies.keyAt(i);
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                continue;
            }

            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = localPolicyState.resolvePolicy(
                    globalPolicyState.getPoliciesSetByAdmins());
            if (policyChanged) {
                enforcePolicy(
                        policyDefinition, localPolicyState.getCurrentResolvedPolicy(), userId);
                sendPolicyChangedToAdmins(
                        localPolicyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        // Even though this is caused by a global policy change, admins who've set
                        // it locally should only care about the local user state.
                        userId);

            }
            isAdminPolicyEnforced &= Objects.equals(
                    value, localPolicyState.getCurrentResolvedPolicy());
        }
        return isAdminPolicyEnforced;
    }

    /**
     * Retrieves the resolved policy for the provided {@code policyDefinition} and {@code userId}.
     */
    @Nullable
    <V> V getResolvedPolicy(@NonNull PolicyDefinition<V> policyDefinition, int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            if (hasLocalPolicyLocked(policyDefinition, userId)) {
                return getLocalPolicyStateLocked(
                        policyDefinition, userId).getCurrentResolvedPolicy();
            }
            if (hasGlobalPolicyLocked(policyDefinition)) {
                return getGlobalPolicyStateLocked(policyDefinition).getCurrentResolvedPolicy();
            }
            return null;
        }
    }

    /**
     * Retrieves the policy set by the admin for the provided {@code policyDefinition} and
     * {@code userId} if one was set, otherwise returns {@code null}.
     */
    @Nullable
    <V> V getLocalPolicySetByAdmin(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return null;
            }
            return getLocalPolicyStateLocked(policyDefinition, userId)
                    .getPoliciesSetByAdmins().get(enforcingAdmin);
        }
    }

    private <V> boolean hasLocalPolicyLocked(PolicyDefinition<V> policyDefinition, int userId) {
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return false;
        }
        if (!mLocalPolicies.contains(userId)) {
            return false;
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mLocalPolicies.get(userId).get(policyDefinition.getPolicyKey())
                .getPoliciesSetByAdmins().isEmpty();
    }

    private <V> boolean hasGlobalPolicyLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            return false;
        }
        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mGlobalPolicies.get(policyDefinition.getPolicyKey()).getPoliciesSetByAdmins()
                .isEmpty();
    }

    @NonNull
    private <V> PolicyState<V> getLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {

        if (policyDefinition.isGlobalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a global only"
                    + "policy.");
        }

        if (!mLocalPolicies.contains(userId)) {
            mLocalPolicies.put(userId, new HashMap<>());
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            mLocalPolicies.get(userId).put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mLocalPolicies.get(userId), policyDefinition);
    }

    private <V> void removeLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {
        if (!mLocalPolicies.contains(userId)) {
            return;
        }
        mLocalPolicies.get(userId).remove(policyDefinition.getPolicyKey());
    }

    @NonNull
    private <V> PolicyState<V> getGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a local only"
                    + "policy.");
        }

        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            mGlobalPolicies.put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mGlobalPolicies, policyDefinition);
    }

    private <V> void removeGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        mGlobalPolicies.remove(policyDefinition.getPolicyKey());
    }

    private static <V> PolicyState<V> getPolicyState(
            Map<String, PolicyState<?>> policies, PolicyDefinition<V> policyDefinition) {
        try {
            // This will not throw an exception because policyDefinition is of type V, so unless
            // we've created two policies with the same key but different types - we can only have
            // stored a PolicyState of the right type.
            PolicyState<V> policyState = (PolicyState<V>) policies.get(
                    policyDefinition.getPolicyKey());
            return policyState;
        } catch (ClassCastException exception) {
            // TODO: handle exception properly
            throw new IllegalArgumentException();
        }
    }

    private <V> void enforcePolicy(
            PolicyDefinition<V> policyDefinition, @Nullable V policyValue, int userId) {
        // null policyValue means remove any enforced policies, ensure callbacks handle this
        // properly
        policyDefinition.enforcePolicy(policyValue, mContext, userId);
    }

    private <V> void sendPolicyResultToAdmin(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, boolean success,
            int reason, int userId) {
        Intent intent = new Intent(PolicyUpdatesReceiver.ACTION_DEVICE_POLICY_SET_RESULT);
        intent.setPackage(admin.getPackageName());

        List<ResolveInfo> receivers = mContext.getPackageManager().queryBroadcastReceiversAsUser(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                admin.getUserId());
        if (receivers.isEmpty()) {
            Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_SET_RESULT"
                    + "in package " + admin.getPackageName());
            return;
        }

        Bundle extras = new Bundle();
        extras.putString(EXTRA_POLICY_KEY, policyDefinition.getPolicyDefinitionKey());
        if (policyDefinition.getCallbackArgs() != null
                && !policyDefinition.getCallbackArgs().isEmpty()) {
            extras.putBundle(EXTRA_POLICY_BUNDLE_KEY, policyDefinition.getCallbackArgs());
        }
        extras.putInt(
                EXTRA_POLICY_TARGET_USER_ID,
                getTargetUser(admin.getUserId(), userId));
        extras.putInt(
                EXTRA_POLICY_SET_RESULT_KEY,
                success ? POLICY_SET_RESULT_SUCCESS : POLICY_SET_RESULT_FAILURE);

        if (!success) {
            extras.putInt(EXTRA_POLICY_UPDATE_REASON_KEY, reason);
        }
        intent.putExtras(extras);

        maybeSendIntentToAdminReceivers(intent, UserHandle.of(admin.getUserId()), receivers);
    }

    // TODO(b/261430877): Finalise the decision on which admins to send the updates to.
    private <V> void sendPolicyChangedToAdmins(
            Set<EnforcingAdmin> admins,
            EnforcingAdmin callingAdmin,
            PolicyDefinition<V> policyDefinition,
            int userId) {
        for (EnforcingAdmin admin: admins) {
            // We're sending a separate broadcast for the calling admin with the result.
            if (admin.equals(callingAdmin)) {
                continue;
            }
            maybeSendOnPolicyChanged(
                    admin, policyDefinition, REASON_CONFLICTING_ADMIN_POLICY, userId);
        }
    }

    private <V> void maybeSendOnPolicyChanged(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, int reason,
            int userId) {
        Intent intent = new Intent(PolicyUpdatesReceiver.ACTION_DEVICE_POLICY_CHANGED);
        intent.setPackage(admin.getPackageName());

        List<ResolveInfo> receivers = mContext.getPackageManager().queryBroadcastReceiversAsUser(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                admin.getUserId());
        if (receivers.isEmpty()) {
            Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_CHANGED"
                    + "in package " + admin.getPackageName());
            return;
        }

        Bundle extras = new Bundle();
        extras.putString(EXTRA_POLICY_KEY, policyDefinition.getPolicyDefinitionKey());
        if (policyDefinition.getCallbackArgs() != null
                && !policyDefinition.getCallbackArgs().isEmpty()) {
            extras.putBundle(EXTRA_POLICY_BUNDLE_KEY, policyDefinition.getCallbackArgs());
        }
        extras.putInt(
                EXTRA_POLICY_TARGET_USER_ID,
                getTargetUser(admin.getUserId(), userId));
        extras.putInt(EXTRA_POLICY_UPDATE_REASON_KEY, reason);
        intent.putExtras(extras);

        maybeSendIntentToAdminReceivers(
                intent, UserHandle.of(admin.getUserId()), receivers);
    }

    private void maybeSendIntentToAdminReceivers(
            Intent intent, UserHandle userHandle, List<ResolveInfo> receivers) {
        for (ResolveInfo resolveInfo : receivers) {
            if (!Manifest.permission.BIND_DEVICE_ADMIN.equals(
                    resolveInfo.activityInfo.permission)) {
                Log.w(TAG, "Receiver " + resolveInfo.activityInfo + " is not protected by"
                        + "BIND_DEVICE_ADMIN permission!");
                continue;
            }
            // TODO: If admins are always bound to, do I still need to set
            //  "BroadcastOptions.setBackgroundActivityStartsAllowed"?
            // TODO: maybe protect it with a permission that is granted to the role so that we
            //  don't accidentally send a broadcast to an admin that no longer holds the role.
            mContext.sendBroadcastAsUser(intent, userHandle);
        }
    }

    private int getTargetUser(int adminUserId, int targetUserId) {
        if (targetUserId == UserHandle.USER_ALL) {
            return TargetUser.GLOBAL_USER_ID;
        }
        if (adminUserId == targetUserId) {
            return TargetUser.LOCAL_USER_ID;
        }
        if (getProfileParentId(adminUserId) == targetUserId) {
            return TargetUser.PARENT_USER_ID;
        }
        return TargetUser.UNKNOWN_USER_ID;
    }

    private int getProfileParentId(int userId) {
        return Binder.withCleanCallingIdentity(() -> {
            UserInfo parentUser = mUserManager.getProfileParent(userId);
            return parentUser != null ? parentUser.id : userId;
        });
    }

    private void write() {
        Log.d(TAG, "Writing device policies to file.");
        new DevicePoliciesReaderWriter().writeToFileLocked();
    }

    // TODO(b/256852787): trigger resolving logic after loading policies as roles are recalculated
    //  and could result in a different enforced policy
    void load() {
        Log.d(TAG, "Reading device policies from file.");
        synchronized (mLock) {
            clear();
            new DevicePoliciesReaderWriter().readFromFileLocked();
        }
    }

    private void clear() {
        synchronized (mLock) {
            mGlobalPolicies.clear();
            mLocalPolicies.clear();
        }
    }

    private class DevicePoliciesReaderWriter {
        private static final String DEVICE_POLICIES_XML = "device_policies.xml";
        private static final String TAG_LOCAL_POLICY_ENTRY = "local-policy-entry";
        private static final String TAG_GLOBAL_POLICY_ENTRY = "global-policy-entry";
        private static final String TAG_ADMINS_POLICY_ENTRY = "admins-policy-entry";
        private static final String ATTR_USER_ID = "user-id";
        private static final String ATTR_POLICY_ID = "policy-id";

        private final File mFile;

        private DevicePoliciesReaderWriter() {
            mFile = new File(Environment.getDataSystemDirectory(), DEVICE_POLICIES_XML);
        }

        void writeToFileLocked() {
            Log.d(TAG, "Writing to " + mFile);

            AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                out.startDocument(null, true);

                // Actual content
                writeInner(out);

                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Log.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        // TODO(b/256846294): Add versioning to read/write
        void writeInner(TypedXmlSerializer serializer) throws IOException {
            writeLocalPoliciesInner(serializer);
            writeGlobalPoliciesInner(serializer);
        }

        private void writeLocalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mLocalPolicies != null) {
                for (int i = 0; i < mLocalPolicies.size(); i++) {
                    int userId = mLocalPolicies.keyAt(i);
                    for (Map.Entry<String, PolicyState<?>> policy : mLocalPolicies.get(
                            userId).entrySet()) {
                        serializer.startTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);

                        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, userId);
                        serializer.attribute(
                                /* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

                        serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                        policy.getValue().saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                        serializer.endTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);
                    }
                }
            }
        }

        private void writeGlobalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mGlobalPolicies != null) {
                for (Map.Entry<String, PolicyState<?>> policy : mGlobalPolicies.entrySet()) {
                    serializer.startTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);

                    serializer.attribute(/* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

                    serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                    policy.getValue().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                    serializer.endTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                Log.d(TAG, "" + mFile + " doesn't exist");
                return;
            }

            Log.d(TAG, "Reading from " + mFile);
            AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                readInner(parser);

            } catch (XmlPullParserException | IOException | ClassNotFoundException e) {
                Log.e(TAG, "Error parsing resources file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        private void readInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException, ClassNotFoundException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                switch (tag) {
                    case TAG_LOCAL_POLICY_ENTRY:
                        readLocalPoliciesInner(parser);
                        break;
                    case TAG_GLOBAL_POLICY_ENTRY:
                        readGlobalPoliciesInner(parser);
                        break;
                    default:
                        Log.e(TAG, "Unknown tag " + tag);
                }
            }
        }

        private void readLocalPoliciesInner(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);
            String policyKey = parser.getAttributeValue(
                    /* namespace= */ null, ATTR_POLICY_ID);
            if (!mLocalPolicies.contains(userId)) {
                mLocalPolicies.put(userId, new HashMap<>());
            }
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mLocalPolicies.get(userId).put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
        }

        private void readGlobalPoliciesInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            String policyKey = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_ID);
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mGlobalPolicies.put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
        }

        @Nullable
        private PolicyState<?> parseAdminsPolicy(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                if (tag.equals(TAG_ADMINS_POLICY_ENTRY)) {
                    return PolicyState.readFromXml(parser);
                }
                Log.e(TAG, "Unknown tag " + tag);
            }
            Log.e(TAG, "Error parsing file, AdminsPolicy not found");
            return null;
        }
    }
}
