package com.example.blekahootteacher

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "BLE_Teacher"

    // Permisos requeridos en Android 12+
    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val REQUEST_CODE_PERMISSIONS = 100

    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Para detener el escaneo automáticamente después de 10 segundos
    private val scanTimeoutMs: Long = 10_000
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var textResults: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textResults = findViewById(R.id.textResults)
        val buttonStartScan = findViewById<Button>(R.id.btnStartScan)

        // Inicializar Bluetooth
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        buttonStartScan.setOnClickListener {
            checkPermissionsAndStartScan()
        }
    }

    /**
     * Verifica permisos y, si están aprobados, inicia el proceso de escaneo BLE.
     */
    private fun checkPermissionsAndStartScan() {
        // Filtra los permisos que aún faltan
        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            // Solicita al usuario los permisos que faltan
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            // Permisos concedidos, iniciamos escaneo
            startBleScan()
        }
    }

    /**
     * Respuesta del usuario al cuadro de diálogo de permisos.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleScan()
            } else {
                Toast.makeText(this, "Permisos de Bluetooth denegados", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Inicia el BLE Scan y filtra por el Manufacturer ID que usamos (0x1234).
     */
    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth para escanear", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "BLE Scanner no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        // Filtro para buscar Manufacturer Data con ID = 0x1234 (hex) = 4660 (decimal)
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x1234, byteArrayOf())  // Valor en hex: 0x1234
            .build()

        val filters = listOf(filter)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Limpia el TextView antes de iniciar
        textResults.text = "Resultados:\n"

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        scanner.startScan(filters, settings, scanCallback)
        Toast.makeText(this, "Escaneo iniciado...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Escaneo BLE iniciado...")

        // Detiene el escaneo automáticamente después de 10 segundos
        handler.postDelayed({
            stopBleScan()
        }, scanTimeoutMs)
    }

    /**
     * Detiene el BLE Scan.
     */
    private fun stopBleScan() {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        scanner.stopScan(scanCallback)
        Toast.makeText(this, "Escaneo detenido", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Escaneo BLE detenido")
    }

    /**
     * Callbacks para manejo de resultados de escaneo BLE.
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Escaneo falló: $errorCode")
        }
    }

    /**
     * Procesa un resultado de escaneo: extrae MAC y manufacturer data.
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val macAddress = device.address // Requiere BLUETOOTH_CONNECT en Android 12+

        // Extraer manufacturer data (ID = 0x1234)
        val scanRecord = result.scanRecord ?: return
        val manufacturerData = scanRecord.getManufacturerSpecificData(0x1234) ?: return
        // manufacturerData contiene algo como "ABCDEF:A", según la app Student

        // Conviértelo a String
        val dataString = String(manufacturerData)

        // Agregar al TextView
        runOnUiThread {
            textResults.append("MAC: $macAddress → $dataString\n")
        }

        Log.d(TAG, "Encontrado: MAC=$macAddress, manufacturerData=$dataString")
    }
}
