/*
  Optical Heart Rate Detection (PBA Algorithm) using the MAX30105 Breakout
  By: Nathan Seidle @ SparkFun Electronics
  Date: October 2nd, 2016
  https://github.com/sparkfun/MAX30105_Breakout

  This is a demo to show the reading of heart rate or beats per minute (BPM) using
  a Penpheral Beat Amplitude (PBA) algorithm.

  It is best to attach the sensor to your finger using a rubber band or other tightening
  device. Humans are generally bad at applying constant pressure to a thing. When you
  press your finger against the sensor it varies enough to cause the blood in your
  finger to flow differently which causes the sensor readings to go wonky.

  Hardware Connections (Breakoutboard to Arduino):
  -5V = 5V (3.3V is allowed)
  -GND = GND
  -SDA = A4 (or SDA)
  -SCL = A5 (or SCL)
  -INT = Not connected

  The MAX30105 Breakout can handle 5V or 3.3V I2C logic. We recommend powering the board with 5V
  but it will also run at 3.3V.
*/

#include <Wire.h>
#include "MAX30105.h"
#include <ArduinoBLE.h>
#include "heartRate.h"

MAX30105 particleSensor;

const byte RATE_SIZE = 4; //Increase this for more averaging. 4 is good.
byte rates[RATE_SIZE]; //Array of heart rates
byte rateSpot = 0;
long lastBeat = 0; //Time at which the last beat occurred

float beatsPerMinute;
int beatAvg;

BLEService heartRateService("180D");
BLELongCharacteristic heartRateChar("2A37", BLERead | BLENotify);

void setup()
{
  Serial.begin(115200);
  Serial.println("Initializing...");

  // Initialize sensor
  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) //Use default I2C port, 400kHz speed
  {
    Serial.println("MAX30105 was not found. Please check wiring/power. ");
    while (1);
  }
  Serial.println("Place your index finger on the sensor with steady pressure.");

  // Initialize BLE
  if (!BLE.begin()) 
  {
    Serial.println("Starting BLE failed!");
    while(1);
  }
  BLE.setLocalName("HeartRateSensor");
  BLE.setAdvertisedService(heartRateService);
  heartRateService.addCharacteristic(heartRateChar);
  BLE.addService(heartRateService);
  heartRateChar.writeValue(0);  // Initial heart rate value
  BLE.advertise();

  particleSensor.setup(); //Configure sensor with default settings
  particleSensor.setPulseAmplitudeRed(0x0A); //Turn Red LED to low to indicate sensor is running
  particleSensor.setPulseAmplitudeGreen(0); //Turn off Green LED
}

void loop()
{
  BLEDevice central = BLE.central();  // Wait for a BLE central to connect

  // If device is connected, read the sensor and send data
  if (central) 
  {
    Serial.print("Connected to central: ");
    Serial.println(central.address());

    while (central.connected()) 
    {
      long irValue = particleSensor.getIR();
      heartRateChar.writeValue(irValue);  // Send heart rate over BLE

      Serial.print("IR=");
      Serial.print(irValue);
      Serial.println("\n");
      delay(10);  // Update every 10 ms
    }

    Serial.print("Disconnected from central: ");
    Serial.println(central.address());
  }
}
