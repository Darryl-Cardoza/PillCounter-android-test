package com.rite.pillcounting.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rite.pillcounting.R
import com.rite.pillcounting.core.hl7.imageWebService.NetworkUtils
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.utils.common.NetworkUtils.rememberIsNetworkAvailable
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.feature.settings.presentation.viewmodel.PmsTestConnectionState
import com.rite.pillcounting.ui.theme.LocalExtendedColors

@Composable
fun ConnectionInfoScreen(
    onBackClick: () -> Unit,
    viewModel: MainActivityViewModel = hiltViewModel(),
    listenerPort: String = HL7Config().serverPort.toString(),
) {
    val extendedColors = LocalExtendedColors.current
    val testConnectionState by viewModel.pmsTestConnectionState.collectAsState()
    val pmsConnection by viewModel.pmsConnection.collectAsState()
    val pmsIpAddress = pmsConnection.first
    val pmsPort = pmsConnection.second

    // Live network state: reflects Wi-Fi connect/disconnect immediately, no manual refresh.
    val isNetworkAvailable = rememberIsNetworkAvailable()

    // Device IP is tied to the active network, so re-read it whenever connectivity changes.
    val deviceIpAddress = remember(isNetworkAvailable) { NetworkUtils.getLocalIpAddress().orEmpty() }

    val pmsNotConfigured = pmsIpAddress.isBlank() || pmsPort.isBlank()

    // Screen-scoped result: reset whenever this screen is (re-)entered so a stale
    // success/failure from a previous visit is never shown.
    DisposableEffect(Unit) {
        viewModel.resetPmsTestConnectionState()
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.secondaryBackground)
            // Swallow all touches so this full-screen overlay blocks clicks on
            // whatever is rendered underneath it in the enclosing Box.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            BackButton(onClick = onBackClick)
            Text(
                text = stringResource(R.string.connection_info_title),
                style = MaterialTheme.typography.bodyMedium,
                color = extendedColors.textColor
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(text = stringResource(R.string.this_device_dispenser_server))

            ConnectionInfoRow(
                label = stringResource(R.string.ip_address),
                value = deviceIpAddress.ifBlank { stringResource(R.string.no_wifi_connection) },
                isError = !isNetworkAvailable
            )

            ConnectionInfoRow(
                label = stringResource(R.string.listener_port),
                value = listenerPort
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                modifier = Modifier.padding(top = 16.dp)
            )

            SectionHeader(
                text = stringResource(R.string.pms_server),
                modifier = Modifier.padding(top = 8.dp)
            )

            ConnectionInfoRow(
                label = stringResource(R.string.pms_ip_address),
                value = pmsIpAddress.ifBlank { "—" },
                isError = pmsIpAddress.isBlank()
            )

            ConnectionInfoRow(
                label = stringResource(R.string.pms_port),
                value = pmsPort.ifBlank { "—" },
                isError = pmsPort.isBlank()
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isTesting = testConnectionState is PmsTestConnectionState.Testing
                val canTest = isNetworkAvailable && !pmsNotConfigured && !isTesting

                Button(
                    onClick = { viewModel.testPmsConnection() },
                    enabled = canTest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(
                        text = if (isTesting) {
                            stringResource(R.string.testing_connection)
                        } else {
                            stringResource(R.string.test_connection)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                when {
                    !isNetworkAvailable -> {
                        Text(
                            text = stringResource(R.string.check_wifi_before_testing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    pmsNotConfigured -> {
                        Text(
                            text = stringResource(R.string.pms_not_configured),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    else -> when (val state = testConnectionState) {
                        is PmsTestConnectionState.Success -> {
                            Text(
                                text = stringResource(R.string.connection_successful, pmsIpAddress),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        is PmsTestConnectionState.Failed -> {
                            Text(
                                text = stringResource(R.string.connection_failed_reason, state.reason),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalExtendedColors.current.textColor.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun ConnectionInfoRow(label: String, value: String, isError: Boolean = false) {
    val extendedColors = LocalExtendedColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = extendedColors.textColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}
