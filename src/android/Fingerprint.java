package de.niklasmerz.cordova.biometric;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;


import androidx.biometric.BiometricManager;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicReference;

public class Fingerprint extends CordovaPlugin {

    private static final String TAG = "Fingerprint";
    private static final int REQUEST_CODE_BIOMETRIC = 1;

    private CallbackContext mCallbackContext = null;

    // Activity-based actions resolve later, in onActivityResult, so they need a callback that a
    // subsequent execute() cannot overwrite. A second prompt is rejected rather than superseding
    // the pending one, so a result can never reach a caller that did not ask for it. See #470.
    private final AtomicReference<CallbackContext> mBiometricActivityCallbackContext =
            new AtomicReference<>(null);

    private PromptInfo.Builder mPromptInfoBuilder;

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.v(TAG, "Init Fingerprint");
        mPromptInfoBuilder = new PromptInfo.Builder(
            this.getApplicationLabel(cordova.getActivity())
        );
    }

    public boolean execute(final String action, JSONArray args, CallbackContext callbackContext) {

        this.mCallbackContext = callbackContext;
        Log.v(TAG, "Fingerprint action: " + action);

        if ("authenticate".equals(action)) {
            executeAuthenticate(args);
            return true;

        } else if ("registerBiometricSecret".equals(action)) {
             executeRegisterBiometricSecret(args);
             return true;

         } else if ("loadBiometricSecret".equals(action)) {
             executeLoadBiometricSecret(args);
             return true;

         } else if ("isAvailable".equals(action)) {
            executeIsAvailable(args);
            return true;

        }
        return false;
    }

    private void executeIsAvailable(JSONArray args) {
        boolean requireStrongBiometrics = new Args(args).getBoolean("requireStrongBiometrics", false);
        PluginError error = canAuthenticate(requireStrongBiometrics);
        if (error != null) {
            sendError(this.mCallbackContext, error);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
            sendSuccess(this.mCallbackContext, "biometric");
        } else {
            sendSuccess(this.mCallbackContext, "finger");
        }
    }
    private void executeRegisterBiometricSecret(JSONArray args) {
        // should at least contains the secret
        if (args == null) {
            sendError(this.mCallbackContext, PluginError.BIOMETRIC_ARGS_PARSING_FAILED);
            return;
        }
        this.runBiometricActivity(args, BiometricActivityType.REGISTER_SECRET);
    }

    private void executeLoadBiometricSecret(JSONArray args) {
        this.runBiometricActivity(args, BiometricActivityType.LOAD_SECRET);
    }

    private void executeAuthenticate(JSONArray args) {
        this.runBiometricActivity(args, BiometricActivityType.JUST_AUTHENTICATE);
    }

    private boolean determineStrongBiometricsRequired(BiometricActivityType type) {
        return type == BiometricActivityType.REGISTER_SECRET || type == BiometricActivityType.LOAD_SECRET;
    }

    private void runBiometricActivity(JSONArray args, BiometricActivityType type) {
        boolean requireStrongBiometrics = determineStrongBiometricsRequired(type);
        PluginError error = canAuthenticate(requireStrongBiometrics);
        if (error != null) {
            sendError(this.mCallbackContext, error);
            return;
        }

        final CallbackContext callbackContext = this.mCallbackContext;
        if (!this.mBiometricActivityCallbackContext.compareAndSet(null, callbackContext)) {
            Log.w(TAG, "Rejecting " + type + ": a biometric prompt is already in progress.");
            sendError(callbackContext, PluginError.BIOMETRIC_ALREADY_IN_PROGRESS);
            return;
        }

        cordova.getActivity().runOnUiThread(() -> {
            mPromptInfoBuilder.parseArgs(args, type);
            Intent intent = new Intent(cordova.getActivity().getApplicationContext(), BiometricActivity.class);
            intent.putExtras(mPromptInfoBuilder.build().getBundle());
            this.cordova.startActivityForResult(this, intent, REQUEST_CODE_BIOMETRIC);
        });
        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
        pluginResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pluginResult);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != REQUEST_CODE_BIOMETRIC) {
            return;
        }

        CallbackContext callbackContext = this.mBiometricActivityCallbackContext.getAndSet(null);
        if (callbackContext == null) {
            Log.e(TAG, "Biometric activity returned but no callback context is pending. Result dropped.");
            return;
        }

        if (resultCode != Activity.RESULT_OK) {
            sendError(callbackContext, intent);
            return;
        }
        sendSuccess(callbackContext, intent);
    }

    // A reload destroys every outstanding JS callback, so release the slot or later prompts would
    // all be rejected. The abandoned callback is not settled: the JS side that owned it is gone.
    @Override
    public void onReset() {
        this.mBiometricActivityCallbackContext.set(null);
    }

    private void sendSuccess(CallbackContext callbackContext, Intent intent) {
        if (intent != null && intent.getExtras() != null) {
            sendSuccess(callbackContext, intent.getExtras().getString(PromptInfo.SECRET_EXTRA));
        } else {
            sendSuccess(callbackContext, "biometric_success");
        }
    }

    private void sendError(CallbackContext callbackContext, Intent intent) {
        if (intent != null) {
            Bundle extras = intent.getExtras();
            sendError(callbackContext, extras.getInt("code"), extras.getString("message"));
        } else {
            sendError(callbackContext, PluginError.BIOMETRIC_DISMISSED);
        }
    }

    private PluginError canAuthenticate(boolean requireStrongBiometrics) {
        int error = BiometricManager.from(cordova.getContext()).canAuthenticate(requireStrongBiometrics ? BiometricManager.Authenticators.BIOMETRIC_STRONG : BiometricManager.Authenticators.BIOMETRIC_WEAK);
        switch (error) {
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                return PluginError.BIOMETRIC_HARDWARE_NOT_SUPPORTED;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return PluginError.BIOMETRIC_NOT_ENROLLED;
            case BiometricManager.BIOMETRIC_SUCCESS:
            default:
                return null;
        }
    }

    private void sendError(CallbackContext callbackContext, int code, String message) {
        JSONObject resultJson = new JSONObject();
        try {
            resultJson.put("code", code);
            resultJson.put("message", message);

            PluginResult result = new PluginResult(PluginResult.Status.ERROR, resultJson);
            result.setKeepCallback(true);
            if (cordova.getActivity() != null) {
                if(callbackContext != null){
                    cordova.getActivity().runOnUiThread(() ->
                            callbackContext.sendPluginResult(result));
                }
                else{
                    Log.e(TAG, code + ":" + message);
                }
            } else {
                Log.e(TAG, "Cordova activity does not exist.");
            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private void sendError(CallbackContext callbackContext, PluginError error) {
        sendError(callbackContext, error.getValue(), error.getMessage());
    }

    private void sendSuccess(CallbackContext callbackContext, String message) {
        cordova.getActivity().runOnUiThread(() ->
                callbackContext.success(message));
    }

    private String getApplicationLabel(Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo app = packageManager
                    .getApplicationInfo(context.getPackageName(), 0);
            return packageManager.getApplicationLabel(app).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
