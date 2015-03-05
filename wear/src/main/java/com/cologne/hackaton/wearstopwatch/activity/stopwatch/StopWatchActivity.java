package com.cologne.hackaton.wearstopwatch.activity.stopwatch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.cologne.hackaton.wearstopwatch.R;
import com.cologne.hackaton.wearstopwatch.activity.event.AttachListenersEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.CreateLapEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.LapUpdateEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.PauseStopwatchEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.RequestStatusEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.ResetStopWatchEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.SaveLapsEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.StartStopwatchEvent;
import com.cologne.hackaton.wearstopwatch.activity.event.StatusResponseEvent;
import com.gabriel.android.timelib.model.Lap;
import com.gabriel.android.timelib.utils.StringUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.greenrobot.event.EventBus;

/**
 * @author Dmytro Khmelenko, Gabriel Schlatter
 */
public class StopWatchActivity extends Activity implements DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {

private static final String START_STOPWATCH_PATH = "start/stopwatch";
private static final String SAVE_LAP_MESSAGE = "save/laptime";
private static final String RESET_STOPWATCH = "reset/watch";
private static final String PAUSE_WATCH = "pause/watch";

  private EventBus eventBus = EventBus.getDefault();

  // Views
  private TextView mIimeView;
  private ImageButton mBtnStart;
  private ImageButton mBtnStop;
  private ImageButton mBtnLock;

  // Statuses
  private boolean mLocked;
  private boolean mIsRunning;

  // Laps
  private List<Lap> mLaps = new ArrayList<>();
  private LapAdapter mLapsAdapter;
  private ListView mLapsListView;

    // Wear Api
    private GoogleApiClient mGoogleApiClient;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_stopwatch);

      // Create a GoogleApiClient instance
      mGoogleApiClient = new GoogleApiClient.Builder(this).addApi(Wearable.API)
              .addConnectionCallbacks(this).addOnConnectionFailedListener(this)
              .build();

    eventBus.register(this);

    final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
    stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
      @Override
      public void onLayoutInflated(WatchViewStub stub) {
        initializeViews();

        if (!isMyServiceRunning(StopWatchService.class)) {
          Intent i = new Intent(StopWatchActivity.this, StopWatchService.class);
          startService(i);
        }
        else {
          // Attach to existing Service
          Log.d(getClass().getSimpleName(), "Attach Listeners");
          eventBus.post(new AttachListenersEvent(
              new StartStopwatchEvent.StopWatchTickCallback() {
                @Override
                public void onTick(long elapsedTime) {
                  String formattedTime = StringUtils.formatString(elapsedTime);
                  mIimeView.setText(formattedTime);
                }
              }));
          eventBus.post(new RequestStatusEvent());
        }
      }
    });
  }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mIsRunning) {
            Intent i = new Intent(this, StopWatchService.class);
            stopService(i);
        }

        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        Wearable.MessageApi.removeListener(mGoogleApiClient, this);
        mGoogleApiClient.disconnect();
    }

    private void sendMessage(final String path, final String text) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi
                        .getConnectedNodes(mGoogleApiClient).await();
                for (Node node : nodes.getNodes()) {
                    MessageApi.SendMessageResult result = Wearable.MessageApi
                            .sendMessage(mGoogleApiClient, node.getId(), path,
                                    text != null ? text.getBytes() : null).await();
                }
            }
        }).start();
    }


  private boolean isMyServiceRunning(Class<?> serviceClass) {
    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    for (ActivityManager.RunningServiceInfo service : manager
        .getRunningServices(Integer.MAX_VALUE)) {
      if (serviceClass.getName().equals(service.service.getClassName())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Initializes view controls
   */
  private void initializeViews() {
    mIimeView = (TextView) findViewById(R.id.tv_time);

    mLapsListView = (ListView) findViewById(R.id.lv_laps);
    mLapsAdapter = new LapAdapter(this, android.R.layout.simple_list_item_1,
        mLaps);
    mLapsListView.setAdapter(mLapsAdapter);

    // start button
    mBtnStart = (ImageButton) findViewById(R.id.ib_start);
    mBtnStart.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (!mLocked) {
          handleStartButton();
        }
      }
    });

    // stop button
    mBtnStop = (ImageButton) findViewById(R.id.ib_stop);
    mBtnStop.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (!mLocked) {
          handleStopButton();
        }
      }
    });

    // lock button
    mBtnLock = (ImageButton) findViewById(R.id.ib_lock);
    mBtnLock.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        handleLockAction();
      }
    });
  }

  /**
   * Handles the action Start
   */
  private void handleStartButton() {

    if (!mIsRunning) {
      mIsRunning = true;

        // sending event START
        sendMessage(START_STOPWATCH_PATH, null);

      eventBus.post(new StartStopwatchEvent(
          new StartStopwatchEvent.StopWatchTickCallback() {
            @Override
            public void onTick(long elapsedTime) {

              String formattedTime = StringUtils.formatString(elapsedTime);
              mIimeView.setText(formattedTime);
            }
          }));

      // Change View state
      mBtnStart.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_playback_pause));
      mBtnStart.setBackgroundColor(getResources().getColor(R.color.orange));
      mBtnStop.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_add));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.blue));

        sendMessage(START_STOPWATCH_PATH, "Stopwatch timer started!");
    }
    else {

      eventBus.post(new PauseStopwatchEvent(
          new PauseStopwatchEvent.PauseTimeReceiver() {
            @Override
            public void receivePauseTime(long pauseTime) {
              String formattedTime = StringUtils.formatString(pauseTime);
              mIimeView.setText(formattedTime);
            }
          }));

      mIsRunning = false;

      // Change View state
      mBtnStart.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_playback_play));
      mBtnStart.setBackgroundColor(getResources().getColor(R.color.green));
      mBtnStop.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_reload));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.dark_blue));
    }
  }

  private void transferLapsToMobile(List<Lap> laps) {

  }

  /**
   * Handles the action Stop
   */
  private void handleStopButton() {
    if (!mIsRunning) {

        // sending event RESET
       sendMessage(RESET_STOPWATCH, null);

      eventBus.post(new ResetStopWatchEvent());
    }
    else {
      eventBus.post(new CreateLapEvent());
    }
  }

  /**
   * Handles the action Lock what appropriate button pressed
   */
  private void handleLockAction() {
    if (!mLocked) {
      // Lock
      mBtnLock.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_lock_closed));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.grey));
      mBtnStart.setBackgroundColor(getResources().getColor(R.color.light_grey));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.light_grey));
      mLocked = true;
    }
    else {
      // Unlock
      mBtnLock.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_lock_open));
      if (mIsRunning) {
        mBtnStop.setBackgroundColor(getResources().getColor(R.color.dark_blue));
        mBtnStart.setBackgroundColor(getResources().getColor(R.color.orange));
      }
      else {
        mBtnStart.setBackgroundColor(getResources().getColor(R.color.green));
        mBtnStop.setBackgroundColor(getResources().getColor(R.color.blue));
      }
      mLocked = false;
    }
  }

  public void onEvent(LapUpdateEvent event) {
    mLaps = event.getLaps();
    Collections.reverse(mLaps);
    mLapsAdapter = new LapAdapter(this, android.R.layout.simple_list_item_1,
        mLaps);
    mLapsListView.setAdapter(mLapsAdapter);

      if(!mLaps.isEmpty()) {

          Lap lap = mLaps.get(0);
          // new lap added
          String lapData = lap.getLapNumber() + "#" + lap.getLapTime() + "#" + lap.getTimeSum();

          // send event NEW LAP
          sendMessage(SAVE_LAP_MESSAGE, lapData);
      }
  }

  public void onEvent(SaveLapsEvent event) {
    transferLapsToMobile(mLaps);

  }

  public void onEvent(StatusResponseEvent event) {
    Log.d(getClass().getSimpleName(), event.isRunning() ? "running"
        : "not running");
    if (event.isRunning()) {
      // Change View state
      mIsRunning = true;
      mBtnStart.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_playback_pause));
      mBtnStart.setBackgroundColor(getResources().getColor(R.color.orange));
      mBtnStop.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_add));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.blue));
    }
    else {
      mIsRunning = false;

      // Change View state
      mBtnStart.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_playback_play));
      mBtnStart.setBackgroundColor(getResources().getColor(R.color.green));
      mBtnStop.setImageDrawable(getResources().getDrawable(
          R.mipmap.ic_action_reload));
      mBtnStop.setBackgroundColor(getResources().getColor(R.color.dark_blue));
    }
  }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(getClass().getSimpleName(), "onConnected: " + bundle);
        Wearable.DataApi.addListener(mGoogleApiClient, this);
        Wearable.MessageApi.addListener(mGoogleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(getClass().getSimpleName(), "onConnectionSuspended: " + i);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {

    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(getClass().getSimpleName(), "onConnectionFailed: " + connectionResult);
    }
}
