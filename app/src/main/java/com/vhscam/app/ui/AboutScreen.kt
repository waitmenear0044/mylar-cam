package com.vhscam.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vhscam.app.AppInfo
import com.vhscam.app.R

/**
 * «О приложении»: блок Credits — логотип автора, название и версия, автор, ссылка на Telegram-канал (логотип ведёт
 * туда же), лицензия и сторонние материалы. Прокручивается (маленький экран, повёрнутый интерфейс).
 */
@Composable
fun AboutContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val url = stringResource(R.string.about_channel_url)
    val noHandler = stringResource(R.string.about_no_handler)
    val openChannel = { openLink(context, url, noHandler) }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Grid * 2, vertical = Dimens.Grid * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Grid),
    ) {
        Image(
            painterResource(R.drawable.wmn_logo),
            contentDescription = stringResource(R.string.about_logo_description),
            modifier = Modifier
                .size(Dimens.IconButton * 2)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = openChannel),
        )
        Text(AppInfo.NAME, color = TextPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Dimens.Grid))
        Text(AppInfo.LABEL.removePrefix(AppInfo.NAME).trim(), color = TextSecondary, fontSize = 14.sp)
        Text(stringResource(R.string.about_author), color = TextPrimary, fontSize = 16.sp, modifier = Modifier.padding(top = Dimens.Grid))
        Text(
            stringResource(R.string.about_channel),
            color = Accent,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = openChannel)
                .padding(horizontal = Dimens.Grid, vertical = Dimens.Grid),
        )
        Text(stringResource(R.string.about_license), color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Dimens.Grid * 2))
        Text(stringResource(R.string.about_third_party_title), color = TextPrimary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Dimens.Grid))
        Text(stringResource(R.string.about_third_party_text), color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}


/** Ссылка во внешнем приложении (Telegram или браузер); если открыть нечем — короткое сообщение, без падения. */
private fun openLink(context: Context, url: String, noHandler: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, noHandler, Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, noHandler, Toast.LENGTH_SHORT).show()
    }
}
