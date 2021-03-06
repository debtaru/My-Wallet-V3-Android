package info.blockchain.wallet.view;

import com.google.common.collect.HashBiMap;
import com.google.zxing.client.android.CaptureActivity;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.util.Pair;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import info.blockchain.wallet.app_rate.AppRate;
import info.blockchain.wallet.callbacks.CustomKeypadCallback;
import info.blockchain.wallet.callbacks.OpCallback;
import info.blockchain.wallet.callbacks.OpSimpleCallback;
import info.blockchain.wallet.connectivity.ConnectivityStatus;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.Account;
import info.blockchain.wallet.payload.AddressBookEntry;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.PayloadBridge;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.send.SendCoins;
import info.blockchain.wallet.send.SendFactory;
import info.blockchain.wallet.send.SendMethods;
import info.blockchain.wallet.send.SuggestedFee;
import info.blockchain.wallet.send.UnspentOutputsBundle;
import info.blockchain.wallet.util.AppUtil;
import info.blockchain.wallet.util.CharSequenceX;
import info.blockchain.wallet.util.DoubleEncryptionFactory;
import info.blockchain.wallet.util.ExchangeRateFactory;
import info.blockchain.wallet.util.FeeUtil;
import info.blockchain.wallet.util.FormatsUtil;
import info.blockchain.wallet.util.MonetaryUtil;
import info.blockchain.wallet.util.PermissionUtil;
import info.blockchain.wallet.util.PrefsUtil;
import info.blockchain.wallet.util.PrivateKeyFactory;
import info.blockchain.wallet.util.WebUtil;
import info.blockchain.wallet.view.helpers.CustomKeypad;
import info.blockchain.wallet.view.helpers.ToastCustom;
import info.blockchain.wallet.viewModel.SendViewModel;

import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.params.MainNetParams;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import piuk.blockchain.android.R;
import piuk.blockchain.android.databinding.FragmentSendBinding;

public class SendFragment extends Fragment implements CustomKeypadCallback, SendFactory.OnFeeSuggestListener, SendViewModel.DataListener {

    private final int SCAN_PRIVX = 301;
    private static Context context = null;

    private MenuItem btSend;
    public static CustomKeypad customKeypad;

    private List<String> sendFromList = null;
    private HashBiMap<Object, Integer> sendFromBiMap = null;
    private SendFromAdapter sendFromAdapter = null;

    private List<String> receiveToList = null;
    private HashBiMap<Object, Integer> receiveToBiMap = null;
    private ReceiveToAdapter receiveToAdapter = null;
    private HashMap<Integer, Integer> spinnerIndexAccountIndexMap = null;

    private TextWatcher btcTextWatcher = null;
    private TextWatcher fiatTextWatcher = null;

    private ProgressDialog progress = null;

    private String strBTC = "BTC";
    private String strFiat = null;
    private double btc_fx = 319.13;
    private boolean textChangeAllowed = true;
    private String defaultSeparator;//Decimal separator based on locale
    private boolean spendInProgress = false;//Used to avoid double clicking on spend and opening confirm dialog twice
    private boolean spDestinationSelected = false;//When a destination is selected from dropdown, mark spend as 'Moved'

    private PendingSpend watchOnlyPendingSpend;

    long balanceAvailable = 0L;//balance from multi_address

    private Pair<String, String> unspentApiResponse;//current selected <from address, unspent api response> - so we don't need to call api repeatedly
    private SuggestedFee suggestedFeeBundle;
    private UnspentOutputsBundle unspentsCoinsBundle;
    private BigInteger absoluteFeeSuggested = FeeUtil.AVERAGE_FEE;//Will default to if not set
    private BigInteger absoluteFeeUsed = FeeUtil.AVERAGE_FEE;

    private BigInteger[] absoluteFeeSuggestedEstimates = null;
    private PrefsUtil prefsUtil;
    private MonetaryUtil monetaryUtil;
    private PayloadManager payloadManager;

    private FragmentSendBinding binding;
//    private SendViewModel viewModel;

    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {

            if (BalanceFragment.ACTION_INTENT.equals(intent.getAction())) {
                updateSendFromSpinnerList();
                updateReceiveToSpinnerList();
            }
        }
    };

    private class PendingSpend {

        boolean isHD;
        int fromXpubIndex;
        LegacyAddress fromLegacyAddress;
        Account fromAccount;
        String note;
        String destination;
        BigInteger bigIntFee;
        BigInteger bigIntAmount;

        @Override
        public String toString() {
            return "PendingSpend{" +
                    "isHD=" + isHD +
                    ", fromXpubIndex=" + fromXpubIndex +
                    ", fromLegacyAddress=" + fromLegacyAddress +
                    ", fromAccount=" + fromAccount +
                    ", note='" + note + '\'' +
                    ", destination='" + destination + '\'' +
                    ", bigIntFee=" + bigIntFee +
                    ", bigIntAmount=" + bigIntAmount +
                    '}';
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_send, container, false);
//        viewModel = new SendViewModel(getActivity(), this);
//        binding.setViewModel(viewModel);

        payloadManager = PayloadManager.getInstance();
        prefsUtil = new PrefsUtil(getActivity());
        monetaryUtil = new MonetaryUtil(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));

        setupToolbar();

        setupViews();

        defaultSeparator = getDefaultDecimalSeparator();

        setBtcTextWatcher();

        setFiatTextWatcher();

        sendFromList = new ArrayList<>();
        sendFromBiMap = HashBiMap.create();
        receiveToList = new ArrayList<>();
        receiveToBiMap = HashBiMap.create();
        spinnerIndexAccountIndexMap = new HashMap<>();

        updateSendFromSpinnerList();
        updateReceiveToSpinnerList();

        setSendFromDropDown();
        setReceiveToDropDown();

        initVars();

        SendFactory.getInstance(getActivity()).getSuggestedFee(this);

        binding.accounts.spinner.setSelection(0);

        selectDefaultAccount();

        setCustomKeypad();

        Bundle bundle = this.getArguments();
        if (bundle != null) {
            String scanData = bundle.getString("scan_data", "");
            handleIncomingQRScan(scanData);
        }

        return binding.getRoot();
    }

    private void setupToolbar(){

        Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(getResources().getDrawable(R.drawable.ic_arrow_back_white_24dp));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View view = getActivity().getCurrentFocus();
                if (view != null) {
                    InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputManager.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                }

                Fragment fragment = new BalanceFragment();
                FragmentManager fragmentManager = getFragmentManager();
                fragmentManager.beginTransaction().replace(R.id.content_frame, fragment).commit();
            }
        });

        if(((AppCompatActivity) getActivity()).getSupportActionBar() == null){
            ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        }

        ((AppCompatActivity) getActivity()).getSupportActionBar().setDisplayShowTitleEnabled(true);
        ((AppCompatActivity) getActivity()).findViewById(R.id.account_spinner).setVisibility(View.GONE);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.send_bitcoin);
        setHasOptionsMenu(true);
    }

    private void setupViews(){

        binding.destination.setHorizontallyScrolling(false);
        binding.destination.setLines(3);
        binding.destination.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if(hasFocus && customKeypad != null)
                    customKeypad.setNumpadVisibility(View.GONE);
            }
        });

        binding.customFee.setKeyListener(DigitsKeyListener.getInstance("0123456789" + getDefaultDecimalSeparator()));
        //As soon as the user customizes our suggested dynamic fee - hide (recommended)
        binding.customFee.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable customizedFee) {
                unspentsCoinsBundle = getCoins();

                if(unspentsCoinsBundle != null) {
                    long balanceAfterFee = (unspentsCoinsBundle.getTotalAmount().longValue() - absoluteFeeUsed.longValue());

                    if (balanceAfterFee < 0) {
                        binding.max.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                    } else {
                        binding.max.setTextColor(getResources().getColor(R.color.textColorPrimary));
                    }

                    String likelyToConfirmMessage = getText(R.string.estimate_confirm_block_count).toString();
                    String unlikelyToConfirmMessage = getText(R.string.fee_too_low_no_confirm).toString();

                    // TODO - MonetaryUtil has small rounding bug so + 1 to show correct block
                    String estimateText = SendMethods.getEstimatedConfirmationMessage(getLongValue(customizedFee.toString()) + 1, absoluteFeeSuggestedEstimates, likelyToConfirmMessage, unlikelyToConfirmMessage);
                    binding.tvEstimate.setText(estimateText);

                    if (estimateText.equals(unlikelyToConfirmMessage)) {
                        binding.tvEstimate.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                    } else {
                        binding.tvEstimate.setTextColor(getResources().getColor(R.color.blockchain_blue));
                    }

                    displaySweepAmount();
                }
            }
        });

        binding.ivFeeInfo.setOnClickListener(v -> alertCustomSpend(absoluteFeeSuggested));
    }

    private void setCustomKeypad(){

        customKeypad = new CustomKeypad(getActivity(), (binding.keypad.numericPad));
        customKeypad.setDecimalSeparator(defaultSeparator);

        //Enable custom keypad and disables default keyboard from popping up
        customKeypad.enableOnView(binding.amountRow.amountBtc);
        customKeypad.enableOnView(binding.amountRow.amountFiat);
        customKeypad.enableOnView(binding.customFee);

        binding.amountRow.amountBtc.setText("");
        binding.amountRow.amountBtc.requestFocus();
    }

    private BigInteger getCustomFee(){

        long amountL = 0L;
        if(!binding.customFee.getText().toString().isEmpty())
            amountL = getLongValue(binding.customFee.getText().toString());

        return BigInteger.valueOf(amountL);
    }

    @Override
    public void onFeeSuggested(SuggestedFee suggestedFee) {

        //Suggested fee received - recalculate unspents based on current input fields
        suggestedFeeBundle = suggestedFee;

        if(unspentApiResponse != null){
            unspentsCoinsBundle = getCoins();
            displaySweepAmount();
        }
    }

    private void setSendFromDropDown(){

        if (sendFromList.size() == 1){
            getUnspent(0);//only 1 item in from dropdown (Account or Legacy Address)
            binding.fromRow.setVisibility(View.GONE);
        }

        sendFromAdapter = new SendFromAdapter(getActivity(), R.layout.spinner_item, sendFromList);
        binding.accounts.spinner.setAdapter(sendFromAdapter);
        binding.accounts.spinner.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {

            @Override
            public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    binding.accounts.spinner.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    binding.accounts.spinner.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    binding.accounts.spinner.setDropDownWidth(binding.accounts.spinner.getWidth());
                }
            }
        });

        binding.accounts.spinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {

                        unspentsCoinsBundle = null;
                        unspentApiResponse = null;
                        binding.max.setVisibility(View.GONE);
                        binding.progressBarMaxAvailable.setVisibility(View.VISIBLE);
                        if(btSend != null)btSend.setEnabled(false);

                        getUnspent(binding.accounts.spinner.getSelectedItemPosition());//the current selected item in from dropdown (Account or Legacy Address)
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> arg0) {
                        ;
                    }
                }
        );

    }

    private void getUnspent(int index){
        final String fromAddress;

        //Fetch unspent data from unspent api
        Object object = sendFromBiMap.inverse().get(index);
        if(object instanceof Account) {
            //V3
            fromAddress = ((Account) object).getXpub();
            if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(((Account) object).getXpub())) {
                balanceAvailable = MultiAddrFactory.getInstance().getXpubAmounts().get(((Account) object).getXpub());
            }
        }else{
            //V2
            fromAddress = ((LegacyAddress)object).getAddress();
            balanceAvailable = MultiAddrFactory.getInstance().getLegacyBalance(((LegacyAddress)object).getAddress());
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {

                try {
                    String response = WebUtil.getInstance().getURL(WebUtil.UNSPENT_OUTPUTS_URL + fromAddress);
                    unspentApiResponse = new Pair<String, String>(fromAddress,response);
                } catch (Exception e) {
                    unspentApiResponse = null;
                }
                unspentsCoinsBundle = getCoins();
                displaySweepAmount();

                return null;
            }

        }.execute();
    }

    private void updateSendFromSpinnerList() {
        //sendFromList is linked to Adapter - do not reconstruct or loose reference otherwise notifyDataSetChanged won't work
        sendFromList.clear();
        sendFromBiMap.clear();

        int spinnerIndex = 0;

        if (payloadManager.getPayload().isUpgraded()) {

            //V3
            List<Account> accounts = payloadManager.getPayload().getHdWallet().getAccounts();
            for (Account item : accounts) {

                if (item.isArchived())
                    continue;//skip archived account

                //no xpub watch only yet

                sendFromList.add(item.getLabel());
                sendFromBiMap.put(item, spinnerIndex);
                spinnerIndex++;
            }
        }

        List<LegacyAddress> legacyAddresses = payloadManager.getPayload().getLegacyAddresses();

        for (LegacyAddress legacyAddress : legacyAddresses) {

            if (legacyAddress.getTag() == PayloadManager.ARCHIVED_ADDRESS)
                continue;//skip archived

            //If address has no label, we'll display address
            String labelOrAddress = legacyAddress.getLabel() == null || legacyAddress.getLabel().length() == 0 ? legacyAddress.getAddress() : legacyAddress.getLabel();

            //Append watch-only with a label - we'll asl for xpriv scan when spending from
            if(legacyAddress.isWatchOnly()){
                labelOrAddress = getResources().getString(R.string.watch_only_label)+" "+labelOrAddress;
            }

            sendFromList.add(labelOrAddress);
            sendFromBiMap.put(legacyAddress, spinnerIndex);
            spinnerIndex++;
        }

        //Notify adapter of list update
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (sendFromAdapter != null) sendFromAdapter.notifyDataSetChanged();
            }
        });
    }

    private void updateReceiveToSpinnerList() {
        //receiveToList is linked to Adapter - do not reconstruct or loose reference otherwise notifyDataSetChanged won't work
        receiveToList.clear();
        receiveToBiMap.clear();
        spinnerIndexAccountIndexMap.clear();

        int spinnerIndex = 0;

        if (payloadManager.getPayload().isUpgraded()) {

            //V3
            List<Account> accounts = payloadManager.getPayload().getHdWallet().getAccounts();
            int accountIndex = 0;
            for (Account item : accounts) {

                spinnerIndexAccountIndexMap.put(spinnerIndex, accountIndex);
                accountIndex++;

                if (item.isArchived())
                    continue;//skip archived account

                //no xpub watch only yet

                receiveToList.add(item.getLabel());
                receiveToBiMap.put(item, spinnerIndex);
                spinnerIndex++;
            }
        }

        List<LegacyAddress> legacyAddresses = payloadManager.getPayload().getLegacyAddresses();

        for (LegacyAddress legacyAddress : legacyAddresses) {

            if (legacyAddress.getTag() == PayloadManager.ARCHIVED_ADDRESS)
                continue;//skip archived address

            //If address has no label, we'll display address
            String labelOrAddress = legacyAddress.getLabel() == null || legacyAddress.getLabel().length() == 0 ? legacyAddress.getAddress() : legacyAddress.getLabel();

            //Prefix "watch-only"
            if (legacyAddress.isWatchOnly()) {
                labelOrAddress = getActivity().getString(R.string.watch_only_label) + " " + labelOrAddress;
            }

            receiveToList.add(labelOrAddress);
            receiveToBiMap.put(legacyAddress, spinnerIndex);
            spinnerIndex++;
        }

        //Address Book
        List<AddressBookEntry> addressBookEntries = payloadManager.getPayload().getAddressBookEntries();

        for(AddressBookEntry addressBookEntry : addressBookEntries){

            //If address has no label, we'll display address
            String labelOrAddress = addressBookEntry.getLabel() == null || addressBookEntry.getLabel().length() == 0 ? addressBookEntry.getAddress() : addressBookEntry.getLabel();

            receiveToList.add(labelOrAddress);
            receiveToBiMap.put(addressBookEntry, spinnerIndex);
            spinnerIndex++;

        }

        //Notify adapter of list update
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (receiveToAdapter != null) receiveToAdapter.notifyDataSetChanged();
            }
        });
    }

    private void setReceiveToDropDown(){

        receiveToAdapter = new ReceiveToAdapter(getActivity(), R.layout.spinner_item, receiveToList);
        receiveToAdapter.setDropDownViewResource(R.layout.spinner_dropdown);

        //If there is only 1 account/address - hide drop down
        if (receiveToList.size() <= 1) binding.spDestination.setVisibility(View.GONE);

        binding.spDestination.setAdapter(receiveToAdapter);
        binding.spDestination.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {

            @Override
            public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    binding.spDestination.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    binding.spDestination.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }

                if(binding.accounts.spinner.getWidth() > 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        binding.spDestination.setDropDownWidth(binding.accounts.spinner.getWidth());
                    }
            }
        });

        binding.spDestination.setOnItemSelectedEvenIfUnchangedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {

                        final Object object = receiveToBiMap.inverse().get(binding.spDestination.getSelectedItemPosition());

                        if (object instanceof LegacyAddress) {

                            //V2
                            if (((LegacyAddress) object).isWatchOnly() && prefsUtil.getValue("WARN_WATCH_ONLY_SPEND", true)) {

                                promptWatchOnlySpendWarning(object);

                            } else {
                                binding.destination.setText(((LegacyAddress) object).getAddress());
                            }
                        } else if(object instanceof Account){
                            //V3
                            //TODO - V3 no watch only yet
                            binding.destination.setText(getV3ReceiveAddress((Account) object));

                        } else if (object instanceof AddressBookEntry){
                            //Address book
                            binding.destination.setText(((AddressBookEntry) object).getAddress());
                        }

                        spDestinationSelected = true;
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                }
        );
    }

    private void promptWatchOnlySpendWarning(final Object object){

        if (object instanceof LegacyAddress && ((LegacyAddress) object).isWatchOnly()) {

            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
            LayoutInflater inflater = getActivity().getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.alert_watch_only_spend, null);
            dialogBuilder.setView(dialogView);
            dialogBuilder.setCancelable(false);

            final AlertDialog alertDialog = dialogBuilder.create();
            alertDialog.setCanceledOnTouchOutside(false);

            final CheckBox confirmDismissForever = (CheckBox) dialogView.findViewById(R.id.confirm_dont_ask_again);

            TextView confirmCancel = (TextView) dialogView.findViewById(R.id.confirm_cancel);
            confirmCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.destination.setText("");
                    if(confirmDismissForever.isChecked()) prefsUtil.setValue("WARN_WATCH_ONLY_SPEND", false);
                    alertDialog.dismiss();
                }
            });

            TextView confirmContinue = (TextView) dialogView.findViewById(R.id.confirm_continue);
            confirmContinue.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.destination.setText(((LegacyAddress) object).getAddress());
                    if(confirmDismissForever.isChecked()) prefsUtil.setValue("WARN_WATCH_ONLY_SPEND", false);
                    alertDialog.dismiss();
                }
            });

            alertDialog.show();
        }
    }

    private String getV3ReceiveAddress(Account account) {

        try {
            int spinnerIndex = receiveToBiMap.get(account);
            int accountIndex = spinnerIndexAccountIndexMap.get(spinnerIndex);
            return payloadManager.getReceiveAddress(accountIndex);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getDefaultDecimalSeparator(){
        DecimalFormat format = (DecimalFormat) DecimalFormat.getInstance(Locale.getDefault());
        DecimalFormatSymbols symbols = format.getDecimalFormatSymbols();
        return Character.toString(symbols.getDecimalSeparator());
    }

    private void initVars(){

        binding.amountRow.amountBtc.addTextChangedListener(btcTextWatcher);
        binding.amountRow.amountBtc.setSelectAllOnFocus(true);

        binding.amountRow.amountFiat.setKeyListener(DigitsKeyListener.getInstance("0123456789" + defaultSeparator));
        binding.amountRow.amountFiat.setHint("0" + defaultSeparator + "00");
        binding.amountRow.amountFiat.addTextChangedListener(fiatTextWatcher);
        binding.amountRow.amountFiat.setSelectAllOnFocus(true);

        binding.amountRow.amountBtc.setKeyListener(DigitsKeyListener.getInstance("0123456789" + defaultSeparator));
        binding.amountRow.amountBtc.setHint("0" + defaultSeparator + "00");

        strBTC = monetaryUtil.getBTCUnit(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));
        strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY);
        btc_fx = ExchangeRateFactory.getInstance(getActivity()).getLastPrice(strFiat);

        binding.amountRow.currencyBtc.setText(strBTC);
        binding.tvFeeUnit.setText(strBTC);
        binding.amountRow.currencyFiat.setText(strFiat);
    }

    private void handleIncomingQRScan(String scanData){

        scanData = scanData.trim();

        String btcAddress = null;
        String btcAmount = null;

        // check for poorly formed BIP21 URIs
        if (scanData.startsWith("bitcoin://") && scanData.length() > 10) {
            scanData = "bitcoin:" + scanData.substring(10);
        }

        if (FormatsUtil.getInstance().isValidBitcoinAddress(scanData)) {
            btcAddress = scanData;
        } else if (FormatsUtil.getInstance().isBitcoinUri(scanData)) {
            btcAddress = FormatsUtil.getInstance().getBitcoinAddress(scanData);
            btcAmount = FormatsUtil.getInstance().getBitcoinAmount(scanData);

            //Convert to correct units
            try {
                btcAmount = monetaryUtil.getDisplayAmount(Long.parseLong(btcAmount));
            }catch (Exception e){
                btcAmount = null;
            }
        } else {
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_bitcoin_address), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return;
        }

        if (!btcAddress.equals("")) {
            binding.destination.setText(btcAddress);
        }

        if (btcAmount != null && !btcAmount.equals("")) {
            binding.amountRow.amountBtc.removeTextChangedListener(btcTextWatcher);
            binding.amountRow.amountFiat.removeTextChangedListener(fiatTextWatcher);

            binding.amountRow.amountBtc.setText(btcAmount);
            binding.amountRow.amountBtc.setSelection(binding.amountRow.amountBtc.getText().toString().length());

            double btc_amount = 0.0;
            try {
                btc_amount = monetaryUtil.getUndenominatedAmount(Double.parseDouble(binding.amountRow.amountBtc.getText().toString()));
            } catch (NumberFormatException e) {
                btc_amount = 0.0;
            }

            // sanity check on strFiat, necessary if the result of a URI scan
            if (strFiat == null) {
                strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY);
            }
            btc_fx = ExchangeRateFactory.getInstance(getActivity()).getLastPrice(strFiat);

            double fiat_amount = btc_fx * btc_amount;
            binding.amountRow.amountFiat.setText(monetaryUtil.getFiatFormat(strFiat).format(fiat_amount));
//                PrefsUtil.getInstance(getActivity()).setValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC);
            strBTC = monetaryUtil.getBTCUnit(MonetaryUtil.UNIT_BTC);
            binding.amountRow.currencyBtc.setText(strBTC);
            binding.tvFeeUnit.setText(strBTC);
            binding.amountRow.currencyFiat.setText(strFiat);

            binding.amountRow.amountBtc.addTextChangedListener(btcTextWatcher);
            binding.amountRow.amountFiat.addTextChangedListener(fiatTextWatcher);
        }
    }

    private void setBtcTextWatcher(){

        btcTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {

                String input = s.toString();

                long lamount = 0L;
                try {
                    //Long is safe to use, but double can lead to ugly rounding issues..
                    lamount = (BigDecimal.valueOf(monetaryUtil.getUndenominatedAmount(Double.parseDouble(binding.amountRow.amountBtc.getText().toString()))).multiply(BigDecimal.valueOf(100000000)).longValue());

                    if (BigInteger.valueOf(lamount).compareTo(BigInteger.valueOf(2100000000000000L)) == 1) {
                        ToastCustom.makeText(getActivity(), getActivity().getString(R.string.invalid_amount), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
                        binding.amountRow.amountBtc.setText("0.0");
                        return;
                    }
                } catch (NumberFormatException nfe) {
                    ;
                }

                binding.amountRow.amountBtc.removeTextChangedListener(this);

                int unit = prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC);
                int max_len = 8;
                NumberFormat btcFormat = NumberFormat.getInstance(Locale.getDefault());
                switch (unit) {
                    case MonetaryUtil.MICRO_BTC:
                        max_len = 2;
                        break;
                    case MonetaryUtil.MILLI_BTC:
                        max_len = 4;
                        break;
                    default:
                        max_len = 8;
                        break;
                }
                btcFormat.setMaximumFractionDigits(max_len + 1);
                btcFormat.setMinimumFractionDigits(0);

                try {
                    if (input.indexOf(defaultSeparator) != -1) {
                        String dec = input.substring(input.indexOf(defaultSeparator));
                        if (dec.length() > 0) {
                            dec = dec.substring(1);
                            if (dec.length() > max_len) {
                                binding.amountRow.amountBtc.setText(input.substring(0, input.length() - 1));
                                binding.amountRow.amountBtc.setSelection(binding.amountRow.amountBtc.getText().length());
                                s = binding.amountRow.amountBtc.getEditableText();
                            }
                        }
                    }
                } catch (NumberFormatException nfe) {
                    ;
                }

                binding.amountRow.amountBtc.addTextChangedListener(this);

                if (textChangeAllowed) {
                    textChangeAllowed = false;
                    updateFiatTextField(s.toString());
                    textChangeAllowed = true;
                }

                if (s.toString().contains(defaultSeparator))
                    binding.amountRow.amountBtc.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
                else
                    binding.amountRow.amountBtc.setKeyListener(DigitsKeyListener.getInstance("0123456789" + defaultSeparator));

                if (unspentApiResponse != null) {
                    unspentsCoinsBundle = getCoins();
                    displaySweepAmount();
                }
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ;
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ;
            }
        };

    }

    private void setFiatTextWatcher(){

        fiatTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {

                String input = s.toString();

                binding.amountRow.amountFiat.removeTextChangedListener(this);

                int max_len = 2;
                NumberFormat fiatFormat = NumberFormat.getInstance(Locale.getDefault());
                fiatFormat.setMaximumFractionDigits(max_len + 1);
                fiatFormat.setMinimumFractionDigits(0);

                try {
                    if (input.indexOf(defaultSeparator) != -1) {
                        String dec = input.substring(input.indexOf(defaultSeparator));
                        if (dec.length() > 0) {
                            dec = dec.substring(1);
                            if (dec.length() > max_len) {
                                binding.amountRow.amountFiat.setText(input.substring(0, input.length() - 1));
                                binding.amountRow.amountFiat.setSelection(binding.amountRow.amountFiat.getText().length());
                                s = binding.amountRow.amountFiat.getEditableText();
                            }
                        }
                    }
                } catch (NumberFormatException nfe) {
                    ;
                }

                binding.amountRow.amountFiat.addTextChangedListener(this);

                if (textChangeAllowed) {
                    textChangeAllowed = false;
                    updateBtcTextField(s.toString());
                    textChangeAllowed = true;
                }

                if (s.toString().contains(defaultSeparator))
                    binding.amountRow.amountFiat.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
                else
                    binding.amountRow.amountFiat.setKeyListener(DigitsKeyListener.getInstance("0123456789" + defaultSeparator));

                if (unspentApiResponse != null) {
                    unspentsCoinsBundle = getCoins();
                    displaySweepAmount();
                }
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                ;
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ;
            }
        };

    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
            strBTC = monetaryUtil.getBTCUnit(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));
            strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY);
            btc_fx = ExchangeRateFactory.getInstance(getActivity()).getLastPrice(strFiat);
            binding.amountRow.currencyBtc.setText(strBTC);
            binding.tvFeeUnit.setText(strBTC);
            binding.amountRow.currencyFiat.setText(strFiat);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        MainActivity.currentFragment = this;

        strBTC = monetaryUtil.getBTCUnit(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC));
        strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY);
        btc_fx = ExchangeRateFactory.getInstance(getActivity()).getLastPrice(strFiat);
        binding.amountRow.currencyBtc.setText(strBTC);
        binding.tvFeeUnit.setText(strBTC);
        binding.amountRow.currencyFiat.setText(strFiat);

        if (getArguments() != null)
            if (getArguments().getBoolean("incoming_from_scan", false)) {
                ;
            }

        IntentFilter filter = new IntentFilter(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(receiver, filter);
    }

    private void selectDefaultAccount() {

        if (binding.accounts.spinner != null) {

            if (payloadManager.getPayload().isUpgraded()) {
                int defaultIndex = payloadManager.getPayload().getHdWallet().getDefaultIndex();
                Account defaultAccount = payloadManager.getPayload().getHdWallet().getAccounts().get(defaultIndex);
                int defaultSpinnerIndex = sendFromBiMap.get(defaultAccount);
                binding.accounts.spinner.setSelection(defaultSpinnerIndex);
            } else {
                //V2
                binding.accounts.spinner.setSelection(0);
            }
        }
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(receiver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void updateFiatTextField(String cBtc) {
        if(cBtc.isEmpty())cBtc = "0";
        double btc_amount = 0.0;
        try {
            btc_amount = monetaryUtil.getUndenominatedAmount(NumberFormat.getInstance(Locale.getDefault()).parse(cBtc).doubleValue());
        } catch (NumberFormatException nfe) {
            btc_amount = 0.0;
        } catch (ParseException pe) {
            btc_amount = 0.0;
        }
        double fiat_amount = btc_fx * btc_amount;
        binding.amountRow.amountFiat.setText(monetaryUtil.getFiatFormat(strFiat).format(fiat_amount));
    }

    private void updateBtcTextField(String cfiat) {
        if(cfiat.isEmpty())cfiat = "0";
        double fiat_amount = 0.0;
        try {
            fiat_amount = NumberFormat.getInstance(Locale.getDefault()).parse(cfiat).doubleValue();
        } catch (NumberFormatException | ParseException e) {
            fiat_amount = 0.0;
        }
        double btc_amount = fiat_amount / btc_fx;
        binding.amountRow.amountBtc.setText(monetaryUtil.getBTCFormat().format(monetaryUtil.getDenominatedAmount(btc_amount)));
    }

    private BigInteger getSpendAmount(){

        //Get amount to spend
        long amountL = 0L;
        if(!binding.amountRow.amountBtc.getText().toString().isEmpty())
            amountL = getLongValue(binding.amountRow.amountBtc.getText().toString());

        return BigInteger.valueOf(amountL);
    }

    /*
    Get details needed to gather unspent data
     */
    private UnspentOutputsBundle getCoins(){

        if(unspentApiResponse == null)return null;

        //Get from address(xpub or legacy) and unspent_api response
        String fromAddress = unspentApiResponse.first;
        String unspentApiString = unspentApiResponse.second;

        BigInteger spendAmount = getSpendAmount();

        //Check should we use dynamic or customized fee?
        boolean useCustomFee = !binding.customFee.getText().toString().isEmpty();
        BigInteger feePerKb = BigInteger.ZERO;
        BigInteger absoluteFee = BigInteger.ZERO;

        if (useCustomFee){
            //User customized fee. if absolute fee used, we need to add to spendAmount for coin selection
//            Log.v("vos","---------------Use Custom Absolute Fee-----------------");
            absoluteFee = getCustomFee();
            spendAmount = spendAmount.add(absoluteFee);

        } else if(suggestedFeeBundle != null) {
            //Dynamic fee fetching successful
//            Log.v("vos","---------------Calculate Dynamic Fee from per kb-----------------");
            feePerKb = suggestedFeeBundle.defaultFeePerKb;

        }else{
            //If dynamic failed we'll use default. if absolute fee used, we need to add to spendAmount for coin selection
//            Log.v("vos","---------------Use default Absolute -----------------");
            absoluteFee = FeeUtil.AVERAGE_FEE;
            spendAmount = spendAmount.add(absoluteFee);
        }

        UnspentOutputsBundle unspentsBundle = null;
        if(balanceAvailable > 0) {

            try {
                //Temporary fix
                //Note! prepareSend() sets a global var, so unspents that will be used for tx needs to be called last.
                //This should be called just before tx confirmation but not possible with prepareSend()'s current state - TODO prepareSend() needs refactor
                setEstimatedBlocks(fromAddress, unspentApiString);

                unspentsBundle = SendFactory.getInstance(getActivity()).prepareSend(fromAddress, spendAmount, feePerKb, unspentApiString);
                if(unspentsBundle != null) {
                    if (feePerKb.compareTo(BigInteger.ZERO) != 0) {
                        //An absolute fee was calculated fromAddresses fee per kb, and was set in prepareSend()
                        absoluteFeeSuggested = unspentsBundle.getRecommendedFee();
                        absoluteFeeUsed = absoluteFeeSuggested;
                    } else {
                        //An absolute fee was specified - use it
                        absoluteFeeUsed = absoluteFee;
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
//                ToastCustom.makeText(getActivity(), e.getMessage(), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
            }
        }

        return unspentsBundle;
    }

    private void setEstimatedBlocks(final String fromAddress, final String unspentApiString) throws Exception {
        if(suggestedFeeBundle != null){

            absoluteFeeSuggestedEstimates = new BigInteger[suggestedFeeBundle.estimateList.size()];

            for(int i = 0; i < absoluteFeeSuggestedEstimates.length; i++){

                BigInteger feePerKb = suggestedFeeBundle.estimateList.get(i).fee;

                UnspentOutputsBundle unspentsBundleFirstBlock = SendFactory.getInstance(getActivity()).prepareSend(fromAddress, getSpendAmount(), feePerKb, unspentApiString);
                if(unspentsBundleFirstBlock != null){
                    absoluteFeeSuggestedEstimates[i] = unspentsBundleFirstBlock.getRecommendedFee();
                }
            }
        }
    }

    private long getSweepAmount(){
        long sweepAmount = balanceAvailable;
        if(unspentsCoinsBundle != null) {
            sweepAmount = unspentsCoinsBundle.getSweepAmount().longValue();
        }

        //Check customized fee
        if(!binding.customFee.getText().toString().isEmpty())
            sweepAmount -= getCustomFee().longValue();

        return sweepAmount;
    }

    private void displaySweepAmount(){

        final double sweepBalance = Math.max(((double) getSweepAmount()) / 1e8, 0.0);

        if(getActivity() != null && !getActivity().isFinishing()) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    binding.progressBarMaxAvailable.setVisibility(View.GONE);
                    binding.max.setVisibility(View.VISIBLE);
                    btSend.setEnabled(true);
                    binding.max.setText(getResources().getString(R.string.max_available) + " " + monetaryUtil.getBTCFormat().format(monetaryUtil.getDenominatedAmount(sweepBalance)) + " " + strBTC);
                }
            });
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        menu.findItem(R.id.action_qr).setVisible(true);
        menu.findItem(R.id.action_share_receive).setVisible(false);

        btSend = menu.findItem(R.id.action_send);
        btSend.setVisible(true);

        btSend.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                sendClicked();

                return false;
            }
        });
    }

    private void sendClicked() {

        //Hide keyboard
        customKeypad.setNumpadVisibility(View.GONE);
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager inputManager = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }

        //Check connectivity before we spend
        if(ConnectivityStatus.hasConnectivity(getActivity())){

            //Get spend details from UI
            final PendingSpend pendingSpend = getPendingSpendFromUIFields();
            if(pendingSpend != null){
                if(isValidSpend(pendingSpend)){

                    //Currently only v2 has watch-only
                    if(!pendingSpend.isHD && pendingSpend.fromLegacyAddress.isWatchOnly()){
                        promptWatchOnlySpend(pendingSpend);
                    }else{
                        checkDoubleEncrypt(pendingSpend);
                    }
                }
            }

        }else{
            payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
            ToastCustom.makeText(getActivity(), getString(R.string.check_connectivity_exit), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
        }
    }

    private void promptWatchOnlySpend(final PendingSpend pendingSpend){

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.privx_required)
                .setMessage(String.format(getString(R.string.watch_only_spend_instructionss), pendingSpend.fromLegacyAddress.getAddress()))
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_continue, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        watchOnlyPendingSpend = pendingSpend;

                        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED  && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            PermissionUtil.requestCameraPermissionFromFragment(binding.mainLayout, getActivity(), MainActivity.currentFragment);
                        }else{
                            startScanActivity();
                        }

                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void startScanActivity(){
        if (!new AppUtil(getActivity()).isCameraOpen()) {
            Intent intent = new Intent(getActivity(), CaptureActivity.class);
            startActivityForResult(intent, SCAN_PRIVX);
        } else {
            ToastCustom.makeText(getActivity(), getString(R.string.camera_unavailable), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == MainActivity.SCAN_URI && resultCode == Activity.RESULT_OK
                && data != null && data.getStringExtra(CaptureActivity.SCAN_RESULT) != null) {
            String strResult = data.getStringExtra(CaptureActivity.SCAN_RESULT);
            handleIncomingQRScan(strResult);

        }else if(requestCode == SCAN_PRIVX && resultCode == Activity.RESULT_OK){
            final String scanData = data.getStringExtra(CaptureActivity.SCAN_RESULT);

            try {
                final String format = PrivateKeyFactory.getInstance().getFormat(scanData);
                if (format != null) {

                    if (payloadManager.getPayload().isDoubleEncrypted()) {
                        promptForSecondPassword(new OpSimpleCallback() {
                            @Override
                            public void onSuccess(String string) {
                                if (!format.equals(PrivateKeyFactory.BIP38)) {
                                    spendFromWatchOnlyNonBIP38(format, scanData);
                                } else {
                                    spendFromWatchOnlyBIP38(scanData);
                                }
                            }

                            @Override
                            public void onFail() {
                                ToastCustom.makeText(getActivity(), getString(R.string.double_encryption_password_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
                            }
                        });
                    }else{
                        if (!format.equals(PrivateKeyFactory.BIP38)) {
                            spendFromWatchOnlyNonBIP38(format, scanData);
                        } else {
                            spendFromWatchOnlyBIP38(scanData);
                        }
                    }

                } else {
                    ToastCustom.makeText(getActivity(), getString(R.string.privkey_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void promptForSecondPassword(final OpSimpleCallback callback){

        final EditText double_encrypt_password = new EditText(getActivity());
        double_encrypt_password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.app_name)
                .setMessage(R.string.enter_double_encryption_pw)
                .setView(double_encrypt_password)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        String secondPassword = double_encrypt_password.getText().toString();

                        if (secondPassword != null &&
                                secondPassword.length() > 0 &&
                                DoubleEncryptionFactory.getInstance().validateSecondPassword(
                                        payloadManager.getPayload().getDoublePasswordHash(),
                                        payloadManager.getPayload().getSharedKey(),
                                        new CharSequenceX(secondPassword), payloadManager.getPayload().getOptions().getIterations()) &&
                                !StringUtils.isEmpty(secondPassword)) {

                            payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(secondPassword));
                            callback.onSuccess(secondPassword);

                        } else {
                            callback.onFail();
                        }

                    }
                }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                ;
            }
        }).show();
    }

    private void spendFromWatchOnlyNonBIP38(final String format, final String scanData){
        ECKey key = null;

        try {
            key = PrivateKeyFactory.getInstance().getKey(format, scanData);
        } catch (Exception e) {
            ToastCustom.makeText(getActivity(), getString(R.string.no_private_key), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            e.printStackTrace();
            return;
        }

        if (key != null && key.hasPrivKey() && watchOnlyPendingSpend.fromLegacyAddress.getAddress().equals(key.toAddress(MainNetParams.get()).toString())) {

            //Create copy, otherwise pass by ref will override
            LegacyAddress tempLegacyAddress = new LegacyAddress();
            if (payloadManager.getPayload().isDoubleEncrypted()) {
                String encryptedKey = new String(Base58.encode(key.getPrivKeyBytes()));
                String encrypted2 = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey, payloadManager.getPayload().getSharedKey(), payloadManager.getTempDoubleEncryptPassword().toString(), payloadManager.getPayload().getOptions().getIterations());
                tempLegacyAddress.setEncryptedKey(encrypted2);
            }else{
                tempLegacyAddress.setEncryptedKey(key.getPrivKeyBytes());
            }
            tempLegacyAddress.setAddress(key.toAddress(MainNetParams.get()).toString());
            tempLegacyAddress.setLabel(watchOnlyPendingSpend.fromLegacyAddress.getLabel());

            watchOnlyPendingSpend.fromLegacyAddress = tempLegacyAddress;

            confirmPayment(watchOnlyPendingSpend);
        } else {
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_private_key), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }
    }

    private void spendFromWatchOnlyBIP38(final String scanData){

        final EditText password = new EditText(getActivity());
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.app_name)
                .setMessage(R.string.bip38_password_entry)
                .setView(password)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        final String pw = password.getText().toString();

                        if (progress != null && progress.isShowing()) {
                            progress.dismiss();
                            progress = null;
                        }
                        progress = new ProgressDialog(getActivity());
                        progress.setTitle(R.string.app_name);
                        progress.setMessage(getActivity().getResources().getString(R.string.please_wait));
                        progress.show();

                        new Thread(new Runnable() {
                            @Override
                            public void run() {

                                Looper.prepare();

                                try {
                                    BIP38PrivateKey bip38 = new BIP38PrivateKey(MainNetParams.get(), scanData);
                                    final ECKey key = bip38.decrypt(pw);

                                    if (key != null && key.hasPrivKey()) {

                                        if(watchOnlyPendingSpend.fromLegacyAddress.getAddress().equals(key.toAddress(MainNetParams.get()).toString())){
                                            //Create copy, otherwise pass by ref will override
                                            LegacyAddress tempLegacyAddress = new LegacyAddress();
                                            if (payloadManager.getPayload().isDoubleEncrypted()) {
                                                String encryptedKey = new String(Base58.encode(key.getPrivKeyBytes()));
                                                String encrypted2 = DoubleEncryptionFactory.getInstance().encrypt(encryptedKey, payloadManager.getPayload().getSharedKey(), payloadManager.getTempDoubleEncryptPassword().toString(), payloadManager.getPayload().getOptions().getIterations());
                                                tempLegacyAddress.setEncryptedKey(encrypted2);
                                            }else{
                                                tempLegacyAddress.setEncryptedKey(key.getPrivKeyBytes());
                                            }
                                            tempLegacyAddress.setAddress(key.toAddress(MainNetParams.get()).toString());
                                            tempLegacyAddress.setLabel(watchOnlyPendingSpend.fromLegacyAddress.getLabel());

                                            watchOnlyPendingSpend.fromLegacyAddress = tempLegacyAddress;

                                            confirmPayment(watchOnlyPendingSpend);
                                        }else{
                                            ToastCustom.makeText(getActivity(), getString(R.string.invalid_private_key), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                        }

                                    } else {
                                        ToastCustom.makeText(getActivity(), getString(R.string.bip38_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                    }

                                } catch (Exception e) {
                                    ToastCustom.makeText(getActivity(), getString(R.string.bip38_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                } finally {
                                    if (progress != null && progress.isShowing()) {
                                        progress.dismiss();
                                        progress = null;
                                    }
                                }

                                Looper.loop();

                            }
                        }).start();

                    }
                }).setNegativeButton(R.string.cancel, null).show();

    }

    private void checkDoubleEncrypt(final PendingSpend pendingSpend){

        if (!payloadManager.getPayload().isDoubleEncrypted() || DoubleEncryptionFactory.getInstance().isActivated()) {
            confirmPayment(pendingSpend);
        } else {
            alertDoubleEncrypted(pendingSpend);
        }
    }

    private void alertDoubleEncrypted(final PendingSpend pendingSpend){
        final EditText password = new EditText(getActivity());
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.app_name)
                .setMessage(R.string.enter_double_encryption_pw)
                .setView(password)
                .setCancelable(false)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        final String pw = password.getText().toString();
                        if(payloadManager.setDoubleEncryptPassword(pw, pendingSpend.isHD)){
                            confirmPayment(pendingSpend);
                        }else{
                            ToastCustom.makeText(getActivity(), getString(R.string.double_encryption_password_error), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                        }
                    }
                }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                ;
            }
        }).show();
    }

    private void confirmPayment(final PendingSpend pendingSpend) {

        new Thread(new Runnable() {
            @Override
            public void run() {

                Looper.prepare();

                AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
                LayoutInflater inflater = getActivity().getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.fragment_send_confirm, null);
                dialogBuilder.setView(dialogView);

                final AlertDialog alertDialog = dialogBuilder.create();
                alertDialog.setCanceledOnTouchOutside(false);

                TextView confirmFrom = (TextView) dialogView.findViewById(R.id.confirm_from_label);

                if(pendingSpend.isHD) {
                    confirmFrom.setText(pendingSpend.fromAccount.getLabel());
                }else{
                    confirmFrom.setText(pendingSpend.fromLegacyAddress.getLabel());
                }

                TextView confirmDestination = (TextView) dialogView.findViewById(R.id.confirm_to_label);
                confirmDestination.setText(pendingSpend.destination);

                TextView tvAmountBtcUnit = (TextView) dialogView.findViewById(R.id.confirm_amount_btc_unit);
                tvAmountBtcUnit.setText(strBTC);
                TextView tvAmountFiatUnit = (TextView) dialogView.findViewById(R.id.confirm_amount_fiat_unit);
                tvAmountFiatUnit.setText(strFiat);

                //BTC Amount
                TextView tvAmountBtc = (TextView) dialogView.findViewById(R.id.confirm_amount_btc);
                tvAmountBtc.setText(monetaryUtil.getDisplayAmount(pendingSpend.bigIntAmount.longValue()));

                //BTC Fee
                final TextView tvFeeBtc = (TextView) dialogView.findViewById(R.id.confirm_fee_btc);
                tvFeeBtc.setText(monetaryUtil.getDisplayAmount(pendingSpend.bigIntFee.longValue()));

                TextView tvTotlaBtc = (TextView) dialogView.findViewById(R.id.confirm_total_btc);
                BigInteger totalBtc = (pendingSpend.bigIntAmount.add(pendingSpend.bigIntFee));
                tvTotlaBtc.setText(monetaryUtil.getDisplayAmount(totalBtc.longValue()));

                //Fiat Amount
                btc_fx = ExchangeRateFactory.getInstance(getActivity()).getLastPrice(strFiat);
                String amountFiat = (monetaryUtil.getFiatFormat(strFiat).format(btc_fx * (pendingSpend.bigIntAmount.doubleValue() / 1e8)));
                TextView tvAmountFiat = (TextView) dialogView.findViewById(R.id.confirm_amount_fiat);
                tvAmountFiat.setText(amountFiat);

                //Fiat Fee
                TextView tvFeeFiat = (TextView) dialogView.findViewById(R.id.confirm_fee_fiat);
                String feeFiat = (monetaryUtil.getFiatFormat(strFiat).format(btc_fx * (pendingSpend.bigIntFee.doubleValue() / 1e8)));
                tvFeeFiat.setText(feeFiat);

                ImageView ivFeeInfo = (ImageView) dialogView.findViewById(R.id.iv_fee_info);
                ivFeeInfo.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new AlertDialog.Builder(getActivity())
                                .setTitle(R.string.transaction_fee)
                                .setMessage(getText(R.string.recommended_fee).toString()+"\n\n"+getText(R.string.transaction_surge).toString())
                                .setPositiveButton(R.string.ok, null).show();
                    }
                });

                if(suggestedFeeBundle != null && suggestedFeeBundle.isSurge){
                    tvFeeBtc.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                    tvFeeFiat.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                    ivFeeInfo.setVisibility(View.VISIBLE);
                }

                TextView tvTotalFiat = (TextView) dialogView.findViewById(R.id.confirm_total_fiat);
                BigInteger totalFiat = (pendingSpend.bigIntAmount.add(pendingSpend.bigIntFee));
                String totalFiatS = (monetaryUtil.getFiatFormat(strFiat).format(btc_fx * (totalFiat.doubleValue() / 1e8)));
                tvTotalFiat.setText(totalFiatS);

                TextView tvCustomizeFee = (TextView) dialogView.findViewById(R.id.tv_customize_fee);
                tvCustomizeFee.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (alertDialog != null && alertDialog.isShowing()) {
                            alertDialog.cancel();
                        }

                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                binding.customFeeContainer.setVisibility(View.VISIBLE);

                                String fee = monetaryUtil.getBTCFormat().format(monetaryUtil.getDenominatedAmount(absoluteFeeSuggested.doubleValue() / 1e8));

                                binding.customFee.setText(fee);
                                binding.customFee.setHint(fee);
                                binding.customFee.requestFocus();
                                binding.customFee.setSelection(binding.customFee.getText().length());
                            }
                        });

                        alertCustomSpend(absoluteFeeSuggested);

                    }
                });

                ImageView confirmCancel = (ImageView) dialogView.findViewById(R.id.confirm_cancel);
                confirmCancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (alertDialog != null && alertDialog.isShowing()) {
                            alertDialog.cancel();
                        }
                    }
                });

                TextView confirmSend = (TextView) dialogView.findViewById(R.id.confirm_send);
                confirmSend.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {

                        if (!spendInProgress) {
                            spendInProgress = true;

                            if (progress != null && progress.isShowing()) {
                                progress.dismiss();
                                progress = null;
                            }
                            progress = new ProgressDialog(getActivity());
                            progress.setCancelable(false);
                            progress.setTitle(R.string.app_name);
                            progress.setMessage(getString(R.string.sending));
                            progress.show();

                            context = getActivity();

                            if (unspentsCoinsBundle != null) {
                                executeSend(pendingSpend, unspentsCoinsBundle, alertDialog);
                            } else {

                                payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
                                ToastCustom.makeText(context.getApplicationContext(), getResources().getString(R.string.transaction_failed), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                                closeDialog(alertDialog, false);
                            }

                            spendInProgress = false;
                        }
                    }
                });

                alertDialog.show();

                //If custom fee set to more than 1.5 times the necessary fee
                if(suggestedFeeBundle !=null && absoluteFeeSuggestedEstimates != null){

                    if (absoluteFeeSuggestedEstimates != null && absoluteFeeUsed.compareTo(absoluteFeeSuggestedEstimates[0]) > 0) {
                        promptAlterFee(absoluteFeeUsed, absoluteFeeSuggestedEstimates[0], R.string.high_fee_not_necessary_info, R.string.lower_fee, R.string.keep_high_fee, alertDialog);
                    }

                    if (absoluteFeeSuggestedEstimates != null && absoluteFeeUsed.compareTo(absoluteFeeSuggestedEstimates[5]) < 0) {
                        promptAlterFee(absoluteFeeUsed, absoluteFeeSuggestedEstimates[5], R.string.low_fee_suggestion, R.string.raise_fee, R.string.keep_low_fee, alertDialog);
                    }
                }

                Looper.loop();

            }
        }).start();
    }

    private void promptAlterFee(BigInteger customFee, final BigInteger absoluteFeeSuggested, int body, int positiveAction, int negativeAction, final AlertDialog confirmDialog) {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.alert_generic_warning, null);
        dialogBuilder.setView(dialogView);

        final AlertDialog alertDialogFee = dialogBuilder.create();
        alertDialogFee.setCanceledOnTouchOutside(false);

        TextView tvMessageBody = (TextView) dialogView.findViewById(R.id.tv_body);

        String message = String.format(getString(body),
                monetaryUtil.getDisplayAmount(customFee.longValue()) + " " + strBTC,
                monetaryUtil.getDisplayAmount(absoluteFeeSuggested.longValue()) + " " + strBTC);
        tvMessageBody.setText(message);

        ImageView confirmBack = (ImageView) dialogView.findViewById(R.id.confirm_cancel);
        confirmBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (alertDialogFee.isShowing()) alertDialogFee.cancel();
            }
        });

        TextView confirmKeep = (TextView) dialogView.findViewById(R.id.confirm_keep);
        confirmKeep.setText(getResources().getString(negativeAction));
        confirmKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (alertDialogFee.isShowing()) alertDialogFee.cancel();
            }
        });

        TextView confirmChange = (TextView) dialogView.findViewById(R.id.confirm_change);
        confirmChange.setText(getResources().getString(positiveAction));
        confirmChange.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (alertDialogFee.isShowing()) alertDialogFee.cancel();
                if (confirmDialog.isShowing()) confirmDialog.cancel();

                PendingSpend pendingSpend = getPendingSpendFromUIFields();
                pendingSpend.bigIntFee = absoluteFeeSuggested;
                absoluteFeeUsed = absoluteFeeSuggested;
                confirmPayment(pendingSpend);
            }
        });
        alertDialogFee.show();
    }

    private void alertCustomSpend(BigInteger fee){

        String message = getResources().getString(R.string.recommended_fee)
                +"\n\n"
                +monetaryUtil.getDisplayAmount(fee.longValue())
                +" "+strBTC;

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.transaction_fee)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null).show();
    }

    private void executeSend(final PendingSpend pendingSpend, final UnspentOutputsBundle unspents, final AlertDialog alertDialog){

        SendFactory.getInstance(context).execSend(pendingSpend.fromXpubIndex, unspents.getOutputs(), pendingSpend.destination, pendingSpend.bigIntAmount, pendingSpend.fromLegacyAddress, pendingSpend.bigIntFee, pendingSpend.note, false, new OpCallback() {

            public void onSuccess() {
            }

            @Override
            public void onSuccess(final String hash) {

                ToastCustom.makeText(context, getResources().getString(R.string.transaction_submitted), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_OK);

                playAudio();

                if (pendingSpend.isHD) {

                    //Update v3 balance immediately after spend - until refresh from server
                    MultiAddrFactory.getInstance().setXpubBalance(MultiAddrFactory.getInstance().getXpubBalance() - (pendingSpend.bigIntAmount.longValue() + pendingSpend.bigIntFee.longValue()));
                    MultiAddrFactory.getInstance().setXpubAmount(payloadManager.getXpubFromAccountIndex(pendingSpend.fromXpubIndex), MultiAddrFactory.getInstance().getXpubAmounts().get(payloadManager.getXpubFromAccountIndex(pendingSpend.fromXpubIndex)) - (pendingSpend.bigIntAmount.longValue() + pendingSpend.bigIntFee.longValue()));

                } else {

                    //Update v2 balance immediately after spend - until refresh from server
                    MultiAddrFactory.getInstance().setLegacyBalance(MultiAddrFactory.getInstance().getLegacyBalance() - (pendingSpend.bigIntAmount.longValue() + pendingSpend.bigIntFee.longValue()));
                    //TODO - why are we not setting individual address balance as well, was this over looked?

                    //Reset double encrypt for V2
                    payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
                }

                PayloadBridge.getInstance().remoteSaveThread(new PayloadBridge.PayloadSaveListener() {
                    @Override
                    public void onSaveSuccess() {
                    }

                    @Override
                    public void onSaveFail() {
                        ToastCustom.makeText(getActivity(), getActivity().getString(R.string.remote_save_ko), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                    }
                });
                closeDialog(alertDialog, true);

                new AppRate(getActivity())
                        .setMinTransactionsUntilPrompt(3)
                        .incrementTransactionCount()
                        .init();
            }

            public void onFail(String error) {
                ToastCustom.makeText(context, error, ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_GENERAL);

                if(!ConnectivityStatus.hasConnectivity(getActivity())) {
                    ToastCustom.makeText(context, getResources().getString(R.string.transaction_queued), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_GENERAL);

                    //Initial send failed - Put send in queue for reattempt
                    String direction = MultiAddrFactory.SENT;
                    if (spDestinationSelected) direction = MultiAddrFactory.MOVED;

                    SendFactory.getInstance(context).execSend(pendingSpend.fromXpubIndex, unspents.getOutputs(), pendingSpend.destination, pendingSpend.bigIntAmount, pendingSpend.fromLegacyAddress, pendingSpend.bigIntFee, pendingSpend.note, true, this);

                    //Refresh BalanceFragment with the following - "placeholder" tx until websocket refreshes list
                    final Intent intent = new Intent(BalanceFragment.ACTION_INTENT);
                    Bundle bundle = new Bundle();
                    bundle.putLong("queued_bamount", (pendingSpend.bigIntAmount.longValue() + pendingSpend.bigIntFee.longValue()));
                    bundle.putString("queued_strNote", pendingSpend.note);
                    bundle.putString("queued_direction", direction);
                    bundle.putLong("queued_time", System.currentTimeMillis() / 1000);
                    intent.putExtras(bundle);

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Looper.prepare();
                            try {
                                Thread.sleep(1000);
                            } catch (Exception e) {
                            }//wait for broadcast receiver to register
                            LocalBroadcastManager.getInstance(context).sendBroadcastSync(intent);
                            Looper.loop();
                        }
                    }).start();
                }

                //Reset double encrypt for V2
                if (!pendingSpend.isHD) {
                    payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
                }

                closeDialog(alertDialog, true);
            }

            @Override
            public void onFailPermanently(String error) {

                //Reset double encrypt for V2
                if (!pendingSpend.isHD) {
                    payloadManager.setTempDoubleEncryptPassword(new CharSequenceX(""));
                }

                if (getActivity() != null)
                    ToastCustom.makeText(getActivity().getApplicationContext(), error, ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);

                closeDialog(alertDialog, false);
            }

        });
    }

    private void closeDialog(AlertDialog alertDialog, boolean sendSuccess) {

        if (progress != null && progress.isShowing()) {
            progress.dismiss();
            progress = null;
        }

        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.cancel();
        }

        if (sendSuccess) {
            Fragment fragment = new BalanceFragment();
            FragmentManager fragmentManager = getFragmentManager();
            fragmentManager.beginTransaction().replace(R.id.content_frame, fragment).commit();
        }
    }

    private void playAudio(){

        AudioManager audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            MediaPlayer mp;
            mp = MediaPlayer.create(context.getApplicationContext(), R.raw.alert);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                @Override
                public void onCompletion(MediaPlayer mp) {
                    mp.reset();
                    mp.release();
                }

            });
            mp.start();
        }
    }

    private PendingSpend getPendingSpendFromUIFields(){

        PendingSpend pendingSpend = new PendingSpend();

        //Check if fields are parsable
        if ((binding.amountRow.amountBtc.getText() == null || binding.amountRow.amountBtc.getText().toString().isEmpty()) ||
                (binding.amountRow.amountFiat.getText() == null || binding.amountRow.amountFiat.getText().toString().isEmpty())){
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_amount), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return null;
        }
        if(binding.destination.getText() == null || binding.destination.getText().toString().isEmpty()){
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_bitcoin_address), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return null;
        }

        //is V3?
        Object object = sendFromBiMap.inverse().get(binding.accounts.spinner.getSelectedItemPosition());

        if(object instanceof LegacyAddress){
            //V2
            pendingSpend.isHD = false;
            pendingSpend.fromLegacyAddress = (LegacyAddress) object;
            pendingSpend.fromXpubIndex = -1;//V2, xpub index must be -1
        }else{
            //V3
            int spinnerIndex = sendFromBiMap.get(object);
            int accountIndex = spinnerIndexAccountIndexMap.get(spinnerIndex);

            pendingSpend.isHD = true;
            pendingSpend.fromAccount = (Account) object;
            pendingSpend.fromXpubIndex = accountIndex;//TODO - get rid of this xpub index
            pendingSpend.fromLegacyAddress = null;//V3, legacy address must be null
        }

        //Amount to send
        pendingSpend.bigIntAmount = BigInteger.valueOf(getLongValue(binding.amountRow.amountBtc.getText().toString()));

        //Fee
        pendingSpend.bigIntFee = absoluteFeeUsed;

        //Destination
        pendingSpend.destination = binding.destination.getText().toString().trim();

        //Note
        pendingSpend.note = null;//future use

        return pendingSpend;
    }

    private boolean isValidSpend(PendingSpend pendingSpend) {

        //Validate amount
        if(!isValidAmount(pendingSpend.bigIntAmount)){
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_amount), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        //Validate sufficient funds
        if(unspentsCoinsBundle == null || unspentsCoinsBundle.getOutputs() == null){
            ToastCustom.makeText(getActivity(), getString(R.string.no_confirmed_funds), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
            return false;
        }
        long amountToSendIncludingFee = pendingSpend.bigIntAmount.longValue() + pendingSpend.bigIntFee.longValue();
        if(pendingSpend.isHD){
            String xpub = pendingSpend.fromAccount.getXpub();
            if(!hasSufficientFunds(xpub, null, amountToSendIncludingFee)){
                ToastCustom.makeText(getActivity(), getString(R.string.insufficient_funds), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                binding.customFee.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                return false;
            }else{
                binding.customFee.setTextColor(getResources().getColor(R.color.primary_text_default_material_light));
            }
        }else{
            if(!hasSufficientFunds(null, pendingSpend.fromLegacyAddress.getAddress(), amountToSendIncludingFee)){
                ToastCustom.makeText(getActivity(), getString(R.string.insufficient_funds), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
                binding.customFee.setTextColor(getResources().getColor(R.color.blockchain_send_red));
                return false;
            }else{
                binding.customFee.setTextColor(getResources().getColor(R.color.primary_text_default_material_light));
            }
        }

        //Validate addresses
        if(!FormatsUtil.getInstance().isValidBitcoinAddress(pendingSpend.destination)){
            ToastCustom.makeText(getActivity(), getString(R.string.invalid_bitcoin_address), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        //Validate send and receive not same addresses
        if((pendingSpend.isHD && getV3ReceiveAddress(pendingSpend.fromAccount).equals(pendingSpend.destination)) ||
                (!pendingSpend.isHD && pendingSpend.fromLegacyAddress.getAddress().equals(pendingSpend.destination))){
            ToastCustom.makeText(getActivity(), getString(R.string.send_to_same_address_warning), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        //Validate sufficient fee TODO - minimum on push tx = 1000 per kb, unless it has sufficient priority
        if(pendingSpend.bigIntFee.longValue() < 1000){
            ToastCustom.makeText(getActivity(), getString(R.string.insufficient_fee), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        if(unspentsCoinsBundle == null){
            ToastCustom.makeText(getActivity(), getString(R.string.no_confirmed_funds), ToastCustom.LENGTH_LONG, ToastCustom.TYPE_ERROR);
            return false;
        }

        //Warn user of unconfirmed funds - but don't block payment
        if(unspentsCoinsBundle.getNotice() != null){
            ToastCustom.makeText(getActivity(), unspentsCoinsBundle.getNotice(), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
        }

        if(unspentsCoinsBundle.getOutputs().size() == 0){
            ToastCustom.makeText(getActivity(), getString(R.string.insufficient_funds), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        //Check after user edits fee (fee could bring balance into negative)
        long balanceAfterFee = (unspentsCoinsBundle.getTotalAmount().longValue() - absoluteFeeUsed.longValue());
        if(balanceAfterFee < 0){
            ToastCustom.makeText(getActivity(), getString(R.string.insufficient_funds), ToastCustom.LENGTH_SHORT, ToastCustom.TYPE_ERROR);
            return false;
        }

        return true;
    }

    private long getLongValue(String amountToSendString){

        String amountToSend = amountToSendString.replace(" ","").replace(defaultSeparator, ".");

        double amount;
        try{
            amount = Double.parseDouble(amountToSend);
        }catch (NumberFormatException nfe){
            amount = 0.0;
        }

        if(getActivity() != null && getActivity().isFinishing())//activity has been destroyed
            return 0l;

        return (BigDecimal.valueOf(monetaryUtil.getUndenominatedAmount(amount)).multiply(BigDecimal.valueOf(100000000)).longValue());
    }

    private boolean isValidAmount(BigInteger bAmount){

        //Test that amount is more than dust
        if (bAmount.compareTo(SendCoins.bDust) == -1) {
            return false;
        }

        //Test that amount does not exceed btc limit
        if (bAmount.compareTo(BigInteger.valueOf(2100000000000000L)) == 1) {
            binding.amountRow.amountBtc.setText("0");
            return false;
        }

        //Test that amount is not zero
        if (!(bAmount.compareTo(BigInteger.ZERO) >= 0)) {
            return false;
        }

        return true;
    }

    private boolean hasSufficientFunds(String xpub, String legacyAddress, long amountToSendIncludingFee){

        if (xpub != null) {
            //HD
            if (xpub != null && MultiAddrFactory.getInstance().getXpubAmounts().containsKey(xpub)) {
                long xpubBalance = MultiAddrFactory.getInstance().getXpubAmounts().get(xpub);
                if (amountToSendIncludingFee > xpubBalance) {
                    return false;
                }
            }
        } else {
            //Legacy
            long legacyAddressBalance = MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress);
            if (amountToSendIncludingFee > legacyAddressBalance) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void onKeypadClose() {
        customKeypad.setNumpadVisibility(View.GONE);
    }

    class SendFromAdapter extends ArrayAdapter<String> {

        public SendFromAdapter(Context context, int textViewResourceId, List<String> accounts) {
            super(context, textViewResourceId, accounts);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getCustomView(position, convertView, parent, true);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return getCustomView(position, convertView, parent, false);
        }

        public View getCustomView(int position, View convertView, ViewGroup parent, boolean isDropdown) {

            LayoutInflater inflater = getActivity().getLayoutInflater();

            int layoutRes = R.layout.spinner_item;
            if (isDropdown) layoutRes = R.layout.fragment_send_account_row_dropdown;

            View row = inflater.inflate(layoutRes, parent, false);

            TextView label = null;
            TextView balance = null;
            if (isDropdown) {
                label = (TextView) row.findViewById(R.id.receive_account_label);
                balance = (TextView) row.findViewById(R.id.receive_account_balance);
            } else
                label = (TextView) row.findViewById(R.id.text);

            String labelText = "";
            long amount = 0L;

            Object object = sendFromBiMap.inverse().get(position);

            if(object instanceof LegacyAddress){
                //V2
                LegacyAddress legacyAddress = ((LegacyAddress) object);
                if(legacyAddress.isWatchOnly())labelText = getActivity().getString(R.string.watch_only_label);
                if (legacyAddress.getLabel() != null && legacyAddress.getLabel().length() > 0) {
                    labelText += legacyAddress.getLabel();
                } else {
                    labelText += legacyAddress.getAddress();
                }

                amount = MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress.getAddress());

            }else{
                //V3
                Account account = ((Account)object);
                labelText = account.getLabel();

                if (MultiAddrFactory.getInstance().getXpubAmounts().containsKey(account.getXpub())) {
                    amount = MultiAddrFactory.getInstance().getXpubAmounts().get(account.getXpub());
                }
            }

            if (isDropdown) {
                balance.setText("(" + monetaryUtil.getDisplayAmount(amount) + " " + strBTC + ")");
                label.setText(labelText);
            } else
                label.setText(labelText);

            return row;
        }
    }

    class ReceiveToAdapter extends ArrayAdapter<String> {

        public ReceiveToAdapter(Context context, int resource, List<String> items) {
            super(context, resource, items);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getCustomView(position, convertView, parent, true);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return getCustomView(position, convertView, parent, false);
        }

        public View getCustomView(int position, View convertView, ViewGroup parent, boolean isDropdown) {

            LayoutInflater inflater = getActivity().getLayoutInflater();

            View row = null;
            if (isDropdown) {
                int layoutRes = R.layout.fragment_send_account_row_dropdown;
                row = inflater.inflate(layoutRes, parent, false);
                TextView label = (TextView) row.findViewById(R.id.receive_account_label);
                label.setText(receiveToList.get(position));
            } else {
                int layoutRes = R.layout.spinner_item;
                row = inflater.inflate(layoutRes, parent, false);
                TextView label = (TextView) row.findViewById(R.id.text);
                label.setText("");
            }

            return row;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtil.PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanActivity();
            } else {
                // Permission request was denied.
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}