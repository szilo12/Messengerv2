package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FirebaseService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit
) {
    var isRegisterState by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val isLoading by FirebaseService.isLoading.collectAsState()
    val authError by FirebaseService.authError.collectAsState()
    val isAvailable by FirebaseService.isFirebaseAvailable.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A) // Slate Dark
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 400.dp)
            ) {
                // Centered App Logo & Gradient Title
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF0084FF), Color(0xFFEC4899))
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = "Messenger Logo",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Text(
                    text = "Olyna Messenger",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isRegisterState) "Hozd létre az új fiókodat másodpercek alatt" else "Üdvözlünk! Jelentkezz be a folytatáshoz",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Error Message Dialog Inline
                authError?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Error, contentDescription = "Error", tint = Color(0xFFEF4444))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(err, color = Color(0xFFFCA5A5), fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }

                if (!isAvailable) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.12f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = "Warning", tint = Color(0xFFF59E0B))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Firebase nincs teljesen konfigurálva. Offline/Helyi teszt üzemmódban futunk.",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Auth Fields Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (isRegisterState) {
                            // Full Name Input
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Teljes Név", color = Color.White.copy(alpha = 0.5f)) },
                                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.5f)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("reg_name_input"),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF0084FF),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                            )
                        }

                        // Email Address Input
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("E-mail Cím", color = Color.White.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null, tint = Color.White.copy(alpha = 0.5f)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_email_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0084FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            )
                        )

                        // Password Input
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Jelszó", color = Color.White.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.5f)) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = "Show/Hide Password",
                                        tint = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0084FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Submit Action Button
                val context = androidx.compose.ui.platform.LocalContext.current
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (!isAvailable) {
                            // If Firebase is details unavailable, run standard localized success flow
                            onAuthSuccess()
                            return@Button
                        }
                        if (email.isBlank() || password.isBlank() || (isRegisterState && displayName.isBlank())) {
                            return@Button
                        }
                        coroutineScope.launch {
                            val success = if (isRegisterState) {
                                FirebaseService.registerUser(context, email.trim(), password, displayName.trim())
                            } else {
                                FirebaseService.loginUser(context, email.trim(), password)
                            }
                            if (success) {
                                onAuthSuccess()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("auth_submit_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0084FF),
                        disabledContainerColor = Color(0xFF0084FF).copy(alpha = 0.5f)
                    ),
                    enabled = !isLoading && email.isNotBlank() && password.length >= 6
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isRegisterState) "Regisztráció" else "Bejelentkezés",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }

                // Switch Between States link text
                Text(
                    text = if (isRegisterState) "Már van fiókod? Jelentkezz be!" else "Nincs még fiókod? Regisztrálj most!",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF38BDF8), // Light Sky Blue
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clickable(enabled = !isLoading) {
                            isRegisterState = !isRegisterState
                        }
                        .padding(8.dp)
                        .testTag("auth_toggle")
                )
            }
        }
    }
}
