package com.apiguanli.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.apiguanli.app.chat.ChatStore
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.probe.CheckStore
import com.apiguanli.app.ui.AppTheme
import com.apiguanli.app.ui.HomeScreen
import com.apiguanli.app.ui.LoginScreen
import com.apiguanli.app.ui.Prefs
import com.apiguanli.app.ui.SearchScreen
import com.apiguanli.app.ui.SiteScreen
import com.apiguanli.app.ui.chat.ChatScreen
import com.apiguanli.app.ui.chat.ChatsScreen
import com.apiguanli.app.ui.check.CheckRunScreen
import com.apiguanli.app.ui.check.ChecksScreen
import com.apiguanli.app.ui.check.NewCheckScreen

private data class TopTab(val route: String, val label: String, val icon: ImageVector)

private val topTabs = listOf(
    TopTab("home", "站点", Icons.Default.Dns),
    TopTab("chats", "对话", Icons.AutoMirrored.Filled.Chat),
    TopTab("search", "比价", Icons.Default.Search),
    TopTab("checks", "体检", Icons.Default.FactCheck),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SiteStore.init(applicationContext)
        ChatStore.init(applicationContext)
        CheckStore.init(applicationContext)
        Prefs.init(applicationContext)
        DebugHooks.handle(intent, lifecycleScope)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                val showBar = topTabs.any { it.route == route }

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (showBar) NavigationBar {
                            topTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = route == tab.route,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    // The bottom bar already covers the system navigation inset.
                    val modifier = Modifier.padding(padding)
                        .let { if (showBar) it.consumeWindowInsets(WindowInsets.navigationBars) else it }
                    NavHost(navController = nav, startDestination = "home", modifier = modifier) {
                        composable("home") {
                            HomeScreen(
                                onAdd = { nav.navigate("login") },
                                onOpen = { id -> nav.navigate("site/$id") },
                            )
                        }
                        composable(
                            "login?siteId={siteId}",
                            arguments = listOf(navArgument("siteId") { type = NavType.StringType; nullable = true }),
                        ) { e ->
                            LoginScreen(
                                siteId = e.arguments?.getString("siteId"),
                                onBack = { nav.popBackStack() },
                                onDone = { id -> nav.navigate("site/$id") { popUpTo("home") } },
                            )
                        }
                        composable("site/{siteId}") { e ->
                            SiteScreen(
                                siteId = e.arguments?.getString("siteId").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onRelogin = { id -> nav.navigate("login?siteId=$id") },
                                onChat = { convId -> nav.navigate("chat/$convId") },
                                onCheck = { model -> nav.navigate("check/new?model=" + android.net.Uri.encode(model)) },
                            )
                        }
                        composable("chats") {
                            ChatsScreen(onOpen = { id -> nav.navigate("chat/$id") })
                        }
                        composable("chat/{convId}") { e ->
                            ChatScreen(convId = e.arguments?.getString("convId").orEmpty(), onBack = { nav.popBackStack() })
                        }
                        composable("search") {
                            SearchScreen(
                                onChat = { convId -> nav.navigate("chat/$convId") },
                                onCheck = { model -> nav.navigate("check/new?model=" + android.net.Uri.encode(model)) },
                            )
                        }
                        composable("checks") {
                            ChecksScreen(onNew = { nav.navigate("check/new") }, onOpen = { id -> nav.navigate("check/$id") })
                        }
                        composable(
                            "check/new?model={model}",
                            arguments = listOf(navArgument("model") { type = NavType.StringType; nullable = true }),
                        ) { e ->
                            NewCheckScreen(
                                initialModel = e.arguments?.getString("model").orEmpty(),
                                onBack = { nav.popBackStack() },
                                onStarted = { id -> nav.navigate("check/$id") { popUpTo("check/new?model={model}") { inclusive = true } } },
                            )
                        }
                        composable("check/{runId}") { e ->
                            CheckRunScreen(runId = e.arguments?.getString("runId").orEmpty(), onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
