package org.wordpress.android.ui.reader;

import android.app.Activity;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.view.MenuItem;
import android.view.View;

import org.wordpress.android.R;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.datasets.ReaderDatabase;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.ReaderTagType;
import org.wordpress.android.ui.WPDrawerActivity;
import org.wordpress.android.ui.accounts.WPComLoginActivity;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.ui.reader.ReaderInterfaces.OnPostSelectedListener;
import org.wordpress.android.ui.reader.ReaderInterfaces.OnTagSelectedListener;
import org.wordpress.android.ui.reader.actions.ReaderActions.RequestDataAction;
import org.wordpress.android.ui.reader.actions.ReaderAuthActions;
import org.wordpress.android.ui.reader.actions.ReaderUserActions;
import org.wordpress.android.ui.reader.services.ReaderUpdateService;
import org.wordpress.android.ui.reader.services.ReaderUpdateService.UpdateTask;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.StringUtils;

import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

/*
 * this activity serves as the host for ReaderPostListFragment
 */

public class ReaderPostListActivity extends WPDrawerActivity
                                    implements OnPostSelectedListener,
                                               OnTagSelectedListener {

    private static boolean mHasPerformedInitialUpdate;
    private static boolean mHasPerformedPurge;

    private final ScheduledExecutorService mUpdateScheduler = Executors.newScheduledThreadPool(1);

    private ReaderTypes.ReaderPostListType mPostListType;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createMenuDrawer(R.layout.reader_activity_post_list);
        readIntent(getIntent(), savedInstanceState);

        // start updating tags & blogs after 500ms, then update them hourly
        mUpdateScheduler.scheduleWithFixedDelay(
                new Runnable() {
                    public void run() {
                        updateFollowedTagsAndBlogs();
                    }
                }, 500, (1000 * 60) * 60, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onDestroy() {
        mUpdateScheduler.shutdownNow();
        super.onDestroy();
    }

    private void readIntent(Intent intent, Bundle savedInstanceState) {
        if (intent == null) {
            return;
        }

        if (intent.hasExtra(ReaderConstants.ARG_POST_LIST_TYPE)) {
            mPostListType = (ReaderTypes.ReaderPostListType) intent.getSerializableExtra(ReaderConstants.ARG_POST_LIST_TYPE);
        } else {
            mPostListType = ReaderTypes.DEFAULT_POST_LIST_TYPE;
        }

        // hide drawer toggle and enable back arrow click if this is blog preview or tag preview
        if (mPostListType.isPreviewType() && getDrawerToggle() != null) {
            getDrawerToggle().setDrawerIndicatorEnabled(false);
            getToolbar().setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackPressed();
                }
            });
        }

        if (savedInstanceState == null) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.READER_ACCESSED);

            if (mPostListType == ReaderTypes.ReaderPostListType.BLOG_PREVIEW) {
                long blogId = intent.getLongExtra(ReaderConstants.ARG_BLOG_ID, 0);
                String blogUrl = intent.getStringExtra(ReaderConstants.ARG_BLOG_URL);
                showListFragmentForBlog(blogId, blogUrl);
            } else {
                // get the tag name from the intent, if not there get it from prefs
                ReaderTag tag;
                if (intent.hasExtra(ReaderConstants.ARG_TAG)) {
                    tag = (ReaderTag) intent.getSerializableExtra(ReaderConstants.ARG_TAG);
                } else  {
                    tag = AppPrefs.getReaderTag();
                }
                // if this is a followed tag and it doesn't exist, revert to default tag
                if (mPostListType == ReaderTypes.ReaderPostListType.TAG_FOLLOWED && !ReaderTagTable.tagExists(tag)) {
                    tag = ReaderTag.getDefaultTag();
                }

                showListFragmentForTag(tag, mPostListType);
            }
        }

        switch (mPostListType) {
            case TAG_PREVIEW:
                setTitle(R.string.reader_title_tag_preview);
                break;
            case BLOG_PREVIEW:
                setTitle(R.string.reader_title_blog_preview);
                break;
            default:
                break;
        }

        // hide the static drawer for blog/tag preview
        if (isStaticMenuDrawer() && mPostListType.isPreviewType()) {
            hideDrawer();
        }
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mReceiver);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ReaderUpdateService.ACTION_FOLLOWED_TAGS_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // purge the database of older data at startup, but only if there's an active connection
        // since we don't want to purge posts that the user would expect to see when offline
        if (!mHasPerformedPurge && NetworkUtils.isNetworkAvailable(this)) {
            mHasPerformedPurge = true;
            ReaderDatabase.purgeAsync();
        }
        if (!mHasPerformedInitialUpdate) {
            performInitialUpdate();
        }
    }

    @Override
    public void onSaveInstanceState(@Nonnull Bundle outState) {
        if (outState.isEmpty()) {
            outState.putBoolean("bug_19917_fix", true);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        ReaderPostListFragment fragment = getListFragment();
        if (fragment == null || !fragment.goBackInTagHistory()) {
            setToolbarClickListener();
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_tags:
                ReaderActivityLauncher.showReaderSubsForResult(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        boolean isResultOK = (resultCode == Activity.RESULT_OK);
        final ReaderPostListFragment listFragment = getListFragment();

        switch (requestCode) {
            // user just returned from the tag editor
            case ReaderConstants.INTENT_READER_SUBS :
                if (isResultOK && listFragment != null && data != null) {
                    if (data.getBooleanExtra(ReaderSubsActivity.KEY_TAGS_CHANGED, false)) {
                        // reload tags if they were changed, and set the last tag added as the current one
                        String lastAddedTag = data.getStringExtra(ReaderSubsActivity.KEY_LAST_ADDED_TAG_NAME);
                        listFragment.doTagsChanged(lastAddedTag);
                    } else if (data.getBooleanExtra(ReaderSubsActivity.KEY_BLOGS_CHANGED, false)) {
                        // update posts if any blog was followed or unfollowed and user is viewing "Blogs I Follow"
                        if (listFragment.getPostListType().isTagType()
                                && ReaderTag.TAG_NAME_FOLLOWING.equals(listFragment.getCurrentTagName())) {
                            listFragment.updatePostsWithTag(
                                    listFragment.getCurrentTag(),
                                    RequestDataAction.LOAD_NEWER,
                                    ReaderTypes.RefreshType.AUTOMATIC);
                        }
                    }
                }
                break;

            // user just returned from reblogging activity, reload the displayed post if reblogging
            // succeeded
            case ReaderConstants.INTENT_READER_REBLOG:
                if (isResultOK && data != null && listFragment != null) {
                    long blogId = data.getLongExtra(ReaderConstants.ARG_BLOG_ID, 0);
                    long postId = data.getLongExtra(ReaderConstants.ARG_POST_ID, 0);
                    listFragment.reloadPost(ReaderPostTable.getPost(blogId, postId, true));
                }
                break;

            // user just returned from the login dialog, need to perform initial update again
            // since creds have changed
            case WPComLoginActivity.REQUEST_CODE:
                if (isResultOK) {
                    removeListFragment();
                    mHasPerformedInitialUpdate = false;
                    performInitialUpdate();
                }
                break;
        }
    }

    @Override
    public void onSignout() {
        super.onSignout();

        AppLog.i(T.READER, "reader post list > user signed out");
        mHasPerformedInitialUpdate = false;

        // reader database will have been cleared by the time this is called, but the fragment must
        // be removed or else it will continue to show the same articles - onResume() will take
        // care of re-displaying the correct fragment if necessary
        removeListFragment();
    }

    ReaderTypes.ReaderPostListType getPostListType() {
        return (mPostListType != null ? mPostListType : ReaderTypes.DEFAULT_POST_LIST_TYPE);
    }

    private void removeListFragment() {
        Fragment listFragment = getListFragment();
        if (listFragment != null) {
            getFragmentManager()
                    .beginTransaction()
                    .remove(listFragment)
                    .commit();
        }
    }

    /*
     * show fragment containing list of latest posts for a specific tag
     */
    private void showListFragmentForTag(final ReaderTag tag, ReaderTypes.ReaderPostListType listType) {
        if (isFinishing()) {
            return;
        }
        Fragment fragment = ReaderPostListFragment.newInstance(tag, listType);
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, getString(R.string.fragment_tag_reader_post_list))
                .commit();
    }

    /*
     * show fragment containing list of latest posts in a specific blog
     */
    private void showListFragmentForBlog(long blogId, String blogUrl) {
        if (isFinishing()) {
            return;
        }
        Fragment fragment = ReaderPostListFragment.newInstance(blogId, blogUrl);
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, getString(R.string.fragment_tag_reader_post_list))
                .commit();
    }

    private ReaderPostListFragment getListFragment() {
        Fragment fragment = getFragmentManager().findFragmentByTag(getString(R.string.fragment_tag_reader_post_list));
        if (fragment == null) {
            return null;
        }
        return ((ReaderPostListFragment) fragment);
    }

    private boolean hasListFragment() {
        return (getListFragment() != null);
    }

    /*
     * initial update performed the first time the user opens the reader
     */
    private void performInitialUpdate() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            return;
        }

        mHasPerformedInitialUpdate = true;

        // update current user to ensure we have their user_id as well as their latest info
        // in case they changed their avatar, name, etc. since last time
        AppLog.d(T.READER, "reader post list > updating current user");
        ReaderUserActions.updateCurrentUser(null);

        // update cookies so that we can show authenticated images in WebViews
        AppLog.d(T.READER, "reader post list > updating cookies");
        ReaderAuthActions.updateCookies(ReaderPostListActivity.this);
    }

    /*
     * start background service to get the latest followed tags and blogs
     */
    void updateFollowedTagsAndBlogs() {
        AppLog.d(T.READER, "reader post list > updating tags and blogs");
        ReaderUpdateService.startService(this,
                EnumSet.of(UpdateTask.TAGS, UpdateTask.FOLLOWED_BLOGS));
    }

    /*
     * user tapped a post in the list fragment
     */
    @Override
    public void onPostSelected(long blogId, long postId) {
        // skip if this activity no longer has the focus - this prevents the post detail from
        // being shown multiple times if the user quickly taps a post more than once
        if (!this.hasWindowFocus()) {
            AppLog.w(T.READER, "reader post list > post selected when activity not focused");
            return;
        }

        AnalyticsTracker.track(AnalyticsTracker.Stat.READER_OPENED_ARTICLE);

        ReaderPostListFragment listFragment = getListFragment();
        if (listFragment != null) {
            switch (getPostListType()) {
                case TAG_FOLLOWED:
                case TAG_PREVIEW:
                    ReaderActivityLauncher.showReaderPostPagerForTag(
                            this,
                            listFragment.getCurrentTag(),
                            getPostListType(),
                            blogId,
                            postId);
                    break;
                case BLOG_PREVIEW:
                    ReaderActivityLauncher.showReaderPostPagerForBlog(
                            this,
                            blogId,
                            postId);
                    break;
            }
        }
    }

    /*
     * user tapped a tag in the list fragment
     */
    @Override
    public void onTagSelected(String tagName) {
        ReaderTag tag = new ReaderTag(tagName, ReaderTagType.FOLLOWED);
        if (hasListFragment() && getListFragment().getPostListType().equals(ReaderTypes.ReaderPostListType.TAG_PREVIEW)) {
            // user is already previewing a tag, so change current tag in existing preview
            getListFragment().setCurrentTag(tag);
        } else {
            // user isn't previewing a tag, so open in tag preview
            ReaderActivityLauncher.showReaderTagPreview(this, tag);
        }
    }

    /*
     * receiver which is notified when followed tags and/or blogs have changed
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing()) {
                return;
            }

            String action = StringUtils.notNullStr(intent.getAction());
            AppLog.d(T.READER, "reader post list > received broadcast " + action);

            if (action.equals(ReaderUpdateService.ACTION_FOLLOWED_TAGS_CHANGED)) {
                ReaderPostListFragment listFragment = getListFragment();
                if (listFragment == null) {
                    // list fragment doesn't exist yet (can happen if user signed out) - create
                    // it now showing the default tag
                    showListFragmentForTag(ReaderTag.getDefaultTag(), ReaderTypes.ReaderPostListType.TAG_FOLLOWED);
                } else if (listFragment.getPostListType() == ReaderTypes.ReaderPostListType.TAG_FOLLOWED) {
                    // list fragment is viewing followed tags, tell it to refresh the list of tags
                    listFragment.refreshTags();
                    // update the current tag if the list fragment is empty - this will happen if
                    // the tag table was previously empty (ie: first run)
                    if (listFragment.isPostAdapterEmpty()) {
                        listFragment.updateCurrentTag();
                    }
                }
            }
        }
    };

}
