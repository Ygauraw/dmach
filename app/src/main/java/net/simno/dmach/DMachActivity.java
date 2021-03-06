/*
* Copyright (C) 2014 Simon Norberg
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package net.simno.dmach;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Switch;
import android.widget.TextView;

import net.simno.dmach.model.Channel;
import net.simno.dmach.model.Patch;
import net.simno.dmach.model.Setting;
import net.simno.dmach.view.CustomFontButton;

import org.parceler.Parcels;

import org.puredata.android.io.AudioParameters;
import org.puredata.android.service.PdService;
import org.puredata.core.PdBase;
import org.puredata.core.utils.IoUtils;

import butterknife.ButterKnife;
import butterknife.InjectView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DMachActivity extends Activity {

    private static final String PREF_TITLE = "net.simno.dmach.PREF_TITLE";
    private static final String PREF_SEQUENCE = "net.simno.dmach.PREF_SEQUENCE";
    private static final String PREF_CHANNELS = "net.simno.dmach.PREF_CHANNELS";
    private static final String PREF_TEMPO = "net.simno.dmach.PREF_TEMPO";
    private static final String PREF_SWING = "net.simno.dmach.PREF_SWING";
    private static final String PREF_CHANNEL = "net.simno.dmach.PREF_CHANNEL";
    private static final String PREF_PROGRESS = "net.simno.dmach.PREF_PROGRESS";

    private static final int PATCH_REQUEST = 1;
    public static final int[] MASKS = {1, 2, 4};
    public static final int GROUPS = 2;
    public static final int CHANNELS = 6;
    public static final int STEPS = 16;

    @InjectView(R.id.channel_container) LinearLayout mChannelContainer;
    @InjectView(R.id.patch_button) ImageButton mPatchButton;

    private boolean mIsRunning;
    private boolean mShowProgress;
    private String mTitle;
    private int[] mSequence;
    private List<Channel> mChannels;
    private int mSelectedChannel;
    private int mTempo;
    private int mSwing;
    private TextView mTempoText;
    private TextView mSwingText;
    private int mPdPatch;
    private PdService mPdService;
    private final Object mLock = new Object();
    private final ServiceConnection mPdConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mPdService = ((PdService.PdBinder)service).getService();
            startAudio();
            initPd();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };
    private final OnSeekBarChangeListener mTempoListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
            switch (seekBar.getId()) {
                case R.id.tempo_seekbar_10:
                    mTempo = (progress + 1) * 10 + (mTempo % 10);
                    break;
                case R.id.tempo_seekbar_1:
                    mTempo = Math.max((mTempo / 10) * 10 + progress, 1);
                    break;
            }
            PdBase.sendFloat("tempo", mTempo);
            if (mTempoText != null) {
                mTempoText.setText(" " + mTempo);
            }
        }
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };
    private final OnSeekBarChangeListener mSwingListener = new OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
            PdBase.sendFloat("swing", progress / 100.0f);
            if (mSwingText != null) {
                mSwingText.setText(" " + progress);
            }
            mSwing = progress;
        }
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreSettings();
        initGui();
        initSystemServices();
        initPdService();
    }

    @Override
    protected void onStop() {
        storeSettings();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void storeSettings() {
        Editor editor = getPreferences(MODE_PRIVATE).edit();
        String sequenceJson = Patch.sequenceToJson(mSequence);
        String channelsJson = Patch.channelsToJson(mChannels);
        editor.putString(PREF_TITLE, mTitle)
                .putString(PREF_SEQUENCE, sequenceJson)
                .putString(PREF_CHANNELS, channelsJson)
                .putInt(PREF_TEMPO, mTempo)
                .putInt(PREF_SWING, mSwing)
                .putInt(PREF_CHANNEL, mSelectedChannel)
                .putBoolean(PREF_PROGRESS, mShowProgress)
                .apply();
    }

    private void restoreSettings() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        mTitle = prefs.getString(PREF_TITLE, "untitled");
        String sequenceJson = prefs.getString(PREF_SEQUENCE, "");
        if (!sequenceJson.isEmpty()) {
            mSequence = Patch.jsonToSequence(sequenceJson);
        } else {
            mSequence = new int[GROUPS * STEPS];
        }
        String channelsJson = prefs.getString(PREF_CHANNELS, "");
        if (!channelsJson.isEmpty()) {
            mChannels = Patch.jsonToChannels(channelsJson);
        } else {
            initChannels();
        }
        mTempo = prefs.getInt(PREF_TEMPO, 120);
        mSwing = prefs.getInt(PREF_SWING, 0);
        mSelectedChannel = prefs.getInt(PREF_CHANNEL, -1);
        mShowProgress = prefs.getBoolean(PREF_PROGRESS, true);
    }

    private void initChannels() {
        // Hardcoded values equal to the pd file
        mChannels = new ArrayList<>();

        Channel bd = new Channel("bd");
        bd.addSetting(new Setting("Pitch A", "Gain", .4f, .49f, 0, 7));
        bd.addSetting(new Setting("Low-pass", "Square", .7f, 0, 5, 3));
        bd.addSetting(new Setting("Pitch B", "Curve Time", .4f, .4f, 1, 2));
        bd.addSetting(new Setting("Decay", "Noise Level", .49f, .7f, 6, 4));
        mChannels.add(bd);

        Channel sd = new Channel("sd");
        sd.addSetting(new Setting("Pitch", "Gain", .49f, .45f, 0, 9));
        sd.addSetting(new Setting("Low-pass", "Noise", .6f, .8f, 7, 1));
        sd.addSetting(new Setting("X-fade", "Attack", .35f, .55f, 8, 6));
        sd.addSetting(new Setting("Decay", "Body Decay", .55f, .42f, 4, 5));
        sd.addSetting(new Setting("Band-pass", "Band-pass Q", .7f, .6f, 2, 3));
        mChannels.add(sd);

        Channel cp = new Channel("cp");
        cp.addSetting(new Setting("Pitch", "Gain", .55f, .3f, 0, 7));
        cp.addSetting(new Setting("Delay 1", "Delay 2", .3f, .3f, 4, 5));
        cp.addSetting(new Setting("Decay", "Filter Q", .59f, .2f, 6, 1));
        cp.addSetting(new Setting("Filter 1", "Filter 2", .9f, .15f, 2, 3));
        mChannels.add(cp);

        Channel tt = new Channel("tt");
        tt.addSetting(new Setting("Pitch", "Gain", .49f, .49f, 0, 1));
        mChannels.add(tt);

        Channel cb = new Channel("cb");
        cb.addSetting(new Setting("Pitch", "Gain", .3f, .49f, 0, 5));
        cb.addSetting(new Setting("Decay 1", "Decay 2", .1f, .75f, 1, 2));
        cb.addSetting(new Setting("Vcf", "Vcf Q", .3f, 0, 3, 4));
        mChannels.add(cb);

        Channel hh = new Channel("hh");
        hh.addSetting(new Setting("Pitch", "Gain", .45f, .4f, 0, 11));
        hh.addSetting(new Setting("Low-pass", "Snap", .8f, .1f, 10, 5));
        hh.addSetting(new Setting("Noise Pitch", "Noise", .55f, .6f, 4, 3));
        hh.addSetting(new Setting("Ratio B", "Ratio A", .9f, 1, 2, 1));
        hh.addSetting(new Setting("Release", "Attack", .55f, .4f, 7, 6));
        hh.addSetting(new Setting("Filter", "Filter Q", .7f, .6f, 8, 9));
        mChannels.add(hh);
    }

    private void initGui() {
        setContentView(R.layout.activity_dmach);
        ButterKnife.inject(this);

        if (mSelectedChannel != -1) {
            CustomFontButton channel =
                    (CustomFontButton) mChannelContainer.getChildAt(mSelectedChannel);
            if (channel != null) {
                channel.setSelected(true);
                getFragmentManager().beginTransaction().add(R.id.fragment_container,
                        ChannelFragment.newInstance(mChannels.get(mSelectedChannel))).commit();
            }
        } else {
            getFragmentManager().beginTransaction().add(R.id.fragment_container,
                    SequencerFragment.newInstance(mSequence, mShowProgress)).commit();
        }
    }

    private void initSystemServices() {
        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                synchronized (mLock) {
                    if (mPdService == null) {
                        return;
                    }
                    if (state == TelephonyManager.CALL_STATE_IDLE) {
                        if (!mPdService.isRunning()) {
                            startAudio();
                        }
                    } else {
                        if (mPdService.isRunning()) {
                            stopAudio();
                        }
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);
    }

    private void initPdService() {
        new Thread() {
            @Override
            public void run() {
                bindService(new Intent(DMachActivity.this, PdService.class), mPdConnection,
                        BIND_AUTO_CREATE);
            }
        }.start();
    }

    private void initPd() {
        PdBase.sendFloat("swing", mSwing / 100.0f);
        PdBase.sendFloat("tempo", mTempo);
        sendSequence();
        sendSettings();
    }

    private void sendSequence() {
        for (int step = 0; step < STEPS; ++step) {
            PdBase.sendList("step", 0, step, mSequence[step]);
            PdBase.sendList("step", 1, step, mSequence[step + STEPS]);
        }
    }

    private void sendSettings() {
        for (Channel channel : mChannels) {
            String name = channel.getName();
            PdBase.sendFloat(name + "p", channel.getPan());
            for (Setting setting : channel.getSettings()) {
                PdBase.sendList(name, setting.getHIndex(), setting.getX());
                PdBase.sendList(name, setting.getVIndex(), setting.getY());
            }
        }
    }

    private void startAudio() {
        synchronized (mLock) {
            if (mPdService == null) {
                return;
            }
            int sampleRate = AudioParameters.suggestSampleRate();
            try {
                mPdService.initAudio(sampleRate, 0, 2, -1);
            } catch (IOException e) {
                finish();
                return;
            }
            if (mPdPatch == 0) {
                try {
                    File dir = getFilesDir();
                    IoUtils.extractZipResource(getResources()
                            .openRawResource(R.raw.dmach), dir, true);
                    File patchFile = new File(dir, "dmach.pd");
                    mPdPatch = PdBase.openPatch(patchFile.getAbsolutePath());
                } catch (IOException e) {
                    finish();
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            mPdService.startAudio(new Intent(this, DMachActivity.class),
                    R.drawable.ic_stat_notify_dmach, "DMach is running", "Touch to return.");
        }
    }

    private void stopAudio() {
        synchronized (mLock) {
            if (mPdService == null) {
                return;
            }
            mPdService.stopAudio();
        }
    }

    private void cleanup() {
        synchronized (mLock) {
            stopAudio();
            if (mPdPatch != 0) {
                PdBase.closePatch(mPdPatch);
                mPdPatch = 0;
            }
            PdBase.release();
            try {
                unbindService(mPdConnection);
            } catch (IllegalArgumentException e) {
                mPdService = null;
            }
        }
    }

    private void setFragment() {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        if (mSelectedChannel != -1) {
            transaction.replace(R.id.fragment_container,
                    ChannelFragment.newInstance(mChannels.get(mSelectedChannel)));
            transaction.commit();
        } else {
            transaction.replace(R.id.fragment_container,
                    SequencerFragment.newInstance(mSequence, mShowProgress));
            transaction.commit();
            getFragmentManager().executePendingTransactions();
        }
    }

    public void onPlayClicked(View view) {
        ImageButton playButton = (ImageButton) view;
        if (mIsRunning) {
            PdBase.sendBang("stop");
        } else {
            PdBase.sendBang("play");
        }
        mIsRunning = !mIsRunning;
        playButton.setSelected(mIsRunning);
    }

    @SuppressLint("InflateParams")
    public void onConfigClicked(View view) {
        final ImageButton configButton = (ImageButton) view;
        configButton.setSelected(true);
        LayoutInflater inflater = LayoutInflater.from(this);
        View layout = inflater.inflate(R.layout.dialog_config, null, false);

        AlertDialog alertDialog = new AlertDialog.Builder(this).setView(layout).create();
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialog.setOnDismissListener(dialog -> configButton.setSelected(false));
        alertDialog.show();

        mTempoText = (TextView) layout.findViewById(R.id.tempo_value);
        mTempoText.setText(" " + mTempo);

        SeekBar tempoSeekBar10 = (SeekBar) layout.findViewById(R.id.tempo_seekbar_10);
        tempoSeekBar10.setProgress((mTempo / 10) - 1);
        tempoSeekBar10.setOnSeekBarChangeListener(mTempoListener);

        SeekBar tempoSeekBar1 = (SeekBar) layout.findViewById(R.id.tempo_seekbar_1);
        tempoSeekBar1.setProgress(mTempo % 10);
        tempoSeekBar1.setOnSeekBarChangeListener(mTempoListener);

        mSwingText = (TextView) layout.findViewById(R.id.swing_value);
        mSwingText.setText(" " + mSwing);

        SeekBar swingSeekBar = (SeekBar) layout.findViewById(R.id.swing_seekbar);
        swingSeekBar.setProgress(mSwing);
        swingSeekBar.setOnSeekBarChangeListener(mSwingListener);

        Switch progressBarSwitch = (Switch) layout.findViewById(R.id.progress_bar_switch);
        progressBarSwitch.setTextColor(mSwingText.getCurrentTextColor());
        progressBarSwitch.setChecked(mShowProgress);
        progressBarSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mShowProgress != isChecked) {
                mShowProgress = isChecked;
                if (mSelectedChannel == -1) {
                    setFragment();
                }
            }
        });
    }

    public void onResetClicked(View view) {
        mSequence = new int[GROUPS * STEPS];
        sendSequence();
        setFragment();
    }

    public void onPatchClicked(View view) {
        view.setSelected(true);
        Patch patch = new Patch(mTitle, mSequence, mChannels, mSelectedChannel, mTempo, mSwing);
        Intent intent = new Intent(this, PatchListActivity.class);
        intent.putExtra(PatchListActivity.PATCH_EXTRA, Parcels.wrap(patch));
        startActivityForResult(intent, PATCH_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mPatchButton.setSelected(false);
        if (requestCode == PATCH_REQUEST) {
            if (resultCode == PatchListActivity.RESULT_LOADED) {
                Patch patch = Parcels.unwrap(data.getParcelableExtra(PatchListActivity.PATCH_EXTRA));
                mTitle = patch.getTitle();
                mSequence = patch.getSequence();
                mChannels = patch.getChannels();
                mSelectedChannel = patch.getSelectedChannel();
                mTempo = patch.getTempo();
                mSwing = patch.getSwing();
                initPd();
                setFragment();
                setChannelSelection();
            } else if (resultCode == PatchListActivity.RESULT_SAVED) {
                mTitle = data.getStringExtra(PatchListActivity.TITLE_EXTRA);
            }
        }
    }

    @SuppressLint("InflateParams")
    public void onLogoClicked(View view) {
        final CustomFontButton logo = (CustomFontButton) view;
        logo.setSelected(true);
        LayoutInflater inflater = LayoutInflater.from(this);
        View layout = inflater.inflate(R.layout.dialog_notices, null, false);
        AlertDialog alertDialog = new AlertDialog.Builder(this).setView(layout).create();
        alertDialog.setCanceledOnTouchOutside(true);
        alertDialog.setOnDismissListener(dialog -> logo.setSelected(false));
        alertDialog.setTitle(getString(R.string.notices));
        alertDialog.show();

        MovementMethod mm = LinkMovementMethod.getInstance();
        ((TextView) layout.findViewById(R.id.dmach_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.cyclone_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.diy2_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.gson_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.hcs_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.icomoon_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.libpd_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.pan_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.pdcore_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.saxmono_header)).setMovementMethod(mm);
        ((TextView) layout.findViewById(R.id.zexy_header)).setMovementMethod(mm);
    }

    public void onChannelClicked(View view) {
        CustomFontButton channel = (CustomFontButton) view;
        LinearLayout channels = (LinearLayout) channel.getParent();
        int index = channels.indexOfChild(channel);
        mSelectedChannel = mSelectedChannel == index ? -1 : index;
        setFragment();
        setChannelSelection();
    }

    private void setChannelSelection() {
        for (int i = 0; i < CHANNELS; ++i) {
            Button channel = (Button) mChannelContainer.getChildAt(i);
            channel.setSelected(i == mSelectedChannel);
        }
    }
}
