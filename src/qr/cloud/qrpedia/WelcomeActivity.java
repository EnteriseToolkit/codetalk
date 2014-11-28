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

import qr.cloud.locale.QRpediaApplication;
import qr.cloud.util.Typefaces;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.cloud.backend.android.CloudBackendSherlockFragmentActivity;

public class WelcomeActivity extends CloudBackendSherlockFragmentActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// if they've already logged in then we skip this screen
		if (getPreferencesAccountName() != null) {
			startActivity(new Intent(WelcomeActivity.this, ScannerActivity.class));
			finish();
			return;
		}

		// otherwise we show the welcome screen
		setContentView(R.layout.activity_welcome);

		Typeface defaultFont = Typefaces.get(WelcomeActivity.this, getString(R.string.default_font));
		((TextView) findViewById(R.id.welcome_title)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.welcome_message)).setTypeface(defaultFont);

		TextView languageSwitcher = (TextView) findViewById(R.id.welcome_switch_language);
		languageSwitcher.setTypeface(Typefaces.get(WelcomeActivity.this, getString(R.string.default_font_bold)));
		languageSwitcher.setPaintFlags(languageSwitcher.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
		languageSwitcher.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				((QRpediaApplication) getApplication()).toggleEnglishWelsh(WelcomeActivity.this);
			}
		});

		Button logInButton = (Button) findViewById(R.id.btn_log_in);
		logInButton.setTypeface(Typefaces.get(WelcomeActivity.this, getString(R.string.default_font_bold)));
		logInButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				boolean showError = false;
				int playAvailability = GooglePlayServicesUtil.isGooglePlayServicesAvailable(WelcomeActivity.this);
				if (playAvailability == ConnectionResult.SERVICE_MISSING
						|| playAvailability == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
						|| playAvailability == ConnectionResult.SERVICE_DISABLED) { // install Google Play if necessary
					Dialog playDialog = GooglePlayServicesUtil.getErrorDialog(playAvailability, WelcomeActivity.this,
							REQUEST_PLAY_SERVICES);
					if (playDialog != null) {
						playDialog.show();
					} else {
						showError = true;
					}
				} else if (playAvailability == ConnectionResult.SUCCESS) {
					startActivity(new Intent(WelcomeActivity.this, ScannerActivity.class));
					finish();
				} else {
					showError = true;
				}
				if (showError) {
					Toast.makeText(WelcomeActivity.this, R.string.welcome_install_error, Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	@Override
	public boolean isAuthEnabled() {
		return false; // we don't want to ask for authentication here
	}

	@Override
	public boolean checkPlayServicesEnabled() {
		return false; // don't check play services here so users can read the screen before logging in
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case REQUEST_PLAY_SERVICES:
				int playAvailability = GooglePlayServicesUtil.isGooglePlayServicesAvailable(WelcomeActivity.this);
				if (playAvailability == ConnectionResult.SUCCESS) { // they've now installed Google Play - continue
					startActivity(new Intent(WelcomeActivity.this, ScannerActivity.class));
					finish();
				} else {
					Toast.makeText(WelcomeActivity.this, R.string.welcome_wait_for_install, Toast.LENGTH_SHORT).show();
				}
				break;
			default:
				super.onActivityResult(requestCode, resultCode, data);
		}
	}
}
