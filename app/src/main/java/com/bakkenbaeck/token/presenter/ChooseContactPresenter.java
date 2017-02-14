package com.bakkenbaeck.token.presenter;

import android.app.Activity;
import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.bakkenbaeck.token.R;
import com.bakkenbaeck.token.crypto.util.TypeConverter;
import com.bakkenbaeck.token.model.local.ActivityResultHolder;
import com.bakkenbaeck.token.model.local.User;
import com.bakkenbaeck.token.network.IdService;
import com.bakkenbaeck.token.util.EthUtil;
import com.bakkenbaeck.token.util.LogUtil;
import com.bakkenbaeck.token.util.PaymentType;
import com.bakkenbaeck.token.view.BaseApplication;
import com.bakkenbaeck.token.view.activity.ChatActivity;
import com.bakkenbaeck.token.view.activity.ChooseContactsActivity;
import com.bakkenbaeck.token.view.activity.ScannerActivity;
import com.bakkenbaeck.token.view.adapter.ContactsAdapter;
import com.bakkenbaeck.token.view.custom.HorizontalLineDivider;
import com.jakewharton.rxbinding.widget.RxTextView;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class ChooseContactPresenter implements Presenter<ChooseContactsActivity> {

    private static final int REQUEST_CODE = 1;

    private @PaymentType.Type
    int viewType;
    private ChooseContactsActivity activity;
    private String encodedEthAmount;
    private ContactsAdapter adapter;
    private User recipientUser;
    private String localCurrency;
    private boolean firstTimeAttaching = true;
    private CompositeSubscription subscriptions;

    @Override
    public void onViewAttached(final ChooseContactsActivity view) {
        this.activity = view;

        if (firstTimeAttaching) {
            firstTimeAttaching = false;
            initLongLivingObjects();
        }

        getIntentData();
        getLocalCurrency();
        initView();
    }

    private void initLongLivingObjects() {
        subscriptions = new CompositeSubscription();
        this.adapter = new ContactsAdapter()
                .loadAllStoredContacts()
                .setOnItemClickListener(this::handleItemClicked);
    }

    @SuppressWarnings("WrongConstant")
    private void getIntentData() {
        this.encodedEthAmount = this.activity.getIntent().getStringExtra(AmountPresenter.INTENT_EXTRA__ETH_AMOUNT);
        this.viewType = this.activity.getIntent().getIntExtra(ChooseContactsActivity.VIEW_TYPE, PaymentType.TYPE_SEND);
    }

    private void getLocalCurrency() {
        final BigInteger weiAmount = TypeConverter.StringHexToBigInteger(this.encodedEthAmount);
        final BigDecimal ethAmount = EthUtil.weiToEth(weiAmount);
        this.localCurrency = BaseApplication.get().getTokenManager().getBalanceManager().convertEthToLocalCurrencyString(ethAmount);
    }

    private void initView() {
        initToolbar();
        initRecyclerView();
        updateEmptyState();
        updateConfirmationButtonState();
        setSendButtonText();
        initSearch();

        this.activity.getBinding().qrScan.setOnClickListener(view -> handleQrScanClicked());
        this.activity.getBinding().btnContinue.setOnClickListener(view -> handleSendClicked());
    }

    private void initToolbar() {
        final String paymentAction = this.viewType == PaymentType.TYPE_SEND
                ? this.activity.getString(R.string.send)
                : this.activity.getString(R.string.request);

        final String title = this.activity.getString(R.string.send_amount, paymentAction, this.localCurrency);
        this.activity.getBinding().title.setText(title);
        this.activity.getBinding().closeButton.setOnClickListener(view -> this.activity.finish());
    }

    private void initRecyclerView() {
        final RecyclerView recyclerView = this.activity.getBinding().contacts;
        recyclerView.setLayoutManager(new LinearLayoutManager(this.activity));
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        final int dividerLeftPadding = activity.getResources().getDimensionPixelSize(R.dimen.avatar_size_small)
                + activity.getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        final HorizontalLineDivider lineDivider =
                new HorizontalLineDivider(activity.getResources().getColor(R.color.divider))
                        .setLeftPadding(dividerLeftPadding);
        recyclerView.addItemDecoration(lineDivider);
        recyclerView.setAdapter(this.adapter);
    }

    private void updateEmptyState() {
        final boolean showingEmptyState = this.activity.getBinding().emptyStateSwitcher.getCurrentView().getId() == this.activity.getBinding().emptyState.getId();
        final boolean shouldShowEmptyState = this.activity.getBinding().contacts.getAdapter().getItemCount() == 0;

        if (shouldShowEmptyState && !showingEmptyState) {
            this.activity.getBinding().emptyStateSwitcher.showPrevious();
        } else if (!shouldShowEmptyState && showingEmptyState) {
            this.activity.getBinding().emptyStateSwitcher.showNext();
        }
    }

    private void updateConfirmationButtonState() {
        this.activity.getBinding().btnContinue.setEnabled(this.recipientUser != null);

        final int color = this.recipientUser != null
                ? ContextCompat.getColor(this.activity, R.color.colorPrimary)
                : ContextCompat.getColor(this.activity, R.color.textColorSecondary);
        this.activity.getBinding().btnContinue.setTextColor(color);
    }

    private void setSendButtonText() {
        final String btnText = this.activity.getString(R.string.button_send_amount, this.localCurrency);
        this.activity.getBinding().btnContinue.setText(btnText);
    }

    private void initSearch() {
        final Subscription sub = RxTextView
                .textChanges(this.activity.getBinding().recipientUser)
                .skip(1)
                .debounce(400, TimeUnit.MILLISECONDS)
                .map(CharSequence::toString)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleSearch);

        this.subscriptions.add(sub);
    }

    private void handleSearch(final String searchString) {
        this.adapter.filter(searchString);
    }

    private void handleItemClicked(final User user) {
        this.recipientUser = user;
        this.activity.getBinding().recipientUser.setText(user.getUsername());
        updateConfirmationButtonState();
    }

    private void handleQrScanClicked() {
        final Intent intent = new Intent(this.activity, ScannerActivity.class);
        intent.putExtra(ScannerActivity.RESULT_TYPE, ScannerActivity.FOR_RESULT);
        this.activity.startActivityForResult(intent, REQUEST_CODE);
    }

    private void handleSendClicked() {
        final Intent intent = new Intent(activity, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA__REMOTE_USER, recipientUser)
                .putExtra(ChatActivity.EXTRA__PAYMENT_ACTION, viewType)
                .putExtra(ChatActivity.EXTRA__ETH_AMOUNT, encodedEthAmount);

        this.activity.startActivity(intent);
        this.activity.finish();
    }

    public void handleActivityResult(final ActivityResultHolder resultHolder) {
        if (resultHolder.getResultCode() != Activity.RESULT_OK) {
            return;
        }

        if (resultHolder.getRequestCode() == REQUEST_CODE) {
            final String userAddress = resultHolder.getIntent().getStringExtra(ScannerPresenter.USER_ADDRESS);
            fetchUser(userAddress);
        }
    }

    private void fetchUser(final String userAddress) {
       final Subscription sub = IdService
                .getApi()
                .getUser(userAddress)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handleUserLoaded, this::handleErrorResponse);

        subscriptions.add(sub);
    }

    private void handleUserLoaded(final User user) {
        this.recipientUser = user;
        this.activity.getBinding().recipientUser.setText(user.getUsername());
        updateConfirmationButtonState();
    }

    private void handleErrorResponse(final Throwable throwable) {
        LogUtil.e(getClass(), "handleErrorResponse " + throwable.getMessage());
    }

    @Override
    public void onViewDetached() {
        this.activity = null;
    }

    @Override
    public void onViewDestroyed() {
        if (this.subscriptions != null) {
            this.subscriptions.unsubscribe();
            this.subscriptions = null;
        }

        this.activity = null;
    }
}