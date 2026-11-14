package com.desertkun.brainout.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.desertkun.brainout.*;
import com.desertkun.brainout.client.settings.ClientSettings;

public class AndroidLauncher extends AndroidApplication
{
	static
	{
		// Version.ENV_SERVICE resolves the backend URL from this system property
		// inside a static initializer, so it has to be set before any core class
		// is touched — hence a static block rather than onCreate().
		if (BuildConfig.ONLINE_ENABLED
				&& BuildConfig.ENV_SERVICE != null && !BuildConfig.ENV_SERVICE.isEmpty())
		{
			System.setProperty("brainout.env_service", BuildConfig.ENV_SERVICE);
		}
	}

	@Override
	protected void onCreate (Bundle savedInstanceState)
	{
		CrashLog.install(this);

		super.onCreate(savedInstanceState);

		// If the app was opened via a brainout:// deep link, hand the encoded
		// location to the client before the engine starts. IntroMenu picks up
		// ConnectToLocation and connects directly (HashedUrl handles the
		// brainout:// prefix and the base64 host;tcp;udp;http payload).
		handleConnectIntent(getIntent());

		AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();

        AndroidEnvironment environment = new AndroidEnvironment(getContext());

        ClientSettings clientSettings = new AndroidSettings(environment, getContext());
        clientSettings.init();

        BrainOutAndroid app = BrainOutAndroid.initAndroidInstance(environment, clientSettings);

        // Online is opt-in at build time (-PbrainoutOnline=true). It works on
        // Android only because :anthill-android relocates org.apache.http away
        // from Android's stripped bootclasspath copy — see docs/AndroidOnline.md.
        // The default (offline) build talks to self-hosted servers directly via
        // direct connect / brainout:// deep link and short-circuits CSOnlineInit.
        // Bundled unsigned data packages are always accepted (there is no
        // --unsafe CLI on Android). Applies to release builds too.
        app.offline = !BuildConfig.ONLINE_ENABLED;
        app.unsafe = true;

		initialize(app, config);

		CrashLog.offerLastReport(this);

		// Debug builds only: `adb shell run-as <package> touch files/crash-test`
		// before launch crashes the render thread a few seconds in, to check the
		// crash report.
		if (BuildConfig.DEBUG && new java.io.File(getFilesDir(), "crash-test").delete())
		{
			new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
				BrainOutAndroid.crashTest = true, 15000);
		}
	}

	@Override
	protected void onPause()
	{
		super.onPause();

		// leaving for another app: keep the connection alive for a while
		if (!isFinishing())
			KeepConnectionService.start(this);
	}

	@Override
	protected void onResume()
	{
		KeepConnectionService.stop(this);

		super.onResume();
	}

	@Override
	protected void onDestroy()
	{
		KeepConnectionService.stop(this);

		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		// singleTask: a brainout:// link opened while the game runs lands here
		super.onNewIntent(intent);
		setIntent(intent);
		handleConnectIntent(intent);
	}

	private void handleConnectIntent(Intent intent)
	{
		if (intent == null)
			return;

		Uri data = intent.getData();
		if (data != null && "brainout".equals(data.getScheme()))
		{
			BrainOutClient.ConnectToLocation = data.toString();
		}
	}
}
