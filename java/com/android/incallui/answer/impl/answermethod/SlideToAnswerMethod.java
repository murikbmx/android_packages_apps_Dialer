/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui.answer.impl.answermethod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.LogUtil;

/**
 * Answer method with a horizontal pill: drag the thumb to the right to answer, to the left to
 * decline.
 */
public class SlideToAnswerMethod extends AnswerMethod {

  private static final String STATE_HINT_TEXT = "hintText";
  private static final String STATE_INCOMING_WILL_DISCONNECT = "incomingWillDisconnect";

  /** Fraction of the travel distance after which releasing the thumb commits the action. */
  private static final float COMMIT_THRESHOLD = 0.7f;
  /** Minimum fraction of travel for a fling to commit the action. */
  private static final float FLING_MIN_PROGRESS = 0.25f;
  private static final float FLING_MIN_VELOCITY_DP = 800;
  private static final long COMMIT_ANIMATION_MILLIS = 150;
  private static final long RESET_ANIMATION_MILLIS = 350;
  private static final long HINT_ANIMATION_MILLIS = 900;
  private static final long RING_ANIMATION_MILLIS = 1800;
  private static final long RING_ANIMATION_DELAY_MILLIS = 1200;

  private View track;
  private View thumb;
  private View thumbIcon;
  private View answerLabel;
  private View declineLabel;
  private View answerGlow;
  private View declineGlow;
  private TextView hintTextView;

  @Nullable private CharSequence hintText;
  private boolean incomingWillDisconnect;
  private boolean actionCommitted;
  private boolean showingTapHint;

  @Nullable private ValueAnimator thumbAnimator;
  @Nullable private ObjectAnimator ringAnimator;
  @Nullable private VelocityTracker velocityTracker;
  private float touchDownX;
  private float thumbStartTranslation;
  private boolean dragging;
  private int touchSlop;

  private final Runnable ringRunnable = this::startRingAnimation;
  private final Runnable hideTapHintRunnable =
      () -> {
        showingTapHint = false;
        updateHintText();
      };

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);
    if (bundle != null) {
      incomingWillDisconnect = bundle.getBoolean(STATE_INCOMING_WILL_DISCONNECT);
      hintText = bundle.getCharSequence(STATE_HINT_TEXT);
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle bundle) {
    super.onSaveInstanceState(bundle);
    bundle.putBoolean(STATE_INCOMING_WILL_DISCONNECT, incomingWillDisconnect);
    bundle.putCharSequence(STATE_HINT_TEXT, hintText);
  }

  @Nullable
  @Override
  @SuppressLint("ClickableViewAccessibility")
  public View onCreateView(
      @NonNull LayoutInflater layoutInflater,
      @Nullable ViewGroup viewGroup,
      @Nullable Bundle bundle) {
    View view = layoutInflater.inflate(R.layout.slide_to_answer_method, viewGroup, false);
    track = view.findViewById(R.id.slide_track);
    thumb = view.findViewById(R.id.slide_thumb);
    thumbIcon = view.findViewById(R.id.slide_thumb_icon);
    answerLabel = view.findViewById(R.id.slide_answer_label);
    declineLabel = view.findViewById(R.id.slide_decline_label);
    answerGlow = view.findViewById(R.id.slide_answer_glow);
    declineGlow = view.findViewById(R.id.slide_decline_glow);
    hintTextView = view.findViewById(R.id.slide_hint_text);
    touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();

    thumb.setOnTouchListener((v, event) -> onThumbTouch(event));
    updateHintText();
    return view;
  }

  @Override
  public void onStart() {
    super.onStart();
    thumb.postDelayed(ringRunnable, RING_ANIMATION_DELAY_MILLIS);
  }

  @Override
  public void onStop() {
    super.onStop();
    stopRingAnimation();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    thumb.removeCallbacks(hideTapHintRunnable);
    if (thumbAnimator != null) {
      thumbAnimator.cancel();
      thumbAnimator = null;
    }
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
  }

  @Override
  public void setHintText(@Nullable CharSequence hintText) {
    this.hintText = hintText;
    updateHintText();
  }

  @Override
  public void setShowIncomingWillDisconnect(boolean incomingWillDisconnect) {
    this.incomingWillDisconnect = incomingWillDisconnect;
    updateHintText();
  }

  private void updateHintText() {
    if (hintTextView == null) {
      return;
    }
    CharSequence text = null;
    if (actionCommitted) {
      text = null;
    } else if (showingTapHint) {
      text = getText(R.string.call_incoming_slide_hint);
    } else if (!TextUtils.isEmpty(hintText)) {
      text = hintText;
    } else if (incomingWillDisconnect) {
      text = getText(R.string.call_incoming_will_disconnect);
    }
    if (text != null) {
      hintTextView.setText(text);
      hintTextView.animate().alpha(1f).start();
    } else {
      hintTextView.animate().alpha(0f).start();
    }
  }

  private boolean onThumbTouch(MotionEvent event) {
    if (actionCommitted) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        stopRingAnimation();
        if (thumbAnimator != null) {
          thumbAnimator.cancel();
        }
        touchDownX = event.getRawX();
        thumbStartTranslation = thumb.getTranslationX();
        dragging = false;
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
        // Keep the parents from stealing the gesture.
        thumb.getParent().requestDisallowInterceptTouchEvent(true);
        thumb.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start();
        return true;

      case MotionEvent.ACTION_MOVE:
        if (velocityTracker != null) {
          velocityTracker.addMovement(event);
        }
        float dx = event.getRawX() - touchDownX;
        if (!dragging && Math.abs(dx) > touchSlop) {
          dragging = true;
        }
        if (dragging) {
          setThumbTranslation(thumbStartTranslation + dx);
        }
        return true;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        thumb.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
        float velocity = 0;
        if (velocityTracker != null) {
          velocityTracker.addMovement(event);
          velocityTracker.computeCurrentVelocity(1000);
          velocity = velocityTracker.getXVelocity();
          velocityTracker.recycle();
          velocityTracker = null;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
          animateThumbBack();
        } else if (!dragging) {
          showTapHint();
        } else {
          onThumbReleased(velocity);
        }
        return true;

      default:
        return false;
    }
  }

  private void onThumbReleased(float velocity) {
    float progress = getProgress();
    float minFlingVelocity = FLING_MIN_VELOCITY_DP * getResources().getDisplayMetrics().density;
    boolean isFling =
        Math.abs(velocity) > minFlingVelocity
            && Math.signum(velocity) == Math.signum(progress)
            && Math.abs(progress) > FLING_MIN_PROGRESS;
    if (Math.abs(progress) >= COMMIT_THRESHOLD || isFling) {
      commit(progress > 0);
    } else {
      animateThumbBack();
    }
  }

  private float getMaxTranslation() {
    int inset = getResources().getDimensionPixelSize(R.dimen.slide_thumb_inset);
    return Math.max(1, (track.getWidth() - thumb.getWidth()) / 2f - inset);
  }

  /** Progress in [-1, 1]: -1 is fully declined and 1 is fully answered, in visual direction. */
  private float getProgress() {
    float progress = thumb.getTranslationX() / getMaxTranslation();
    return isRtl() ? -progress : progress;
  }

  private boolean isRtl() {
    return track.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
  }

  private void setThumbTranslation(float translation) {
    float max = getMaxTranslation();
    float clamped = Math.max(-max, Math.min(max, translation));
    float previousProgress = getProgress();
    thumb.setTranslationX(clamped);
    float progress = getProgress();

    // Tick once when crossing the point where releasing the thumb will commit the action.
    if ((Math.abs(previousProgress) < COMMIT_THRESHOLD) != (Math.abs(progress) < COMMIT_THRESHOLD)) {
      thumb.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    float answer = Math.max(0, progress);
    float decline = Math.max(0, -progress);
    answerGlow.setAlpha(answer);
    declineGlow.setAlpha(decline);
    // Fade out the label the thumb moves over, and the other one too since it is not the target.
    answerLabel.setAlpha(1 - Math.min(1, Math.abs(progress) * 2) * (answer > 0 ? 1f : 0.6f));
    declineLabel.setAlpha(1 - Math.min(1, Math.abs(progress) * 2) * (decline > 0 ? 1f : 0.6f));
    // Turn the handset into the "hang up" position while declining.
    thumbIcon.setRotation(decline * 135);

    getParent().onAnswerProgressUpdate(progress);
  }

  private void animateThumbTo(float target, long duration, Animator.AnimatorListener listener) {
    if (thumbAnimator != null) {
      thumbAnimator.cancel();
    }
    thumbAnimator = ValueAnimator.ofFloat(thumb.getTranslationX(), target);
    thumbAnimator.setDuration(duration);
    thumbAnimator.addUpdateListener(
        animation -> setThumbTranslation((float) animation.getAnimatedValue()));
    if (listener != null) {
      thumbAnimator.addListener(listener);
    }
    thumbAnimator.start();
  }

  private void animateThumbBack() {
    animateThumbTo(0, RESET_ANIMATION_MILLIS, null);
    thumbAnimator.setInterpolator(new OvershootInterpolator(1.5f));
    thumbAnimator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            thumb.postDelayed(ringRunnable, RING_ANIMATION_DELAY_MILLIS);
          }
        });
  }

  private void commit(boolean answer) {
    LogUtil.i("SlideToAnswerMethod.commit", answer ? "answer" : "reject");
    actionCommitted = true;
    stopRingAnimation();
    updateHintText();
    thumb.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    float target = getMaxTranslation() * (answer != isRtl() ? 1 : -1);
    animateThumbTo(
        target,
        COMMIT_ANIMATION_MILLIS,
        new AnimatorListenerAdapter() {
          private boolean canceled;

          @Override
          public void onAnimationCancel(Animator animation) {
            canceled = true;
          }

          @Override
          public void onAnimationEnd(Animator animation) {
            if (canceled || getParent() == null) {
              return;
            }
            if (answer) {
              getParent().answerFromMethod();
            } else {
              getParent().rejectFromMethod();
            }
          }
        });
  }

  /** Nudges the thumb towards "answer" and shows how to use the slider. */
  private void showTapHint() {
    showingTapHint = true;
    updateHintText();
    thumb.removeCallbacks(hideTapHintRunnable);
    thumb.postDelayed(hideTapHintRunnable, 3000);

    float nudge = getMaxTranslation() * 0.3f * (isRtl() ? -1 : 1);
    if (thumbAnimator != null) {
      thumbAnimator.cancel();
    }
    thumbAnimator = ValueAnimator.ofFloat(0, nudge, 0, -nudge * 0.5f, 0);
    thumbAnimator.setDuration(HINT_ANIMATION_MILLIS);
    thumbAnimator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
    thumbAnimator.addUpdateListener(
        animation -> setThumbTranslation((float) animation.getAnimatedValue()));
    thumbAnimator.start();
  }

  /** Wiggles the phone icon like a ringing handset while the thumb is idle. */
  private void startRingAnimation() {
    if (thumbIcon == null || actionCommitted || ringAnimator != null) {
      return;
    }
    // Keyframes are evenly spaced, the trailing zeros make a pause between the wiggles.
    ringAnimator =
        ObjectAnimator.ofFloat(
            thumbIcon, View.ROTATION, 0, -18, 16, -14, 12, -8, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    ringAnimator.setDuration(RING_ANIMATION_MILLIS);
    ringAnimator.setRepeatCount(ValueAnimator.INFINITE);
    ringAnimator.start();
  }

  private void stopRingAnimation() {
    if (thumb != null) {
      thumb.removeCallbacks(ringRunnable);
    }
    if (ringAnimator != null) {
      ringAnimator.cancel();
      ringAnimator = null;
      thumbIcon.setRotation(0);
    }
  }
}
