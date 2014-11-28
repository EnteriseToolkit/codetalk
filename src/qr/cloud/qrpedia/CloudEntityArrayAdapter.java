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

import java.util.List;

import qr.cloud.qrpedia.CloudEntityListFragment.DisplayMode;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.cloud.backend.android.CloudEntity;

public class CloudEntityArrayAdapter extends ArrayAdapter<CloudEntity> {

	LayoutInflater mInflater;
	static String mFontName;

	Context mContext;
	DisplayMode mDisplayMode;
	ListRowCallback mListRowCallback;

	public interface ListRowCallback {
		public Location getLocation();

		public void clickItem(int position);
	}

	public CloudEntityArrayAdapter(Context context, int resource, List<CloudEntity> objects, DisplayMode displayMode,
			ListRowCallback listRowCallback) {
		super(context, resource, 0, objects);

		mContext = context;
		mDisplayMode = displayMode;
		mListRowCallback = listRowCallback;

		mInflater = LayoutInflater.from(context);
		if (mFontName == null) {
			mFontName = context.getString(R.string.default_font);
		}
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;

		if (convertView == null) {
			convertView = mInflater.inflate(R.layout.message_row, null);
			holder = new ViewHolder();
			holder.messageText = (TextView) convertView.findViewById(R.id.message_text);
			holder.messageInfo = (TextView) convertView.findViewById(R.id.message_info);

			Typeface listFont = Typefaces.get(getContext(), mFontName);
			holder.messageText.setTypeface(listFont);
			holder.messageInfo.setTypeface(listFont);

			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}

		CloudEntity c = getItem(position);

		// linkify, but make sure we can still click on links in our app (http://stackoverflow.com/q/9164190/1993220)
		String messageText = c.get(QRCloudUtils.DATABASE_PROP_MESSAGE).toString().trim();
		messageText = messageText.replaceAll("[\r\n]+", "\n"); // TODO: allow two newlines; remove multiple spaces?
		SpannableStringBuilder stringBuilder = new SpannableStringBuilder(messageText);
		Linkify.addLinks(stringBuilder, Linkify.ALL);
		URLSpan[] urlSpans = stringBuilder.getSpans(0, stringBuilder.length(), URLSpan.class);
		for (URLSpan span : urlSpans) {
			int start = stringBuilder.getSpanStart(span);
			int end = stringBuilder.getSpanEnd(span);
			int flags = stringBuilder.getSpanFlags(span);
			stringBuilder.removeSpan(span); // need to remove the original span to block default behaviour
			stringBuilder.setSpan(new URLSpan(span.getURL()) {
				@Override
				public void onClick(View view) {
					// pass the click to the parent
					if (mListRowCallback != null) {
						ViewParent parent = view.getParent();
						if (parent instanceof View) {
							View parentView = (View) parent;
							Object tag = parentView.getTag();
							if (tag instanceof ViewHolder) {
								mListRowCallback.clickItem(((ViewHolder) tag).position);
							}
						}
					}

					// do the default action for this link
					mContext.startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(getURL())),
							null));
				}
			}, start, end, flags);
		}
		holder.messageText.setText(stringBuilder);
		if (urlSpans.length == 0) {
			holder.messageText.setMovementMethod(null); // TODO: what's the default?
		} else {
			holder.messageText.setMovementMethod(LinkMovementMethod.getInstance()); // enable links
		}

		// show the correct additional text - time/distance/rating
		switch (mDisplayMode) {
			case TIME:
				holder.messageInfo.setText(QRCloudUtils.getElapsedTime(c.getCreatedAt()));
				break;
			case LOCATION:
				boolean error = true;
				if (mListRowCallback != null) {
					Location location = mListRowCallback.getLocation();
					if (location != null) {
						error = false;
						try {
							Location ourLocation = new Location("");
							ourLocation
									.setLatitude(Double.valueOf(c.get(QRCloudUtils.DATABASE_PROP_LATITUDE).toString()));
							ourLocation.setLongitude(Double.valueOf(c.get(QRCloudUtils.DATABASE_PROP_LONGITUDE)
									.toString()));
							holder.messageInfo
									.setText(QRCloudUtils.getFormattedDistance(location.distanceTo(ourLocation)));
						} catch (NullPointerException e) {
							error = true;
						} catch (NumberFormatException e) {
							error = true;
						}
					}
				}
				if (error) {
					holder.messageInfo.setText("");
				}
				break;
			case RATING:
				Object rating = c.get(QRCloudUtils.DATABASE_PROP_RATING);
				rating = rating == null ? "0" : rating.toString();
				holder.messageInfo.setText("0".equals(rating) ? "" : "+" + rating);
				break;
			case OWNER:
				holder.messageInfo.setText(QRCloudUtils.getFormattedDate(c.getCreatedAt()));
				break;
		}

		holder.position = position;
		return convertView;
	}

	public class ViewHolder {
		public TextView messageText;
		public TextView messageInfo;
		public int position; // the index of this view in the list
	}
}
