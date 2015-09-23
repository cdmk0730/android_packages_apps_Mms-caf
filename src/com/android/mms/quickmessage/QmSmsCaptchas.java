/*
 * Copyright (C) 2015 The MoKee OpenSource Project
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

package com.android.mms.quickmessage;

import com.android.mms.transaction.SmsReceiverService;
import com.android.mms.R;

import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

public class QmSmsCaptchas extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();

        if (extras == null) {
            // We have nothing, abort
            return;
        }

        String captchas = extras.getString("captchas");
        long threadId = extras.getLong("threadId", -1);
        if (!TextUtils.isEmpty(captchas)) {
            ClipboardManager clipboardManager = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboardManager.setText(captchas);
            Toast.makeText(context, String.format(context.getString(R.string.captchas_has_copied), captchas), Toast.LENGTH_SHORT).show();

            // MarkRead
            Intent mrIntent = new Intent();
            mrIntent.setClass(context, QmMarkRead.class);
            mrIntent.putExtra(QmMarkRead.SMS_THREAD_ID, threadId);
            context.sendBroadcast(mrIntent);
        }
    }
}
