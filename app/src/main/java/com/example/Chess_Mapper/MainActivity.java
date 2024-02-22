package com.example.PPGAuth;

import java.util.Arrays;
import android.os.Bundle;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.ui.AppBarConfiguration;
import com.example.PPGAuth.databinding.ActivityMainBinding;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.Vibrator;

import com.example.Chess_Mapper.BluetoothLeService;
import com.example.Chess_Mapper.CycleGen;

import java.io.IOException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.List;
import java.util.ArrayDeque;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import android.os.IBinder;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;


public class MainActivity extends AppCompatActivity {

    private final static String TAG = MainActivity.class.getSimpleName();

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    // GUI Components
    private GraphView graphView;
    private TextView mBluetoothStatus;
    private TextView mVerifyStatus;
    private Button mScanBtn;
    private Button mOffBtn;
    private Button mDiscoverBtn;
    private Button mVerifyBtn;
    private Button mCaptureBtn;
    private ListView mDevicesListView;

    private ArrayAdapter<String> mBTArrayAdapter;

    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names

    public final static String ENABLE_BLE =
            "com.example.mainactivity.cyclegen.ENABLE_BLE";
    public final static String DISABLE_BLE =
            "com.example.mainactivity.cyclegen.DISABLE_BLE";
    public final static String DISCOVER_DEVICES =
            "com.example.mainactivity.cyclegen.DISCOVER_DEVICES";
    public final static String DEVICE_SELECTED =
            "com.example.mainactivity.cyclegen.DEVICE_SELECTED";
    public final static String CAPTURE_CYCLES =
            "com.example.mainactivity.cyclegen.CAPTURE_CYCLES";
    public final static String VERIFY =
            "com.example.mainactivity.cyclegen.VERIFY";
    public final static String GET_CURRENT_STATE =
            "com.example.mainactivity.cyclegen.GET_CURRENT_STATE";

    public final static String NAME_DATA =
            "com.example.mainactivity.cyclegen.NAME_DATA";
    public final static String ADDRESS_DATA =
            "com.example.mainactivity.cyclegen.ADDRESS_DATA";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        graphView = findViewById(R.id.graph);
        mBluetoothStatus = (TextView)findViewById(R.id.bluetooth_status);
        mVerifyStatus = (TextView)findViewById(R.id.verification_status);
        mScanBtn = (Button)findViewById(R.id.scan);
        mOffBtn = (Button)findViewById(R.id.off);
        mDiscoverBtn = (Button)findViewById(R.id.discover);
        mVerifyBtn = (Button)findViewById(R.id.verify);
        mCaptureBtn = (Button)findViewById(R.id.capture);

        graphView.setTitle("My data");

        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);

        mDevicesListView = (ListView)findViewById(R.id.devices_list_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        Intent cycleGenIntent = new Intent(this, CycleGen.class);
        startService(cycleGenIntent);

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

        mVerifyBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                verify();
            }
        });

        mCaptureBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                capture();
            }
        });
    }

    private final BroadcastReceiver mCycleGenUpdateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "enter BroadcastReceiver");
            final String action = intent.getAction();
            Log.d(TAG, "action = " + action);
            if (CycleGen.BLUETOOTH_ENABLED.equals(action)) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                mBluetoothStatus.setText(getString(R.string.BTEnable));
            } else if (CycleGen.BLUETOOTH_DISABLED.equals(action)) {
                mBluetoothStatus.setText(getString(R.string.sBTdisabl));
            } else if (CycleGen.DEVICE_FOUND.equals(action)) {
                String name = intent.getStringExtra(CycleGen.NAME_DATA);
                String address = intent.getStringExtra(CycleGen.ADDRESS_DATA);
                mBTArrayAdapter.add(name + "\n" + address);
                mBTArrayAdapter.notifyDataSetChanged();
            } else if (CycleGen.CONNECTION_SUCCESS.equals(action)) {
                mBTArrayAdapter.clear(); // clear items
                String name = intent.getStringExtra(CycleGen.NAME_DATA);
                mBluetoothStatus.setText(getString(R.string.BTConnected) + " " + name);
            } else if (CycleGen.CONNECTION_FAILED.equals(action)) {
                mBluetoothStatus.setText(getString(R.string.BTconnFail));
            } else if (CycleGen.DISCONNECTED.equals(action)) {
                mBluetoothStatus.setText(getString(R.string.sEnabled));
            } else if (CycleGen.NEW_CYCLE.equals(action)) {
                float[] data = (float[]) intent.getSerializableExtra(CycleGen.EXTRA_FLOAT_ARRAY);
                Log.e(TAG, Arrays.toString(data));
                drawGraph(data);
            } else if (CycleGen.VERIFICATION_FAILED.equals(action)) {
                mVerifyStatus.setText(getString(R.string.noData));
            } else if (CycleGen.VERIFICATION_SUCCESS.equals(action)) {
                String result = intent.getStringExtra(CycleGen.EXTRA_DATA);
                mVerifyStatus.setText(result);
            }
        }
    };

    public void verify() {
        broadcastUpdate(VERIFY);
    }

    public void capture() {
        broadcastUpdate(CAPTURE_CYCLES);
    }

    public void drawGraph(float[] sampleCycle) {
        LineGraphSeries<DataPoint> series = new LineGraphSeries<>();
        int i = 0;
        for (Float value : sampleCycle) {
            DataPoint point = new DataPoint(i++, value);
            series.appendData(point, true, sampleCycle.length);
        }
        graphView.removeAllSeries();
        graphView.addSeries(series);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mCycleGenUpdateReceiver, cycleGenUpdateIntentFilter());
        broadcastUpdate(GET_CURRENT_STATE);
        Log.e(TAG, "RESUMING ACTIVITY");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "DESTROYING ACTIVITY");
    }

    private static IntentFilter cycleGenUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(CycleGen.BLUETOOTH_ENABLED);
        intentFilter.addAction(CycleGen.BLUETOOTH_DISABLED);
        intentFilter.addAction(CycleGen.DEVICE_FOUND);
        intentFilter.addAction(CycleGen.CONNECTION_SUCCESS);
        intentFilter.addAction(CycleGen.CONNECTION_FAILED);
        intentFilter.addAction(CycleGen.DISCONNECTED);
        intentFilter.addAction(CycleGen.NEW_CYCLE);
        intentFilter.addAction(CycleGen.VERIFICATION_FAILED);
        intentFilter.addAction(CycleGen.VERIFICATION_SUCCESS);

        return intentFilter;
    }

    private void bluetoothOn(){
        broadcastUpdate(ENABLE_BLE);
    }

    private void bluetoothOff(){
        broadcastUpdate(DISABLE_BLE);
    }

    private void discover(){
        // Check if the device is already discovering
        mBTArrayAdapter.clear();
        broadcastUpdate(DISCOVER_DEVICES);
    }

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

    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            mBluetoothStatus.setText(getString(R.string.cConnet));
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0,info.length() - 17);
            Log.i("Main: ", address);
            broadcastUpdate(DEVICE_SELECTED, name, address);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, String name, String address) {
        final Intent intent = new Intent(action);
        intent.putExtra(NAME_DATA, name);
        intent.putExtra(ADDRESS_DATA, address);
        sendBroadcast(intent);
    }

}