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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import qr.cloud.qrpedia.CloudEntityArrayAdapter.ListRowCallback;
import qr.cloud.qrpedia.CloudEntityArrayAdapter.ViewHolder;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockListFragment;
import com.commonsware.cwac.endless.EndlessAdapter;
import com.google.cloud.backend.android.CloudCallbackHandler;
import com.google.cloud.backend.android.CloudEntity;
import com.google.cloud.backend.android.CloudQuery;
import com.google.cloud.backend.android.CloudQuery.Order;
import com.google.cloud.backend.android.CloudQuery.Scope;
import com.google.cloud.backend.android.F;

public class CloudEntityListFragment extends SherlockListFragment implements ListRowCallback {

	private static final int ERROR_LIMIT = 2; // before we stop automatically retrying item loading
	private static final String LOG_TAG = "CloudListFragment";

	FragmentInteractionListener mCallback;

	public interface FragmentInteractionListener {
		public void reportItem(CloudEntity item);

		public void clickItem(CloudEntity item);

		public String loadItems(CloudQuery cloudQuery, F extraFilter, CloudCallbackHandler<List<CloudEntity>> handler,
				String previousQueryId);

		public String getLocationHash(); // triggers a location update

		public Location getLocation(); // just returns the stored value

		public void refreshCompleted();

		public String getCurrentAccountName();
	}

	CloudEntityAdapter mCloudAdapter = null;
	ArrayList<CloudEntity> mItems = null;

	String mSortType;
	F mExtraFilter;
	boolean mGeoHashEnabled;

	String mQueryId;
	String mLoadingText;

	int mItemOffset; // TODO: make this screen size dependent?
	int mListPosition;
	int mListTop;

	long mRefreshTime;
	int mMinimumCloudRefreshWaitTime;

	enum DisplayMode {
		TIME, LOCATION, RATING, OWNER
	}

	DisplayMode mDisplayMode;

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		// use this to get the filter category or the extra filter
		Bundle launchState = getArguments();
		if (launchState != null) {
			mSortType = launchState.getString(QRCloudUtils.FRAGMENT_SORT_TYPE);

			if (mSortType == QRCloudUtils.DATABASE_PROP_GEOCELL) {
				// geo sorting is more difficult - we need a manual filter, refreshed as they move (see cache function)
				mGeoHashEnabled = true;
			} else {
				String filterOperator = launchState.getString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_OPERATOR);
				String filterProperty = launchState.getString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_PROPERTY);
				String filterValue = launchState.getString(QRCloudUtils.FRAGMENT_EXTRA_FILTER_VALUE);
				if (!TextUtils.isEmpty(filterOperator) && !TextUtils.isEmpty(filterProperty)
						&& !TextUtils.isEmpty(filterValue)) {
					mExtraFilter = F.createFilter(filterOperator, filterProperty, filterValue);
				}
			}
		}

		// get the right loading hint and store the mode we're in for displaying the right content in tabs
		if (mGeoHashEnabled) {
			mLoadingText = getString(R.string.loading_messages, getString(R.string.loading_nearby));
			mDisplayMode = DisplayMode.LOCATION;
		} else if (mExtraFilter != null) {
			mLoadingText = getString(R.string.loading_messages, getString(R.string.loading_your));
			mDisplayMode = DisplayMode.OWNER;
		} else if (QRCloudUtils.DATABASE_PROP_RATING.equals(mSortType)) {
			mLoadingText = getString(R.string.loading_messages, getString(R.string.loading_popular));
			mDisplayMode = DisplayMode.RATING;
		} else {
			mLoadingText = getString(R.string.loading_messages, getString(R.string.loading_recent));
			mDisplayMode = DisplayMode.TIME;
		}

		// time limit for the refresh button
		mMinimumCloudRefreshWaitTime = getResources().getInteger(R.integer.minimum_cloud_refresh_time);

		setRetainInstance(true); // we want to save state on rotation to avoid reloading

		// set the adapter after attaching the fragment to try to deal with slow load times possibly caused by the cloud
		// connection not yet being established when onActivityCreated is called
		if (mCloudAdapter == null) {
			mItemOffset = 0;
			mItems = new ArrayList<CloudEntity>();
			mCloudAdapter = new CloudEntityAdapter(mItems, mDisplayMode);
			mCloudAdapter.setRunInBackground(false); // we handle our own item loading
		}
		setListAdapter(mCloudAdapter);

		// configure the list view
		ListView listView = getListView();
		listView.setSelector(R.drawable.message_row_selector); // override the default selector
		listView.setDivider(new ColorDrawable(this.getResources().getColor(R.color.list_divider)));
		listView.setDividerHeight(1);
		listView.setOnItemLongClickListener(mLongClickListener);
	}

	@Override
	public void onResume() {
		super.onResume();
		refreshEmptyListHint();
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);

		// make sure that the container activity has implemented the callback interface
		try {
			mCallback = (FragmentInteractionListener) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString() + " must implement "
					+ FragmentInteractionListener.class.toString());
		}
	}

	public void addItem(String itemText, boolean geoTagged) {
		// check whether we should actually add the item
		boolean addItem = !mGeoHashEnabled; // add to non-geo fragments by default
		if (mGeoHashEnabled && geoTagged) {
			addItem = true; // if this is the location tab and they added the location to this item then add it
		}
		if (addItem) {
			// TODO: can we prevent adding duplicates here? (e.g., when the query updates and adds the item before we've
			// had chance to add it here?)
			CloudEntity newItem = new CloudEntity(QRCloudUtils.DATABASE_KIND_MESSAGES);
			newItem.put(QRCloudUtils.DATABASE_PROP_MESSAGE, itemText);
			mItems.add(0, newItem);
			mCloudAdapter.onDataReady();
		}
	}

	private void refreshEmptyListHint() {
		// show either the no messages hint, or an internet unavailable hint
		String messageText;
		int messageFont;
		if (!QRCloudUtils.internetAvailable(getActivity())) {
			messageText = getString(R.string.no_internet_hint, getString(R.string.no_internet_task_view));
			messageFont = R.string.default_font_bold;
		} else {
			int emptyMessageId;
			switch (mDisplayMode) {
				case LOCATION:
					emptyMessageId = R.string.no_nearby_messages;
					break;
				case OWNER:
					emptyMessageId = R.string.no_self_messages;
					break;
				case RATING:
					emptyMessageId = R.string.no_popular_messages;
					break;
				case TIME:
				default:
					emptyMessageId = R.string.no_recent_messages;
					break;
			}
			messageText = getString(R.string.empty_list_hint, getString(emptyMessageId));
			messageFont = R.string.default_font;
		}
		QRCloudUtils.setListViewEmptyView(getListView(), messageText, messageFont, R.dimen.activity_horizontal_margin,
				R.dimen.activity_vertical_margin);
	}

	public boolean refreshList(boolean force) {
		refreshEmptyListHint(); // in case they enabled network access then manually refreshed
		if (mCloudAdapter != null && QRCloudUtils.internetAvailable(getActivity())) {
			// only refresh if we've waited long enough since the last operation
			long currentTime = System.currentTimeMillis();
			if (force || currentTime - mRefreshTime > mMinimumCloudRefreshWaitTime) {
				// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "list refresh");
				mCloudAdapter.stopAppending();
				if (force || mItems.size() == 0) { // bug means zero size lists don't refresh properly
					try {
						// the endless adapter pretty much completely ignores restartAppending most of the time
						mCloudAdapter.cacheInBackground();
					} catch (Exception e) {
					}
				}
				mCloudAdapter.restartAppending();
				return true;
			}
		}
		return false;
	}

	public boolean getGeoHashEnabled() {
		return mGeoHashEnabled;
	}

	@Override
	public Location getLocation() {
		return mCallback.getLocation();
	}

	@Override
	public void clickItem(int position) { // TODO: duplication here?
		mCallback.clickItem(mItems.get(position));
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		clickItem(position);

		// if in the rating view, increase the item rating while we're reloading from the server
		// TODO: re-sort the list immediately?
		if (mDisplayMode == DisplayMode.RATING) {
			Object viewTag = v.getTag();
			if (viewTag instanceof ViewHolder) {
				ViewHolder viewHolder = (ViewHolder) viewTag;
				String viewText = viewHolder.messageInfo.getText().toString().replace("+", "");
				int itemRating = 1;
				try {
					itemRating = Integer.parseInt(viewText) + 1;
				} catch (NumberFormatException e) {
				}
				viewHolder.messageInfo.setText("+" + itemRating);
			}
		}
	}

	private OnItemLongClickListener mLongClickListener = new OnItemLongClickListener() {
		@Override
		public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
			// show a slightly different menu for items created by the current user (allow deletion)
			String accountName = mCallback.getCurrentAccountName();
			final boolean isOwner = (accountName != null && (accountName.equals(mItems.get(position).getCreatedBy()))) ? true
					: false;
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setTitle(R.string.message_options);
			builder.setItems(isOwner ? R.array.message_options_values_self : R.array.message_options_values_other,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {

							String chosenValue = getResources().getStringArray(
									isOwner ? R.array.message_options_values_self
											: R.array.message_options_values_other)[which];
							if (chosenValue.equals(getString(R.string.add_to_clippings))) {

								// launch the editor with the chosen item's text
								Intent saveMessageIntent = new Intent(getActivity(), SavedTextEditorActivity.class);
								saveMessageIntent.putExtra(QRCloudUtils.DATABASE_PROP_MESSAGE,
										(String) mItems.get(position).get(QRCloudUtils.DATABASE_PROP_MESSAGE));
								startActivity(saveMessageIntent);

								// click the item to increase its rating
								clickItem(position);

							} else if (chosenValue.equals(getString(R.string.report_item))) {

								// tell the parent to report the post, then show a confirmation message
								mCallback.reportItem(mItems.get(position));
								Toast.makeText(getActivity(), R.string.item_reported, Toast.LENGTH_SHORT).show();

							} else if (chosenValue.equals(getString(R.string.delete_item))) {

								// report the post - items reported by their owner are regarded as deleted
								mCallback.reportItem(mItems.get(position));
								Toast.makeText(getActivity(), R.string.item_deleted, Toast.LENGTH_SHORT).show();
							}
						}
					});
			builder.show();
			return true;
		}
	};

	private class CloudEntityAdapter extends EndlessAdapter {
		int mErrorCount;

		CloudEntityAdapter(ArrayList<CloudEntity> list, DisplayMode displayMode) {
			super(new CloudEntityArrayAdapter(getActivity(), R.layout.message_row, list, displayMode,
					CloudEntityListFragment.this));
			mErrorCount = 0;
		}

		@Override
		protected View getPendingView(ViewGroup parent) {
			// TODO: cache the loader row?
			View row = getActivity().getLayoutInflater().inflate(R.layout.loader_row, null);
			TextView pendingMessage = (TextView) row.findViewById(R.id.message_loading_text);
			pendingMessage.setText(mLoadingText);
			pendingMessage.setTypeface(Typefaces.get(getActivity(), getString(R.string.default_font)));
			return row;
		}

		@Override
		public boolean cacheInBackground() throws Exception {
			if (!QRCloudUtils.internetAvailable(getActivity())) {
				return false;
			}

			mRefreshTime = System.currentTimeMillis(); // update the last refreshed time
			mItemOffset += QRCloudUtils.ITEMS_TO_LOAD; // load another set of items if possible //TODO: fix when loaded
			// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "loading items");

			// execute the cloud query with the handler
			CloudQuery cloudQuery = new CloudQuery(QRCloudUtils.DATABASE_KIND_MESSAGES);
			cloudQuery.setSort(mSortType == null ? CloudEntity.PROP_CREATED_AT : mSortType, Order.DESC); // default date
			if (mGeoHashEnabled) {
				// filter by a geohash/geocell hack: http://stackoverflow.com/a/1096744/1993220
				String geoHash = mCallback.getLocationHash(); // calculated to query precision
				if (geoHash == null) {
					// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "no geo");
					return false; // no point doing anything - no geohash available
				} else if (QRCloudUtils.GEOCELL_LOADING_MAGIC_VALUE.equals(geoHash)) {
					// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "magic geo");
					return true; // we're getting a location - wait for another refresh callback
				} else {
					// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "real geo");
					String maxGeoHash = new String(geoHash + "\ufffd");
					mExtraFilter = F.and(F.ge(QRCloudUtils.DATABASE_PROP_GEOCELL, geoHash),
							F.le(QRCloudUtils.DATABASE_PROP_GEOCELL, maxGeoHash));
				}
			} else {
				// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "geo disabled");
			}
			cloudQuery.setLimit(mItemOffset);
			cloudQuery.setScope(Scope.FUTURE_AND_PAST);

			mQueryId = mCallback.loadItems(cloudQuery, mExtraFilter, mQueryHandler, mQueryId);
			return true;
		}

		// TODO: will this update when we scan a new code - do we need to remove it?
		// create a response handler that will receive the query result or an error
		private CloudCallbackHandler<List<CloudEntity>> mQueryHandler = new CloudCallbackHandler<List<CloudEntity>>() {
			@Override
			public void onComplete(List<CloudEntity> results) {
				// Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "loaded " + results.size());
				onItemsReady(results);
			}

			@Override
			public void onError(IOException exception) {
				if (QRCloudUtils.DEBUG) {
					Log.d(LOG_TAG, CloudEntityListFragment.this.getTag() + " " + "loading exception: " + exception);
				}
				mErrorCount += 1; // TODO: this seems to reset every time
				mItemOffset -= QRCloudUtils.ITEMS_TO_LOAD;
				mItemOffset = mItemOffset < 0 ? 0 : mItemOffset;
				if (mErrorCount <= ERROR_LIMIT) {
					try {
						cacheInBackground();
					} catch (Exception e) {
						onError(new IOException(exception.getMessage()));
					}
				} else {
					// must still tell the callback we've refreshed, but not clear items already loaded
					// TODO: show an error message here?
					stopAppending();
					mCallback.refreshCompleted();
				}
			}
		};

		public void onItemsReady(List<CloudEntity> data) {
			// TODO: we need some way of detecting when this is a future/past result or user requested (limit numbers)
			// TODO: fix list going to top on older devices after clearing and re-adding
			mErrorCount = 0;
			mItems.clear(); // we actually download *all* items each time... (TODO: filter instead; this slows down)
			boolean noData = data == null || data.size() == 0;
			if (noData || data.size() < mItemOffset) {
				stopAppending(); // if we received fewer items than we asked for we've reached the end of the list
				if (noData) {
					mItemOffset = 0;
					mCallback.refreshCompleted(); // must still tell the callback we've refreshed
					return;
				} else {
					mItemOffset = data.size();
				}
			}

			// sort the items (can only sort by one field in the cloud query)
			final Location sortLocation = getGeoHashEnabled() ? getLocation() : null;
			if (QRCloudUtils.DATABASE_PROP_RATING.equals(mSortType)) {
				// if we're a rating tab, show highest rated items first
				Collections.sort(data, new Comparator<CloudEntity>() {
					@Override
					public int compare(CloudEntity o1, CloudEntity o2) {
						final int o1Rating = Integer.valueOf(o1.get(QRCloudUtils.DATABASE_PROP_RATING).toString());
						final int o2Rating = Integer.valueOf(o2.get(QRCloudUtils.DATABASE_PROP_RATING).toString());
						final Date o1Date = o1.getCreatedAt();
						final Date o2Date = o2.getCreatedAt();
						if (o1Rating == o2Rating) {
							// sort by date if the ratings are the same
							return (o1Date.after(o2Date) ? -1 : (o1Date.equals(o2Date) ? 0 : 1));
						} else {
							// otherwise sort by rating
							return (o1Rating > o2Rating ? -1 : (o1Rating == o2Rating ? 0 : 1));
						}
					}
				});
			} else if (sortLocation != null) {
				// if we're a location tab, sort by distance to current location
				// TODO: this is highly inefficient, and we also end up doing it again when we display the row...
				Collections.sort(data, new Comparator<CloudEntity>() {
					@Override
					public int compare(CloudEntity o1, CloudEntity o2) {
						boolean error = false;
						float l1Distance = 0;
						float l2Distance = 0;
						try {
							Location l1 = new Location("");
							l1.setLatitude(Double.valueOf(o1.get(QRCloudUtils.DATABASE_PROP_LATITUDE).toString()));
							l1.setLongitude(Double.valueOf(o1.get(QRCloudUtils.DATABASE_PROP_LONGITUDE).toString()));
							l1Distance = sortLocation.distanceTo(l1);
						} catch (NullPointerException e) {
							error = true;
						} catch (NumberFormatException e) {
							error = true;
						}
						try {
							Location l2 = new Location("");
							l2.setLatitude(Double.valueOf(o2.get(QRCloudUtils.DATABASE_PROP_LATITUDE).toString()));
							l2.setLongitude(Double.valueOf(o2.get(QRCloudUtils.DATABASE_PROP_LONGITUDE).toString()));
							l2Distance = sortLocation.distanceTo(l2);
						} catch (NullPointerException e) {
							error = true;
						} catch (NumberFormatException e) {
							error = true;
						}

						if (!error) {
							return (l1Distance < l2Distance ? -1 : (l1Distance == l2Distance ? 0 : 1));
						} else {
							// sort by date if we encountered a geo error
							final Date o1Date = o1.getCreatedAt();
							final Date o2Date = o2.getCreatedAt();
							return (o1Date.after(o2Date) ? -1 : (o1Date.equals(o2Date) ? 0 : 1));
						}
					}
				});
			} else if (mSortType != null) {
				// if the sort type isn't null (i.e. CloudEntity.PROP_CREATED_AT for the time tab), sort by time
				Collections.sort(data, new Comparator<CloudEntity>() {
					@Override
					public int compare(CloudEntity o1, CloudEntity o2) {
						final Date o1Date = o1.getCreatedAt();
						final Date o2Date = o2.getCreatedAt();
						return (o1Date.after(o2Date) ? -1 : (o1Date.equals(o2Date) ? 0 : 1));
					}
				});
			}

			mItems.addAll(data);
			mCloudAdapter.onDataReady(); // makes the adapter remove the loader and call notifyDataSetChanged()
			mCallback.refreshCompleted();
		}

		@Override
		protected void appendCachedData() {
		}
	}
}
