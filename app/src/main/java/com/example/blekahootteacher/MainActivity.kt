package com.example.blekahootteacher

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

// Modelo para cada estudiante detectado
data class Student(
    val name: String,
    var code: String? = null // null => no confirmado
)

class MainActivity : AppCompatActivity() {

    private val TAG = "TeacherApp"

    // Permisos
    private val PERMISSION_REQUEST_CODE = 200

    /**
     * Retorna los permisos requeridos dependiendo de la versión de Android.
     * Android 12+ => BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, FINE_LOCATION
     * Android 6 a 11 => BLUETOOTH, BLUETOOTH_ADMIN, FINE_LOCATION
     */
    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // BLE
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isScanning = false
    private var isAdvertising = false

    // UI
    private lateinit var tvStudents: TextView
    private lateinit var btnConfirmNext: Button
    private lateinit var btnStartAll: Button
    private lateinit var btnNewRoundAll: Button

    // Lista de estudiantes detectados
    private val discoveredStudents = mutableListOf<Student>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Referencias UI
        tvStudents = findViewById(R.id.tvStudents)
        btnConfirmNext = findViewById(R.id.btnConfirmNext)
        btnStartAll = findViewById(R.id.btnStartAll)
        btnNewRoundAll = findViewById(R.id.btnNewRoundAll)

        // Verificar permisos BLE
        checkAndRequestPermissions()

        // Inicializar BLE
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        // Iniciar escaneo para "NAME:<nombre>"
        startScanForStudents()

        // Botón para confirmar al primer estudiante sin código
        btnConfirmNext.setOnClickListener {
            val studentToConfirm = discoveredStudents.firstOrNull { it.code == null }
            if (studentToConfirm == null) {
                Toast.makeText(this, "No hay estudiantes sin confirmar", Toast.LENGTH_SHORT).show()
            } else {
                val randomCode = generateRandomCode()
                studentToConfirm.code = randomCode
                advertiseConfirmation(studentToConfirm.name, randomCode)
                updateStudentListUI()
            }
        }

        // Botón para enviar "START:ALL" a todos los confirmados
        btnStartAll.setOnClickListener {
            advertiseStartAll()
        }

        // Botón para enviar "NEWROUND:ALL" a todos (por ejemplo, tras 10s de pregunta)
        btnNewRoundAll.setOnClickListener {
            advertiseNewRoundAll()
        }
    }

    /**
     * Chequea y pide permisos en tiempo de ejecución
     */
    private fun checkAndRequestPermissions() {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Respuesta al request de permisos
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Al menos uno se denegó
                for ((index, perm) in permissions.withIndex()) {
                    if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
                        if (!showRationale) {
                            // "No volver a preguntar"
                            showGoToSettingsDialog()
                            return
                        } else {
                            Toast.makeText(this, "Permiso $perm denegado", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Diálogo para ir a Ajustes si el usuario marcó "No volver a preguntar"
     */
    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage("Necesitamos el permiso de Bluetooth/Ubicación para detectar a los estudiantes. " +
                    "Por favor habilítalo en Ajustes.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Inicia el escaneo BLE para "NAME:<nombre>"
     */
    private fun startScanForStudents() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth para escanear", Toast.LENGTH_SHORT).show()
            return
        }
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "BLE Scanner no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (isScanning) return

        val filter = ScanFilter.Builder()
            .setManufacturerData(0x1234, byteArrayOf()) // Mismo ID que el Student
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "Escaneo iniciado (NAME:...)")
    }

    private fun stopScanning() {
        if (!isScanning) return
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "Escaneo detenido")
    }

    /**
     * Callbacks de escaneo
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Escaneo falló: $errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val data = record.getManufacturerSpecificData(0x1234) ?: return
        val dataString = String(data)
        Log.d(TAG, "Recibido: $dataString")

        // Si es "NAME:<nombre>", registramos al estudiante
        if (dataString.startsWith("NAME:")) {
            val parts = dataString.split(":")
            if (parts.size == 2) {
                val name = parts[1].trim()
                // ¿Ya existe en la lista?
                val existing = discoveredStudents.firstOrNull { it.name == name }
                if (existing == null) {
                    discoveredStudents.add(Student(name))
                    runOnUiThread {
                        updateStudentListUI()
                    }
                    Log.d(TAG, "Nuevo estudiante detectado: $name")
                }
            }
        }
    }

    /**
     * Muestra en pantalla la lista de estudiantes y su estado
     */
    private fun updateStudentListUI() {
        if (discoveredStudents.isEmpty()) {
            tvStudents.text = "No hay estudiantes detectados."
        } else {
            val builder = StringBuilder()
            discoveredStudents.forEachIndexed { index, student ->
                val status = student.code?.let { "Código: $it" } ?: "Sin confirmar"
                builder.append("${index+1}) ${student.name} → $status\n")
            }
            tvStudents.text = builder.toString()
        }
    }

    /**
     * Genera un código aleatorio de 1 byte en hex, p.ej. "AF", "2B", etc.
     */
    private fun generateRandomCode(): String {
        val randomByte = (0..255).random().toByte()
        return String.format("%02X", randomByte)
    }

    /**
     * Publica "CONF:<nombre>:<code>"
     */
    private fun advertiseConfirmation(name: String, code: String) {
        stopAdvertisingIfNeeded()

        val dataString = "CONF:$name:$code"
        val dataToSend = dataString.toByteArray()
        Log.d(TAG, "Advertise CONF: $dataString")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x1234, dataToSend)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true

        // Detenemos en 2s
        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 2000)
    }

    /**
     * Envía "START:ALL" a todos los estudiantes confirmados (solo un advertising).
     */
    private fun advertiseStartAll() {
        stopAdvertisingIfNeeded()

        val dataString = "START:ALL"
        val dataToSend = dataString.toByteArray()
        Log.d(TAG, "Advertise START:ALL")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x1234, dataToSend)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true

        // Mantén 5s para que todos lo detecten
        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 5000)
    }

    /**
     * Envía "NEWROUND:ALL" tras los 10s de pregunta, para que pasen a la pantalla 3.
     */
    private fun advertiseNewRoundAll() {
        stopAdvertisingIfNeeded()

        val dataString = "NEWROUND:ALL"
        val dataToSend = dataString.toByteArray()
        Log.d(TAG, "Advertise NEWROUND:ALL")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x1234, dataToSend)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true

        // Lo mantenemos 5s
        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 5000)
    }

    /**
     * Callback genérico de Advertising
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising iniciado correctamente")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Error en advertising: $errorCode")
        }
    }

    /**
     * Detiene la publicidad BLE si está activa
     */
    private fun stopAdvertisingIfNeeded() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "Advertising detenido")
        }
    }
}
