package pct.droid.fragments;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import pct.droid.R;
import pct.droid.base.preferences.Prefs;
import pct.droid.base.providers.media.models.Media;
import pct.droid.base.subs.Caption;
import pct.droid.base.utils.AnimUtils;
import pct.droid.base.utils.PixelUtils;
import pct.droid.base.utils.PrefUtils;
import pct.droid.base.utils.StringUtils;
import timber.log.Timber;

public class VideoPlayerFragment extends BaseVideoPlayerFragment implements View.OnSystemUiVisibilityChangeListener {

	@InjectView(R.id.toolbar)
	Toolbar mToolbar;
	@InjectView(R.id.progress_indicator)
	ProgressBar mProgressIndicator;
	@InjectView(R.id.video_surface)
	SurfaceView videoSurface;
	@InjectView(R.id.subtitle_text)
	TextView mSubtitleText;
	@InjectView(R.id.control_layout)
	RelativeLayout mControlLayout;
	@InjectView(R.id.player_info)
	TextView mPlayerInfo;
	@InjectView(R.id.control_bar)
	SeekBar mControlBar;
	@InjectView(R.id.play_butotn)
	ImageButton playButton;
	@InjectView(R.id.currentTime)
	TextView mCurrentTimeTextView;
	@InjectView(R.id.length_time)
	TextView lengthTime;
	View mDecorView;

	private AudioManager mAudioManager;

	private long mLastSystemShowTime = System.currentTimeMillis();

	private static final int FADE_OUT_OVERLAY = 5000;
	private static final int FADE_OUT_INFO = 1000;

	private int mLastSystemUIVisibility;
	private boolean mOverlayVisible = true;

	private Handler mDisplayHandler;

	private static final int TOUCH_NONE = 0;
	private static final int TOUCH_VOLUME = 1;
	private static final int TOUCH_BRIGHTNESS = 2;
	private static final int TOUCH_SEEK = 3;
	private int mTouchAction;
	private int mSurfaceYDisplayRange;
	private float mTouchY, mTouchX;

	private int mAudioMax;
	private float mVol;

	private boolean mIsFirstBrightnessGesture = true;
	private float mRestoreBrightness = -1f;


	@Override public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_videoplayer, container, false);
		view.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View v, MotionEvent event) {
				return onTouchEvent(event);
			}
		});
		return view;
	}

	@Override public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		ButterKnife.inject(this, view);
	}

	@Override public void onActivityCreated(@Nullable Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		setRetainInstance(true);

		getActionBarActivity().setSupportActionBar(mToolbar);

		videoSurface.setVisibility(View.VISIBLE);

		mToolbar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                onTouchEvent(event);
                return true;
            }
        });

		/* Services and miscellaneous */
		mAudioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
		mAudioMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

		mDisplayHandler = new Handler(Looper.getMainLooper());

		mDecorView = getActivity().getWindow().getDecorView();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			mDecorView.setOnSystemUiVisibilityChangeListener(this);
		}

		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {
			getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
			getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
			mToolbar.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                    getResources().getDimensionPixelSize(R.dimen.abc_action_bar_default_height_material) +
                            PixelUtils.getStatusBarHeight(getActivity())));
			mToolbar.setPadding(mToolbar.getPaddingLeft(), PixelUtils.getStatusBarHeight(getActivity()), mToolbar.getPaddingRight(),
					mToolbar.getPaddingBottom());
		}

		if (null != mCallback.getData()) {
			Media media = mCallback.getData();
			if (media != null && media.title != null) {
				if (null != mCallback.getQuality()) {
					getActionBarActivity().getSupportActionBar().setTitle(
							getString(R.string.now_playing) + ": " + media.title + " (" + mCallback.getQuality() + ")");
				} else {
					getActionBarActivity().getSupportActionBar().setTitle(getString(R.string.now_playing) + ": " + media.title);
				}
			} else {
				getActionBarActivity().getSupportActionBar().setTitle(getString(R.string.now_playing));
			}

		} else {
			getActionBarActivity().getSupportActionBar().setTitle(getString(R.string.now_playing));
		}
		getActionBarActivity().getSupportActionBar().setDisplayHomeAsUpEnabled(true);


		mSubtitleText.setTextColor(PrefUtils.get(getActivity(), Prefs.SUBTITLE_COLOR, Color.WHITE));
		mSubtitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, PrefUtils.get(getActivity(), Prefs.SUBTITLE_SIZE, 16));

		mControlBar.setOnSeekBarChangeListener(mOnControlBarListener);

		getActionBarActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}

	@Override
	public void onStop() {
		super.onStop();
		//restore brightness
		if (mRestoreBrightness != -1f) {
			int brightness = (int) (mRestoreBrightness * 255f);
			Settings.System.putInt(getActionBarActivity().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
		} else {
			Settings.System
					.putInt(getActionBarActivity().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
							Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
		}
	}

	@Override public void onDestroyView() {
		super.onDestroyView();
		mAudioManager = null;
	}


	private ActionBarActivity getActionBarActivity() {
		return (ActionBarActivity) getActivity();
	}

	@Override protected SurfaceView getVideoSurface() {
		return videoSurface;
	}


	public boolean onTouchEvent(MotionEvent event) {
		DisplayMetrics screen = new DisplayMetrics();
		getActionBarActivity().getWindowManager().getDefaultDisplay().getMetrics(screen);

		if (mSurfaceYDisplayRange == 0) {
			mSurfaceYDisplayRange = Math.min(screen.widthPixels, screen.heightPixels);
		}

		float y_changed = event.getRawY() - mTouchY;
		float x_changed = event.getRawX() - mTouchX;

		// coef is the gradient's move to determine a neutral zone
		float coef = Math.abs(y_changed / x_changed);
		float xgesturesize = ((x_changed / screen.xdpi) * 2.54f);

		int[] offset = new int[2];
		videoSurface.getLocationOnScreen(offset);

		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				// Audio
				mTouchY = event.getRawY();
				mVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
				mTouchAction = TOUCH_NONE;
				// Seek
				mTouchX = event.getRawX();
				break;

			case MotionEvent.ACTION_MOVE:
				if (coef > 2) {
					mTouchY = event.getRawY();
					mTouchX = event.getRawX();
					if ((int) mTouchX > (screen.widthPixels / 2)) {
						doVolumeTouch(y_changed);
					}
					if ((int) mTouchX < (screen.widthPixels / 2)) {
						doBrightnessTouch(y_changed);
					}
				} else {
					// Seek (Right or Left move)
					doSeekTouch(coef, xgesturesize, false);
				}
				break;

			case MotionEvent.ACTION_UP:
				if (mTouchAction == TOUCH_NONE) {
					if (!mOverlayVisible) {
						showOverlay();
					} else {
						hideOverlay();
					}
				} else {
					showOverlay();
				}

				doSeekTouch(coef, xgesturesize, true);
				break;
		}
		return true;
	}


	@Override
	public void onSystemUiVisibilityChange(int visibility) {
		if ((mLastSystemUIVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0 &&
				(visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
			showOverlay();
		}

		mLastSystemUIVisibility = visibility;
	}

	private void doSeekTouch(float coef, float gesturesize, boolean seek) {
		// No seek action if coef > 0.5 and gesturesize < 1cm
		if (coef > 0.5 || Math.abs(gesturesize) < 1) {
			return;
		}

		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_SEEK) {
			return;
		}
		mTouchAction = TOUCH_SEEK;

		// Size of the jump, 10 minutes max (600000), with a bi-cubic progression, for a 8cm gesture
		int jump = (int) (Math.signum(gesturesize) * ((600000 * Math.pow((gesturesize / 8), 4)) + 3000));

		// Adjust the jump
		if ((jump > 0) && ((getCurrentTime() + jump) > getDuration())) {
			jump = (int) (getDuration() - getCurrentTime());
		}
		if ((jump < 0) && ((getCurrentTime() + jump) < 0)) {
			jump = (int) -getCurrentTime();
		}

		if (seek && getDuration() > 0) {
			seek(jump);
		}

		if (getDuration() > 0) {
			showPlayerInfo(String.format("%s%s (%s)", jump >= 0 ? "+" : "", StringUtils.millisToString(jump),
					StringUtils.millisToString(getCurrentTime() + jump)));
		}
	}

	private void doVolumeTouch(float y_changed) {
		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_VOLUME)
			return;
		float delta = -((y_changed * 2f / mSurfaceYDisplayRange) * mAudioMax);
		mVol += delta;
        int vol = (int) Math.min(Math.max(mVol, 0), mAudioMax);
		if (delta != 0f) {
			setAudioVolume(vol);
		}
	}

	private void setAudioVolume(int vol) {
		mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
		mTouchAction = TOUCH_VOLUME;
		showPlayerInfo(getString(R.string.volume) + '\u00A0' + Integer.toString(vol));
	}

	private void initBrightnessTouch() {
		float brightnesstemp = 0.6f;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO &&
					Settings.System.getInt(getActionBarActivity().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) ==
							Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
				Settings.System.putInt(getActionBarActivity().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
						Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
				mRestoreBrightness = -1f;
			} else {
				mRestoreBrightness = brightnesstemp =
						android.provider.Settings.System.getInt(getActionBarActivity().getContentResolver(),
								android.provider.Settings.System
										.SCREEN_BRIGHTNESS) /
								255.0f;
			}
		} catch (Settings.SettingNotFoundException e) {
			e.printStackTrace();
		}
		WindowManager.LayoutParams lp = getActionBarActivity().getWindow().getAttributes();
		lp.screenBrightness = brightnesstemp;
		getActionBarActivity().getWindow().setAttributes(lp);
		mIsFirstBrightnessGesture = false;
	}

	private void doBrightnessTouch(float y_changed) {
		if (mTouchAction != TOUCH_NONE && mTouchAction != TOUCH_BRIGHTNESS)
			return;
		if (mIsFirstBrightnessGesture) initBrightnessTouch();
		mTouchAction = TOUCH_BRIGHTNESS;

		// Set delta : 2f is arbitrary for now, it possibly will change in the future
		float delta = -y_changed / mSurfaceYDisplayRange * 2f;

		changeBrightness(delta);
	}

	private void changeBrightness(float delta) {
		// Estimate and adjust Brightness
		WindowManager.LayoutParams lp = getActionBarActivity().getWindow().getAttributes();
		lp.screenBrightness = Math.min(Math.max(lp.screenBrightness + delta, 0.01f), 1);
		// Set Brightness
		getActionBarActivity().getWindow().setAttributes(lp);
		showPlayerInfo(getString(R.string.brightness) + '\u00A0' + Math.round(lp.screenBrightness * 15));
	}


	@Override
	protected void onErrorEncountered() {
		/* Encountered Error, exit player with a message */
		AlertDialog dialog = new AlertDialog.Builder(getActivity())
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						getActionBarActivity().finish();
					}
				})
				.setTitle("Encountered error")
				.setMessage("Encountered error")
				.create();
		dialog.show();
	}


	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void showOverlay() {
		if (!mOverlayVisible) {
			updatePlayPauseState();

			AnimUtils.fadeIn(mControlLayout);
			AnimUtils.fadeIn(mToolbar);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
				mDecorView.setSystemUiVisibility(uiOptions);
			} else {
				getActionBarActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
				getActionBarActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			}

			mLastSystemShowTime = System.currentTimeMillis();
		}

		mOverlayVisible = true;
		mDisplayHandler.removeCallbacks(mOverlayHideRunnable);
		mDisplayHandler.postDelayed(mOverlayHideRunnable, FADE_OUT_OVERLAY);
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public void hideOverlay() {
		// Can only hide 1000 millisec after show, because navbar doesn't seem to hide otherwise.
		if (mLastSystemShowTime + 1000 < System.currentTimeMillis()) {
			AnimUtils.fadeOut(mControlLayout);
			AnimUtils.fadeOut(mToolbar);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
				mDecorView.setSystemUiVisibility(uiOptions);
			} else {
				getActionBarActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
				getActionBarActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
			}

			mDisplayHandler.removeCallbacks(mOverlayHideRunnable);
			mOverlayVisible = false;
		}
	}

	protected void showPlayerInfo(String text) {
		mPlayerInfo.setVisibility(View.VISIBLE);
		mPlayerInfo.setText(text);
		mDisplayHandler.removeCallbacks(mInfoHideRunnable);
		mDisplayHandler.postDelayed(mInfoHideRunnable, FADE_OUT_INFO);
	}

	private void hidePlayerInfo() {
		if (mPlayerInfo.getVisibility() == View.VISIBLE) {
			Animation fadeOutAnim = AnimationUtils.loadAnimation(getActivity(), android.R.anim.fade_out);
			mPlayerInfo.startAnimation(fadeOutAnim);
		}
		mPlayerInfo.setVisibility(View.INVISIBLE);
	}

	public void updatePlayPauseState() {
		if (isPlaying()) {
			playButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_av_pause));
		} else {
			playButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_av_play));
		}
	}

	private SeekBar.OnSeekBarChangeListener mOnControlBarListener = new SeekBar.OnSeekBarChangeListener() {
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
			setSeeking(true);
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			setSeeking(false);
		}

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (fromUser && isSeeking()) {
				setLastSub(null);
				setCurrentTime(progress);
				VideoPlayerFragment.this.onProgressChanged(getCurrentTime(), getDuration());
				checkSubs();
			}
		}
	};

	@Override void onHardwareAccelerationError() {
		AlertDialog dialog = new AlertDialog.Builder(getActivity())
				.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						disableHardwareAcceleration();
						loadMedia();
					}
				})
				.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						getActionBarActivity().finish();
					}
				})
				.setTitle(R.string.hardware_acceleration_error_title)
				.setMessage(R.string.hardware_acceleration_error_message)
				.create();
		if (!getActionBarActivity().isFinishing())
			dialog.show();
	}

	private Runnable mOverlayHideRunnable = new Runnable() {
		@Override
		public void run() {
			hideOverlay();
		}
	};

	private Runnable mInfoHideRunnable = new Runnable() {
		@Override
		public void run() {
			hidePlayerInfo();
		}
	};

	@Override
	protected void showTimedCaptionText(final Caption text) {
		mDisplayHandler.post(new Runnable() {
			@Override
			public void run() {
				if (text == null) {
					if (mSubtitleText.getText().length() > 0) {
						mSubtitleText.setText("");
					}
					return;
				}
				SpannableStringBuilder styledString = (SpannableStringBuilder) Html.fromHtml(text.content);

				ForegroundColorSpan[] toRemoveSpans = styledString.getSpans(0, styledString.length(), ForegroundColorSpan.class);
				for (ForegroundColorSpan remove : toRemoveSpans) {
					styledString.removeSpan(remove);
				}

				if (!mSubtitleText.getText().toString().equals(styledString.toString())) {
					mSubtitleText.setText(styledString);
				}
			}
		});
	}

	@Override
    protected void setProgressVisible(boolean visible) {
		mProgressIndicator.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	/**
	 * Updates the overlay when the media playback progress has changed
	 * @param currentTime
	 * @param duration
	 */
	@Override
    protected void onProgressChanged(long currentTime, long duration) {
		mControlBar.setMax((int) duration);
		mControlBar.setProgress((int) currentTime);
		mControlBar.setSecondaryProgress(0); // hack to make the secondary progress appear on Android 5.0
		mControlBar.setSecondaryProgress(getStreamerProgress());
		if (getCurrentTime() >= 0) mCurrentTimeTextView.setText(StringUtils.millisToString(currentTime));
		if (getDuration() >= 0) lengthTime.setText(StringUtils.millisToString(duration));

		mControlBar.setSecondaryProgress(0); // hack to make the secondary progress appear on Android 5.0
		mControlBar.setSecondaryProgress(getStreamerProgress());
	}

	@OnClick(R.id.play_butotn)
    void onPlayPauseClick(){
		togglePlayPause();
	}

	@OnClick(R.id.rewindButton)
    void onRewindClick(){
		seekBackwardClick();
	}

	@OnClick(R.id.forwardButton)
    void onForwardClick(){
		seekForwardClick();
	}

	@OnClick(R.id.scaleButton)
    void onScaleClick(){
		scaleClick();
	}

	@OnClick(R.id.subsButton)
    void onSubsClick(){
		subsClick();
	}


}
