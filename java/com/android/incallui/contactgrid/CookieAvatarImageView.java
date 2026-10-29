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

package com.android.incallui.contactgrid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

/**
 * Avatar clipped to a wavy "cookie" shape that slowly rotates while the view is shown. Only the
 * outline rotates, the photo itself stays upright.
 */
public class CookieAvatarImageView extends ImageView {

  private static final int LOBES = 9;
  /** Depth of the valleys between lobes, relative to the radius. */
  private static final float WAVE_DEPTH = 0.1f;
  private static final int POINTS = 180;
  private static final long ROTATION_DURATION_MILLIS = 20_000;

  private final Path shapePath = new Path();
  private final Path rotatedPath = new Path();
  private final Matrix rotationMatrix = new Matrix();
  private final ValueAnimator rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
  private float rotationDegrees;

  public CookieAvatarImageView(Context context) {
    this(context, null);
  }

  public CookieAvatarImageView(Context context, AttributeSet attrs) {
    super(context, attrs);
    setScaleType(ScaleType.CENTER_CROP);
    setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, Outline outline) {
            if (shapePath.isEmpty()) {
              outline.setEmpty();
              return;
            }
            rotationMatrix.setRotate(rotationDegrees, getWidth() / 2f, getHeight() / 2f);
            shapePath.transform(rotationMatrix, rotatedPath);
            outline.setPath(rotatedPath);
          }
        });
    setClipToOutline(true);

    rotationAnimator.setDuration(ROTATION_DURATION_MILLIS);
    rotationAnimator.setInterpolator(new LinearInterpolator());
    rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
    rotationAnimator.addUpdateListener(
        animation -> {
          rotationDegrees = (float) animation.getAnimatedValue();
          invalidateOutline();
        });
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    buildShape(w, h);
    invalidateOutline();
  }

  private void buildShape(int width, int height) {
    shapePath.reset();
    if (width <= 0 || height <= 0) {
      return;
    }
    float cx = width / 2f;
    float cy = height / 2f;
    float radius = Math.min(width, height) / 2f;
    for (int i = 0; i < POINTS; i++) {
      double theta = 2 * Math.PI * i / POINTS;
      float r = radius * (1 - WAVE_DEPTH * (1 - (float) Math.cos(LOBES * theta)) / 2);
      float x = cx + r * (float) Math.cos(theta);
      float y = cy + r * (float) Math.sin(theta);
      if (i == 0) {
        shapePath.moveTo(x, y);
      } else {
        shapePath.lineTo(x, y);
      }
    }
    shapePath.close();
  }

  @Override
  public void onVisibilityAggregated(boolean isVisible) {
    super.onVisibilityAggregated(isVisible);
    updateAnimation(isVisible && isAttachedToWindow());
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateAnimation(isShown());
  }

  @Override
  protected void onDetachedFromWindow() {
    updateAnimation(false);
    super.onDetachedFromWindow();
  }

  private void updateAnimation(boolean run) {
    if (run) {
      if (!rotationAnimator.isStarted()) {
        rotationAnimator.start();
        rotationAnimator.setCurrentFraction(rotationDegrees / 360f);
      } else if (rotationAnimator.isPaused()) {
        rotationAnimator.resume();
      }
    } else if (rotationAnimator.isRunning()) {
      rotationAnimator.pause();
    }
  }
}
