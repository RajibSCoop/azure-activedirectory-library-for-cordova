/*******************************************************************************
 * Copyright (c) Microsoft Open Technologies, Inc.
 * All Rights Reserved
 * Licensed under the Apache License, Version 2.0.
 * See License.txt in the project root for license information.
 ******************************************************************************/

package com.microsoft.aad.adal;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static com.microsoft.aad.adal.SimpleSerialization.tokenItemToJSON;

public class CordovaAdalPlugin extends CordovaPlugin {

    private static final PromptBehavior SHOW_PROMPT_ALWAYS = PromptBehavior.Always;

    private static final int GET_ACCOUNTS_PERMISSION_REQ_CODE = 0;
    private static final String PERMISSION_DENIED_ERROR =  "Permissions denied";
    private static final String SECRET_KEY =  "com.microsoft.aad.CordovaADAL";

    private final Hashtable<String, AuthenticationContext> contexts = new Hashtable<String, AuthenticationContext>();
    private AuthenticationContext currentContext;
    private CallbackContext callbackContext;
    private CallbackContext loggerCallbackContext;

    public CordovaAdalPlugin() {

        // Android API < 18 does not support AndroidKeyStore so ADAL requires
        // some extra work to crete and pass secret key to ADAL.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                SecretKey secretKey = this.createSecretKey(SECRET_KEY);
                AuthenticationSettings.INSTANCE.setSecretKey(secretKey.getEncoded());
            } catch (Exception e) {
                Log.w("CordovaAdalPlugin", "Unable to create secret key: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

        this.cordova.setActivityResultCallback(this);
        this.callbackContext = callbackContext;

        if (action.equals("createAsync")) {

            // Don't catch JSONException since it is already handled by Cordova
            String authority = args.getString(0);
            // Due to https://github.com/AzureAD/azure-activedirectory-library-for-android/blob/master/src/src/com/microsoft/aad/adal/AuthenticationContext.java#L158
            // AuthenticationContext constructor validates authority by default
            boolean validateAuthority = args.optBoolean(1, true);
            return createAsync(authority, validateAuthority);

        } else if (action.equals("acquireTokenAsync")) {

            final String authority = args.getString(0);
            final boolean validateAuthority = args.optBoolean(1, true);
            final String resourceUrl = args.getString(2);
            final String clientId = args.getString(3);
            final String redirectUrl = args.getString(4);
            final String userId = args.optString(5, null).equals("null") ? null : args.optString(5, null);
            final String extraQueryParams = args.optString(6, null).equals("null") ? null : args.optString(6, null);

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    acquireTokenAsync(
                            authority,
                            validateAuthority,
                            resourceUrl,
                            clientId,
                            redirectUrl,
                            userId,
                            extraQueryParams);
                }
            });

            return true;
        } else if (action.equals("acquireTokenSilentAsync")) {

            final String authority = args.getString(0);
            final boolean validateAuthority = args.optBoolean(1, true);
            final String resourceUrl = args.getString(2);
            final String clientId = args.getString(3);

            // This is a workaround for Cordova bridge issue. When null us passed from JS side
            // it is being translated to "null" string
            final String userId = args.getString(4).equals("null") ? null : args.getString(4);

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    acquireTokenSilentAsync(
                            authority,
                            validateAuthority,
                            resourceUrl, clientId, userId);
                }
            });

            return true;

        } else if (action.equals("tokenCacheClear")){

            String authority = args.getString(0);
            boolean validateAuthority = args.optBoolean(1, true);
            return clearTokenCache(authority, validateAuthority);

        } else if (action.equals("tokenCacheReadItems")){

            String authority = args.getString(0);
            boolean validateAuthority = args.optBoolean(1, true);
            return readTokenCacheItems(authority, validateAuthority);

        } else if (action.equals("tokenCacheDeleteItem")){

            String authority = args.getString(0);
            boolean validateAuthority = args.optBoolean(1, true);
            String itemAuthority = args.getString(2);
            String resource = args.getString(3);
            resource = resource.equals("null") ? null : resource;
            String clientId = args.getString(4);
            String userId = args.getString(5);
            boolean isMultipleResourceRefreshToken = args.getBoolean(6);

            return deleteTokenCacheItem(authority, validateAuthority, itemAuthority, resource, clientId, userId, isMultipleResourceRefreshToken);
        } else if (action.equals("setUseBroker")) {

            boolean useBroker = args.getBoolean(0);
            return setUseBroker(useBroker);
        } else if (action.equals("setLogger")) {
            this.loggerCallbackContext = callbackContext;
            return setLogger();
        } else if (action.equals("setLogLevel")) {
            Integer logLevel = args.getInt(0);
            return setLogLevel(logLevel);
        }

        return false;
    }

    private boolean createAsync(String authority, boolean validateAuthority) {

        try {
            getOrCreateContext(authority, validateAuthority);
        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        callbackContext.success();
        return true;
    }

    private void acquireTokenAsync(String authority, boolean validateAuthority, String resourceUrl, String clientId, String redirectUrl, String userId, String extraQueryParams) {

        final AuthenticationContext authContext;
        try{
            authContext = getOrCreateContext(authority, validateAuthority);
        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return;
        }

        if (userId != null) {
            ITokenCacheStore cache = authContext.getCache();
            if (cache instanceof ITokenStoreQuery) {

                List<TokenCacheItem> tokensForUserId = ((ITokenStoreQuery)cache).getTokensForUser(userId);
                if (tokensForUserId.size() > 0) {
                    // Try to acquire alias for specified userId
                    userId = tokensForUserId.get(0).getUserInfo().getDisplayableId();
                }
            }
        }

        authContext.acquireToken(
                this.cordova.getActivity(),
                resourceUrl,
                clientId,
                redirectUrl,
                userId,
                SHOW_PROMPT_ALWAYS,
                extraQueryParams,
                new DefaultAuthenticationCallback(callbackContext));
    }

    private void acquireTokenSilentAsync(String authority, boolean validateAuthority, String resourceUrl, String clientId, String userId) {

        final AuthenticationContext authContext;
        try{
            authContext = getOrCreateContext(authority, validateAuthority);

            //  We should retrieve userId from broker cache since local is always empty
            boolean useBroker = AuthenticationSettings.INSTANCE.getUseBroker();
            if (useBroker) {
                if (TextUtils.isEmpty(userId)) {
                    // Get first user from account list
                    userId = authContext.getBrokerUser();
                }

                for (UserInfo info: authContext.getBrokerUsers()) {
                    if (info.getDisplayableId().equals(userId)) {
                        userId = info.getUserId();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return;
        }

        authContext.acquireTokenSilentAsync(resourceUrl, clientId, userId, new DefaultAuthenticationCallback(callbackContext));
    }

    private boolean readTokenCacheItems(String authority, boolean validateAuthority) throws JSONException {

        final AuthenticationContext authContext;
        try{
            authContext = getOrCreateContext(authority, validateAuthority);
        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        JSONArray result = new JSONArray();
        ITokenCacheStore cache = authContext.getCache();

        if (cache instanceof ITokenStoreQuery) {
            Iterator<TokenCacheItem> cacheItems = ((ITokenStoreQuery)cache).getAll();

            while (cacheItems.hasNext()){
                TokenCacheItem item = cacheItems.next();
                result.put(tokenItemToJSON(item));
            }
        }

        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));

        return true;
    }

    private boolean deleteTokenCacheItem(String authority, boolean validateAuthority, String itemAuthority,  String resource,
                                         String clientId, String userId, boolean isMultipleResourceRefreshToken) {

        final AuthenticationContext authContext;
        try{
            authContext = getOrCreateContext(authority, validateAuthority);
        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        String key = CacheKey.createCacheKey(itemAuthority, resource, clientId, isMultipleResourceRefreshToken, userId, null);
        authContext.getCache().removeItem(key);

        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
        return true;
    }

    private boolean clearTokenCache(String authority, boolean validateAuthority) {
        final AuthenticationContext authContext;
        try{
            authContext = getOrCreateContext(authority, validateAuthority);
        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        authContext.getCache().removeAll();
        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
        return true;
    }

    private boolean setUseBroker(boolean useBroker) {

        try {
            AuthenticationSettings.INSTANCE.setUseBroker(useBroker);

        } catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        callbackContext.success();
        return true;
    }

    private boolean setLogLevel(Integer logLevel) {
        try {
            Logger.LogLevel level = Logger.LogLevel.values()[logLevel];
            Logger.getInstance().setLogLevel(level);
        }
        catch (Exception e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.getMessage()));
            return true;
        }

        callbackContext.success();
        return true;
    }

    private boolean setLogger() {
        Logger.getInstance().setExternalLogger(new Logger.ILogger() {
            @Override
            public void Log(String tag, String message, String additionalMessage, Logger.LogLevel level, ADALError errorCode) {

                JSONObject logItem = new JSONObject();
                try {
                    logItem.put("tag", tag);
                    logItem.put("additionalMessage", additionalMessage);
                    logItem.put("message", message);
                    logItem.put("level", level.ordinal());
                    logItem.put("errorCode", errorCode.ordinal());
                }

                catch(Exception ex) {
                    ex.printStackTrace();
                }

                PluginResult logResult = new PluginResult(PluginResult.Status.OK, logItem);
                logResult.setKeepCallback(true);
                loggerCallbackContext.sendPluginResult(logResult);
            }
        });

        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (currentContext != null) {
            currentContext.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return;
            }
        }
        callbackContext.success();
    }

    private AuthenticationContext getOrCreateContext (String authority, boolean validateAuthority) throws NoSuchPaddingException, NoSuchAlgorithmException {

        AuthenticationContext result;
        if (!contexts.containsKey(authority)) {
            result = new AuthenticationContext(this.cordova.getActivity(), authority, validateAuthority);
            this.contexts.put(authority, result);
        } else {
            result = contexts.get(authority);
        }

        currentContext = result;
        return result;
    }

    private SecretKey createSecretKey(String key) throws NoSuchAlgorithmException, UnsupportedEncodingException, InvalidKeySpecException {
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBEWithSHA256And256BitAES-CBC-BC");
        SecretKey tempkey = keyFactory.generateSecret(new PBEKeySpec(key.toCharArray(), "abcdedfdfd".getBytes("UTF-8"), 100, 256));
        SecretKey secretKey = new SecretKeySpec(tempkey.getEncoded(), "AES");
        return secretKey;
    }
}
