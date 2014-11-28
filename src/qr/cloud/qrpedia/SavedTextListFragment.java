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
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;

import com.actionbarsherlock.app.SherlockListFragment;

public class SavedTextListFragment extends SherlockListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

	OnSavedTextSelectedListener mCallback;

	public interface OnSavedTextSelectedListener {
		public void onSavedTextSelected(long position);
	}

	private static final int MESSAGE_LIST_LOADER = 0x01;
	private SimpleCursorAdapter mCursorAdapter;

	private boolean mIsInDialog = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// start loading content
		getLoaderManager().initLoader(MESSAGE_LIST_LOADER, null, this);
		String[] bindFrom = { QRCloudDatabase.COL_MESSAGE };
		int[] bindTo = { R.id.imported_text };
		mCursorAdapter = new SimpleCursorAdapter(getActivity(), R.layout.import_text_row, null, bindFrom, bindTo,
				CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
		setListAdapter(mCursorAdapter);
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);

		// make sure that the container activity has implemented the callback interface
		try {
			mCallback = (OnSavedTextSelectedListener) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString() + " must implement "
					+ OnSavedTextSelectedListener.class.toString());
		}
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		// configure the list view
		ListView listView = getListView();
		QRCloudUtils.setListViewEmptyView(listView, getString(R.string.no_saved_messages), R.string.default_font,
				R.dimen.activity_horizontal_margin, R.dimen.activity_vertical_margin);
		listView.setSelector(R.drawable.message_row_selector); // override the default selector
		listView.setDivider(new ColorDrawable(this.getResources().getColor(R.color.list_divider)));
		listView.setDividerHeight(1);
		listView.setOnItemLongClickListener(mLongClickListener);
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
		CursorLoader cursorLoader = new CursorLoader(getActivity(), QRCloudProvider.CONTENT_URI_MESSAGES,
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

	@Override
	public void onListItemClick(ListView list, View view, int position, long id) {
		mCallback.onSavedTextSelected(id);
	}

	private OnItemLongClickListener mLongClickListener = new OnItemLongClickListener() {
		@Override
		public boolean onItemLongClick(AdapterView<?> parent, View view, int position, final long id) {
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setTitle(R.string.clipping_options);
			builder.setItems(R.array.clipping_options_values, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					String chosenValue = getResources().getStringArray(R.array.clipping_options_values)[which];
					if (chosenValue.equals(getString(R.string.btn_delete))) {
						// delete the selected item
						getActivity().getContentResolver().delete(
								Uri.withAppendedPath(QRCloudProvider.CONTENT_URI_MESSAGES, String.valueOf(id)), null,
								null);

					}
				}
			});
			builder.show();
			return true;
		}
	};
}
