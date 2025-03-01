package com.example.blekahootteacher

import android.annotation.SuppressLint
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
    private val showAwAckSet = mutableSetOf<String>() // Para ACK_SHOWAW
    private var answersList = mutableListOf<String>() // Lista de respuestas correctas para cada ronda

    private val TAG = "TeacherQuestionActivity"
    private var keepSendingEndRound = false
    // UI
    private lateinit var imgQuestion: ImageView
    private lateinit var btnNewRound: Button
    // Cola para almacenar los códigos de estudiante que han respondido
    private val pendingResponsesQueue = mutableListOf<String>()

    // Mantiene el código del estudiante al que se le está enviando CONFRES en este momento
    private var currentConfirmCode: String? = null
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
    private var totalStudents = 0

    // Map de "code" => Respuesta
    private val responsesMap = mutableMapOf<String, ResponseData>()
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // No llamamos a super => se desactiva el botón atrás
        // Puedes mostrar un Toast si deseas notificar
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_question)
        totalStudents = intent.getIntExtra("EXTRA_TOTAL_STUDENTS", 0)
        imgQuestion = findViewById(R.id.imgQuestion)
        btnNewRound = findViewById(R.id.btnNewRound)
        Log.d(TAG, totalStudents.toString())
        // BLE
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // (Opcional) Cargar imágenes enumeradas
        val directoryUriString = intent.getStringExtra("EXTRA_DIRECTORY_URI")
        directoryUriString?.let { uriString ->
            val uri = Uri.parse(uriString)

            // 1) Cargar imágenes enumeradas
            loadImagesFromDirectory(uri)

            // 2) Cargar archivo respuestas.txt
            loadAnswersFile(uri)
        }

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
            .setReportDelay(0)
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
        Log.d(TAG, "Recibido: $dataString" + " info " + dataString.startsWith("RESP:").toString())

        if (dataString.startsWith("ACKRES:")) {
            val parts = dataString.split(":")
            if (parts.size == 2) {
                val ackCode = parts[1].trim()
                if (ackCode == currentConfirmCode) {
                    Log.d(TAG, "Recibido ACKRES de $ackCode. Confirmación completada.")
                    // 1) Dejar de anunciar CONFRES
                    stopAdvertisingConfRes()
                    // 2) Liberar la variable
                    currentConfirmCode = null
                    // 3) Procesar siguiente en cola (si existe)
                    processNextInQueue()
                }
            }
        }
        if (dataString.startsWith("ACK_SHOWAW:")) {
            // Formato "ACK_SHOWAW:<studentCode>"
            val parts = dataString.split(":")
            if (parts.size == 2) {
                val code = parts[1].trim()
                showAwAckSet.add(code)
                Log.d(TAG, "ACK_SHOWAW recibido de $code => ${showAwAckSet.size} de $totalStudents")

                // Cuando lleguen todos
                if (showAwAckSet.size >= totalStudents) {
                    // Detenemos el Advertising SHOWAW
                    stopAdvertisingShowAw()
                    Log.d(TAG, "Todos los ACK_SHOWAW recibidos. Listo para iniciar otra ronda.")
                    // Aquí podrías habilitar el botón de "NewRound" o cambiar de fase.
                }
            }
        }
        if (dataString.startsWith("RESP:")) {
            val parts = dataString.split(":")
            if (parts.size == 3) {
                val code = parts[1].trim()
                val answer = parts[2].trim()

                // (1) Verificar si ya lo tenemos en la cola o si ya fue confirmado
                if (!pendingResponsesQueue.contains(code)) {
                    // (opcional) Guarda la respuesta en un map, p. ej. responsesMap[code] = answer
                    responsesMap[code] = ResponseData(answer, System.currentTimeMillis() - startRoundTimestamp)

                    // (2) Agregar a la cola
                    pendingResponsesQueue.add(code)

                    // (3) Ver si podemos iniciar la confirmación
                    processNextInQueue()
                }
                // Si ya está en la cola, lo ignoras (no vuelves a agregar).
            }
        }

    }


    private var isAdvertisingConfRes = false

    private fun advertiseConfirmResponse(code: String) {
        stopAdvertisingIfNeeded()  // Detiene el advertising actual
        if (isAdvertisingConfRes) {
            // Si ya estabas anunciando para otro code, primero lo detienes
            stopAdvertisingConfRes()
        }

        val dataString = "CONFRES:$code"
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
        isAdvertisingConfRes = true

        Log.d(TAG, "Iniciando CONFRES:$code indefinidamente")
    }

    private fun stopAdvertisingConfRes() {
        if (!isAdvertisingConfRes) return
        stopAdvertisingIfNeeded() // tu método general
        isAdvertisingConfRes = false
        Log.d(TAG, "Publicidad CONFRES detenida")
    }

    private fun processNextInQueue() {
        // Si no estoy procesando un code, y hay uno en cola, lo proceso
        if (currentConfirmCode == null && pendingResponsesQueue.isNotEmpty()) {
            val nextCode = pendingResponsesQueue.removeAt(0)
            currentConfirmCode = nextCode
            advertiseConfirmResponse(nextCode)  // inicia la publicidad de "CONFRES:<code>" indefinidamente
        }
    }

    private fun advertiseSignal(signal: String) {
        stopAdvertisingIfNeeded()
        Log.d(TAG, "Enviada señal " + signal)
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

        // Verifica si ya todos respondieron
        if (responsesMap.size < totalStudents) {
            // Falta(n) estudiante(s). Enviamos "ENDROUND" repetidamente.
            keepSendingEndRound = true
            sendEndRoundRepeatedly()
        } else {
            // Todos han respondido o se acabó el tiempo
            keepSendingEndRound = false
            stopScanForResponses()
            showResults()

            // AHORA enviamos la señal SHOWAW
            showAnswersForCurrentQuestion()
        }
    }

    private fun sendEndRoundRepeatedly() {
        if (!keepSendingEndRound) return

        stopAdvertisingIfNeeded()

        val dataString = "ENDROUND"
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
            // Mientras keepSendingEndRound sea true, reenvía
            if (responsesMap.size < totalStudents) {
                // Falta(n) estudiante(s). Enviamos "ENDROUND" repetidamente.
                keepSendingEndRound = true
            } else {
                // Todos han respondido; solo mostrar resultados
                keepSendingEndRound = false
            }
            if (keepSendingEndRound) {
                Log.d(TAG, "Enviando ENDROUND " + responsesMap.size.toString())
                sendEndRoundRepeatedly()
            }
        }, 2000)
    }



    private fun showResults() {
        Log.d(TAG, "===== RESULTADOS DE LA RONDA =====")
        for ((code, respData) in responsesMap) {
            Log.d(TAG, "Estudiante($code) => Respuesta=${respData.answer}, Tiempo=${respData.timeMillis} ms")
        }
        // Aquí podrías abrir otra Activity, mandar "SHOWRESULTS", etc.
        Toast.makeText(this, "Resultados en LogCat", Toast.LENGTH_LONG).show()
    }
    private fun loadAnswersFile(directoryUri: Uri) {
        val docFile = DocumentFile.fromTreeUri(this, directoryUri) ?: return
        // Buscamos un archivo llamado "respuestas.txt"
        val answersFile = docFile.listFiles().firstOrNull { it.name.equals("respuestas.txt", ignoreCase = true) }
            ?: return

        contentResolver.openInputStream(answersFile.uri)?.use { inputStream ->
            val lines = inputStream.bufferedReader().readLines()
            // Convertimos cada línea a mayúsculas para normalizar
            answersList.clear()
            answersList.addAll(lines.map { it.trim().uppercase() })
        }
        Log.d(TAG, "Archivo respuestas.txt cargado: $answersList")
    }


    /**
     * Envía SHOWAW:<correctAnswer> de manera indefinida y espera ACK_SHOWAW de todos los estudiantes
     */
    fun showAnswersForCurrentQuestion() {
        if (currentIndex < 0 || currentIndex >= answersList.size) {
            Toast.makeText(this, "Índice de pregunta fuera de rango", Toast.LENGTH_SHORT).show()
            return
        }
        // Supongamos que deseas usar la primera opción de cada línea
        val rawAnswer = answersList[currentIndex]
        // Separa por coma y toma el primer elemento, normalizando (mayúsculas y sin espacios)
        val correctAnswer = rawAnswer.split(",")[0].trim().uppercase()

        // Limpia el set de ACK para esta fase
        showAwAckSet.clear()

        // Inicia el advertising indefinido con la respuesta correcta ya filtrada
        startAdvertisingShowAwIndefinitely(correctAnswer)
        Toast.makeText(this, "SHOWAW enviado: $correctAnswer", Toast.LENGTH_SHORT).show()
    }

    /**
     * Inicia un anuncio BLE indefinido con la señal "SHOWAW:<correctAnswer>"
     */
    private var isAdvertisingShowAw = false

    private fun startAdvertisingShowAwIndefinitely(correctAnswer: String) {
        if (isAdvertisingShowAw) return

        // Detenemos cualquier advertising previo
        stopAdvertisingIfNeeded()

        val dataString = "SHOWAW:$correctAnswer"
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

        advertiser?.startAdvertising(settings, data, showAwAdvertiseCallback)
        isAdvertising = true
        isAdvertisingShowAw = true
        Log.d(TAG, "SHOWAW => Advertising indefinido con respuesta=$correctAnswer")
    }

    private fun stopAdvertisingShowAw() {
        if (!isAdvertisingShowAw) return
        stopAdvertisingIfNeeded()
        isAdvertisingShowAw = false
        Log.d(TAG, "SHOWAW => Advertising detenido")
    }

    // Callback exclusivo para SHOWAW (opcional; podrías reutilizar uno único)
    private val showAwAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertising SHOWAW iniciado correctamente")
        }
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Error al iniciar Advertising SHOWAW: $errorCode")
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        stopAdvertisingIfNeeded()
        stopScanForResponses()
    }
}