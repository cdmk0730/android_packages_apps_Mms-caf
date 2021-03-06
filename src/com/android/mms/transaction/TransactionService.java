/*
 * Copyright (c) 2012 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.mms.transaction;

import android.app.NotificationManager;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.database.DatabaseUtils;
import android.database.sqlite.SqliteWrapper;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Mms.Sent;
import android.provider.Telephony.MmsSms.PendingMessages;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.ui.MessageUtils;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.MultiSimUtility;
import com.android.mms.util.RateController;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;

import java.io.IOException;
import java.util.ArrayList;

/**
 * The TransactionService of the MMS Client is responsible for handling requests
 * to initiate client-transactions sent from:
 * <ul>
 * <li>The Proxy-Relay (Through Push messages)</li>
 * <li>The composer/viewer activities of the MMS Client (Through intents)</li>
 * </ul>
 * The TransactionService runs locally in the same process as the application.
 * It contains a HandlerThread to which messages are posted from the
 * intent-receivers of this application.
 * <p/>
 * <b>IMPORTANT</b>: This is currently the only instance in the system in
 * which simultaneous connectivity to both the mobile data network and
 * a Wi-Fi network is allowed. This makes the code for handling network
 * connectivity somewhat different than it is in other applications. In
 * particular, we want to be able to send or receive MMS messages when
 * a Wi-Fi connection is active (which implies that there is no connection
 * to the mobile data network). This has two main consequences:
 * <ul>
 * <li>Testing for current network connectivity ({@link android.net.NetworkInfo#isConnected()} is
 * not sufficient. Instead, the correct test is for network availability
 * ({@link android.net.NetworkInfo#isAvailable()}).</li>
 * <li>If the mobile data network is not in the connected state, but it is available,
 * we must initiate setup of the mobile data connection, and defer handling
 * the MMS transaction until the connection is established.</li>
 * </ul>
 */
public class TransactionService extends Service implements Observer {
    private static final String TAG = "TransactionService";
    private static final boolean DEBUG = true;

    /**
     * Used to identify notification intents broadcasted by the
     * TransactionService when a Transaction is completed.
     */
    public static final String TRANSACTION_COMPLETED_ACTION =
            "android.intent.action.TRANSACTION_COMPLETED_ACTION";

    /**
     * Action for the Intent which is sent by Alarm service to launch
     * TransactionService.
     */
    public static final String ACTION_ONALARM = "android.intent.action.ACTION_ONALARM";

    /**
     * Action for the Intent which is sent when the user turns on the auto-retrieve setting.
     * This service gets started to auto-retrieve any undownloaded messages.
     */
    public static final String ACTION_ENABLE_AUTO_RETRIEVE
            = "android.intent.action.ACTION_ENABLE_AUTO_RETRIEVE";

    /**
     * Used as extra key in notification intents broadcasted by the TransactionService
     * when a Transaction is completed (TRANSACTION_COMPLETED_ACTION intents).
     * Allowed values for this key are: TransactionState.INITIALIZED,
     * TransactionState.SUCCESS, TransactionState.FAILED.
     */
    public static final String STATE = "state";

    /**
     * Used as extra key in notification intents broadcasted by the TransactionService
     * when a Transaction is completed (TRANSACTION_COMPLETED_ACTION intents).
     * Allowed values for this key are any valid content uri.
     */
    public static final String STATE_URI = "uri";

    public static final String CANCEL_URI = "canceluri";

    private static final int EVENT_TRANSACTION_REQUEST = 1;
    private static final int EVENT_CONTINUE_MMS_CONNECTIVITY = 3;
    private static final int EVENT_HANDLE_NEXT_PENDING_TRANSACTION = 4;
    private static final int EVENT_NEW_INTENT = 5;
    private static final int EVENT_MMS_CONNECTIVITY_TIMEOUT = 6;
    private static final int EVENT_QUIT = 100;

    // delay 75 seconds to check whether we have enabled mms connectivity
    private static final int MMS_CONNECTIVITY_DELAY = 75 * 1000;
    private static final int MMS_CONNECTIVITY_RETRY_TIMES = 3;

    private static final int TOAST_MSG_QUEUED = 1;
    private static final int TOAST_DOWNLOAD_LATER = 2;
    private static final int TOAST_NO_APN = 3;
    private static final int TOAST_SEND_FAILED_RETRY = 4;
    private static final int TOAST_DOWNLOAD_FAILED_RETRY = 5;
    private static final int TOAST_DOWNLOAD_CANCELED = 6;
    private static final int TOAST_NONE = -1;

    // How often to extend the use of the MMS APN while a transaction
    // is still being processed.
    private static final int APN_EXTENSION_WAIT = 30 * 1000;

    private ServiceHandler mServiceHandler;
    private Looper mServiceLooper;
    private final ArrayList<Transaction> mProcessing  = new ArrayList<Transaction>();
    private final ArrayList<Transaction> mPending  = new ArrayList<Transaction>();
    private ConnectivityManager mConnMgr;
    private ConnectivityBroadcastReceiver mReceiver;
    private static TransactionService sInstance;

    private PowerManager.WakeLock mWakeLock;
    private int mMmsConnecvivityRetryCount;

    private Integer mRef = 0;
    private int launchRetryAttempt;
    private final int maxLaunchRetryAttempts = 5;
    private ArrayList<TxnRequest> mTxnSubIdMap = new ArrayList();

    public Handler mToastHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String str = null;

            if (msg.what == TOAST_MSG_QUEUED) {
                str = getString(R.string.message_queued);
            } else if (msg.what == TOAST_DOWNLOAD_LATER) {
                str = getString(R.string.download_later);
            } else if (msg.what == TOAST_NO_APN) {
                str = getString(R.string.no_apn);
            } else if (msg.what == TOAST_SEND_FAILED_RETRY) {
                str = getString(R.string.send_failed_retry);
            } else if (msg.what == TOAST_DOWNLOAD_FAILED_RETRY) {
                str = getString(R.string.download_failed_retry);
            } else if (msg.what == TOAST_DOWNLOAD_CANCELED) {
                str = getString(R.string.download_canceled);
            }

            if (str != null) {
                Toast.makeText(TransactionService.this, str,
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    public static TransactionService getInstance() {
        return sInstance;
    }

    public boolean isIdle() {
        synchronized (mRef) {
            Log.d(TAG, "isIdle mRef=" + mRef);
            if (mRef > 0) {
                return false;
            } else {
                return true;
            }
        }
    }

    @Override
    public void onCreate() {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "Creating TransactionService");
        }
        sInstance = this;

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.
        HandlerThread thread = new HandlerThread("TransactionService");
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        mReceiver = new ConnectivityBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mReceiver, intentFilter);
    }

    class TxnRequest {
        String txnId;
        int destSub;
        int originSub;
        boolean isFailed = false;

        TxnRequest(String id, int destSub, int originSub) {
            this.txnId = id;
            this.destSub = destSub;
            this.originSub = originSub;
        }

        public String toString() {
            return "TxnRequest=[txnId=" + txnId
                + ", destSub=" + destSub
                + ", originSub=" + originSub
                + ", isFailed=" + isFailed
                + "]";
        }

    };

    private String getTxnIdFromDb(Uri uri) {
        String txnId = null;
        Cursor c = getApplicationContext().getContentResolver().query(uri,
                null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    txnId = c.getString(c.getColumnIndex(Mms.TRANSACTION_ID));
                    Log.d(TAG, "TxnId in db=" + txnId );
                    c.close();
                    c = null;
                    return txnId;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        Log.d(TAG, "TxnId in db=" + txnId );
        return txnId;

    }

    private int getSubIdFromDb(Uri uri) {
        int subId = 0;
        Cursor c = getApplicationContext().getContentResolver().query(uri,
                null, null, null, null);
        Log.d(TAG, "Cursor= "+DatabaseUtils.dumpCursorToString(c));
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    subId = c.getInt(c.getColumnIndex(Mms.SUB_ID));
                    Log.d(TAG, "subId in db="+subId );
                    c.close();
                    c = null;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
        return subId;
    }

    private void addUnique(String txnId, int dest, int origin) {
        synchronized (mTxnSubIdMap) {
            boolean isRecordExist = false;
            boolean isInitExist = false;

            if (txnId == null || (dest == -1 && origin == -1)) {
                //TransactionService does not have any info of
                //sub for this transaction.
                //It could happen if MMS process or transactionService died
                //or transaction id is null for the transaction.
                //We will go ahead with MMS but we wont be able to revert back
                Log.d(TAG, "NOT TRACKING, Txn=" + txnId
                        + ", dest=" + dest
                        + ", origin=" + origin);

                return;
            }

            TxnRequest requested = new TxnRequest(txnId, dest, origin);
            Log.d(TAG, "addUnique(): requested=" + requested);
            for (TxnRequest t : mTxnSubIdMap) {
                Log.d(TAG, "Dump =" + t);
                if (t.txnId.equals(txnId)) {
                    Log.d(TAG, "addUnique() Record exists");
                    isRecordExist = true;
                }
                if(t.txnId.equals("init")) {
                    isInitExist = true;
                }
            }

            if (!isRecordExist) {
                TxnRequest txnRequest = new TxnRequest(txnId, dest, origin);
                Log.d(TAG, "Adding a new record =" + txnRequest);
                mTxnSubIdMap.add(txnRequest);
            }

            if (!isInitExist) {
                Log.d(TAG, "Adding a init record");
                mTxnSubIdMap.add(new TxnRequest("init", dest, origin));
            }
        }
    }

    private void cleanupMap(int currentDds) {
        synchronized (mTxnSubIdMap) {
            Log.d(TAG, "cleanupMap for all successful txn on DDS = " + currentDds);
            for (int i = 0; i<mTxnSubIdMap.size(); i++ ) {
                TxnRequest t = mTxnSubIdMap.get(i);
                Log.d(TAG, "Dump =" + t);
                if (t.destSub == currentDds && t.isFailed == false) {
                    Log.d(TAG, "cleanup " + t);
                    mTxnSubIdMap.remove(t);
                }
            }
        }

    }

    private void removeFromMap(String txnId) {
        synchronized (mTxnSubIdMap) {
            Log.d(TAG, "removeFromMap: txnId = " + txnId);
            for (int i = 0; i<mTxnSubIdMap.size(); i++ ) {
                TxnRequest t = mTxnSubIdMap.get(i);
                Log.d(TAG, "Dump =" + t);
                if (t.txnId.equals(txnId)) {
                    Log.d(TAG, "removeFromMap(), Record found = " + t);
                    mTxnSubIdMap.remove(t);
                    return;
                }
            }
        }
    }

    private void updateTxnFailedInMap(String txnId) {
        synchronized (mTxnSubIdMap) {
            Log.d(TAG, "updateTxnFailedInMap: txnId = " + txnId);
            for (int i = 0; i<mTxnSubIdMap.size(); i++ ) {
                TxnRequest t = mTxnSubIdMap.get(i);
                Log.d(TAG, "Dump =" + t);
                if (t.txnId.equals(txnId)) {
                    Log.d(TAG, "updateTxnFailedInMap(), Record found = "+
                            t + ", marked as failed");
                    t.isFailed = true;
                    return;
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Log.d(TAG, "onStartCommand(): E");
            incRefCount();

            Message msg = mServiceHandler.obtainMessage(EVENT_NEW_INTENT);
            msg.arg1 = startId;
            msg.obj = intent;
            mServiceHandler.sendMessage(msg);
        }
        return Service.START_NOT_STICKY;
    }

    private void decRefCount() {
        synchronized (mRef) {
            Log.d(TAG, "decRefCount() mRef=" + --mRef);
            if (mRef < 0) {
                Log.d(TAG, "BUG, mRef IS NEGATIVE !!!");
                mRef =0;
            }
        }
    }

    private void decRefCountN(int n) {
        synchronized (mRef) {
            mRef = mRef - n;
            Log.d(TAG, "decRefCountN() mRef=" + mRef);
        }
    }

    private void incRefCount() {
        synchronized (mRef) {
            Log.d(TAG, "incRefCount() mRef=" + ++mRef);
        }
    }

    private void incRefCountN(int n) {
        synchronized (mRef) {
            mRef = mRef + n;
            Log.d(TAG, "incRefCountN() mRef=" + mRef);
        }
    }

    public void onNewIntent(Intent intent, int serviceId) {
        mConnMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean enableMmsData = getApplicationContext().getResources().getBoolean(
                com.android.internal.R.bool.config_setup_mms_data);
        if (mConnMgr == null || !MmsConfig.isSmsEnabled(getApplicationContext())) {
            endMmsConnectivity();
            decRefCount();
            return;
        }
        boolean noNetwork = !isNetworkAvailable(enableMmsData);
        if (getResources().getBoolean(R.bool.config_retry_always) && !noNetwork) {
            noNetwork |= !MessageUtils.isDataNetworkAvaiable(getApplicationContext());
        }

        Log.d(TAG, "onNewIntent: serviceId: " + serviceId + ": " + intent.getExtras() +
                " intent=" + intent);
        Log.d(TAG, "    networkAvailable=" + !noNetwork);

        Bundle extras = intent.getExtras();
        String action = intent.getAction();
        if ((ACTION_ONALARM.equals(action) || ACTION_ENABLE_AUTO_RETRIEVE.equals(action) ||
                (extras == null)) || ((extras != null) && !extras.containsKey("uri")
                && !extras.containsKey(CANCEL_URI))) {

            //We hit here when either the Retrymanager triggered us or there is
            //send operation in which case uri is not set. For rest of the
            //cases(MT MMS) we hit "else" case.

            // Scan database to find all pending operations.
            Cursor cursor = PduPersister.getPduPersister(this).getPendingMessages(
                    System.currentTimeMillis());
            Log.d(TAG, "Cursor= "+DatabaseUtils.dumpCursorToString(cursor));
            if (cursor != null) {
                try {
                    int count = cursor.getCount();

                    //if more than 1 records are present in DB.
                    if (count > 1) {
                        incRefCountN(count-1);
                        Log.d(TAG, "onNewIntent() multiple pending items mRef=" + mRef);
                    }

                    Log.d(TAG, "onNewIntent: cursor.count=" + count + " action=" + action);

                    if (count == 0) {

                        Log.d(TAG, "onNewIntent: no pending messages. Stopping service.");
                        RetryScheduler.setRetryAlarm(this);
                        cleanUpIfIdle(serviceId);
                        decRefCount();
                        return;
                    }

                    int columnIndexOfMsgId = cursor.getColumnIndexOrThrow(PendingMessages.MSG_ID);
                    int columnIndexOfMsgType = cursor.getColumnIndexOrThrow(
                            PendingMessages.MSG_TYPE);

                    while (cursor.moveToNext()) {
                        int msgType = cursor.getInt(columnIndexOfMsgType);
                        int transactionType = getTransactionType(msgType);

                        Log.d(TAG, "onNewIntent: msgType=" + msgType + " transactionType=" +
                                    transactionType);
                        if (noNetwork) {
                            // Because there is a MMS queue list including
                            // unsent and undownload MMS in database while data
                            // network unenabled. Anyway we should give user the
                            // information about the last MMS in the queue list.
                            // While there is no data network, we will give
                            // a prompt to user about the last MMS failed in
                            // database, so we need to fetch the information of
                            // last pending MMS in the queue list, just make the
                            // cursor move to the end of the queue list, and
                            // give the corresponding prompt to user.
                            cursor.moveToLast();
                            transactionType = getTransactionType(cursor
                                    .getInt(columnIndexOfMsgType));
                            Uri uri = ContentUris.withAppendedId(
                                    Mms.CONTENT_URI,
                                    cursor.getLong(columnIndexOfMsgId));
                            boolean inRetry = ACTION_ONALARM.equals(intent.getAction());
                            onNetworkUnavailable(serviceId, transactionType, uri, inRetry);
                            Log.d(TAG, "No network during MO or retry operation");
                            decRefCountN(count);
                            Log.d(TAG, "Reverted mRef to =" + mRef);
                            return;
                        }
                        int failureType = cursor.getInt(
                                cursor.getColumnIndexOrThrow(
                                        PendingMessages.ERROR_TYPE));
                        switch (transactionType) {
                            case -1:
                                decRefCount();
                                break;
                            case Transaction.RETRIEVE_TRANSACTION:
                                // If it's a transiently failed transaction,
                                // we should retry it in spite of current
                                // downloading mode. If the user just turned on the auto-retrieve
                                // option, we also retry those messages that don't have any errors.
                                DownloadManager downloadManager = DownloadManager.getInstance();
                                boolean autoDownload = downloadManager.isAuto();
                                boolean isMobileDataEnabled = mConnMgr.getMobileDataEnabled();
                                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                                    Log.v(TAG, "onNewIntent: failureType=" + failureType +
                                            " action=" + action + " isTransientFailure:" +
                                            isTransientFailure(failureType) + " autoDownload=" +
                                            autoDownload);
                                }
                                if (!autoDownload || MessageUtils.isMmsMemoryFull()
                                        || !isMobileDataEnabled) {
                                    // If autodownload is turned off, don't process the
                                    // transaction.
                                    Log.d(TAG, "onNewIntent: skipping - autodownload off");
                                    decRefCount();
                                    break;
                                }
                                // Logic is twisty. If there's no failure or the failure
                                // is a non-permanent failure, we want to process the transaction.
                                // Otherwise, break out and skip processing this transaction.
                                if (!(failureType == MmsSms.NO_ERROR ||
                                        isTransientFailure(failureType))) {
                                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                                        Log.v(TAG, "onNewIntent: skipping - permanent error");
                                    }
                                    decRefCount();
                                    break;
                                }
                                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                                    Log.v(TAG, "onNewIntent: falling through and processing");
                                }
                               // fall-through
                            default:
                                Uri uri = ContentUris.withAppendedId(
                                        Mms.CONTENT_URI,
                                        cursor.getLong(columnIndexOfMsgId));
                                // Handle no net work failed case.
                                // If the network is restored, then
                                // show the MMS status as sending.
                                if (failureType == MmsSms.ERR_TYPE_MMS_PROTO_TRANSIENT
                                        && getResources().getBoolean(R.bool.config_manual_resend)) {
                                    updateMsgErrorType(uri, MmsSms.NO_ERROR);
                                }

                                String txnId = getTxnIdFromDb(uri);
                                int subId = getSubIdFromDb(uri);
                                Log.d(TAG, "SubId from DB= "+subId);

                                if(subId != MultiSimUtility.getCurrentDataSubscription
                                        (getApplicationContext())) {
                                    Log.d(TAG, "This MMS transaction can not be done"+
                                         "on current sub. Ignore it. uri="+uri);
                                    decRefCount();
                                    break;
                                }

                                int destSub = intent.getIntExtra(Mms.SUB_ID, -1);
                                int originSub = intent.getIntExtra(
                                        MultiSimUtility.ORIGIN_SUB_ID, -1);

                                Log.d(TAG, "Destination Sub = "+destSub);
                                Log.d(TAG, "Origin Sub = "+originSub);

                                addUnique(txnId, destSub, originSub);

                                TransactionBundle args = new TransactionBundle(
                                        transactionType, uri.toString());
                                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                                    Log.v(TAG, "onNewIntent: launchTransaction uri=" + uri);
                                }
                                // FIXME: We use the same serviceId for all MMs.
                                launchTransaction(serviceId, args, false);
                                break;
                        }
                    }
                } finally {
                    cursor.close();
                }
            } else {
                Log.d(TAG, "onNewIntent: no pending messages. Stopping service.");
                RetryScheduler.setRetryAlarm(this);
                cleanUpIfIdle(serviceId);
                decRefCount();
            }
        } else if ((extras != null) && extras.containsKey(CANCEL_URI)) {
            String uriStr = intent.getStringExtra(CANCEL_URI);
            Uri mCancelUri = Uri.parse(uriStr);
            for (Transaction transaction : mProcessing) {
                transaction.cancelTransaction(mCancelUri);
            }
            for (Transaction transaction : mPending) {
                transaction.cancelTransaction(mCancelUri);
            }
        } else {
            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                Log.v(TAG, "onNewIntent: launch transaction...");
            }
            String uriStr = intent.getStringExtra("uri");
            int destSub = intent.getIntExtra(Mms.SUB_ID, -1);
            int originSub = intent.getIntExtra(MultiSimUtility.ORIGIN_SUB_ID, -1);

            Uri uri = Uri.parse(uriStr);
            int subId = getSubIdFromDb(uri);
            String txnId = getTxnIdFromDb(uri);

            if (txnId == null) {
                Log.d(TAG, "Transaction already over.");
                decRefCount();
                return;
            }

            Log.d(TAG, "SubId from DB= "+subId);
            Log.d(TAG, "Destination Sub = "+destSub);
            Log.d(TAG, "Origin Sub = "+originSub);

            if (noNetwork) {
                synchronized (mRef) {
                    Log.e(TAG, "No network during MT operation");
                    decRefCount();
                }
                return;
            }

            addUnique(txnId, destSub, originSub);

            // For launching NotificationTransaction and test purpose.
            TransactionBundle args = new TransactionBundle(intent.getExtras());
            launchTransaction(serviceId, args, noNetwork);
        }
    }

    private void removeNotification() {
        synchronized (mTxnSubIdMap) {
            Log.d(TAG, "removeNotification, txnId=init" );
            boolean anyFailure = false;
            TxnRequest req = null;
            int currentDds = MultiSimUtility.getCurrentDataSubscription
                (getApplicationContext());
            Log.d(TAG, "removeNotification, currentDds=" + currentDds);

            for (int i = 0; i<mTxnSubIdMap.size(); i++ ) {
                TxnRequest t = mTxnSubIdMap.get(i);
                Log.d(TAG, "removeNotification Dump =" + t);

                if (!(t.txnId.equals("init")) && t.isFailed == true) {
                    anyFailure = true;
                }
                if (t.txnId.equals("init")) {
                    req = t;
                }
            }

            Log.d(TAG, "removeNotification(), found init = " + req);

            if (req != null) {

                // remove notification
                String ns = Context.NOTIFICATION_SERVICE;
                NotificationManager mNotificationManager = (NotificationManager)
                    getApplicationContext().getSystemService(ns);
                mNotificationManager.cancel(req.destSub);

                boolean isSilent = true; //default, silent enabled.
                if ("prompt".equals(
                            SystemProperties.get(TelephonyProperties.PROPERTY_MMS_TRANSACTION))) {
                    isSilent = false;
                }

                if (isSilent) {
                    int nextSub = req.originSub;
                    Log.d(TAG, "MMS silent transaction finished for sub=" + nextSub);
                    Intent silentIntent = new Intent(getApplicationContext(),
                            com.android.mms.ui.SelectMmsSubscription.class);
                    silentIntent.putExtra(Mms.SUB_ID, nextSub);
                    /*since it is trigger_switch_only, origin is irrelevant.*/
                    silentIntent.putExtra(MultiSimUtility.ORIGIN_SUB_ID, -1);
                    silentIntent.putExtra("TRIGGER_SWITCH_ONLY", 1);
                    getApplicationContext().startService(silentIntent);
                }

                if (!anyFailure) {
                    Log.d(TAG, "removeNotification(), removing init");
                    mTxnSubIdMap.remove(req);
                } else {
                    Log.d(TAG, "removeNotification(), some txn failed, not removing init");
                }
                cleanupMap(currentDds);

                return;
            }
        }

    }

    private void cleanUpIfIdle(int startId) {
        synchronized (mProcessing) {
            if (mProcessing.isEmpty() && mPending.isEmpty()) {
                Log.v(TAG, "CleanUpIfIdle: txnServ is idle");
                removeNotification();
            } else {
                Log.v(TAG, "CleanUpIfIdle: txnServ is not idle");
            }
        }
    }

    private static boolean isTransientFailure(int type) {
        return type >= MmsSms.NO_ERROR && type < MmsSms.ERR_TYPE_GENERIC_PERMANENT;
    }

    private boolean isNetworkAvailable(boolean enableMmsData) {
        if (mConnMgr == null) {
            return false;
        } else {
            NetworkInfo ni = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
            if (ni == null) {
                return false;
            } else {
                return ni.isAvailable() &&
                        (enableMmsData || mConnMgr.getMobileDataEnabled());
            }
        }
    }

    private int getTransactionType(int msgType) {
        switch (msgType) {
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                return Transaction.RETRIEVE_TRANSACTION;
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
                return Transaction.READREC_TRANSACTION;
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                return Transaction.SEND_TRANSACTION;
            default:
                Log.w(TAG, "Unrecognized MESSAGE_TYPE: " + msgType);
                return -1;
        }
    }

    private void launchTransaction(int serviceId, TransactionBundle txnBundle, boolean noNetwork) {
        if (noNetwork) {
            Log.w(TAG, "launchTransaction: no network error!");
            onNetworkUnavailable(serviceId, txnBundle.getTransactionType(),
                    Uri.parse(txnBundle.getUri()), false);
            return;
        }
        Message msg = mServiceHandler.obtainMessage(EVENT_TRANSACTION_REQUEST);
        msg.arg1 = serviceId;
        msg.obj = txnBundle;

        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "launchTransaction: sending message " + msg);
        }
        mServiceHandler.sendMessage(msg);
    }

    private void onNetworkUnavailable(int serviceId, int transactionType,
            Uri uri, boolean inRetry) {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "onNetworkUnavailable: sid=" + serviceId + ", type=" + transactionType);
        }

        int toastType = TOAST_NONE;
        if (transactionType == Transaction.RETRIEVE_TRANSACTION) {
            if (getResources().getBoolean(R.bool.config_retry_always) && inRetry) {
                toastType = isLastRetry(uri.getLastPathSegment()) ?
                        TOAST_NONE : TOAST_DOWNLOAD_FAILED_RETRY;
            } else {
                toastType = TOAST_DOWNLOAD_LATER;
            }
        } else if (transactionType == Transaction.SEND_TRANSACTION) {
            if (getResources().getBoolean(R.bool.config_retry_always) && inRetry) {
                toastType = isLastRetry(uri.getLastPathSegment()) ?
                        TOAST_NONE : TOAST_SEND_FAILED_RETRY;
            } else {
                if (getResources().getBoolean(R.bool.config_manual_resend)) {
                    updateMsgErrorType(uri, MmsSms.ERR_TYPE_MMS_PROTO_TRANSIENT);
                }
                toastType = TOAST_MSG_QUEUED;
            }
        }
        if (toastType != TOAST_NONE) {
            mToastHandler.sendEmptyMessage(toastType);
        }

        if (getResources().getBoolean(R.bool.config_retry_always)
                && !isLastRetry(uri.getLastPathSegment())) {
            RetryScheduler.scheduleRetry(getApplicationContext(), uri);
            RetryScheduler.setRetryAlarm(getApplicationContext());
        }
    }

    private void updateMsgErrorType(Uri mmsUri, int errorType) {
        // Update the pending_msgs table with an error type for that new item.
        ContentValues values = new ContentValues(1);
        values.put(PendingMessages.ERROR_TYPE, errorType);
        long msgId = ContentUris.parseId(mmsUri);
        SqliteWrapper.update(getApplicationContext(), getApplicationContext().getContentResolver(),
                PendingMessages.CONTENT_URI,
                values, PendingMessages.MSG_ID + "=" + msgId, null);
    }

    private int getRetryIndex(String msgId) {
        Uri.Builder uriBuilder = PendingMessages.CONTENT_URI.buildUpon();
        uriBuilder.appendQueryParameter("protocol", "mms");
        uriBuilder.appendQueryParameter("message", msgId);

        Cursor cursor = null;
        try {
             cursor = SqliteWrapper.query(this, getContentResolver(),
                    uriBuilder.build(), null, null, null, null);
            if (cursor != null && (cursor.getCount() == 1) && cursor.moveToFirst()) {
                int index = cursor.getInt(cursor.getColumnIndexOrThrow(
                        PendingMessages.RETRY_INDEX));
                Log.v(TAG, "getRetryIndex:" + index) ;
                return index;
            }
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean isLastRetry(String msgId) {
        Uri.Builder uriBuilder = PendingMessages.CONTENT_URI.buildUpon();
        uriBuilder.appendQueryParameter("protocol", "mms");
        uriBuilder.appendQueryParameter("message", msgId);

        Cursor cursor = null;
        try {
             cursor = SqliteWrapper.query(this, getContentResolver(),
                    uriBuilder.build(), null, null, null, null);
            if (cursor != null && (cursor.getCount() == 1) && cursor.moveToFirst()) {
                int retryIndex = cursor.getInt(cursor.getColumnIndexOrThrow(
                        PendingMessages.RETRY_INDEX));
                DefaultRetryScheme scheme = new DefaultRetryScheme(this, retryIndex);
                Log.i(TAG,"isLastRetry retryIndex="+retryIndex+" limit="+scheme.getRetryLimit());
                if (retryIndex == scheme.getRetryLimit()) {
                    return true;
                }
            }
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "Destroying TransactionService");
        }
        if (!mPending.isEmpty()) {
            Log.w(TAG, "TransactionService exiting with transaction still pending");
        }

        releaseWakeLock();

        unregisterReceiver(mReceiver);

        mServiceHandler.sendEmptyMessage(EVENT_QUIT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    /**
     * Handle status change of Transaction (The Observable).
     */
    public void update(Observable observable) {
        Log.d(TAG, "update() E");
        decRefCount();

        Transaction transaction = (Transaction) observable;
        int serviceId = transaction.getServiceId();
        launchRetryAttempt = 0;

        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "update transaction " + serviceId);
        }

        try {
            synchronized (mProcessing) {
                mProcessing.remove(transaction);
                if (mPending.size() > 0) {
                    Log.d(TAG, "update: handle next pending transaction...");
                    Message msg = mServiceHandler.obtainMessage(
                            EVENT_HANDLE_NEXT_PENDING_TRANSACTION,
                            transaction.getConnectionSettings());
                    mServiceHandler.sendMessage(msg);
                }
                else if (mProcessing.isEmpty()) {
                    Log.d(TAG, "update: endMmsConnectivity");
                    endMmsConnectivity();
                } else {
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "update: mProcessing is not empty");
                    }
                }
            }

            Intent intent = new Intent(TRANSACTION_COMPLETED_ACTION);
            TransactionState state = transaction.getState();
            Uri uri = state.getContentUri();
            String txnId = getTxnIdFromDb(uri);

            int result = state.getState();
            intent.putExtra(STATE, result);

            switch (result) {
                case TransactionState.SUCCESS:
                    Log.d(TAG, "Transaction complete: " + serviceId);

                    removeFromMap(txnId);
                    intent.putExtra(STATE_URI, state.getContentUri());

                    // Notify user in the system-wide notification area.
                    switch (transaction.getType()) {
                        case Transaction.NOTIFICATION_TRANSACTION:
                        case Transaction.RETRIEVE_TRANSACTION:
                            if (getResources().getBoolean(R.bool.config_detect_low_memory)
                                    && MessageUtils.isMemoryLow()) {
                                MessagingNotification.notifyMemoryLow(this);
                            }
                            // We're already in a non-UI thread called from
                            // NotificationTransacation.run(), so ok to block here.
                            long threadId = MessagingNotification.getThreadId(
                                    this, state.getContentUri());
                            MessagingNotification.blockingUpdateNewMessageIndicator(this,
                                    threadId,
                                    false);
                            MessagingNotification.updateDownloadFailedNotification(this);
                            break;
                        case Transaction.SEND_TRANSACTION:
                            RateController.getInstance().update();
                            break;
                    }
                    break;
                case TransactionState.FAILED:
                    int type = transaction.getType();
                    if (uri != null) {
                        String msgId = uri.getLastPathSegment();
                        if (!isLastRetry(msgId)) {
                            if (type == Transaction.SEND_TRANSACTION) {
                                mToastHandler.sendEmptyMessage(TOAST_SEND_FAILED_RETRY);
                            } else if ((type == Transaction.RETRIEVE_TRANSACTION) ||
                                    (type == Transaction.NOTIFICATION_TRANSACTION)) {
                                mToastHandler.sendEmptyMessage(TOAST_DOWNLOAD_FAILED_RETRY);
                            }
                        }
                    }
                    Log.v(TAG, "Transaction failed: " + serviceId);
                    updateTxnFailedInMap(txnId);
                    updateTxnFailedInMap("init");
                    break;
                case TransactionState.CANCELED:
                    Log.d(TAG, "Transaction canceled: " + serviceId);
                    removeFromMap(txnId);
                    if (transaction.getType() == Transaction.RETRIEVE_TRANSACTION) {
                        mToastHandler.sendEmptyMessage(TOAST_DOWNLOAD_CANCELED);
                    }
                    break;
                default:
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "Transaction state unknown: " +
                                serviceId + " " + result);
                    }
                    break;
            }

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "update: broadcast transaction result " + result);
            }
            // Broadcast the result of the transaction.
            sendBroadcast(intent);
        } finally {
            transaction.detach(this);
            cleanUpIfIdle(serviceId);
        }
    }


    private synchronized void createWakeLock() {
        // Create a new wake lock if we haven't made one yet.
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS Connectivity");
            mWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireWakeLock() {
        // It's okay to double-acquire this because we are not using it
        // in reference-counted mode.
        Log.v(TAG, "mms acquireWakeLock");
        mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        // Don't release the wake lock if it hasn't been created and acquired.
        if (mWakeLock != null && mWakeLock.isHeld()) {
            Log.v(TAG, "mms releaseWakeLock");
            mWakeLock.release();
        }
    }

    protected int beginMmsConnectivity() throws IOException {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
            Log.v(TAG, "beginMmsConnectivity");
        }
        // Take a wake lock so we don't fall asleep before the message is downloaded.
        createWakeLock();

        int result = mConnMgr.startUsingNetworkFeature(
                ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS);

        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE )|| DEBUG) {
            Log.v(TAG, "beginMmsConnectivity: result=" + result);
        }

        switch (result) {
            case PhoneConstants.APN_ALREADY_ACTIVE:
                acquireWakeLock();
                return result;
            case PhoneConstants.APN_REQUEST_STARTED:
                acquireWakeLock();
                boolean reconnect = getResources().getBoolean(R.bool.config_reconnect)
                        || getResources().getBoolean(R.bool.config_retry_always);
                if (reconnect) {
                    mServiceHandler.sendEmptyMessageDelayed(EVENT_MMS_CONNECTIVITY_TIMEOUT,
                            MMS_CONNECTIVITY_DELAY);
                }
                return result;
        }

        throw new IOException("Cannot establish MMS connectivity");
    }

    protected void endMmsConnectivity() {
        try {
            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "endMmsConnectivity");
            }

            // cancel timer for renewal of lease
            mServiceHandler.removeMessages(EVENT_CONTINUE_MMS_CONNECTIVITY);
            if (mConnMgr != null) {
                mConnMgr.stopUsingNetworkFeature(
                        ConnectivityManager.TYPE_MOBILE,
                        Phone.FEATURE_ENABLE_MMS);
            }
        } finally {
            releaseWakeLock();
        }
    }

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        private String decodeMessage(Message msg) {
            if (msg.what == EVENT_QUIT) {
                return "EVENT_QUIT";
            } else if (msg.what == EVENT_CONTINUE_MMS_CONNECTIVITY) {
                return "EVENT_CONTINUE_MMS_CONNECTIVITY";
            } else if (msg.what == EVENT_TRANSACTION_REQUEST) {
                return "EVENT_TRANSACTION_REQUEST";
            } else if (msg.what == EVENT_HANDLE_NEXT_PENDING_TRANSACTION) {
                return "EVENT_HANDLE_NEXT_PENDING_TRANSACTION";
            } else if (msg.what == EVENT_NEW_INTENT) {
                return "EVENT_NEW_INTENT";
            }
            return "unknown message.what";
        }

        private String decodeTransactionType(int transactionType) {
            if (transactionType == Transaction.NOTIFICATION_TRANSACTION) {
                return "NOTIFICATION_TRANSACTION";
            } else if (transactionType == Transaction.RETRIEVE_TRANSACTION) {
                return "RETRIEVE_TRANSACTION";
            } else if (transactionType == Transaction.SEND_TRANSACTION) {
                return "SEND_TRANSACTION";
            } else if (transactionType == Transaction.READREC_TRANSACTION) {
                return "READREC_TRANSACTION";
            }
            return "invalid transaction type";
        }

        /**
         * Handle incoming transaction requests.
         * The incoming requests are initiated by the MMSC Server or by the
         * MMS Client itself.
         */
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "Handling incoming message: " + msg + " = " + decodeMessage(msg));

            Transaction transaction = null;

            switch (msg.what) {
                case EVENT_NEW_INTENT:
                    onNewIntent((Intent)msg.obj, msg.arg1);
                    break;

                case EVENT_QUIT:
                    getLooper().quit();
                    return;

                case EVENT_CONTINUE_MMS_CONNECTIVITY:
                    synchronized (mProcessing) {
                        if (mProcessing.isEmpty() && mPending.isEmpty()) {
                            return;
                        }
                    }

                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "handle EVENT_CONTINUE_MMS_CONNECTIVITY event...");
                    }

                    try {
                        int result = beginMmsConnectivity();
                        if (result != PhoneConstants.APN_ALREADY_ACTIVE) {
                            Log.v(TAG, "Extending MMS connectivity returned " + result +
                                    " instead of APN_ALREADY_ACTIVE");
                            // Just wait for connectivity startup without
                            // any new request of APN switch.
                            return;
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Attempt to extend use of MMS connectivity failed");
                        return;
                    }

                    // Restart timer
                    renewMmsConnectivity();
                    return;

                case EVENT_TRANSACTION_REQUEST:
                    int serviceId = msg.arg1;
                    try {
                        TransactionBundle args = (TransactionBundle) msg.obj;
                        TransactionSettings transactionSettings;

                        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                            Log.v(TAG, "EVENT_TRANSACTION_REQUEST MmscUrl=" +
                                    args.getMmscUrl() + " proxy port: " + args.getProxyAddress());
                        }

                        // Set the connection settings for this transaction.
                        // If these have not been set in args, load the default settings.
                        String mmsc = args.getMmscUrl();
                        if (mmsc != null) {
                            transactionSettings = new TransactionSettings(
                                    mmsc, args.getProxyAddress(), args.getProxyPort());
                        } else {
                            transactionSettings = new TransactionSettings(
                                                    TransactionService.this, null);
                        }

                        int transactionType = args.getTransactionType();

                        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                            Log.v(TAG, "handle EVENT_TRANSACTION_REQUEST: transactionType=" +
                                    transactionType + " " + decodeTransactionType(transactionType));
                        }

                        // Create appropriate transaction
                        switch (transactionType) {
                            case Transaction.NOTIFICATION_TRANSACTION:
                                String uri = args.getUri();
                                if (uri != null) {
                                    transaction = new NotificationTransaction(
                                            TransactionService.this, serviceId,
                                            transactionSettings, uri);
                                } else {
                                    // Now it's only used for test purpose.
                                    byte[] pushData = args.getPushData();
                                    PduParser parser = new PduParser(pushData);
                                    GenericPdu ind = parser.parse();

                                    int type = PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
                                    if ((ind != null) && (ind.getMessageType() == type)) {
                                        transaction = new NotificationTransaction(
                                                TransactionService.this, serviceId,
                                                transactionSettings, (NotificationInd) ind);
                                    } else {
                                        Log.e(TAG, "Invalid PUSH data.");
                                        transaction = null;
                                        return;
                                    }
                                }
                                break;
                            case Transaction.RETRIEVE_TRANSACTION:
                                transaction = new RetrieveTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            case Transaction.SEND_TRANSACTION:
                                transaction = new SendTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            case Transaction.READREC_TRANSACTION:
                                transaction = new ReadRecTransaction(
                                        TransactionService.this, serviceId,
                                        transactionSettings, args.getUri());
                                break;
                            default:
                                Log.w(TAG, "Invalid transaction type: " + serviceId);
                                transaction = null;
                                return;
                        }

                        if (!processTransaction(transaction)) {
                            transaction = null;
                            return;
                        }

                        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                            Log.v(TAG, "Started processing of incoming message: " + msg);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Exception occurred while handling message: " + msg, ex);

                        if (transaction != null) {
                            try {
                                transaction.detach(TransactionService.this);
                                if (mProcessing.contains(transaction)) {
                                    synchronized (mProcessing) {
                                        mProcessing.remove(transaction);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "Unexpected Throwable.", t);
                            } finally {
                                // Set transaction to null to allow stopping the
                                // transaction service.
                                transaction = null;
                            }
                        }
                    } finally {
                        if (transaction == null) {
                            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                                Log.v(TAG, "Transaction was null. Stopping self: " + serviceId);
                            }

                            launchRetryAttempt++;
                            if (launchRetryAttempt <= maxLaunchRetryAttempts) {
                                Log.d(TAG, "launchTransaction retry attempt - "
                                        + launchRetryAttempt);
                                TransactionBundle args = (TransactionBundle) msg.obj;
                                sleep(5*1000);
                                launchTransaction(serviceId, args, false);
                            } else {
                                Log.e(TAG, "Multiple launchTransaction retries failed");
                                launchRetryAttempt = 0;
                                decRefCount();

                            }
                        }
                    }
                    return;
                case EVENT_HANDLE_NEXT_PENDING_TRANSACTION:
                    processPendingTransaction(transaction, (TransactionSettings) msg.obj);
                    return;
                case EVENT_MMS_CONNECTIVITY_TIMEOUT:
                    removeMessages(EVENT_MMS_CONNECTIVITY_TIMEOUT);
                    mMmsConnecvivityRetryCount++;
                    if (mMmsConnecvivityRetryCount > MMS_CONNECTIVITY_RETRY_TIMES) {
                        Log.d(TAG, "MMS_CONNECTIVITY_TIMEOUT");
                        mMmsConnecvivityRetryCount = 0;
                        if (getResources().getBoolean(R.bool.config_retry_always)
                                && !mPending.isEmpty()) {
                            abortPendingTransaction(true);
                        }
                        return;
                    }

                    if (!mPending.isEmpty()) {
                        try {
                            beginMmsConnectivity();
                        } catch (IOException e) {
                            Log.w(TAG, "Attempt to use of MMS connectivity failed");
                            return;
                        }
                    }
                    return;
                default:
                    Log.w(TAG, "what=" + msg.what);
                    return;
            }
        }

        public void markAllPendingTransactionsAsFailed() {
            synchronized (mProcessing) {
                while (mPending.size() != 0) {
                    Transaction transaction = mPending.remove(0);
                    transaction.mTransactionState.setState(TransactionState.FAILED);
                    if (transaction instanceof SendTransaction) {
                        Uri uri = ((SendTransaction)transaction).mSendReqURI;
                        transaction.mTransactionState.setContentUri(uri);
                        int respStatus = PduHeaders.RESPONSE_STATUS_ERROR_NETWORK_PROBLEM;
                        ContentValues values = new ContentValues(1);
                        values.put(Mms.RESPONSE_STATUS, respStatus);

                        SqliteWrapper.update(TransactionService.this,
                                TransactionService.this.getContentResolver(),
                                uri, values, null, null);
                    }
                    transaction.notifyObservers();
                }
            }
        }

        void sleep(int ms) {
            try {
                Log.d(TAG, "Sleeping for "+ms+"(ms)...");
                Thread.currentThread().sleep(ms);
                Log.d(TAG, "Sleeping...Done!");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void processPendingTransaction(Transaction transaction,
                                               TransactionSettings settings) {

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                Log.v(TAG, "processPendingTxn: transaction=" + transaction);
            }

            int numProcessTransaction = 0;
            synchronized (mProcessing) {
                if (mPending.size() != 0) {
                    transaction = mPending.remove(0);
                }
                numProcessTransaction = mProcessing.size();
            }

            if (transaction != null) {
                if (settings != null) {
                    transaction.setConnectionSettings(settings);
                }

                /*
                 * Process deferred transaction
                 */
                try {
                    int serviceId = transaction.getServiceId();

                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                        Log.v(TAG, "processPendingTxn: process " + serviceId);
                    }

                    if (processTransaction(transaction)) {
                        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                            Log.v(TAG, "Started deferred processing of transaction  "
                                    + transaction);
                        }
                    } else {
                        transaction = null;
                    }
                } catch (IOException e) {
                    Log.w(TAG, e.getMessage(), e);
                }
            } else {
                if (numProcessTransaction == 0) {
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                        Log.v(TAG, "processPendingTxn: no more transaction, endMmsConnectivity");
                    }
                    endMmsConnectivity();
                }
            }
        }

        /**
         * Internal method to begin processing a transaction.
         * @param transaction the transaction. Must not be {@code null}.
         * @return {@code true} if process has begun or will begin. {@code false}
         * if the transaction should be discarded.
         * @throws IOException if connectivity for MMS traffic could not be
         * established.
         */
        private boolean processTransaction(Transaction transaction) throws IOException {
            // Check if transaction already processing
            synchronized (mProcessing) {
                for (Transaction t : mPending) {
                    if (t.isEquivalent(transaction)) {
                        Log.d(TAG, "Transaction already pending: " +
                                transaction.getServiceId());
                        decRefCount();
                        return true;
                    }
                }
                for (Transaction t : mProcessing) {
                    if (t.isEquivalent(transaction)) {
                        Log.d(TAG, "Duplicated transaction: " + transaction.getServiceId());
                        decRefCount();
                        return true;
                    }
                }

                /*
                * Make sure that the network connectivity necessary
                * for MMS traffic is enabled. If it is not, we need
                * to defer processing the transaction until
                * connectivity is established.
                */
                Log.d(TAG, "processTransaction: call beginMmsConnectivity...");

                int connectivityResult = beginMmsConnectivity();
                if (connectivityResult == PhoneConstants.APN_REQUEST_STARTED) {
                    mPending.add(transaction);
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "processTransaction: connResult=APN_REQUEST_STARTED, " +
                                "defer transaction pending MMS connectivity");
                    }
                    return true;
                }

                Log.d(TAG, "Adding transaction to 'mProcessing' list: " + transaction);
                mProcessing.add(transaction);
            }

            // Set a timer to keep renewing our "lease" on the MMS connection
            sendMessageDelayed(obtainMessage(EVENT_CONTINUE_MMS_CONNECTIVITY),
                    APN_EXTENSION_WAIT);

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "processTransaction: starting transaction " + transaction);
            }

            // Attach to transaction and process it
            transaction.attach(TransactionService.this);
            transaction.process();
            return true;
        }

        public void removePendingTransactionsOnNoConnectivity(boolean retry) {
            Log.d(TAG, "removePendingTransactionsOnNoConnectivity entry isRetry:" + retry);
            // Check if transaction already processing
            synchronized (mProcessing) {
                while (mPending.size() != 0) {
                    Transaction transaction = mPending.remove(0);
                    transaction.mTransactionState.setState(TransactionState.FAILED);
                    decRefCount();
                    Uri uri = getTransactionUri(transaction);
                    // Get uri from transaction
                    if (uri == null) {
                        continue;
                    }
                    // Set Error Type to MMS_PROTO_TRANSIENT
                    transaction.mTransactionState.setContentUri(uri);
                    if (retry) {
                        transaction.notifyObservers();
                    } else if (getResources().getBoolean(R.bool.config_manual_resend)) {
                        updateMsgErrorType(uri, MmsSms.ERR_TYPE_MMS_PROTO_TRANSIENT);
                    }
                }
            }
        }
    }

    private void renewMmsConnectivity() {
        // Set a timer to keep renewing our "lease" on the MMS connection
        mServiceHandler.sendMessageDelayed(
                mServiceHandler.obtainMessage(EVENT_CONTINUE_MMS_CONNECTIVITY),
                           APN_EXTENSION_WAIT);
    }

    private Uri getTransactionUri(Transaction transaction) {
        Uri uri = null;
        // Get uri from transaction
        if (transaction instanceof SendTransaction) {
            uri = ((SendTransaction)transaction).mSendReqURI;
        } else if (transaction instanceof NotificationTransaction) {
            uri = ((NotificationTransaction)transaction).getUri();
        } else if (transaction instanceof RetrieveTransaction) {
            uri = ((RetrieveTransaction)transaction).getUri();
        }
        return uri;
    }

    private void abortPendingTransaction(boolean retry) {
        Transaction transaction = mPending.get(0);
        Uri uri = getTransactionUri(transaction);
        if (uri == null) {
            return;
        }

        String msgId = uri.getLastPathSegment();
        boolean first = getRetryIndex(msgId) ==  0;

        int type = transaction.getType();
        if (type == Transaction.SEND_TRANSACTION) {
            mToastHandler.sendEmptyMessage(first ?
                TOAST_MSG_QUEUED : TOAST_SEND_FAILED_RETRY);
        } else if ((type == Transaction.RETRIEVE_TRANSACTION) ||
            (type == Transaction.NOTIFICATION_TRANSACTION)) {
            mToastHandler.sendEmptyMessage(first ?
                TOAST_DOWNLOAD_LATER : TOAST_DOWNLOAD_FAILED_RETRY);
        }
        mServiceHandler.removePendingTransactionsOnNoConnectivity(retry);
        endMmsConnectivity();
    }

    private class ConnectivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.w(TAG, "ConnectivityBroadcastReceiver.onReceive() action: " + action);
            }

            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                return;
            }

            NetworkInfo mmsNetworkInfo = null;

            boolean enableMmsData = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_setup_mms_data);
            if (mConnMgr != null && (mConnMgr.getMobileDataEnabled() || enableMmsData)) {
                mmsNetworkInfo = mConnMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_MMS);
            } else {
                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                    Log.v(TAG, "mConnMgr is null, bail");
                }
            }

            /*
             * If we are being informed that connectivity has been established
             * to allow MMS traffic, then proceed with processing the pending
             * transaction, if any.
             */

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                Log.v(TAG, "Handle ConnectivityBroadcastReceiver.onReceive(): " + mmsNetworkInfo);
            }

            // Check availability of the mobile network.
            if (mmsNetworkInfo == null) {
                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || DEBUG) {
                    Log.v(TAG, "mms type is null or mobile data is turned off, bail");
                }
                if (mProcessing.isEmpty() && !mPending.isEmpty()) {
                    abortPendingTransaction(false);
                }
            } else {
                // This is a very specific fix to handle the case where the phone receives an
                // incoming call during the time we're trying to setup the mms connection.
                // When the call ends, restart the process of mms connectivity.
                if (Phone.REASON_VOICE_CALL_ENDED.equals(mmsNetworkInfo.getReason())) {
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "   reason is " + Phone.REASON_VOICE_CALL_ENDED +
                                ", retrying mms connectivity");
                    }
                    renewMmsConnectivity();
                    return;
                }

                if (mmsNetworkInfo.isConnected()) {
                    TransactionSettings settings = new TransactionSettings(
                            TransactionService.this, mmsNetworkInfo.getExtraInfo());
                    // If this APN doesn't have an MMSC, mark everything as failed and bail.
                    if (TextUtils.isEmpty(settings.getMmscUrl())) {
                        Log.v(TAG, "   empty MMSC url, bail");
                        mToastHandler.sendEmptyMessage(TOAST_NO_APN);
                        mServiceHandler.markAllPendingTransactionsAsFailed();
                        endMmsConnectivity();
                        return;
                    }

                    Message msg = mServiceHandler.obtainMessage(
                            EVENT_HANDLE_NEXT_PENDING_TRANSACTION,
                            settings);
                    mServiceHandler.sendMessage(msg);
                } else {
                    if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "   TYPE_MOBILE_MMS not connected, bail");
                    }

                    // Retry mms connectivity once it's possible to connect
                    if (mmsNetworkInfo.isAvailable()) {
                        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                            Log.v(TAG, "   retrying mms connectivity for it's available");
                        }
                        renewMmsConnectivity();
                    } else {
                        if (!mPending.isEmpty()) {
                            abortPendingTransaction(true);
                        }
                    }
                }
            }
        }
    };
}
