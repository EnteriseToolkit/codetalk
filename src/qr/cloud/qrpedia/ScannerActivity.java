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
import java.util.Collection;

import qr.cloud.locale.QRpediaApplication;
import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.google.cloud.backend.android.CloudBackendSherlockFragmentActivity;
import com.google.scanner.DecoderActivityHandler;
import com.google.scanner.IDecoderActivity;
import com.google.scanner.ViewfinderView;
import com.google.scanner.camera.CameraConfigurationManager;
import com.google.scanner.camera.CameraManager;
import com.google.scanner.result.ResultHandler;
import com.google.scanner.result.ResultHandlerFactory;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;

public class ScannerActivity extends CloudBackendSherlockFragmentActivity implements IDecoderActivity,
		SurfaceHolder.Callback {

	public static final int EXIT_SCANNER_REQUEST = 4472; // (hgra)

	DecoderActivityHandler mHandler = null;
	ViewfinderView mViewfinderView = null;
	CameraManager mCameraManager = null;
	boolean mHasSurface = false;
	Collection<BarcodeFormat> mDecodeFormats = null;
	String mCharacterSet = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_scanner);
		mHandler = null;
		mHasSurface = false;

		// set up the fonts
		Typeface defaultFont = Typefaces.get(ScannerActivity.this, getString(R.string.default_font));
		((TextView) findViewById(R.id.scanner_status_view)).setTypeface(defaultFont);
	}

	@Override
	protected void onPostCreate() {
		super.onPostCreate();
		if (isAuthEnabled() && getCredentialAccountName() == null) { // go back if they didn't select an account
			startActivity(new Intent(ScannerActivity.this, WelcomeActivity.class));
			finish();
		}
	}

	@Override
	protected void onPostAuthenticate() {
		super.onPostAuthenticate();

		// on some devices the camera doesn't start if the authentication window was shown - a restart fixes this (hack)
		finish();
		startActivity(new Intent(ScannerActivity.this, ScannerActivity.class));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getSupportMenuInflater().inflate(R.menu.activity_scanner, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_my_items:
				startActivity(new Intent(ScannerActivity.this, MyTagsListActivity.class));
				return true;
			case R.id.menu_clippings_library:
				startActivity(new Intent(ScannerActivity.this, SavedTextListActivity.class));
				return true;
			case R.id.menu_visitor_guide:
				String videoId = "nrblN3f5sx0";
				try {
					Intent videoIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + videoId));
					startActivity(videoIntent);
				} catch (ActivityNotFoundException e) {
					try {
						startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.youtube.com/watch?v="
								+ videoId)));
					} catch (Throwable t) {
					}
				}
				return true;
			case R.id.menu_change_language:
				((QRpediaApplication) getApplication()).toggleEnglishWelsh(ScannerActivity.this);
				return true;
		}
		return false;
	}

	@Override
	protected void onResume() {
		super.onResume();

		// switching orientation while we scan is annoying - set to fixed
		CameraConfigurationManager.setScreenOrientationFixed(ScannerActivity.this, true);

		// CameraManager must be initialised here, not in onCreate().
		if (mCameraManager == null) {
			mCameraManager = new CameraManager(getApplication());
		}

		if (mViewfinderView == null) {
			mViewfinderView = (ViewfinderView) findViewById(R.id.scanner_viewfinder_view);
			mViewfinderView.setCameraManager(mCameraManager);
		}

		showScanner();

		SurfaceView surfaceView = (SurfaceView) findViewById(R.id.scanner_preview_view);
		SurfaceHolder surfaceHolder = surfaceView.getHolder();
		if (mHasSurface) {
			// the activity was paused but not stopped, so the surface still exists. Therefore
			// surfaceCreated() won't be called, so init the camera here.
			initCamera(surfaceHolder);
		} else {
			// install the callback and wait for surfaceCreated() to init the camera.
			surfaceHolder.addCallback(this);
			CameraConfigurationManager.setPushBuffers(surfaceHolder);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mHandler != null) {
			mHandler.quitSynchronously();
			mHandler = null;
		}

		mCameraManager.closeDriver();

		if (!mHasSurface) {
			SurfaceView surfaceView = (SurfaceView) findViewById(R.id.scanner_preview_view);
			SurfaceHolder surfaceHolder = surfaceView.getHolder();
			surfaceHolder.removeCallback(this);
		}

		// no need to save orientation any more
		CameraConfigurationManager.setScreenOrientationFixed(ScannerActivity.this, false);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_FOCUS || keyCode == KeyEvent.KEYCODE_CAMERA) {
			// handle these events so they don't launch the Camera app
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		if (holder == null) {
			// Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
		}
		if (!mHasSurface) {
			mHasSurface = true;
			initCamera(holder);
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		mHasSurface = false;
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		// Ignore
	}

	@Override
	public ViewfinderView getViewfinder() {
		return mViewfinderView;
	}

	@Override
	public Handler getHandler() {
		return mHandler;
	}

	@Override
	public CameraManager getCameraManager() {
		return mCameraManager;
	}

	@Override
	public void handleDecode(Result rawResult, Bitmap barcode) {
		drawResultPoints(barcode, rawResult);

		// increase the number of codes scanned
		SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
		int codesScanned = sharedPreferences.getInt(getString(R.string.check_codes_scanned), 0);
		Editor countEditor = sharedPreferences.edit();
		countEditor.putInt(getString(R.string.check_codes_scanned), codesScanned + 1);
		countEditor.commit();

		// TODO: ignore if resultHandler.areContentsSecure()?
		ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);
		Intent browseCodeIntent = new Intent(ScannerActivity.this, MessageViewerActivity.class);
		browseCodeIntent.putExtra(QRCloudUtils.DATABASE_PROP_CONTENTS, rawResult.getText());
		browseCodeIntent.putExtra(QRCloudUtils.DATABASE_PROP_TYPE, resultHandler.getType().name());
		browseCodeIntent.putExtra(QRCloudUtils.DATABASE_PROP_FORMAT, rawResult.getBarcodeFormat().name());
		browseCodeIntent.putExtra(getString(R.string.key_display_title), resultHandler.getDisplayTitle());
		browseCodeIntent.putExtra(getString(R.string.key_display_contents), resultHandler.getDisplayContents());
		browseCodeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP); // we want to close existing viewers
		startActivityForResult(browseCodeIntent, EXIT_SCANNER_REQUEST);
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case EXIT_SCANNER_REQUEST:
				if (data != null && data.getExtras() != null) {
					if (data.hasExtra(QRCloudUtils.DATABASE_KIND_BANS)
							|| data.hasExtra(QRCloudUtils.DATABASE_KIND_VERSIONS)) {
						// banned user, or using an older version - exit
						finish();
					}
				}
				break;

			default:
				super.onActivityResult(requestCode, resultCode, data);
		}
	}

	protected void drawResultPoints(Bitmap barcode, Result rawResult) {
		ResultPoint[] points = rawResult.getResultPoints();
		if (points != null && points.length > 0) {
			Canvas canvas = new Canvas(barcode);
			Paint paint = new Paint();
			paint.setColor(getResources().getColor(R.color.result_image_border));
			paint.setStrokeWidth(3.0f);
			paint.setStyle(Paint.Style.STROKE);
			Rect border = new Rect(2, 2, barcode.getWidth() - 2, barcode.getHeight() - 2);
			canvas.drawRect(border, paint);

			paint.setColor(getResources().getColor(R.color.result_points));
			if (points.length == 2) {
				paint.setStrokeWidth(4.0f);
				drawLine(canvas, paint, points[0], points[1]);
			} else if (points.length == 4
					&& (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A || rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
				// hacky special case -- draw two lines, for the barcode and metadata
				drawLine(canvas, paint, points[0], points[1]);
				drawLine(canvas, paint, points[2], points[3]);
			} else {
				paint.setStrokeWidth(10.0f);
				for (ResultPoint point : points) {
					canvas.drawPoint(point.getX(), point.getY(), paint);
				}
			}
		}
	}

	protected static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b) {
		canvas.drawLine(a.getX(), a.getY(), b.getX(), b.getY(), paint);
	}

	protected void showScanner() {
		mViewfinderView.setVisibility(View.VISIBLE);
	}

	protected void initCamera(SurfaceHolder surfaceHolder) {
		boolean error = false;
		try {
			mCameraManager.openDriver(surfaceHolder);
			// creating the handler starts the preview, which can also throw a RuntimeException.
			if (mHandler == null)
				mHandler = new DecoderActivityHandler(this, mDecodeFormats, mCharacterSet, mCameraManager);
		} catch (IOException ioe) {
			error = true;
			// Log.w(TAG, ioe);
		} catch (RuntimeException e) {
			error = true;
			// Barcode Scanner has seen crashes in the wild: RuntimeException: Fail to connect to camera service
			// Log.w(TAG, "Unexpected error initializing camera", e);
		}
		if (error) {
			((TextView) findViewById(R.id.scanner_status_view)).setText(R.string.msg_camera_error);
		}
	}
}
