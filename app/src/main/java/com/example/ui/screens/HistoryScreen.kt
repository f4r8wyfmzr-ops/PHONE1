package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.ui.components.TransactionRowCard
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    transactionsState: UiState<List<UpiTransaction>>,
    onTransactionClick: (UpiTransaction) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.history_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_input"),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val filters = listOf(
                    "ALL" to stringResource(R.string.history_filter_all),
                    "VERIFIED" to stringResource(R.string.history_filter_verified),
                    "PENDING" to stringResource(R.string.history_filter_pending),
                    "FAILED" to stringResource(R.string.history_filter_failed),
                )
                filters.forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFilter == key,
                        onClick = { selectedFilter = key },
                        label = { Text(label) },
                        modifier = Modifier.testTag("history_filter_$key"),
                    )
                }
            }

            when (transactionsState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = transactionsState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                is UiState.Success -> {
                    val filtered = remember(transactionsState.data, searchQuery, selectedFilter) {
                        transactionsState.data.filter { tx ->
                            val matchesSearch = searchQuery.isBlank() ||
                                tx.payeeName.contains(searchQuery, ignoreCase = true) ||
                                tx.payeeVpa.contains(searchQuery, ignoreCase = true) ||
                                tx.utrNumber.contains(searchQuery, ignoreCase = true)

                            val matchesFilter = when (selectedFilter) {
                                "VERIFIED" -> tx.status == TransactionStatus.VERIFIED_SUCCESS && tx.pspVerified
                                "PENDING" -> tx.status == TransactionStatus.PENDING_VERIFICATION ||
                                    tx.status == TransactionStatus.AWAITING_PSP_CONFIRMATION ||
                                    tx.status == TransactionStatus.INITIATED
                                "FAILED" -> tx.status == TransactionStatus.FAILED ||
                                    tx.status == TransactionStatus.BLOCKED_FRAUD ||
                                    tx.status == TransactionStatus.USER_CANCELLED ||
                                    tx.status == TransactionStatus.PSP_UNAVAILABLE
                                else -> true
                            }
                            matchesSearch && matchesFilter
                        }
                    }

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.home_empty_txns),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(filtered, key = { it.id }) { tx ->
                                TransactionRowCard(tx = tx, onClick = { onTransactionClick(tx) })
                            }
                        }
                    }
                }
            }
        }
    }
}
