/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.deskclock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.design.widget.TabLayout.ViewPagerOnTabSelectedListener;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.android.deskclock.actionbarmenu.ActionBarMenuManager;
import com.android.deskclock.actionbarmenu.MenuItemControllerFactory;
import com.android.deskclock.actionbarmenu.NightModeMenuItemController;
import com.android.deskclock.actionbarmenu.SettingMenuItemController;
import com.android.deskclock.alarms.AlarmStateManager;
import com.android.deskclock.data.DataModel;
import com.android.deskclock.events.Events;
import com.android.deskclock.provider.Alarm;
import com.android.deskclock.uidata.TabListener;
import com.android.deskclock.uidata.UiDataModel;
import com.android.deskclock.uidata.UiDataModel.Tab;
import com.android.deskclock.widget.RtlViewPager;

import static android.support.v4.view.ViewPager.SCROLL_STATE_DRAGGING;
import static android.support.v4.view.ViewPager.SCROLL_STATE_IDLE;
import static android.support.v4.view.ViewPager.SCROLL_STATE_SETTLING;
import static com.android.deskclock.AnimatorUtils.getAlphaAnimator;
import static com.android.deskclock.AnimatorUtils.getScaleAnimator;

/**
 * The main activity of the application which displays 4 different tabs contains alarms, world
 * clocks, timers and a stopwatch.
 */
public class DeskClock extends BaseActivity
        implements LabelDialogFragment.AlarmLabelDialogHandler,
        RingtonePickerDialogFragment.RingtoneSelectionListener {

    /** Models the interesting state of display the {@link #mFab} button may inhabit. */
    private enum FabState { SHOWING, HIDE_ARMED, HIDING }

    /** Coordinates handling of context menu items. */
    private final ActionBarMenuManager mActionBarMenuManager = new ActionBarMenuManager(this);

    /** Shrinks the {@link #mFab}, {@link #mLeftButton} and {@link #mRightButton} to nothing. */
    private final AnimatorSet mHideAnimation = new AnimatorSet();

    /** Grows the {@link #mFab}, {@link #mLeftButton} and {@link #mRightButton} to natural sizes. */
    private final AnimatorSet mShowAnimation = new AnimatorSet();

    /** Automatically starts the {@link #mShowAnimation} after {@link #mHideAnimation} ends. */
    private final AnimatorListenerAdapter mAutoStartShowListener = new AutoStartShowListener();

    /** The current display state of the {@link #mFab}. */
    private FabState mFabState = FabState.SHOWING;

    /** The single floating-action button shared across all tabs in the user interface. */
    private ImageView mFab;

    /** The button left of the {@link #mFab} shared across all tabs in the user interface. */
    private ImageButton mLeftButton;

    /** The button right of the {@link #mFab} shared across all tabs in the user interface. */
    private ImageButton mRightButton;

    /** The ViewPager that pages through the fragments representing the content of the tabs. */
    private RtlViewPager mFragmentTabPager;

    /** Generates the fragments that are displayed by the {@link #mFragmentTabPager}. */
    private TabFragmentAdapter mFragmentTabPagerAdapter;

    /** The container that stores the tab headers. */
    private TabLayout mTabLayout;

    /** {@code true} when a settings change necessitates recreating this activity. */
    private boolean mRecreateActivity;

    @Override
    public void onNewIntent(Intent newIntent) {
        super.onNewIntent(newIntent);

        // Fragments may query the latest intent for information, so update the intent.
        setIntent(newIntent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            // Set the background color to initially match the theme value so that we can
            // smoothly transition to the dynamic color.
            final int backgroundColor = getResources().getColor(R.color.default_background);
            setBackgroundColor(backgroundColor, false /* animate */);
        }

        setContentView(R.layout.desk_clock);

        // Configure the toolbar.
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Configure the menu item controllers add behavior to the toolbar.
        mActionBarMenuManager
                .addMenuItemController(new SettingMenuItemController(this))
                .addMenuItemController(new NightModeMenuItemController(this))
                .addMenuItemController(MenuItemControllerFactory.getInstance()
                        .buildMenuItemControllers(this));

        // Inflate the menu during creation to avoid a double layout pass. Otherwise, the menu
        // inflation occurs *after* the initial draw and a second layout pass adds in the menu.
        onCreateOptionsMenu(toolbar.getMenu());

        // Create the tabs that make up the user interface.
        mTabLayout = (TabLayout) findViewById(R.id.sliding_tabs);
        for (int i = 0; i < UiDataModel.getUiDataModel().getTabCount(); i++) {
            final Tab tab = UiDataModel.getUiDataModel().getTab(i);
            mTabLayout.addTab(mTabLayout.newTab()
                    .setIcon(tab.getIconId())
                    .setContentDescription(tab.getContentDescriptionId()));
        }

        // Configure the buttons shared by the tabs.
        mFab = (ImageView) findViewById(R.id.fab);
        mLeftButton = (ImageButton) findViewById(R.id.left_button);
        mRightButton = (ImageButton) findViewById(R.id.right_button);

        mFab.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedDeskClockFragment().onFabClick(view);
            }
        });
        mLeftButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedDeskClockFragment().onLeftButtonClick(view);
            }
        });
        mRightButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getSelectedDeskClockFragment().onRightButtonClick(view);
            }
        });

        // Build the reusable animations that hide and show the fab and left/right buttons.
        final long duration = UiDataModel.getUiDataModel().getFabShowAndHideAnimationDuration();
        mHideAnimation
                .setDuration(duration)
                .play(getScaleAnimator(mFab, 1f, 0f))
                .with(getAlphaAnimator(mLeftButton, 1f, 0f))
                .with(getAlphaAnimator(mRightButton, 1f, 0f));

        mShowAnimation
                .setDuration(duration)
                .play(getScaleAnimator(mFab, 0f, 1f))
                .with(getAlphaAnimator(mLeftButton, 0f, 1f))
                .with(getAlphaAnimator(mRightButton, 0f, 1f));

        // Customize the view pager.
        mFragmentTabPagerAdapter = new TabFragmentAdapter(this);
        mFragmentTabPager = (RtlViewPager) findViewById(R.id.desk_clock_pager);
        // Keep all four tabs to minimize jank.
        mFragmentTabPager.setOffscreenPageLimit(3);
        // Set Accessibility Delegate to null so view pager doesn't intercept movements and
        // prevent the fab from being selected.
        mFragmentTabPager.setAccessibilityDelegate(null);
        // Mirror changes made to the selected page of the view pager into UiDataModel.
        mFragmentTabPager.setOnRTLPageChangeListener(new PageChangeWatcher());
        mFragmentTabPager.setAdapter(mFragmentTabPagerAdapter);

        // Selecting a tab implicitly selects a page in the view pager.
        mTabLayout.setOnTabSelectedListener(new ViewPagerOnTabSelectedListener(mFragmentTabPager));

        // Honor changes to the selected tab from outside entities.
        UiDataModel.getUiDataModel().addTabListener(new TabChangeWatcher());

        // Update the next alarm time on app startup because the user might have altered the data.
        AlarmStateManager.updateNextAlarm(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DataModel.getDataModel().setApplicationInForeground(true);

        // Honor the selected tab in case it changed while the app was paused.
        updateCurrentTab(UiDataModel.getUiDataModel().getSelectedTabIndex());
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        if (mRecreateActivity) {
            mRecreateActivity = false;

            // A runnable must be posted here or the new DeskClock activity will be recreated in a
            // paused state, even though it is the foreground activity.
            mFragmentTabPager.post(new Runnable() {
                @Override
                public void run() {
                    recreate();
                }
            });
        }
    }

    @Override
    public void onPause() {
        DataModel.getDataModel().setApplicationInForeground(false);
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mActionBarMenuManager.createOptionsMenu(menu, getMenuInflater());
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        mActionBarMenuManager.prepareShowMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return mActionBarMenuManager.handleMenuItemClick(item) || super.onOptionsItemSelected(item);
    }

    /**
     * Called by the LabelDialogFormat class after the dialog is finished.
     */
    @Override
    public void onDialogLabelSet(Alarm alarm, String label, String tag) {
        final Fragment frag = getFragmentManager().findFragmentByTag(tag);
        if (frag instanceof AlarmClockFragment) {
            ((AlarmClockFragment) frag).setLabel(alarm, label);
        }
    }

    /**
     * Called by the RingtonePickerDialogFragment class after the dialog is finished.
     */
    @Override
    public void onRingtoneSelected(Uri ringtoneUri, String fragmentTag) {
        final Fragment frag = getFragmentManager().findFragmentByTag(fragmentTag);
        if (frag instanceof AlarmClockFragment) {
            ((AlarmClockFragment) frag).setRingtone(ringtoneUri);
        }
    }

    public ImageView getFab() { return mFab; }
    public ImageButton getLeftButton() { return mLeftButton; }
    public ImageButton getRightButton() { return mRightButton; }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Recreate the activity if any settings have been changed
        if (requestCode == SettingMenuItemController.REQUEST_CHANGE_SETTINGS
                && resultCode == RESULT_OK) {
            mRecreateActivity = true;
        }
    }

    /**
     * Configure the {@link #mFragmentTabPager} and {@link #mTabLayout} to display the tab at the
     * given {@code index}.
     *
     * @param index the index of the page to display
     */
    private void updateCurrentTab(int index) {
        final TabLayout.Tab tab = mTabLayout.getTabAt(index);
        if (tab != null && !tab.isSelected()) {
            tab.select();
        }
        if (mFragmentTabPager.getCurrentItem() != index) {
            mFragmentTabPager.setCurrentItem(index);
        }
    }

    /**
     * Display the fab and left/right buttons that match the currently selected fragment.
     */
    private void updateButtons() {
        // Update the shared buttons to reflect the new tab.
        final DeskClockFragment f = getSelectedDeskClockFragment();
        if (f != null) {
            f.setFabAppearance();
            f.setLeftRightButtonAppearance();
        }
    }

    private DeskClockFragment getSelectedDeskClockFragment() {
        final int index = UiDataModel.getUiDataModel().getSelectedTabIndex();
        return (DeskClockFragment) mFragmentTabPagerAdapter.getItem(index);
    }

    /**
     * As the view pager changes the selected page, update the model to record the new selected tab.
     */
    private class PageChangeWatcher implements OnPageChangeListener {

        /** The last reported page scroll state; used to detect exotic state changes. */
        private int mPriorState = SCROLL_STATE_IDLE;

        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // Only hide the fab when a non-zero drag distance is detected. This prevents
            // over-scrolling from needlessly hiding the fab.
            if (mFabState == FabState.HIDE_ARMED && positionOffsetPixels != 0) {
                mFabState = FabState.HIDING;
                mHideAnimation.start();
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            if (mPriorState == SCROLL_STATE_IDLE && state == SCROLL_STATE_SETTLING) {
                // The user has tapped a tab button; play the hide and show animations linearly.
                mHideAnimation.addListener(mAutoStartShowListener);
                mHideAnimation.start();
                mFabState = FabState.HIDING;

            } else if (mPriorState == SCROLL_STATE_SETTLING && state == SCROLL_STATE_DRAGGING) {
                // The user has interrupted settling on a tab and the fab button must be re-hidden.
                if (mShowAnimation.isStarted()) {
                    mShowAnimation.cancel();
                }
                if (mHideAnimation.isStarted()) {
                    // Let the hide animation finish naturally; don't auto show when it ends.
                    mHideAnimation.removeListener(mAutoStartShowListener);
                } else {
                    // Start and immediately end the hide animation to jump to the hidden state.
                    mHideAnimation.start();
                    mHideAnimation.end();
                }
                mFabState = FabState.HIDING;

            } else if (state != SCROLL_STATE_DRAGGING && mFabState == FabState.HIDING) {
                // The user has lifted their finger; show the buttons now or after hide ends.
                if (mHideAnimation.isStarted()) {
                    // Finish the hide animation and then start the show animation.
                    mHideAnimation.addListener(mAutoStartShowListener);
                } else {
                    updateButtons();
                    mShowAnimation.start();

                    // The animation to show the fab has begun; update the state to showing.
                    mFabState = FabState.SHOWING;
                }
            } else if (state == SCROLL_STATE_DRAGGING) {
                // The user has started a drag so arm the hide animation.
                mFabState = FabState.HIDE_ARMED;
            }

            // Update the last known state.
            mPriorState = state;
        }

        @Override
        public void onPageSelected(int position) {
            UiDataModel.getUiDataModel().setSelectedTabIndex(position);
        }
    }

    /**
     * If this listener is attached to {@link #mHideAnimation} when it ends, the corresponding
     * {@link #mShowAnimation} is automatically started.
     */
    private class AutoStartShowListener extends AnimatorListenerAdapter {
        @Override
        public void onAnimationEnd(Animator animation) {
            // Prepare the hide animation for its next use; by default do not auto-show after hide.
            mHideAnimation.removeListener(mAutoStartShowListener);

            // Update the buttons now that they are no longer visible.
            updateButtons();

            // Automatically start the grow animation now that shrinking is complete.
            mShowAnimation.start();

            // The animation to show the fab has begun; update the state to showing.
            mFabState = FabState.SHOWING;
        }
    }

    /**
     * As the model reports changes to the selected tab, update the user interface.
     */
    private class TabChangeWatcher implements TabListener {
        @Override
        public void selectedTabChanged(Tab oldSelectedTab, Tab newSelectedTab) {
            final int index = newSelectedTab.ordinal();

            // Update the view pager and tab layout to agree with the model.
            updateCurrentTab(index);

            // Avoid sending events for the initial tab selection on launch and re-selecting a tab
            // after a configuration change.
            if (DataModel.getDataModel().isApplicationInForeground()) {
                switch (newSelectedTab) {
                    case ALARMS:
                        Events.sendAlarmEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case CLOCKS:
                        Events.sendClockEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case TIMERS:
                        Events.sendTimerEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                    case STOPWATCH:
                        Events.sendStopwatchEvent(R.string.action_show, R.string.label_deskclock);
                        break;
                }
            }

            // If the hide animation has already completed, the buttons must be updated now when the
            // new tab is known. Otherwise they are updated at the end of the hide animation.
            if (!mHideAnimation.isStarted()) {
                updateButtons();
            }
        }
    }

    /**
     * This adapter produces the DeskClockFragments that are the contents of the tabs.
     */
    private static class TabFragmentAdapter extends FragmentPagerAdapter {

        private final FragmentManager mFragmentManager;
        private final Context mContext;

        public TabFragmentAdapter(AppCompatActivity activity) {
            super(activity.getFragmentManager());
            mContext = activity;
            mFragmentManager = activity.getFragmentManager();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            position = UiDataModel.getUiDataModel().getTabLayoutIndex(position);
            return super.instantiateItem(container, position);
        }

        @Override
        public Fragment getItem(int position) {
            final String tag = makeFragmentName(R.id.desk_clock_pager, position);
            Fragment fragment = mFragmentManager.findFragmentByTag(tag);
            if (fragment == null) {
                final Tab tab = UiDataModel.getUiDataModel().getTab(position);
                final String fragmentClassName = tab.getFragmentClassName();
                fragment = Fragment.instantiate(mContext, fragmentClassName);
            }
            return fragment;
        }

        @Override
        public int getCount() {
            return UiDataModel.getUiDataModel().getTabCount();
        }

        /** This implementation duplicated from {@link FragmentPagerAdapter#makeFragmentName}. */
        private String makeFragmentName(int viewId, long id) {
            return "android:switcher:" + viewId + ":" + id;
        }
    }
}