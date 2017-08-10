package jp.araobp.iot.sensor_network

import android.util.Log
import kotlin.concurrent.thread

class DriverSimulatorServiceImpl : SensorNetworkService() {

    private var mValue = 300
    private var mSleep = TIMER * mValue

    private var mDriverStatus = DriverStatus(opened = false, started = false)

    override fun onCreate() {
        super.onCreate()

        mSleep = TIMER * mValue
        try {
            thread {
                while (true) {
                    if (mDriverStatus.opened && mDriverStatus.started) {
                        try {
                            Thread.sleep(mSleep.toLong())
                        } catch (e: InterruptedException) {
                            Log.e(TAG, e.toString())
                        }
                        rx("%19:FLOAT:-0.01,0.03,-0.01")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun open(baudrate: Int): Boolean {
        mDriverStatus.opened = true
        return true
    }

    override fun tx(message: String) {
        if (mDriverStatus.opened) {
            rx("#" + message)
            val cmd = message.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            when (cmd[0]) {
                SensorNetworkProtocol.WHO -> rx("$:WHO:${DEVICE_NAME}")
                SensorNetworkProtocol.STA -> mDriverStatus.started = true
                SensorNetworkProtocol.STP -> mDriverStatus.started = false
                SensorNetworkProtocol.GET -> rx("$:GET:" + mValue.toString())
                SensorNetworkProtocol.SET -> try {
                    mValue = Integer.parseInt(cmd[1])
                    mSleep = TIMER * mValue
                    Log.e(TAG, "mValue: " + mValue.toString())
                } catch (e: Exception) {
                    Log.e(TAG, e.toString())
                }

                SensorNetworkProtocol.MAP -> rx("$:MAP:${sDevices}")
                SensorNetworkProtocol.RSC -> rx("$:RSC:${sSchedule}")
            }
        }
    }

    override fun stop() {
        mDriverStatus.started = false
    }

    override fun close() {
        mDriverStatus.opened = false
    }

    override fun status(): DriverStatus {
        return mDriverStatus
    }

    companion object {

        private val TAG = "Simulator"
        private val sDevices = "16,18,19"
        private val sSchedule = "0,0,0,0|19,0,0,0|0,0,0,0|0,0,0,0|0,0,0,0|18,0,0,0|0,0,0,0"

        val DEVICE_NAME = "SENSOR_SIMULATOR"

        val TIMER = 8  // 8msec
    }

}