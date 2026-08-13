package ua.com.devinsider.pdfscanner.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import dagger.hilt.android.AndroidEntryPoint
import ua.com.devinsider.pdfscanner.ui.components.BottomNavBar
import ua.com.devinsider.pdfscanner.ui.components.BottomTab
import ua.com.devinsider.pdfscanner.ui.screens.DocumentListScreen
import ua.com.devinsider.pdfscanner.ui.screens.PdfViewerScreen
import ua.com.devinsider.pdfscanner.ui.screens.ToolsScreen
import ua.com.devinsider.pdfscanner.ui.theme.MyApplicationTheme
import ua.com.devinsider.pdfscanner.ui.viewmodels.MainViewModel
import ua.com.devinsider.pdfscanner.BuildConfig
import com.ironsource.mediationsdk.IronSource

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Unity LevelPlay test suite and debug mode for development
        // IronSource.setMetaData("is_test_suite", "enable")
        // IronSource.setAdaptersDebug(true)
        
        // TODO: The IronSource App Key is now read from local.properties to keep it out of public repositories.
        // Add IRONSOURCE_APP_KEY="your_key" to your local.properties file.
        IronSource.init(this, BuildConfig.IRONSOURCE_APP_KEY)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            
            MyApplicationTheme(darkTheme = isDarkMode ?: false) {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.DOCUMENTS) }

                androidx.compose.runtime.DisposableEffect(navController) {
                    val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                        val screenName = when {
                            destination.route?.startsWith("viewer") == true -> "PdfViewerScreen"
                            else -> destination.route ?: "UnknownScreen"
                        }
                        ua.com.devinsider.pdfscanner.utils.AnalyticsHelper.logScreenView(applicationContext, screenName)
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose {
                        navController.removeOnDestinationChangedListener(listener)
                    }
                }

                Scaffold(
                    bottomBar = {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route
                        val isViewer = currentRoute?.startsWith("viewer") == true

                        androidx.compose.foundation.layout.Column {
                            if (!isViewer) {
                                ua.com.devinsider.pdfscanner.ui.components.LevelPlayBanner(
                                    bannerSize = com.ironsource.mediationsdk.ISBannerSize.BANNER
                                )
                            }
                            BottomNavBar(
                                currentTab = currentTab,
                                onTabSelected = { tab ->
                                    currentTab = tab
                                    navController.navigate(tab.name) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { paddingValues ->
                    NavHost(
                        navController = navController,
                        startDestination = BottomTab.DOCUMENTS.name,
                        modifier = Modifier.padding(paddingValues)
                    ) {
                        composable(BottomTab.DOCUMENTS.name) {
                            DocumentListScreen(
                                viewModel = viewModel,
                                filter = { true },
                                onNavigateToViewer = { path ->
                                    navController.navigate("viewer/${URLEncoder.encode(path, StandardCharsets.UTF_8.toString())}")
                                }
                            )
                        }
                        composable(BottomTab.RECENT.name) {
                            DocumentListScreen(
                                viewModel = viewModel,
                                filter = { it.isCreatedByApp },
                                onNavigateToViewer = { path ->
                                    navController.navigate("viewer/${URLEncoder.encode(path, StandardCharsets.UTF_8.toString())}")
                                }
                            )
                        }
                        composable(BottomTab.BOOKMARKS.name) {
                            DocumentListScreen(
                                viewModel = viewModel,
                                filter = { it.isBookmarked },
                                onNavigateToViewer = { path ->
                                    navController.navigate("viewer/${URLEncoder.encode(path, StandardCharsets.UTF_8.toString())}")
                                }
                            )
                        }
                        composable(BottomTab.TOOLS.name) {
                            ToolsScreen(viewModel)
                        }
                        composable(
                            route = "viewer/{filePath}",
                            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val filePath = URLDecoder.decode(backStackEntry.arguments?.getString("filePath") ?: "", StandardCharsets.UTF_8.toString())
                            PdfViewerScreen(filePath = filePath, navController = navController)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        IronSource.onResume(this)
    }

    override fun onPause() {
        super.onPause()
        IronSource.onPause(this)
    }
}
