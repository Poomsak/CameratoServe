package ps.ex.lenovo.cmererveatosa;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private SurfaceHolder holder;
    private Camera camera;
    private boolean previewRunning = false;
    private int camId = 0;
    private ByteArrayOutputStream previewStream = new ByteArrayOutputStream();
    private int rotationSteps = 0;
    private boolean aboveLockScreen = true;

    private static PjpegServer pjpegServer = new PjpegServer();
    private static Thread serverThread = new Thread(pjpegServer);
    private static HashMap<Integer, List<Camera.Size>> cameraSizes = new HashMap<>();
    private static ReentrantReadWriteLock frameLock = new ReentrantReadWriteLock();
    private static byte[] jpegFrame;
    private Camera mCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cacheResolutions();

        SurfaceView cameraView = (SurfaceView) findViewById(R.id.surfaceView);
        holder = cameraView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        Button btnIP = findViewById(R.id.btnIp);
        final TextView txtViwew = findViewById(R.id.textIp);
        btnIP.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.e("MainActivity", "Ip Mobile "+getMobileIP());
                txtViwew.setText("Ip Mobile "+getMobileIP() +" : "+" ip Wifi "+getIpWifi());
            }
        });

    }

    public String getIpWifi(){
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        return Formatter.formatIpAddress(ip);
    }
    @Override
    protected void onResume() {
        super.onResume();

        loadPreferences();

        openCamAndPreview();

        if (!serverThread.isAlive()) serverThread.start();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        if (aboveLockScreen)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }
    private void openCamAndPreview() {
        try {
            if (camera == null) camera = Camera.open(camId);
            startPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void loadPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        camId = Integer.parseInt(preferences.getString("cam", "0"));
        Integer rotDegrees = Integer.parseInt(preferences.getString("rotation", "0"));
        rotationSteps = rotDegrees / 90;
        Integer port = Integer.parseInt(preferences.getString("port", "8080"));
        PjpegServer.setPort(port);
        aboveLockScreen = preferences.getBoolean("above_lock_screen", aboveLockScreen);
        Boolean allIps = preferences.getBoolean("allow_all_ips", false);
        PjpegServer.setAllIpsAllowed(allIps);
    }

    private void startPreview() {
        if (previewRunning) stopPreview();

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        if (display.getRotation() == Surface.ROTATION_0) {
            camera.setDisplayOrientation(90);
        } else if (display.getRotation() == Surface.ROTATION_270) {
            camera.setDisplayOrientation(180);
        } else {
            camera.setDisplayOrientation(0);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String res = preferences.getString("resolution", "640x480");
        String[] resParts = res.split("x");

        Camera.Parameters p = camera.getParameters();
        p.setPreviewSize(Integer.parseInt(resParts[0]), Integer.parseInt(resParts[1]));
        camera.setParameters(p);

        try {
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
        } catch (IOException e) {
            e.printStackTrace();
        }

        camera.startPreview();

        holder.addCallback(this);

        previewRunning = true;
    }
    private void stopPreview() {
        if (!previewRunning) return;

        holder.removeCallback(this);
        camera.stopPreview();
        camera.setPreviewCallback(null);

        previewRunning = false;
    }
    public static byte[] getJpegFrame() {
        try {
            frameLock.readLock().lock();
            return jpegFrame;
        } finally {
            frameLock.readLock().unlock();
        }
    }

    private void cacheResolutions() {
        int cams = Camera.getNumberOfCameras();
        for (int i = 0; i < cams; i++) {
            Camera cam = Camera.open(i);
            Camera.Parameters params = cam.getParameters();
            cameraSizes.put(i, params.getSupportedPreviewSizes());
            cam.release();
        }
    }
    private static void setJpegFrame(ByteArrayOutputStream stream) {
        try {
            frameLock.writeLock().lock();
            jpegFrame = stream.toByteArray();
        } finally {
            frameLock.writeLock().unlock();
        }
    }
    public static String getMobileIP() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = (NetworkInterface) en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        String ipaddress = inetAddress .getHostAddress().toString();
                        return ipaddress;
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e("MainActivity", "Exception in Get IP Address: " + ex.toString());
        }
        return null;
    }

    public String getIpAddress() {
        String ip = "";
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface
                    .getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces
                        .nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface
                        .getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress
                            .nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        ip += "Server running at : "
                                + inetAddress.getHostAddress();
                    }
                }
            }

        } catch (SocketException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            ip += "Something Wrong! " + e.toString() + "\n";
        }
        return ip;
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {

        previewStream.reset();
        Camera.Parameters p = camera.getParameters();

        int previewHeight = p.getPreviewSize().height,
                previewWidth = p.getPreviewSize().width;

        switch(rotationSteps) {
            case 1:
                bytes = Rotator.rotateYUV420Degree90(bytes, previewWidth, previewHeight);
                break;
            case 2:
                bytes = Rotator.rotateYUV420Degree180(bytes, previewWidth, previewHeight);
                break;
            case 3:
                bytes = Rotator.rotateYUV420Degree270(bytes, previewWidth, previewHeight);
                break;
        }

        if (rotationSteps == 1 || rotationSteps == 3) {
            int tmp = previewHeight;
            previewHeight = previewWidth;
            previewWidth = tmp;
        }

        int format = p.getPreviewFormat();
        new YuvImage(bytes, format, previewWidth, previewHeight, null)
                .compressToJpeg(new Rect(0, 0, previewWidth, previewHeight),
                        100, previewStream);

        setJpegFrame(previewStream);
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {

        stopPreview();
        if (camera != null) camera.release();
        camera = null;
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        openCamAndPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

        stopPreview();
        if (camera != null) camera.release();
        camera = null;

    }
}
