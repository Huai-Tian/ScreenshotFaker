package fake.screenshot.pages

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fake.screenshot.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplicationCompose() {
    TopAppBar(title = { Text(stringResource(R.string.application)) })
}
