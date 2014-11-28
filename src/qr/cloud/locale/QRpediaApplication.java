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

package qr.cloud.locale;

import java.util.Locale;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.preference.PreferenceManager;

// a custom Application that is able to override the application's language to something not supported by the system
// see: http://stackoverflow.com/a/4239680 - the only non-default language solution that seems to actually work
public class QRpediaApplication extends Application {
	private static final String LOCALE_PREF_KEY = "key_locale_pref";

	private Locale mDefaultLocale;
	private Locale mOverriddenLocale = null;

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (mOverriddenLocale != null && !newConfig.locale.getLanguage().equals(mOverriddenLocale.getLanguage())) {
			updateLocale(newConfig);
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		mDefaultLocale = Locale.getDefault();
		requestLanguageUpdate();
	}

	private void updateLocale(Configuration config) {
		Locale.setDefault(mOverriddenLocale);
		config.locale = mOverriddenLocale;
		getBaseContext().getResources()
				.updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());
	}

	private void requestLanguageUpdate() {
		String preferenceLanguage = getApplicationLanguage();
		Configuration config = getBaseContext().getResources().getConfiguration();
		boolean changed = false;
		if (!"".equals(preferenceLanguage) && !config.locale.getLanguage().equals(preferenceLanguage)) {
			// set to the requested locale if not already set
			mOverriddenLocale = new Locale(preferenceLanguage);
			changed = true;
		} else if (!config.locale.getLanguage().equals(mDefaultLocale.getLanguage())) {
			// otherwise, reset to the system default
			mOverriddenLocale = mDefaultLocale;
			changed = true;
		}
		if (changed) {
			updateLocale(config);
		}
	}

	/**
	 * Get the current language in use from the stored preferences (note: not necessarily the system language)
	 * 
	 * @return
	 */
	public String getApplicationLanguage() {
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		return settings.getString(LOCALE_PREF_KEY, "");
	}

	/**
	 * Set the application's language - use null for system default, "cy" or "cy_GB" for Welsh. Note: the change will
	 * not take effect until the next Activity launch
	 * 
	 * @param newLanguage
	 */
	public void setApplicationLanguage(String newLanguage) {
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
		Editor editor = settings.edit();
		editor.putString(LOCALE_PREF_KEY, newLanguage);
		editor.commit();
		requestLanguageUpdate();
	}

	/**
	 * Toggle between English and Welsh - will finish and restart the given Activity, closing all other activities
	 * (note: beware that passed intents will be lost)
	 * 
	 * @param activity
	 */
	public void toggleEnglishWelsh(Activity activity) {
		String currentLanguage = getApplicationLanguage();
		String newLanguage = "cy_GB";
		if (newLanguage.equals(currentLanguage)) {
			newLanguage = null; // system default
		}
		setApplicationLanguage(newLanguage);

		// need to restart all activities to update to the new language
		activity.finish();
		Intent restartIntent = new Intent(activity, activity.getClass());
		restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		activity.startActivity(restartIntent);
	}
}