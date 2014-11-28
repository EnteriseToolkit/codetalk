/*
 * Copyright (c) 2014 Simon Robinson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qr.cloud.qrpedia;

import qr.cloud.util.BackDetectorRelativeLayout;
import qr.cloud.util.QRCloudDatabase;
import qr.cloud.util.QRCloudProvider;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

public class SavedTextEditorActivity extends SherlockFragmentActivity {

	EditText mMessageText;
	boolean initialMessageSet;
	int mMaxMessageLength;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_message_editor);

		// set up tabs and action bar hints
		ActionBar actionBar = getSupportActionBar();
		actionBar.setDisplayShowTitleEnabled(true);
		actionBar.setDisplayHomeAsUpEnabled(true);
		actionBar.setDisplayUseLogoEnabled(true);

		BackDetectorRelativeLayout.setSearchActivity(this);
		mMessageText = (EditText) findViewById(R.id.message_text);
		mMessageText.setTypeface(Typefaces.get(SavedTextEditorActivity.this, getString(R.string.default_font)));

		// load an initial message, if requested
		final Intent launchIntent = getIntent();
		if (launchIntent != null) {
			String action = launchIntent.getAction();
			String type = launchIntent.getType();
			if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
				String sharedText = launchIntent.getStringExtra(Intent.EXTRA_TEXT);
				if (sharedText != null) {
					mMessageText.append(sharedText); // no need to check length - EditText does so for us
					initialMessageSet = true;
				}
			} else {
				String initialMessage = launchIntent.getStringExtra(QRCloudUtils.DATABASE_PROP_MESSAGE);
				if (initialMessage != null) {
					mMessageText.append(initialMessage); // no need to check length - EditText does so for us
					initialMessageSet = true;
				}
			}
		}

		// set up the message length in the title bar
		mMaxMessageLength = getResources().getInteger(R.integer.max_message_characters);
		actionBar.setTitle(getString(R.string.title_clipping_editor, (mMaxMessageLength - mMessageText.getText()
				.length())));
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (mMessageText != null) {
			// we want to get notifications when the text is changed (but after adding existing text in onCreate)
			mMessageText.addTextChangedListener(mTextWatcher);
		}
	}

	@Override
	protected void onPause() {
		if (mMessageText != null) {
			// we don't want to get the notification that the text was removed from the window on pause or destroy
			mMessageText.removeTextChangedListener(mTextWatcher);
		}
		super.onPause();
	}

	@Override
	public void onBackPressed() {
		hideKeyboard();
		if (mMessageText.length() > 0 && mMessageText.getText().toString().trim().length() > 0) {
			AlertDialog.Builder builder = new AlertDialog.Builder(SavedTextEditorActivity.this);
			builder.setTitle(R.string.new_message);
			builder.setMessage(R.string.save_message);
			builder.setIcon(android.R.drawable.ic_dialog_alert);
			builder.setNegativeButton(R.string.btn_discard, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					finish();
				}
			});
			builder.setPositiveButton(R.string.btn_save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					saveMessage();
				}
			});
			builder.show();
			return;
		}
		super.onBackPressed();
	}

	private void hideKeyboard() {
		if (mMessageText != null) {
			InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			inputMethodManager.hideSoftInputFromWindow(mMessageText.getWindowToken(), 0);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getSupportMenuInflater().inflate(R.menu.activity_clipping_editor, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				return true;

			case R.id.menu_save_clipping:
				saveMessage();
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	private void saveMessage() {
		String messageText = mMessageText.getText().toString();
		if (TextUtils.isEmpty(messageText)) {
			return;
		}
		if (messageText.trim().length() <= 0) {
			return;
		}

		mMessageText.setEnabled(false);

		// insert into the database
		ContentValues messageData = new ContentValues();
		messageData.put(QRCloudDatabase.COL_MESSAGE, messageText);
		messageData.put(QRCloudDatabase.COL_DATE, System.currentTimeMillis());
		getContentResolver().insert(QRCloudProvider.CONTENT_URI_MESSAGES, messageData);

		if (initialMessageSet) {
			// if we launched from an existing item make sure they know the message was saved
			Toast.makeText(SavedTextEditorActivity.this, R.string.item_added_to_clippings, Toast.LENGTH_SHORT).show();
		}

		finish();
	}

	private TextWatcher mTextWatcher = new TextWatcher() {
		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			getSupportActionBar().setTitle(getString(R.string.title_clipping_editor, (mMaxMessageLength - s.length())));
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void afterTextChanged(Editable s) {
		}
	};
}
