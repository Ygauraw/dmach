/**
 * Copyright (C) 2013 Simon Norberg
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 * 
 */

package net.simno.android.dmach;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import net.simno.android.dmach.PatchFragment.PatchFragmentListener;
import net.simno.android.dmach.model.Channel;
import net.simno.android.dmach.model.Patch;
import net.simno.android.dmach.model.PointF;
import net.simno.android.dmach.model.Setting;
import net.simno.android.dmach.view.ProgressBarView;
import net.simno.android.dmach.R;

import org.puredata.android.io.AudioParameters;
import org.puredata.android.service.PdService;
import org.puredata.android.utils.PdUiDispatcher;
import org.puredata.core.PdBase;
import org.puredata.core.PdListener;
import org.puredata.core.utils.IoUtils;

import com.michaelnovakjr.numberpicker.NumberPicker;
import com.michaelnovakjr.numberpicker.NumberPickerDialog;
import com.michaelnovakjr.numberpicker.NumberPickerDialog.OnNumberSetListener;

import android.os.Bundle;
import android.os.IBinder;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.ToggleButton;

public class DMachActivity extends Activity
implements PatchFragmentListener, OnNumberSetListener {

	public interface OnBeatListener {
		public void onBeat(int beat);
	}
	
	public interface OnTempoChangeListener {
		public void onTempoChange(int tempo);
	}
	
	private final String TAG = this.getClass().getSimpleName();
	static final int STEP_COUNT = 8;
	static final int CHANNEL_COUNT = 4;
	private ArrayList<Channel> channels;
	private int selectedChannelIndex = -1;
	private int tempo = 120;
	private int patch;
	private boolean isRunning;
	private ProgressBarView progressBarView;
	private PdUiDispatcher dispatcher;
	private PdService pdService;
	private final Object lock = new Object();
	private final ServiceConnection pdConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			pdService = ((PdService.PdBinder)service).getService();
			initPd();
		}
		@Override
		public void onServiceDisconnected(ComponentName name) {
			// this method will never be called
		}
	};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initChannels();
        initGui();
        initSystemServices();
        initPdService();
		Log.i(TAG, "onCreate");
	}
	
	@Override
	protected void onDestroy() {
		cleanup();
		Log.i(TAG, "onDestroy " + Integer.toString(getChangingConfigurations(), 16));
		super.onDestroy();
	}
	
	private void initChannels() {
		channels = new ArrayList<Channel>(CHANNEL_COUNT);
		
		Patch bdPatch = new Patch();
		bdPatch.addSetting(new Setting("Freq 1", "Freq 2", new PointF(.4f, .4f)));
		bdPatch.addSetting(new Setting("Curve Time", "Square", new PointF(.4f, 0)));
		bdPatch.addSetting(new Setting("Noise Level", "Low-pass", new PointF(.7f, .7f)));
		bdPatch.addSetting(new Setting("Decay", "Gain", new PointF(.49f, .49f)));
		channels.add(new Channel("bd", bdPatch, new boolean[STEP_COUNT]));
		
		Patch sdPatch = new Patch();
		sdPatch.addSetting(new Setting("Pitch", "Noise", new PointF(.49f , .8f)));
		sdPatch.addSetting(new Setting("Band-pass", "Band-pass Q", new PointF(.7f, .6f)));
		sdPatch.addSetting(new Setting("Decay", "Body Decay", new PointF(.55f, .42f)));
		sdPatch.addSetting(new Setting("Attack", "Low-pass", new PointF(.55f, .6f)));
		sdPatch.addSetting(new Setting("X-fade", "Gain", new PointF(.35f, .45f)));
		channels.add(new Channel("sd", sdPatch, new boolean[STEP_COUNT]));
		
		Patch ttPatch = new Patch();
		ttPatch.addSetting(new Setting("Pitch", "Gain", new PointF(.499f, .49f)));
		channels.add(new Channel("tt", ttPatch, new boolean[STEP_COUNT]));
		
		Patch hhPatch = new Patch();
		hhPatch.addSetting(new Setting("Pitch", "Ratio A", new PointF(.45f, 1)));
		hhPatch.addSetting(new Setting("Ratio B", "Noise", new PointF(.9f, .6f)));
		hhPatch.addSetting(new Setting("Noise Pitch", "Snap", new PointF(.55f, .1f)));
		hhPatch.addSetting(new Setting("Attack", "Release", new PointF(.4f, .55f)));
		hhPatch.addSetting(new Setting("Filter", "Filter Q", new PointF(.7f, .6f)));
		hhPatch.addSetting(new Setting("Low-pass", "Gain", new PointF(.8f, .4f)));
		channels.add(new Channel("hh", hhPatch, new boolean[STEP_COUNT]));
		Log.i(TAG, "initChannels");
	}
	
	private void initGui() {
		setContentView(R.layout.activity_dmach);

		getFragmentManager().beginTransaction()
        .add(R.id.fragment_container, SequencerFragment.newInstance(channels)).commit();
		
		final RelativeLayout container = (RelativeLayout) findViewById(R.id.fragment_container);
		container.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				initProgressBar(container.getWidth(), container.getHeight());
				container.getViewTreeObserver().removeOnGlobalLayoutListener(this);
			}
		});
		
		Log.i(TAG, "initGui");
	}
	
	private void initProgressBar(int width, int height) {
		progressBarView = new ProgressBarView(this, width, height, STEP_COUNT, tempo);
//      fragmentContainer.addView(progressBarView);
//      progressBarView.bringToFront();
		
		Log.i(TAG, "initProgressBar");
	}
	
	private void addProgressBar() {
		((RelativeLayout) findViewById(R.id.fragment_container)).addView(progressBarView);
	}
	
	private void removeProgressBar() {
		((RelativeLayout) findViewById(R.id.fragment_container)).removeView(progressBarView);
	}
	
	private void initSystemServices() {
		TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		telephonyManager.listen(new PhoneStateListener() {
			@Override
			public void onCallStateChanged(int state, String incomingNumber) {
				synchronized (lock) {
					if (pdService == null) {
						return;
					}
					if (state == TelephonyManager.CALL_STATE_IDLE) {
						if (!pdService.isRunning()) {
							startAudio();
						}
					} else {
						if (pdService.isRunning()) {
							stopAudio();
						}
					}
				}
			}
		}, PhoneStateListener.LISTEN_CALL_STATE);
		Log.i(TAG, "initSystemServices");
	} 
	
	private void initPdService() {
		new Thread() {
			@Override
			public void run() {
				bindService(new Intent(DMachActivity.this, PdService.class),
						pdConnection, BIND_AUTO_CREATE);
			}
		}.start();
		Log.i(TAG, "initPdService");
	}
	
	private void initPd() {
		dispatcher = new PdUiDispatcher();
		PdBase.setReceiver(dispatcher);
		dispatcher.addListener("pos", new PdListener.Adapter() {
			@Override
			public void receiveFloat(String source, float x) {
				progressBarView.onBeat((int) x);
			}
		});
		startAudio();
		Log.i(TAG, "initPd");
	}
	
	private void startAudio() {
		synchronized (lock) {
			if (pdService == null) {
				return;
			}
			int sampleRate = AudioParameters.suggestSampleRate();
			try {
				pdService.initAudio(sampleRate, 0, 2, -1);
			} catch (IOException e) {
				Log.e(TAG, e.toString());
				finish();
				return;
			}
			if (patch == 0) {
				System.out.println("patch == 0");
				try {
					File dir = getFilesDir();
					IoUtils.extractZipResource(getResources().openRawResource(R.raw.dmach), dir, true);
					File patchFile = new File(dir, "dmach.pd");
					patch = PdBase.openPatch(patchFile.getAbsolutePath());
				} catch (IOException e) {
					Log.e(TAG, e.toString());
					finish();
					return;
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					
				}
			}
			pdService.startAudio(new Intent(this, DMachActivity.class),
					R.drawable.ic_stat_notify_dmach, "DMach", "Return to DMach.");	
		}
		Log.i(TAG, "startAudio");
	}
	
	private void stopAudio() {
		synchronized (lock) {
			if (pdService == null) {
				return;
			}
			pdService.stopAudio();
		}
		Log.i(TAG, "stopAudio");
	}
	
	private void cleanup() {
		synchronized (lock) {
			stopAudio();
			if (patch != 0) {
				PdBase.closePatch(patch);
				patch = 0;
			}
			dispatcher.release();
			PdBase.release();
			try {
				unbindService(pdConnection);
			} catch (IllegalArgumentException e) {
				pdService = null;
			}
		}
		Log.i(TAG, "cleanup");
	}
	
	public void onBackPressed() {
		new AlertDialog.Builder(this)
			.setIcon(R.drawable.icon)
			.setTitle("Closing DMach")
			.setMessage("Are you sure you want to close DMach?")
			.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					finish();
				}
			})
			.setNegativeButton("No", null)
			.show();
		Log.i(TAG, "onBackPressed");
	}
	
	public void onPlayClicked(View view) {
		ImageButton imageButton = (ImageButton) view;
		if (true == isRunning) {
			imageButton.setImageResource(R.drawable.play);
			removeProgressBar();
		} else {
			imageButton.setImageResource(R.drawable.stop);
			addProgressBar();
		}
		isRunning = !isRunning;
		PdBase.sendBang("run");
		Log.i(TAG, "onPlayClicked");
	}
		
	public void onTempoClicked(View view) {
		int tempo = Integer.parseInt((String) ((Button) view).getText());
		NumberPickerDialog dialog = new NumberPickerDialog(this, -1, 120);
		NumberPicker picker = dialog.getNumberPicker();
		picker.setRange(1, 1000);
		picker.setCurrent(tempo);
		picker.setSpeed(50);
        dialog.setOnNumberSetListener(this);
        dialog.show();
		Log.i(TAG, "onTempoClicked");
	}
	
	public void onResetClicked(View view) {
		//reset sequence
		//sendBang to pd
		//setFragment if channel == -1
		Log.i(TAG, "onResetClicked");
	}
	
	@Override
	public void onNumberSet(int selectedNumber) {
		PdBase.sendFloat("tempo", selectedNumber);
		((Button) findViewById(R.id.tempoButton)).setText("" + selectedNumber);
		Log.i(TAG, "onNumberSet: " + selectedNumber);
	}
	
	public void onChannelClicked(View view) {
		RadioGroup group = (RadioGroup) findViewById(R.id.channels);
		int index = group.indexOfChild(view);
		if (index != -1) {
			if (index == selectedChannelIndex) {
				group.clearCheck();
				selectedChannelIndex = -1;
			} else {
				selectedChannelIndex = index;
			}
			setFragment();
		}
		Log.i(TAG, "onChannelClicked");
	}
	
	public void onStepClicked(View view) {
		ViewGroup steps = (ViewGroup) findViewById(R.id.steps);
		ViewGroup group = ((ViewGroup)view.getParent());
		Channel channel = channels.get(steps.indexOfChild(group));
		int buttonIndex = group.indexOfChild(view);
		boolean status = ((ToggleButton) view).isChecked();
		
		channel.setStep(buttonIndex, status);
		PdBase.sendFloat(channel.getName(), buttonIndex);
		
		Log.i(TAG, "onStepClicked " + channel.getName() + " " + buttonIndex);
	}
	
	@Override
	public void onSettingIndexChanged(int index) {
		getSelectedChannel().getPatch().setSelectedSettingIndex(index);
		Log.i(TAG, "onSettingIndexChanged");
	}

	@Override
	public void onSettingPosChanged(PointF pos) {
		getSelectedChannel().getPatch().setSelectedPos(pos);
		String name = getSelectedChannel().getName();
		int index = getSelectedChannel().getPatch().getSelectedSettingIndex();
		PdBase.sendFloat(name + (2 * index), pos.getX());
		PdBase.sendFloat(name + (2 * index + 1), pos.getY());
//		Log.i(TAG, "onSettingPosChanged");
	}
		
	private Channel getSelectedChannel() {
//		Log.i(TAG, "getSelectedChannel");
		return channels.get(selectedChannelIndex);
	}
	
	private void setFragment() {
		FragmentTransaction transaction = getFragmentManager().beginTransaction();
		if (selectedChannelIndex != -1) {
			transaction.replace(R.id.fragment_container,
					PatchFragment.newInstance(getSelectedChannel().getPatch()));
		} else {
			transaction.replace(R.id.fragment_container,
					SequencerFragment.newInstance(channels));
		}
		transaction.commit();
		Log.i(TAG, "setFragment");
	}
}