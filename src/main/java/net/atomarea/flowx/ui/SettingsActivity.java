package net.atomarea.flowx.ui;

import android.app.AlertDialog;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.widget.Toast;

import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.duenndns.ssl.MemorizingTrustManager;
import net.atomarea.flowx.Config;
import net.atomarea.flowx.R;
import net.atomarea.flowx.entities.Account;
import net.atomarea.flowx.services.ExportLogsService;
import net.atomarea.flowx.xmpp.XmppConnection;

public class SettingsActivity extends XmppActivity implements
		OnSharedPreferenceChangeListener {

	public static final int REQUEST_WRITE_LOGS = 0xbf8701;
	private SettingsFragment mSettingsFragment;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FragmentManager fm = getFragmentManager();
		mSettingsFragment = (SettingsFragment) fm.findFragmentById(android.R.id.content);
		if (mSettingsFragment == null || !mSettingsFragment.getClass().equals(SettingsFragment.class)) {
			mSettingsFragment = new SettingsFragment();
			fm.beginTransaction().replace(android.R.id.content, mSettingsFragment).commit();
		}
	}

	@Override
	void onBackendConnected() {

	}

	@Override
	public void onStart() {
		super.onStart();
		PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
		ListPreference resources = (ListPreference) mSettingsFragment.findPreference("resource");
		if (resources != null) {
			ArrayList<CharSequence> entries = new ArrayList<>(Arrays.asList(resources.getEntries()));
			if (!entries.contains(Build.MODEL)) {
				entries.add(0, Build.MODEL);
				resources.setEntries(entries.toArray(new CharSequence[entries.size()]));
				resources.setEntryValues(entries.toArray(new CharSequence[entries.size()]));
			}
		}

		if (Config.FORCE_ORBOT) {
			PreferenceCategory connectionOptions = (PreferenceCategory) mSettingsFragment.findPreference("connection_options");
			PreferenceScreen expert = (PreferenceScreen) mSettingsFragment.findPreference("expert");
			if (connectionOptions != null) {
				expert.removePreference(connectionOptions);
			}
		}

		final Preference exportLogsPreference = mSettingsFragment.findPreference("export_logs");
		exportLogsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				hasStoragePermission(REQUEST_WRITE_LOGS);
				return true;
			}
		});
	}

	@Override
	public void onStop() {
		super.onStop();
		PreferenceManager.getDefaultSharedPreferences(this)
				.unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences preferences, String name) {
		final List<String> resendPresence = Arrays.asList(
				"confirm_messages",
				"xa_on_silent_mode",
				"away_when_screen_off",
				"allow_message_correction",
				"treat_vibrate_as_silent",
				"manually_change_presence");
		if (name.equals("resource")) {
			String resource = preferences.getString("resource", "mobile")
					.toLowerCase(Locale.US);
			if (xmppConnectionServiceBound) {
				for (Account account : xmppConnectionService.getAccounts()) {
					if (account.setResource(resource)) {
						if (!account.isOptionSet(Account.OPTION_DISABLED)) {
							XmppConnection connection = account.getXmppConnection();
							if (connection != null) {
								connection.resetStreamId();
							}
							xmppConnectionService.reconnectAccountInBackground(account);
						}
					}
				}
			}
		} else if (name.equals("keep_foreground_service")) {
			xmppConnectionService.toggleForegroundService();
		} else if (resendPresence.contains(name)) {
			if (xmppConnectionServiceBound) {
				if (name.equals("away_when_screen_off")
						|| name.equals("manually_change_presence")) {
					xmppConnectionService.toggleScreenEventReceiver();
				}
				if (name.equals("manually_change_presence") && !noAccountUsesPgp()) {
					Toast.makeText(this, R.string.republish_pgp_keys, Toast.LENGTH_LONG).show();
				}
				xmppConnectionService.refreshAllPresences();
			}
		} else if (name.equals("dont_trust_system_cas")) {
			xmppConnectionService.updateMemorizingTrustmanager();
			reconnectAccounts();
		} else if (name.equals("use_tor")) {
			reconnectAccounts();
		}

	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (grantResults.length > 0)
			if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (requestCode == REQUEST_WRITE_LOGS) {
					getApplicationContext().startService(new Intent(getApplicationContext(), ExportLogsService.class));
				}
			} else {
				Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
			}
	}

	private void displayToast(final String msg) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
			}
		});
	}

	private void reconnectAccounts() {
		for (Account account : xmppConnectionService.getAccounts()) {
			if (!account.isOptionSet(Account.OPTION_DISABLED)) {
				xmppConnectionService.reconnectAccountInBackground(account);
			}
		}
	}

	public void refreshUiReal() {
		//nothing to do. This Activity doesn't implement any listeners
	}

}