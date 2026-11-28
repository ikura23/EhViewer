package com.hippo.ehviewer.ui.login

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.util.displayString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import splitties.systemservices.clipboardManager

/**
 * Cookie 登录页面，支持手动输入、剪贴板一键智能识别以及在首栏直接粘贴整段文本后自动拆分填充 `ipb_member_id`、`ipb_pass_hash`、`igneous`，并请求表站校验身份有效性。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.CookieSignInScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val memberId = rememberTextFieldState()
    val passHash = rememberTextFieldState()
    val igneous = rememberTextFieldState()

    var memberIdErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var passHashErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var igneousErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }
    var isProgressIndicatorVisible by rememberSaveable { mutableStateOf(false) }
    var signInJob by remember { mutableStateOf<Job?>(null) }

    val pasteSuccessMsg = stringResource(R.string.cookie_sign_in_paste_success)
    val pasteEmptyMsg = stringResource(R.string.cookie_sign_in_paste_empty)

    fun applyParsedCookies(parsed: ParsedIdentityCookies): Boolean {
        if (parsed.isEmpty) return false
        parsed.ipbMemberId?.let {
            memberId.setTextAndPlaceCursorAtEnd(it)
            memberIdErrorRes = null
        }
        parsed.ipbPassHash?.let {
            passHash.setTextAndPlaceCursorAtEnd(it)
            passHashErrorRes = null
        }
        parsed.igneous?.let {
            igneous.setTextAndPlaceCursorAtEnd(it)
            igneousErrorRes = null
        }
        return true
    }

    // 若用户直接将整段 Cookie 粘贴到第一个输入框中，自动识别并拆分填充各字段
    LaunchedEffect(memberId) {
        snapshotFlow { memberId.text.toString() }.collectLatest { current ->
            val parsed = parseIdentityCookies(current)
            if (parsed.ipbMemberId != null && parsed.ipbPassHash != null) {
                applyParsedCookies(parsed)
                snackbar(pasteSuccessMsg)
            }
        }
    }

    fun pasteFromClipboard() {
        val text = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
        }.getOrNull().orEmpty()
        val parsed = parseIdentityCookies(text)
        if (applyParsedCookies(parsed)) {
            launch { snackbar(pasteSuccessMsg) }
        } else {
            launch { snackbar(pasteEmptyMsg) }
        }
    }

    fun signIn() {
        if (signInJob?.isActive == true) return
        val cleanMemberId = sanitizeCookieValue(memberId.text.toString())
        val cleanPassHash = sanitizeCookieValue(passHash.text.toString())
        val cleanIgneous = sanitizeCookieValue(igneous.text.toString())

        memberIdErrorRes = when {
            cleanMemberId.isEmpty() -> R.string.error_cookie_cannot_empty
            !isValidCookieValue(cleanMemberId) -> R.string.error_cookie_invalid_format
            else -> null
        }
        passHashErrorRes = when {
            cleanPassHash.isEmpty() -> R.string.error_cookie_cannot_empty
            !isValidCookieValue(cleanPassHash) -> R.string.error_cookie_invalid_format
            else -> null
        }
        igneousErrorRes = if (cleanIgneous.isNotEmpty() && !isValidCookieValue(cleanIgneous)) {
            R.string.error_cookie_invalid_format
        } else {
            null
        }
        if (memberIdErrorRes != null || passHashErrorRes != null || igneousErrorRes != null) return

        focusManager.clearFocus()
        isProgressIndicatorVisible = true

        signInJob = launchIO {
            runSuspendCatching {
                EhUtils.signOut()
                EhCookieStore.setIdentityCookies(
                    ipbMemberId = cleanMemberId,
                    ipbPassHash = cleanPassHash,
                    igneous = cleanIgneous.ifEmpty { null },
                )
                // 请求表站配置页面验证身份 Cookie 是否真实有效
                EhEngine.getUConfig(EhUrl.SITE_E)
                postLogin().await()
            }.onFailure { error ->
                EhUtils.signOut()
                withUIContext {
                    focusManager.clearFocus()
                    isProgressIndicatorVisible = false
                    awaitConfirmationOrCancel(
                        confirmText = R.string.get_it,
                        title = R.string.sign_in_failed,
                        showCancelButton = false,
                        text = {
                            Text(text = error.displayString())
                        },
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.sign_in_via_cookies)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(dimensionResource(com.hippo.ehviewer.R.dimen.keyline_margin)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val maxWidth = dimensionResource(com.hippo.ehviewer.R.dimen.single_max_width)
                Column(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.cookie_sign_in_tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = ::pasteFromClipboard,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(text = stringResource(R.string.cookie_sign_in_paste_from_clipboard))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    @Composable
                    fun CookieInputField(
                        state: TextFieldState,
                        labelRes: Int,
                        errorRes: Int?,
                        imeAction: ImeAction,
                        modifier: Modifier = Modifier,
                        onDone: (() -> Unit)? = null,
                    ) {
                        OutlinedTextField(
                            state = state,
                            modifier = modifier.fillMaxWidth(),
                            label = { Text(stringResource(labelRes)) },
                            supportingText = errorRes?.let { res -> { Text(stringResource(res)) } },
                            trailingIcon = errorRes?.let { { Icon(imageVector = Icons.Filled.Info, contentDescription = null) } },
                            keyboardOptions = KeyboardOptions(imeAction = imeAction),
                            onKeyboardAction = onDone?.let { action -> { action() } },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            isError = errorRes != null,
                        )
                    }
                    CookieInputField(
                        state = memberId,
                        labelRes = R.string.cookie_ipb_member_id,
                        errorRes = memberIdErrorRes,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    CookieInputField(
                        state = passHash,
                        labelRes = R.string.cookie_ipb_pass_hash,
                        errorRes = passHashErrorRes,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    CookieInputField(
                        state = igneous,
                        labelRes = R.string.cookie_igneous_optional,
                        errorRes = igneousErrorRes,
                        imeAction = ImeAction.Done,
                        modifier = Modifier.padding(bottom = 20.dp),
                        onDone = ::signIn,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = ::signIn,
                            enabled = !isProgressIndicatorVisible,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.sign_in))
                        }
                    }
                }
            }
            if (isProgressIndicatorVisible) {
                CircularWavyProgressIndicator()
            }
        }
    }
}
