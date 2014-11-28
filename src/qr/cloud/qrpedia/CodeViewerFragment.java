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
import qr.cloud.util.Typefaces;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.result.ParsedResultType;

public class CodeViewerFragment extends SherlockFragment {

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View contentView = inflater.inflate(R.layout.activity_code_viewer, container, false);

		// setHasOptionsMenu(true); disabled for now - see below

		// load the code's contents
		Bundle launchState = getArguments();
		if (launchState != null) {

			TextView titleText = (TextView) contentView.findViewById(R.id.code_info_title);
			titleText.setText(getString(R.string.result_type,
					getString(launchState.getInt(getString(R.string.key_display_title)))));

			// interpret the type
			TextView typeText = (TextView) contentView.findViewById(R.id.code_info_type);
			BarcodeFormat barcodeFormat = BarcodeFormat.UPC_A; // default to products
			try {
				barcodeFormat = BarcodeFormat.valueOf(launchState.getString(QRCloudUtils.DATABASE_PROP_FORMAT));
			} catch (IllegalArgumentException e) {
			}
			String formattedName = QRCloudUtils.toDisplayCase(barcodeFormat.name());
			formattedName = formattedName.replace("Ean", "EAN").replace("Itf", "ITF").replace("Pdf", "PDF")
					.replace("Qr", "QR").replace("Rss", "RSS").replace("Upc", "UPC").replace("_a", "_A")
					.replace("_e", "_E").replace("_", " "); // hack"
			typeText.setText(formattedName);

			TextView contentsText = (TextView) contentView.findViewById(R.id.code_info_contents);
			contentsText.setText(launchState.getString(getString(R.string.key_display_contents)));

			// linkify where appropriate
			ParsedResultType barcodeType = ParsedResultType.TEXT; // default to text
			try {
				barcodeType = ParsedResultType.valueOf(launchState.getString(QRCloudUtils.DATABASE_PROP_TYPE));
			} catch (IllegalArgumentException e) {
			}
			if (barcodeType == ParsedResultType.EMAIL_ADDRESS || barcodeType == ParsedResultType.URI
					|| barcodeType == ParsedResultType.TEL || barcodeType == ParsedResultType.GEO) {
				Linkify.addLinks(contentsText, Linkify.ALL);
			}

			// set the correct fonts
			Typeface defaultFont = Typefaces.get(getActivity(), getString(R.string.default_font));
			titleText.setTypeface(Typefaces.get(getActivity(), getString(R.string.default_font_bold)));
			typeText.setTypeface(defaultFont);
			contentsText.setTypeface(defaultFont);
		}

		return contentView;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		// TODO: this breaks existing menus (must also re-enable setOptionsMenu above if changing)
		// inflater.inflate(R.menu.fragment_code_viewer, menu);
	}
}
