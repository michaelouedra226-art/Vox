package com.example

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VoxGeminiDashboard(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("voxgemini_prefs", Context.MODE_PRIVATE)

    var apiKey = MutableStateFlow(prefs.getString("api_key", "") ?: "")
        private set

    var isApiKeyValid = MutableStateFlow(false)
        private set

    var textInput = MutableStateFlow("")
    var selectedVoice = MutableStateFlow("Puck")
    var selectedTone = MutableStateFlow("Normal")

    var isLoading = MutableStateFlow(false)
        private set

    var statusMessage = MutableStateFlow<String?>(null)
        private set

    private var mediaPlayer: android.media.MediaPlayer? = null
    var isPlaying = MutableStateFlow(false)
        private set
    var currentPosition = MutableStateFlow(0)
        private set
    var duration = MutableStateFlow(0)
        private set
    var lastGeneratedFileName = MutableStateFlow<String?>(null)
        private set

    private var playbackProgressJob: kotlinx.coroutines.Job? = null

    init {
        isApiKeyValid.value = isValidKey(apiKey.value)
    }

    private fun isValidKey(key: String): Boolean {
        val trimmed = key.trim()
        val regex = Regex("^AIzaSy[A-Za-z0-9_-]{33}$")
        if (trimmed.isEmpty()) {
            val fallback = try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
            return fallback.trim().matches(regex)
        }
        return trimmed.matches(regex)
    }

    fun saveApiKey(key: String) {
        apiKey.value = key
        prefs.edit().putString("api_key", key).apply()
        isApiKeyValid.value = isValidKey(key)
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            isPlaying.value = false
            stopProgressPolling()
        } else {
            player.start()
            isPlaying.value = true
            startProgressPolling()
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let { player ->
            player.seekTo(positionMs)
            currentPosition.value = positionMs
        }
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        playbackProgressJob = viewModelScope.launch(Dispatchers.Main) {
            while (isPlaying.value) {
                mediaPlayer?.let { player ->
                    currentPosition.value = player.currentPosition
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun stopProgressPolling() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
    }

    fun loadAudioFile(file: File, displayName: String) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                releaseMediaPlayer()
                val player = android.media.MediaPlayer()
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.setOnCompletionListener {
                    isPlaying.value = false
                    currentPosition.value = 0
                    stopProgressPolling()
                }
                mediaPlayer = player
                isPlaying.value = false
                currentPosition.value = 0
                duration.value = player.duration
                lastGeneratedFileName.value = displayName
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun releaseMediaPlayer() {
        stopProgressPolling()
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {}
            player.release()
        }
        mediaPlayer = null
        isPlaying.value = false
        currentPosition.value = 0
        duration.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        releaseMediaPlayer()
    }

    fun generateAudio(context: Context) {
        val key = apiKey.value.trim().ifEmpty {
            try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (key.trim().isEmpty() || key == "MY_GEMINI_API_KEY") {
            statusMessage.value = "Erreur : Clé API manquante ou invalide"
            return
        }

        val textToSpeak = textInput.value.trim()
        if (textToSpeak.isEmpty()) {
            statusMessage.value = "Erreur : Saisissez du texte à synthétiser"
            return
        }

        if (textToSpeak.length > 5000) {
            statusMessage.value = "Erreur : Limite de 5 000 caractères dépassée"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            statusMessage.value = "Génération de l'audio..."

            try {
                val toneInstruction = when (selectedTone.value) {
                    "Chuchotement" -> "Parle impérativement en chuchotant, d'une voix très basse et confidentielle."
                    "Douceur" -> "Prends un ton extrêmement doux, calme, protecteur et posé."
                    "Dynamique" -> "Prends un ton énergique, rapide, souriant et très enthousiaste."
                    "Sérieux" -> "Prends un ton formel, sérieux, académique et professionnel."
                    else -> "Parle de manière totalement naturelle et fluide."
                }

                val prompt = "$toneInstruction Voici le texte à lire : $textToSpeak"

                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()

                val jsonPayload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", prompt)
                        }))
                    }))

                    val voiceConfig = JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", selectedVoice.value)
                            })
                        })
                    }

                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", voiceConfig)
                    })

                    put("config", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", voiceConfig)
                    })
                }

                val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key"

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseString = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        val errorDetail = when (response.code) {
                            400, 403 -> "Clé API invalide ou expirée"
                            429 -> "Trop de requêtes, veuillez patienter quelques minutes"
                            else -> "Erreur HTTP ${response.code}"
                        }
                        statusMessage.value = "Erreur : $errorDetail"
                        return@launch
                    }

                    if (responseString.isEmpty()) {
                        statusMessage.value = "Erreur : Réponse vide reçue"
                        return@launch
                    }

                    val jsonResponse = JSONObject(responseString)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        statusMessage.value = "Erreur : Aucun audio généré par l'IA"
                        return@launch
                    }

                    val firstCandidate = candidates.getJSONObject(0)
                    val contentObj = firstCandidate.optJSONObject("content")
                    val partsArr = contentObj?.optJSONArray("parts")
                    if (partsArr == null || partsArr.length() == 0) {
                        statusMessage.value = "Erreur : Contenu audio manquant"
                        return@launch
                    }

                    var base64AudioData: String? = null
                    for (i in 0 until partsArr.length()) {
                        val part = partsArr.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            base64AudioData = inlineData.optString("data")
                            break
                        }
                    }

                    if (base64AudioData.isNullOrEmpty()) {
                        statusMessage.value = "Erreur : Format audio introuvable dans la réponse"
                        return@launch
                    }

                    val audioBytes = Base64.decode(base64AudioData, Base64.DEFAULT)
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "VoxGemini_$timestamp.mp3"

                    val isSaved = saveAudioFile(context, fileName, audioBytes)
                    if (isSaved) {
                        val cacheFile = File(context.cacheDir, "preview.mp3")
                        try {
                            FileOutputStream(cacheFile).use { fos ->
                                fos.write(audioBytes)
                            }
                            loadAudioFile(cacheFile, fileName)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        statusMessage.value = "SUCCESS:Audio enregistré dans vos Téléchargements !"
                    } else {
                        statusMessage.value = "Erreur : Échec de l'enregistrement du fichier"
                    }
                }
            } catch (e: Exception) {
                val userFriendlyMessage = if (e is java.net.UnknownHostException || e is java.net.ConnectException) {
                    "Veuillez vérifier votre connexion Internet"
                } else {
                    e.localizedMessage ?: e.message ?: "Erreur réseau inconnue"
                }
                statusMessage.value = "Erreur : $userFriendlyMessage"
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun saveAudioFile(context: Context, fileName: String, audioBytes: ByteArray): Boolean {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(audioBytes)
                    }
                    true
                } else {
                    false
                }
            } else {
                val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsFolder.exists()) {
                    downloadsFolder.mkdirs()
                }
                val destinationFile = File(downloadsFolder, fileName)
                FileOutputStream(destinationFile).use { os ->
                    os.write(audioBytes)
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxGeminiDashboard(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val apiKey by viewModel.apiKey.collectAsState()
    val isApiKeyValid by viewModel.isApiKeyValid.collectAsState()
    val textInput by viewModel.textInput.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val selectedTone by viewModel.selectedTone.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val lastGeneratedFileName by viewModel.lastGeneratedFileName.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.releaseMediaPlayer()
        }
    }

    var showApiKey by remember { mutableStateOf(false) }
    val isKeyNotEmpty = apiKey.trim().isNotEmpty()
    val isApiKeyError = isKeyNotEmpty && !isApiKeyValid
    var lastBackPressTime by remember { mutableStateOf(0L) }

    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(context, "Appuyez à nouveau pour quitter", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            if (msg.startsWith("SUCCESS:")) {
                Toast.makeText(context, msg.removePrefix("SUCCESS:"), Toast.LENGTH_LONG).show()
                viewModel.clearStatusMessage()
            } else if (msg.startsWith("Erreur :")) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                viewModel.clearStatusMessage()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        // High Density Theme Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF6750A4), shape = RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "VoxGemini Logo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "VoxGemini",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1B1F)
                    )
                )
            }
            IconButton(onClick = { /* No-op settings icon for aesthetics */ }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Paramètres",
                    tint = Color(0xFF49454F)
                )
            }
        }

        // Scrollable Main Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: API Config
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Clé API Google AI Studio",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { viewModel.saveApiKey(it) },
                    placeholder = { Text("Entrez votre clé API Google AI Studio (AIzaSy...)") },
                    singleLine = true,
                    isError = isApiKeyError,
                    supportingText = {
                        if (isApiKeyError) {
                            Text(
                                text = "Format invalide (doit commencer par 'AIzaSy' et faire 39 caractères)",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                text = "La clé est sauvegardée localement de manière sécurisée.",
                                color = Color(0xFF49454F).copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFF79747E),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color(0xFF1C1B1F),
                        unfocusedTextColor = Color(0xFF1C1B1F),
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error,
                        errorSupportingTextColor = MaterialTheme.colorScheme.error
                    ),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showApiKey) "Masquer" else "Afficher",
                                tint = if (isApiKeyError) MaterialTheme.colorScheme.error else Color(0xFF6750A4)
                            )
                        }
                    }
                )
            }

            // Section 2: Input Text (Contenu à synthétiser)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Contenu à synthétiser",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFFF3EDF7), shape = RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = {
                            if (it.length <= 5000) {
                                viewModel.textInput.value = it
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("text_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFF1D1B20),
                            lineHeight = 20.sp
                        ),
                        decorationBox = { innerTextField ->
                            if (textInput.isEmpty()) {
                                Text(
                                    text = "Saisissez votre texte ici (max 5000 caractères)...",
                                    color = Color(0xFF49454F).copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // Dynamic character counter and status below the input box
                val charCount = textInput.length
                val counterColor = when {
                    charCount >= 4800 -> Color(0xFFB3261E) // Material Red (Critical)
                    charCount >= 4000 -> Color(0xFFE28413) // Orange (Warning)
                    else -> Color(0xFF49454F) // M3 Neutral (Normal)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            charCount >= 4800 -> "Limite de caractères presque atteinte !"
                            charCount >= 4000 -> "Approche de la limite..."
                            else -> "Prêt pour la synthèse"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = counterColor.copy(alpha = 0.8f),
                            fontWeight = if (charCount >= 4000) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                    Text(
                        text = "$charCount / 5000",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = counterColor,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.testTag("char_counter")
                    )
                }
            }

            // Section 3: Voice Chips (Profil Vocal)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Profil Vocal",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val voices = listOf(
                        "Puck" to "Puck",
                        "Charon" to "Charon",
                        "Kore" to "Kore",
                        "Fenrir" to "Fenrir",
                        "Aoede" to "Aoede"
                    )
                    voices.forEach { (voiceId, displayName) ->
                        val isSelected = selectedVoice == voiceId
                        val bg = if (isSelected) Color(0xFFE8DEF8) else Color.Transparent
                        val textCol = if (isSelected) Color(0xFF1D192B) else Color(0xFF49454F)
                        val borderMod = if (isSelected) Modifier else Modifier.border(1.dp, Color(0xFF79747E), RoundedCornerShape(8.dp))

                        Surface(
                            onClick = { viewModel.selectedVoice.value = voiceId },
                            modifier = Modifier
                                .height(40.dp)
                                .then(borderMod)
                                .testTag("voice_chip_$voiceId"),
                            shape = RoundedCornerShape(8.dp),
                            color = bg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF6750A4), shape = RoundedCornerShape(4.dp))
                                    )
                                }
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = textCol,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Section 4: Tone Chips (Modulation du Ton)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Modulation du Ton",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF49454F),
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tones = listOf("Normal", "Chuchotement", "Douceur", "Dynamique", "Sérieux")
                    tones.forEach { tone ->
                        val isSelected = selectedTone == tone
                        val bg = if (isSelected) Color(0xFF6750A4) else Color.Transparent
                        val textCol = if (isSelected) Color.White else Color(0xFF49454F)
                        val borderMod = if (isSelected) Modifier else Modifier.border(1.dp, Color(0xFF79747E), RoundedCornerShape(8.dp))

                        Surface(
                            onClick = { viewModel.selectedTone.value = tone },
                            modifier = Modifier
                                .height(40.dp)
                                .then(borderMod)
                                .testTag("tone_chip_$tone"),
                            shape = RoundedCornerShape(8.dp),
                            color = bg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = tone,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = textCol,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Section 5: Loading Feedback inside scroll
            AnimatedVisibility(visible = statusMessage != null && !statusMessage!!.startsWith("SUCCESS:") && !statusMessage!!.startsWith("Erreur :")) {
                statusMessage?.let {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE8DEF8)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF6750A4),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = Color(0xFF1D192B)
                            )
                        }
                    }
                }
            }

            // Audio Player Component
            AnimatedVisibility(visible = lastGeneratedFileName != null) {
                lastGeneratedFileName?.let { fileName ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF3EDF7)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8DEF8))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = "Audio Player",
                                    tint = Color(0xFF6750A4),
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = "Aperçu de l'audio",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D1B20)
                                        )
                                    )
                                    Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF49454F)
                                        ),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Slider and Timestamps
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Slider(
                                    value = currentPosition.toFloat(),
                                    onValueChange = { viewModel.seekTo(it.toInt()) },
                                    valueRange = 0f..maxOf(1f, duration.toFloat()),
                                    modifier = Modifier.fillMaxWidth().testTag("audio_slider"),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF6750A4),
                                        activeTrackColor = Color(0xFF6750A4),
                                        inactiveTrackColor = Color(0xFFE8DEF8)
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    fun formatTime(ms: Int): String {
                                        val totalSec = ms / 1000
                                        val min = totalSec / 60
                                        val sec = totalSec % 60
                                        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                                    }
                                    Text(
                                        text = formatTime(currentPosition),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF49454F),
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                    Text(
                                        text = formatTime(duration),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF49454F),
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }

                            // Play/Pause button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                IconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color(0xFF6750A4), shape = RoundedCornerShape(24.dp))
                                        .testTag("play_pause_button")
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Lire",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 6: Action Footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFEF7FF))
                .padding(16.dp)
        ) {
            Button(
                onClick = { viewModel.generateAudio(context) },
                enabled = !isLoading && isApiKeyValid && textInput.trim().isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("generate_button"),
                shape = RoundedCornerShape(28.dp), // completely rounded (pill shape)
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6750A4),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF6750A4).copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Synthèse en cours...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Générer & Télécharger",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Générer & Télécharger",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
