package com.example.blekahootteacher

import android.Manifest
import android.app.Activity
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

// Modelo de estudiante detectado
data class Student(
    val name: String,
    var code: String? = null
)

class MainActivity : AppCompatActivity() {

    private val TAG = "TeacherApp"
    private val PERMISSION_REQUEST_CODE = 200

    // SAF para carpeta (si usas imágenes enumeradas)
    private val REQUEST_CODE_PICK_DIRECTORY = 123
    private var selectedDirectoryUri: Uri? = null

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
    private lateinit var btnSelectDirectory: Button

    // Lista de estudiantes
    private val discoveredStudents = mutableListOf<Student>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStudents = findViewById(R.id.tvStudents)
        btnConfirmNext = findViewById(R.id.btnConfirmNext)
        btnStartAll = findViewById(R.id.btnStartAll)
        btnSelectDirectory = findViewById(R.id.btnSelectDirectory)

        checkAndRequestPermissions()

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        startScanForStudents()

        // Botón para confirmar al primer sin código
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

        // Botón para enviar "START:ALL" y abrir TeacherQuestionActivity
        btnStartAll.setOnClickListener {
            advertiseStartAll()
            if (selectedDirectoryUri == null) {
                Toast.makeText(this, "Selecciona la carpeta de imágenes si usas enumeradas", Toast.LENGTH_SHORT).show()
            }
            // Ir a TeacherQuestionActivity
            val intent = Intent(this, TeacherQuestionActivity::class.java)
            // Si usas imágenes:
            selectedDirectoryUri?.let { uri ->
                intent.putExtra("EXTRA_DIRECTORY_URI", uri.toString())
            }
            // Podrías pasar la lista de estudiantes confirmados si lo necesitas
            startActivity(intent)
        }

        // Botón para seleccionar directorio
        btnSelectDirectory.setOnClickListener {
            pickDirectory()
        }
    }

    // -------------------------------------------------------------------
    //         MANEJO DE PERMISOS
    // -------------------------------------------------------------------
    private fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                for ((index, perm) in permissions.withIndex()) {
                    if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
                        if (!showRationale) {
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

    private fun showGoToSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage("Necesitamos Bluetooth/Ubicación para detectar a los estudiantes.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // -------------------------------------------------------------------
    //         SELECCIONAR DIRECTORIO (SAF)
    // -------------------------------------------------------------------
    private fun pickDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_CODE_PICK_DIRECTORY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_DIRECTORY && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                selectedDirectoryUri = uri
                Toast.makeText(this, "Carpeta seleccionada: $uri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------
    //         ESCANEO DE ESTUDIANTES (NAME:<nombre>)
    // -------------------------------------------------------------------
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
            .setManufacturerData(0x1234, byteArrayOf())
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

        if (dataString.startsWith("NAME:")) {
            val parts = dataString.split(":")
            if (parts.size == 2) {
                val name = parts[1].trim()
                // Si no está ya en la lista
                val existing = discoveredStudents.firstOrNull { it.name == name }
                if (existing == null) {
                    discoveredStudents.add(Student(name))
                    runOnUiThread { updateStudentListUI() }
                    Log.d(TAG, "Nuevo estudiante detectado: $name")
                }
            }
        }
    }

    private fun updateStudentListUI() {
        if (discoveredStudents.isEmpty()) {
            tvStudents.text = "No hay estudiantes detectados."
        } else {
            val sb = StringBuilder()
            discoveredStudents.forEachIndexed { i, st ->
                val stCode = st.code ?: "sin confirmar"
                sb.append("${i+1}) ${st.name} => $stCode\n")
            }
            tvStudents.text = sb.toString()
        }
    }

    private fun generateRandomCode(): String {
        val rByte = (0..255).random().toByte()
        return String.format("%02X", rByte)  // "AB", etc.
    }

    /**
     * Envía "CONF:<nombre>:<código>"
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

        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 2000)
    }

    /**
     * Envía "START:ALL"
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

        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 5000)
    }

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

    private fun stopAdvertisingIfNeeded() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "Advertising detenido")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        stopAdvertisingIfNeeded()
    }
}
