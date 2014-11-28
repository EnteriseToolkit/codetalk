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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.ViewFlipper;

public class IndicatedViewFlipper extends ViewFlipper {

	private Paint mPaint = new Paint();

	public IndicatedViewFlipper(Context context, AttributeSet attrs) {
		super(context, attrs);
		mPaint.setAntiAlias(true);
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		super.dispatchDraw(canvas);

		Resources res = getResources();
		int childCount = getChildCount();
		float margin = res.getDimensionPixelSize(R.dimen.view_flipper_margin);
		float radius = res.getDimensionPixelSize(R.dimen.view_flipper_radius);
		float twoRM = 2 * (radius + margin);
		float cx = getWidth() / 2f - (twoRM * ((float) childCount / 2f)) + radius + margin;
		float cy = getHeight() - res.getDimensionPixelSize(R.dimen.activity_vertical_margin);

		canvas.save();
		for (int i = 0; i < childCount; i++) {
			if (i == getDisplayedChild()) {
				mPaint.setColor(res.getColor(R.color.viewflipper_indicator_selected));
				canvas.drawCircle(cx, cy, radius, mPaint);
			} else {
				mPaint.setColor(res.getColor(R.color.viewflipper_indicator_unselected));
				canvas.drawCircle(cx, cy, radius, mPaint);
			}
			cx += twoRM;
		}
		canvas.restore();
	}
}