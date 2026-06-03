package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.brain.CommandResult
import com.example.brain.CommandType
import com.example.speech.SpeechState
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.ContactsResolver

class MainActivity : ComponentActivity() {

    private lateinit var jarvis: JarvisController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Core Voice assistant controller
        jarvis = JarvisController(this)

        handleVoiceTrigger(intent)

        setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        JarvisDashboard(
                            controller = jarvis,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceTrigger(intent)
    }

    private fun handleVoiceTrigger(intent: android.content.Intent?) {
        val trigger = (intent?.getBooleanExtra("LAUNCH_VOICE_TRIGGER", false) ?: false) ||
                (intent?.action == android.content.Intent.ACTION_ASSIST)
        if (trigger) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // Short post delay to ensure UI components are loaded fully
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    jarvis.toggleListening()
                }, 400)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        jarvis.destroy()
    }
}

@Composable
fun JarvisDashboard(
    controller: JarvisController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val speechState by remember { controller.state }
    val transcriptionText by remember { controller.transcriptionText }
    val activeResult by remember { controller.activeResult }
    val errorMessage by remember { controller.errorMessage }
    val historyLog = controller.commandHistory
    
    var isDefaultAssistant by remember {
        mutableStateOf(false)
    }

    // Check default assistant status when the view is mounted
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            isDefaultAssistant = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        }
    }

    val requestRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            isDefaultAssistant = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        }
    }

    // List of dynamic contacts on the device
    var sampleContacts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Request permissions dynamically
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCallPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasRecordPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        hasContactsPermission = permissions[Manifest.permission.READ_CONTACTS] ?: false
        hasCallPermission = permissions[Manifest.permission.CALL_PHONE] ?: false
        if (hasRecordPermission) {
            Toast.makeText(context, "הרשאת מיקרופון אושרה!", Toast.LENGTH_SHORT).show()
        }
        if (hasContactsPermission) {
            sampleContacts = ContactsResolver.getSampleContacts(context)
        }
    }

    // Load initial sample contacts if authorized
    LaunchedEffect(hasContactsPermission) {
        if (hasContactsPermission) {
            sampleContacts = ContactsResolver.getSampleContacts(context)
        }
    }

    // Master deep space blue color scheme
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF030712), Color(0xFF0F172A), Color(0xFF1E1B4B))
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant top assistant bar
            HeaderSection(
                hasRecordPermission = hasRecordPermission,
                hasContactsPermission = hasContactsPermission,
                hasCallPermission = hasCallPermission,
                onRequestPermissions = {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Jarvis sci-fi reactive visual reactor core
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(Color(0x0AFFFFFF))
                    .clickable {
                        if (!hasRecordPermission) {
                            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else {
                            controller.toggleListening()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                ReactorCore(state = speechState)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Speech states & Transcription Card
            TranscriptionContainer(
                state = speechState,
                text = transcriptionText,
                error = errorMessage
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Last Recognized Command Status card
            AnimatedVisibility(
                visible = activeResult != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut()
            ) {
                activeResult?.let { result ->
                    CommandStatusCard(result = result)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scrollable contents: quick actions & contacts list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // system default-assistant option card
                item {
                    AssistantRolePromoCard(
                        isDefault = isDefaultAssistant,
                        onRequestSetting = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                                if (roleManager != null && roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                                    val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)
                                    requestRoleLauncher.launch(intent)
                                } else {
                                    try {
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "לא ניתן לפתוח את הגדרות המכשיר", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "לא ניתן לפתוח את הגדרות המכשיר", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                // Quick Sample Commands
                item {
                    Text(
                        text = "פקודות מהירות לדוגמה בהן תוכל להתנסות:",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    QuickActionSection { sampleCmd ->
                        controller.executeTextCommand(sampleCmd)
                    }
                }

                // Authorized Device Contacts section
                if (hasContactsPermission && sampleContacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "אנשי קשר מהמכשיר שלך (לחץ לחיוג מהיר):",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(sampleContacts) { contact ->
                        ContactRowItem(
                            name = contact.first,
                            number = contact.second,
                            onDialClick = {
                                controller.executeTextCommand("תתקשר אל ${contact.first}")
                            },
                            onMessageClick = {
                                controller.executeTextCommand("וואטסאפ אל ${contact.first}")
                            }
                        )
                    }
                }

                // Command logs history
                if (historyLog.isNotEmpty()) {
                    item {
                        Text(
                            text = "היסטוריית פקודות אחרונות:",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(historyLog) { logItem ->
                        HistoryLogCard(result = logItem)
                    }
                }
            }
        }

        // Floating Action Button at bottom center for voice input as requested
        FloatingMicrophoneButton(
            listeningState = speechState,
            onClick = {
                if (!hasRecordPermission || !hasCallPermission) {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.CALL_PHONE
                        )
                    )
                } else {
                    controller.toggleListening()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
fun HeaderSection(
    hasRecordPermission: Boolean,
    hasContactsPermission: Boolean,
    hasCallPermission: Boolean,
    onRequestPermissions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "עוזר חכם",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Text(
                    text = "עוזר קולי פעיל | Jarvis",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        if (!hasRecordPermission || !hasContactsPermission || !hasCallPermission) {
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3B82F6),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "אישורים ויכולות",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "הרשאות",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun ReactorCore(
    state: SpeechState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "reactor")

    // Animations for outer pulsing arcs depending on state
    val normalPulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val listeningPulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_pulse"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val reactorColor by animateColorAsState(
        targetValue = when (state) {
            SpeechState.IDLE -> Color(0xFF06B6D4) // Bright Cyan
            SpeechState.LISTENING -> Color(0xFFEF4444) // Intense active Red
            SpeechState.PROCESSING -> Color(0xFFD946EF) // Processing Magenta
            SpeechState.ERROR -> Color(0xFFF97316) // Error Orange
        },
        animationSpec = tween(500),
        label = "color"
    )

    val finalPulse = if (state == SpeechState.LISTENING) listeningPulse else normalPulse

    Box(
        modifier = modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasCenter = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val maxRadius = (size.minDimension / 2f) - 16.dp.toPx()

            // 1. Draw outermost background pulsing glow
            drawCircle(
                color = reactorColor.copy(alpha = 0.08f),
                radius = maxRadius * finalPulse,
                center = canvasCenter
            )

            // 2. Draw second glowing shell
            drawCircle(
                color = reactorColor.copy(alpha = 0.15f),
                radius = maxRadius * 0.85f * finalPulse,
                center = canvasCenter,
                style = Stroke(width = 2.dp.toPx())
            )

            // 3. Draw dual futuristic revolving segment arcs representing computing
            val strokeWidth = 5.dp.toPx()
            val arcSize = maxRadius * 1.4f
            val arcOffsetRadius = maxRadius * 0.7f
            drawArc(
                color = reactorColor,
                startAngle = rotationAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(canvasCenter.x - arcOffsetRadius, canvasCenter.y - arcOffsetRadius),
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                color = reactorColor.copy(alpha = 0.4f),
                startAngle = rotationAngle + 180f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(canvasCenter.x - arcOffsetRadius, canvasCenter.y - arcOffsetRadius),
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 4. Draw central core sphere
            drawCircle(
                color = reactorColor,
                radius = maxRadius * 0.45f * if (state == SpeechState.LISTENING) finalPulse else 1f,
                center = canvasCenter
            )

            // 5. Draw computer interface points
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = maxRadius * 0.15f,
                center = canvasCenter
            )
        }

        // Central icon
        Icon(
            imageVector = when (state) {
                SpeechState.IDLE -> Icons.Default.Notifications
                SpeechState.LISTENING -> Icons.Default.PlayArrow
                SpeechState.PROCESSING -> Icons.Default.Refresh
                SpeechState.ERROR -> Icons.Default.Warning
            },
            contentDescription = null,
            tint = Color(0xFF0F172A),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun TranscriptionContainer(
    state: SpeechState,
    text: String,
    error: String?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x1E334155),
            contentColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val stateText = when (state) {
                SpeechState.IDLE -> "מערכת מוכנה לפקודה שלך"
                SpeechState.LISTENING -> "מקשיב לקולך כעת..."
                SpeechState.PROCESSING -> "מנתח פקודה קולית..."
                SpeechState.ERROR -> "אירעה שגיאה בזיהוי"
            }

            val stateColor = when (state) {
                SpeechState.IDLE -> Color(0xFF38BDF8)
                SpeechState.LISTENING -> Color(0xFFF87171)
                SpeechState.PROCESSING -> Color(0xFFE879F9)
                SpeechState.ERROR -> Color(0xFFFB923C)
            }

            Text(
                text = stateText,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = stateColor,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = Color(0xFFFCA5A5),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                )
            } else {
                Text(
                    text = text.ifEmpty { "אמור פקודה כמו \'מה השעה לונדון\' או לחץ על כפתור המיקרופון..." },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        color = if (text.isEmpty()) Color(0xFF64748B) else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun CommandStatusCard(result: CommandResult) {
    val statusColor = when (result.type) {
        CommandType.CALL -> Color(0xFF10B981)
        CommandType.WHATSAPP -> Color(0xFF06B6D4)
        CommandType.SPOTIFY -> Color(0xFF84CC16)
        CommandType.TIME -> Color(0xFFEAB308)
        CommandType.CLOCK -> Color(0xFFF43F5E)
        CommandType.UNKNOWN -> Color(0xFF64748B)
    }

    val icon = when (result.type) {
        CommandType.CALL -> Icons.Default.Phone
        CommandType.WHATSAPP -> Icons.Default.Send
        CommandType.SPOTIFY -> Icons.Default.PlayArrow
        CommandType.TIME -> Icons.Default.DateRange
        CommandType.CLOCK -> Icons.Default.Refresh
        CommandType.UNKNOWN -> Icons.Default.Warning
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.12f),
            contentColor = Color.White
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(statusColor.copy(alpha = 0.3f), Color.Transparent))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (result.type) {
                        CommandType.CALL -> "חיוג מהיר"
                        CommandType.WHATSAPP -> "הודעת וואטסאפ"
                        CommandType.SPOTIFY -> "נגן מוזיקה בספוטיפיי"
                        CommandType.TIME -> "בדיקת שעה"
                        CommandType.CLOCK -> "הגדרת שעון וטיימר"
                        CommandType.UNKNOWN -> "פקודה לא מזוהה"
                    },
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = result.feedbackText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
fun QuickActionSection(
    onCommandSelected: (String) -> Unit
) {
    val quickCommands = listOf(
        "מה השעה?",
        "תתקשר אל 100",
        "נגן שלמה ארצי",
        "וואטסאפ אל יוסי"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickCommands.forEach { command ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B))
                    .clickable { onCommandSelected(command) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ContactRowItem(
    name: String,
    number: String,
    onDialClick: () -> Unit,
    onMessageClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x0EFFFFFF),
            contentColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                        .clip(CircleShape)
                        .background(Color(0xFF3B82F6).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.firstOrNull()?.toString() ?: "C",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFF60A5FA),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = number,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Call Icon Button
                IconButton(
                    onClick = onDialClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x1F10B981))
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "התקשר לאיש קשר",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // WhatsApp Icon Button
                IconButton(
                    onClick = onMessageClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x1F06B6D4))
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "שלח הודעת וואטסאפ לכתובת",
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryLogCard(result: CommandResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x05FFFFFF),
            contentColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val (icon, color) = when (result.type) {
                CommandType.CALL -> Pair(Icons.Default.Phone, Color(0xFF10B981))
                CommandType.WHATSAPP -> Pair(Icons.Default.Send, Color(0xFF06B6D4))
                CommandType.SPOTIFY -> Pair(Icons.Default.PlayArrow, Color(0xFF84CC16))
                CommandType.TIME -> Pair(Icons.Default.DateRange, Color(0xFFEAB308))
                CommandType.CLOCK -> Pair(Icons.Default.Refresh, Color(0xFFF43F5E))
                CommandType.UNKNOWN -> Pair(Icons.Default.Warning, Color(0xFF64748B))
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = result.feedbackText,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFCBD5E1)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun FloatingMicrophoneButton(
    listeningState: SpeechState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_fab")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_anim"
    )

    val activeColor = if (listeningState == SpeechState.LISTENING) Color(0xFFEF4444) else Color(0xFF0284C7)

    FloatingActionButton(
        onClick = onClick,
        containerColor = activeColor,
        contentColor = Color.White,
        modifier = modifier
            .testTag("voice_input_fab")
            .size(72.dp)
            .clip(CircleShape)
            .then(
                if (listeningState == SpeechState.LISTENING) {
                    Modifier.padding((6 * (pulseScale - 1f)).dp)
                } else {
                    Modifier
                }
            ),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
    ) {
        Icon(
            imageVector = if (listeningState == SpeechState.LISTENING) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = "פעולה קולית / מיקרופון",
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun AssistantRolePromoCard(
    isDefault: Boolean,
    onRequestSetting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("assistant_role_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDefault) Color(0x1F10B981) else Color(0x1F3B82F6),
            contentColor = Color.White
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    if (isDefault) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFF3B82F6).copy(alpha = 0.4f),
                    Color.Transparent
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isDefault) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF3B82F6).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDefault) Icons.Default.CheckCircle else Icons.Default.Star,
                        contentDescription = null,
                        tint = if (isDefault) Color(0xFF10B981) else Color(0xFF3B82F6),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = if (isDefault) "עוזר דיגיטלי ברירת מחדל פעיל! 🤖" else "הגדר כעוזר דיגיטלי ברירת מחדל",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isDefault) Color(0xFF34D399) else Color(0xFF60A5FA)
                    )
                )
            }

            Text(
                text = if (isDefault) {
                    "מעולה! 'עוזר חכם' מוגדר כעוזר המערכתי הראשי שלך. כעת תוכל להפעיל אותו במהירות מכל מסך באמצעות לחיצה ארוכה על כפתור הבית או ביצוע מחווה קולית מתאימה במכשיר."
                } else {
                    "כדי להפעיל את 'עוזר חכם' במהירות מכל מסך באמצעות קיצורי דרך מערכתיים (לחיצה ארוכה על כפתור הבית או מחוות החלקה), הגדר אותה כאפליקציית העוזר הדיגיטלי המוגדרת כברירת מחדל בהגדרות המכשיר."
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFFCBD5E1),
                    lineHeight = 20.sp
                )
            )

            if (!isDefault) {
                Button(
                    onClick = onRequestSetting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3B82F6),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "הגדר כעוזר ברירת מחדל",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
