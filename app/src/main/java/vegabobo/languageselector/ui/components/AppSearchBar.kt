package vegabobo.languageselector.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vegabobo.languageselector.R
import vegabobo.languageselector.ui.screen.main.AppLabels

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(
    modifier: Modifier = Modifier,
    placeholder: String = "",
    query: String,
    onUpdatedValue: (String) -> Unit,
    onSearch: (String) -> Unit,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedLabels: List<AppLabels>,
    onSelectedLabelsChange: (AppLabels) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .semantics { isTraversalGroup = true }
            .then(modifier),
    ) {
        if (isExpanded && query.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                FilterLabel(
                    title = stringResource(id = R.string.filter_show_system),
                    onClick = { onSelectedLabelsChange(AppLabels.SYSTEM_APP) },
                    isSelected = selectedLabels.contains(AppLabels.SYSTEM_APP),
                )
                Spacer(Modifier.padding(4.dp))
                FilterLabel(
                    title = stringResource(id = R.string.filter_show_modified),
                    onClick = { onSelectedLabelsChange(AppLabels.MODIFIED) },
                    isSelected = selectedLabels.contains(AppLabels.MODIFIED),
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            SearchBarDefaults.InputField(
                modifier = Modifier.fillMaxWidth(),
                query = query,
                onQueryChange = onUpdatedValue,
                onSearch = onSearch,
                expanded = isExpanded,
                onExpandedChange = onExpandedChange,
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = { Row { actions() } },
            )
        }
    }

    BackHandler(enabled = isExpanded) {
        if (query.isNotBlank()) {
            onUpdatedValue("")
        } else {
            onExpandedChange(false)
        }
    }
}
