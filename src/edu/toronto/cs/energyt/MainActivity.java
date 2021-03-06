package edu.toronto.cs.energyt;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.VideoView;

/**
 * Main player activity.
 * 
 * @author mcupak
 * 
 */
public class MainActivity extends Activity {

	public static final String OTHER_FILE = Environment
			.getExternalStorageDirectory().getAbsolutePath()
			+ "/Download/other.mp4";
	public static final String HTMLSOURCE_FILE = Environment
			.getExternalStorageDirectory().getAbsolutePath()
			+ "/Download/html_source.html";
	public static final String YOUTUBE_URL = "http://www.youtube.com/watch?v=Vjm3KPGab5o";
	public static final String TAG = "energyt";
	private VideoView video;
	private boolean isPlaying;
	private String linkPlaying;
	private PlayListener playListener;
	private StopListener stopListener;
	private ResetListener resetListener;
	// private CompletionListener completionListener;

	// These variables have to do with the implementation of energy efficient
	// algorithm
	public static final long CONNECT_THRESHOLD = 8000;
	public static final long DISCONNECT_THRES = 50000;
	private volatile long ytDownloadedSize;
	private volatile long videoViewStreamedSize;
	private volatile boolean ytDownFinished;
	private volatile int signalStrength; 

	// Objects in order to acquire info about signal strength, WiFi connection
	// etc...
	public WifiManager wifi = null;
	public TelephonyManager telephony = null;
	public SignalStateListener signalListener = null;

	// All threads are public because we want MainActivity to have full control
	// upon
	// them even if some of them are initialized/spawned from other threads...
	public static final int SERVER_PREP_TIME = 10000;
	public static final int THREADS_COMPLETION_PERIOD = 10000;
	public StreamProxy serverSocketThread;
	public StreamProxy.StreamToMediaPlayerTask clientSocketThread;
	public DownloadTask ytDownloaderThread;

	/* ACTIVITY LISTENERS */

	/**
	 * Listener: Base class for listening events. Other classes listening
	 * classes inherit from it directly...
	 * 
	 * @author michael
	 * 
	 */
	private class Listener {

		protected MainActivity mainApp;

		public Listener(MainActivity mainApp) {
			this.mainApp = mainApp;
		}

		protected void DestroyThreads() {
			if (mainApp.clientSocketThread != null
					&& mainApp.clientSocketThread.getStatus() != AsyncTask.Status.FINISHED) {
				mainApp.clientSocketThread.cancel(true);
			}
			if (mainApp.ytDownloaderThread != null
					&& mainApp.ytDownloaderThread.getStatus() != AsyncTask.Status.FINISHED) {
				mainApp.ytDownloaderThread.cancel(true);
			}
			// Loop in order to be sure that threads have stopped execution
			for (int i = 0; i < THREADS_COMPLETION_PERIOD; i++)
				;
			// mainApp.wait(100);
			mainApp.clientSocketThread = null;
			mainApp.ytDownloaderThread = null;
		}
	}

	private class PlayListener extends Listener implements OnClickListener {

		public PlayListener(MainActivity mainApp) {
			super(mainApp);
		}

		@Override
		public void onClick(View v) {
			Log.d(MainActivity.TAG, "In Play listener");
			String currentLink = ((EditText) mainApp.findViewById(R.id.edtURL))
					.getText().toString();
			mainApp.isPlaying = true;
			Log.d(MainActivity.TAG, "Link playing: " + currentLink);
			if (mainApp.linkPlaying == null) {
				mainApp.linkPlaying = currentLink;
				mainApp.setStreamedSize(0);
				mainApp.setYTDownloadSize(0);
				// Loop in order to be sure that server has initialized before
				// spawning other threads...
				Log.d(MainActivity.TAG, "Calling Download Task");
				mainApp.ytDownloaderThread = new DownloadTask(mainApp);
				mainApp.ytDownloaderThread.executeOnExecutor(
						AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
			} else if (mainApp.linkPlaying.compareTo(currentLink) != 0) {
				mainApp.video.stopPlayback();
				this.DestroyThreads();
				mainApp.linkPlaying = currentLink;
				mainApp.setStreamedSize(0);
				mainApp.setYTDownloadSize(0);
				// Loop in order to be sure that server has initialized before
				// spawning other threads...
				Log.d(MainActivity.TAG, "Calling Download Task");
				mainApp.ytDownloaderThread = new DownloadTask(mainApp);
				mainApp.ytDownloaderThread.executeOnExecutor(
						AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
			} else {
				mainApp.video.start();
			}
		}

	}

	private class StopListener extends Listener implements OnClickListener {

		public StopListener(MainActivity mainApp) {
			super(mainApp);
		}

		@Override
		public void onClick(View v) {
			if (mainApp.video != null) {
				mainApp.video.stopPlayback();
				mainApp.video.seekTo(0);
			}
			this.DestroyThreads();
			mainApp.isPlaying = false;
			mainApp.linkPlaying = null;
		}

	}

	private class ResetListener extends Listener implements OnClickListener {

		public ResetListener(MainActivity mainApp) {
			super(mainApp);
		}

		public void onClick(View v) {
			if (mainApp.video != null) {
				mainApp.video.seekTo(0);
			}
			this.DestroyThreads();
			mainApp.isPlaying = false;
			mainApp.linkPlaying = null;
		}
	}
	
	private class SignalStateListener extends PhoneStateListener {
		
		MainActivity mainApp;
		
		SignalStateListener(MainActivity mainApp) {
			super();
			this.mainApp = mainApp;
		}
		
		@Override
		public void onSignalStrengthsChanged(SignalStrength signalStrength) {
			super.onSignalStrengthsChanged(signalStrength);
			mainApp.signalStrength = signalStrength.getGsmSignalStrength();
		}
	}

	/* ACTIVITY CORE FUNCTIONS */

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		this.ytDownloadedSize = 0;
		this.videoViewStreamedSize = 0;
		this.signalStrength = 0;
		this.setYtDownFinished(false);

		this.wifi = (WifiManager) this
				.getSystemService(Context.WIFI_SERVICE);
		this.signalListener = new SignalStateListener(this);
		this.telephony = (TelephonyManager) this
				.getSystemService(Context.TELEPHONY_SERVICE);
		this.telephony.listen(signalListener, 
				SignalStateListener.LISTEN_SIGNAL_STRENGTHS);
		

		this.isPlaying = false;
		this.linkPlaying = null;
		this.serverSocketThread = null;
		this.clientSocketThread = null;
		this.ytDownloaderThread = null;
		this.playListener = new PlayListener(this);
		this.stopListener = new StopListener(this);
		this.resetListener = new ResetListener(this);
		// this.completionListener = new CompletionListener(this);

		// First of all initialize the server...
		Log.d(TAG, "Instantiating server");
		this.serverSocketThread = new StreamProxy(this);
		this.serverSocketThread.executeOnExecutor(
				AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
		Log.d(TAG, "Server triggered");
		for (int i = 0; i < SERVER_PREP_TIME; i++)
			;

		getWindow().setFormat(PixelFormat.TRANSLUCENT);
		setContentView(R.layout.activity_main);

		((EditText) findViewById(R.id.edtURL)).setText(YOUTUBE_URL);

		this.video = (VideoView) findViewById(R.id.vidView);
		// this.video.setOnCompletionListener(this.completionListener);

		// set up buttons to override default controls
		Button mPlay = (Button) findViewById(R.id.btnPlay);
		Button mPause = (Button) findViewById(R.id.btnPause);
		Button mReset = (Button) findViewById(R.id.btnReset);
		Button mStop = (Button) findViewById(R.id.btnStop);

		mPlay.setOnClickListener(this.playListener);

		mPause.setOnClickListener(new OnClickListener() {
			public void onClick(View view) {
				Log.d(TAG, "On pause event.");
				if (video != null) {
					Log.d(TAG, "In Pause listener");
					isPlaying = false;
					video.pause();
				}
			}
		});

		mReset.setOnClickListener(this.resetListener);

		mStop.setOnClickListener(this.stopListener);

		video.setOnErrorListener(new OnErrorListener() {

			@Override
			public boolean onError(MediaPlayer mp, int what, int extra) {
				return true;
			}
		});

	}

	// @Override
	// protected void onResume() {
	//
	// }
	//
	// @Override
	// protected void onPause() {
	// int currPosition = this.video.getCurrentPosition();
	//
	// }

	/**
	 * playVideo(): Triggers videoView to play the specific video the user
	 * demanded after prefetching has finished.
	 */
	public void playVideo() {
		try {
			Log.d(TAG, "Playback started.");
			isPlaying = true;
			video.setVideoURI(Uri.parse("http://127.0.0.1:8893/xyz"));
			video.start();
			video.requestFocus();
			Log.d(TAG, "Duration: " + video.getDuration());
			Log.d(TAG, "Buffered Percentage: " + video.getBufferPercentage());
		} catch (Exception e) {
			Log.e(TAG, "error: " + e.getMessage(), e);
			if (video != null) {
				video.stopPlayback();
			}
		}
	}

	public long getRemainingBuffer() {
		return this.ytDownloadedSize - this.videoViewStreamedSize;
	}

	public boolean canDisconnect() {
		return (this.getRemainingBuffer() > MainActivity.DISCONNECT_THRES);
	}

	public boolean canConnect() {
		return (this.getRemainingBuffer() < MainActivity.CONNECT_THRESHOLD);
	}

	public String getLinkPlaying() {
		return this.linkPlaying;
	}

	public void setYTDownloadSize(long size) {
		this.ytDownloadedSize = size;
	}

	public long getYTDownloadSize() {
		return this.ytDownloadedSize;
	}

	public void setStreamedSize(long size) {
		this.videoViewStreamedSize = size;
	}

	public long getStreamedSize() {
		return this.videoViewStreamedSize;
	}

	public boolean ytThreadFinished() {
		return ytDownFinished;
	}

	public void setYtDownFinished(boolean ytDownFinished) {
		this.ytDownFinished = ytDownFinished;
	}
	
	public int getSignalStrength() {
		return this.signalStrength;
	}

}
