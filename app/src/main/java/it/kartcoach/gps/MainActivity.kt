package it.kartcoach.gps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: KartCoachViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { KartCoachApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            vm.startSensorsAndGps()
        }
    }

    override fun onStop() {
        vm.stopSensorsAndGps()
        super.onStop()
    }
}

@Composable
fun KartCoachApp(vm: KartCoachViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf("coach") }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) vm.startSensorsAndGps()
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (!permissionGranted) {
            PermissionScreen { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when (screen) {
                    "coach" -> CoachScreen(
                        state = state,
                        onSetTarget = vm::setTargetFromText,
                        onClearTarget = vm::clearTarget
                    )
                    "analysis" -> AnalysisScreen(
                        state = state,
                        onFinish = {
                            val file = vm.finishSession(false)
                            if (file != null) Toast.makeText(context, "Salvato: $file", Toast.LENGTH_LONG).show()
                        },
                        onToggle = {
                            val file = vm.toggleRecording()
                            if (file != null) Toast.makeText(context, "Salvato: $file", Toast.LENGTH_LONG).show()
                        }
                    )
                    "setup" -> SetupScreen(
                        state = state,
                        onMorcone = vm::selectMorcone,
                        onSetStart = vm::setStartFinishHere,
                        onAdd = vm::addMarker,
                        onUndo = vm::removeLastMarker,
                        onClear = vm::clearMarkers
                    )
                }
                if (state.activeCue == null || screen != "coach") {
                    NavigationBar(
                        screen = screen,
                        onSelect = { screen = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        state.pendingTrackConfirmation?.let { candidate ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Pista riconosciuta") },
                text = { Text("Sei alla ${candidate.name}? Se confermi, da ora l'app la riconoscerà automaticamente quando torni qui.") },
                confirmButton = { Button(onClick = { vm.confirmPendingTrack(true) }) { Text("SÌ, CONFERMA") } },
                dismissButton = { TextButton(onClick = { vm.confirmPendingTrack(false) }) { Text("NO") } }
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("KARTCOACH GPS", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(20.dp))
            Text(
                "Lo smartphone registra GPS e sensori e deve essere fissato saldamente al kart. Durante la guida mostra solo segnali grandi; l'analisi dettagliata è pensata per i box.",
                color = Color.LightGray, textAlign = TextAlign.Center, fontSize = 18.sp
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("ATTIVA GPS") }
        }
    }
}

@Composable
private fun CoachScreen(
    state: LiveCoachState,
    onSetTarget: (String) -> Boolean,
    onClearTarget: () -> Unit
) {
    val cue = state.activeCue
    if (cue != null) {
        val bg = when (cue.type) {
            CueType.BRAKE -> Color(0xFFD50000)
            CueType.WAIT -> Color(0xFFFFC400)
            CueType.TURN -> Color(0xFFFF6D00)
            CueType.STRAIGHTEN -> Color(0xFF00C853)
            CueType.THROTTLE -> Color(0xFF00A0FF)
            CueType.FULL_THROTTLE -> Color(0xFF7C4DFF)
        }
        Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cue.type.label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 76.sp)
                if (cue.note.isNotBlank()) Text(cue.note, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        return
    }

    var targetInput by remember(state.targetLapMillis) {
        mutableStateOf(state.targetLapMillis?.let(::formatLap) ?: "31.500")
    }
    var targetError by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 82.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text(state.recognizedTrack?.name?.uppercase() ?: "PISTA NON CONFERMATA", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(modeLabel(state.learningMode), color = modeColor(state.learningMode), fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(gpsText(state), color = if (state.gpsAvailable) Color(0xFF69F0AE) else Color.Red, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${state.location?.speedMps?.times(3.6f)?.toInt() ?: 0}", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black)
                Text("km/h", color = Color.Gray, fontSize = 15.sp)
            }
        }

        if (!state.recording) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (state.targetLapMillis == null) Color(0xFF241B00) else Color(0xFF102019))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("TARGET PRIMA DI PARTIRE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.targetLapMillis == null) "Imposta il tempo che vuoi raggiungere. Esempio Morcone: 31.500"
                        else "Target attivo: ${formatLap(state.targetLapMillis)}. Partendo, la registrazione si avvierà automaticamente.",
                        color = Color.LightGray, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = targetInput,
                            onValueChange = { targetInput = it; targetError = false },
                            label = { Text("Tempo target") },
                            placeholder = { Text("31.500") },
                            singleLine = true,
                            isError = targetError,
                            modifier = Modifier.width(180.dp)
                        )
                        Button(onClick = { targetError = !onSetTarget(targetInput) }) {
                            Text(if (state.targetLapMillis == null) "IMPOSTA" else "AGGIORNA")
                        }
                        if (state.targetLapMillis != null) {
                            TextButton(onClick = onClearTarget) { Text("CAMBIA") }
                        }
                    }
                    if (targetError) Text("Formato non valido. Usa ad esempio 31.500 oppure 1:02.300", color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("GIRO", formatLap(state.currentLapMillis), big = true)
            Stat("TARGET", state.targetLapMillis?.let(::formatLap) ?: "--.---")
            Stat("ULTIMO", state.lastLapMillis?.let(::formatLap) ?: "--.---")
            Stat("BEST", state.bestLapMillis?.let(::formatLap) ?: "--.---")
        }

        Column {
            state.lastLapTargetDeltaMs?.let { delta ->
                Text(
                    "Ultimo vs target: ${formatSignedDelta(delta)}",
                    color = if (delta <= 0) Color(0xFF69F0AE) else Color(0xFFFFAB91),
                    fontSize = 19.sp, fontWeight = FontWeight.Black
                )
            }
            state.bestTargetDeltaMs?.let { delta ->
                Text(
                    "Best vs target: ${formatSignedDelta(delta)}",
                    color = if (delta <= 0) Color(0xFF69F0AE) else Color.White,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
            state.analysis?.let {
                Text("Potenziale interno: -${formatLap(it.potentialGainMs)}", color = Color(0xFF69F0AE), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(state.statusMessage, color = Color.Gray, fontSize = 14.sp, maxLines = 2)
        }
    }
}

@Composable
private fun AnalysisScreen(state: LiveCoachState, onFinish: () -> Unit, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 78.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ANALISI BOX", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(state.statusMessage, color = Color.LightGray, fontSize = 14.sp)
            }
            Button(onClick = if (state.recording) onFinish else onToggle) {
                Text(if (state.recording) "FINE SESSIONE" else "AVVIA MANUALE")
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("GIRI", state.lapCount.toString())
            Stat("TARGET", state.targetLapMillis?.let(::formatLap) ?: "--.---")
            Stat("BEST", state.bestLapMillis?.let(::formatLap) ?: "--.---")
            state.analysis?.let { Stat("IDEALE", formatLap(it.idealLapMs)) }
        }
        state.bestTargetDeltaMs?.let { delta ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (delta <= 0) "TARGET RAGGIUNTO: ${formatSignedDelta(delta)}" else "MANCANO AL TARGET: +${formatLap(delta)}",
                color = if (delta <= 0) Color(0xFF69F0AE) else Color(0xFFFFAB91),
                fontSize = 18.sp, fontWeight = FontWeight.Black
            )
        }
        state.analysis?.let { a ->
            val target = state.targetLapMillis
            if (target != null && state.bestLapMillis != null && state.bestLapMillis > target) {
                val idealGap = a.idealLapMs - target
                Text(
                    if (idealGap <= 0) "Il target è compatibile con i tuoi migliori micro-settori: va assemblato un giro più pulito."
                    else "Il tuo ideale attuale è ancora ${formatSignedDelta(idealGap)} dal target: servirà aumentare il livello in alcune zone, non solo unire i migliori settori.",
                    color = Color.LightGray, fontSize = 14.sp
                )
            }
        }

        if (state.entryTimingInsights.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("TIMING INGRESSO IMU", color = Color(0xFFFFD740), fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("Misura il ritardo carico/decelerazione → inizio rotazione. Il riferimento nasce dai tuoi giri più veloci, non da un valore fisso.", color = Color.LightGray, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            state.entryTimingInsights.forEach { insight ->
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF201B0D))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Curva ${insight.cornerIndex} · circa ${insight.distanceM.toInt()} m", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text(
                            "Misurato ${insight.measuredDelayMs} ms · riferimento ${insight.referenceDelayMs} ms · Δ ${if (insight.deltaMs >= 0) "+" else ""}${insight.deltaMs} ms",
                            color = if (insight.deltaMs < 0) Color(0xFFFFAB91) else Color.LightGray,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(insight.advice, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        val analysis = state.analysis
        if (analysis == null) {
            Text("Servono almeno 2 giri validi. I primi giri servono a imparare pista e guida; poi il confronto diventa automatico.", color = Color.Gray, fontSize = 17.sp)
        } else if (analysis.findings.isEmpty()) {
            Text("I migliori settori sono già molto coerenti. Continua a controllare il timing IMU degli ingressi.", color = Color.LightGray, fontSize = 17.sp)
        } else {
            Text("DOVE STAI LASCIANDO TEMPO", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            analysis.findings.forEach { f ->
                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${f.rank}. ${f.title}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp)
                            Text("+%.3f s".format(Locale.US, f.deltaMs / 1000.0), color = Color(0xFFFFAB91), fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(f.advice, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("circa ${f.distanceFromLapStartM.toInt()} m dal riferimento giro · ${f.evidence}", color = Color.Gray, fontSize = 13.sp)
                        Text("Segnale prossimo run: ${f.cueType.label}", color = modeColor(LearningMode.COACH_READY), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        state.lastSavedFile?.let {
            Spacer(Modifier.height(8.dp))
            Text("Telemetria salvata: Download/KartCoach/$it", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SetupScreen(
    state: LiveCoachState,
    onMorcone: () -> Unit,
    onSetStart: () -> Unit,
    onAdd: (CueType) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 76.dp)) {
        Text("SETUP / DIAGNOSTICA", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("Normalmente non devi usare questa schermata: serve per calibrazione o test.", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onMorcone) { Text("USA MORCONE") }
            Button(onClick = onSetStart, enabled = state.location != null) { Text("START/FINISH QUI") }
        }
        Spacer(Modifier.height(12.dp))
        Text("GPS: ${state.location?.let { "%.6f, %.6f".format(Locale.US, it.latitude, it.longitude) } ?: "attesa"}", color = Color.LightGray)
        Text("Precisione: ${state.location?.accuracyM?.let { if (it.isFinite()) "%.1f m".format(Locale.US, it) else "--" } ?: "--"}", color = Color.LightGray)
        Text("IMU: a=${"%.2f".format(Locale.US, state.sensors.linearAccelMps2)} m/s² · ω=${"%.2f".format(Locale.US, state.sensors.gyroMagnitudeRadS)} rad/s", color = Color.LightGray)
        Text("Linea appresa: ${if (state.track.startFinish != null) "SÌ" else "NO"}", color = Color.LightGray)
        Text("Marker coach: ${state.track.markers.size}", color = Color.LightGray)

        Spacer(Modifier.height(18.dp))
        Text("Marker manuali (opzionale):", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CueType.entries.filter { it != CueType.WAIT }.forEach { type ->
                OutlinedButton(onClick = { onAdd(type) }, enabled = state.location != null) { Text(type.label) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUndo, enabled = state.track.markers.isNotEmpty()) { Text("ANNULLA ULTIMO") }
            OutlinedButton(onClick = onClear, enabled = state.track.markers.isNotEmpty()) { Text("CANCELLA MARKER") }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, big: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = if (big) 39.sp else 27.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun NavigationBar(screen: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().height(66.dp), color = Color(0xFF151515)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            NavButton("COACH", screen == "coach") { onSelect("coach") }
            NavButton("ANALISI", screen == "analysis") { onSelect("analysis") }
            NavButton("SETUP", screen == "setup") { onSelect("setup") }
        }
    }
}

@Composable
private fun NavButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = RoundedCornerShape(8.dp)) {
        Text(text, color = if (selected) Color.White else Color.Gray, fontWeight = FontWeight.Bold)
    }
}

private fun modeLabel(mode: LearningMode): String = when (mode) {
    LearningMode.WAITING_FOR_TRACK -> "ATTESA PISTA"
    LearningMode.LEARNING_TRACK -> "APPRENDIMENTO PISTA"
    LearningMode.LEARNING_DRIVER -> "APPRENDIMENTO GUIDA"
    LearningMode.COACH_READY -> "COACH ATTIVO"
}

private fun modeColor(mode: LearningMode): Color = when (mode) {
    LearningMode.WAITING_FOR_TRACK -> Color.Gray
    LearningMode.LEARNING_TRACK -> Color(0xFFFFD740)
    LearningMode.LEARNING_DRIVER -> Color(0xFFFFAB40)
    LearningMode.COACH_READY -> Color(0xFF69F0AE)
}

private fun gpsText(state: LiveCoachState): String {
    val p = state.location ?: return "GPS: attesa fix"
    val acc = if (p.accuracyM.isFinite()) " ±%.1fm".format(Locale.US, p.accuracyM) else ""
    return "GPS OK$acc · IMU attiva"
}

private fun formatSignedDelta(ms: Long): String {
    val sign = if (ms > 0) "+" else if (ms < 0) "−" else "±"
    return "$sign${formatLap(kotlin.math.abs(ms))}"
}

private fun formatLap(ms: Long): String {
    val totalSeconds = ms / 1000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds - minutes * 60
    return if (minutes > 0) "%d:%06.3f".format(Locale.US, minutes, seconds) else "%05.3f".format(Locale.US, seconds)
}
