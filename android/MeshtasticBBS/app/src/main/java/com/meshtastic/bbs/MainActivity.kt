package com.meshtastic.bbs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.ui.screens.BoardListScreen
import com.meshtastic.bbs.ui.screens.ComposeScreen
import com.meshtastic.bbs.ui.screens.LoginScreen
import com.meshtastic.bbs.ui.screens.MeshChatScreen
import com.meshtastic.bbs.ui.screens.PostListScreen
import com.meshtastic.bbs.ui.screens.PostViewScreen
import com.meshtastic.bbs.ui.screens.ServerSelectScreen
import com.meshtastic.bbs.ui.screens.ServerHostScreen
import com.meshtastic.bbs.ui.theme.MeshtasticBBSTheme
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

class MainActivity : ComponentActivity() {

    private val vm: BbsViewModel by viewModels()

    private val btPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else emptyArray()

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        vm.setBtPermissionGranted(results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBtPermissions()

        setContent {
            MeshtasticBBSTheme {
                if (BuildConfig.SERVER_BUILD) {
                    ServerHostScreen(onExit = ::finish)
                } else {
                    val screen by vm.screen.collectAsStateWithLifecycle()
                    when (val s = screen) {
                        is Screen.Login -> LoginScreen(vm)
                        is Screen.ServerSelect -> ServerSelectScreen(vm)
                        is Screen.Boards -> BoardListScreen(vm)
                        is Screen.Posts -> PostListScreen(vm, s.boardName)
                        is Screen.PostView -> PostViewScreen(vm, s.postId)
                        is Screen.Compose -> ComposeScreen(
                            vm = vm,
                            boardName = s.boardName,
                            replyToPostId = s.replyToPostId,
                            editPostId = s.editPostId,
                        )
                        is Screen.MeshChat -> MeshChatScreen(vm)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBtPermissions()
    }

    private fun checkBtPermissions() {
        if (btPermissions.isEmpty()) {
            vm.setBtPermissionGranted(true)
            return
        }
        val allGranted = btPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            vm.setBtPermissionGranted(true)
        } else {
            vm.setBtPermissionGranted(false)
            btPermissionLauncher.launch(btPermissions)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (BuildConfig.SERVER_BUILD) {
            finish()
            return
        }

        when (val s = vm.screen.value) {
            is Screen.Login -> if (vm.state.value.isConnected) vm.cancelConnect() else finish()
            is Screen.ServerSelect -> finish()
            is Screen.Boards -> finish()
            is Screen.Posts -> vm.navigateTo(Screen.Boards)
            is Screen.PostView -> vm.returnToPostList(vm.state.value.currentBoardName)
            is Screen.Compose -> {
                val back = when {
                    s.replyToPostId != null -> Screen.PostView(s.replyToPostId)
                    s.editPostId != null -> Screen.PostView(s.editPostId)
                    else -> Screen.Posts(s.boardName)
                }
                vm.navigateTo(back)
            }
            is Screen.MeshChat -> vm.navigateTo(Screen.Boards)
        }
    }
}
