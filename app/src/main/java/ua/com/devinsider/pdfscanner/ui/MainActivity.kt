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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsState()
            
            MyApplicationTheme(darkTheme = isDarkMode ?: false) {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.DOCUMENTS) }

                Scaffold(
                    bottomBar = {
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
}
