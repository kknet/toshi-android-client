/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.presenter.webview;

import android.os.Build;
import android.support.annotation.StringRes;
import android.util.Pair;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import com.toshi.BuildConfig;
import com.toshi.R;
import com.toshi.presenter.Presenter;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.WebViewActivity;
import com.toshi.view.custom.listener.OnLoadListener;

import java.net.URI;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public class WebViewPresenter implements Presenter<WebViewActivity> {

    private WebViewActivity activity;
    private SofaWebViewClient webClient;
    private SofaInjector sofaInjector;
    private SofaHostWrapper sofaHostWrapper;
    private CompositeSubscription subscriptions;
    private PublishSubject<String> viewSubject;

    private boolean firstTimeAttaching = true;
    private boolean firstTimeLoading= true;
    private boolean isLoaded = false;

    @Override
    public void onViewAttached(final WebViewActivity view) {
        this.activity = view;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }

        initWebClient();
        initShortLivingObjects();
        initView();
    }

    private void initShortLivingObjects() {
        final Subscription sub = Observable.zip(
                    viewSubject.asObservable(),
                    sofaInjector.loadUrl(getAddress()).toObservable(),
                    Pair::new
                )
                .map(stringSofaInjectResponsePair -> stringSofaInjectResponsePair.second)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .toSingle()
                .subscribe(
                        this::handleWebResourceResponse,
                        this::onError
                );

        subscriptions.add(sub);
    }

    private void handleWebResourceResponse(final SofaInjectResponse response) {
        if (activity == null) return;
        activity.getBinding()
                .webview
                .loadDataWithBaseURL(
                        response.getAddress(),
                        response.getData(),
                        response.getMimeType(),
                        response.getEncoding(),
                        null);

        setToolbarAddress(response.getAddress());
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
        this.viewSubject = PublishSubject.create();
    }

    private void initWebClient() {
        if (this.webClient != null) {
            hideLoadingSpinner();
            return;
        }
        initInjectsAndEmbeds();
        initWebSettings();
        injectEverything();
    }

    private void initInjectsAndEmbeds() {
        this.webClient = new SofaWebViewClient(this.loadedListener);
        this.sofaInjector = new SofaInjector(this.loadedListener);
        this.sofaHostWrapper = new SofaHostWrapper(this.activity, this.activity.getBinding().webview);
    }

    private void initWebSettings() {
        final WebSettings webSettings = this.activity.getBinding().webview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDomStorageEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.WEB_DEBUG_ENABLED);
        }
    }

    private void injectEverything() {
        this.activity.getBinding().webview.addJavascriptInterface(this.sofaHostWrapper.getSofaHost(), "SOFAHost");
        this.activity.getBinding().webview.setWebViewClient(this.webClient);
    }

    private void initView() {
        initToolbar();
        animateLoadingSpinner();
        initClickListeners();
    }

    private void initToolbar() {
        this.activity.getBinding().closeButton.setOnClickListener(__ -> handleBackButtonClicked());
    }

    private void initClickListeners() {
        this.activity.getBinding().backButton.setOnClickListener(__ -> {
            final WebView webView = this.activity.getBinding().webview;
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });
        this.activity.getBinding().forwardButton.setOnClickListener(__ -> {
            final WebView webView = this.activity.getBinding().webview;
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });
    }

    private void setToolbarTitle(final String title) {
        if (this.activity == null) return;
        try {
            this.activity.getBinding().title.setText(title == null ? this.activity.getString(R.string.page_blank) : title);
            this.activity.getBinding().title.setVisibility(title == null ? View.GONE : View.VISIBLE);

        } catch (final IllegalArgumentException ex) {
            this.activity.getBinding().title.setText(BaseApplication.get().getString(R.string.unknown_address));
        }
    }

    private void setToolbarAddress(final String address) {
        if (this.activity == null) return;
        this.activity.getBinding().address.setText(address);
        this.activity.getBinding().address.setVisibility(address == null ? View.GONE : View.VISIBLE);
    }

    private void handleBackButtonClicked() {
        if (this.activity == null) return;
        this.activity.onBackPressed();
    }

    private void animateLoadingSpinner() {
        if (this.activity == null || this.isLoaded) return;
        final Animation rotateAnimation = AnimationUtils.loadAnimation(this.activity, R.anim.rotate);
        this.activity.getBinding().loadingView.startAnimation(rotateAnimation);
    }

    private final OnLoadListener loadedListener = new OnLoadListener() {
        @Override
        public void onReady() {
            if (activity == null) return;

            viewSubject.onNext(null);
            handleOnReady();
        }

        private void handleOnReady() {
            try {

                if (firstTimeLoading) {
                    firstTimeLoading = false;
                    return;
                }

                final String address = getAddress();
                loadUrlFromAddress(address);
            } catch (IllegalArgumentException e) {
                onError(e);
            }
        }

        private void loadUrlFromAddress(final String address) {
            if (sofaInjector == null) {
                onError(new Throwable("SofaInjector is null"));
                return;
            }
            final Subscription sub = sofaInjector.loadUrl(address)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            s -> handleWebResourceResponse(s),
                            this::onError
                    );

            subscriptions.add(sub);
        }

        @Override
        public void onLoaded() {
            if (activity == null) return;
            hideLoadingSpinner();
            final WebView webview = activity.getBinding().webview;
            setToolbarTitle(webview.getTitle());
            isLoaded = true;
        }

        @Override
        public void onUrlChanged(String url) {
            setToolbarAddress(url);
        }

        @Override
        public void onError(final Throwable t) {
            WebViewPresenter.this.onError(t);
        }

        @Override
        public void newPageLoad(final String address) {
            try {
                loadUrlFromAddress(address);
            } catch (IllegalArgumentException e) {
                showToast(R.string.unsupported_format);
            }
        }
    };

    private void onError(final Throwable t) {
        LogUtil.exception(getClass(), "Unable to load Dapp", t);
        if (activity == null) return;
        showToast(R.string.error__dapp_loading);
        activity.finish();
    }

    private void hideLoadingSpinner() {
        activity.getBinding().loadingView.clearAnimation();
        activity.getBinding().loadingView.setVisibility(View.GONE);
        activity.getBinding().webview.setVisibility(View.VISIBLE);
    }

    private String getAddress() throws IllegalArgumentException {
        final String url = this.activity.getIntent().getStringExtra(WebViewActivity.EXTRA__ADDRESS).trim();
        final URI uri = URI.create(url);
        return uri.getScheme() == null
                ? "http://" + uri.toASCIIString()
                : uri.toASCIIString();
    }

    private void showToast(final @StringRes int stringRes) {
        Toast.makeText(
                this.activity,
                stringRes,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onViewDetached() {
        this.subscriptions.clear();
        this.sofaHostWrapper.clear();
        this.activity = null;
    }

    @Override
    public void onDestroyed() {
        this.subscriptions = null;
        this.activity = null;
        destroy();
    }

    private void destroy() {
        this.sofaInjector.destroy();
        this.sofaInjector = null;
        this.webClient = null;
        this.isLoaded = false;
        this.sofaHostWrapper = null;
    }

}
