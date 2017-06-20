package com.jaus.albertogiunta.justintrain_oraritreni.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.IntDef;

import com.android.vending.billing.IInAppBillingService;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Locale;

import trikita.log.Log;

/**
 * BillingManager.java
 *
 * A simple, short, and understandable wrapper class around Google Play’s In-App Billing API v3.
 *
 * This is not a library, not in the sense that you can import and use. This is intentional.
 *
 * The authors’ experience with IAB libraries has been that:
 * (1) they do not provide enough value, since the Google API is not that difficult to work with;
 * (2) when something goes wrong, it’s notoriously hard to debug;
 * (3) many of the popular ones do not handle all the different kinds of error conditions properly,
 * and in the process, make it harder for a caller to handle because they silently drop them.
 *
 * To use this class, copy/paste into your own app’s code. There is a lot of logging; use a flag
 * to turn off most of it in production.
 *
 * This class has been tested and used in a production application for over a year. It relies on,
 * and requires, an Event Bus, to broadcast to the rest of the app when the user purchases the
 * Premium version. It is optimized for a single in-app purchase (“Premium”) and does not handle
 * multiple in-app purchases, e.g. games where you have to buy something every few days to keep
 * going.
 *
 * It supports a limited-time Trial Mode check as well: simply implement a class named {@code Trial}
 * with a static method named {@code isInTrialPeriod(Context)}.
 *
 *
 * Copyright 2016 onwards, Chimbori.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
public class BillingManager {
    private static final String TAG = "BillingManager";

    private static final int BILLING_API_VERSION = 3;

    private static final String PREMIUM_VERSION_PRODUCT_ID = "premium_upgrade_mp";

    private static final int REQUEST_CODE_BILLING_PURCHASE = 1001;  // Pick whatever.

    private final Context context;

    @IntDef({
            FREEMIUM_STATUS_UNKNOWN,
            FREEMIUM_STATUS_ERROR,
            FREEMIUM_STATUS_TRIAL,
            FREEMIUM_STATUS_FREE,
            FREEMIUM_STATUS_PREMIUM,
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface FreemiumStatus {
    }

    /** App has not yet checked the in-app purchase status. */
    public static final  int FREEMIUM_STATUS_UNKNOWN = 0;
    /** Error encountered checking in-app purchase status; includes cases where IAB is unavailable. */
    private static final int FREEMIUM_STATUS_ERROR   = 1;
    /** User is in Trial period. */
    public static final  int FREEMIUM_STATUS_TRIAL   = 2;
    /** User is beyond trial period, and using only Free mode features. */
    public static final  int FREEMIUM_STATUS_FREE    = 3;
    /** User has purchased Premium, and all features should be enabled unconditionally. */
    public static final  int FREEMIUM_STATUS_PREMIUM = 4;

    /**
     * Error codes generated by the In-App Billing API, using the same exact names.
     * https://developer.android.com/google/play/billing/billing_reference.html
     */
    private static class BillingError {
        static final int BILLING_RESPONSE_RESULT_OK                  = 0; // Success
        static final int BILLING_RESPONSE_RESULT_USER_CANCELED       = 1; // User pressed back or canceled a dialog
        static final int BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE = 2; // Network connection is down
        static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3; // Billing API version is not supported for the type requested
        static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE    = 4; // Requested product is not available for purchase
        static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR     = 5; // Invalid arguments provided to the API.
        // This error can also indicate that the application was not correctly signed or properly
        // set up for In-app Billing in Google Play, or does not have the necessary permissions in its manifest
        static final int BILLING_RESPONSE_RESULT_ERROR               = 6; // Fatal error during the API action
        static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED  = 7; // Failure to purchase since item is already owned
        static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED      = 8; // Failure to consume since item is not owned

        /**
         * This is RESULT_CODE not RESPONSE_RESULT. This code is sent as the value of Response Code
         * when handleActivityResult returns something other than Activity.RESULT_OK.
         */
        private static final int BILLING_RESULT_CODE_NOT_OK = 100;
    }

    @SuppressLint("StaticFieldLeak")
    private static BillingManager instance;

    @FreemiumStatus
    private int freemiumStatus = FREEMIUM_STATUS_UNKNOWN;

    /**
     * Ensure that there is exactly one instance of {@code BillingManager} app-wide, because
     * otherwise
     * there are two @Producers registered with Otto, which is disallowed and causes a
     * RuntimeException.
     */
    public static synchronized BillingManager with(Context context) {
        if (instance == null) {
            Log.i(TAG, "Created");
            instance = new BillingManager(context);
        }
        return instance;
    }

    private BillingManager(Context context) {
        this.context = context;
    }

    public void checkPurchaseStatus() {
        new AsyncTask<Void, Void, Integer>() {
            @FreemiumStatus
            private int newStatus;

            /**
             * Checks the status of the user’s purchase, on a background thread.
             * Don’t post a Bus Event from within this function whenever the status changes because
             * (1) it’s on a background thread, and
             * (2) after this function returns, the {@code AsyncTask} wrapper dispatches an event, no matter
             * what the status.
             */
            @Override
            protected Integer doInBackground(Void... voids) {
                Thread.currentThread().setName("BillingManager.checkPurchaseStatus");
                reconnect(new OnBillingServiceConnectedListener() {
                    @Override
                    public void onConnected(ServiceConnection serviceConnection, IInAppBillingService billingService) {
                        Log.i(TAG, "onConnected");
                        Bundle ownedItems = null;
                        try {
                            Log.i(TAG, "Calling IInAppBillingService.getPurchases(…)");
                            ownedItems = billingService.getPurchases(BILLING_API_VERSION, context.getPackageName(), "inapp", null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "RemoteException: ");
                            e.printStackTrace();
                            newStatus = FREEMIUM_STATUS_ERROR;
                        }

                        if (ownedItems != null) {
                            Log.i(TAG, "Returned from IInAppBillingService.getPurchases(…)");
                            int responseCode = ownedItems.getInt("RESPONSE_CODE");
                            Log.i(TAG, "responseCode: " + responseCode);
                            if (responseCode == BillingError.BILLING_RESPONSE_RESULT_OK) {
                                ArrayList<String> ownedSkus = ownedItems.getStringArrayList("INAPP_PURCHASE_ITEM_LIST");
                                for (int i = 0; i < ownedSkus.size(); ++i) {
                                    Log.i(TAG, "ownedSkus: " + ownedSkus.get(i));
                                    if (ownedSkus.get(i).equals(PREMIUM_VERSION_PRODUCT_ID)) {
                                        String purchaseDataJson = ownedItems.getStringArrayList("INAPP_PURCHASE_DATA_LIST").get(i);
                                        Log.i(TAG, "purchaseDataJson: " + purchaseDataJson);
                                        newStatus = FREEMIUM_STATUS_PREMIUM;
                                    }
                                }
                            } else {
                                newStatus = FREEMIUM_STATUS_ERROR;
                                String billingErrorString = getBillingErrorString(responseCode);
                                if (billingErrorString != null) {
                                    Log.e(TAG, "billingErrorString: " + billingErrorString);
                                }
                            }
                        }

                        if (newStatus == FREEMIUM_STATUS_UNKNOWN) {
                            // User has not purchased Premium by this point, so it’s either FREE or TRIAL.
//                            newStatus = Trial.isInTrialPeriod(context) ? FREEMIUM_STATUS_TRIAL : FREEMIUM_STATUS_FREE;
                            newStatus = FREEMIUM_STATUS_FREE;
                        }

                        Log.i(TAG, "newStatus: " + getFreemiumStatusString(newStatus));

                        // Force a disconnect, don’t leave the service connection open. The next time we need to
                        // refresh status, it is likely that the service is dead and we run into a
                        // {@code DeadObjectException} anyway.
                        disconnect(serviceConnection, billingService);
                    }

                    @Override
                    public void onConnectionError() {
                        Log.e(TAG, "onConnectionError");
                        newStatus = FREEMIUM_STATUS_ERROR;
                    }
                });
                return null;
            }

            @Override
            protected void onPostExecute(Integer integer) {
                updateStatus(newStatus);
            }
        }.execute();
    }

    private void reconnect(final OnBillingServiceConnectedListener onBillingServiceConnectionListener) {
        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        serviceIntent.setPackage("com.android.vending");

        final ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "onServiceConnected");
                IInAppBillingService billingService = IInAppBillingService.Stub.asInterface(service);
                if (onBillingServiceConnectionListener != null) {
                    onBillingServiceConnectionListener.onConnected(this, billingService);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "onServiceDisconnected");
                if (onBillingServiceConnectionListener != null) {
                    onBillingServiceConnectionListener.onDisconnected();
                }
            }
        };

        boolean connectionSuccessful = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!connectionSuccessful) {
            if (onBillingServiceConnectionListener != null) {
                onBillingServiceConnectionListener.onConnectionError();
            }
        }
    }

    private void disconnect(ServiceConnection serviceConnection, IInAppBillingService billingService) {
        Log.i(TAG, "disconnect");
        if (billingService != null) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {  //  Service not registered.
                // Ignore.
            }
        }
    }

    @FreemiumStatus
    public int getFreemiumStatus() {
        return freemiumStatus;
    }

    private void purchasePremium(final Activity activity) {
        Log.i(TAG, "purchasePremium");
        reconnect(new OnBillingServiceConnectedListener() {
            @Override
            public void onConnected(ServiceConnection serviceConnection, IInAppBillingService billingService) {
                Log.i(TAG, "onConnected");
                Bundle buyIntentBundle = null;
                try {
                    buyIntentBundle = billingService.getBuyIntent(BILLING_API_VERSION,
                            context.getPackageName(), PREMIUM_VERSION_PRODUCT_ID, "inapp", "developerPayload");
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException:");
                    e.printStackTrace();
                }

                if (buyIntentBundle == null) {  // Possibly purchased earlier.
                    Log.i(TAG, "buyIntentBundle == null");
                    checkPurchaseStatus();
                    return;
                }

                PendingIntent pendingIntent = buyIntentBundle.getParcelable("BUY_INTENT");
                if (pendingIntent == null) {  // Possibly purchased earlier.
                    Log.i(TAG, "pendingIntent == null");
                    checkPurchaseStatus();
                    return;
                }

                try {
                    activity.startIntentSenderForResult(pendingIntent.getIntentSender(),
                            REQUEST_CODE_BILLING_PURCHASE, new Intent(), 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Log.e(TAG, "IntentSender.SendIntentException:");
                    e.printStackTrace();
                }
            }

            @Override
            public void onConnectionError() {
                Log.e(TAG, "onConnectionError");
            }
        });
    }

    /**
     * Handler for when the In-App Billing API returns a result.
     *
     * @return {@code true} if handled, {@code false if not handled}.
     *
     * Call this from your Activity.onActivityResult(...) which has a similar signature.
     *
     * public class YourActivity extends Activity {
     * // ...
     * @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
     * if (BillingManager.with(context).handleActivityResult(requestCode, resultCode, intent)) {
     * return;
     * }
     * super.onActivityResult(requestCode, resultCode, intent);
     * }
     *
     * // ...
     * }
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        Log.i(TAG, "handleActivityResult");

        if (requestCode != REQUEST_CODE_BILLING_PURCHASE) {
            Log.w(TAG, "requestCode != REQUEST_CODE_BILLING_PURCHASE");
            return false;  // Not for us, not handled.
        }

        if (resultCode == Activity.RESULT_OK) {
            int responseCode = data.getIntExtra("RESPONSE_CODE", 0);
            Log.i(TAG, "responseCode: " + responseCode);
            if (responseCode == BillingError.BILLING_RESPONSE_RESULT_OK) {
                String purchaseDataJson = data.getStringExtra("INAPP_PURCHASE_DATA");
                Log.i(TAG, "purchaseDataJson: " + purchaseDataJson);
                try {
                    JSONObject purchaseData = new JSONObject(purchaseDataJson);
                    if (PREMIUM_VERSION_PRODUCT_ID.equals(purchaseData.getString("productId"))) {
                        updateStatus(FREEMIUM_STATUS_PREMIUM);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "JSONException:");
                    e.printStackTrace();
                }
            } else {
                Log.e(TAG, "responseCode != BillingError.BILLING_RESPONSE_RESULT_OK");
                updateStatus(FREEMIUM_STATUS_ERROR);
                String billingErrorString = getBillingErrorString(responseCode);
                if (billingErrorString != null) {
                    Log.e(TAG, "billingErrorString: " + billingErrorString);
                }
            }

        } else if (resultCode == BillingError.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {
            updateStatus(FREEMIUM_STATUS_PREMIUM);
            Log.e(TAG, "resultCode == BillingError.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED");

        } else {
            updateStatus(FREEMIUM_STATUS_ERROR);
            Log.e(TAG, "resultCode: " + resultCode);
//            App.bus().post(new PremiumPurchaseError(resultCode, BillingError.BILLING_RESULT_CODE_NOT_OK));
        }

        return true;  // Even if the response was not OK, this request was meant for us to handle, and we handled it.
    }

    /**
     * Internal mutator that also broadcasts a bus event, that other app components can listen for
     * purchase events, and update the UI accordingly.
     */
    private void updateStatus(int newStatus) {
        Log.i(TAG, "updateStatus: " + getFreemiumStatusString(newStatus));
        freemiumStatus = newStatus;
//        App.bus().post(new FreemiumStatusEvent(freemiumStatus));
    }

    /**
     * @return a stringified version of the In-App Billing error code, appropriate for logging.
     */
    public static String getBillingErrorString(int errorCode) {
        switch (errorCode) {
            case BillingError.BILLING_RESPONSE_RESULT_OK:
                return "OK";
            case BillingError.BILLING_RESPONSE_RESULT_USER_CANCELED:
                return "USER_CANCELED";
            case BillingError.BILLING_RESPONSE_RESULT_SERVICE_UNAVAILABLE:
                return "SERVICE_UNAVAILABLE";
            case BillingError.BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE:
                // This happens too often, e.g. in China, and we don’t want this clogging up logs
                // so return a null to skip logging this.
                return null; // "BILLING_UNAVAILABLE";
            case BillingError.BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE:
                return "ITEM_UNAVAILABLE";
            case BillingError.BILLING_RESPONSE_RESULT_DEVELOPER_ERROR:
                return "DEVELOPER_ERROR";
            case BillingError.BILLING_RESPONSE_RESULT_ERROR:
                return "RESULT_ERROR";
            case BillingError.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED:
                return "ITEM_ALREADY_OWNED";
            case BillingError.BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED:
                return "ITEM_NOT_OWNED";
        }
        return String.format(Locale.getDefault(), "UNKNOWN_ERROR: %d", errorCode);
    }

    /**
     * A convenience wrapper for {@code getFreemiumStatusString} that returns the stringified
     * version
     * of the current status. This way, it does not require the caller to pass in a status code
     * explicitly.
     */
    public String getFreemiumStatusString() {
        return getFreemiumStatusString(freemiumStatus);
    }

    /**
     * @return a stringified version of {@link FreemiumStatus}, appropriate for logging.
     */
    private static String getFreemiumStatusString(@FreemiumStatus int status) {
        switch (status) {
            case BillingManager.FREEMIUM_STATUS_UNKNOWN:
                return null;  // Null, because there is no value in having these logged.
            case FREEMIUM_STATUS_ERROR:
                return "Error";  // Covers FREEMIUM_STATUS_ERROR & any unknown errors.
            case BillingManager.FREEMIUM_STATUS_FREE:
                return "Free";
            case BillingManager.FREEMIUM_STATUS_PREMIUM:
                return "Premium";
            case BillingManager.FREEMIUM_STATUS_TRIAL:
                return "Trial";
        }
        return null;
    }

    /**
     * Abstract class that has default implementations for the methods we don’t care about, and an
     * abstract method, {@code onConnected}, to handle the common use case.
     */
    abstract static class OnBillingServiceConnectedListener {
        public abstract void onConnected(ServiceConnection serviceConnection,
                                         IInAppBillingService billingService);

        public void onDisconnected() {
            Log.i(TAG, "onDisconnected");
        }

        public void onConnectionError() {
            Log.i(TAG, "onConnectionError");
        }
    }
}