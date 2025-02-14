package com.example.blekahootteacher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

// Para guardar respuesta y tiempo
data class ResponseData(
    val answer: String,
    val timeMillis: Long
)

class TeacherQuestionActivity : AppCompatActivity() {

    private val TAG = "TeacherQuestionActivity"

    // UI
    private lateinit var imgQuestion: ImageView
    private lateinit var btnNewRound: Button

    // BLE
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false

    // Timer
    private var startRoundTimestamp: Long = 0L
    private var roundDurationMs: Long = 30000  // 30s
    private var roundActive = false

    // Suponemos 4 estudiantes confirmados (ajusta según tu lista)
    private val totalStudents = 4

    // Map de "code" => Respuesta
    private val responsesMap = mutableMapOf<String, ResponseData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_question)

        imgQuestion = findViewById(R.id.imgQuestion)
        btnNewRound = findViewById(R.id.btnNewRound)

        // BLE
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // (Opcional) Cargar imágenes enumeradas
        val directoryUriString = intent.getStringExtra("EXTRA_DIRECTORY_URI")
        directoryUriString?.let { loadImagesFromDirectory(Uri.parse(it)) }

        // Botón para iniciar la ronda
        btnNewRound.setOnClickListener {
            onNewRound()
        }
    }

    /**
     * Carga imágenes enumeradas (1.jpg, 2.jpg...) si las usas.
     * Mostramos la primera en imgQuestion, de ejemplo.
     */
    private var imageFiles: List<DocumentFile> = emptyList()
    private var currentIndex = 0

    private fun loadImagesFromDirectory(directoryUri: Uri) {
        val docFile = DocumentFile.fromTreeUri(this, directoryUri) ?: return
        if (!docFile.isDirectory) return

        val children = docFile.listFiles().filter { file ->
            file.name?.endsWith(".jpg", ignoreCase = true) == true ||
                    file.name?.endsWith(".jpeg", ignoreCase = true) == true
        }
        imageFiles = children.sortedBy { it.name?.toIntOrNull() ?: Int.MAX_VALUE }
        if (imageFiles.isNotEmpty()) {
            currentIndex = 0
            showImageAtIndex(0)
        }
    }

    private fun showImageAtIndex(index: Int) {
        if (index < 0 || index >= imageFiles.size) return
        val doc = imageFiles[index]
        try {
            contentResolver.openInputStream(doc.uri).use { inputStream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    imgQuestion.setImageBitmap(bitmap)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------------
    //      LÓGICA DE NEWROUND Y ESCANEO DE RESPUESTAS
    // -------------------------------------------------------------------
    private fun onNewRound() {
        // Inicia la ronda
        roundActive = true
        startRoundTimestamp = System.currentTimeMillis()
        responsesMap.clear()

        Toast.makeText(this, "Ronda iniciada (30s)", Toast.LENGTH_SHORT).show()

        // Enviar NEWROUND
        advertiseSignal("NEWROUND")

        // Iniciar escaneo para "RESP:<code>:<answer>"
        startScanForResponses()

        // Timer 30s
        Handler(Looper.getMainLooper()).postDelayed({
            checkEndOfRound()
        }, roundDurationMs)
    }

    private fun startScanForResponses() {
        if (isScanning) return
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Activa Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "No se puede escanear BLE", Toast.LENGTH_SHORT).show()
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x1234, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "Escaneo de respuestas iniciado")
    }

    private fun stopScanForResponses() {
        if (!isScanning) return
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "Escaneo de respuestas detenido")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(ct: Int, result: ScanResult) {
            super.onScanResult(ct, result)
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
        val manufData = record.getManufacturerSpecificData(0x1234) ?: return
        val dataString = String(manufData)
        Log.d(TAG, "Recibido: $dataString")

        if (dataString.startsWith("RESP:")) {
            val parts = dataString.split(":")
            // RESP:<code>:<answer>
            if (parts.size == 3) {
                val code = parts[1]
                val answer = parts[2]
                // Calcula el tiempo
                val now = System.currentTimeMillis()
                val timeTaken = now - startRoundTimestamp

                // Guarda la respuesta
                responsesMap[code] = ResponseData(answer, timeTaken)

                // Log de depuración
                Log.d(TAG, "RESP => code=$code, answer=$answer, time=${timeTaken}ms")

                // Confirmar al estudiante => "CONFRES:<code>"
                advertiseConfirmResponse(code)

                // Revisa si todos respondieron
                checkEndOfRound()
            }
        }
    }

    private fun advertiseConfirmResponse(code: String) {
        advertiseSignal("CONFRES:$code")
    }

    private fun advertiseSignal(signal: String) {
        stopAdvertisingIfNeeded()

        val dataString = signal
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

        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 2000)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising señal iniciado")
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

    /**
     * Verifica si todos respondieron o si se acabó el tiempo.
     * Si la ronda ya no está activa, no hace nada.
     */
    private fun checkEndOfRound() {
        if (!roundActive) return

        val responsesCount = responsesMap.size
        if (responsesCount >= totalStudents) {
            // Todos respondieron
            endRound()
        } else {
            // Queda esperar a que pase el tiempo
            val now = System.currentTimeMillis()
            val elapsed = now - startRoundTimestamp
            if (elapsed >= roundDurationMs) {
                // Se cumplió el tiempo
                endRound()
            }
        }
    }

    private fun endRound() {
        roundActive = false
        stopScanForResponses()

        // Muestra resultados
        showResults()
    }

    private fun showResults() {
        Log.d(TAG, "===== RESULTADOS DE LA RONDA =====")
        for ((code, respData) in responsesMap) {
            Log.d(TAG, "Estudiante($code) => Respuesta=${respData.answer}, Tiempo=${respData.timeMillis} ms")
        }
        // Aquí podrías abrir otra Activity, mandar "SHOWRESULTS", etc.
        Toast.makeText(this, "Resultados en LogCat", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertisingIfNeeded()
        stopScanForResponses()
    }
}
