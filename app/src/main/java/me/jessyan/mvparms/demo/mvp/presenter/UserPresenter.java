package me.jessyan.mvparms.demo.mvp.presenter;

import android.app.Application;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.OnLifecycleEvent;
import android.support.v4.app.Fragment;
import android.support.v4.app.SupportActivity;
import android.support.v7.widget.RecyclerView;

import com.jess.arms.di.scope.ActivityScope;
import com.jess.arms.integration.AppManager;
import com.jess.arms.mvp.BasePresenter;
import com.jess.arms.utils.PermissionUtil;
import com.jess.arms.utils.RxLifecycleUtils;

import java.util.List;

import javax.inject.Inject;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.jessyan.mvparms.demo.mvp.contract.UserContract;
import me.jessyan.mvparms.demo.mvp.model.entity.User;
import me.jessyan.rxerrorhandler.core.RxErrorHandler;
import me.jessyan.rxerrorhandler.handler.ErrorHandleSubscriber;
import me.jessyan.rxerrorhandler.handler.RetryWithDelay;

/**
 * ================================================
 * 展示 Presenter 的用法
 *
 * @see <a href="https://github.com/JessYanCoding/MVPArms/wiki#2.4.4">Presenter wiki 官方文档</a>
 * ================================================
 */
@ActivityScope
public class UserPresenter extends BasePresenter<UserContract.Model, UserContract.View> {

    private RxErrorHandler mErrorHandler;
    private AppManager mAppManager;
    private Application mApplication;
    private List<User> mUsers;
    private RecyclerView.Adapter mAdapter;
    private int lastUserId = 1;
    private boolean isFirst = true;
    private int preEndIndex;

    @Inject
    public UserPresenter(UserContract.Model model, UserContract.View rootView, RxErrorHandler handler
            , AppManager appManager, Application application, List<User> list, RecyclerView.Adapter adapter) {
        super(model, rootView);
        this.mApplication = application;
        this.mErrorHandler = handler;
        this.mAppManager = appManager;
        this.mUsers = list;
        this.mAdapter = adapter;
    }

    /**
     * 使用 2017 Google IO 发布的 Architecture Components 中的 Lifecycles 的新特性 (此特性已被加入 Support library)
     * 使 {@code Presenter} 可以与 {@link SupportActivity} 和 {@link Fragment} 的部分生命周期绑定
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    void onCreate() {
        requestUsers(true);//打开 App 时自动加载列表
    }

    public void requestUsers(final boolean pullToRefresh) {
        //请求外部存储权限用于适配android6.0的权限管理机制
        PermissionUtil.externalStorage(new PermissionUtil.RequestPermission() {
            @Override
            public void onRequestPermissionSuccess() {
                //request permission success, do something.
            }

            @Override
            public void onRequestPermissionFailure(List<String> permissions) {
                mRootView.showMessage("Request permissions failure");
            }

            @Override
            public void onRequestPermissionFailureWithAskNeverAgain(List<String> permissions) {
                mRootView.showMessage("Need to go to the settings");
            }
        }, mRootView.getRxPermissions(), mErrorHandler);


        if (pullToRefresh) lastUserId = 1;//下拉刷新默认只请求第一页

        //关于RxCache缓存库的使用请参考 http://www.jianshu.com/p/b58ef6b0624b

        boolean isEvictCache = pullToRefresh;//是否驱逐缓存,为ture即不使用缓存,每次下拉刷新即需要最新数据,则不使用缓存

        if (pullToRefresh && isFirst) {//默认在第一次下拉刷新时使用缓存
            isFirst = false;
            isEvictCache = false;
        }

        mModel.getUsers(lastUserId, isEvictCache)
                .subscribeOn(Schedulers.io())
                .retryWhen(new RetryWithDelay(3, 2))//遇到错误时重试,第一个参数为重试几次,第二个参数为重试的间隔
                .doOnSubscribe(disposable -> {
                    if (pullToRefresh)
                        mRootView.showLoading();//显示下拉刷新的进度条
                    else
                        mRootView.startLoadMore();//显示上拉加载更多的进度条
                }).subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    if (pullToRefresh)
                        mRootView.hideLoading();//隐藏下拉刷新的进度条
                    else
                        mRootView.endLoadMore();//隐藏上拉加载更多的进度条
                })
                .compose(RxLifecycleUtils.bindToLifecycle(mRootView))//使用 Rxlifecycle,使 Disposable 和 Activity 一起销毁
                .subscribe(new ErrorHandleSubscriber<List<User>>(mErrorHandler) {
                    @Override
                    public void onNext(List<User> users) {
                        lastUserId = users.get(users.size() - 1).getId();//记录最后一个id,用于下一次请求
                        if (pullToRefresh) mUsers.clear();//如果是下拉刷新则清空列表
                        preEndIndex = mUsers.size();//更新之前列表总长度,用于确定加载更多的起始位置
                        mUsers.addAll(users);
                        if (pullToRefresh)
                            mAdapter.notifyDataSetChanged();
                        else
                            mAdapter.notifyItemRangeInserted(preEndIndex, users.size());
                    }
                });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.mAdapter = null;
        this.mUsers = null;
        this.mErrorHandler = null;
        this.mAppManager = null;
        this.mApplication = null;
    }
}
