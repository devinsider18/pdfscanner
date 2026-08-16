package ua.com.devinsider.pdfscanner.ui.components

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ironsource.mediationsdk.ISBannerSize
import com.ironsource.mediationsdk.IronSource
import com.ironsource.mediationsdk.IronSourceBannerLayout
import android.widget.FrameLayout
import android.view.Gravity

@Composable
fun LevelPlayBanner(
    modifier: Modifier = Modifier,
    bannerSize: ISBannerSize = ISBannerSize.BANNER,
    isAdsSdkReady: Boolean = false
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    if (activity == null || !isAdsSdkReady) return

    val bannerContainer = remember { FrameLayout(activity) }

    DisposableEffect(Unit) {
        val banner = IronSource.createBanner(activity, bannerSize)
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = Gravity.CENTER
        bannerContainer.addView(banner, layoutParams)
        
        IronSource.loadBanner(banner)
        
        onDispose {
            IronSource.destroyBanner(banner)
            bannerContainer.removeAllViews()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { bannerContainer }
    )
}
