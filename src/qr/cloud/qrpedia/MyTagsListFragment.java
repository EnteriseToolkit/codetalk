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

import qr.cloud.util.QRCloudDatabase;
import qr.cloud.util.QRCloudProvider;
import qr.cloud.util.QRCloudUtils;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockListFragment;

public class MyTagsListFragment extends SherlockListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

	private static final int TAGS_LIST_LOADER = 0x01;
	private SimpleCursorAdapter mCursorAdapter;

	private boolean mIsInDialog = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// start loading content
		getLoaderManager().initLoader(TAGS_LIST_LOADER, null, this);
		String[] bindFrom = { QRCloudDatabase.COL_MESSAGE };
		int[] bindTo = { R.id.imported_text };
		mCursorAdapter = new SimpleCursorAdapter(getActivity(), R.layout.import_text_row, null, bindFrom, bindTo,
				CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
		setListAdapter(mCursorAdapter);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		// configure the list view
		ListView listView = getListView();
		QRCloudUtils.setListViewEmptyView(listView, getString(R.string.no_posted_messages), R.string.default_font,
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
		CursorLoader cursorLoader = new CursorLoader(getActivity(), QRCloudProvider.CONTENT_URI_TAGS,
				QRCloudDatabase.PROJECTION_ID_MESSAGE, null, null, QRCloudDatabase.COL_DATE + " DESC");
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
}
