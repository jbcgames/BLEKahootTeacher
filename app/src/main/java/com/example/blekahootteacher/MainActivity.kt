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
    private var isAdvertisingStartAll = false

    private val startAllAckSet = mutableSetOf<String>()
    private val TAG = "TeacherApp"
    private val PERMISSION_REQUEST_CODE = 200
    private var totalStudents: Int = 0

    // SAF para carpeta (si usas imágenes enumeradas)
    private val REQUEST_CODE_PICK_DIRECTORY = 123
    private var selectedDirectoryUri: Uri? = null

    // BLE
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isScanning = false
    private var isAdvertising = false
    private var isAdvertisingConf = false
    private val confAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising CONF iniciado correctamente")
        }
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Error al iniciar Advertising CONF: $errorCode")
        }
    }
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
        // Al pulsar Start All
        btnStartAll.setOnClickListener {
            if (selectedDirectoryUri == null) {
                Toast.makeText(this, "Primero selecciona la carpeta de imágenes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Verifica si hay algún estudiante con code == null
            val anyUnconfirmed = discoveredStudents.any { it.code == null }
            if (anyUnconfirmed) {
                Toast.makeText(this, "Aún hay estudiantes sin confirmar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1) Calcula cuántos estudiantes están confirmados
            val totalConfirmed = discoveredStudents.count { it.code != null }
            totalStudents = totalConfirmed

            // 2) Limpia el set de ACK_START para empezar a contar desde cero
            startAllAckSet.clear()

            // 3) Inicia anuncio indefinido de "START:ALL"
            startAdvertisingStartAllIndefinitely()

            Toast.makeText(
                this,
                "START:ALL enviado. Esperando ACK_START de $totalStudents estudiante(s).",
                Toast.LENGTH_SHORT
            ).show()
        }







        // Botón para seleccionar directorio
        btnSelectDirectory.setOnClickListener {
            pickDirectory()
        }
    }

    // -------------------------------------------------------------------
    //         MANEJO DE PERMISOS
    // -------------------------------------------------------------------
    private fun stopAdvertisingStartAll() {
        if (!isAdvertisingStartAll) return

        stopAdvertisingIfNeeded()
        isAdvertisingStartAll = false
        Log.d(TAG, "START:ALL => Advertising detenido")
    }

    private fun startAdvertisingStartAllIndefinitely() {
        // Si ya estamos anunciando START:ALL, no repitas
        if (isAdvertisingStartAll) return

        // Detenemos cualquier otro advertising en curso
        stopAdvertisingIfNeeded()

        val dataString = "START:ALL"
        val dataToSend = dataString.toByteArray()

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
        isAdvertisingStartAll = true

        Log.d(TAG, "START:ALL => Advertising indefinido iniciado")
    }

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
            .setReportDelay(0)
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
        if (dataString.startsWith("ACK_START")) {
            val deviceId = result.device.address
            startAllAckSet.add(deviceId)
            Log.d(TAG, "ACK_START recibido de $deviceId => ${startAllAckSet.size} de $totalStudents")

            // Cuando alcance el total...
            if (startAllAckSet.size >= totalStudents) {

                stopAdvertisingIfNeeded()
                stopScanning()
                Log.d(TAG, "Todos los ACK_START recibidos. Se detiene START:ALL.")

                // ABRIMOS la TeacherQuestionActivity en el hilo principal
                runOnUiThread {
                    val intent = Intent(this@MainActivity, TeacherQuestionActivity::class.java)
                    intent.putExtra("EXTRA_TOTAL_STUDENTS", totalStudents)
                    // Pásale también la carpeta de imágenes
                    intent.putExtra("EXTRA_DIRECTORY_URI", selectedDirectoryUri.toString())
                    startActivity(intent)
                }
            }
        }


        if (dataString.startsWith("ACKCODE:")) {
            val parts = dataString.split(":")
            if (parts.size == 2) {
                val codeAck = parts[1]
                // (1) Sacamos de pendingAckCodes
                pendingAckCodes.remove(codeAck)
                // (2) Log o Toast
                runOnUiThread {
                    Log.d(TAG, "ACKCODE recibido => $codeAck. Dejar de reenviar CONF")
                }
            }
        }
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
    // FILE: MainActivity.kt (Profesor)

// Un map o set para trackear a quién esperas ACK
    private val pendingAckCodes = mutableSetOf<String>()
    fun advertiseConfirmationIndefinitely(name: String, code: String) {
        // Si el code ya no está en pendingAckCodes, no anunciar
        if (!pendingAckCodes.contains(code)) {
            return
        }

        // Si ya estamos anunciando CONF, no repitas
        if (isAdvertisingConf) return

        // Detener cualquier advertising activo antes
        stopAdvertisingIfNeeded()

        val dataString = "CONF:$name:$code"
        val dataToSend = dataString.toByteArray()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(0x1234, dataToSend)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, confAdvertiseCallback)
        isAdvertising = true
        isAdvertisingConf = true

        Log.d(TAG, "Anunciando CONF:$name:$code indefinidamente")
    }
    fun stopAdvertisingConf() {
        if (!isAdvertisingConf) return
        stopAdvertisingIfNeeded()  // tu método genérico
        isAdvertisingConf = false
        Log.d(TAG, "Publicidad CONF detenida")
    }
    fun advertiseConfirmation(name: String, code: String) {
        // Agregar 'code' a pendientes
        pendingAckCodes.add(code)

        // Llama a la publicidad indefinida, en lugar de sendConfRepeatedly
        advertiseConfirmationIndefinitely(name, code)
    }

    // Nueva función que hace "CONF" en bucle
    private fun sendConfRepeatedly(name: String, code: String) {
        if (!pendingAckCodes.contains(code)) {
            // Ya no está pendiente => no reenvíes
            return
        }

        stopAdvertisingIfNeeded()

        val dataString = "CONF:$name:$code"
        val dataToSend = dataString.toByteArray()

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

        // Repite a los 2s si sigue pendiente
        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
            // Verificar de nuevo. Si code sigue en pendingAckCodes, reenviamos
            if (pendingAckCodes.contains(code)) {
                sendConfRepeatedly(name, code)
            }
        }, 2000)
    }


    /**
     * Envía "START:ALL"
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