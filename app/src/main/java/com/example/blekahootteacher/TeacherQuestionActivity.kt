package com.example.blekahootteacher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.net.Uri
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

// Fases posibles para un solo botón:
enum class Phase {
    NEW_ROUND,   // Envía señal "NEWROUND", no cambia imagen
    RESULTS,     // Muestra o hace algo con resultados (opcional)
    NEW_ANSWER   // Cambia la imagen + (opcional) envía "NEWANSWER"
}

class TeacherQuestionActivity : AppCompatActivity() {

    private val TAG = "TeacherQuestionActivity"

    private lateinit var imgQuestion: ImageView
    private lateinit var btnAction: Button

    // BLE
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    // Lista de archivos .jpg enumerados
    private var imageFiles: List<DocumentFile> = emptyList()
    private var currentIndex = 0

    // Fase actual del botón
    private var currentPhase = Phase.NEW_ROUND

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_question)

        // UI
        imgQuestion = findViewById(R.id.imgQuestion)
        btnAction = findViewById(R.id.btnAction)

        // BLE
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        // Recibir URI de la carpeta (si venía de MainActivity)
        val directoryUriString = intent.getStringExtra("EXTRA_DIRECTORY_URI")
        if (directoryUriString != null) {
            val directoryUri = Uri.parse(directoryUriString)
            loadImagesFromDirectory(directoryUri)
        } else {
            Toast.makeText(this, "No se recibió carpeta de imágenes", Toast.LENGTH_SHORT).show()
        }

        // Texto inicial = "NewRound"
        btnAction.text = "NewRound"
        currentPhase = Phase.NEW_ROUND

        // Manejamos las pulsaciones del botón según la fase
        btnAction.setOnClickListener {
            when (currentPhase) {
                Phase.NEW_ROUND -> {
                    // Enviar "NEWROUND"
                    advertiseSignal("NEWROUND")
                    // Pasar a la siguiente fase (p.ej. RESULTS)
                    currentPhase = Phase.RESULTS
                    btnAction.text = "Results"
                }
                Phase.RESULTS -> {
                    // Lógica de "ver resultados" (si la necesitas)
                    showResults()
                    // Pasar a la fase NEW_ANSWER
                    currentPhase = Phase.NEW_ANSWER
                    btnAction.text = "NewAnswer"
                }
                Phase.NEW_ANSWER -> {
                    // Cambiar a la siguiente imagen
                    nextImage()
                    // (Opcional) enviar "NEWANSWER" si quieres avisar a estudiantes
                    advertiseSignal("NEWANSWER")
                    // Después de cambiar a la próxima imagen, regresamos a NEW_ROUND
                    currentPhase = Phase.NEW_ROUND
                    btnAction.text = "NewRound"
                }
            }
        }
    }

    /**
     * Muestra los archivos .jpg de la carpeta, ordenados por nombre.
     */
    private fun loadImagesFromDirectory(directoryUri: Uri) {
        val docFile = DocumentFile.fromTreeUri(this, directoryUri)
        if (docFile == null || !docFile.isDirectory) {
            Toast.makeText(this, "Carpeta inválida", Toast.LENGTH_SHORT).show()
            return
        }

        val children = docFile.listFiles().filter { file ->
            file.name?.endsWith(".jpg", ignoreCase = true) == true ||
                    file.name?.endsWith(".jpeg", ignoreCase = true) == true
        }
        // Ordenar por nombre: "1.jpg", "2.jpg", etc.
        imageFiles = children.sortedBy { it.name?.toIntOrNull() ?: Int.MAX_VALUE }

        // Cargar la primera imagen
        currentIndex = 0
        if (imageFiles.isNotEmpty()) {
            showImageAtIndex(0)
        }
    }

    /**
     * Muestra la imagen en imageFiles[index].
     */
    private fun showImageAtIndex(index: Int) {
        if (index < 0 || index >= imageFiles.size) return
        val doc = imageFiles[index]
        try {
            contentResolver.openInputStream(doc.uri).use { inputStream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    imgQuestion.setImageBitmap(bitmap)
                } else {
                    Toast.makeText(this, "No se pudo decodificar: ${doc.name}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error leyendo imagen: ${doc.name}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * nextImage => siguiente archivo .jpg
     */
    private fun nextImage() {
        currentIndex++
        if (currentIndex >= imageFiles.size) {
            Toast.makeText(this, "No hay más imágenes", Toast.LENGTH_SHORT).show()
            currentIndex = imageFiles.size - 1
            return
        }
        showImageAtIndex(currentIndex)
    }

    /**
     * Ejemplo de función para "ver resultados"
     * (aquí solo mostramos un Toast, pero podrías hacer algo más avanzado).
     */
    private fun showResults() {
        Toast.makeText(this, "Mostrando resultados...", Toast.LENGTH_SHORT).show()
        // Podrías abrir un diálogo, otra Activity, etc.
    }

    /**
     * Envía una señal por BLE, con un string (p.ej. "NEWROUND" o "NEWANSWER").
     */
    private fun advertiseSignal(signal: String) {
        stopAdvertisingIfNeeded()

        val dataString = signal
        val dataToSend = dataString.toByteArray()
        Log.d(TAG, "Advertise $signal")

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

        // Mantener 2s
        Handler(Looper.getMainLooper()).postDelayed({
            stopAdvertisingIfNeeded()
        }, 2000)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising iniciado correctamente")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Error en advertising: $errorCode")
            Toast.makeText(this@TeacherQuestionActivity, "Error en advertising: $errorCode", Toast.LENGTH_SHORT).show()
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
        stopAdvertisingIfNeeded()
    }
}
