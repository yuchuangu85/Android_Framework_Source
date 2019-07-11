/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.ex.chips.sample;

import android.os.Bundle;
import android.text.util.Rfc822Tokenizer;
import android.widget.MultiAutoCompleteTextView;
import android.app.Activity;

import com.android.ex.chips.BaseRecipientAdapter;
import com.android.ex.chips.RecipientEditTextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final RecipientEditTextView emailRetv =
                (RecipientEditTextView) findViewById(R.id.email_retv);
        emailRetv.setTokenizer(new Rfc822Tokenizer());
        emailRetv.setAdapter(new BaseRecipientAdapter(this));

        final RecipientEditTextView phoneRetv =
                (RecipientEditTextView) findViewById(R.id.phone_retv);
        phoneRetv.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());
        phoneRetv.setAdapter(
                new BaseRecipientAdapter(BaseRecipientAdapter.QUERY_TYPE_PHONE, this));
    }

}
