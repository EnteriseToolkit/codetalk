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
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import qr.cloud.db.ContentProviderAuthority;
import qr.cloud.qrpedia.CloudEntityListFragment.FragmentInteractionListener;
import qr.cloud.util.LocationRetriever;
import qr.cloud.util.LocationRetriever.LocationResult;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import qr.cloud.util.UPCDatabaseRestClient;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.Log;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.Tab;
import com.actionbarsherlock.app.SherlockFragmentActivity;
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
import com.google.cloud.backend.android.CloudQuery;
import com.google.cloud.backend.android.CloudQuery.Scope;
import com.google.cloud.backend.android.F;
import com.google.cloud.backend.android.F.Op;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.result.ParsedResultType;
import com.loopj.android.http.JsonHttpResponseHandler;

// theme: http://jgilfelt.github.io/android-actionbarstylegenerator/#name=qrpedia&compat=sherlock&theme=light_dark&actionbarstyle=solid&texture=0&hairline=0&backColor=377fd2%2C100&secondaryColor=377fd2%2C100&tabColor=f2f2f2%2C100&tertiaryColor=fff%2C100&accentColor=3d98ff%2C100&cabBackColor=377fd2%2C100&cabHighlightColor=3d98ff%2C100
// (also a good colour: #83b0fe)

// TODO: if there are fewer results than the display limit, don't do a server query (just cache others)
// TODO: on HTC Sensation, first scan after setting account doesn't work
// TODO: save list position when refreshing items
// TODO: remove/refresh item when reporting?
// TODO: pause preview while splash is shown?
// TODO: set empty text fonts in all lists - will need to set a custom empty view though (directly on the listview)
// TODO: show animated gif on first message added to a barcode, and animated +1 when tapping
// TODO: add photo next to item
// TODO: add report review view for certain users
// TODO: fix link click row highlighting

public class MessageViewerActivity extends CloudBackendSherlockFragmentActivity implements FragmentInteractionListener,
		GooglePlayServicesClient.ConnectionCallbacks, GooglePlayServicesClient.OnConnectionFailedListener {

	private static final String TAG = "MessageViewer";

	private static final int NON_URGENT_QUERY_DELAY = 2500; // milliseconds to delay non-ui queries

	TabsAdapter mTabsAdapter;
	ViewPager mViewPager;
	TextView mCodeContents;

	MenuItem mRefreshButtonItem;
	RotateAnimation mRotateAnimation;

	Location mLocation;
	LocationClient mLocationClient; // use the Google Play Services location client by default
	boolean mWaitingForGooglePlayLocation;
	boolean mGooglePlayLocationConnected; // not available until initialised
	LocationRetriever mLocationListener; // fall back to manual (but inaccurate) calculation if necessary
	long mManualLocationRequestTime;
	int mMinimumLocationRefreshWaitTime;
	boolean mLocationTabEnabled = true; // set this to false to prevent pre-loading of the location tab

	// for tracking whether we're allowed to use the app or not
	enum UserState {
		UNKNOWN, CHECKING, ALLOWED, BANNED
	}

	// for tracking whether this is a current or old version of the app
	enum ApplicationState {
		UNKNOWN, CHECKING, OLD, CURRENT
	}

	static UserState mUserState = UserState.UNKNOWN;
	static ApplicationState mApplicationState = ApplicationState.UNKNOWN;

	String mCodeHash = null; // default to no code loaded
	String mProductDetails = null; // for loading product details
	BarcodeFormat mBarcodeFormat = BarcodeFormat.UPC_A; // default to products
	ParsedResultType mBarcodeType = ParsedResultType.TEXT; // default to text

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_message_viewer);

		// set up tabs and collapse the action bar
		ActionBar actionBar = getSupportActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
		actionBar.setDisplayShowTitleEnabled(false);
		if (QRCloudUtils.actionBarIsSplit(MessageViewerActivity.this)) {
			actionBar.setDisplayShowHomeEnabled(false); // TODO: also need to show inverted/rearranged icons
		}

		// load Google Play Services location client
		mWaitingForGooglePlayLocation = true;
		mGooglePlayLocationConnected = false;
		mLocationClient = new LocationClient(MessageViewerActivity.this, MessageViewerActivity.this,
				MessageViewerActivity.this);

		// refresh interval for location queries and load whether location has been updated, and product details
		mMinimumLocationRefreshWaitTime = getResources().getInteger(R.integer.minimum_location_refresh_time);
		if (savedInstanceState != null) {
			mLocationTabEnabled = savedInstanceState.getBoolean(getString(R.string.key_location_tab_visited));
			mProductDetails = savedInstanceState.getString(getString(R.string.key_product_details));
			double savedLat = savedInstanceState.getDouble(QRCloudUtils.DATABASE_PROP_LATITUDE);
			double savedLon = savedInstanceState.getDouble(QRCloudUtils.DATABASE_PROP_LONGITUDE);
			if (savedLat != 0.0d && savedLon != 0.0d) {
				mLocation = new Location(QRCloudUtils.DATABASE_PROP_GEOCELL); // just need any string to initialise
				mLocation.setLatitude(savedLat);
				mLocation.setLongitude(savedLon);
			}
			mManualLocationRequestTime = savedInstanceState.getLong(getString(R.string.key_location_request_time));
			long currentTime = System.currentTimeMillis();
			if (currentTime - mManualLocationRequestTime < LocationRetriever.LOCATION_WAIT_TIME) {
				// we've started but probably not finished getting the location (manual method) - try again
				requestManualLocationAndUpdateTab();
			}
		}

		// get the code hash and details (must be after getting mProductDetails to stop multiple queries)
		final Intent launchIntent = getIntent();
		// Bundle barcodeDetailsBundle = null;
		if (launchIntent != null) {
			final String codeContents = launchIntent.getStringExtra(QRCloudUtils.DATABASE_PROP_CONTENTS);
			if (codeContents != null) {
				// we need the hash for database lookups
				mCodeHash = QRCloudUtils.sha1Hash(codeContents);
				parseCodeDetailsAndUpdate(launchIntent, codeContents, savedInstanceState == null);
			}
		}
		if (mCodeHash == null) {
			finish();
		}

		// set up animation for the refresh button
		mRotateAnimation = new RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF,
				0.5f);
		mRotateAnimation.setDuration(600);
		mRotateAnimation.setRepeatCount(Animation.INFINITE);
		mRotateAnimation.setRepeatMode(Animation.RESTART);

		// set up tab paging
		mViewPager = (ViewPager) findViewById(R.id.message_sort_pager);
		mViewPager.setOffscreenPageLimit(3); // tried to save data, but 1 is the minimum - just pre-cache everything

		// load the tabs (see: http://stackoverflow.com/a/12090317/1993220)
		mTabsAdapter = new TabsAdapter(this, mViewPager);
		mTabsAdapter.addTab(actionBar.newTab().setIcon(R.drawable.ic_action_clock_inverse),
				CloudEntityListFragment.class, getSortBundle(CloudEntity.PROP_CREATED_AT), false);
		mTabsAdapter.addTab(actionBar.newTab().setIcon(R.drawable.ic_action_location_inverse),
				CloudEntityListFragment.class, getSortBundle(QRCloudUtils.DATABASE_PROP_GEOCELL), true);
		mTabsAdapter.addTab(actionBar.newTab().setIcon(R.drawable.ic_action_star_10_inverse),
				CloudEntityListFragment.class, getSortBundle(QRCloudUtils.DATABASE_PROP_RATING), false);
		mTabsAdapter.addTab(actionBar.newTab().setIcon(R.drawable.ic_action_user_inverse),
				CloudEntityListFragment.class,
				getFilterBundle(F.Op.EQ.name(), CloudEntity.PROP_CREATED_BY, getCredentialAccountName()), false);
		// mTabsAdapter
		// .addTab(actionBar.newTab().setIcon(
		// mBarcodeFormat == BarcodeFormat.QR_CODE ? R.drawable.ic_action_qrcode_inverse
		// : R.drawable.ic_action_barcode_inverse), CodeViewerFragment.class, barcodeDetailsBundle);
	}

	private Bundle getSortBundle(String sortProperty) {
		Bundle sortBundle = new Bundle();
		sortBundle.putString(QRCloudUtils.FRAGMENT_SORT_TYPE, sortProperty);
		return sortBundle;
	}

	private Bundle getFilterBundle(String operator, String property, String name) {
		Bundle sortBundle = new Bundle();
		sortBundle.putString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_OPERATOR, operator);
		sortBundle.putString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_PROPERTY, property);
		sortBundle.putString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_VALUE, name);
		return sortBundle;
	}

	// private Bundle getBarcodeDetailsBundle(BarcodeFormat format, int title, String contents, ParsedResultType type) {
	// Bundle sortBundle = new Bundle();
	// sortBundle.putString(QRCloudUtils.DATABASE_PROP_FORMAT, format.name());
	// sortBundle.putInt(getString(R.string.key_display_title), title);
	// sortBundle.putString(getString(R.string.key_display_contents), contents);
	// sortBundle.putString(QRCloudUtils.DATABASE_PROP_TYPE, type.name());
	// return sortBundle;
	// }

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putBoolean(getString(R.string.key_location_tab_visited), mLocationTabEnabled);
		savedInstanceState.putString(getString(R.string.key_product_details), mProductDetails);
		if (mLocation != null) {
			savedInstanceState.putDouble(QRCloudUtils.DATABASE_PROP_LATITUDE, mLocation.getLatitude());
			savedInstanceState.putDouble(QRCloudUtils.DATABASE_PROP_LONGITUDE, mLocation.getLongitude());
		}
		savedInstanceState.putLong(getString(R.string.key_location_request_time), mManualLocationRequestTime);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	protected void onPostCreate() {
		SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
		long banCheckTime = sharedPreferences.getLong(getString(R.string.check_ban_time), 0);
		long versionCheckTime = sharedPreferences.getLong(getString(R.string.check_ban_time), 0);
		boolean questionnaireCompleted = sharedPreferences.getBoolean(
				getString(R.string.check_questionnaire_completed), false);
		int codesScanned = sharedPreferences.getInt(getString(R.string.check_codes_scanned), 0);
		long currentTime = System.currentTimeMillis();

		// show the questionnaire if they haven't already completed it on another device
		if (codesScanned >= 4 && !questionnaireCompleted) {
			// delay so we let the UI queries load first
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					CloudCallbackHandler<List<CloudEntity>> userQuestionnaireHandler = new CloudCallbackHandler<List<CloudEntity>>() {
						@Override
						public void onComplete(List<CloudEntity> results) {
							if (results == null) {
								return; // nothing we can do
							}
							if (results.size() == 0) {
								startActivity(new Intent(MessageViewerActivity.this, QuestionnaireActivity.class));
							} else {
								// so we don't do this query again
								SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
								Editor editor = sharedPreferences.edit();
								editor.putBoolean(getString(R.string.check_questionnaire_completed), true);
								editor.commit();
							}
						}

						@Override
						public void onError(IOException exception) {
							// nothing we can do
						}
					};

					CloudQuery cloudQuery = new CloudQuery(QRCloudUtils.DATABASE_KIND_QUESTIONNAIRE);
					cloudQuery.setFilter(F.eq(CloudEntity.PROP_CREATED_BY, getPreferencesAccountName()));
					cloudQuery.setLimit(1);
					cloudQuery.setScope(Scope.PAST);

					getCloudBackend().list(cloudQuery, userQuestionnaireHandler);
				}
			}, NON_URGENT_QUERY_DELAY);
		}

		// TODO: is this the reason why it can be slow to load codes initially?
		// check for banned users
		if (mUserState == UserState.UNKNOWN
				|| currentTime - banCheckTime > getResources().getInteger(R.integer.ban_check_interval)) {

			mUserState = UserState.CHECKING;

			// save the new query time (regardless of failures)
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putLong(getString(R.string.check_ban_time), currentTime);
			editor.commit();

			// delay so we let the UI queries load first
			new Handler().postDelayed(new Runnable() {
				@Override
				public void run() {
					CloudCallbackHandler<List<CloudEntity>> userStateHandler = new CloudCallbackHandler<List<CloudEntity>>() {
						@Override
						public void onComplete(List<CloudEntity> results) {
							if (results == null) {
								return; // nothing we can do (could change state to unknown again?)
							}
							mUserState = results.size() > 0 ? UserState.BANNED : UserState.ALLOWED;
							showBannedMessage();
						}

						@Override
						public void onError(IOException exception) {
							// nothing we can do (could change state to unknown again?)
						}
					};

					CloudQuery cloudQuery = new CloudQuery(QRCloudUtils.DATABASE_KIND_BANS);
					cloudQuery.setFilter(F.eq(QRCloudUtils.DATABASE_PROP_USER, getPreferencesAccountName()));
					cloudQuery.setLimit(1);
					cloudQuery.setScope(Scope.PAST);

					getCloudBackend().list(cloudQuery, userStateHandler);
				}
			}, NON_URGENT_QUERY_DELAY);
		} else {
			showBannedMessage();
		}

		// check for new application versions (note that only certain versions are forced; others are just bug fixes)
		if (mApplicationState == ApplicationState.UNKNOWN
				|| currentTime - versionCheckTime > getResources().getInteger(R.integer.version_check_interval)) {

			mApplicationState = ApplicationState.CHECKING;

			// save the new time (regardless of failures)
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putLong(getString(R.string.check_version_time), currentTime);
			editor.commit();

			int currentAppVersion = -1;
			try {
				PackageManager manager = getPackageManager();
				PackageInfo info = manager.getPackageInfo(getPackageName(), 0);
				currentAppVersion = info.versionCode;
			} catch (Exception e) {
				// nothing we can do
			}

			// check if an upgrade is available
			if (currentAppVersion >= 0) {

				// delay so we let the UI queries load first
				final int currentVersion = currentAppVersion;
				new Handler().postDelayed(new Runnable() {
					@Override
					public void run() {

						CloudCallbackHandler<List<CloudEntity>> applicationStateHandler = new CloudCallbackHandler<List<CloudEntity>>() {
							@Override
							public void onComplete(List<CloudEntity> results) {
								if (results == null) {
									return; // nothing we can do (could change state to unknown again?)
								}
								mApplicationState = results.size() > 0 ? ApplicationState.OLD
										: ApplicationState.CURRENT;
								showUpdateMessage();
							}

							@Override
							public void onError(IOException exception) {
								// nothing we can do (could change state to unknown again?)
							}
						};

						CloudQuery cloudQuery = new CloudQuery(QRCloudUtils.DATABASE_KIND_VERSIONS);
						cloudQuery.setFilter(F.gt(QRCloudUtils.DATABASE_PROP_VERSION, currentVersion));
						cloudQuery.setLimit(1);
						cloudQuery.setScope(Scope.PAST);

						getCloudBackend().list(cloudQuery, applicationStateHandler);
					}
				}, NON_URGENT_QUERY_DELAY);
			}
		} else {
			showUpdateMessage();
		}
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

	private void showUpdateMessage() {
		if (mApplicationState == ApplicationState.OLD) {
			AlertDialog.Builder builder = new AlertDialog.Builder(MessageViewerActivity.this);
			builder.setTitle(R.string.new_version_title);
			builder.setMessage(getString(R.string.new_version_message));
			builder.setIcon(android.R.drawable.ic_dialog_alert);
			builder.setNegativeButton(R.string.btn_exit, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					Intent finishIntent = new Intent();
					finishIntent.putExtra(QRCloudUtils.DATABASE_KIND_VERSIONS, true);
					setResult(Activity.RESULT_OK, finishIntent);
					finish();
				}
			});
			builder.setPositiveButton(R.string.btn_update, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					Intent finishIntent = new Intent();
					finishIntent.putExtra(QRCloudUtils.DATABASE_KIND_VERSIONS, true);
					setResult(Activity.RESULT_OK, finishIntent);
					finish();

					// must post delayed or the camera crashes
					new Handler().postDelayed(new Runnable() {
						@Override
						public void run() {
							try {
								Intent intent = new Intent(Intent.ACTION_VIEW);
								intent.setData(Uri.parse("market://details?id=" + getString(R.string.market_package)));
								startActivity(intent);
							} catch (ActivityNotFoundException e) {
								Toast.makeText(getApplicationContext(), R.string.new_version_failure,
										Toast.LENGTH_SHORT).show();
							}
						}
					}, 250);
				}
			});
			builder.show();
		}
	}

	private void showBannedMessage() {
		if (mUserState == UserState.BANNED) {
			AlertDialog.Builder builder = new AlertDialog.Builder(MessageViewerActivity.this);
			builder.setTitle(R.string.banned_user_title);
			builder.setMessage(getString(R.string.banned_user_message));
			builder.setIcon(android.R.drawable.ic_dialog_alert);
			builder.setNegativeButton(R.string.btn_exit, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					Intent finishIntent = new Intent();
					finishIntent.putExtra(QRCloudUtils.DATABASE_KIND_BANS, true);
					setResult(Activity.RESULT_OK, finishIntent);
					finish();
				}
			});
			builder.setPositiveButton(R.string.btn_contact, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int whichButton) {
					Intent finishIntent = new Intent();
					finishIntent.putExtra(QRCloudUtils.DATABASE_KIND_BANS, true);
					setResult(Activity.RESULT_OK, finishIntent);
					finish();

					// must post delayed or the camera crashes
					new Handler().postDelayed(new Runnable() {
						@Override
						public void run() {
							try {
								Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);
								emailIntent.setType("plain/text");
								emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL,
										new String[] { getString(R.string.contact_email) });
								emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
										getString(R.string.banned_user_email_title));
								startActivity(Intent.createChooser(emailIntent, getString(R.string.btn_contact)));
							} catch (ActivityNotFoundException e) {
								Toast.makeText(getApplicationContext(), R.string.contact_email_failure,
										Toast.LENGTH_SHORT).show();
							}
						}
					}, 250);
				}
			});
			builder.show();
		}
	}

	@Override
	protected void onDestroy() {
		if (mLocationListener != null) {
			mLocationListener.cancelGetLocation();
		}
		mLocationTabHandler.removeCallbacks(mLocationTabRunnable);
		mRefreshCompletedHandler.removeCallbacks(mRefreshCompletedRunnable);
		super.onDestroy();
	}

	private void parseCodeDetailsAndUpdate(Intent launchIntent, String codeContents, boolean updateCode) {

		// need the format to send when composing a message
		try {
			mBarcodeFormat = BarcodeFormat.valueOf(launchIntent.getStringExtra(QRCloudUtils.DATABASE_PROP_FORMAT));
		} catch (IllegalArgumentException e) {
		}

		// store the type for later analysis
		try {
			mBarcodeType = ParsedResultType.valueOf(launchIntent.getStringExtra(QRCloudUtils.DATABASE_PROP_TYPE));
		} catch (IllegalArgumentException e) {
		}

		// insert/update into the codes database
		if (updateCode) {
			updateCode(mCodeHash, codeContents, mBarcodeFormat, mBarcodeType);
		}

		// show the code details in a tab
		int barcodeTitle = launchIntent.getIntExtra(getString(R.string.key_display_title), R.string.result_text);

		// note: we ignore formatted contents for URLs, as this was breaking case-sensitive links
		String formattedBarcodeContents = launchIntent.getStringExtra(getString(R.string.key_display_contents));
		if (mBarcodeType == ParsedResultType.URI) {
			if (formattedBarcodeContents.matches("(?i)^http[s]?\\://[a-z\\-]+\\.qrwp\\.org.*$")) { // case-insensitive
				barcodeTitle = R.string.result_qrpedia; // special display for QRpedia codes
			}
			formattedBarcodeContents = codeContents;
		}

		// barcodeDetailsBundle = getBarcodeDetailsBundle(mBarcodeFormat, barcodeTitle, formattedBarcodeContents,
		// barcodeType);

		// show the code details
		TextView titleText = (TextView) findViewById(R.id.code_info_title);
		titleText.setText(getString(R.string.result_type, getString(barcodeTitle)));
		mCodeContents = (TextView) findViewById(R.id.code_info_contents);
		mCodeContents.setText(formattedBarcodeContents);

		// request the product details if applicable
		if (mBarcodeFormat == BarcodeFormat.UPC_A || mBarcodeFormat == BarcodeFormat.UPC_E
				|| mBarcodeFormat == BarcodeFormat.UPC_EAN_EXTENSION || mBarcodeFormat == BarcodeFormat.EAN_8
				|| mBarcodeFormat == BarcodeFormat.EAN_13) {
			if (mProductDetails != null) {
				setCodeDetails(mProductDetails);
			} else {
				try {
					requestBarcodeDetails(codeContents);
				} catch (JSONException e) {
					// problem getting code details - ignore
				}
			}
		} else if (mBarcodeType == ParsedResultType.EMAIL_ADDRESS || mBarcodeType == ParsedResultType.URI
				|| mBarcodeType == ParsedResultType.TEL || mBarcodeType == ParsedResultType.GEO) {
			// linkify where appropriate
			Linkify.addLinks(mCodeContents, Linkify.ALL);
		}

		// set the correct fonts
		titleText.setTypeface(Typefaces.get(MessageViewerActivity.this, getString(R.string.default_font_bold)));
		mCodeContents.setTypeface(Typefaces.get(MessageViewerActivity.this, getString(R.string.default_font)));
	}

	private void requestBarcodeDetails(String barcode) throws JSONException {
		UPCDatabaseRestClient.get(barcode, null, new JsonHttpResponseHandler() {
			@Override
			public void onSuccess(int a, JSONObject result) {
				if (result != null) {
					try {
						// for UPC database
						// if (result.getBoolean("valid")) {
						// setCodeDetails(result.getString("description"));
						// }

						// for EAN database
						JSONObject product = result.getJSONObject("product");
						if (product != null) {
							JSONObject attributes = product.getJSONObject("attributes");
							if (attributes != null) {
								setCodeDetails(attributes.getString("product"));
							}
						}
					} catch (JSONException e) {
						// parse error - ignore
					}
				}
			}
		});
	}

	private void setCodeDetails(String description) {
		mProductDetails = description;
		if (!TextUtils.isEmpty(mProductDetails)) {
			mCodeContents.setText(mCodeContents.getText() + " � " + mProductDetails);
		}
	}

	private void updateCode(final String codeHash, final String codeContents, final BarcodeFormat barcodeFormat,
			final ParsedResultType barcodeType) {

		// delay so we let the UI queries load first
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				CloudCallbackHandler<List<CloudEntity>> handler = new CloudCallbackHandler<List<CloudEntity>>() {
					@Override
					public void onComplete(List<CloudEntity> results) {
						if (results.size() <= 0) {
							// no record for this code exists - create a CloudEntity with the new code
							CloudEntity newCode = new CloudEntity(QRCloudUtils.DATABASE_KIND_CODES);
							newCode.put(QRCloudUtils.DATABASE_PROP_HASH, codeHash);
							newCode.put(QRCloudUtils.DATABASE_PROP_CONTENTS, codeContents);
							newCode.put(QRCloudUtils.DATABASE_PROP_FORMAT, barcodeFormat.name());
							newCode.put(QRCloudUtils.DATABASE_PROP_TYPE, barcodeType.name());
							newCode.put(QRCloudUtils.DATABASE_PROP_SCANS, 1); // this is the initial scan
							newCode.put(QRCloudUtils.DATABASE_PROP_SOURCE, ContentProviderAuthority.DB_SOURCE);

							// execute the insertion; nothing we can do on error, so ignore the result
							getCloudBackend().insert(newCode, null);
						} else {
							CloudEntity existingEntity = results.get(0);
							if (existingEntity != null) {
								Object currentScanCount = existingEntity.get(QRCloudUtils.DATABASE_PROP_SCANS);
								if (currentScanCount != null) {
									// update to increase the scan count; nothing to do on error, so no result handler
									existingEntity.put(QRCloudUtils.DATABASE_PROP_SCANS,
											Integer.valueOf(currentScanCount.toString()) + 1);
									getCloudBackend().update(existingEntity, null);
								}
							}
						}
					}

					@Override
					public void onError(IOException exception) {
						// nothing else we can do
					}
				};

				// now search for an existing record of this code
				CloudQuery cloudQuery = new CloudQuery(QRCloudUtils.DATABASE_KIND_CODES);
				cloudQuery.setFilter(F.eq(QRCloudUtils.DATABASE_PROP_HASH, codeHash));
				cloudQuery.setLimit(1);
				cloudQuery.setScope(Scope.PAST);
				getCloudBackend().list(cloudQuery, handler);
			}
		}, NON_URGENT_QUERY_DELAY);
	}

	@Override
	public void reportItem(CloudEntity itemToReport) {
		// create a response handler that will receive the result or an error
		CloudCallbackHandler<CloudEntity> handler = new CloudCallbackHandler<CloudEntity>() {
			@Override
			public void onComplete(final CloudEntity result) {
				// nothing else to do here
				// TODO: queue updating this and the other lists to remove the item?
			}

			@Override
			public void onError(final IOException exception) {
				if (QRCloudUtils.DEBUG) {
					Log.d(TAG, "Reporting exception: " + exception.getMessage()); // nothing much to do here
				}
			}
		};

		itemToReport.put(QRCloudUtils.DATABASE_PROP_REPORTED, true);
		getCloudBackend().update(itemToReport, handler);
	}

	@Override
	public void clickItem(CloudEntity itemToClick) {
		if (itemToClick == null) {
			return; // they've clicked on the empty/loading item between adding and refreshing
		}
		Object currentRating = itemToClick.get(QRCloudUtils.DATABASE_PROP_RATING);
		if (currentRating == null) {
			return; // they've clicked on their message between adding and refreshing
		}

		// create a response handler that will receive the result or an error
		CloudCallbackHandler<CloudEntity> handler = new CloudCallbackHandler<CloudEntity>() {
			@Override
			public void onComplete(final CloudEntity result) {
				// nothing else to do here
				// TODO: re-sort the lists?
			}

			@Override
			public void onError(final IOException exception) {
				if (QRCloudUtils.DEBUG) {
					Log.d(TAG, "Rating increase exception: " + exception.getMessage()); // nothing much to do here
				}
			}
		};

		itemToClick.put(QRCloudUtils.DATABASE_PROP_RATING, Integer.valueOf(currentRating.toString()) + 1);
		getCloudBackend().update(itemToClick, handler);
	}

	@Override
	public String loadItems(CloudQuery cloudQuery, F extraFilter, CloudCallbackHandler<List<CloudEntity>> handler,
			String previousQueryId) {
		if (previousQueryId != null) {
			getCloudBackend().unsubscribeFromQuery(previousQueryId);
		}
		F queryFilter = F.and(F.createFilter(Op.EQ.name(), QRCloudUtils.DATABASE_PROP_HASH, mCodeHash),
				F.createFilter(Op.EQ.name(), QRCloudUtils.DATABASE_PROP_REPORTED, false)); // F caching doesn't work
		cloudQuery.setFilter(extraFilter == null ? queryFilter : F.and(queryFilter, extraFilter));
		getCloudBackend().list(cloudQuery, handler);
		return cloudQuery.getQueryId();
	}

	// handler and runnable for refreshing the location tab
	Handler mLocationTabHandler = new Handler();
	Runnable mLocationTabRunnable = new Runnable() {
		@Override
		public void run() {
			CloudEntityListFragment locationTab = mTabsAdapter.getLocationTab();
			if (locationTab != null) {
				locationTab.refreshList(true);
			}
		}
	};

	@Override
	public String getLocationHash() {
		if (mLocationTabEnabled) {
			if (mWaitingForGooglePlayLocation) {
				return QRCloudUtils.GEOCELL_LOADING_MAGIC_VALUE;
			}
			if (mGooglePlayLocationConnected) {
				mLocation = mLocationClient.getLastLocation();
			}
			if (!mGooglePlayLocationConnected || mLocation == null) {
				// return the cached location if we're asking again within the time limit
				long currentTime = System.currentTimeMillis();
				if (currentTime - mManualLocationRequestTime < mMinimumLocationRefreshWaitTime) {
					return mLocation == null ? null : GeocellUtils.compute(
							new Point(mLocation.getLatitude(), mLocation.getLongitude()),
							QRCloudUtils.GEOCELL_QUERY_PRECISION);
				}
				return requestManualLocationAndUpdateTab();
			}
			return mLocation == null ? null : GeocellUtils.compute(
					new Point(mLocation.getLatitude(), mLocation.getLongitude()), QRCloudUtils.GEOCELL_QUERY_PRECISION);
		}
		return null;
	}

	private String requestManualLocationAndUpdateTab() {
		if (mWaitingForGooglePlayLocation) {
			return QRCloudUtils.GEOCELL_LOADING_MAGIC_VALUE;
		}
		if (mGooglePlayLocationConnected) {
			mLocation = mLocationClient.getLastLocation();
			mLocationTabHandler.removeCallbacks(mLocationTabRunnable);
			mLocationTabHandler.post(mLocationTabRunnable);
		}
		if (!mGooglePlayLocationConnected || mLocation == null) {
			// request a location update, returning a magic value so the list knows to keep waiting
			LocationResult locationResult = new LocationResult() {
				@Override
				public void gotLocation(Location location) {
					mLocation = location;
					mManualLocationRequestTime = System.currentTimeMillis(); // so we have time to load actual content
					mLocationTabHandler.removeCallbacks(mLocationTabRunnable);
					mLocationTabHandler.post(mLocationTabRunnable);
				}
			};

			mManualLocationRequestTime = System.currentTimeMillis();
			if (mLocationListener == null) {
				mLocationListener = new LocationRetriever();
			}
			if (mLocationListener.getLocation(MessageViewerActivity.this, locationResult)) {
				return QRCloudUtils.GEOCELL_LOADING_MAGIC_VALUE;
			}
		}
		return null;
	}

	@Override
	public Location getLocation() {
		return mLocation;
	}

	private void setRefreshIcon(MenuItem item) {
		if (item != null) {
			mRefreshButtonItem = item;
			mRefreshButtonItem.setActionView(R.layout.refresh_button);
			ImageView refreshIcon = (ImageView) mRefreshButtonItem.getActionView().findViewById(R.id.refresh_button);
			refreshIcon.startAnimation(mRotateAnimation);
		} else if (mRefreshButtonItem != null) {
			ImageView refreshIcon = (ImageView) mRefreshButtonItem.getActionView().findViewById(R.id.refresh_button);
			refreshIcon.clearAnimation();
			mRotateAnimation.cancel();
			mRefreshButtonItem.setActionView(null);
			mRefreshButtonItem = null;
		} else {
			mRotateAnimation.cancel(); // always try to cancel the animation (even if we've rotated)
		}
	}

	// handler and runnable for updating the refresh icon
	Handler mRefreshCompletedHandler = new Handler();
	Runnable mRefreshCompletedRunnable = new Runnable() {
		@Override
		public void run() {
			setRefreshIcon(null); // when the animation finishes, return the button to its original state
		}
	};

	@Override
	public void refreshCompleted() {
		mRefreshCompletedHandler.removeCallbacks(mRefreshCompletedRunnable);
		mRefreshCompletedHandler.post(mRefreshCompletedRunnable);
	}

	@Override
	public String getCurrentAccountName() {
		return getPreferencesAccountName();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getSupportMenuInflater().inflate(R.menu.activity_message_viewer, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_scan_again:
				Intent scanIntent = new Intent(MessageViewerActivity.this, ScannerActivity.class);
				scanIntent.putExtra(getString(R.string.key_started_scanning), true);
				startActivity(scanIntent);
				return true;

			case R.id.menu_add_content:
				Intent addMessageIntent = new Intent(MessageViewerActivity.this, MessageEditorActivity.class);
				addMessageIntent.putExtra(QRCloudUtils.DATABASE_PROP_HASH, mCodeHash);
				addMessageIntent.putExtra(QRCloudUtils.DATABASE_PROP_FORMAT, mBarcodeFormat.name());
				addMessageIntent.putExtra(QRCloudUtils.DATABASE_PROP_TYPE, mBarcodeType.name());
				startActivityForResult(addMessageIntent, MessageEditorActivity.ADD_MESSAGE_REQUEST);
				return true;

			case R.id.menu_refresh:
				// request a refresh
				Fragment selectedFragment = mTabsAdapter.getSelectedItem();
				if (selectedFragment != null && selectedFragment instanceof CloudEntityListFragment) {
					// start the button animation
					setRefreshIcon(item);
					if (!((CloudEntityListFragment) selectedFragment).refreshList(false)) {
						// the refresh was denied (too soon), so post a message to remove the animation manually
						mRefreshCompletedHandler.removeCallbacks(mRefreshCompletedRunnable);
						mRefreshCompletedHandler.postDelayed(mRefreshCompletedRunnable, 600);
					} else {
						// refresh starting
					}
				}
				return true;

			case R.id.menu_view_clippings:
				startActivity(new Intent(MessageViewerActivity.this, SavedTextListActivity.class));
				return true;
		}
		return false;
	}

	/*
	 * Called by Location Services when the request to connect the client finishes successfully. At this point, you can
	 * request the current location or start periodic updates
	 */
	@Override
	public void onConnected(Bundle dataBundle) {
		mWaitingForGooglePlayLocation = false;
		mGooglePlayLocationConnected = true;
		if (mLocation == null) {
			requestManualLocationAndUpdateTab();
		}
	}

	/*
	 * Called by Location Services if the attempt to Location Services fails.
	 */
	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		// couldn't connect to Play Services to get the location
		// for better handling of this, see: http://developer.android.com/training/location/retrieve-current.html
		mWaitingForGooglePlayLocation = false;
		mGooglePlayLocationConnected = false;
		if (mLocation == null) {
			requestManualLocationAndUpdateTab();
		}
	}

	/*
	 * Called by Location Services if the connection to the location client is disconnected or drops because of an
	 * error.
	 */
	@Override
	public void onDisconnected() {
		mWaitingForGooglePlayLocation = false;
		mGooglePlayLocationConnected = false;
		if (mLocation == null) {
			requestManualLocationAndUpdateTab();
		}
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case MessageEditorActivity.ADD_MESSAGE_REQUEST:
				if (resultCode == RESULT_OK && data != null && data.getExtras() != null) {
					String message = data.getStringExtra(QRCloudUtils.DATABASE_PROP_MESSAGE);
					boolean geoTagged = data.getBooleanExtra(QRCloudUtils.DATABASE_PROP_GEOCELL, false);
					if (message != null) {
						for (CloudEntityListFragment fragment : mTabsAdapter.getAllListTabs()) {
							fragment.addItem(message, geoTagged);
						}
					}
				}
			default:
				super.onActivityResult(requestCode, resultCode, data);
		}
	}

	// see: http://stackoverflow.com/a/11730613/1993220
	public class TabsAdapter extends FragmentPagerAdapter implements ActionBar.TabListener,
			ViewPager.OnPageChangeListener {
		private final Context mContext;
		private final ActionBar mActionBar;
		private final FragmentManager mFragmentManager;
		private final ViewPager mViewPager;
		private final ArrayList<TabInfo> mTabs;
		private SparseArray<String> mFragmentTags;
		private int mSelectedItem = 0;

		private int mLocationTabPosition = -1;

		class TabInfo {
			private final Class<?> clss;
			private final Bundle args;
			private final boolean isLocation;

			TabInfo(Class<?> _class, Bundle _args, boolean isLocationTab) {
				clss = _class;
				args = _args;
				isLocation = isLocationTab;
			}
		}

		public TabsAdapter(SherlockFragmentActivity activity, ViewPager pager) {
			super(activity.getSupportFragmentManager());
			mContext = activity;
			mActionBar = activity.getSupportActionBar();
			mFragmentManager = activity.getSupportFragmentManager();
			mTabs = new ArrayList<TabInfo>();
			mFragmentTags = new SparseArray<String>();
			mViewPager = pager;
			mViewPager.setAdapter(this);
			mViewPager.setOnPageChangeListener(this);
		}

		public void addTab(ActionBar.Tab tab, Class<?> clss, Bundle args, boolean isLocationTab) {
			TabInfo info = new TabInfo(clss, args, isLocationTab);
			tab.setTag(info);
			tab.setTabListener(this);
			mTabs.add(info);
			mActionBar.addTab(tab);
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return mTabs.size();
		}

		public Fragment getSelectedItem() {
			String tag = mFragmentTags.get(mSelectedItem);
			if (tag == null) {
				return null;
			}
			return mFragmentManager.findFragmentByTag(tag);
		}

		public CloudEntityListFragment getLocationTab() {
			String tag = mFragmentTags.get(mLocationTabPosition);
			if (tag == null) {
				return null;
			}
			return (CloudEntityListFragment) mFragmentManager.findFragmentByTag(tag); // we know it's the right type
		}

		public ArrayList<CloudEntityListFragment> getAllListTabs() {
			ArrayList<CloudEntityListFragment> allTabs = new ArrayList<CloudEntityListFragment>();
			for (int i = 0, n = mFragmentTags.size(); i < n; i++) {
				String tag = mFragmentTags.get(i);
				if (tag == null) {
					return null;
				}
				Fragment fragment = mFragmentManager.findFragmentByTag(tag);
				if (fragment instanceof CloudEntityListFragment) {
					allTabs.add((CloudEntityListFragment) fragment);
				}
			}
			return allTabs;
		}

		@Override
		public Object instantiateItem(ViewGroup container, int position) {
			Object obj = super.instantiateItem(container, position);
			if (obj instanceof Fragment) {
				// record the fragment tag here to refer to it later - http://stackoverflow.com/a/12104399/1993220
				Fragment f = (Fragment) obj;
				String tag = f.getTag();
				mFragmentTags.put(position, tag);
				TabInfo info = mTabs.get(position);
				if (info.isLocation) {
					mLocationTabPosition = position;
				}
			}
			return obj;
		}

		@Override
		public Fragment getItem(int position) {
			TabInfo info = mTabs.get(position);
			if (info.isLocation) {
				mLocationTabPosition = position;
			}
			return Fragment.instantiate(mContext, info.clss.getName(), info.args);
		}

		@Override
		public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
		}

		@Override
		public void onPageSelected(int position) {
			mSelectedItem = position;
			mActionBar.setSelectedNavigationItem(position);

			if (!mLocationTabEnabled) {
				Fragment selectedFragment = getSelectedItem();
				if (selectedFragment != null && selectedFragment instanceof CloudEntityListFragment) {
					CloudEntityListFragment cloudFragment = (CloudEntityListFragment) selectedFragment;
					if (cloudFragment.getGeoHashEnabled()) {
						// the location tab is selected - force refresh the location query and save the id for future
						mLocationTabEnabled = true;
						cloudFragment.refreshList(true);
					}
				}
			}
		}

		@Override
		public void onPageScrollStateChanged(int state) {
		}

		@Override
		public void onTabSelected(Tab tab, FragmentTransaction ft) {
			Object tag = tab.getTag();
			for (int i = 0, n = mTabs.size(); i < n; i++) {
				if (mTabs.get(i) == tag) {
					mSelectedItem = i;
					mViewPager.setCurrentItem(i);
					break;
				}
			}
		}

		@Override
		public void onTabUnselected(Tab tab, FragmentTransaction ft) {
		}

		@Override
		public void onTabReselected(Tab tab, FragmentTransaction ft) {
		}
	}
}
