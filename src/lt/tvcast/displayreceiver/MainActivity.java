package lt.tvcast.displayreceiver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Miracast (Wi-Fi Display) receiver for the TV, made for Samsung DeX and phone mirroring. Android
 * has no receiver of its own and Sony's does not get the Wi-Fi Direct events it needs, so this app
 * does it: it keeps a Wi-Fi Direct search running, and once a phone has joined it connects to the
 * phone's RTSP port (the source listens, the sink connects) and plays what is sent. While a cast
 * plays, the controls are hidden and the picture takes the whole screen.
 */
public class MainActivity extends Activity {
    private static final String TAG = "displayreceiver";
    private static final int REQ_LOCATION = 1;
    private static final int RTSP_PORT = 7236;

    static final int INK = 0xFFFFFFFF;
    static final int INK_SOFT = 0xB3FFFFFF;
    static final int INK_FAINT = 0x66FFFFFF;
    static final int OK_GREEN = 0xFF6BDB8B;
    static final int BUSY_AMBER = 0xFFFFC069;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private boolean wanted;                   // searching: off until the user presses Start, off again when the screen is left
    private volatile boolean sinkBusy;        // a group exists and we are waiting for / serving a phone
    private volatile boolean connecting;      // a phone has joined and the session is being set up or runs
    private String deviceName = "";

    private TextView statusText, nameText, fiveText;
    private Button fiveButton;
    private View statusDot;
    private Button button;
    private TextureView surfaceView;
    private volatile Surface surface;
    private View ui;
    private final Handler handler = new Handler();
    private WifiManager.WifiLock wifiLock;

    private final Runnable rediscover = new Runnable() {
        @Override public void run() { if (wanted && !sinkBusy) start(); }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                onGroup();
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(a)) {
                WifiP2pDevice d = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (d != null) deviceName = d.deviceName;
            }
            refresh();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(64), dp(28), dp(64), dp(28));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF0B0D1A, 0xFF1B1840 }));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.app_icon);
        mark.setClipToOutline(true);
        mark.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
        root.addView(mark, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = text(34, INK, "sans-serif-light");
        title.setText(R.string.app_name);
        root.addView(title, margin(12));

        TextView subtitle = text(16, INK_SOFT, "sans-serif");
        subtitle.setText(R.string.subtitle);
        root.addView(subtitle, margin(4));

        // status card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(32), dp(18), dp(32), dp(18));
        card.setBackground(rounded(0x1AFFFFFF, dp(24), 0));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        row.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusText = text(22, INK, "sans-serif-medium");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(14);
        row.addView(statusText, sp);
        card.addView(row);

        nameText = text(15, INK_SOFT, "sans-serif");
        card.addView(nameText, margin(6));

        TextView steps = text(14, INK_FAINT, "sans-serif");
        steps.setText(R.string.steps);
        steps.setMaxWidth(dp(520));
        card.addView(steps, margin(10));
        fiveText = text(14, BUSY_AMBER, "sans-serif-medium");
        card.addView(fiveText, margin(8));

        LinearLayout.LayoutParams cp = margin(18);
        root.addView(card, cp);

        button = pill();
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        fiveButton = pill();
        fiveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFiveGuide(); }
        });
        Button helpButton = pill();
        helpButton.setText(R.string.btn_help);
        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGuide(Guide.help(MainActivity.this, new Runnable() {
                @Override public void run() { closeGuide(); }
            })); }
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = dp(16);
        buttons.addView(fiveButton, fp);
        buttons.addView(helpButton, fp);
        root.addView(buttons, margin(16));

        TextView back = text(12, INK_FAINT, "sans-serif");
        back.setText(R.string.back_hint);
        root.addView(back, margin(10));

        // the picture of a cast goes to this surface; the controls above it are hidden while it plays
        // A TextureView, not a SurfaceView: the TV's own video plane showed 2560x1440 pictures with the
        // wrong line pitch (the picture doubled sideways, the lower half turned to noise) although a
        // screenshot looked fine. A TextureView is composed by the GPU like any other view.
        surfaceView = new TextureView(this);
        surfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = new Surface(st); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { surface = null; return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        ui = root;
        FrameLayout frame = new FrameLayout(this);
        guideHost = frame;
        frame.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        button.requestFocus();

        if (p2p == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            wanted = false;
            button.setEnabled(false);
            nameText.setText(R.string.unsupported);
        }
        disarmFiveGhz();      // a mode left on by an earlier run (the process was killed) ends here
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        registerReceiver(receiver, f);
        // No Wi-Fi power saving while the app is in front: without it the link's delay grew from
        // ~4 ms to 20-50 ms with large swings a few minutes into a cast, and the picture stuttered
        if (wifiLock == null) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG);
            wifiLock.setReferenceCounted(false);
        }
        wifiLock.acquire();
        resumed = true;
        if (wanted && wifiPrepared) start();
        refresh();
    }

    @Override protected void onStop() {
        // 5 GHz mode lasts for one visit: leaving the app switches it off and forgets the adb key
        if (!preparing) disarmFiveGhz();
        super.onStop();
    }

    @Override protected void onPause() {
        resumed = false;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        unregisterReceiver(receiver);
        handler.removeCallbacks(rediscover);
        // leaving the screen ends the search; Android then switches Wi-Fi Direct off once it is idle
        wanted = false;
        release();
        super.onPause();
    }

    /** A rounded button that turns white when it has the focus (the TV remote). */
    Button pill() {
        final Button b = new Button(this);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(36), dp(4), dp(36), dp(4));
        b.setStateListAnimator(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] { android.R.attr.state_focused }, rounded(0xFFFFFFFF, dp(40), 0));
        bg.addState(new int[0], rounded(0x33FFFFFF, dp(40), 0));
        b.setBackground(bg);
        b.setTextColor(INK);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                b.setTextColor(focused ? 0xFF14142B : INK);
            }
        });
        return b;
    }

    // ---- 5 GHz ----

    /**
     * 5 GHz mode is the user's choice. It needs USB debugging on the TV and gives this app shell access
     * through it (see prepare5Ghz), so it is off until it is switched on here; off, the app never
     * touches adb and the Wi-Fi Direct group stays on 2.4 GHz (the phone sends 1080p).
     */
    private boolean fiveGhzWanted() {
        return getSharedPreferences("displayreceiver", MODE_PRIVATE).getBoolean("fiveGhz", false);
    }

    // ---- the explanation screens ----

    private FrameLayout guideHost;
    private View guide;

    /** Shows an explanation over the main screen (the main screen stays below, its focus kept). */
    private void showGuide(View v) {
        closeGuide();
        guide = v;
        guideHost.addView(v, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ui.setVisibility(View.INVISIBLE);
        Object first = v.getTag();
        if (first instanceof View) ((View) first).requestFocus();
    }

    private void closeGuide() {
        View g = guide;
        guide = null;
        if (g != null && guideHost != null) {
            guideHost.removeView(g);
            ui.setVisibility(View.VISIBLE);
            fiveButton.requestFocus();
        }
    }

    /** Pressing the 5 GHz button explains the mode first; the change is made from that screen. */
    private void showFiveGuide() {
        showGuide(Guide.five(this, fiveGhzWanted(), new Runnable() {
            @Override public void run() {
                toggleFiveGhz();
                closeGuide();
            }
        }, new Runnable() {
            @Override public void run() { closeGuide(); }
        }));
    }

    @Override public void onBackPressed() {
        if (guide != null) closeGuide();
        else super.onBackPressed();
    }

    private void toggleFiveGhz() {
        boolean on = !fiveGhzWanted();
        getSharedPreferences("displayreceiver", MODE_PRIVATE).edit().putBoolean("fiveGhz", on).apply();
        if (on) {
            // the Wi-Fi restart ends a running search: stop it, set 5 GHz up, search again
            handler.removeCallbacks(rediscover);
            release();
            wifiPrepared = false;
            if (!preparing) prepareWifi();
        } else {
            disarmFiveGhz();
        }
        refresh();
    }

    /**
     * Switches 5 GHz mode off: the saved choice, and the key the app signs in to adb with. Without the
     * key the app can no longer use adb, even though the TV still lists it as allowed. Wi-Fi keeps the
     * country code until the TV restarts; nothing else was changed on the TV.
     */
    private void disarmFiveGhz() {
        SharedPreferences sp = getSharedPreferences("displayreceiver", MODE_PRIVATE);
        if (sp.getBoolean("fiveGhz", false)) sp.edit().putBoolean("fiveGhz", false).apply();
        SelfAdb.deleteKey(new java.io.File(getFilesDir(), "adbkey.pk8"));
        fiveState = 5;
    }

    private volatile boolean wifiPrepared = true, preparing, resumed;
    private volatile int fiveState;              // 0 nothing to say, 1 working, 2 USB debugging off, 3 waiting for "Allow", 4 ready

    /**
     * The TV has no Wi-Fi country code (it has no SIM to take one from), so 5 GHz is passive-scan only
     * and a Wi-Fi Direct group can only start on 2.4 GHz; the phone then offers a lower quality. The
     * shell may send the country the Wi-Fi stack listens for and restart Wi-Fi so it takes effect, and
     * the app can reach the shell through the TV's own adb (loopback address), once USB debugging is on and
     * allowed. The country comes from the TV's language. Runs before the first search, because the
     * Wi-Fi restart ends any search that is already running.
     */
    private void prepareWifi() {
        preparing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try { prepare5Ghz(); }
                catch (Exception e) { note("5 GHz setup: " + e); }
                finally {
                    wifiPrepared = true;
                    preparing = false;
                    if (!resumed) disarmFiveGhz();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (wanted && resumed) start();
                            refresh();
                        }
                    });
                }
            }
        }).start();
    }

    private void prepare5Ghz() throws Exception {
        if (!fiveGhzWanted()) { fiveState = 5; refresh(); return; }
        String cc = CountryCode.normalise(getResources().getConfiguration().getLocales().get(0).getCountry());
        if (cc == null) { note("no country in the TV's language, 5 GHz setup skipped"); return; }
        SelfAdb adb = null;
        try {
            fiveState = 1;
            refresh();
            adb = SelfAdb.connect(new java.io.File(getFilesDir(), "adbkey.pk8"), 45000);
            String now = adb.shell("cmd wifi get-country-code");
            if (now.contains("= " + cc)) { fiveState = 4; note("Wi-Fi country is already " + cc); return; }
            note("setting the Wi-Fi country " + cc);
            adb.shell("am broadcast -a android.telephony.action.NETWORK_COUNTRY_CHANGED"
                    + " --es android.telephony.extra.NETWORK_COUNTRY " + cc.toLowerCase(java.util.Locale.ROOT)
                    + " --ei android.telephony.extra.SLOT_INDEX 0");
            Thread.sleep(2000);
            adb.shell("cmd wifi set-wifi-enabled disabled");
            Thread.sleep(5000);
            adb.shell("cmd wifi set-wifi-enabled enabled");
            for (int i = 0; i < 20; i++) {                       // up to ~40 s for the Wi-Fi to come back
                Thread.sleep(2000);
                if (adb.shell("cmd wifi status").contains("is connected")) break;
            }
            String driver = adb.shell("dumpsys wifi | grep mDriverCountryCode");
            note("Wi-Fi: " + driver);
            fiveState = driver.contains(cc) ? 4 : 0;
        } catch (java.net.ConnectException e) {
            fiveState = 2;                                       // USB debugging is off
            note("5 GHz setup: USB debugging is off");
        } catch (java.net.SocketTimeoutException e) {
            fiveState = 3;                                       // nobody pressed "Allow"
            note("5 GHz setup: not allowed on the TV");
        } finally {
            if (adb != null) adb.close();
        }
    }

    // ---- connecting ----

    /** A Wi-Fi Direct group exists: find the phone's address and connect to its RTSP port. */
    private void onGroup() {
        if (p2p == null || channel == null || sinkBusy) return;
        try {
            p2p.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override public void onConnectionInfoAvailable(final WifiP2pInfo info) {
                    if (!info.groupFormed || sinkBusy) return;
                    sinkBusy = true;
                    refresh();
                    final String go = info.groupOwnerAddress == null ? null : info.groupOwnerAddress.getHostAddress();
                    note("group formed, we are " + (info.isGroupOwner ? "owner" : "client"));
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try { connectToSource(info.isGroupOwner, go); }
                            finally {
                                sinkBusy = false;
                                connecting = false;
                                refresh();
                                // the phone drops the group when it is done: go back to waiting
                                handler.postDelayed(rediscover, 2000);
                            }
                        }
                    }).start();
                }
            });
        } catch (SecurityException ignored) { }
    }

    /** Tries the host with a short timeout; null when nothing listens on the RTSP port. */
    private Socket tryConnect(String host, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, RTSP_PORT), timeoutMs);
            return s;
        } catch (IOException e) {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
            return null;
        }
    }

    /** Asks every address of the group's subnet at once (an absent host needs a second for ARP to give up). */
    private Socket findSource(boolean weAreOwner, String go) {
        if (!weAreOwner && go != null) return tryConnect(go, 1500);
        if (go == null || go.lastIndexOf('.') < 0) return null;
        final String prefix = go.substring(0, go.lastIndexOf('.') + 1);
        final AtomicReference<Socket> found = new AtomicReference<Socket>();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (int i = 2; i < 255; i++) {
            final String host = prefix + i;
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (found.get() != null) return;
                    Socket s = tryConnect(host, 1500);
                    if (s == null) return;
                    if (!found.compareAndSet(null, s)) try { s.close(); } catch (IOException ignored) { }
                }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return found.get();
    }

    /** True once somebody has joined the group this TV owns (waits up to the given time). */
    private boolean waitForClient(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && wanted) {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final boolean[] has = new boolean[1];
            try {
                if (channel == null) return false;
                p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                    @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup g) {
                        has[0] = g != null && g.getClientList() != null && !g.getClientList().isEmpty();
                        done.countDown();
                    }
                });
                done.await(2, TimeUnit.SECONDS);
            } catch (Exception e) { return false; }
            if (has[0]) return true;
            try { Thread.sleep(700); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private void connectToSource(boolean weAreOwner, String go) {
        // our own group can exist long before a phone joins it: wait for a client, then look for it
        if (weAreOwner && !waitForClient(600000)) {
            note("no phone joined the group");
            return;
        }
        connecting = true;
        refresh();
        long end = System.currentTimeMillis() + 28000;
        while (System.currentTimeMillis() < end) {
            Socket s = findSource(weAreOwner, go);
            if (s != null) {
                note("connected to the source");
                RtspSink.Log logger = new RtspSink.Log() {
                    @Override public void note(String m) { MainActivity.this.note(m); }
                };
                for (int w = 0; w < 30 && surface == null; w++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
                TsPlayer player = new TsPlayer(surface, logger, new Runnable() {
                    @Override public void run() { setUiVisible(false); }
                });
                try { new RtspSink(s, logger, player).run(); }
                finally {
                    player.release();
                    setUiVisible(true);
                }
                return;
            }
            note("no RTSP listener yet");
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
        note("gave up: the phone never started listening");
    }

    // ---- Wi-Fi Direct search ----

    private void toggle() {
        if (wanted) {
            wanted = false;
            handler.removeCallbacks(rediscover);
            release();
            disarmFiveGhz();
        } else {
            wanted = true;
            start();
        }
        refresh();
    }

    private void start() {
        if (!wifiPrepared) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQ_LOCATION);
            return;
        }
        if (channel == null) channel = p2p.initialize(this, getMainLooper(), null);
        try {
            // a group left over from an earlier run would keep its old channel: start clean
            p2p.removeGroup(channel, null);
            p2p.requestDeviceInfo(channel, new WifiP2pManager.DeviceInfoListener() {
                @Override public void onDeviceInfoAvailable(WifiP2pDevice d) {
                    if (d != null && d.deviceName != null && !d.deviceName.isEmpty()) {
                        deviceName = d.deviceName;
                        refresh();
                    }
                }
            });
            p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    refresh();
                    // Android ends a search after about two minutes: start it again while we wait
                    handler.removeCallbacks(rediscover);
                    handler.postDelayed(rediscover, 60000);
                }
                @Override public void onFailure(int reason) {
                    Toast.makeText(MainActivity.this, getString(R.string.failed, reason), Toast.LENGTH_LONG).show();
                }
            });
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.no_permission, Toast.LENGTH_LONG).show();
        }
    }


    private void release() {
        if (channel == null) return;
        try {
            p2p.stopPeerDiscovery(channel, null);
            p2p.removeGroup(channel, null);
        } catch (SecurityException ignored) { }
        channel.close();
        channel = null;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != REQ_LOCATION) return;
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            wanted = true;
            start();
            refresh();
        } else {
            wanted = false;
            Toast.makeText(this, R.string.no_permission, Toast.LENGTH_LONG).show();
            refresh();
        }
    }

    // ---- screen ----

    private void refresh() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (statusText == null) return;
                int text, color;
                if (!wanted) { text = R.string.status_off; color = INK_FAINT; }
                else if (connecting) { text = R.string.status_connecting; color = BUSY_AMBER; }
                else { text = R.string.status_wait; color = OK_GREEN; }
                statusText.setText(text);
                int five = fiveState;
                fiveText.setVisibility(five == 0 ? View.GONE : View.VISIBLE);
                fiveText.setText(five == 1 ? R.string.five_wait : five == 2 ? R.string.five_usb : five == 3 ? R.string.five_allow : five == 5 ? R.string.five_off : R.string.five_ok);
                fiveButton.setText(fiveGhzWanted() ? R.string.btn_5g_on : R.string.btn_5g_off);
                fiveText.setTextColor(five == 4 ? OK_GREEN : five == 5 ? INK_FAINT : BUSY_AMBER);
                statusDot.setBackground(rounded(color, dp(6), 0));
                button.setText(wanted ? R.string.btn_stop : R.string.btn_start);
                if (button.isEnabled()) {
                    nameText.setText(deviceName.isEmpty() ? getString(R.string.tv_name_unknown)
                            : getString(R.string.tv_name, deviceName));
                }
            }
        });
    }

    private void setUiVisible(final boolean visible) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!visible) closeGuide();
                ui.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void note(String s) {
        Log.i(TAG, s);
    }

    static GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeColor != 0) g.setStroke(2, strokeColor);
        return g;
    }

    TextView text(int sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    /** Layout with a top margin: centred, or full width (for text that is left-aligned and wraps). */
    LinearLayout.LayoutParams margin(int topDp, boolean fullWidth) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                fullWidth ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        if (!fullWidth) p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private LinearLayout.LayoutParams margin(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
