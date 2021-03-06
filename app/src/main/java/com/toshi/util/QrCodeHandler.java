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

package com.toshi.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.toshi.R;
import com.toshi.exception.InvalidQrCode;
import com.toshi.exception.InvalidQrCodePayment;
import com.toshi.manager.model.PaymentTask;
import com.toshi.model.local.QrCodePayment;
import com.toshi.model.local.User;
import com.toshi.model.sofa.Payment;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.ChatActivity;
import com.toshi.view.activity.SendActivity;
import com.toshi.view.activity.ViewUserActivity;
import com.toshi.view.fragment.DialogFragment.PaymentConfirmationDialog;
import com.toshi.view.fragment.DialogFragment.PaymentConfirmationType;

import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class QrCodeHandler {

    public interface QrCodeHandlerListener {
        void onInvalidQrCode();
    }

    private static final String WEB_SIGNIN = "web-signin:";

    private CompositeSubscription subscriptions;
    private AppCompatActivity activity;
    private QrCodeHandlerListener listener;
    private @ScannerResultType.TYPE int scannerResultType;

    public QrCodeHandler(final AppCompatActivity activity, final @ScannerResultType.TYPE int scannerResultType) {
        this.activity = activity;
        this.subscriptions = new CompositeSubscription();
        this.scannerResultType = scannerResultType;
    }

    public void setOnQrCodeHandlerListener(final QrCodeHandlerListener listener) {
        this.listener = listener;
    }

    public void handleResult(final String result) {
        final QrCode qrCode = new QrCode(result);
        final @QrCodeType.Type int qrCodeType = qrCode.getQrCodeType();
        final boolean isScannerResultTypePaymentAddress = this.scannerResultType == ScannerResultType.PAYMENT_ADDRESS;

        if (isScannerResultTypePaymentAddress && qrCodeType != QrCodeType.PAYMENT_ADDRESS) {
            showToast(BaseApplication.get().getString(R.string.scan_error_payment));
            this.activity.finish();
        } else if (qrCodeType == QrCodeType.EXTERNAL_PAY) {
            handleExternalPayment(qrCode);
        } else if (qrCodeType == QrCodeType.ADD) {
            handleAddQrCode(qrCode);
        } else if (qrCodeType == QrCodeType.PAY) {
            handlePaymentQrCode(qrCode);
        } else if (qrCodeType == QrCodeType.PAYMENT_ADDRESS) {
            handlePaymentAddressQrCode(qrCode);
        } else if (result.startsWith(WEB_SIGNIN)) {
            handleWebLogin(result);
        } else {
            handleInvalidQrCode();
        }
    }

    private void handleExternalPayment(final QrCode qrCode)  {
        try {
            final QrCodePayment payment = qrCode.getExternalPayment();
            final Subscription sub =
                    getUserFromPaymentAddress(payment.getAddress())
                    .subscribe(
                            user -> showToshiPaymentConfirmationDialog(user.getToshiId(), payment),
                            __ -> showExternalPaymentConfirmationDialog(payment)
                    );

            this.subscriptions.add(sub);
        } catch (InvalidQrCodePayment e) {
            handleInvalidQrCode();
        }
    }

    private Single<User> getUserFromPaymentAddress(final String paymentAddress) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromPaymentAddress(paymentAddress)
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void showToshiPaymentConfirmationDialog(final String toshiId, final QrCodePayment payment) {
        if (this.activity == null) return;
        try {
            final PaymentConfirmationDialog dialog =
                    PaymentConfirmationDialog
                            .newInstanceToshiPayment(
                                    toshiId,
                                    payment.getValue(),
                                    payment.getMemo(),
                                    PaymentType.TYPE_REQUEST
                            );
            dialog.show(this.activity.getSupportFragmentManager(), PaymentConfirmationDialog.TAG);
            dialog.setOnPaymentConfirmationApprovedListener(this::onPaymentApproved)
                    .setOnPaymentConfirmationCanceledListener(this::onPaymentCanceled);
        } catch (InvalidQrCodePayment e) {
            handleInvalidQrCode();
        }
    }

    private void showExternalPaymentConfirmationDialog(final QrCodePayment payment) {
        if (this.activity == null) return;
        try {
            final PaymentConfirmationDialog dialog =
                    PaymentConfirmationDialog
                            .newInstanceExternalPayment(
                                    payment.getAddress(),
                                    payment.getValue(),
                                    payment.getMemo(),
                                    PaymentType.TYPE_REQUEST
                            );
            dialog.show(this.activity.getSupportFragmentManager(), PaymentConfirmationDialog.TAG);
            dialog.setOnPaymentConfirmationApprovedListener(this::onPaymentApproved)
                    .setOnPaymentConfirmationCanceledListener(this::onPaymentCanceled);
        } catch (InvalidQrCodePayment e) {
            handleInvalidQrCode();
        }
    }

    private void onPaymentApproved(final Bundle bundle, final PaymentTask paymentTask) {
        final @PaymentConfirmationType.Type int type = (bundle.getInt(PaymentConfirmationDialog.CONFIRMATION_TYPE));

        if (type == PaymentConfirmationType.EXTERNAL) {
            handleExternalPayment(paymentTask);
            return;
        }

        if (type == PaymentConfirmationType.TOSHI) {
            handleToshiPayment(bundle, paymentTask.getPayment());
            return;
        }

        LogUtil.i(getClass(), "Unhandled type in onPaymentApproved.");
    }

    private void onPaymentCanceled(final Bundle bundle) {
        this.activity.finish();
    }

    private void handleAddQrCode(final QrCode qrCode) {
        try {
            final Subscription sub =
                    getUserByUsername(qrCode.getUsername())
                    .doOnSuccess(__ -> playScanSound())
                    .subscribe(
                            this::handleUserToAdd,
                            __ -> handleInvalidQrCode()
                    );

            this.subscriptions.add(sub);
        } catch (InvalidQrCode e) {
            handleInvalidQrCode();
        }
    }

    private void handleUserToAdd(final User user) {
        if (user == null) {
            handleInvalidQrCode();
            return;
        }

        goToProfileView(user.getToshiId());
    }

    private void goToProfileView(final String toshiId) {
        if (this.activity == null) return;
        final Intent intent = new Intent(this.activity, ViewUserActivity.class)
                .putExtra(ViewUserActivity.EXTRA__USER_ADDRESS, toshiId)
                .putExtra(ViewUserActivity.EXTRA__PLAY_SCAN_SOUNDS, true);
        this.activity.startActivity(intent);
        this.activity.finish();
    }

    private void handlePaymentQrCode(final QrCode qrCode) {
        try {
            final QrCodePayment payment = qrCode.getToshiPayment();
            final Subscription sub =
                    getUserByUsername(payment.getUsername())
                    .doOnSuccess(__ -> playScanSound())
                    .subscribe(
                            user -> showToshiPaymentConfirmationDialog(user.getToshiId(), payment),
                            __ -> handleInvalidQrCode()
                    );

            this.subscriptions.add(sub);
        } catch (InvalidQrCodePayment e) {
            handleInvalidQrCode();
        }
    }

    private Single<User> getUserByUsername(final String username) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromUsername(username)
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void handlePaymentAddressQrCode(final QrCode qrCode) {
        if (this.scannerResultType == ScannerResultType.PAYMENT_ADDRESS) {
            finishActivityWithResult(qrCode);
        } else {
            copyPaymentAddressToClipBoard(qrCode);
            showToast(BaseApplication.get().getString(R.string.copied_payment_address_to_clipboard, qrCode.getPayload()));
        }
        this.activity.finish();
    }

    private void finishActivityWithResult(final QrCode qrCode) {
        if (this.activity == null) return;
        final Intent intent = new Intent()
                .putExtra(SendActivity.ACTIVITY_RESULT, qrCode.getPayloadAsAddress().getHexAddress());
        this.activity.setResult(Activity.RESULT_OK, intent);
        this.activity.finish();
    }

    private void copyPaymentAddressToClipBoard(final QrCode qrCode) {
        if (this.activity == null) return;
        final ClipboardManager clipboard = (ClipboardManager) this.activity.getSystemService(Context.CLIPBOARD_SERVICE);
        final ClipData clip = ClipData.newPlainText(this.activity.getString(R.string.payment_address), qrCode.getPayloadAsAddress().getHexAddress());
        clipboard.setPrimaryClip(clip);
    }

    private void handleWebLogin(final String result) {
        final String token = result.substring(WEB_SIGNIN.length());
        final Subscription sub =
                loginWithToken(token)
                .doOnSuccess(__ -> playScanSound())
                .subscribe(
                        __ -> handleLoginSuccess(),
                        this::handleLoginFailure
                );

        this.subscriptions.add(sub);
    }

    private Single<Void> loginWithToken(final String token) {
        return BaseApplication
                .get()
                .getUserManager()
                .webLogin(token)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void playScanSound() {
        SoundManager.getInstance().playSound(SoundManager.SCAN);
    }

    private void handleLoginSuccess() {
        showToast(BaseApplication.get().getString(R.string.web_signin));
        if (this.activity == null) return;
        this.activity.finish();
    }

    private void handleLoginFailure(final Throwable throwable) {
        LogUtil.exception(getClass(), "Login failure", throwable);
        showToast(BaseApplication.get().getString(R.string.error__web_signin));
    }

    private Payment createPayment(final Bundle bundle) {
        final String value = bundle.getString(PaymentConfirmationDialog.ETH_AMOUNT);
        final String paymentAddress = bundle.getString(PaymentConfirmationDialog.PAYMENT_ADDRESS);
        return new Payment()
                .setValue(value)
                .setToAddress(paymentAddress);
    }

    private void handleExternalPayment(final PaymentTask paymentTask) {
        try {
            sendExternalPayment(paymentTask);
        } catch (InvalidQrCodePayment invalidQrCodePayment) {
            handleInvalidQrCode();
        }
    }

    private void sendExternalPayment(final PaymentTask paymentTask) throws InvalidQrCodePayment {
        BaseApplication
                .get()
                .getTransactionManager()
                .sendExternalPayment(paymentTask);
    }

    private void handleToshiPayment(final Bundle bundle, final Payment payment) {
        try {
            final String toshiId = bundle.getString(PaymentConfirmationDialog.TOSHI_ID);
            goToChatActivityWithPayment(toshiId, payment);
        } catch (final InvalidQrCodePayment ex) {
            handleInvalidQrCode();
        }
    }

    private void goToChatActivityWithPayment(final String toshiId, final Payment payment) throws InvalidQrCodePayment {
        final Intent intent = new Intent(this.activity, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA__THREAD_ID, toshiId)
                .putExtra(ChatActivity.EXTRA__PAYMENT_ACTION, PaymentType.TYPE_SEND)
                .putExtra(ChatActivity.EXTRA__ETH_AMOUNT, payment.getValue())
                .putExtra(ChatActivity.EXTRA__PLAY_SCAN_SOUNDS, true);

        this.activity.startActivity(intent);
        this.activity.finish();
    }

    private void handleInvalidQrCode() {
        SoundManager.getInstance().playSound(SoundManager.SCAN_ERROR);
        this.listener.onInvalidQrCode();
    }

    private void showToast(final String string) {
        Toast.makeText(
                BaseApplication.get(),
                string,
                Toast.LENGTH_LONG
        ).show();
    }

    public void clear() {
        this.subscriptions.clear();
        this.activity = null;
    }
}
