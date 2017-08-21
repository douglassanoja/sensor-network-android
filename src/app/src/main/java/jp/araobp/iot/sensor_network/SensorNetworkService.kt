package jp.araobp.iot.sensor_network

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import jp.araobp.iot.edge_computing.EdgeComputing
import org.greenrobot.eventbus.EventBus
import kotlin.reflect.full.primaryConstructor

/**
 * Sensor network service
 */
abstract class SensorNetworkService: Service(), SensorEventListener {

    companion object {
        private val TAG = "SensorNetworkService"
        const val CMD_SEND_INTERVAL = 250L  // 250msec
        const val G_UNIT = 9.90665  // 9.80665 m/s^2
        const val BUILTIN_SENSOR_TEMPERATURE_INTERVAL = 5000_000  // 5sec
        const val BUILTIN_SENSOR_HUMIDITY_INTERVAL = 5000_000  // 5sec
        const val BUILTIN_SENSOR_ACCELEROMETER_INTERVAL = 500_000  // 500msec
    }

    data class DriverStatus(var opened: Boolean = false, var started: Boolean = false)
    var driverStatus = DriverStatus(opened = false, started = false)

    private val mBinder: IBinder = ServiceBinder()
    private var mEdgeComputing: EdgeComputing? = null
    private var mLoggingEnabled = false
    private val mEventBus = EventBus.getDefault()

    inner class ServiceBinder : Binder() {
        fun getService(): SensorNetworkService {
            return this@SensorNetworkService
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SensorNetworkService started")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind called")
        val EDGE_COMPUTING_CLASS = intent.extras["edge_computing_class"] as String
        val sEdgeComputingClass = Class.forName(EDGE_COMPUTING_CLASS).kotlin
        mEdgeComputing = sEdgeComputingClass.primaryConstructor!!.call() as EdgeComputing

        val mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val temperature: Sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        val humidity: Sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        val accelerometer: Sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mSensorManager.registerListener(this, temperature, BUILTIN_SENSOR_TEMPERATURE_INTERVAL)
        mSensorManager.registerListener(this, humidity, BUILTIN_SENSOR_HUMIDITY_INTERVAL)
        mSensorManager.registerListener(this, accelerometer, BUILTIN_SENSOR_ACCELEROMETER_INTERVAL)

        return mBinder
    }

    /**
     * receives data from the sensor network and parses it
     */
    protected fun rx(message: String) {
        var timestamp = System.currentTimeMillis()
        var sensorData = SensorNetworkEvent.SensorData(timestamp = timestamp, rawData = message)
        val response = message.split(":".toRegex()).toList()

        when (message.substring(startIndex = 0, endIndex = 1)) {
            "%" -> {
                sensorData.deviceId = response[0].substring(1).toInt()
                sensorData.type = response[1]
                val dataStringList: List<String> = response[2].split(",".toRegex()).toList()
                when(sensorData.type) {
                    SensorNetworkProtocol.FLOAT -> sensorData.data = dataStringList.map{ it.toFloat() }.toList()
                    SensorNetworkProtocol.INT8_T, SensorNetworkProtocol.UINT8_T,
                    SensorNetworkProtocol.INT16_T, SensorNetworkProtocol.UINT16_T
                    -> sensorData.data = dataStringList.map{ it.toInt() }.toList()
                }
                mEdgeComputing?.onSensorData(sensorData)
                if (mLoggingEnabled) {
                    mEventBus.post(sensorData)
                }
            }
            "#" -> {
                when (message.substring(startIndex = 1, endIndex = 3)) {
                    SensorNetworkProtocol.STA -> {
                        sensorData.schedulerInfo = SensorNetworkEvent.SchedulerInfo(schedulerInfoType = SensorNetworkEvent.SchedulerInfoType.STARTED)
                        driverStatus.started = true
                    }
                    SensorNetworkProtocol.STP -> {
                        sensorData.schedulerInfo = SensorNetworkEvent.SchedulerInfo(schedulerInfoType = SensorNetworkEvent.SchedulerInfoType.STOPPED)
                        driverStatus.started = false
                    }
                }
                mEventBus.post(sensorData)
            }
            "$" -> {
                when (response[1]) {
                    SensorNetworkProtocol.GET -> sensorData.schedulerInfo = SensorNetworkEvent.SchedulerInfo(
                            schedulerInfoType = SensorNetworkEvent.SchedulerInfoType.TIMER_SCALER,
                            timerScaler = response[2].toInt()
                    )
                    SensorNetworkProtocol.MAP -> sensorData.schedulerInfo = SensorNetworkEvent.SchedulerInfo(
                            schedulerInfoType = SensorNetworkEvent.SchedulerInfoType.DEVICE_MAP,
                            deviceMap = response[2].split(",".toRegex()).toList().
                                    map { it.toInt() }.toList()
                    )
                    SensorNetworkProtocol.RSC -> sensorData.schedulerInfo = SensorNetworkEvent.SchedulerInfo(
                            schedulerInfoType = SensorNetworkEvent.SchedulerInfoType.SCHEDULE,
                            schedule = response[2].
                                    split("\\|".toRegex()).toList().
                                    map {
                                        it.split(",".toRegex()).map { it.toInt() }.toList()
                                    }.toList()
                    )
                }
                mEventBus.post(sensorData)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "sensor accuracy changed to " + accuracy.toString())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (driverStatus.started) {
            var sensorData = SensorNetworkEvent.SensorData(timestamp = event.timestamp, rawData = event.values[0].toString())
            when (event.sensor.type) {
                Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                    sensorData.deviceId = SensorNetworkProtocol.AMBIENT_TEMPERATURE
                    sensorData.data = event.values.map { it.toInt() }.toList()
                    sensorData.type = SensorNetworkProtocol.INT8_T
                }
                Sensor.TYPE_RELATIVE_HUMIDITY -> {
                    sensorData.deviceId = SensorNetworkProtocol.RELATIVE_HUMIDITY
                    sensorData.data = event.values.map { it.toInt() }.toList()
                    sensorData.type = SensorNetworkProtocol.INT8_T
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    sensorData.deviceId = SensorNetworkProtocol.ACCELEROMETER
                    sensorData.data = event.values.map { (it / G_UNIT * 100).toInt().toFloat() / 100.0 }.toList()
                    sensorData.type = SensorNetworkProtocol.FLOAT
                }
            }
            Log.d(TAG, sensorData.toString())
            mEdgeComputing?.onSensorData(sensorData)
        }
    }

    /**
     * opens the device driver
     */
    protected abstract fun open(baudrate: Int): Boolean
    fun openDevice(baudrate: Int) {
        var opened = open(baudrate)
        driverStatus.opened = opened
    }

    /**
     * transmits data to the sensor network
     */
    protected abstract fun tx(message: String)
    fun transmit(message: String) {
        tx(message)
        when (message.substring(startIndex = 0, endIndex = 2)) {
            SensorNetworkProtocol.STA -> driverStatus.started = true
            SensorNetworkProtocol.STP -> driverStatus.started = false
        }
    }

    /**
     * closes the device driver
     */
    protected abstract fun close()
    fun closeDevice() {
        close()
        driverStatus.opened = false
    }

    /**
     * fetches scheduler-related info from the sensor network
     *
     * @see SensorData
     * @see rx
     */
    fun fetchSchedulerInfo() {
        try {
            Thread.sleep(CMD_SEND_INTERVAL)
            transmit(SensorNetworkProtocol.GET)
            Thread.sleep(CMD_SEND_INTERVAL)
            transmit(SensorNetworkProtocol.SCN)
            transmit(SensorNetworkProtocol.MAP)
            Thread.sleep(CMD_SEND_INTERVAL)
            transmit(SensorNetworkProtocol.RSC)
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * starts running the sensor network
     */
    fun startScheduler() {
        transmit(SensorNetworkProtocol.STA)
        driverStatus.started = true
    }

    /**
     * stops running the sensor network
     */
    fun stopScheduler() {
        transmit(SensorNetworkProtocol.STP)
        driverStatus.started = false
    }

    /**
     * sends sensor data to SensorDataHandlerActivity
     */
    fun enableLogging(enabled: Boolean) {
        mLoggingEnabled = enabled
    }

}