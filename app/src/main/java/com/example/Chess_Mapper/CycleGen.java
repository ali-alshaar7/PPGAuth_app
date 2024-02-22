package com.example.Chess_Mapper;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.example.PPGAuth.MainActivity;
import com.example.PPGAuth.R;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

public class CycleGen extends Service {

    private final static String TAG = "CycleGen: ";

    private static final int NOTIF_ID = 1;
    private static final String NOTIF_CHANNEL_ID = "Channel_Id";

    private NotificationManager manager;
    private NotificationCompat.Builder mNotifBuilder;

    private BluetoothLeService mBluetoothLeService;
    private BluetoothAdapter mBTAdapter;

    public final static String BLUETOOTH_ENABLED =
            "com.example.cyclegen.BLUETOOTH_ENABLED";
    public final static String BLUETOOTH_DISABLED =
            "com.example.cyclegen.BLUETOOTH_DISABLED";
    public final static String DEVICE_FOUND =
            "com.example.cyclegen.DEVICE_FOUND";
    public final static String CONNECTION_FAILED =
            "com.example.cyclegen.CONNECTION_FAILED";
    public final static String CONNECTION_SUCCESS =
            "com.example.cyclegen.CONNECTION_SUCCESS";
    public final static String DISCONNECTED =
            "com.example.cyclegen.DISCONNECTED";
    public final static String NEW_CYCLE =
            "com.example.cyclegen.NEW_CYCLE";
    public final static String VERIFICATION_FAILED =
            "com.example.cyclegen.VERIFICATION_FAILED";
    public final static String VERIFICATION_SUCCESS =
            "com.example.cyclegen.VERIFICATION_SUCCESS";

    public final static String NAME_DATA =
            "com.example.cyclegen.NAME_DATA";
    public final static String ADDRESS_DATA =
            "com.example.cyclegen.ADDRESS_DATA";
    public final static String EXTRA_DATA =
            "com.example.cyclegen.EXTRA_DATA";
    public final static String EXTRA_FLOAT_ARRAY =
            "com.example.cyclegen.EXTRA_FLOAT_ARRAY";

    int maxIrValCapacity = 100;
    int countCycle = 100;
    ArrayDeque<Integer> IrValDeque = new ArrayDeque<>(maxIrValCapacity);
    float[] curCycle = new float[0];

    private boolean captureFlag = false;
    private int numCaptured = 0;
    float[][] cycleArray = new float[5][50];

    private final int INTERVAL = 10; // Interval in milliseconds
    private Handler mHandler = new Handler();
    private Runnable mRunnable;

    private String curName;
    private String curAddress;

    Python py;
    PyObject module;
    Module verifModel;

    public CycleGen() {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground();

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        if (mBTAdapter == null) {
            // Device does not support Bluetooth
            Toast.makeText(getApplicationContext(),getString(R.string.sBTdevNF),Toast.LENGTH_SHORT).show();
        }

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        mRunnable = new Runnable() {
            @Override
            public void run() {
                // Call your method here
                readDataFromSensor();

                // Schedule the method to be called again after the interval
                mHandler.postDelayed(this, INTERVAL);
            }
        };

        if(!Python.isStarted()){
            Python.start(new AndroidPlatform(this));
        }

        // 2. Obtain the python instance
        py = Python.getInstance();
        module = py.getModule("preproc");

        try {
            String modelPath = assetFilePath(this, "droid_model.pt");
            Log.e("PytorchHelloWorld", modelPath);
            verifModel = LiteModuleLoader.load(modelPath);
        } catch (IOException e) {
            Log.e("PytorchHelloWorld", "Error reading assets", e);
        }

        return START_STICKY;
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // add the name to the list
                if (device.getName() != null) {
                    broadcastUpdate(DEVICE_FOUND, device.getName(), device.getAddress());
                }
            }
        }
    };

    private final BroadcastReceiver mActivityReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "enter BroadcastReceiver");
            final String action = intent.getAction();
            Log.d(TAG, "action = " + action);
            if (MainActivity.ENABLE_BLE.equals(action)) {
                bluetoothOn();
            } else if (MainActivity.DISABLE_BLE.equals(action)) {
                bluetoothOff();
            } else if (MainActivity.DISCOVER_DEVICES.equals(action)) {
                discover();
            } else if (MainActivity.DEVICE_SELECTED.equals(action)) {
                String name = intent.getStringExtra(MainActivity.NAME_DATA);
                String address = intent.getStringExtra(MainActivity.ADDRESS_DATA);
                connectDevice(name, address);
            } else if (MainActivity.CAPTURE_CYCLES.equals(action)) {
                captureFlag = true;
                numCaptured = 0;
            } else if (MainActivity.VERIFY.equals(action)) {
                verify();
            } else if (MainActivity.GET_CURRENT_STATE.equals(action)) {
                if (curName != null) broadcastUpdate(CONNECTION_SUCCESS, curName, curAddress);
                else if (mBTAdapter.isEnabled()) broadcastUpdate(BLUETOOTH_ENABLED);
                else if (!mBTAdapter.isEnabled()) broadcastUpdate(BLUETOOTH_DISABLED);

                verify();
            }
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "enter BroadcastReceiver");
            final String action = intent.getAction();
            Log.d(TAG, "action = " + action);
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mHandler.post(mRunnable);
            } else if(BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                curName = null;
                curAddress = null;
                mHandler.removeCallbacks(mRunnable);
                broadcastUpdate(DISCONNECTED);
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.d(TAG, "receive data");
                byte[] value = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                if (value != null) {
                    IrValDeque.addLast(toInt(value));
                    if (IrValDeque.size() > maxIrValCapacity) {
                        IrValDeque.removeFirst();
                    }
                    processCycle();
                }
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private static IntentFilter mainActivityUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(MainActivity.ENABLE_BLE);
        intentFilter.addAction(MainActivity.DISABLE_BLE);
        intentFilter.addAction(MainActivity.DEVICE_SELECTED);
        intentFilter.addAction(MainActivity.DISCOVER_DEVICES);
        intentFilter.addAction(MainActivity.CAPTURE_CYCLES);
        intentFilter.addAction(MainActivity.VERIFY);
        intentFilter.addAction(MainActivity.GET_CURRENT_STATE);
        return intentFilter;
    }

    private void vibrate(long durationMillis) {
        // Get the Vibrator service
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Check if the device has a vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            // Vibrate for the specified duration
            vibrator.vibrate(durationMillis);
        }
    }

    public void processCycle() {
        if (countCycle != 0) {
            countCycle--;
            return;
        }
        countCycle = 100;

        int[] intArray = IrValDeque.stream().mapToInt(Integer::intValue).toArray();
        PyObject cycleObj = module.callAttr("sig_preproc", intArray);
        if (cycleObj.toString().length() < 4) return;
        float[] sampleCycle = cycleObj.toJava(float[].class);
        if (!validateCycle(sampleCycle)) return;

        curCycle = sampleCycle;
        broadcastUpdate(NEW_CYCLE, sampleCycle);

        if (captureFlag) {
            cycleArray[numCaptured++] = sampleCycle;
            if (numCaptured == 5) {
                Toast.makeText(getApplicationContext(),getString(R.string.allCaptured),Toast.LENGTH_SHORT).show();
                captureFlag = false;
                vibrate(100);
            } else Toast.makeText(getApplicationContext(),Integer.toString(numCaptured)+getString(R.string.nCaptured),Toast.LENGTH_SHORT).show();
        }

    }

    private void bluetoothOn(){
        if (!mBTAdapter.isEnabled()) {
            broadcastUpdate(BLUETOOTH_ENABLED);
            Toast.makeText(getApplicationContext(),getString(R.string.sBTturON),Toast.LENGTH_SHORT).show();
        }
        else{
            Toast.makeText(getApplicationContext(),getString(R.string.BTisON), Toast.LENGTH_SHORT).show();
        }
    }

    private void bluetoothOff(){
        mBTAdapter.disable(); // turn off
        broadcastUpdate(BLUETOOTH_DISABLED);
        Toast.makeText(getApplicationContext(),"Bluetooth turned Off", Toast.LENGTH_SHORT).show();
    }

    private void discover(){
        // Check if the device is already discovering
        if(mBTAdapter.isDiscovering()){
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(),getString(R.string.DisStop),Toast.LENGTH_SHORT).show();
        }
        else{
            if(mBTAdapter.isEnabled()) {
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), getString(R.string.DisStart), Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
            else{
                Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void verify() {
        if (curCycle == null || curCycle.length == 0) {
            broadcastUpdate(VERIFICATION_FAILED);
            return;
        }
        Tensor cycleTensor1 = floatArrayToTensor(curCycle);
        int avgOut = 0;

        for (float[] cycleArr : cycleArray) {
            if (cycleArr.length == 0) {
                broadcastUpdate(VERIFICATION_FAILED);
                return;
            }
            Tensor cycleTensor2 = floatArrayToTensor(cycleArr);
            final Tensor outputTensor = verifModel.forward(IValue.from(cycleTensor1), IValue.from(cycleTensor2)).toTensor();
            final float[] scores = outputTensor.getDataAsFloatArray();
            avgOut += scores[0] >= 0.5 ? 1 : 0;
        }
        broadcastUpdate(VERIFICATION_SUCCESS, Boolean.toString(avgOut >= 3));
        Log.d("DRAWER", "verification over");
    }

    private void connectDevice(String name, String address) {

        if(!mBTAdapter.isEnabled()) {
            broadcastUpdate(CONNECTION_FAILED);
            Toast.makeText(getBaseContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
            return;
        }

        boolean status = mBluetoothLeService.connect(address);
        if(status == true) {
            Log.d(TAG, "connect success");
            curName = name;
            curAddress = address;
            broadcastUpdate(CONNECTION_SUCCESS, name, address);
            if(mBTAdapter.isDiscovering()){
                mBTAdapter.cancelDiscovery();
                Toast.makeText(getApplicationContext(),getString(R.string.DisStop),Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.d(TAG, "connect failed" + name + address);
            broadcastUpdate(CONNECTION_FAILED);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(mActivityReceiver, mainActivityUpdateIntentFilter());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.e(TAG, "DESTROYING SERVICE");
        Toast.makeText(getApplicationContext(),"Service Destroyed",Toast.LENGTH_SHORT).show();

        mBluetoothLeService.disconnect();
        mBluetoothLeService.close();
        mHandler.removeCallbacks(mRunnable);
        System.exit(0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, float[] array) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRA_FLOAT_ARRAY, array);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, String data) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRA_DATA, data);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, String name, String address) {
        final Intent intent = new Intent(action);
        intent.putExtra(NAME_DATA, name);
        intent.putExtra(ADDRESS_DATA, address);
        sendBroadcast(intent);
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName,
                                       IBinder service) {
            Log.d(TAG, "start service Connection");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            mBluetoothLeService.initialize(mBTAdapter);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG, "end Service Connection");
            mBluetoothLeService = null;
        }
    };

    private void startForeground() {
        NotificationChannel chan = new NotificationChannel(
                NOTIF_CHANNEL_ID,
                "PPGAuth Service",
                NotificationManager.IMPORTANCE_LOW);
        manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        Intent notificationIntent = new Intent(this, MainActivity.class);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        mNotifBuilder =  new NotificationCompat.Builder(this,
                NOTIF_CHANNEL_ID) // don't forget create a notification channel first
                .setOngoing(true)
                .setContentText("PPGAuth service is running background")
                .setContentIntent(pendingIntent);

        startForeground(NOTIF_ID, mNotifBuilder.build());
    }

    public static Tensor floatArrayToTensor(float[] floatArray) {
        // Create a PyTorch Tensor from the float array
        return Tensor.fromBlob(floatArray, new long[]{floatArray.length});
    }

    private void readDataFromSensor() {
        Log.d(TAG, "Reading from sensor = ");
        if (mBluetoothLeService != null) {
            List<BluetoothGattService> services = mBluetoothLeService.getSupportedGattServices();
            if (services == null) return;
            for (BluetoothGattService gattService : services) {
                if (!mBluetoothLeService.UUID_BLE_RX.equals(gattService.getUuid())) continue;
                List<BluetoothGattCharacteristic> gattCharacteristics =
                        gattService.getCharacteristics();
                if (gattCharacteristics == null) return;
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    if (!mBluetoothLeService.UUID_SENS_RX.equals(gattCharacteristic.getUuid())) continue;
                    mBluetoothLeService.readCharacteristic(gattCharacteristic);
                }
            }
        }
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    static int toInt(byte[] bytes)
    {
        int intValue = ((bytes[3] & 0xFF) << 24) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[1] & 0xFF) << 8) |
                (bytes[0] & 0xFF);

        return intValue;
    }

    private static float findMinValue(float[] array) {
        float min = Float.POSITIVE_INFINITY;
        for (float value : array) {
            min = Math.min(min, value);
        }
        return min;
    }

    private static float findMaxValue(float[] array) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : array) {
            max = Math.max(max, value);
        }
        return max;
    }

    public static boolean validateCycle(float[] cycle) {
        float minValue = findMinValue(cycle);
        float maxValue = findMaxValue(cycle);

        return ((minValue > -1000 && minValue < -50) && (maxValue > 50 && maxValue < 1000));
    }
}