package com.example.PPGAuth;

import android.os.Bundle;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.ui.AppBarConfiguration;
import com.example.PPGAuth.databinding.ActivityMainBinding;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ServiceConnection;
import android.content.ComponentName;

import com.example.Chess_Mapper.BluetoothLeService;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.List;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import android.app.Service;
//import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
//import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;


public class MainActivity extends AppCompatActivity {

    private final static String TAG = MainActivity.class.getSimpleName();



    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    // GUI Components
    private GraphView graphView;
    private TextView mBluetoothStatus;
    private Button mScanBtn;
    private Button mOffBtn;
    private Button mDiscoverBtn;
    private ListView mDevicesListView;

    int maxIrValCapacity = 100;
    ArrayDeque<Integer> IrValDeque = new ArrayDeque<>(maxIrValCapacity);

    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;

    private BluetoothLeService mBluetoothLeService;

    private final int INTERVAL = 20; // Interval in milliseconds
    private Handler mHandler = new Handler();
    private Runnable mRunnable;

    Python py;
    PyObject module;



    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names
    public final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // used in bluetooth handler to identify message status

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        graphView = findViewById(R.id.graph);
        mBluetoothStatus = (TextView)findViewById(R.id.bluetooth_status);
        mScanBtn = (Button)findViewById(R.id.scan);
        mOffBtn = (Button)findViewById(R.id.off);
        mDiscoverBtn = (Button)findViewById(R.id.discover);

        graphView.setTitle("My data");

        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        mDevicesListView = (ListView)findViewById(R.id.devices_list_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        if(!Python.isStarted()){
            Python.start(new AndroidPlatform(this));
        }

        // 2. Obtain the python instance
        py = Python.getInstance();
        module = py.getModule("preproc");

        mRunnable = new Runnable() {
            @Override
            public void run() {
                // Call your method here
                readDataFromSensor();

                // Schedule the method to be called again after the interval
                mHandler.postDelayed(this, INTERVAL);
            }
        };

        if (mBTAdapter == null) {
            // Device does not support Bluetooth
            mBluetoothStatus.setText(getString(R.string.sBTstaNF));
            Toast.makeText(getApplicationContext(),getString(R.string.sBTdevNF),Toast.LENGTH_SHORT).show();
        }
        else {

            mScanBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    bluetoothOn();
                }
            });

            mOffBtn.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v){
                    bluetoothOff();
                }
            });

            mDiscoverBtn.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v){
                    discover();
                }
            });
        }
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

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName,
                                       IBinder service) {
            Log.d("Main: ", "start service Connection");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            mBluetoothLeService.initialize(mBTAdapter);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d("Main: ", "end Service Connection");
            mBluetoothLeService = null;
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
                mHandler.removeCallbacks(mRunnable);
                mBluetoothStatus.setText(getString(R.string.sEnabled));
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.d(TAG, "receive data");
                byte[] value = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
                if (value != null) {
                    IrValDeque.addLast(toInt(value));
                    if (IrValDeque.size() > maxIrValCapacity) {
                        IrValDeque.removeFirst();
                    }
                    drawGraph();
                }
            }
        }
    };

    static int toInt(byte[] bytes)
    {
        int intValue = ((bytes[3] & 0xFF) << 24) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[1] & 0xFF) << 8) |
                (bytes[0] & 0xFF);

        return intValue;
    }

    public void drawGraph() {
        if(IrValDeque.size() > 0) {
            LineGraphSeries<DataPoint> series = new LineGraphSeries<>();
            int i = 0;
            for (Integer value : IrValDeque) {
                DataPoint point = new DataPoint(i++, value);
                series.appendData(point, true, IrValDeque.size());
            }
            graphView.removeAllSeries();
            graphView.addSeries(series);

        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mBluetoothLeService.disconnect();
        mBluetoothLeService.close();
        mHandler.removeCallbacks(mRunnable);

        System.exit(0);
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private void bluetoothOn(){
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothStatus.setText(getString(R.string.BTEnable));
            Toast.makeText(getApplicationContext(),getString(R.string.sBTturON),Toast.LENGTH_SHORT).show();

        }
        else{
            Toast.makeText(getApplicationContext(),getString(R.string.BTisON), Toast.LENGTH_SHORT).show();
        }
    }

    // Enter here after user selects "yes" or "no" to enabling radio
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent Data){
        // Check which request we're responding to
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected.
                mBluetoothStatus.setText(getString(R.string.sEnabled));
            }
            else
                mBluetoothStatus.setText(getString(R.string.sDisabled));
        }
    }

    private void bluetoothOff(){
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText(getString(R.string.sBTdisabl));
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
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), getString(R.string.DisStart), Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
            else{
                Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
            }
        }
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // add the name to the list
                if (device.getName() != null) {
                    mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                    mBTArrayAdapter.notifyDataSetChanged();
                }
            }
        }
    };


    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            if(!mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText(getString(R.string.cConnet));
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0,info.length() - 17);
            Log.i("Main: ", address);

            boolean status = mBluetoothLeService.connect(address);
            if(status == true) {
                mBluetoothStatus.setText(getString(R.string.BTConnected) + " " + name);
                if(mBTAdapter.isDiscovering()){
                    mBTAdapter.cancelDiscovery();
                    Toast.makeText(getApplicationContext(),getString(R.string.DisStop),Toast.LENGTH_SHORT).show();
                    mBTArrayAdapter.clear(); // clear items
                }
            } else mBluetoothStatus.setText(getString(R.string.BTconnFail));
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        }

        return super.onOptionsItemSelected(item);
    }

}