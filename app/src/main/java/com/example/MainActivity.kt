package com.example

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.PreferencesStore
import com.example.data.model.UpiTransaction
import com.example.data.repository.PhonePayRepository
import com.example.receipt.ReceiptPdfGenerator
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.signOutUser
import com.example.ui.components.ReceiptDetailDialog
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PaymentReviewScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.theme.PhonePayTheme
import com.example.ui.viewmodel.PhonePayViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_SCAN = "scan"
const val ROUTE_SCAN_MANUAL = "scan_manual"
const val ROUTE_PAYMENT_REVIEW = "payment_review"
const val ROUTE_HISTORY = "history"
const val ROUTE_SECURITY = "security"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhonePayRoot()
        }
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<com.google.firebase.auth.FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
private fun LocalizedWrapper(
    languageCode: String,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val (localizedContext, localizedConfig) = remember(languageCode, baseConfig) {
        val locale = Locale.forLanguageTag(if (languageCode == "hi") "hi" else "en")
        Locale.setDefault(locale)
        val newConfig = Configuration(baseConfig).apply {
            setLocale(locale)
        }
        val ctx: Context = baseContext.createConfigurationContext(newConfig)
        ctx to newConfig
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
    ) {
        content()
    }
}

@Composable
fun PhonePayRoot() {
    val context = LocalContext.current
    val prefsStore = remember(context) { PreferencesStore(context.applicationContext) }
    val preferredLanguage by prefsStore.preferredLanguage.collectAsStateWithLifecycle(initialValue = "en")
    val scope = rememberCoroutineScope()

    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val applyLanguage: (String) -> Unit = { code ->
        val normalized = if (code == "hi") "hi" else "en"
        scope.launch { prefsStore.setLanguage(normalized) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
    }

    PhonePayTheme(isHindi = preferredLanguage == "hi") {
        LocalizedWrapper(languageCode = preferredLanguage) {
            val user = currentUser
            if (user == null) {
                val credentialManager = remember(context) { CredentialManager.create(context) }
                LaunchedEffect(Unit) {
                    attemptAutoSignIn(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = {},
                        onUnauthenticated = {},
                        scope = scope,
                    )
                }
                AuthScreen(
                    currentLanguage = preferredLanguage,
                    onLanguageChange = applyLanguage,
                    onAuthSuccess = {},
                )
            } else {
                key(user.uid) {
                    AuthenticatedPhonePayApp(
                        currentUserId = user.uid,
                        displayName = user.displayName ?: "Phone Pay User",
                        email = user.email ?: "",
                        preferredLanguage = preferredLanguage,
                        onApplyLanguage = applyLanguage,
                    )
                }
            }
        }
    }
}

@Composable
fun AuthenticatedPhonePayApp(
    currentUserId: String,
    displayName: String,
    email: String,
    preferredLanguage: String,
    onApplyLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val navController = rememberNavController()

    val viewModel: PhonePayViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                PhonePayViewModel(
                    repository = PhonePayRepository(db = db, auth = Firebase.auth),
                    preferencesStore = PreferencesStore(app.applicationContext),
                    currentUserId = currentUserId,
                    userDisplayName = displayName,
                    userEmail = email,
                    packageName = app.packageName,
                )
            }
        },
    )

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val transactionsState by viewModel.transactionsState.collectAsStateWithLifecycle()
    val dailySummary by viewModel.dailySummary.collectAsStateWithLifecycle()
    val isPspConfigured by viewModel.isPspConfigured.collectAsStateWithLifecycle()
    val customBackendUrl by viewModel.customBackendUrl.collectAsStateWithLifecycle()
    val selectedQr by viewModel.selectedQr.collectAsStateWithLifecycle()
    val activeFlow by viewModel.activeFlow.collectAsStateWithLifecycle()
    val selectedReceiptTx by viewModel.selectedReceiptTx.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    // Sync profile language preference if present
    val profileLang = (profileState as? UiState.Success)?.data?.preferredLanguage
    LaunchedEffect(profileLang) {
        if (profileLang == "hi" || profileLang == "en") {
            if (profileLang != preferredLanguage) {
                onApplyLanguage(profileLang)
            }
        }
    }

    val sharePdfReceipt: (UpiTransaction) -> Unit = { tx ->
        runCatching {
            val uri = ReceiptPdfGenerator.generatePdfReceipt(context, tx)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PHONE PAY Receipt - ${tx.payeeName}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share UPI Receipt"))
        }
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route ?: ROUTE_HOME
    val showNavigationChrome = currentRoute in setOf(ROUTE_HOME, ROUTE_HISTORY, ROUTE_SECURITY)

    val navItems = listOf(
        Triple(ROUTE_HOME, R.string.nav_home, Icons.Filled.Home to Icons.Outlined.Home),
        Triple(ROUTE_SCAN, R.string.nav_scan, Icons.Filled.QrCodeScanner to Icons.Outlined.QrCodeScanner),
        Triple(ROUTE_HISTORY, R.string.nav_history, Icons.Filled.History to Icons.Outlined.History),
        Triple(ROUTE_SECURITY, R.string.nav_security, Icons.Filled.Security to Icons.Outlined.Security),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp

        Scaffold(
            bottomBar = {
                if (showNavigationChrome && !useRail) {
                    NavigationBar {
                        navItems.forEach { (route, labelRes, icons) ->
                            val selected = currentRoute == route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (route == ROUTE_SCAN) {
                                        navController.navigate(ROUTE_SCAN)
                                    } else {
                                        navController.navigate(route) {
                                            popUpTo(ROUTE_HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) icons.first else icons.second,
                                        contentDescription = stringResource(labelRes),
                                    )
                                },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.testTag("nav_item_$route"),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (showNavigationChrome && useRail) {
                    NavigationRail {
                        navItems.forEach { (route, labelRes, icons) ->
                            val selected = currentRoute == route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    if (route == ROUTE_SCAN) {
                                        navController.navigate(ROUTE_SCAN)
                                    } else {
                                        navController.navigate(route) {
                                            popUpTo(ROUTE_HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) icons.first else icons.second,
                                        contentDescription = stringResource(labelRes),
                                    )
                                },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.testTag("rail_item_$route"),
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(ROUTE_HOME) {
                        HomeScreen(
                            profileState = profileState,
                            transactionsState = transactionsState,
                            dailySummary = dailySummary,
                            isPspConfigured = isPspConfigured,
                            currentLanguage = preferredLanguage,
                            onLanguageChange = { code ->
                                onApplyLanguage(code)
                                viewModel.setLanguage(code)
                            },
                            onScanQrClick = { navController.navigate(ROUTE_SCAN) },
                            onManualVpaClick = { navController.navigate(ROUTE_SCAN_MANUAL) },
                            onOpenSecurityClick = { navController.navigate(ROUTE_SECURITY) },
                            onTransactionClick = { tx -> viewModel.openReceipt(tx) },
                        )
                    }

                    composable(ROUTE_SCAN) {
                        QrScannerScreen(
                            initialShowManualEntry = false,
                            onQrParsed = { qr ->
                                viewModel.selectQrPayload(qr)
                                navController.navigate(ROUTE_PAYMENT_REVIEW)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(ROUTE_SCAN_MANUAL) {
                        QrScannerScreen(
                            initialShowManualEntry = true,
                            onQrParsed = { qr ->
                                viewModel.selectQrPayload(qr)
                                navController.navigate(ROUTE_PAYMENT_REVIEW)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(ROUTE_PAYMENT_REVIEW) {
                        val qr = selectedQr
                        if (qr != null) {
                            val profile = (profileState as? UiState.Success)?.data
                                ?: com.example.data.model.UserProfile(userId = currentUserId)
                            PaymentReviewScreen(
                                qrPayload = qr,
                                profile = profile,
                                activeFlow = activeFlow,
                                viewModel = viewModel,
                                onSharePdfReceipt = sharePdfReceipt,
                                onDone = {
                                    viewModel.clearActivePayment()
                                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                                },
                            )
                        }
                    }

                    composable(ROUTE_HISTORY) {
                        HistoryScreen(
                            transactionsState = transactionsState,
                            onTransactionClick = { tx -> viewModel.openReceipt(tx) },
                        )
                    }

                    composable(ROUTE_SECURITY) {
                        SecuritySettingsScreen(
                            profileState = profileState,
                            currentLanguage = preferredLanguage,
                            currentCustomBackendUrl = customBackendUrl,
                            statusBanner = statusBanner,
                            onLanguageChange = { code ->
                                onApplyLanguage(code)
                                viewModel.setLanguage(code)
                            },
                            onSaveSettings = { bio, daily, perTxn, url ->
                                viewModel.saveSecuritySettings(bio, daily, perTxn, url)
                            },
                            onSignOut = {
                                signOutUser(credentialManager, scope)
                            },
                        )
                    }
                }
            }
        }
    }

    val receiptTx = selectedReceiptTx
    if (receiptTx != null) {
        ReceiptDetailDialog(
            tx = receiptTx,
            onSharePdf = { sharePdfReceipt(receiptTx) },
            onVerifyStatus = {
                viewModel.closeReceipt()
                viewModel.verifyTransactionWithPsp(receiptTx)
            },
            onDismiss = { viewModel.closeReceipt() },
        )
    }
}
