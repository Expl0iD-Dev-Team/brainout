package com.desertkun.brainout.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Android freezes a backgrounded app within seconds, so its network threads
 * stop sending keep-alives and the game server drops the player after about
 * half a minute. While the game is in the background this foreground service
 * keeps the process running, so switching to a messenger for a moment does
 * not cost the match. It gives up after a few minutes; the game then
 * reconnects (or offers to) when it is opened again.
 */
public class KeepConnectionService extends Service
{
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 1;
    private static final long KEEP_MS = 5 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable giveUp = this::stopSelf;

    /** Called when the game goes to the background. */
    public static void start(Context context)
    {
        try
        {
            Intent intent = new Intent(context, KeepConnectionService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent);
            else
                context.startService(intent);
        }
        catch (RuntimeException e)
        {
            // the system may refuse a foreground service; the game then just
            // reconnects when it comes back, as it did before
            e.printStackTrace();
        }
    }

    /** Called when the game is back in front. */
    public static void stop(Context context)
    {
        context.stopService(new Intent(context, KeepConnectionService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
        else
        {
            startForeground(NOTIFICATION_ID, notification);
        }

        handler.removeCallbacks(giveUp);
        handler.postDelayed(giveUp, KEEP_MS);

        return START_NOT_STICKY;
    }

    private Notification buildNotification()
    {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.keep_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);

            builder = new Notification.Builder(this, CHANNEL);
        }
        else
        {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }

        Intent open = new Intent(this, AndroidLauncher.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keep_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy()
    {
        handler.removeCallbacks(giveUp);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }
}
