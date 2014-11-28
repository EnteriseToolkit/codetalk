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

import qr.cloud.util.QRCloudUtils;
import qr.cloud.util.Typefaces;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.cloud.backend.android.CloudBackendSherlockFragmentActivity;
import com.google.cloud.backend.android.CloudCallbackHandler;
import com.google.cloud.backend.android.CloudEntity;

public class QuestionnaireActivity extends CloudBackendSherlockFragmentActivity {

	IndicatedViewFlipper mViewFlipper;
	Button mPrevButton;
	Button mNextButton;
	SeekBar mSeekBarQ1;
	SeekBar mSeekBarQ2;
	boolean mAnsweredQ1;
	boolean mAnsweredQ2;
	CheckBox mNoMessages;
	EditText mComments;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_questionnaire);

		mViewFlipper = (IndicatedViewFlipper) findViewById(R.id.view_flipper);
		mSeekBarQ1 = (SeekBar) findViewById(R.id.seek_bar_q1);
		mSeekBarQ1.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				mAnsweredQ1 = true;
				return false;
			}
		});
		mSeekBarQ2 = (SeekBar) findViewById(R.id.seek_bar_q2);
		mSeekBarQ2.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				mAnsweredQ2 = true;
				return false;
			}
		});

		mPrevButton = (Button) findViewById(R.id.question_prev);
		mPrevButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				int currentChild = mViewFlipper.getDisplayedChild();
				if (currentChild > 0) {
					mViewFlipper.setInAnimation(QuestionnaireActivity.this, android.R.anim.slide_in_left);
					mViewFlipper.setOutAnimation(QuestionnaireActivity.this, android.R.anim.slide_out_right);
					mViewFlipper.showPrevious();
				}
				currentChild = mViewFlipper.getDisplayedChild();
				if (currentChild == 0) {
					mPrevButton.setEnabled(false);
				}
				if (currentChild < mViewFlipper.getChildCount() - 1) {
					mNextButton.setText(R.string.btn_next);
				}
			}
		});

		mNextButton = (Button) findViewById(R.id.question_next);
		mNextButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				int currentChild = mViewFlipper.getDisplayedChild();
				if ((!mAnsweredQ1 && currentChild == 1)
						|| (!mAnsweredQ2 && !mNoMessages.isChecked() && currentChild == 2)) {
					Toast.makeText(QuestionnaireActivity.this, R.string.questionnaire_hint, Toast.LENGTH_SHORT).show();
					return;
				}
				if (currentChild < mViewFlipper.getChildCount() - 1) {
					mViewFlipper.setInAnimation(QuestionnaireActivity.this, R.anim.slide_in_right);
					mViewFlipper.setOutAnimation(QuestionnaireActivity.this, R.anim.slide_out_left);
					mViewFlipper.showNext();
				} else {
					postResults();
					return;
				}
				currentChild = mViewFlipper.getDisplayedChild();
				if (currentChild > 0) {
					mPrevButton.setEnabled(true);
				}
				if (currentChild == mViewFlipper.getChildCount() - 1) {
					mNextButton.setText(R.string.btn_finish);
				}
			}
		});

		mNoMessages = (CheckBox) findViewById(R.id.questionnaire_no_messages);
		mNoMessages.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (isChecked) {
					mSeekBarQ2.setEnabled(false);
				} else {
					mSeekBarQ2.setEnabled(true);
				}
			}
		});

		mComments = (EditText) findViewById(R.id.questionnaire_comments);

		// set fonts
		Typeface defaultFont = Typefaces.get(QuestionnaireActivity.this, getString(R.string.default_font));
		((TextView) findViewById(R.id.questionnaire_intro_title)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_intro)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_q1_title)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_q1)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_q2_title)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_q2)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_end_title)).setTypeface(defaultFont);
		((TextView) findViewById(R.id.questionnaire_end)).setTypeface(defaultFont);
		mComments.setTypeface(defaultFont);
		mNoMessages.setTypeface(defaultFont);

		Typeface boldFont = Typefaces.get(QuestionnaireActivity.this, getString(R.string.default_font_bold));
		mPrevButton.setTypeface(boldFont);
		mNextButton.setTypeface(boldFont);
		((TextView) findViewById(R.id.min_value_q1)).setTypeface(boldFont);
		((TextView) findViewById(R.id.max_value_q1)).setTypeface(boldFont);
		((TextView) findViewById(R.id.min_value_q2)).setTypeface(boldFont);
		((TextView) findViewById(R.id.max_value_q2)).setTypeface(boldFont);

		if (savedInstanceState != null) {
			mViewFlipper
					.setDisplayedChild(savedInstanceState.getInt(getString(R.string.key_questionnaire_progress), 0));
			mAnsweredQ1 = savedInstanceState.getBoolean(getString(R.string.key_questionnaire_q1_answered), false);
			mAnsweredQ2 = savedInstanceState.getBoolean(getString(R.string.key_questionnaire_q2_answered), false);
		}
		if (mViewFlipper.getDisplayedChild() == 0) {
			mPrevButton.setEnabled(false);
		} else {
			mPrevButton.setEnabled(true);
		}
		if (mViewFlipper.getDisplayedChild() == mViewFlipper.getChildCount() - 1) {
			mNextButton.setText(R.string.btn_finish);
		} else {
			mNextButton.setText(R.string.btn_next);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putInt(getString(R.string.key_questionnaire_progress), mViewFlipper.getDisplayedChild());
		savedInstanceState.putBoolean(getString(R.string.key_questionnaire_q1_answered), mAnsweredQ1);
		savedInstanceState.putBoolean(getString(R.string.key_questionnaire_q2_answered), mAnsweredQ2);
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public void onBackPressed() {
		// do nothing unless they've already completed
		SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
		if (sharedPreferences.getBoolean(getString(R.string.check_questionnaire_completed), false)) {
			super.onBackPressed();
		}
	}

	private void postResults() {
		// we've now completed the questionnaire
		SharedPreferences sharedPreferences = getCloudBackend().getSharedPreferences();
		Editor editor = sharedPreferences.edit();
		editor.putBoolean(getString(R.string.check_questionnaire_completed), true);
		editor.commit();

		findViewById(R.id.questionnaire_submit_progress).setVisibility(View.VISIBLE);

		// create a CloudEntity with the questionnaire answers
		CloudEntity newPost = new CloudEntity(QRCloudUtils.DATABASE_KIND_QUESTIONNAIRE);
		newPost.put(QRCloudUtils.DATABASE_PROP_Q1, mSeekBarQ1.getProgress() + 1); // min: 1
		newPost.put(QRCloudUtils.DATABASE_PROP_Q2, mSeekBarQ2.getProgress() + 1); // max: 7
		newPost.put(QRCloudUtils.DATABASE_PROP_Q2_INVALID, mNoMessages.isChecked());
		newPost.put(QRCloudUtils.DATABASE_PROP_OTHER_COMMENTS, mComments.getText().toString());

		// create a response handler that will receive the result or an error
		CloudCallbackHandler<CloudEntity> handler = new CloudCallbackHandler<CloudEntity>() {
			@Override
			public void onComplete(final CloudEntity result) {
				Toast.makeText(QuestionnaireActivity.this, R.string.questionnaire_thanks, Toast.LENGTH_SHORT).show();
				finish();
			}

			@Override
			public void onError(final IOException exception) {
				Toast.makeText(QuestionnaireActivity.this, R.string.questionnaire_thanks, Toast.LENGTH_SHORT).show();
				finish();
			}
		};

		// execute the insertion with the handler
		getCloudBackend().insert(newPost, handler);
	}
}
