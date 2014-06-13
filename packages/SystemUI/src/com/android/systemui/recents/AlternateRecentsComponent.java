/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import com.android.systemui.R;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A proxy implementation for the recents component */
public class AlternateRecentsComponent implements ActivityOptions.OnAnimationStartedListener {

    /** A handler for messages from the recents implementation */
    class RecentsMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_UPDATE_FOR_CONFIGURATION) {
                Resources res = mContext.getResources();
                float statusBarHeight = res.getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height);
                Bundle replyData = msg.getData().getParcelable(KEY_CONFIGURATION_DATA);
                mSingleCountFirstTaskRect = replyData.getParcelable(KEY_SINGLE_TASK_STACK_RECT);
                mSingleCountFirstTaskRect.offset(0, (int) statusBarHeight);
                mTwoCountFirstTaskRect = replyData.getParcelable(KEY_TWO_TASK_STACK_RECT);
                mTwoCountFirstTaskRect.offset(0, (int) statusBarHeight);
                mMultipleCountFirstTaskRect = replyData.getParcelable(KEY_MULTIPLE_TASK_STACK_RECT);
                mMultipleCountFirstTaskRect.offset(0, (int) statusBarHeight);
                if (Console.Enabled) {
                    Console.log(Constants.Log.App.RecentsComponent,
                            "[RecentsComponent|RecentsMessageHandler|handleMessage]",
                            "singleTaskRect: " + mSingleCountFirstTaskRect +
                            " twoTaskRect: " + mTwoCountFirstTaskRect +
                            " multipleTaskRect: " + mMultipleCountFirstTaskRect);
                }

                // If we had the update the animation rects as a result of onServiceConnected, then
                // we check for whether we need to toggle the recents here.
                if (mToggleRecentsUponServiceBound) {
                    startRecentsActivity();
                    mToggleRecentsUponServiceBound = false;
                }
            }
        }
    }

    /** A service connection to the recents implementation */
    class RecentsServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (Console.Enabled) {
                Console.log(Constants.Log.App.RecentsComponent,
                        "[RecentsComponent|ServiceConnection|onServiceConnected]",
                        "toggleRecents: " + mToggleRecentsUponServiceBound);
            }
            mService = new Messenger(service);
            mServiceIsBound = true;

            if (hasValidTaskRects()) {
                // Start recents if this new service connection was triggered by hitting recents
                if (mToggleRecentsUponServiceBound) {
                    startRecentsActivity();
                    mToggleRecentsUponServiceBound = false;
                }
            } else {
                // Otherwise, update the animation rects before starting the recents if requested
                updateAnimationRects();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (Console.Enabled) {
                Console.log(Constants.Log.App.RecentsComponent,
                        "[RecentsComponent|ServiceConnection|onServiceDisconnected]");
            }
            mService = null;
            mServiceIsBound = false;
        }
    }

    final public static int MSG_UPDATE_FOR_CONFIGURATION = 0;
    final public static int MSG_UPDATE_TASK_THUMBNAIL = 1;
    final public static int MSG_PRELOAD_TASKS = 2;
    final public static int MSG_CANCEL_PRELOAD_TASKS = 3;
    final public static int MSG_SHOW_RECENTS = 4;
    final public static int MSG_HIDE_RECENTS = 5;
    final public static int MSG_TOGGLE_RECENTS = 6;
    final public static int MSG_START_ENTER_ANIMATION = 7;

    final public static String EXTRA_ANIMATING_WITH_THUMBNAIL = "recents.animatingWithThumbnail";
    final public static String EXTRA_FROM_ALT_TAB = "recents.triggeredFromAltTab";
    final public static String KEY_CONFIGURATION_DATA = "recents.data.updateForConfiguration";
    final public static String KEY_WINDOW_RECT = "recents.windowRect";
    final public static String KEY_SYSTEM_INSETS = "recents.systemInsets";
    final public static String KEY_SINGLE_TASK_STACK_RECT = "recents.singleCountTaskRect";
    final public static String KEY_TWO_TASK_STACK_RECT = "recents.twoCountTaskRect";
    final public static String KEY_MULTIPLE_TASK_STACK_RECT = "recents.multipleCountTaskRect";


    final static int sMinToggleDelay = 425;

    final static String sToggleRecentsAction = "com.android.systemui.recents.SHOW_RECENTS";
    final static String sRecentsPackage = "com.android.systemui";
    final static String sRecentsActivity = "com.android.systemui.recents.RecentsActivity";
    final static String sRecentsService = "com.android.systemui.recents.RecentsService";

    Context mContext;
    SystemServicesProxy mSystemServicesProxy;

    // Recents service binding
    Messenger mService = null;
    Messenger mMessenger;
    RecentsMessageHandler mHandler;
    boolean mBootCompleted = false;
    boolean mServiceIsBound = false;
    boolean mToggleRecentsUponServiceBound;
    RecentsServiceConnection mConnection = new RecentsServiceConnection();

    // Variables to keep track of if we need to start recents after binding
    View mStatusBarView;
    boolean mTriggeredFromAltTab;

    Rect mSingleCountFirstTaskRect = new Rect();
    Rect mTwoCountFirstTaskRect = new Rect();
    Rect mMultipleCountFirstTaskRect = new Rect();
    long mLastToggleTime;

    public AlternateRecentsComponent(Context context) {
        mContext = context;
        mSystemServicesProxy = new SystemServicesProxy(context);
        mHandler = new RecentsMessageHandler();
        mMessenger = new Messenger(mHandler);
    }

    public void onStart() {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.RecentsComponent, "[RecentsComponent|start]");
        }

        // Try to create a long-running connection to the recents service
        bindToRecentsService(false);
    }

    public void onBootCompleted() {
        mBootCompleted = true;
    }

    /** Shows the recents */
    public void onShowRecents(boolean triggeredFromAltTab, View statusBarView) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.RecentsComponent, "[RecentsComponent|showRecents]");
        }
        mStatusBarView = statusBarView;
        mTriggeredFromAltTab = triggeredFromAltTab;
        if (!mServiceIsBound) {
            // Try to create a long-running connection to the recents service before toggling
            // recents
            bindToRecentsService(true);
            return;
        }

        try {
            startRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    /** Hides the recents */
    public void onHideRecents(boolean triggeredFromAltTab) {
        if (Console.Enabled) {
            Console.log(Constants.Log.App.RecentsComponent, "[RecentsComponent|hideRecents]");
        }
        if (mServiceIsBound && mBootCompleted) {
            // Notify recents to close it
            try {
                Bundle data = new Bundle();
                Message msg = Message.obtain(null, MSG_HIDE_RECENTS, triggeredFromAltTab ? 1 : 0, 0);
                msg.setData(data);
                mService.send(msg);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
    }

    /** Toggles the alternate recents activity */
    public void onToggleRecents(View statusBarView) {
        if (Console.Enabled) {
            Console.logStartTracingTime(Constants.Log.App.TimeRecentsStartup,
                    Constants.Log.App.TimeRecentsStartupKey);
            Console.logStartTracingTime(Constants.Log.App.TimeRecentsLaunchTask,
                    Constants.Log.App.TimeRecentsLaunchKey);
            Console.log(Constants.Log.App.RecentsComponent, "[RecentsComponent|toggleRecents]",
                    "serviceIsBound: " + mServiceIsBound);
        }
        mStatusBarView = statusBarView;
        mTriggeredFromAltTab = false;
        if (!mServiceIsBound) {
            // Try to create a long-running connection to the recents service before toggling
            // recents
            bindToRecentsService(true);
            return;
        }

        try {
            toggleRecentsActivity();
        } catch (ActivityNotFoundException e) {
            Console.logRawError("Failed to launch RecentAppsIntent", e);
        }
    }

    public void onPreloadRecents() {
        // Do nothing
    }

    public void onCancelPreloadingRecents() {
        // Do nothing
    }

    public void onConfigurationChanged(Configuration newConfig) {
        updateAnimationRects();
    }

    /** Binds to the recents implementation */
    private void bindToRecentsService(boolean toggleRecentsUponConnection) {
        mToggleRecentsUponServiceBound = toggleRecentsUponConnection;
        Intent intent = new Intent();
        intent.setClassName(sRecentsPackage, sRecentsService);
        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    /** Returns whether we have valid task rects to animate to. */
    boolean hasValidTaskRects() {
        return mSingleCountFirstTaskRect != null && mSingleCountFirstTaskRect.width() > 0 &&
                mSingleCountFirstTaskRect.height() > 0 && mTwoCountFirstTaskRect != null &&
                mTwoCountFirstTaskRect.width() > 0 && mTwoCountFirstTaskRect.height() > 0 &&
                mMultipleCountFirstTaskRect != null && mMultipleCountFirstTaskRect.width() > 0 &&
                mMultipleCountFirstTaskRect.height() > 0;
    }

    /** Updates each of the task animation rects. */
    void updateAnimationRects() {
        if (mServiceIsBound) {
            Resources res = mContext.getResources();
            int statusBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
            int navBarHeight = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height);
            Rect rect = new Rect();
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRectSize(rect);

            // Try and update the recents configuration
            try {
                Bundle data = new Bundle();
                data.putParcelable(KEY_WINDOW_RECT, rect);
                data.putParcelable(KEY_SYSTEM_INSETS, new Rect(0, statusBarHeight, 0, 0));
                Message msg = Message.obtain(null, MSG_UPDATE_FOR_CONFIGURATION, 0, 0);
                msg.setData(data);
                msg.replyTo = mMessenger;
                mService.send(msg);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
    }

    /** Loads the first task thumbnail */
    Bitmap loadFirstTaskThumbnail() {
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RunningTaskInfo> tasks = ssp.getRunningTasks(1);

        for (ActivityManager.RunningTaskInfo t : tasks) {
            return ssp.getTaskThumbnail(t.id);
        }
        return null;
    }

    /** Returns the proper rect to use for the animation, given the number of tasks. */
    Rect getAnimationTaskRect(List<ActivityManager.RecentTaskInfo> tasks) {
        // NOTE: Currently there's no method to get the number of non-home tasks, so we have to
        // compute this ourselves
        SystemServicesProxy ssp = mSystemServicesProxy;
        Iterator<ActivityManager.RecentTaskInfo> iter = tasks.iterator();
        while (iter.hasNext()) {
            ActivityManager.RecentTaskInfo t = iter.next();

            // Skip tasks in the home stack
            if (ssp.isInHomeStack(t.persistentId)) {
                iter.remove();
                continue;
            }
        }
        if (tasks.size() <= 1) {
            return mSingleCountFirstTaskRect;
        } else if (tasks.size() <= 2) {
            return mTwoCountFirstTaskRect;
        } else {
            return mMultipleCountFirstTaskRect;
        }
    }

    /** Converts from the device rotation to the degree */
    float getDegreesForRotation(int value) {
        switch (value) {
            case Surface.ROTATION_90:
                return 360f - 90f;
            case Surface.ROTATION_180:
                return 360f - 180f;
            case Surface.ROTATION_270:
                return 360f - 270f;
        }
        return 0f;
    }

    /** Takes a screenshot of the surface */
    Bitmap takeScreenshot(Display display) {
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        float[] dims = {dm.widthPixels, dm.heightPixels};
        float degrees = getDegreesForRotation(display.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            Matrix m = new Matrix();
            m.preRotate(-degrees);
            m.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }
        return SurfaceControl.screenshot((int) dims[0], (int) dims[1]);
    }

    /** Creates the activity options for a thumbnail transition. */
    ActivityOptions getThumbnailTransitionActivityOptions(Rect taskRect) {
        // Loading from thumbnail
        Bitmap thumbnail;
        Bitmap firstThumbnail = loadFirstTaskThumbnail();
        if (firstThumbnail != null) {
            // Create the thumbnail
            thumbnail = Bitmap.createBitmap(taskRect.width(), taskRect.height(),
                    Bitmap.Config.ARGB_8888);
            int size = Math.min(firstThumbnail.getWidth(), firstThumbnail.getHeight());
            Canvas c = new Canvas(thumbnail);
            c.drawBitmap(firstThumbnail, new Rect(0, 0, size, size),
                    new Rect(0, 0, taskRect.width(), taskRect.height()), null);
            c.setBitmap(null);
            // Recycle the old thumbnail
            firstThumbnail.recycle();
        } else {
            // Load the thumbnail from the screenshot if can't get one from the system
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            Bitmap screenshot = takeScreenshot(display);
            if (screenshot != null) {
                Resources res = mContext.getResources();
                int size = Math.min(screenshot.getWidth(), screenshot.getHeight());
                int statusBarHeight = res.getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height);
                thumbnail = Bitmap.createBitmap(taskRect.width(), taskRect.height(),
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(thumbnail);
                c.drawBitmap(screenshot, new Rect(0, statusBarHeight, size, statusBarHeight +
                        size), new Rect(0, 0, taskRect.width(), taskRect.height()), null);
                c.setBitmap(null);
                // Recycle the temporary screenshot
                screenshot.recycle();
            } else {
                return null;
            }
        }

        return ActivityOptions.makeThumbnailScaleDownAnimation(mStatusBarView, thumbnail,
                taskRect.left, taskRect.top, this);
    }

    /** Returns whether the recents is currently running */
    boolean isRecentsTopMost(AtomicBoolean isHomeTopMost) {
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RunningTaskInfo> tasks = ssp.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            ActivityManager.RunningTaskInfo topTask = tasks.get(0);
            ComponentName topActivity = topTask.topActivity;

            // Check if the front most activity is recents
            if (topActivity.getPackageName().equals(sRecentsPackage) &&
                    topActivity.getClassName().equals(sRecentsActivity)) {
                if (isHomeTopMost != null) {
                    isHomeTopMost.set(false);
                }
                return true;
            }

            if (isHomeTopMost != null) {
                isHomeTopMost.set(ssp.isInHomeStack(topTask.id));
            }
        }
        return false;
    }

    /** Toggles the recents activity */
    void toggleRecentsActivity() {
        // If the user has toggled it too quickly, then just eat up the event here (it's better than
        // showing a janky screenshot).
        // NOTE: Ideally, the screenshot mechanism would take the window transform into account
        if (System.currentTimeMillis() - mLastToggleTime < sMinToggleDelay) {
            return;
        }

        // If Recents is the front most activity, then we should just communicate with it directly
        // to launch the first task or dismiss itself
        AtomicBoolean isTopTaskHome = new AtomicBoolean();
        if (isRecentsTopMost(isTopTaskHome)) {
            // Notify recents to close itself
            try {
                Bundle data = new Bundle();
                Message msg = Message.obtain(null, MSG_TOGGLE_RECENTS, 0, 0);
                msg.setData(data);
                mService.send(msg);

                // Time this path
                Console.logTraceTime(Constants.Log.App.TimeRecentsStartup,
                        Constants.Log.App.TimeRecentsStartupKey, "sendToggleRecents");
                Console.logTraceTime(Constants.Log.App.TimeRecentsLaunchTask,
                        Constants.Log.App.TimeRecentsLaunchKey, "sendToggleRecents");
            } catch (RemoteException re) {
                re.printStackTrace();
            }
            mLastToggleTime = System.currentTimeMillis();
            return;
        } else {
            // Otherwise, start the recents activity
            startRecentsActivity(isTopTaskHome.get());
        }
    }

    /** Starts the recents activity if it is not already running */
    void startRecentsActivity() {
        // Check if the top task is in the home stack, and start the recents activity
        AtomicBoolean isTopTaskHome = new AtomicBoolean();
        if (!isRecentsTopMost(isTopTaskHome)) {
            startRecentsActivity(isTopTaskHome.get());
        }
    }

    /** Starts the recents activity */
    void startRecentsActivity(boolean isTopTaskHome) {
        // If Recents is not the front-most activity and we should animate into it.  If
        // the activity at the root of the top task stack in the home stack, then we just do a
        // simple transition.  Otherwise, we animate to the rects defined by the Recents service,
        // which can differ depending on the number of items in the list.
        SystemServicesProxy ssp = mSystemServicesProxy;
        List<ActivityManager.RecentTaskInfo> recentTasks =
                ssp.getRecentTasks(3, UserHandle.CURRENT.getIdentifier());
        Rect taskRect = getAnimationTaskRect(recentTasks);
        boolean useThumbnailTransition = !isTopTaskHome &&
                hasValidTaskRects();

        if (useThumbnailTransition) {
            // Try starting with a thumbnail transition
            ActivityOptions opts = getThumbnailTransitionActivityOptions(taskRect);
            if (opts != null) {
                startAlternateRecentsActivity(opts, true);
            } else {
                // Fall through below to the non-thumbnail transition
                useThumbnailTransition = false;
            }
        }

        // If there is no thumbnail transition, then just use a generic transition
        if (!useThumbnailTransition) {
            ActivityOptions opts = ActivityOptions.makeCustomAnimation(mContext,
                    R.anim.recents_from_launcher_enter,
                    R.anim.recents_from_launcher_exit, mHandler, this);
            startAlternateRecentsActivity(opts, false);
        }

        Console.logTraceTime(Constants.Log.App.TimeRecentsStartup,
                Constants.Log.App.TimeRecentsStartupKey, "startRecentsActivity");
        mLastToggleTime = System.currentTimeMillis();
    }

    /** Starts the recents activity */
    void startAlternateRecentsActivity(ActivityOptions opts, boolean animatingWithThumbnail) {
        Intent intent = new Intent(sToggleRecentsAction);
        intent.setClassName(sRecentsPackage, sRecentsActivity);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(EXTRA_ANIMATING_WITH_THUMBNAIL, animatingWithThumbnail);
        intent.putExtra(EXTRA_FROM_ALT_TAB, mTriggeredFromAltTab);
        if (opts != null) {
            mContext.startActivityAsUser(intent, opts.toBundle(), new UserHandle(
                    UserHandle.USER_CURRENT));
        } else {
            mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }
    }


    /**** OnAnimationStartedListener Implementation ****/

    @Override
    public void onAnimationStarted() {
        // Notify recents to start the enter animation
        try {
            Message msg = Message.obtain(null, MSG_START_ENTER_ANIMATION, 0, 0);
            mService.send(msg);
        } catch (RemoteException re) {
            re.printStackTrace();
        }
    }
}
