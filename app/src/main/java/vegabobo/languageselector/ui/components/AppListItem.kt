package vegabobo.languageselector.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vegabobo.languageselector.R
import vegabobo.languageselector.ui.screen.main.AppInfo

private val SystemLabelBackground = Color(0xFFE0E0E0)
private val SystemLabelContent = Color(0xFF424242)
private val UserLabelBackground = Color(0xFFBBDEFB)
private val UserLabelContent = Color(0xFF0D47A1)
private val ModifiedLabelBackground = Color(0xFFFFE0B2)
private val ModifiedLabelContent = Color(0xFFE65100)

@Composable
fun AppListItem(
    modifier: Modifier = Modifier,
    app: AppInfo,
    onClickApp: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .clickable { onClickApp(app.pkg) }
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app = app)
        Spacer(modifier = Modifier.padding(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy((-4).dp)
        ) {
            Text(text = app.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(text = app.pkg, fontSize = 12.sp, maxLines = 1)
            Row {
                val (appTypeLabel, appTypeBackground, appTypeContent) = if (app.isSystemApp()) {
                    Triple(
                        stringResource(id = R.string.system_app_label),
                        SystemLabelBackground,
                        SystemLabelContent
                    )
                } else {
                    Triple(
                        stringResource(id = R.string.user_app_label),
                        UserLabelBackground,
                        UserLabelContent
                    )
                }
                TextLabel(
                    text = appTypeLabel,
                    backgroundColor = appTypeBackground,
                    contentColor = appTypeContent
                )
                if (app.isModified()) {
                    TextLabel(
                        text = stringResource(id = R.string.label_modified),
                        backgroundColor = ModifiedLabelBackground,
                        contentColor = ModifiedLabelContent
                    )
                }
            }
        }
    }
}

@Composable
fun TextLabel(
    text: String,
    backgroundColor: Color,
    contentColor: Color
) {
    Box(Modifier.padding(top = 2.dp, end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
        ) {
            Text(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                text = text,
                maxLines = 1,
                lineHeight = 16.sp,
                fontSize = 10.sp,
                color = contentColor
            )
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo) {
    val context = LocalContext.current
    val cacheKey = "${app.pkg}:${app.iconVersion}"
    val bitmap by produceState(
        initialValue = AppIconMemoryCache.get(cacheKey),
        key1 = cacheKey,
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                AppIconMemoryCache.get(cacheKey) ?: runCatching {
                    val applicationInfo = context.packageManager.getApplicationInfo(app.pkg, 0)
                    context.packageManager.getApplicationIcon(applicationInfo).toBitmap(
                        width = APP_ICON_BITMAP_SIZE,
                        height = APP_ICON_BITMAP_SIZE,
                    )
                }.getOrNull()?.also { AppIconMemoryCache.put(cacheKey, it) }
            }
        }
    }

    val loadedBitmap = bitmap
    if (loadedBitmap == null) {
        Image(
            modifier = Modifier.size(32.dp),
            painter = painterResource(R.drawable.icon_placeholder),
            contentDescription = null,
            contentScale = ContentScale.Fit,
        )
    } else {
        Image(
            modifier = Modifier.size(32.dp),
            bitmap = loadedBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
        )
    }
}

private object AppIconMemoryCache : LruCache<String, Bitmap>(APP_ICON_CACHE_SIZE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int {
        return value.allocationByteCount / 1024
    }
}

private const val APP_ICON_BITMAP_SIZE = 96
private const val APP_ICON_CACHE_SIZE_KB = 8 * 1024
