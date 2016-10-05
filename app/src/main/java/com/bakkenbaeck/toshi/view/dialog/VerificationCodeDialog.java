package com.bakkenbaeck.toshi.view.dialog;


import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.view.ContextThemeWrapper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import com.bakkenbaeck.toshi.R;
import com.bakkenbaeck.toshi.network.rest.ToshiService;
import com.bakkenbaeck.toshi.network.ws.model.VerificationConfirm;
import com.bakkenbaeck.toshi.network.ws.model.VerificationSuccess;
import com.bakkenbaeck.toshi.network.ws.model.WebSocketError;
import com.bakkenbaeck.toshi.util.OnNextSubscriber;
import com.bakkenbaeck.toshi.view.BaseApplication;

public class VerificationCodeDialog extends DialogFragment {

    private static final String PHONE_NUMBER = "phone_number";
    private String phoneNumber;
    private Listener listener;
    private View view;

    private OnNextSubscriber<WebSocketError> errorSubscriber;
    private OnNextSubscriber<VerificationSuccess> successSubscriber;

    /* The activity that creates an instance of this dialog fragment must
     * implement this interface in order to receive event callbacks.
     * Each method passes the DialogFragment in case the host needs to query it. */
    public interface Listener {
        void onVerificationCodeSuccess(final VerificationCodeDialog dialog);
    }


    public static VerificationCodeDialog newInstance(final String phoneNumber) {
        final VerificationCodeDialog dialog = new VerificationCodeDialog();
        final Bundle args = new Bundle();
        args.putString(PHONE_NUMBER, phoneNumber);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        try {
            this.listener = (Listener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement VerificationCodeDialog.Listener");
        }

        this.errorSubscriber = generateErrorSubscriber();
        this.successSubscriber = generateSuccessSubscriber();
        BaseApplication.get().getSocketObservables().getErrorObservable().subscribe(this.errorSubscriber);
        BaseApplication.get().getSocketObservables().getVerificationSuccessObservable().subscribe(this.successSubscriber);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(new ContextThemeWrapper(getActivity(), R.style.DialogTheme));
        final LayoutInflater inflater = getActivity().getLayoutInflater();

        this.view = inflater.inflate(R.layout.dialog_verification_code, null);
        builder.setView(this.view);

        this.phoneNumber = getArguments().getString(PHONE_NUMBER);
        initViews(this.view);

        final Dialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private OnNextSubscriber<WebSocketError> generateErrorSubscriber() {
        return new OnNextSubscriber<WebSocketError>() {
            @Override
            public void onNext(final WebSocketError webSocketError) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        view.findViewById(R.id.spinner_view).setVisibility(View.INVISIBLE);
                        setErrorOnCodeField();
                    }
                });
            }
        };
    }

    private OnNextSubscriber<VerificationSuccess> generateSuccessSubscriber() {
        return new OnNextSubscriber<VerificationSuccess>() {
            @Override
            public void onNext(final VerificationSuccess verificationSuccess) {
                // Update the user
                BaseApplication.get().getUserManager().refresh();
                listener.onVerificationCodeSuccess(VerificationCodeDialog.this);
                dismiss();
            }
        };
    }

    private void initViews(final View view) {
        view.findViewById(R.id.cancelButton).setOnClickListener(this.dismissDialog);
        view.findViewById(R.id.continueButton).setOnClickListener(new ValidateAndContinueDialog(view));
    }

    private final View.OnClickListener dismissDialog = new View.OnClickListener() {
        @Override
        public void onClick(final View v) {
            dismiss();
        }
    };

    private class ValidateAndContinueDialog implements View.OnClickListener {

        private final View view;

        private ValidateAndContinueDialog(final View view) {
            this.view = view;
        }

        @Override
        public void onClick(final View v) {
            final EditText verificationCodeInput = (EditText) this.view.findViewById(R.id.verification_code);
            if (TextUtils.isEmpty(verificationCodeInput.getText())) {
                setErrorOnCodeField();
                return;
            }

            final String inputtedVerificationCode = verificationCodeInput.getText().toString().trim();

            final VerificationConfirm vcFrame = new VerificationConfirm(phoneNumber, inputtedVerificationCode);
            BaseApplication.get().sendWebSocketMessage(vcFrame.toString());

            this.view.findViewById(R.id.spinner_view).setVisibility(View.VISIBLE);
        }
    }

    private void setErrorOnCodeField() {
        final EditText verificationCodeInput = (EditText) this.view.findViewById(R.id.verification_code);
        verificationCodeInput.requestFocus();
        verificationCodeInput.setError(getString(R.string.error__invalid_verification_code));
    }

    @Override
    public void onDetach() {
        this.errorSubscriber.unsubscribe();
        this.successSubscriber.unsubscribe();
        this.errorSubscriber = null;
        this.successSubscriber = null;
        super.onDetach();
    }
}