package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.ArabicStrings
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalLanguage
import com.example.core.i18n.LocalStrings
import com.example.data.firebase.AuthManager
import com.example.data.firebase.WChatRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallOverlayScreen
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.WChatTheme
import com.example.ui.viewmodel.WChatViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

const val ROUTE_HOME = "home"
const val ROUTE_CHAT_DETAIL = "chat_detail/{chatId}"

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppContent()
        }
    }
}

@Composable
fun AppContent() {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    var unauthLanguage by remember { mutableStateOf(AppLanguage.ARABIC) }
    var unauthDarkTheme by remember { mutableStateOf(false) }

    val user = currentUser
    if (user == null) {
        val strings = if (unauthLanguage == AppLanguage.ARABIC) ArabicStrings else EnglishStrings
        val layoutDir = if (unauthLanguage == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalLanguage provides unauthLanguage,
            LocalLayoutDirection provides layoutDir,
        ) {
            WChatTheme(darkTheme = unauthDarkTheme) {
                AuthScreen(
                    onAuthSuccess = {},
                    onLanguageChange = { unauthLanguage = it },
                    darkTheme = unauthDarkTheme,
                    onToggleTheme = { unauthDarkTheme = it },
                )
            }
        }
    } else {
        key(user.uid) {
            AuthenticatedWChatApp(
                uid = user.uid,
                email = user.email.orEmpty(),
                isEmailVerified = user.isEmailVerified,
            )
        }
    }
}

@Composable
private fun AuthenticatedWChatApp(
    uid: String,
    email: String,
    isEmailVerified: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: WChatViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val repository = WChatRepository(app.applicationContext)
                val crypto = E2eeCryptoManager(app.applicationContext)
                WChatViewModel(
                    repository = repository,
                    cryptoManager = crypto,
                    currentUserId = uid,
                    currentUserEmail = email,
                    isEmailVerified = isEmailVerified,
                )
            }
        },
    )

    val language by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
    val activeCall by viewModel.activeCall.collectAsStateWithLifecycle()

    val strings = if (language == AppLanguage.ARABIC) ArabicStrings else EnglishStrings
    val layoutDir = if (language == AppLanguage.ARABIC) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalLanguage provides language,
        LocalLayoutDirection provides layoutDir,
    ) {
        WChatTheme(darkTheme = darkTheme) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideScreen = maxWidth >= 600.dp
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                ) {
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            viewModel = viewModel,
                            isWideScreen = isWideScreen,
                            onOpenChat = { chatId ->
                                navController.navigate("chat_detail/$chatId")
                            },
                            onSignOut = {
                                AuthManager.signOut(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onBeforeSignOut = { viewModel.setOfflineBeforeSignOut() },
                                )
                            },
                        )
                    }
                    composable(
                        route = ROUTE_CHAT_DETAIL,
                        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
                        ChatDetailScreen(
                            chatId = chatId,
                            viewModel = viewModel,
                            onBack = {
                                viewModel.setActiveChat(null)
                                navController.popBackStack()
                            },
                        )
                    }
                }

                val call = activeCall
                if (call != null) {
                    CallOverlayScreen(
                        call = call,
                        myUid = uid,
                        onAnswer = { viewModel.answerCall(call) },
                        onEndOrDecline = { duration, declined ->
                            viewModel.endOrDeclineCall(call, duration, declined)
                        },
                    )
                }
            }
        }
    }
}
