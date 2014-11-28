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

import java.io.IOException;

import qr.cloud.db.ContentProviderAuthority;
import qr.cloud.qrpedia.BookmarksListFragment.OnBookmarkSelectedListener;
import qr.cloud.qrpedia.SavedTextListFragment.OnSavedTextSelectedListener;
import qr.cloud.util.BackDetectorRelativeLayout;
import qr.cloud.util.LocationRetriever;
import qr.cloud.util.LocationRetriever.LocationResult;
import qr.cloud.util.QRCloudDatabase;
import qr.cloud.util.QRCloudProvider;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.beoui.geocell.GeocellUtils;
import com.beoui.geocell.model.Point;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;
import com.google.cloud.backend.android.CloudBackendSherlockFragmentActivity;
import com.google.cloud.backend.android.CloudCallbackHandler;
import com.google.cloud.backend.android.CloudEntity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.result.ParsedResultType;

public class MessageEditorActivity extends CloudBackendSherlockFragmentActivity implements OnSavedTextSelectedListener,
		OnBookmarkSelectedListener, GooglePlayServicesClient.ConnectionCallbacks,
		GooglePlayServicesClient.OnConnectionFailedListener {

	public static final int ADD_MESSAGE_REQUEST = 2744; // (argh)

	EditText mMessageText;
	View mMessageSubmitProgress;

	int mMaxMessageLength;
	long mMessagePostStartTime;

	String mCodeHash;
	BarcodeFormat mBarcodeFormat = BarcodeFormat.UPC_A; // default to products
	ParsedResultType mBarcodeType = ParsedResultType.TEXT; // default to text

	Location mLocation;
	LocationClient mLocationClient; // use the Google Play Services location client by default
	boolean mGooglePlayLocationConnected = false; // not available until initialised
	LocationRetriever mLocationListener; // fall back to manual (but inaccurate) calculation if necessary
	MenuItem mLocationButtonItem;
	RotateAnimation mRotateAnimation;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_message_editor);

		// get the code hash
		final Intent launchIntent = getIntent();
		if (launchIntent != null) {
			final String hashCode = launchIntent.getStringExtra(QRCloudUtils.DATABASE_PROP_HASH);
			if (hashCode != null) {
				mCodeHash = hashCode;
				try {
					mBarcodeFormat = BarcodeFormat.valueOf(launchIntent
							.getStringExtra(QRCloudUtils.DATABASE_PROP_FORMAT));
				} catch (IllegalArgumentException e) {
				}
				try {
					mBarcodeType = ParsedResultType.valueOf(launchIntent
							.getStringExtra(QRCloudUtils.DATABASE_PROP_TYPE));
				} catch (IllegalArgumentException e) {
				}
			}
		}
		if (mCodeHash == null) {
			setResult(Activity.RESULT_CANCELED);
			finish();
		}

		// load saved location
		if (savedInstanceState != null) {
			double savedLat = savedInstanceState.getDouble(QRCloudUtils.DATABASE_PROP_LATITUDE);
			double savedLon = savedInstanceState.getDouble(QRCloudUtils.DATABASE_PROP_LONGITUDE);
			if (savedLat != 0.0d && savedLon != 0.0d) {
				mLocation = new Location(QRCloudUtils.DATABASE_PROP_GEOCELL); // just need any string to initialise
				mLocation.setLatitude(savedLat);
				mLocation.setLongitude(savedLon);
			}
		}

		// load Google Play Services location client
		mLocationClient = new LocationClient(MessageEditorActivity.this, MessageEditorActivity.this,
				MessageEditorActivity.this);

		// set up tabs and action bar hints
		ActionBar actionBar = getSupportActionBar();
		actionBar.setDisplayShowTitleEnabled(true);
		actionBar.setDisplayHomeAsUpEnabled(true);

		mMaxMessageLength = getResources().getInteger(R.integer.max_message_characters);
		actionBar.setTitle(getString(R.string.title_message_editor, mMaxMessageLength));

		BackDetectorRelativeLayout.setSearchActivity(this);
		mMessageText = (EditText) findViewById(R.id.message_text);
		mMessageText.setTypeface(Typefaces.get(MessageEditorActivity.this, getString(R.string.default_font)));
		mMessageSubmitProgress = findViewById(R.id.message_submit_progress);

		// add a custom view for the send button
		actionBar.setDisplayShowCustomEnabled(true);
		actionBar.setCustomView(R.layout.post_view);
		((ImageView) actionBar.getCustomView().findViewById(R.id.post_message))
				.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						postMessage();
					}
				});

		// set up animation for the location button
		mRotateAnimation = new RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF,
				0.5f);
		mRotateAnimation.setDuration(600);
		mRotateAnimation.setRepeatCount(Animation.INFINITE);
		mRotateAnimation.setRepeatMode(Animation.RESTART);
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		if (mLocation != null) {
			savedInstanceState.putDouble(QRCloudUtils.DATABASE_PROP_LATITUDE, mLocation.getLatitude());
			savedInstanceState.putDouble(QRCloudUtils.DATABASE_PROP_LONGITUDE, mLocation.getLongitude());
		}
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (mLocationClient != null) {
			mLocationClient.connect(); // register for location updates
		}
	}

	@Override
	protected void onStop() {
		// disconnect and invalidate the location client
		if (mLocationClient != null) {
			mLocationClient.disconnect();
		}
		super.onStop();
	}

	@Override
	protected void onResume() {
		super.onResume();

		// check we have internet access
		if (!QRCloudUtils.internetAvailable(MessageEditorActivity.this)) {
			Toast.makeText(MessageEditorActivity.this,
					getString(R.string.no_internet_hint, getString(R.string.no_internet_task_post)), Toast.LENGTH_SHORT)
					.show();
		}

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
	protected void onDestroy() {
		mRefreshHandler.removeCallbacks(mRefreshRunnableWithError);
		mRefreshHandler.removeCallbacks(mRefreshRunnableNoError);
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		if (!mMessageText.isEnabled()) {
			if (System.currentTimeMillis() - mMessagePostStartTime > getResources().getInteger(
					R.integer.message_post_timeout)) {
				AlertDialog.Builder builder = new AlertDialog.Builder(MessageEditorActivity.this);
				builder.setTitle(R.string.message_posting_cancel_title);
				builder.setMessage(getString(R.string.message_posting_cancel_message,
						getString(R.string.no_internet_task_view)));
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setPositiveButton(R.string.message_posting_cancel_wait, null);
				builder.setNegativeButton(R.string.message_posting_cancel_stop, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						// TODO: this doesn't actually cancel sending, so we still get the toast when it fails properly
						messagePostingError();
					}
				});
				builder.show();
			}
			return; // we're submitting a message; don't go back
		}
		hideKeyboard();
		if (mMessageText.length() > 0 && mMessageText.getText().toString().trim().length() > 0) {
			AlertDialog.Builder builder = new AlertDialog.Builder(MessageEditorActivity.this);
			builder.setTitle(R.string.new_message);
			builder.setMessage(R.string.save_message);
			builder.setIcon(android.R.drawable.ic_dialog_alert);
			builder.setNegativeButton(R.string.btn_discard, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					setResult(Activity.RESULT_CANCELED);
					finish();
				}
			});
			builder.setPositiveButton(R.string.btn_save, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					postMessage();
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
		if (QRCloudUtils.actionBarIsSplit(MessageEditorActivity.this)) {
			if (mLocation == null) {
				getSupportMenuInflater().inflate(R.menu.activity_message_editor_location_off, menu);
			} else {
				getSupportMenuInflater().inflate(R.menu.activity_message_editor_location_on, menu);
			}
			getSupportMenuInflater().inflate(R.menu.activity_message_editor, menu);
		} else {
			getSupportMenuInflater().inflate(R.menu.activity_message_editor, menu);
			if (mLocation != null) {
				menu.findItem(R.id.menu_add_location).setVisible(false);
				menu.findItem(R.id.menu_remove_location).setVisible(true);
			}
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (!mMessageText.isEnabled()) {
			return true; // we're submitting a message; don't do any of these actions
		}
		switch (item.getItemId()) {
			case android.R.id.home:
				onBackPressed();
				return true;

			case R.id.menu_add_location:
			case R.id.menu_remove_location:
				// save that we want or do not want location automatically from now on
				SharedPreferences.Editor preferencesEditor = getCloudBackend().getSharedPreferences().edit();
				preferencesEditor.putBoolean(getString(R.string.pref_use_location), mLocation == null);
				preferencesEditor.commit();
				if (mLocation == null) {
					getLocation(item);
				} else {
					mLocation = null;
					supportInvalidateOptionsMenu();
				}
				return true;

			case R.id.menu_import_link:
				if (QRCloudDatabase.getBookmarksCount(getContentResolver()) > 0) {
					BookmarksListDialogFragment bookmarkFragment = new BookmarksListDialogFragment();
					bookmarkFragment.show(getSupportFragmentManager(), BookmarksListDialogFragment.FRAGMENT_TAG);
				} else {
					Toast.makeText(MessageEditorActivity.this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show();
				}
				return true;

			case R.id.menu_import_clipping:
				if (QRCloudDatabase.getSavedMessageCount(getContentResolver()) > 0) {
					SavedTextListDialogFragment savedTextFragment = new SavedTextListDialogFragment();
					savedTextFragment.show(getSupportFragmentManager(), SavedTextListDialogFragment.FRAGMENT_TAG);
				} else {
					Toast.makeText(MessageEditorActivity.this, R.string.no_saved_messages_toast, Toast.LENGTH_SHORT)
							.show();
				}
				return true;

			default:
				return super.onOptionsItemSelected(item);
		}
	}

	// handler and runnable for refreshing the location tab
	Handler mRefreshHandler = new Handler();
	Runnable mRefreshRunnableWithError = new Runnable() {
		@Override
		public void run() {
			if (mLocation == null) {
				// couldn't find the location - error
				Toast.makeText(MessageEditorActivity.this, R.string.no_location, Toast.LENGTH_SHORT).show();
			}
			setRefreshIcon(null);
			supportInvalidateOptionsMenu();
		}
	};
	Runnable mRefreshRunnableNoError = new Runnable() {
		@Override
		public void run() {
			setRefreshIcon(null);
			supportInvalidateOptionsMenu();
		}
	};

	private void setRefreshIcon(MenuItem item) {
		if (item != null) {
			mLocationButtonItem = item;
			mLocationButtonItem.setActionView(R.layout.refresh_button);
			ImageView refreshIcon = (ImageView) mLocationButtonItem.getActionView().findViewById(R.id.refresh_button);
			refreshIcon.startAnimation(mRotateAnimation);
		} else if (mLocationButtonItem != null) {
			ImageView refreshIcon = (ImageView) mLocationButtonItem.getActionView().findViewById(R.id.refresh_button);
			refreshIcon.clearAnimation();
			mRotateAnimation.cancel();
			mLocationButtonItem.setActionView(null);
			mLocationButtonItem = null;
		}
	}

	private void updateLocationIfNecessary() {
		if (mLocation == null) {
			// see whether to automatically add the location
			SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
			boolean useLocation = true;
			try {
				useLocation = sharedPreferences.getBoolean(getString(R.string.pref_use_location), true);
			} catch (ClassCastException e) {
			}
			if (useLocation) {
				getLocation(null); // use the non-interface version of getLocation
			}
		}
	}

	private void getLocation(MenuItem item) {
		final boolean showInterfaceAndErrors = item != null; // pass null to show no interface, or the item otherwise
		LocationResult locationResult = new LocationResult() {
			@Override
			public void gotLocation(Location location) {
				mLocation = location;
				mRefreshHandler.removeCallbacks(mRefreshRunnableWithError);
				mRefreshHandler.removeCallbacks(mRefreshRunnableNoError);
				mRefreshHandler.post(showInterfaceAndErrors ? mRefreshRunnableWithError : mRefreshRunnableNoError);
			}
		};

		if (mGooglePlayLocationConnected) {
			Location location = mLocationClient.getLastLocation();
			if (location != null) {
				locationResult.gotLocation(location);
				return;
			}
		}
		if (mLocationListener == null) {
			mLocationListener = new LocationRetriever();
		}
		if (mLocationListener.getLocation(MessageEditorActivity.this, locationResult)) {
			if (showInterfaceAndErrors) {
				setRefreshIcon(item);
			}
		} else {
			if (showInterfaceAndErrors) {
				// no location source enabled - show message with settings dialog
				AlertDialog.Builder builder = new AlertDialog.Builder(MessageEditorActivity.this);
				builder.setTitle(R.string.no_location_title);
				builder.setMessage(R.string.no_location_message);
				builder.setIcon(android.R.drawable.ic_dialog_alert);
				builder.setNegativeButton(R.string.btn_cancel, null);
				builder.setPositiveButton(R.string.btn_settings, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int whichButton) {
						startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
					}
				});
				builder.show();
			}
		}
	}

	@Override
	public void onSavedTextSelected(long itemId) {
		// load from the database and add to the existing text
		mMessageText.append(QRCloudDatabase.getMessageById(getContentResolver(), itemId));

		// close the dialog
		SavedTextListDialogFragment dialogFragment = (SavedTextListDialogFragment) getSupportFragmentManager()
				.findFragmentByTag(SavedTextListDialogFragment.FRAGMENT_TAG);
		if (dialogFragment != null) {
			dialogFragment.dismiss();
		}

		mMessageText.requestFocus();
	}

	@Override
	public void onBookmarkSelected(long itemId) {
		// load from the database and add to the existing text
		mMessageText.append(QRCloudDatabase.getURLByBookmarkId(getContentResolver(), itemId));

		// close the dialog
		BookmarksListDialogFragment dialogFragment = (BookmarksListDialogFragment) getSupportFragmentManager()
				.findFragmentByTag(BookmarksListDialogFragment.FRAGMENT_TAG);
		if (dialogFragment != null) {
			dialogFragment.dismiss();
		}

		mMessageText.requestFocus();
	}

	private void postMessage() {
		final String messageText = mMessageText.getText().toString();
		if (TextUtils.isEmpty(messageText)) {
			return;
		}
		if (messageText.trim().length() <= 0) {
			return;
		}

		mMessagePostStartTime = System.currentTimeMillis();
		mMessageText.setEnabled(false);
		getSupportActionBar().getCustomView().findViewById(R.id.post_message).setEnabled(false);
		mMessageSubmitProgress.setVisibility(View.VISIBLE);

		// create a CloudEntity with the new post
		CloudEntity newPost = new CloudEntity(QRCloudUtils.DATABASE_KIND_MESSAGES);
		newPost.put(QRCloudUtils.DATABASE_PROP_HASH, mCodeHash);
		newPost.put(QRCloudUtils.DATABASE_PROP_MESSAGE, messageText);
		if (mLocation != null) {
			// geohash/geocell - see: http://stackoverflow.com/a/1096744/1993220
			newPost.put(QRCloudUtils.DATABASE_PROP_LATITUDE, mLocation.getLatitude());
			newPost.put(QRCloudUtils.DATABASE_PROP_LONGITUDE, mLocation.getLongitude());
			newPost.put(QRCloudUtils.DATABASE_PROP_GEOCELL, GeocellUtils.compute(new Point(mLocation.getLatitude(),
					mLocation.getLongitude()), QRCloudUtils.GEOCELL_STORED_PRECISION));
		}
		newPost.put(QRCloudUtils.DATABASE_PROP_COUNTRY,
				((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE)).getSimCountryIso());
		newPost.put(QRCloudUtils.DATABASE_PROP_RATING, 0);
		newPost.put(QRCloudUtils.DATABASE_PROP_REPORTED, false);
		newPost.put(QRCloudUtils.DATABASE_PROP_SOURCE, ContentProviderAuthority.DB_SOURCE);

		// create a response handler that will receive the result or an error
		CloudCallbackHandler<CloudEntity> handler = new CloudCallbackHandler<CloudEntity>() {
			@Override
			public void onComplete(final CloudEntity result) {
				Intent intent = new Intent();
				intent.putExtra(QRCloudUtils.DATABASE_PROP_MESSAGE, mMessageText.getText().toString());
				intent.putExtra(QRCloudUtils.DATABASE_PROP_GEOCELL, mLocation != null); // whether we were geotagged
				setResult(Activity.RESULT_OK, intent);
				mMessageSubmitProgress.setVisibility(View.GONE);

				// insert into the "my tags" database
				ContentValues messageData = new ContentValues();
				messageData.put(QRCloudDatabase.COL_HASH, mCodeHash);
				messageData.put(QRCloudDatabase.COL_FORMAT, mBarcodeFormat.name());
				messageData.put(QRCloudDatabase.COL_TYPE, mBarcodeType.name());
				messageData.put(QRCloudDatabase.COL_MESSAGE, messageText);
				messageData.put(QRCloudDatabase.COL_DATE, System.currentTimeMillis());
				getContentResolver().insert(QRCloudProvider.CONTENT_URI_TAGS, messageData);

				finish();
			}

			@Override
			public void onError(final IOException exception) {
				messagePostingError();
			}
		};

		// execute the insertion with the handler
		getCloudBackend().insert(newPost, handler);
	}

	private void messagePostingError() {
		mMessageSubmitProgress.setVisibility(View.GONE);
		mMessageText.setEnabled(true);
		getSupportActionBar().getCustomView().findViewById(R.id.post_message).setEnabled(true);
		Toast.makeText(MessageEditorActivity.this, R.string.post_message_failure, Toast.LENGTH_SHORT).show();
	}

	/*
	 * Called by Location Services when the request to connect the client finishes successfully. At this point, you can
	 * request the current location or start periodic updates
	 */
	@Override
	public void onConnected(Bundle dataBundle) {
		mGooglePlayLocationConnected = true;
		updateLocationIfNecessary();
	}

	/*
	 * Called by Location Services if the attempt to Location Services fails.
	 */
	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		// couldn't connect to Play Services to get the location
		// for better handling of this, see: http://developer.android.com/training/location/retrieve-current.html
		mGooglePlayLocationConnected = false;
		updateLocationIfNecessary();
	}

	/*
	 * Called by Location Services if the connection to the location client is disconnected or drops because of an
	 * error.
	 */
	@Override
	public void onDisconnected() {
		mGooglePlayLocationConnected = false;
	}

	private TextWatcher mTextWatcher = new TextWatcher() {
		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
			getSupportActionBar().setTitle(getString(R.string.title_message_editor, (mMaxMessageLength - s.length())));
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void afterTextChanged(Editable s) {
		}
	};
}
