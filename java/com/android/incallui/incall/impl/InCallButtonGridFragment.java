/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.incallui.incall.impl;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.common.FragmentUtils;
import com.android.incallui.incall.protocol.InCallButtonIds;

import java.util.List;
import java.util.Set;

/** Fragment for the in call buttons (mute, speaker, ect.). */
public class InCallButtonGridFragment extends Fragment {

  /** Maximum number of buttons that can be placed, including the ones in the overflow menu. */
  private static final int MAX_BUTTONS = 6;
  private static final int ROW_BUTTON_COUNT = 4;
  private static final int OVERFLOW_BUTTON_COUNT = 3;

  private final CheckableLabeledButton[] rowButtons = new CheckableLabeledButton[ROW_BUTTON_COUNT];
  private final CheckableLabeledButton[] overflowButtons =
      new CheckableLabeledButton[OVERFLOW_BUTTON_COUNT];
  private CheckableLabeledButton moreButton;
  private int overflowButtonCount;
  @Nullable private PopupMenu overflowMenu;
  private OnButtonGridCreatedListener buttonGridListener;

  public static Fragment newInstance() {
    return new InCallButtonGridFragment();
  }

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);
    buttonGridListener = FragmentUtils.getParent(this, OnButtonGridCreatedListener.class);
    Assert.isNotNull(buttonGridListener);
  }

  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle bundle) {
    View view = inflater.inflate(R.layout.incall_button_grid, parent, false);

    rowButtons[0] = view.findViewById(R.id.incall_first_button);
    rowButtons[1] = view.findViewById(R.id.incall_second_button);
    rowButtons[2] = view.findViewById(R.id.incall_third_button);
    rowButtons[3] = view.findViewById(R.id.incall_fourth_button);
    overflowButtons[0] = view.findViewById(R.id.incall_fifth_button);
    overflowButtons[1] = view.findViewById(R.id.incall_sixth_button);
    overflowButtons[2] = view.findViewById(R.id.incall_seventh_button);
    moreButton = view.findViewById(R.id.incall_more_button);
    moreButton.setOnClickListener(v -> showOverflowMenu());

    return view;
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle bundle) {
    super.onViewCreated(view, bundle);
    buttonGridListener.onButtonGridCreated(this);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (overflowMenu != null) {
      overflowMenu.dismiss();
      overflowMenu = null;
    }
    buttonGridListener.onButtonGridDestroyed();
  }

  public void onInCallScreenDialpadVisibilityChange(boolean isShowing) {
    for (CheckableLabeledButton button : rowButtons) {
      button.setImportantForAccessibility(
          isShowing
              ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
              : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    moreButton.setImportantForAccessibility(
        isShowing
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
  }

  public int updateButtonStates(
      List<ButtonController> buttonControllers,
      @Nullable ButtonChooser buttonChooser,
      int voiceNetworkType,
      int phoneType) {
    Set<Integer> allowedButtons = new ArraySet<>();
    Set<Integer> disabledButtons = new ArraySet<>();
    for (ButtonController controller : buttonControllers) {
      if (controller.isAllowed()) {
        allowedButtons.add(controller.getInCallButtonId());
        if (!controller.isEnabled()) {
          disabledButtons.add(controller.getInCallButtonId());
        }
      }
    }

    for (ButtonController controller : buttonControllers) {
      controller.setButton(null);
    }

    if (buttonChooser == null) {
      buttonChooser =
          ButtonChooserFactory.newButtonChooser(voiceNetworkType, false /* isWiFi */, phoneType);
    }

    int maxButtons = getResources().getInteger(R.integer.incall_num_rows) > 0 ? MAX_BUTTONS : 0;
    List<Integer> buttonsToPlace =
        buttonChooser.getButtonPlacement(maxButtons, allowedButtons, disabledButtons);

    // Everything fits into the row: show the buttons directly. Otherwise keep the last slot of
    // the row for the "more" button and move the remaining buttons into its popup menu.
    boolean needsOverflow = buttonsToPlace.size() > ROW_BUTTON_COUNT;
    int rowCount = needsOverflow ? ROW_BUTTON_COUNT - 1 : ROW_BUTTON_COUNT;

    for (int i = 0; i < ROW_BUTTON_COUNT; ++i) {
      if (i >= rowCount) {
        rowButtons[i].setVisibility(View.GONE);
      } else if (i >= buttonsToPlace.size()) {
        rowButtons[i].setVisibility(View.INVISIBLE);
      } else {
        placeButton(buttonsToPlace.get(i), rowButtons[i]);
      }
    }
    for (int i = 0; i < OVERFLOW_BUTTON_COUNT; ++i) {
      int index = rowCount + i;
      if (needsOverflow && index < buttonsToPlace.size()) {
        placeButton(buttonsToPlace.get(index), overflowButtons[i]);
      }
    }
    overflowButtonCount =
        needsOverflow ? Math.min(buttonsToPlace.size() - rowCount, OVERFLOW_BUTTON_COUNT) : 0;
    moreButton.setVisibility(needsOverflow ? View.VISIBLE : View.GONE);
    if (!needsOverflow && overflowMenu != null) {
      overflowMenu.dismiss();
    }

    return buttonsToPlace.size();
  }

  private void placeButton(@InCallButtonIds int buttonId, CheckableLabeledButton button) {
    buttonGridListener.getButtonController(buttonId).setButton(button);
  }

  private void showOverflowMenu() {
    PopupMenu menu = new PopupMenu(requireContext(), moreButton);
    menu.setForceShowIcon(true);
    for (int i = 0; i < OVERFLOW_BUTTON_COUNT; ++i) {
      CheckableLabeledButton button = overflowButtons[i];
      // Controllers leave buttons that are not allowed invisible.
      if (i >= overflowButtonCount || button.getVisibility() != View.VISIBLE) {
        continue;
      }
      MenuItem item = menu.getMenu().add(Menu.NONE, i, i, button.getLabelText());
      item.setEnabled(button.isEnabled());
      if (button.isCheckable()) {
        item.setCheckable(true);
        item.setChecked(button.isChecked());
      }
      Drawable icon = button.getIconDrawable();
      if (icon != null && icon.getConstantState() != null) {
        icon = icon.getConstantState().newDrawable(getResources()).mutate();
        icon.setTint(button.getIconTintList().getDefaultColor());
        item.setIcon(icon);
      }
    }
    if (!menu.getMenu().hasVisibleItems()) {
      return;
    }
    menu.setOnMenuItemClickListener(
        item -> {
          overflowButtons[item.getItemId()].performClick();
          return true;
        });
    menu.setOnDismissListener(m -> overflowMenu = null);
    overflowMenu = menu;
    menu.show();
  }

  /** Interface to let the listener know the status of the button grid. */
  public interface OnButtonGridCreatedListener {
    void onButtonGridCreated(InCallButtonGridFragment inCallButtonGridFragment);
    void onButtonGridDestroyed();

    ButtonController getButtonController(@InCallButtonIds int id);
  }
}
