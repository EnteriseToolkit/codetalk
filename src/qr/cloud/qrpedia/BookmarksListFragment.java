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

import qr.cloud.util.QRCloudUtils;
import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.View;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockListFragment;

// TODO: fix text overflowing onto multiple lines
public class BookmarksListFragment extends SherlockListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

	OnBookmarkSelectedListener mCallback;

	public interface OnBookmarkSelectedListener {
		public void onBookmarkSelected(long itemId);
	}

	private static final int BOOKMARKS_LIST_LOADER = 0x02;
	private SimpleCursorAdapter mCursorAdapter;

	private boolean mIsInDialog = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// start loading content
		getLoaderManager().initLoader(BOOKMARKS_LIST_LOADER, null, this);
		String[] bindFrom = { Browser.BookmarkColumns.TITLE, Browser.BookmarkColumns.URL };
		int[] bindTo = { R.id.imported_bookmark_title, R.id.imported_bookmark_url };
		mCursorAdapter = new SimpleCursorAdapter(getActivity(), R.layout.import_bookmarks_row, null, bindFrom, bindTo,
				CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
		setListAdapter(mCursorAdapter);
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);

		// make sure that the container activity has implemented the callback interface
		try {
			mCallback = (OnBookmarkSelectedListener) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString() + " must implement "
					+ OnBookmarkSelectedListener.class.toString());
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		// configure the list view
		ListView listView = getListView();
		QRCloudUtils.setListViewEmptyView(listView, getString(R.string.no_bookmarks), R.string.default_font,
				R.dimen.activity_horizontal_margin, R.dimen.activity_vertical_margin);
		listView.setSelector(R.drawable.message_row_selector); // override the default selector
		listView.setDivider(new ColorDrawable(this.getResources().getColor(R.color.list_divider)));
		listView.setDividerHeight(1);
		if (mIsInDialog && Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
			// fix black background issue pre-honeycomb
			listView.setBackgroundColor(Color.WHITE);
		}
	}

	public void setIsInDialog(boolean isInDialog) {
		mIsInDialog = isInDialog; // for fixing pre-Honeycomb dialog display issue
	}

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		String[] projection = new String[] { Browser.BookmarkColumns._ID, Browser.BookmarkColumns.TITLE,
				Browser.BookmarkColumns.URL };
		CursorLoader cursorLoader = new CursorLoader(getActivity(), android.provider.Browser.BOOKMARKS_URI, projection,
				android.provider.Browser.BookmarkColumns.BOOKMARK, null, null);
		return cursorLoader;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
		mCursorAdapter.swapCursor(cursor);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> loader) {
		mCursorAdapter.swapCursor(null);
	}

	@Override
	public void onListItemClick(ListView list, View view, int position, long id) {
		mCallback.onBookmarkSelected(id);
	}
}
