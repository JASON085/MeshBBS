package com.meshtastic.bbs.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.ui.theme.*
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

@Composable
fun LoginScreen(vm: BbsViewModel) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val focus    = LocalFocusManager.current

    if (!state.isConnected) {
        LaunchedEffect(Unit) {
            vm.navigateTo(Screen.ServerSelect)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
        )
        return
    }

    // Keep screen awake while logging in (waiting for LoRa response can take time)
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPw   by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0D1A35), Color(0xFF050810)),
                    center = Offset.Zero,
                    radius = 1400f,
                )
            )
    ) {
        // Subtle grid lines (cyberpunk grid background)
        Canvas(Modifier.fillMaxSize()) {
            val step = 52.dp.toPx()
            val lc   = Color(0x0A4B8FFF)
            var x = 0f; while (x <= size.width)  { drawLine(lc, Offset(x, 0f), Offset(x, size.height), 0.8f); x += step }
            var y = 0f; while (y <= size.height) { drawLine(lc, Offset(0f, y), Offset(size.width, y),  0.8f); y += step }
        }

        // Glowing orbs (decorative)
        Box(
            Modifier.size(300.dp).offset((-60).dp, (-60).dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(MeshViolet.copy(alpha = 0.12f), Color.Transparent)))
        )
        Box(
            Modifier.size(200.dp).align(Alignment.BottomEnd).offset(40.dp, 40.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Primary.copy(alpha = 0.10f), Color.Transparent)))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))

            // Logo
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(MeshViolet.copy(0.3f), Primary.copy(0.2f))))
                    .border(1.dp, MeshViolet.copy(0.4f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.WifiTethering, null, tint = Primary, modifier = Modifier.size(36.dp))
            }

            Spacer(Modifier.height(20.dp))

            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(brush = Brush.linearGradient(listOf(Primary, MeshViolet)))) {
                        append("Mesh")
                    }
                    withStyle(SpanStyle(color = Tertiary)) { append("BBS") }
                },
                fontSize    = 42.sp,
                fontWeight  = FontWeight.Black,
            )

            Text(
                "透過 Meshtastic APP 連接 LoRa 節點",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
            )
            Spacer(Modifier.height(44.dp))

            GlassCard {
                Column(Modifier.padding(22.dp)) {

                        // Connected indicator + selected server ID
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(AuthorGreen))
                            Spacer(Modifier.width(6.dp))
                            Text("Meshtastic 已連線", color = AuthorGreen,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "BBS 伺服器：${state.selectedServerName.ifBlank { state.selectedServerId.ifBlank { "廣播" } }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                        )
                        Spacer(Modifier.height(16.dp))

                        BbsTextField(
                            value         = username,
                            onValueChange = { username = it },
                            label         = "帳號",
                            leadingIcon   = { Icon(Icons.Default.Person, null, Modifier.size(20.dp)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
                        )
                        Spacer(Modifier.height(12.dp))
                        BbsTextField(
                            value                = password,
                            onValueChange        = { password = it },
                            label                = "密碼",
                            leadingIcon          = { Icon(Icons.Default.Lock, null, Modifier.size(20.dp)) },
                            trailingIcon         = {
                                IconButton({ showPw = !showPw }) {
                                    Icon(
                                        if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        null, tint = OnSurfaceVariant
                                    )
                                }
                            },
                            visualTransformation = if (showPw) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focus.clearFocus()
                                if (username.isNotBlank() && password.isNotBlank())
                                    vm.login(username, password)
                            })
                        )
                        Spacer(Modifier.height(18.dp))
                        GradientButton(
                            text    = if (state.isLoading) "登入中…" else "登入 BBS",
                            enabled = username.isNotBlank() && password.isNotBlank() && !state.isLoading,
                            onClick = { focus.clearFocus(); vm.login(username, password) },
                            loading = state.isLoading,
                            gradient = listOf(MeshViolet, Primary),
                        )

                        Card(
                            modifier = Modifier.padding(top = 12.dp),
                            colors   = CardDefaults.cardColors(containerColor = TertiaryContainer.copy(0.3f)),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, null, tint = Tertiary,
                                    modifier = Modifier.size(15.dp).padding(top = 1.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "新用戶首次登入會自動建立帳號，直接輸入想使用的帳號與密碼即可。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnTertiaryContainer,
                                )
                            }
                        }


                        // Disconnect link
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick  = { vm.navigateTo(Screen.ServerSelect) },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("切換 BBS 伺服器節點",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceVariant)
                        }
                }
            }

            // Bluetooth permission warning
            if (!state.btPermissionGranted) {
                val ctx = LocalContext.current
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1A00)),
                    shape  = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF8C00).copy(0.6f)),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BluetoothDisabled, null,
                                tint = Color(0xFFFF8C00), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "藍牙權限尚未完整開啟。\n" +
                                "Meshtastic APP 需要藍牙相關權限才能連線 LoRa 裝置。\n" +
                                "請到 Android 設定中為本 App 開啟相關權限。",
                                color = Color(0xFFFFCC80),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                ).apply {
                                    data = android.net.Uri.fromParts("package", ctx.packageName, null)
                                }
                                ctx.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C00)
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Settings, null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("前往 App 設定開啟藍牙權限")
                        }
                    }
                }
            }


            // Error banner
            state.error?.let { err ->
                Spacer(Modifier.height(14.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = ErrorContainer),
                    shape  = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(err, color = OnErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            IconButton({ vm.clearError() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, null,
                                    tint = OnErrorContainer, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ?? Shared UI helpers (used by other screens too) ??????????????

@Composable
fun GlassCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(20.dp))
    ) { content() }
}

@Composable
fun BbsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    leadingIcon:  (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value                = value,
        onValueChange        = onValueChange,
        label                = { Text(label) },
        placeholder          = { Text(placeholder, color = OnSurfaceVariant.copy(0.5f)) },
        leadingIcon          = leadingIcon,
        trailingIcon         = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions      = keyboardOptions,
        keyboardActions      = keyboardActions,
        singleLine           = true,
        modifier             = Modifier.fillMaxWidth(),
        shape                = RoundedCornerShape(12.dp),
        colors               = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Primary,
            unfocusedBorderColor = Outline,
            focusedLabelColor    = Primary,
            cursorColor          = Primary,
        )
    )
}

@Composable
fun GradientButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean  = false,
    gradient: List<Color> = listOf(Primary, Secondary),
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Brush.horizontalGradient(gradient)
                else Brush.horizontalGradient(listOf(SurfaceVariant, SurfaceVariant))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                color       = Color.White,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text  = text,
                color = if (enabled) Color.White else OnSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}


